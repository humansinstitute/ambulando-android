package studio.otherstuff.ambulando.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import java.security.SecureRandom
import java.text.Normalizer

/** NIP-49 failures. Wrong password and tampering are deliberately indistinguishable. */
sealed class Nip49Error(message: String) : Exception(message, null, false, false) {
    data object Malformed : Nip49Error("This is not a valid ncryptsec backup.")
    data object UnsupportedVersion : Nip49Error("This backup uses a version this app does not support.")
    data object CostOutOfBounds : Nip49Error("This backup asks for more memory than this app will spend on a password.")
    data object InvalidPassword : Nip49Error("Enter a password.")
    data object DecryptionFailed : Nip49Error("Wrong password, or the backup was altered.")
    data object InvalidKey : Nip49Error("The backup does not contain a valid Nostr key.")
}

/**
 * NIP-49 password-encrypted private keys (`ncryptsec`), built only from BouncyCastle's scrypt and XChaCha20-Poly1305.
 *
 * Password: NFKC-normalised UTF-8. Key: scrypt(r=8, p=1, 16-byte salt). Payload: exactly 91 bytes
 * `version(2) | log_n | salt(16) | nonce(24) | key_security | ciphertext(32) | tag(16)`, with the key-security byte as the
 * AEAD associated data. Memory is 128 * 2^log_n * 8 bytes, so the cost byte of an untrusted backup is bounded BEFORE scrypt runs.
 * Owned password bytes and the derived key are cleared after use, best effort. JVM strings and library copies cannot be
 * guaranteed wiped. This primitive performs no network or storage operations.
 */
object Nip49 {
    const val HRP = "ncryptsec"
    const val VERSION = 2
    const val PAYLOAD_BYTES = 91
    /** Bounds untrusted text before general Bech32 decoding allocates buffers. */
    const val MAX_ENCODED_LENGTH = 200
    const val DEFAULT_LOG_N = 16
    /** Smallest cost this app will write or read (below this a password adds little). */
    const val MIN_LOG_N = 10
    /** Largest cost this app will write; 2^20 needs about 1 GiB. */
    const val MAX_ENCRYPT_LOG_N = 20
    /** Default ceiling when reading another client's backup (about 256 MiB): a phone must not be asked for more by a crafted string. */
    const val DEFAULT_MAX_DECRYPT_LOG_N = 18

    /** 0 = handled insecurely (pasted/stored unencrypted), 1 = never so handled, 2 = not tracked. */
    enum class KeySecurity(val byte: Int) {
        INSECURE(0), SECURE(1), UNTRACKED(2);
        companion object { fun of(byte: Int) = entries.firstOrNull { it.byte == byte } }
    }

    class Decrypted(val secret: ByteArray, val keySecurity: KeySecurity, val logN: Int) {
        override fun toString() = "Nip49.Decrypted(logN=$logN)"
    }

    private const val SALT = 16
    private const val NONCE = 24
    private const val TAG_BITS = 128
    private val secureRandom by lazy { SecureRandom() }

    /** NFKC then UTF-8; the caller owns zeroing of the returned bytes. */
    internal fun passwordBytes(password: CharSequence): ByteArray {
        val text = Normalizer.normalize(password, Normalizer.Form.NFKC)
        if (text.isEmpty()) throw Nip49Error.InvalidPassword
        return text.toByteArray(Charsets.UTF_8)
    }

    /** One scrypt at a time process-wide: each run holds up to 256 MiB, so concurrent attempts must queue rather than add up. */
    private val kdfLock = Any()

    private fun deriveKey(password: CharSequence, salt: ByteArray, logN: Int): ByteArray {
        val bytes = passwordBytes(password)
        try { return synchronized(kdfLock) { SCrypt.generate(bytes, salt, 1 shl logN, 8, 1, 32) } } finally { bytes.fill(0) }
    }

    /** Encrypts a valid 32-byte secret. [random] is injectable for tests only; production uses SecureRandom. */
    fun encrypt(
        secret: ByteArray, password: CharSequence, logN: Int = DEFAULT_LOG_N, keySecurity: KeySecurity = KeySecurity.UNTRACKED,
        random: SecureRandom = secureRandom,
    ): String {
        require(logN in MIN_LOG_N..MAX_ENCRYPT_LOG_N) { "log_n out of range" }
        require(NostrKeys.isValidSecret(secret)) { "not a valid secret key" }
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val key = deriveKey(password, salt, logN)
        try {
            val aad = byteArrayOf(keySecurity.byte.toByte())
            val cipher = XChaCha20Poly1305()
            cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
            val sealed = ByteArray(cipher.getOutputSize(secret.size))
            val n = cipher.processBytes(secret, 0, secret.size, sealed, 0)
            cipher.doFinal(sealed, n)
            val payload = byteArrayOf(VERSION.toByte(), logN.toByte()) + salt + nonce + aad + sealed
            check(payload.size == PAYLOAD_BYTES)
            return Bech32.encode(HRP, payload)
        } finally { key.fill(0) }
    }

    /** Decrypts an `ncryptsec`. [maxLogN] can raise the ceiling for a backup the person knows came from a stronger client (up to 20). */
    fun decrypt(ncryptsec: String, password: CharSequence, maxLogN: Int = DEFAULT_MAX_DECRYPT_LOG_N): Decrypted {
        if (ncryptsec.length > MAX_ENCODED_LENGTH) throw Nip49Error.Malformed
        val decoded = Bech32.decode(ncryptsec.trim()) ?: throw Nip49Error.Malformed
        if (decoded.hrp != HRP || decoded.data.size != PAYLOAD_BYTES) throw Nip49Error.Malformed
        return decryptPayload(decoded.data, password, maxLogN)
    }

    internal fun decryptPayload(p: ByteArray, password: CharSequence, maxLogN: Int = DEFAULT_MAX_DECRYPT_LOG_N): Decrypted {
        if (p.size != PAYLOAD_BYTES) throw Nip49Error.Malformed
        if ((p[0].toInt() and 0xff) != VERSION) throw Nip49Error.UnsupportedVersion
        val logN = p[1].toInt() and 0xff
        if (logN < MIN_LOG_N || logN > maxLogN.coerceAtMost(MAX_ENCRYPT_LOG_N)) throw Nip49Error.CostOutOfBounds
        val salt = p.copyOfRange(2, 2 + SALT)
        val nonce = p.copyOfRange(2 + SALT, 2 + SALT + NONCE)
        val securityByte = p[2 + SALT + NONCE].toInt() and 0xff
        val security = KeySecurity.of(securityByte) ?: throw Nip49Error.Malformed
        val sealed = p.copyOfRange(3 + SALT + NONCE, PAYLOAD_BYTES)
        val key = deriveKey(password, salt, logN)
        try {
            val cipher = XChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce, byteArrayOf(securityByte.toByte())))
            val out = ByteArray(cipher.getOutputSize(sealed.size))
            val secret = try {
                val n = cipher.processBytes(sealed, 0, sealed.size, out, 0)
                cipher.doFinal(out, n)
                out.copyOf(32)
            } catch (e: InvalidCipherTextException) { throw Nip49Error.DecryptionFailed }
            finally { out.fill(0) }
            if (!NostrKeys.isValidSecret(secret)) { secret.fill(0); throw Nip49Error.InvalidKey }
            return Decrypted(secret, security, logN)
        } finally { key.fill(0) }
    }
}
