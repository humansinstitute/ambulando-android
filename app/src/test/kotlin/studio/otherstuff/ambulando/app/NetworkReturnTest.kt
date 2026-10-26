package studio.otherstuff.ambulando.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkReturnTest {
    @Test fun validatedReturnBurstsCoalesceAndBackgroundStopCancelsPendingRefresh() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var refreshes = 0
        val observer = NetworkReturn(context, this) { refreshes++ }
        observer.start(); observer.start()
        val callback = shadowOf(manager).networkCallbacks.single()
        val network = org.robolectric.shadows.ShadowNetwork.newInstance(123)
        val available = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        callback.onCapabilitiesChanged(network, NetworkCapabilities()); runCurrent()
        assertEquals(0, refreshes)
        repeat(10) { callback.onCapabilitiesChanged(network, available) }
        runCurrent(); advanceTimeBy(751); runCurrent()
        assertEquals(1, refreshes)
        callback.onCapabilitiesChanged(network, available); advanceUntilIdle()
        assertEquals(1, refreshes)
        callback.onLost(network); runCurrent()
        callback.onCapabilitiesChanged(network, available); runCurrent()
        observer.stop(); advanceUntilIdle()
        assertEquals(1, refreshes)
        assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
    }
}
