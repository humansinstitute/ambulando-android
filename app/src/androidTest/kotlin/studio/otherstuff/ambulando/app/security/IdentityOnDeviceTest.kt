package studio.otherstuff.ambulando.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import java.io.File

/** Public NIP-19 specification vector only. */
@RunWith(AndroidJUnit4::class)
class IdentityOnDeviceTest {
    private val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    private lateinit var dir: File

    private fun service() = IdentityService(
        File(dir, "vault.json"), KeystoreVaultSecretStore(dir),
        KeystoreBackgroundCredentialStore(dir), KeystoreSessionKeyStore(dir),
    )

    @Before fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "identity-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        runBlocking { runCatching { service().remove() } }
        runCatching { KeystoreSessionKeyStore(dir).delete() }
        runCatching { KeystoreBackgroundCredentialStore(dir).delete() }
        KeystoreBox(AndroidStorage.VAULT_SECRET_ALIAS, true).destroy()
        dir.deleteRecursively()
    }

    @Test fun importLockUnlockSignAndResumeAcrossRelaunch() = runBlocking {
        val first = service()
        assertEquals(IdentityState.Unlocked(npub), first.importIdentity(nsec, "4821"))
        // Only ciphertext is on disk.
        dir.walk().filter { it.isFile }.forEach { assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains(nsec)) }
        val event = first.sign(NostrEventTemplate(1, emptyList(), "hello"))
        assertTrue(SignatureVerifier.verify(event))

        // A new process resumes the trusted-device session without the PIN.
        val relaunched = service()
        assertTrue(relaunched.resumeSession())
        assertEquals(IdentityState.Unlocked(npub), relaunched.state())

        // Lock now revokes the session: the next launch needs the PIN, and a wrong PIN is refused.
        assertEquals(IdentityState.Locked(npub), relaunched.lock())
        val third = service()
        assertFalse(third.resumeSession())
        assertTrue(runCatching { third.unlock("0000") }.isFailure)
        assertEquals(IdentityState.Unlocked(npub), third.unlock("4821"))
    }
}
