package studio.otherstuff.ambulando.core.store

import java.io.File

/**
 * Where the installed app keeps the user's account. These names are a compatibility contract: an update must find the
 * identity, Tower connection, selections, drafts and unsent work exactly where the previous build left them. Changing
 * any of them strands existing data, so they are pinned by tests; a rename needs a migration that copies, verifies and
 * only then switches.
 *
 * Android paths come from the app (`Context.noBackupFilesDir` for data, `Context.cacheDir` for caches); this core module
 * only names what lives under them. The data directory must be excluded from cloud backup and device transfer (the
 * vault is useless without the device-bound key, and the store is rebuilt from Tower).
 */
object ProductionStorage {
    /** Subdirectory of the app's no-backup files directory holding `Store/ambulando.sqlite` and `Identity/vault.json`. */
    const val DATA_DIRECTORY_NAME = "AmbulandoNative"
    const val STORE_DIRECTORY_NAME = "Store"
    const val DATABASE_FILE_NAME = "ambulando.sqlite"
    const val VAULT_PATH = "Identity/vault.json"
    /** Staged chat attachments (privacy-stripped bytes awaiting upload) and in-flight transfers, under the data directory. */
    const val STAGING_DIRECTORY_NAME = "Staging"
    const val TRANSFERS_DIRECTORY_NAME = "Transfers"

    /** Platform secret aliases (Android Keystore / encrypted preferences), device-only and never backed up. */
    const val VAULT_SECRET_SERVICE = "studio.otherstuff.ambulando.native.identity-vault.v1"
    const val BACKGROUND_READ_SERVICE = "studio.otherstuff.ambulando.native.background-read.v1"
    const val SESSION_SERVICE = "studio.otherstuff.ambulando.native.session.v1"

    /** Cache subdirectory for downloaded files (re-downloadable; the system may purge it). */
    const val FILE_CACHE_DIRECTORY_NAME = "AmbulandoFiles"
    /** Cache subdirectory for avatar thumbnails (re-downloadable; the system may purge it). */
    const val AVATAR_CACHE_DIRECTORY_NAME = "AmbulandoAvatars"

    /** `<base>/AmbulandoNative`, where `base` is the app's no-backup files directory. */
    fun dataDirectory(base: File): File = File(base, DATA_DIRECTORY_NAME)
    fun storeDirectory(data: File): File = File(data, STORE_DIRECTORY_NAME)
    fun vaultFile(data: File): File = File(data, VAULT_PATH)
    fun stagingDirectory(data: File): File = File(data, STAGING_DIRECTORY_NAME)
    fun transfersDirectory(data: File): File = File(data, TRANSFERS_DIRECTORY_NAME)
    fun fileCacheDirectory(cache: File): File = File(cache, FILE_CACHE_DIRECTORY_NAME)
    fun avatarCacheDirectory(cache: File): File = File(cache, AVATAR_CACHE_DIRECTORY_NAME)
}
