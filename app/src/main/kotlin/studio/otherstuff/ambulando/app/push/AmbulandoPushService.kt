package studio.otherstuff.ambulando.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import studio.otherstuff.ambulando.app.AmbulandoApplication

/** No content fetch, identity restoration or private payload logging on delivery. */
class AmbulandoPushService : FirebaseMessagingService() {
    private val push get() = (application as AmbulandoApplication).push
    override fun onNewToken(token: String) { push.newToken() }
    override fun onMessageReceived(message: RemoteMessage) {
        val fd = message.data["fd"] ?: return
        push.launch { message(fd.toByteArray()) }
    }
}
