package studio.otherstuff.ambulando.app.push

import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import studio.otherstuff.ambulando.app.AmbulandoApplication

class AmbulandoPushService : PushService() {
    private val push get() = (application as AmbulandoApplication).push
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) { push.launch { newEndpoint(instance, endpoint) } }
    override fun onMessage(message: PushMessage, instance: String) {
        if (message.decrypted && push.state.all().any { it.instance == instance && it.enabled }) {
            PushDeliveryWorker.enqueue(this, instance, message.content)
        }
    }
    override fun onRegistrationFailed(reason: FailedReason, instance: String) { push.fail(instance, "Distributor registration failed. Check distributor setup and retry.") }
    override fun onUnregistered(instance: String) { push.unregistered(instance) }
    override fun onTempUnavailable(instance: String) { push.fail(instance, "Distributor server is temporarily unavailable.") }
}
