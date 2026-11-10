package studio.otherstuff.ambulando.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import java.io.File
import java.util.UUID

/** Isolated throwaway identity only: no production account, secret export or network. */
@RunWith(AndroidJUnit4::class)
class IdentityCreationKeystoreTest {
    @Test fun creationSurvivesNewServiceAndPINUnlockWithRealKeystore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = ".creation-test.${UUID.randomUUID()}"
        val dir = File(context.noBackupFilesDir, "creation-test-${UUID.randomUUID()}")
        fun service() = IdentityService(File(dir, "vault.json"), KeystoreVaultSecretStore(dir, suffix),
            sessionStore = KeystoreSessionKeyStore(dir, suffix))
        try {
            val first = service()
            val state = first.createIdentity("246810") as IdentityState.Unlocked
            val event = first.sign(NostrEventTemplate(1, emptyList(), "isolated test", 123))
            assertTrue(SignatureVerifier.verify(event))
            val second = service()
            assertTrue(second.resumeSession())
            assertEquals(state, second.state())
            second.lock()
            val cold = service()
            assertFalse(cold.resumeSession())
            assertEquals(IdentityState.Locked(state.npub), cold.state())
            assertEquals(state, cold.unlock("246810"))
            assertTrue(SignatureVerifier.verify(cold.sign(NostrEventTemplate(1, emptyList(), "cold test", 124))))
            assertFalse(File(dir, "vault.json").readText().contains("nsec"))
        } finally {
            KeystoreBox(AndroidStorage.SESSION_ALIAS + suffix, true).destroy()
            KeystoreBox(AndroidStorage.VAULT_SECRET_ALIAS + suffix, true).destroy()
            dir.deleteRecursively()
        }
    }
}
