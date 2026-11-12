package studio.otherstuff.ambulando.app.garmin

import android.content.*
import com.garmin.android.connectiq.IQDevice

/** SDK 2.2 registers its own exported receiver. Prepare extras before it reads
 * them, and contain malformed IPC at that boundary, before any app callback. */
internal class GarminSdkContext(base: Context, private val rejected: () -> Unit) : ContextWrapper(base) {
    private val receivers = mutableMapOf<BroadcastReceiver, BroadcastReceiver>()
    private fun wrap(receiver: BroadcastReceiver?): BroadcastReceiver? = receiver?.let { original ->
        receivers.getOrPut(original) { object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    intent.setExtrasClassLoader(IQDevice::class.java.classLoader)
                    original.onReceive(context, intent)
                } catch (_: RuntimeException) { rejected() }
            }
        } }
    }
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
        baseContext.registerReceiver(wrap(receiver), filter)
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? =
        baseContext.registerReceiver(wrap(receiver), filter, flags)
    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        baseContext.unregisterReceiver(receivers.remove(receiver) ?: receiver)
    }
}
