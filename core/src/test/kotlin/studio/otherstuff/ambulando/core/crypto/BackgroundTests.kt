package studio.otherstuff.ambulando.core.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Hex
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.FlakyBackgroundStore
import studio.otherstuff.ambulando.core.support.MemoryBackgroundStore
import studio.otherstuff.ambulando.core.support.MemorySecretStore
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.File

class BackgroundReaderTests {
    private val origin = Fixtures.ORIGIN
    private val base = "$origin/api/v4/flightdeck-pg/workspaces"

    private fun template(url: String, method: String = "GET", payload: Boolean = false, kind: Int = NIP98.KIND): NostrEventTemplate {
        val tags = mutableListOf(listOf("u", url), listOf("method", method))
        if (payload) tags.add(listOf("payload", Sha256Hex.of("x")))
        return NostrEventTemplate(kind, tags, "")
    }

    @Test fun policyAllowsOnlyPinnedReadRequests() {
        val policy = ReadPolicy(origin, setOf(Fixtures.WORKSPACE_ID))
        val w = Fixtures.WORKSPACE_ID
        assertTrue(policy.allows(template(base)))
        assertTrue(policy.allows(template("$base/$w/events/stream?cursor=abc")))
        assertTrue(policy.allows(template("$base/$w/channels/${Fixtures.CHANNEL_1}/messages?limit=200")))
        assertFalse("unpinned workspace", policy.allows(template("$base/${Fixtures.WORKSPACE_ID_2}/events")))
        assertFalse("write", policy.allows(template("$base/$w/channels/x/messages", method = "POST", payload = true)))
        assertFalse("delete", policy.allows(template("$base/$w/messages/x", method = "DELETE")))
        assertFalse("other host", policy.allows(template("https://evil.example.com/api/v4/flightdeck-pg/workspaces")))
        assertFalse("plain HTTP", policy.allows(template("http://tower.example.com/api/v4/flightdeck-pg/workspaces")))
        assertFalse("explicit other port", policy.allows(template("https://tower.example.com:8443/api/v4/flightdeck-pg/workspaces")))
        assertFalse("credentials", policy.allows(template("https://u:p@tower.example.com/api/v4/flightdeck-pg/workspaces")))
        assertFalse("outside flightdeck-pg", policy.allows(template("$origin/api/v4/storage/$w/content")))
        assertFalse("path traversal", policy.allows(template("$base/$w/../other")))
        assertFalse("prefix lookalike", policy.allows(template("$origin/api/v4/flightdeck-pg/workspacesX")))
        assertFalse(
            "never a chat signature",
            policy.allows(NostrEventTemplate(MessageInstructionSignature.KIND, listOf(listOf("u", base), listOf("method", "GET")), "")),
        )
        assertFalse("content must be empty", policy.allows(NostrEventTemplate(NIP98.KIND, listOf(listOf("u", base), listOf("method", "GET")), "x")))
    }

    @Test fun lockedIdentitySignsReadsOnlyWithEnabledCredentialAndManualLockRevokes() = runBlocking {
        val dir = tempDir()
        val background = MemoryBackgroundStore()
        val secrets = MemorySecretStore()
        val identity = IdentityService(File(dir, "vault.json"), secrets, background)
        identity.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        identity.enableBackgroundRead(TowerOrigin(origin), listOf(Fixtures.WORKSPACE_ID.uppercase(), "not-a-uuid"))
        assertEquals("ids are normalised and filtered", listOf(Fixtures.WORKSPACE_ID), background.read()!!.workspaceIds)

        // Process death: a new service over the same files is locked; the credential loads without the PIN.
        val relaunched = IdentityService(File(dir, "vault.json"), secrets, background)
        assertEquals("UI still sees a locked identity", IdentityState.Locked(TestKeys.NPUB), relaunched.state())
        assertTrue(relaunched.backgroundReadEnabled())
        assertTrue(relaunched.loadBackgroundRead())
        relaunched.requireReader(TestKeys.NPUB)
        expectFailure { relaunched.requireSigner(TestKeys.NPUB) }
        val read = relaunched.sign(template("$base/${Fixtures.WORKSPACE_ID}/events"))
        assertTrue(SignatureVerifier.verify(read))
        assertEquals(TestKeys.PUBKEY_HEX, read.pubkey)
        assertEquals(TestKeys.PUBKEY_HEX, relaunched.publicKeyHex())
        assertEquals(ReadPolicy(origin, setOf(Fixtures.WORKSPACE_ID)), relaunched.backgroundPolicy())
        expectError(IdentityError.Locked) {
            relaunched.sign(
                NostrEventTemplate(
                    MessageInstructionSignature.KIND,
                    MessageInstructionSignature.tags("hi", Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1), "hi",
                ),
            )
        }

        // Manual lock removes the credential: nothing is signed until the next unlock.
        relaunched.lock()
        assertNull(background.read())
        assertFalse(relaunched.canReadWhileLocked())
        expectFailure { relaunched.sign(template(base)) }
        expectFailure { relaunched.requireReader(TestKeys.NPUB) }
    }

    @Test fun credentialOfAnotherIdentityIsIgnored() = runBlocking {
        val background = MemoryBackgroundStore()
        val first = IdentityService(File(tempDir(), "vault.json"), MemorySecretStore(), background)
        first.importIdentity(TestKeys.otherNsec(), TestKeys.PIN)
        first.enableBackgroundRead(TowerOrigin(origin), listOf(Fixtures.WORKSPACE_ID))
        val other = IdentityService(File(tempDir(), "vault.json"), MemorySecretStore(), background)
        other.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        other.lock()   // also deletes the shared item; re-store the first identity's credential
        first.enableBackgroundRead(TowerOrigin(origin), listOf(Fixtures.WORKSPACE_ID))
        assertFalse("a credential for a different key is never used", other.loadBackgroundRead())
    }

    @Test fun updateChangesOnlyAnExistingMatchingCredential() = runBlocking {
        val background = MemoryBackgroundStore()
        val identity = IdentityService(File(tempDir(), "vault.json"), MemorySecretStore(), background)
        identity.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        identity.updateBackgroundWorkspaces(TowerOrigin(origin), listOf(Fixtures.WORKSPACE_ID))
        assertNull("nothing is created by an update", background.read())
        identity.enableBackgroundRead(TowerOrigin(origin), listOf(Fixtures.WORKSPACE_ID))
        identity.updateBackgroundWorkspaces(TowerOrigin("https://other.example.com"), listOf(Fixtures.WORKSPACE_ID_2))
        assertEquals("another origin is ignored", listOf(Fixtures.WORKSPACE_ID), background.read()!!.workspaceIds)
        identity.updateBackgroundWorkspaces(TowerOrigin(origin), listOf(Fixtures.WORKSPACE_ID_2, Fixtures.WORKSPACE_ID))
        assertEquals(listOf(Fixtures.WORKSPACE_ID, Fixtures.WORKSPACE_ID_2), background.read()!!.workspaceIds)
    }

    @Test fun credentialEncodingRoundTripsAndKeepsTheIOSFieldNames() {
        val credential = BackgroundReadCredential(
            publicKeyHex = TestKeys.PUBKEY_HEX, secretKey = Hex.decode(TestKeys.SECRET_HEX)!!, origin = origin,
            workspaceIds = listOf(Fixtures.WORKSPACE_ID), createdAt = "2026-10-01T00:00:00Z",
        )
        val text = String(credential.encode())
        for (key in listOf("\"version\"", "\"publicKeyHex\"", "\"secretKey\"", "\"origin\"", "\"workspaceIds\"", "\"createdAt\"")) {
            assertTrue(key, text.contains(key))
        }
        assertEquals(credential, BackgroundReadCredential.decode(credential.encode()))
        assertFalse("toString never prints the key", credential.toString().contains(TestKeys.SECRET_HEX))
        assertNull(BackgroundReadCredential.decode("garbage".toByteArray()))
    }
}

class BackgroundRevocationTests {
    private val read = NostrEventTemplate(NIP98.KIND, listOf(listOf("u", "${Fixtures.ORIGIN}/api/v4/flightdeck-pg/workspaces"), listOf("method", "GET")), "")

    @Test fun manualLockRevokesEvenWhenDeleteFails() = runBlocking {
        val dir = tempDir()
        val secrets = MemorySecretStore()
        val background = FlakyBackgroundStore()
        val identity = IdentityService(File(dir, "vault.json"), secrets, background)
        identity.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        identity.enableBackgroundRead(TowerOrigin(Fixtures.ORIGIN), listOf(Fixtures.WORKSPACE_ID))
        background.failDeletes = 1
        identity.lock()
        assertTrue("the platform item survived the failed delete", background.stored)
        assertTrue("the failure is reported, not hidden", identity.revocationIncomplete())
        // Same process: reload and sign both refuse.
        assertFalse(identity.loadBackgroundRead())
        expectFailure { identity.sign(read) }
        assertFalse("the reload attempt finished the deletion", background.stored)
        // Relaunch with the item still present (delete failing again): still refused.
        background.write(
            BackgroundReadCredential(
                publicKeyHex = TestKeys.PUBKEY_HEX, secretKey = Hex.decode(TestKeys.SECRET_HEX)!!, origin = Fixtures.ORIGIN,
                workspaceIds = listOf(Fixtures.WORKSPACE_ID), createdAt = "",
            ),
        )
        background.failDeletes = 5
        val relaunched = IdentityService(File(dir, "vault.json"), secrets, background)
        assertFalse(relaunched.loadBackgroundRead())
        assertFalse(relaunched.canReadWhileLocked())
        assertFalse(relaunched.backgroundReadEnabled())
        expectFailure { relaunched.requireReader(TestKeys.NPUB) }
        // Unlock and enable again: the user's explicit choice un-revokes with a fresh credential.
        background.failDeletes = 0
        relaunched.unlock(TestKeys.PIN)
        relaunched.enableBackgroundRead(TowerOrigin(Fixtures.ORIGIN), listOf(Fixtures.WORKSPACE_ID))
        relaunched.lock()
        val fresh = IdentityService(File(dir, "vault.json"), secrets, background)
        assertFalse("a manual lock after re-enabling revokes again", fresh.loadBackgroundRead())
    }

    @Test fun disableAndRemoveFailClosed() = runBlocking {
        for (operation in listOf("disable", "remove")) {
            val dir = tempDir()
            val secrets = MemorySecretStore()
            val background = FlakyBackgroundStore()
            val identity = IdentityService(File(dir, "vault.json"), secrets, background)
            identity.importIdentity(TestKeys.NSEC, TestKeys.PIN)
            identity.enableBackgroundRead(TowerOrigin(Fixtures.ORIGIN), listOf(Fixtures.WORKSPACE_ID))
            background.failDeletes = 1
            if (operation == "disable") identity.disableBackgroundRead() else runCatching { identity.remove() }
            val relaunched = IdentityService(File(dir, "vault.json"), secrets, background)
            assertFalse(operation, relaunched.loadBackgroundRead())
        }
    }

    @Test fun lockedIdentityWithoutCredentialCannotReadOrSign() = runBlocking {
        val identity = makeIdentity(unlocked = false)
        assertFalse(identity.loadBackgroundRead())
        expectError(IdentityError.Locked) { identity.sign(read) }
        expectError(IdentityError.Locked) { identity.publicKeyHex() }
        expectError(IdentityError.Locked) { identity.requireReader(TestKeys.NPUB) }
    }
}
