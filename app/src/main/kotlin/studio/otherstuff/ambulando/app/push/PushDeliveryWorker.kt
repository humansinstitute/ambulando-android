package studio.otherstuff.ambulando.app.push

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.withTimeout
import studio.otherstuff.ambulando.app.AmbulandoApplication
import studio.otherstuff.ambulando.core.notifications.PushHint
import java.util.concurrent.TimeUnit

/** Event-triggered, bounded follow-up to a decrypted push, never periodic notification polling. */
class PushDeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val instance = inputData.getString("instance") ?: return Result.failure()
        val payload = inputData.getByteArray("hint") ?: return Result.failure()
        return try {
            withTimeout(25_000) { (applicationContext as AmbulandoApplication).push.message(instance, payload) }
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
    companion object {
        fun enqueue(context: Context, instance: String, payload: ByteArray) {
            val hint = PushHint.parse(payload) ?: return
            val request = OneTimeWorkRequestBuilder<PushDeliveryWorker>()
                .setInputData(workDataOf("instance" to instance, "hint" to payload))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("ambulando.push.$instance").build()
            WorkManager.getInstance(context).enqueueUniqueWork("push:$instance:${hint.notificationId}:${hint.changeSeq}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
