package studio.otherstuff.ambulando.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreBoxTest {
    private val box = KeystoreBox("test.keystore-box.v1", unlockedDeviceRequired = false)

    @After fun tearDown() = box.destroy()

    @Test fun sealsOpensAndRejectsTamperingAndDestroyedKeys() {
        val secret = ByteArray(32) { it.toByte() }
        val sealed = box.seal(secret, "aad".toByteArray())
        assertTrue(box.exists())
        // The emulator reports where it keeps the key; real devices usually say TEE or StrongBox.
        org.junit.Assert.assertNotNull(box.securityLevel())
        assertFalse(sealed.copyOfRange(13, sealed.size).contentEquals(secret))
        assertArrayEquals(secret, box.open(sealed, "aad".toByteArray()))
        assertTrue(runCatching { box.open(sealed, "other".toByteArray()) }.isFailure)
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(runCatching { box.open(tampered, "aad".toByteArray()) }.isFailure)
        box.destroy()
        assertTrue(runCatching { box.open(sealed, "aad".toByteArray()) }.isFailure)
    }
}
