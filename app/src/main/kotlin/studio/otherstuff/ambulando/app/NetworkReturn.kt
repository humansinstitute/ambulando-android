package studio.otherstuff.ambulando.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*

/** Foreground-only default-network observer; bursts coalesce and never force-reset the outage retry budget. */
internal class NetworkReturn(context: Context, private val scope: CoroutineScope, private val returned: suspend () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pending: Job? = null
    private var lastReturn: Long? = null
    private var validated: Network? = null
    fun start() {
        if (callback != null || manager == null) return
        val observer = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                val source = this
                scope.launch {
                    if (callback === source && validated == network) { validated = null; pending?.cancel(); pending = null }
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val source = this
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                scope.launch {
                    if (callback !== source) return@launch
                    if (!available) {
                        if (validated == network) { validated = null; pending?.cancel(); pending = null }
                        return@launch
                    }
                    if (validated == network) return@launch
                    validated = network
                    if (pending?.isActive == true) return@launch
                    val now = android.os.SystemClock.elapsedRealtime()
                    val wait = maxOf(750L, lastReturn?.let { 5_000 - (now - it) } ?: 0L)
                    pending = scope.launch {
                        delay(wait)
                        if (callback === source) {
                            lastReturn = android.os.SystemClock.elapsedRealtime()
                            returned()
                        }
                    }
                }
            }
        }
        callback = observer
        manager.registerDefaultNetworkCallback(observer)
    }
    fun stop() {
        callback?.let { manager?.unregisterNetworkCallback(it) }
        callback = null; pending?.cancel(); pending = null; validated = null
    }
}
