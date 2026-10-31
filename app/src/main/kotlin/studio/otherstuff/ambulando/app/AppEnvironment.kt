package studio.otherstuff.ambulando.app

import android.content.Context
import android.content.Intent
import studio.otherstuff.ambulando.app.security.AndroidStorage
import studio.otherstuff.ambulando.app.security.KeystoreBackgroundCredentialStore
import studio.otherstuff.ambulando.app.security.KeystoreSessionKeyStore
import studio.otherstuff.ambulando.app.security.KeystoreVaultSecretStore
import studio.otherstuff.ambulando.core.crypto.IdentityService
import java.io.File

/**
 * Where this run keeps its account and how it reaches Tower.
 *
 * - **Production**: the pinned [AndroidStorage] locations in no-backup app storage and the production Keystore aliases.
 *   Every update reads the same places, so an account is never lost to an upgrade.
 * - **Sample data** (debug builds only, started explicitly with [SAMPLE_EXTRA]): an in-process stand-in for Tower, a
 *   throwaway key generated on the device, its own directory under the cache and its own Keystore aliases, labelled
 *   on every screen. Release builds cannot enter it ([BuildConfig.DEBUG] is false there and the flag is ignored).
 */
sealed class AppEnvironment(val baseDir: File, val aliasSuffix: String) {
    class Production(context: Context) : AppEnvironment(context.noBackupFilesDir, "")
    class Sample(context: Context) : AppEnvironment(File(context.cacheDir, AndroidStorage.SAMPLE_DIR), AndroidStorage.SAMPLE_ALIAS_SUFFIX)

    val isSample: Boolean get() = this is Sample

    /**
     * Whether a start puts back the open section and record stacks saved by the last run: always in production. Sample data
     * starts the same way every time unless a debug test opts in with [RESTORE_NAVIGATION_EXTRA].
     */
    fun restoresNavigation(intent: Intent?): Boolean = !isSample || intent?.getBooleanExtra(RESTORE_NAVIGATION_EXTRA, false) == true
    val identityDir: File get() = File(baseDir, AndroidStorage.IDENTITY_DIR)
    val storeFile: File get() = File(File(baseDir, AndroidStorage.STORE_DIR), AndroidStorage.STORE_FILE)

    fun identityService(): IdentityService {
        identityDir.mkdirs()
        return IdentityService(
            File(identityDir, AndroidStorage.VAULT_FILE),
            KeystoreVaultSecretStore(identityDir, aliasSuffix),
            KeystoreBackgroundCredentialStore(identityDir, aliasSuffix),
            KeystoreSessionKeyStore(identityDir, aliasSuffix),
        )
    }

    companion object {
        /** Intent extra (or instrumentation argument) that starts sample-data mode in a debug build. */
        const val SAMPLE_EXTRA = "studio.otherstuff.ambulando.SAMPLE_DATA"

        /** Debug sample data only: restore the saved screens at launch (otherwise sample data always starts at Deck). */
        const val RESTORE_NAVIGATION_EXTRA = "studio.otherstuff.ambulando.RESTORE_NAVIGATION"

        fun select(context: Context, intent: Intent?, instrumentationFlag: Boolean = false): AppEnvironment {
            val wantsSample = intent?.getBooleanExtra(SAMPLE_EXTRA, false) == true || instrumentationFlag
            return if (BuildConfig.DEBUG && wantsSample) Sample(context) else Production(context)
        }
    }
}
