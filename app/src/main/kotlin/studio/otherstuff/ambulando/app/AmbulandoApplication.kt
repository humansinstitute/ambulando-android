package studio.otherstuff.ambulando.app

import android.app.Application
import studio.otherstuff.ambulando.app.background.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.OkHttpTransport

/** WorkManager never resumes an interactive signing session after process death. */
class AmbulandoApplication : Application(), BackgroundHost {
    var activeModel: AppModel? = null; private set
    private val models = mutableMapOf<Boolean, AppModel>()
    /** One session owner per storage environment for the process, including time granted to WorkManager. */
    fun model(environment: AppEnvironment): AppModel = models.getOrPut(environment.isSample) { AppModel(environment, this) }.also { activeModel = it }
    override suspend fun backgroundEntryPoint(): BackgroundEntryPoint {
        activeModel?.takeUnless { it.environment.isSample }?.let { return it }
        return object : BackgroundEntryPoint {
            override suspend fun backgroundRefresh(budgetMillis: Long): Boolean {
                val environment = AppEnvironment.Production(this@AmbulandoApplication)
                val identity = environment.identityService()
                if (!identity.loadBackgroundRead()) return false
                val store = LocalStore(environment.storeFile.parentFile!!)
                val engine = SyncEngine()
                try {
                    val sync = WorkspaceSync(store, engine, identity, OkHttpTransport())
                    for (connection in store.reader.connections()) {
                        sync.backgroundRefresh(connection, store.reader.syncableWorkspaces(connection.id), budgetMillis = budgetMillis)
                    }
                    return true
                } finally { engine.cancelAll(); engine.close(); store.close() }
            }
            override suspend fun finishSending(budgetMillis: Long) = false
        }
    }
}
