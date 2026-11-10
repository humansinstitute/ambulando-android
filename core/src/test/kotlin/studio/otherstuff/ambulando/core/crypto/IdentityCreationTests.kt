package studio.otherstuff.ambulando.core.crypto

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.support.*
import java.io.File
import java.nio.file.Files

class IdentityCreationTests {
    private fun file() = File(Files.createTempDirectory("creation").toFile(), "vault.json")

    @Test fun generatedIdentitySignsAndReopensWithSessionOrPIN() = runBlocking {
        val file = file(); val secrets = MemorySecretStore(); val sessions = MemorySessionStore()
        val service = IdentityService(file, secrets, sessionStore = sessions)
        val state = service.createIdentity(TestKeys.PIN) as IdentityState.Unlocked
        val event = service.sign(NostrEventTemplate(1, emptyList(), "creation test", 123))
        assertTrue(SignatureVerifier.verify(event))
        assertFalse(file.readText().contains("nsec"))
        assertEquals(1, secrets.writes)
        val reopened = IdentityService(file, secrets, sessionStore = sessions)
        assertTrue(reopened.resumeSession())
        assertEquals(state, reopened.state())
        reopened.lock()
        val cold = IdentityService(file, secrets, sessionStore = sessions)
        assertFalse(cold.resumeSession())
        assertEquals(IdentityState.Locked(state.npub), cold.state())
        assertEquals(state, cold.unlock(TestKeys.PIN))
    }

    @Test fun parallelTapsCreateExactlyOneIdentityAndKeepItsBytes() = runBlocking {
        val file = file(); val secrets = MemorySecretStore(); val service = IdentityService(file, secrets)
        val results = (1..6).map { async { runCatching { service.createIdentity(TestKeys.PIN) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() == IdentityError.Exists })
        val bytes = file.readBytes()
        assertEquals(IdentityError.Exists, runCatching { service.createIdentity(TestKeys.PIN) }.exceptionOrNull())
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1, secrets.writes)
    }

    @Test fun importAndDamagedVaultAreNeverReplacedByCreation() = runBlocking {
        val file = file(); val secrets = MemorySecretStore(); val service = IdentityService(file, secrets)
        service.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val bytes = file.readBytes()
        assertEquals(IdentityError.Exists, runCatching { service.createIdentity(TestKeys.PIN) }.exceptionOrNull())
        assertArrayEquals(bytes, file.readBytes())
        file.writeText("damaged")
        assertEquals(IdentityError.Exists, runCatching { IdentityService(file, secrets).createIdentity(TestKeys.PIN) }.exceptionOrNull())
        assertEquals("damaged", file.readText())
    }

    @Test fun invalidPINAndUnavailableStorageDoNotCreateVaultAndRetryWorks() = runBlocking {
        val file = file(); val good = MemorySecretStore()
        var fails = true
        val store = object : VaultSecretStore {
            override fun read() = good.read()
            override fun write(data: ByteArray) { if (fails) throw IdentityError.KeychainUnavailable; good.write(data) }
        }
        val service = IdentityService(file, store)
        assertEquals(IdentityError.InvalidPIN, runCatching { service.createIdentity("abc") }.exceptionOrNull())
        assertEquals(IdentityError.KeychainUnavailable, runCatching { service.createIdentity(TestKeys.PIN) }.exceptionOrNull())
        assertEquals(IdentityState.None, service.state()); assertFalse(file.exists())
        fails = false
        assertTrue(service.createIdentity(TestKeys.PIN) is IdentityState.Unlocked)
    }

    @Test fun failedVaultWriteLeavesNoIdentityAndCanBeRetried() = runBlocking {
        val root = Files.createTempDirectory("creation-blocked").toFile()
        val parent = File(root, "blocked").apply { writeText("file, not directory") }
        val file = File(parent, "vault.json"); val secrets = MemorySecretStore()
        val service = IdentityService(file, secrets)
        assertTrue(runCatching { service.createIdentity(TestKeys.PIN) }.isFailure)
        assertEquals(IdentityState.None, service.state())
        parent.delete(); parent.mkdirs()
        assertTrue(service.createIdentity(TestKeys.PIN) is IdentityState.Unlocked)
    }

    @Test fun independentCreatesHaveDifferentPublicKeys() = runBlocking {
        val one = IdentityService(file(), MemorySecretStore()).createIdentity(TestKeys.PIN)
        val two = IdentityService(file(), MemorySecretStore()).createIdentity(TestKeys.PIN)
        assertNotEquals(one.npub, two.npub)
    }
}
