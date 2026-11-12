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
    @Test fun twoWorkspacesShareInstallationButRevocationAndRoutingStayIndependent() {
        val context = RuntimeEnvironment.getApplication() as AmbulandoApplication
        val state = PushState(context)
        state.all().forEach { state.remove(it.instance) }
        val first = state.enrollment("connection", "actor", "workspace-a", "app-a", "https://a.example", "tower-a")
            .copy(projectId = "test-project", ready = true)
        state.add(first)
        val second = state.enrollment("connection", "actor", "workspace-b", "app-b", "https://a.example", "tower-a")
            .copy(projectId = "test-project", ready = true)
        state.add(second)
        assertEquals(first.installationId, second.installationId)
        assertNotEquals(first.instance, second.instance)
        val hint = studio.otherstuff.ambulando.core.notifications.PushHint("https://a.example", "tower-a", "workspace-b", "item", 1, second.installationId)
        assertEquals(second.instance, PushState(context).matching(hint, "test-project")?.instance)
        state.update(first.instance) { it.copy(enabled = false, ready = false) }
        assertEquals(second.instance, state.matching(hint, "test-project")?.instance)
        assertNull(state.matching(hint.copy(workspaceId = "workspace-a"), "test-project"))
        assertNull(state.matching(hint.copy(towerNpub = "other-tower"), "test-project"))
        assertNull(state.matching(hint.copy(origin = "https://evil.example"), "test-project"))
        assertNull(state.matching(hint.copy(installationId = "other-installation"), "test-project"))
        assertNull(state.matching(hint, "other-project"))
        val otherActor = state.enrollment("connection", "other-actor", "workspace-b", "app-b", "https://a.example", "tower-a")
        val otherOrigin = state.enrollment("connection-b", "actor", "workspace-b", "app-b", "https://b.example", "tower-b")
        assertNotEquals(first.installationId, otherActor.installationId)
        assertNotEquals(first.installationId, otherOrigin.installationId)
        state.remove(first.instance)
        assertEquals(second.instance, state.matching(hint, "test-project")?.instance)
        state.remove(second.instance)
    }
    @Test fun missingConfigurationIsUnavailableAndOfflineTokenResetSurvivesReload() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication() as AmbulandoApplication
        // A developer may have a real ignored google-services.json. Simulate missing config explicitly.
        val noConfig = object : android.content.ContextWrapper(context) {
            private val missing = object : android.content.res.Resources(context.resources.assets, context.resources.displayMetrics, context.resources.configuration) {
                override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int =
                    if (name == "google_app_id") 0 else super.getIdentifier(name, defType, defPackage)
            }
            override fun getResources() = missing
        }
        val firebase = FirebaseDevice(noConfig)
        assertTrue(firebase.unavailableReason()!!.contains("no Firebase Android configuration"))
        assertNull(firebase.projectId())
        firebase.requireTokenReset()
        firebase.deleteToken() // No config: must retain reset rather than reuse another actor's token.
        assertTrue(context.getSharedPreferences("fcm_lifecycle", Context.MODE_PRIVATE).getBoolean("reset_required", false))
        assertTrue(runCatching { FirebaseDevice(noConfig).token() }.isFailure)
        assertNull(context.activeModel) // Cold device handling did not construct an interactive signing session.
    }
    @Test fun legacyDistributorStateIsNotPresentedAsWorkingFcm() {
        val context = RuntimeEnvironment.getApplication() as AmbulandoApplication
        val file = java.io.File(context.noBackupFilesDir, "push/registrations.json")
        file.parentFile!!.mkdirs()
        file.writeText("""[{"instance":"old","connectionId":"c","signerNpub":"actor","workspaceId":"w","appNpub":"app","origin":"https://a.example","enabled":true,"ready":true,"endpoint":"https://push.example/old"}]""")
        val legacy = PushState(context).all().single()
        assertFalse(legacy.enabled)
        assertFalse(legacy.ready)
        assertTrue(legacy.message.contains("FCM setup required"))
        file.delete()
    }

}
