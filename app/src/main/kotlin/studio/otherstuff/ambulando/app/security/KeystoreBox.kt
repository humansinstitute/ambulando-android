package studio.otherstuff.ambulando.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets with a non-exportable AES-256-GCM key held by the Android Keystore. The app never sees the key
 * material and only ciphertext is written to app storage. Where the device has it, the key lives in StrongBox or the
 * TEE; on devices without secure hardware the Keystore may keep it in software, still non-exportable to apps.
 * [securityLevel] reports which one the device actually provided.
 *
 * - [unlockedDeviceRequired]: the key is usable only while the device is unlocked (the analogue of iOS
 *   `WhenUnlockedThisDeviceOnly`). Background work on a locked device cannot open such a box.
 * - Keystore entries are never included in backup or device transfer, so a sealed blob restored elsewhere is
 *   unreadable (this-device-only).
 *
 * Output format: 1 version byte (0x01) || 12-byte IV || ciphertext+tag.
 */
class KeystoreBox(private val alias: String, private val unlockedDeviceRequired: Boolean) {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun exists(): Boolean = keyStore.containsAlias(alias)

    fun seal(plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        associatedData?.let { cipher.updateAAD(it) }
        val body = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected IV length" }
        return byteArrayOf(VERSION) + iv + body
    }

    /** Throws if the key is missing, the device is locked (when required) or the blob was altered. */
    fun open(sealed: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(sealed.size > 1 + IV_BYTES && sealed[0] == VERSION) { "Unrecognised sealed format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, sealed, 1, IV_BYTES))
        associatedData?.let { cipher.updateAAD(it) }
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    /** Deletes the key: every blob sealed with it becomes permanently unreadable. */
    fun destroy() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    /** Where the Keystore keeps this key, as reported by the platform; null if the key does not exist yet. */
    fun securityLevel(): KeySecurity? {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurity.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurity.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurity.SOFTWARE
                else -> KeySecurity.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeySecurity.TRUSTED_ENVIRONMENT else KeySecurity.SOFTWARE
        }
    }

    private fun key(create: Boolean): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IllegalStateException("Keystore key is missing")
        // Two first seals racing must not each generate (and replace) the alias.
        synchronized(lockFor(alias)) {
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            return generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (unlockedDeviceRequired) setUnlockedDeviceRequired(true)
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            if (!strongBox) throw e
            generate(strongBox = false)
        } catch (e: java.security.ProviderException) {
            // Some devices report StrongBox but fail generation; fall back to the TEE.
            if (!strongBox) throw e
            generate(strongBox = false)
        }
    }

    enum class KeySecurity { STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private fun lockFor(alias: String): Any = locks.getOrPut(alias) { Any() }

        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val VERSION: Byte = 0x01
    }
}
