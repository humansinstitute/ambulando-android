// Adapted from the NoteToSelf / In Dialogue native identity vault.
package studio.otherstuff.ambulando.core.crypto

import kotlinx.serialization.Serializable
import studio.otherstuff.ambulando.core.nostr.Hex
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.WireJson
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Identity failures. Instances carry no stack trace (they are domain outcomes, compared by value), so the
 * no-argument cases can be singletons.
 */
sealed class IdentityError(message: String) : Exception(message, null, false, false) {
    data object InvalidNsec : IdentityError("Enter a valid Bech32 nsec with a 32-byte Nostr secret key.")
    data object InvalidPIN : IdentityError("PIN must contain 4 to 12 digits.")
    data object Exists : IdentityError("An identity already exists. Remove it explicitly before importing another.")
    data object Missing : IdentityError("No identity has been imported into this app.")
    data object Locked : IdentityError("Unlock your identity to sign.")
    data object KeychainUnavailable : IdentityError("The Ambulando Keystore secret is unavailable. Your identity has been kept.")
    data object InvalidVault : IdentityError("Incorrect PIN or damaged identity vault. Your identity has been kept.")
    data object SignerMismatch : IdentityError("The unlocked identity is not the one this connection was made with.")
    data object AccountMismatch : IdentityError(
        "This key is not the identity this device's Tower connection belongs to. Nothing was replaced; import that identity's key instead.",
    )
}

/**
 * Holds the per-install secret that, combined with the PIN, derives the vault key. The production implementation is a
 * device-only, non-backed-up platform secret (Android Keystore wrapped) owned by this app; implementations throw
 * [IdentityError.KeychainUnavailable] when the secret exists but cannot be read, and return null only when absent.
 */
interface VaultSecretStore {
    fun read(): ByteArray?
    fun write(data: ByteArray)
}

enum class VaultReadProblem {
    /** The file exists but the platform has not made it readable (for example before the first unlock since boot). */
    UNREADABLE,

    /** The file was read but is not a vault this app can open. It is kept as it is; nothing replaces it. */
    DAMAGED,
}

sealed class IdentityState {
    /** No identity vault on this device. */
    data object None : IdentityState()

    /**
     * An identity vault is on this device but cannot be read right now (protected storage unavailable, or the file is
     * damaged). It is never treated as [None]: no import is offered, nothing is replaced, and it is read again when possible.
     */
    data object Unavailable : IdentityState()
    data class Locked(override val npub: String) : IdentityState()
    data class Unlocked(override val npub: String) : IdentityState()

    open val npub: String? get() = null
}

/**
 * PIN + per-install secret → PBKDF2-HMAC-SHA256 (210k) → AES-GCM sealed scalar.
 * Only the sealed envelope is written to disk; the scalar lives in memory while unlocked.
 *
 * Not thread-safe: [IdentityService] owns it behind a mutex.
 */
class IdentityVault(val file: File, private val secrets: VaultSecretStore) {
    /** On-disk envelope. Field names and order match the iOS vault (`Data` fields are standard base64). */
    @Serializable
    internal data class Envelope(
        val version: Int,
        val iterations: Int,
        val salt: String,
        val sealed: String,
        val publicKeyHex: String,
    )

    private var clear: ByteArray? = null

    var publicKeyHex: String? = null
        private set

    /** Why an existing vault could not be read at the last attempt (null when it was read). */
    var readProblem: VaultReadProblem? = null
        private set

    /**
     * Public key only (no secret), kept beside the vault so a launch that cannot read the vault still knows which
     * identity it holds. It never unlocks anything; it lets the user-enabled background read credential be matched to
     * this identity while the vault is sealed.
     */
    val publicKeyHintFile: File get() = File(file.absoluteFile.parentFile, "vault.pub")

    /** The vault's public key, or (while the vault cannot be read) the stored hint. */
    val knownPublicKeyHex: String?
        get() {
            publicKeyHex?.let { return it }
            val hex = runCatching { publicKeyHintFile.readText(Charsets.UTF_8) }.getOrNull()?.trim() ?: return null
            return if (hex.length == 64 && Hex.decode(hex) != null) hex else null
        }

    val isUnlocked: Boolean get() = clear != null
    val exists: Boolean get() = file.exists()

    /** A vault file exists but its public key could not be read (protected data unavailable, or damaged). */
    val unreadable: Boolean get() = exists && publicKeyHex == null

    init {
        reload()
    }

    /**
     * Reads the public key from the sealed envelope if it is not known yet. A file that exists but cannot be read is
     * [VaultReadProblem.UNREADABLE], never a missing identity; reading again later recovers it. Nothing here writes,
     * moves or deletes the vault.
     */
    fun reload() {
        if (publicKeyHex != null || !exists) return
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null) {
            readProblem = VaultReadProblem.UNREADABLE
            return
        }
        val envelope = decodeEnvelope(bytes)
        if (envelope == null || envelope.publicKeyHex.length != 64) {
            readProblem = VaultReadProblem.DAMAGED
            return
        }
        publicKeyHex = envelope.publicKeyHex
        readProblem = null
        writePublicKeyHint(envelope.publicKeyHex)
    }

    /** Writes the hint when missing or different (also brings installs from earlier builds up to date once readable). */
    private fun writePublicKeyHint(hex: String) {
        val known = runCatching { publicKeyHintFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (known == hex) return
        runCatching { AtomicFiles.write(publicKeyHintFile, hex.toByteArray(Charsets.UTF_8)) }
    }

    private fun key(pin: String, secret: ByteArray, salt: ByteArray): SecretKeySpec {
        if (!validPIN(pin)) throw IdentityError.InvalidPIN
        val password = (pin + ":" + Base64.getEncoder().encodeToString(secret)).toCharArray()
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val derived = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } catch (e: Exception) {
                throw IdentityError.KeychainUnavailable
            }
            try {
                return SecretKeySpec(derived, "AES")
            } finally {
                derived.fill(0)
            }
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }

    fun create(nsec: String, pin: String) {
        if (exists) throw IdentityError.Exists
        if (!validPIN(pin)) throw IdentityError.InvalidPIN
        val scalar = NostrKeys.secretKey(nsec) ?: throw IdentityError.InvalidNsec
        try {
            val pubkey = NostrKeys.publicKeyHex(scalar)
            val secret = secrets.read() ?: random(32).also { secrets.write(it) }
            val salt = random(16)
            val combined = seal(scalar, key(pin, secret, salt))
            file.absoluteFile.parentFile?.mkdirs()
            if (exists) throw IdentityError.Exists
            val envelope = Envelope(VERSION, ITERATIONS, b64(salt), b64(combined), pubkey)
            AtomicFiles.write(file, WireJson.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8))
            clear = scalar.copyOf()
            publicKeyHex = pubkey
            readProblem = null
            writePublicKeyHint(pubkey)
        } finally {
            scalar.fill(0)
        }
    }

    fun unlock(pin: String) {
        lock()
        if (!exists) throw IdentityError.Missing
        if (!validPIN(pin)) throw IdentityError.InvalidPIN
        val secret = secrets.read() ?: throw IdentityError.KeychainUnavailable
        try {
            val envelope = decodeEnvelope(file.readBytes()) ?: throw IdentityError.InvalidVault
            val salt = unb64(envelope.salt)
            if (envelope.version != VERSION || envelope.iterations != ITERATIONS || salt?.size != 16) throw IdentityError.InvalidVault
            val sealed = unb64(envelope.sealed) ?: throw IdentityError.InvalidVault
            val opened = open(sealed, key(pin, secret, salt))
            if (opened.size != 32 || NostrKeys.publicKeyHex(opened) != envelope.publicKeyHex) {
                opened.fill(0)
                throw IdentityError.InvalidVault
            }
            clear = opened
            publicKeyHex = envelope.publicKeyHex
        } catch (e: Exception) {
            throw IdentityError.InvalidVault
        }
    }

    fun lock() {
        clear?.fill(0)
        clear = null
    }

    /** Unlocks from a stored session key, only if it is this vault's key. */
    internal fun unlock(secretKey: ByteArray) {
        val expected = publicKeyHex
        if (!exists || expected == null || secretKey.size != 32 ||
            runCatching { NostrKeys.publicKeyHex(secretKey) }.getOrNull() != expected
        ) throw IdentityError.InvalidVault
        lock()
        clear = secretKey.copyOf()
    }

    /** Removes only this app's sealed identity. The per-install secret is retained. */
    fun remove() {
        lock()
        if (exists && !file.delete()) throw java.io.IOException("Could not remove the identity vault.")
        publicKeyHintFile.delete()
        publicKeyHex = null
        readProblem = null
    }

    /** A copy of the unlocked key for the trusted-device session and the user-enabled background credential only. */
    internal fun secretCopy(): ByteArray = clear?.copyOf() ?: throw IdentityError.Locked

    fun sign(template: NostrEventTemplate): NostrEvent {
        val key = clear ?: throw IdentityError.Locked
        return NostrKeys.sign(template, key)
    }

    companion object {
        const val VERSION = 1
        const val ITERATIONS = 210_000
        private val rng = SecureRandom()

        fun validPIN(pin: String): Boolean = PIN.matches(pin)
        private val PIN = Regex("^[0-9]{4,12}$")

        internal fun random(count: Int): ByteArray = ByteArray(count).also { rng.nextBytes(it) }

        private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
        private fun unb64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

        internal fun decodeEnvelope(bytes: ByteArray): Envelope? =
            runCatching { WireJson.decodeFromString(Envelope.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()

        /** AES-GCM with a random 96-bit nonce; output is `nonce || ciphertext || tag` (CryptoKit's `combined`). */
        internal fun seal(plain: ByteArray, key: SecretKeySpec): ByteArray {
            val nonce = random(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            return nonce + cipher.doFinal(plain)
        }

        internal fun open(combined: ByteArray, key: SecretKeySpec): ByteArray {
            if (combined.size < 12 + 16) throw IdentityError.InvalidVault
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, combined, 0, 12))
            return cipher.doFinal(combined, 12, combined.size - 12)
        }
    }
}

/** Write-to-temporary-then-rename, so a reader never sees a partial file. */
object AtomicFiles {
    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: throw java.io.IOException("No parent directory for ${target.name}")
        dir.mkdirs()
        val temp = File.createTempFile(".${target.name}.", ".tmp", dir)
        try {
            temp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temp.delete()
        }
    }
}
