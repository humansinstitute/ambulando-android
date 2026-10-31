package studio.otherstuff.ambulando.app

import android.app.Application
import studio.otherstuff.ambulando.app.background.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.OkHttpTransport
import studio.otherstuff.ambulando.core.workspaces.RuntimeEnvironment
import studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntimes

/** WorkManager never resumes an interactive signing session after process death. */
class AmbulandoApplication : Application(), BackgroundHost {
    var activeModel: AppModel? = null; private set
    private val models = mutableMapOf<Boolean, AppModel>()
    /** One session owner per storage environment for the process, including time granted to WorkManager. */
    fun model(environment: AppEnvironment): AppModel = models.getOrPut(environment.isSample) { AppModel(environment, this) }.also { activeModel = it }
    override suspend fun backgroundEntryPoint(): BackgroundEntryPoint {
        activeModel?.takeUnless { it.environment.isSample }?.let { return it }
        return object : BackgroundEntryPoint {
            /** A window with no screen: the same runtimes and rotation as the app, read-only unless the identity is unlocked in memory. */
            private suspend fun <T> withRuntimes(block: suspend (BackgroundRefresher) -> T): T {
                val environment = AppEnvironment.Production(this@AmbulandoApplication)
                val identity = environment.identityService()
                identity.loadBackgroundRead()
                val base = environment.storeFile.parentFile!!
                val catalog = LocalStore(base)
                val engine = SyncEngine(maxConcurrent = 4, reservedForeground = 1, maxPerGroup = 3)
                val transport = OkHttpTransport()
                val runtimes = WorkspaceRuntimes(catalog, WorkspaceStorePool(catalog, java.io.File(base, "Workspaces")), foreground = false) { hook ->
                    RuntimeEnvironment(engine, identity, transport, studio.otherstuff.ambulando.core.transport.OkHttpEventStreamTransport(), null,
                        java.io.File(environment.baseDir, "attachments"), java.io.File(environment.baseDir, "uploads"), java.io.File(environment.baseDir, "composer-uploads"),
                        null, kotlinx.coroutines.Dispatchers.Default, hook)
                }
                try { return block(BackgroundRefresher(catalog, runtimes, identity)) }
                finally { engine.cancelAll(); runtimes.close(); engine.close(); catalog.close() }
            }
            override suspend fun backgroundRefresh(budgetMillis: Long): Boolean = withRuntimes { it.refresh(budgetMillis) }
            override suspend fun finishSending(budgetMillis: Long) = false
        }
    }
}
