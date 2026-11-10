package studio.otherstuff.ambulando.app

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import studio.otherstuff.ambulando.app.push.*

@RunWith(RobolectricTestRunner::class)
class PushStateTest {
    @Test fun coldReloadPreservesIsolatedRegistrationsAndLocalRevocation() {
        val context = (RuntimeEnvironment.getApplication() as AmbulandoApplication)
        val state = PushState(context)
        state.all().forEach { state.remove(it.instance) }
        val a = PushRegistration(connectionId = "tower-a", signerNpub = "actor-a", workspaceId = "same-workspace", appNpub = "app", origin = "https://a.example")
        val b = PushRegistration(connectionId = "tower-b", signerNpub = "actor-b", workspaceId = "same-workspace", appNpub = "app", origin = "https://b.example")
        state.add(a); state.add(b)
        state.update(a.instance) { it.copy(enabled = false, ready = false, seen = mapOf("item" to 5)) }
        val cold = PushState(context).all()
        assertFalse(cold.single { it.instance == a.instance }.enabled)
        assertTrue(cold.single { it.instance == b.instance }.enabled)
        assertEquals(5L, cold.single { it.instance == a.instance }.seen["item"])
        assertNotEquals(a.instance, b.instance)
        state.remove(a.instance); state.remove(b.instance)
    }
    @Test fun channelIsCreatedAndPurposeTextCannotContainProducerContent() {
        val context = (RuntimeEnvironment.getApplication() as AmbulandoApplication)
        context.push.createChannel()
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(AndroidPush.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("New workspace activity", AndroidPush.genericTitle("untrusted.producer", "mention"))
        assertEquals("You were mentioned", AndroidPush.genericTitle("flightdeck.core", "mention"))
    }
}
