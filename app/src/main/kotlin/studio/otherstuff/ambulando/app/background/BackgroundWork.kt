package studio.otherstuff.ambulando.app.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * What the app does when Android grants background time. Implemented by the app's state owner once storage, identity
 * and sync exist; the workers only find it and give it a time budget.
 */
interface BackgroundEntryPoint {
    /** Runs the shared background refresh pipeline within [budgetMillis]; returns false if nothing could be done. */
    suspend fun backgroundRefresh(budgetMillis: Long): Boolean

    /** Sends unsent messages and changes if the identity is unlocked in memory, waiting until idle or the budget ends. */
    suspend fun finishSending(budgetMillis: Long): Boolean
}

/** The process-wide owner of the entry point (the Application implements it). */
interface BackgroundHost {
    suspend fun backgroundEntryPoint(): BackgroundEntryPoint?
}

/**
 * Opportunistic periodic refresh, the Android counterpart of the iPhone's BGAppRefreshTask: requested no more often than
 * every 15 minutes with a network, scheduled entirely by Android (it may run later or not at all). It runs the same keyed
 * jobs at background priority and stops when its budget ends; stored pages and cursors are kept.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = (applicationContext as? BackgroundHost)?.backgroundEntryPoint() ?: return Result.success()
        withTimeoutOrNull(BUDGET_MILLIS + 5_000) { entry.backgroundRefresh(BUDGET_MILLIS) }
        return Result.success()
    }

    companion object { const val BUDGET_MILLIS = 25_000L }
}

/**
 * Leaving the foreground with unsent items asks Android for a short expedited job (the counterpart of iOS finishing
 * time). Nothing is signed unless the identity is still unlocked in memory.
 */
class FinishSendingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = (applicationContext as? BackgroundHost)?.backgroundEntryPoint() ?: return Result.success()
        withTimeoutOrNull(BUDGET_MILLIS + 5_000) { entry.finishSending(BUDGET_MILLIS) }
        return Result.success()
    }

    companion object { const val BUDGET_MILLIS = 25_000L }
}

object BackgroundScheduler {
    private const val REFRESH = "ambulando.refresh"
    private const val FINISH = "ambulando.finish-sends"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Requests periodic refresh no more often than every 15 minutes (Android's minimum); keeps an existing schedule. */
    fun scheduleRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelRefresh(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(REFRESH)

    /** Asks for an expedited finishing job (falls back to a normal one when the app is out of expedited quota). */
    fun requestFinishSending(context: Context) {
        val request = OneTimeWorkRequestBuilder<FinishSendingWorker>()
            .setConstraints(network)
            .apply {
                // Before Android 12 an expedited job runs as a foreground service with a notification; the app uses
                // no foreground service, so older versions get an ordinary job instead.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FINISH, ExistingWorkPolicy.REPLACE, request)
    }
}
