package studio.otherstuff.ambulando.core.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.MemoryBackgroundStore
import studio.otherstuff.ambulando.core.support.MemorySecretStore
import studio.otherstuff.ambulando.core.support.MemorySessionStore
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.UnavailableSecretStore
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.File

/**
 * Identity retention across relaunches and unreadable storage: an update or a launch that cannot read protected
 * storage must keep the imported identity, never treat it as missing and never accept an import over it.
 */
class IdentityRetentionTests {
    private fun vaultFile(data: File) = File(data, "Identity/vault.json")

    /** Makes a file unreadable for the duration of [block] (skipped when running with privileges that ignore it). */
    private suspend fun withUnreadable(file: File, block: suspend () -> Unit) {
        file.setReadable(false, false)
        try {
            assumeTrue("running with privileges that ignore file permissions", runCatching { file.readBytes() }.isFailure)
            block()
        } finally {
            file.setReadable(true, true)
        }
    }

    @Test fun relaunchKeepsTheIdentityAndTheTrustedSession() = runBlocking {
        val data = tempDir()
        val secrets = MemorySecretStore()
        val session = MemorySessionStore()
        IdentityService(vaultFile(data), secrets, sessionStore = session).importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val identity = IdentityService(vaultFile(data), secrets, sessionStore = session)
        assertEquals(IdentityState.Locked(TestKeys.NPUB), identity.state())
        assertTrue("the trusted-device session survives the relaunch", identity.resumeSession())
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), identity.state())
    }

    @Test fun unreadableVaultIsUnavailableNotMissingAndRecovers() = runBlocking {
        val data = tempDir()
        val secrets = MemorySecretStore()
        val session = MemorySessionStore()
        val file = vaultFile(data)
        IdentityService(file, secrets, sessionStore = session).importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val original = file.readBytes()
        lateinit var identity: IdentityService
        withUnreadable(file) {
            identity = IdentityService(file, secrets, sessionStore = session)
            assertFalse(identity.resumeSession())
            assertEquals(IdentityState.Unavailable, identity.state())
            assertNull(identity.state().npub)
            assertEquals(VaultReadProblem.UNREADABLE, identity.vaultProblem())
            // A fresh import would replace the account: refused while any vault exists.
            expectError(IdentityError.Exists) { identity.importIdentity(TestKeys.otherNsec(), TestKeys.PIN) }
        }
        // Storage readable again: the same process reads the vault and resumes the trusted-device session.
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), identity.refreshAvailability())
        assertArrayEquals("the vault bytes are untouched", original, file.readBytes())
    }

    @Test fun recoveredVaultWithoutSessionIsLocked() = runBlocking {
        val data = tempDir()
        val secrets = MemorySecretStore()
        val file = vaultFile(data)
        IdentityService(file, secrets, sessionStore = MemorySessionStore()).apply {
            importIdentity(TestKeys.NSEC, TestKeys.PIN)
            lock()
        }
        lateinit var identity: IdentityService
        withUnreadable(file) {
            identity = IdentityService(file, secrets, sessionStore = MemorySessionStore())
            assertEquals(IdentityState.Unavailable, identity.state())
        }
        assertEquals(IdentityState.Locked(TestKeys.NPUB), identity.refreshAvailability())
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), identity.unlock(TestKeys.PIN))
    }

    @Test fun unavailablePlatformSecretKeepsTheVault() = runBlocking {
        val data = tempDir()
        val file = vaultFile(data)
        val secrets = MemorySecretStore()
        IdentityService(file, secrets).importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val original = file.readBytes()
        val identity = IdentityService(file, UnavailableSecretStore())
        assertEquals(IdentityState.Locked(TestKeys.NPUB), identity.state())
        expectError(IdentityError.KeychainUnavailable) { identity.unlock(TestKeys.PIN) }
        assertEquals(IdentityState.Locked(TestKeys.NPUB), identity.state())
        assertArrayEquals(original, file.readBytes())
        assertEquals("with the secret readable again the same PIN works", IdentityState.Unlocked(TestKeys.NPUB), IdentityService(file, secrets).unlock(TestKeys.PIN))
    }

    @Test fun refreshDoesNotClobberAnUnlockedIdentity() = runBlocking {
        val identity = IdentityService(vaultFile(tempDir()), MemorySecretStore(), sessionStore = MemorySessionStore())
        identity.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        repeat(3) { assertEquals(IdentityState.Unlocked(TestKeys.NPUB), identity.refreshAvailability()) }
        assertEquals(TestKeys.PUBKEY_HEX, identity.sign(NostrEventTemplate(1, emptyList(), "still signing", 1)).pubkey)
        assertNull(identity.vaultProblem())
    }

    @Test fun damagedVaultIsSurfacedSeparatelyAndKept() = runBlocking {
        val file = vaultFile(tempDir())
        file.parentFile.mkdirs()
        val garbage = "{\"version\":1,\"truncated".toByteArray()
        file.writeBytes(garbage)
        val identity = IdentityService(file, MemorySecretStore(), sessionStore = MemorySessionStore())
        assertEquals(IdentityState.Unavailable, identity.state())
        assertEquals(VaultReadProblem.DAMAGED, identity.vaultProblem())
        expectError(IdentityError.Exists) { identity.importIdentity(TestKeys.NSEC, TestKeys.PIN) }
        assertArrayEquals(garbage, file.readBytes())
    }

    @Test fun importIsBoundToTheExistingConnectionIdentity() = runBlocking {
        val identity = IdentityService(vaultFile(tempDir()), MemorySecretStore())
        expectError(IdentityError.AccountMismatch) { identity.importIdentity(TestKeys.otherNsec(), TestKeys.PIN, requiredNpub = TestKeys.NPUB) }
        assertEquals(IdentityState.None, identity.state())
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), identity.importIdentity(TestKeys.NSEC, TestKeys.PIN, requiredNpub = TestKeys.NPUB))
    }

    /**
     * After process death with the vault unreadable, the user-enabled background read credential still loads (matched
     * by the public-key hint). It reads only; the identity is not shown as unlocked and nothing can be written.
     */
    @Test fun backgroundCredentialStillLoadsWhileTheVaultIsSealed() = runBlocking {
        val data = tempDir()
        val file = vaultFile(data)
        val secrets = MemorySecretStore()
        val background = MemoryBackgroundStore()
        IdentityService(file, secrets, background, MemorySessionStore()).apply {
            importIdentity(TestKeys.NSEC, TestKeys.PIN)
            enableBackgroundRead(TowerOrigin(Fixtures.ORIGIN), listOf(Fixtures.WORKSPACE_ID))
        }
        withUnreadable(file) {
            val identity = IdentityService(file, secrets, background, MemorySessionStore())
            assertTrue("matched by the public-key hint", identity.loadBackgroundRead())
            assertTrue(identity.canReadWhileLocked())
            assertEquals("the UI is not unlocked by the background credential", IdentityState.Unavailable, identity.state())
            assertEquals(VaultReadProblem.UNREADABLE, identity.vaultProblem())
            identity.requireReader(TestKeys.NPUB)
            expectError(IdentityError.Locked) { identity.requireSigner(TestKeys.NPUB) }
        }
    }

    @Test fun hintIsAddedForAVaultFromAnEarlierBuild() = runBlocking {
        val file = vaultFile(tempDir())
        val secrets = MemorySecretStore()
        IdentityService(file, secrets).importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val hint = File(file.parentFile, "vault.pub")
        assertTrue(hint.delete())
        assertEquals(IdentityState.Locked(TestKeys.NPUB), IdentityService(file, secrets).state())
        assertEquals(TestKeys.PUBKEY_HEX, hint.readText())
        assertFalse("the hint holds only the public key", hint.readText().contains("nsec"))
    }
}
