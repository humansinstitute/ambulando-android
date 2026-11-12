package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garmin.android.connectiq.IQDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.voice.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.latestMessages

/** Retained audio / screen-off Finish regression. Phone microphone is an explicit emulator stand-in. */
@RunWith(AndroidJUnit4::class)
class GarminRemoteFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }
    @Test fun finishRetainsOfflineDraftThenRetriesOneMessageToPinnedDestination() {
        // Phone microphone is an explicit test stand-in: this does not simulate a successful Bluetooth route.
        check(context.packageName.endsWith(".watchtest"))
        shell("input keyevent KEYCODE_WAKEUP")
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            WatchNativeConsent.grant(activity!!)
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            instrumentation.runOnMainSync { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            val remote = model.garmin
            val device = IQDevice(72L, "Synthetic watch")
            val session = "send_" + System.currentTimeMillis()
            fun command(op: String) = mapOf("v" to 1, "s" to session, "c" to "${session}_$op", "op" to op)
            lateinit var record: studio.otherstuff.ambulando.app.garmin.RemoteSession
            instrumentation.runOnMainSync {
                model.voice.cancel()
                remote.sessions.filter { it.status == "waiting" }.forEach { remote.dismiss(it.session) }
                val c = model.connection!!; val w = model.workspaces.first { it.id == SampleIds.STUDIO }
                val destination = VoiceDestination.capture(c, w, ChatTarget(c.id, w.id, SampleIds.GENERAL, null), "Synthetic original channel")
                remote.bind(device, destination)
                remote.savePrompt("Pinned voice instruction")
                record = remote.journal.begin(session)
                model.watchDelivery(destination) // Same pinned delivery lifetime; capture source alone is the stand-in.
                model.voice.start(destination, record.recording, headsetOnly = false, prompt = remote.prompt)
            }
            compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.RECORDING }
            instrumentation.runOnMainSync { remote.savePrompt("Changed instruction for future recordings") }
            assertEquals("Pinned voice instruction", model.voice.state.value!!.prompt)
            Thread.sleep(1500)
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            Thread.sleep(500)
            model.sampleTower!!.offline = true
            instrumentation.runOnMainSync {
                // Navigation changes cannot redirect Finish. A mismatched device cannot stop the capture.
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.DESIGN)
                remote.receive(IQDevice(999L, "Wrong watch"), command("finish"))
                assertEquals(VoicePhase.RECORDING, model.voice.state.value!!.phase)
                remote.receive(device, command("finish"))
                remote.receive(device, command("finish"))
            }
            compose.waitUntil(30_000) { remote.sessions.firstOrNull { it.session == session }?.status == "error" }
            assertEquals(record.recording, model.voice.state.value!!.id)
            assertTrue(model.voice.drafts.audio(model.voice.state.value!!).length() > 0)
            model.sampleTower!!.offline = false
            instrumentation.runOnMainSync { model.refresh() }
            Thread.sleep(3000)
            instrumentation.runOnMainSync { remote.receive(device, command("retry")); remote.receive(device, command("retry")) }
            compose.waitUntil(30_000) { remote.sessions.firstOrNull { it.session == session }?.status == "sent" }
            val owner = model.runtimeMap.values.first { it.workspace.id == SampleIds.STUDIO }
            val messages = kotlinx.coroutines.runBlocking { owner.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500) }
            assertEquals(1, messages.count { it.clientRequestId == record.recording })
            assertTrue(messages.single { it.clientRequestId == record.recording }.body.startsWith("Pinned voice instruction\n\n"))
            assertFalse(messages.single { it.clientRequestId == record.recording }.body.contains("Changed instruction"))
            instrumentation.runOnMainSync { remote.receive(device, command("finish")); remote.receive(device, command("cancel")); remote.receive(device, command("start")) }
            assertEquals("sent", remote.sessions.first { it.session == session }.status)
            assertNull(model.voice.state.value)
        }
    }

}
