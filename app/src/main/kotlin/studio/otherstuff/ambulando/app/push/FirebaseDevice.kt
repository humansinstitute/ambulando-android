package studio.otherstuff.ambulando.app.push

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses only the actual public Android project resources generated from google-services.json. No Analytics. */
class FirebaseDevice(private val context: Context) {
    fun unavailableReason(): String? {
        val options = FirebaseOptions.fromResource(context)
        if (options == null || options.projectId.isNullOrBlank() || options.gcmSenderId.isNullOrBlank() || options.apiKey.isBlank())
            return "This build has no Firebase Android configuration. Phone push is unavailable."
        if (GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS)
            return "Google Play services are missing or need updating. Phone push is unavailable."
        return null
    }
    private val lifecycle get() = context.getSharedPreferences("fcm_lifecycle", Context.MODE_PRIVATE)
    fun requireTokenReset() { check(lifecycle.edit().putBoolean("reset_required", true).commit()) }
    fun senderId(): String? = FirebaseOptions.fromResource(context)?.gcmSenderId
    fun projectId(): String? = FirebaseOptions.fromResource(context)?.projectId
    private fun messaging(): FirebaseMessaging {
        check(unavailableReason() == null)
        FirebaseApp.initializeApp(context)
        return FirebaseMessaging.getInstance().also {
            it.isAutoInitEnabled = false // Tokens are requested explicitly only after workspace opt-in.
            it.setDeliveryMetricsExportToBigQuery(false)
        }
    }
    suspend fun token(): String {
        if (lifecycle.getBoolean("reset_required", false)) deleteToken()
        check(!lifecycle.getBoolean("reset_required", false))
        return messaging().token.awaitPrivate()
    }
    suspend fun deleteToken() {
        if (unavailableReason() != null) return // Keep durable reset pending; never reuse it for a later identity.
        messaging().deleteToken().awaitPrivate()
        check(lifecycle.edit().putBoolean("reset_required", false).commit())
    }
}
private suspend fun <T> Task<T>.awaitPrivate(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(IllegalStateException("Firebase operation unavailable"))
        }
    }
}
