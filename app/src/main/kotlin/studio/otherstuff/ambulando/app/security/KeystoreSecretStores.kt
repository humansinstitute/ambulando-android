package studio.otherstuff.ambulando.app.security

import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.crypto.BackgroundCredentialStore
import studio.otherstuff.ambulando.core.crypto.BackgroundReadCredential
import studio.otherstuff.ambulando.core.crypto.SessionKey
import studio.otherstuff.ambulando.core.crypto.SessionKeyStore
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import java.io.File

/**
 * Android storage names, pinned so every update keeps the account in the same places (the analogue of the iPhone
 * app's `ProductionStorage`). Changing any of these would strand an existing identity; tests pin them.
 */
object AndroidStorage {
    const val VAULT_SECRET_ALIAS = "studio.otherstuff.ambulando.android.identity-vault.v1"
    const val SESSION_ALIAS = "studio.otherstuff.ambulando.android.session.v1"
    const val BACKGROUND_READ_ALIAS = "studio.otherstuff.ambulando.android.background-read.v1"

    const val IDENTITY_DIR = "AmbulandoNative/Identity"
    const val VAULT_FILE = "vault.json"
    const val VAULT_SECRET_FILE = "vault-secret.sealed"
    const val SESSION_FILE = "session.sealed"
    const val BACKGROUND_READ_FILE = "background-read.sealed"
    const val STORE_DIR = "AmbulandoNative/Store"
    const val STORE_FILE = "ambulando.sqlite"

    /** Sample-data mode keeps everything apart: its own directory (in the cache) and its own Keystore aliases. */
    const val SAMPLE_DIR = "AmbulandoSample"
    const val SAMPLE_ALIAS_SUFFIX = ".sample"
}

/**
 * A small secret sealed by a Keystore key and written to a file in the app's no-backup directory.
 * - [read] returns null only when nothing was ever stored. If a sealed file exists but cannot be opened (device
 *   locked for an unlocked-device key, Keystore entry gone, altered file), it throws: callers must never mistake an
 *   unreadable secret for an absent one and replace it.
 * - [delete] removes the file and destroys the key, so stale copies anywhere are unreadable.
 */
internal class SealedFile(private val file: File, private val box: KeystoreBox, private val purpose: String) {
    private val aad = purpose.toByteArray(Charsets.UTF_8)

    fun read(): ByteArray? {
        if (!file.exists()) return null
        val sealed = file.readBytes()
        return try {
            box.open(sealed, aad)
        } catch (e: Exception) {
            throw IllegalStateException("The $purpose could not be opened on this device.", e)
        }
    }

    fun write(data: ByteArray) {
        file.parentFile?.mkdirs()
        AtomicFiles.write(file, box.seal(data, aad))
    }

    fun delete() {
        if (file.exists() && !file.delete()) throw IllegalStateException("The $purpose could not be deleted.")
        box.destroy()
    }
}

/**
 * The per-install secret mixed into the PIN-derived vault key. Its Keystore key requires the device to be unlocked,
 * like the iPhone's `WhenUnlockedThisDeviceOnly` item, and nothing about it leaves the device.
 */
class KeystoreVaultSecretStore(identityDir: File, aliasSuffix: String = "") : VaultSecretStore {
    private val sealed = SealedFile(File(identityDir, AndroidStorage.VAULT_SECRET_FILE),
        KeystoreBox(AndroidStorage.VAULT_SECRET_ALIAS + aliasSuffix, unlockedDeviceRequired = true), "identity secret")

    override fun read(): ByteArray? = sealed.read()
    override fun write(data: ByteArray) = sealed.write(data)
}

/** The trusted-device session copy: usable only while the device is unlocked; revoked by Lock now. */
class KeystoreSessionKeyStore(identityDir: File, aliasSuffix: String = "") : SessionKeyStore {
    private val sealed = SealedFile(File(identityDir, AndroidStorage.SESSION_FILE),
        KeystoreBox(AndroidStorage.SESSION_ALIAS + aliasSuffix, unlockedDeviceRequired = true), "session key")

    override fun read(): SessionKey? = sealed.read()?.let { SessionKey.decode(it) ?: throw IllegalStateException("The session key is damaged.") }
    override fun write(key: SessionKey) = sealed.write(key.encode())
    override fun delete() = sealed.delete()
}

/**
 * The opt-in *Refresh while locked* credential: readable after the first unlock since boot (background work on a
 * locked device), the analogue of `AfterFirstUnlockThisDeviceOnly`. Its use is limited by the app-enforced read policy.
 */
class KeystoreBackgroundCredentialStore(identityDir: File, aliasSuffix: String = "") : BackgroundCredentialStore {
    private val sealed = SealedFile(File(identityDir, AndroidStorage.BACKGROUND_READ_FILE),
        KeystoreBox(AndroidStorage.BACKGROUND_READ_ALIAS + aliasSuffix, unlockedDeviceRequired = false), "background read credential")

    override fun read(): BackgroundReadCredential? =
        sealed.read()?.let { BackgroundReadCredential.decode(it) ?: throw IllegalStateException("The background credential is damaged.") }
    override fun write(credential: BackgroundReadCredential) = sealed.write(credential.encode())
    override fun delete() = sealed.delete()
}
