package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.companion.CompanionDeviceManager
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

/** Synthetic Garmin commands and CDM association; real Android Activity, permissions, service and missing-headset error. */
@RunWith(AndroidJUnit4::class)
class GarminRemoteFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }
    @Test fun associatedBackgroundWatchRequestOpensVisibleActivityAndFailsClearlyWithoutHeadset() {
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT")
        val mac = "02:00:00:00:00:71"
        shell("cmd companiondevice associate 0 ${context.packageName} $mac")
        try {
            val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                val app = context.applicationContext as AmbulandoApplication
                compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
                val model = app.activeModel!!
                compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
                compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
                val remote = model.garmin
                val device = IQDevice(71L, "Synthetic Vivoactive")
                val session = "emulator_" + System.currentTimeMillis()
                fun command(op: String) = mapOf("v" to 1, "s" to session, "c" to "${session}_$op", "op" to op)
                val association = context.getSystemService(CompanionDeviceManager::class.java).myAssociations.first { it.deviceMacAddress.toString() == mac }
                compose.runOnIdle {
                    model.voice.cancel()
                    remote.sessions.filter { it.status == "waiting" }.forEach { remote.dismiss(it.session) }
                    val c = model.connection!!; val w = model.workspaces.first { it.id == SampleIds.STUDIO }
                    remote.bind(device, VoiceDestination.capture(c, w, ChatTarget(c.id, w.id, SampleIds.GENERAL, null), "Synthetic test destination"))
                    remote.associate(device.deviceIdentifier, association.id)
                    assertTrue(remote.canOpenRecordingScreen())
                }
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(1500)
                instrumentation.runOnMainSync { remote.receive(device, command("start")) }
                compose.waitUntil(15_000) { remote.sessions.firstOrNull { it.session == session }?.status == "error" }
                val draft = model.voice.state.value!!
                assertTrue(draft.headsetOnly)
                assertEquals(VoicePhase.INTERRUPTED, draft.phase)
                assertTrue("Actual route error: ${draft.error}", draft.error.orEmpty().contains("Bluetooth"))
                assertTrue(shell("dumpsys activity activities").contains("WatchRecordingActivity"))
                assertEquals("error", remote.sessions.first { it.session == session }.status)
                instrumentation.runOnMainSync {
                    remote.receive(device, command("start"))
                    remote.receive(device, command("finish"))
                    assertEquals(draft.id, model.voice.state.value!!.id)
                    assertEquals(VoicePhase.INTERRUPTED, model.voice.state.value!!.phase)
                    remote.receive(device, command("cancel"))
                    assertNull(model.voice.state.value)
                    assertEquals("cancelled", remote.sessions.first { it.session == session }.status)
                    remote.receive(device, command("start"))
                    assertNull(model.voice.state.value)
                }
                shell("input keyevent KEYCODE_BACK")
            }
        } finally { shell("cmd companiondevice disassociate 0 ${context.packageName} $mac") }
    }
    @Test fun finishRetainsOfflineDraftThenRetriesOneMessageToPinnedDestination() {
        // Phone microphone is an explicit test stand-in: this does not simulate a successful Bluetooth route.
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            val remote = model.garmin
            val device = IQDevice(72L, "Synthetic watch")
            val session = "send_" + System.currentTimeMillis()
            fun command(op: String) = mapOf("v" to 1, "s" to session, "c" to "${session}_$op", "op" to op)
            lateinit var record: studio.otherstuff.ambulando.app.garmin.RemoteSession
            compose.runOnIdle {
                model.voice.cancel()
                remote.sessions.filter { it.status == "waiting" }.forEach { remote.dismiss(it.session) }
                val c = model.connection!!; val w = model.workspaces.first { it.id == SampleIds.STUDIO }
                val destination = VoiceDestination.capture(c, w, ChatTarget(c.id, w.id, SampleIds.GENERAL, null), "Synthetic original channel")
                remote.bind(device, destination)
                record = remote.journal.begin(session)
                model.voice.start(destination, record.recording, headsetOnly = false)
            }
            compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.RECORDING }
            Thread.sleep(1500)
            model.sampleTower!!.offline = true
            compose.runOnIdle {
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
            compose.runOnIdle { model.refresh() }
            Thread.sleep(3000)
            compose.runOnIdle { remote.receive(device, command("retry")); remote.receive(device, command("retry")) }
            compose.waitUntil(30_000) { remote.sessions.firstOrNull { it.session == session }?.status == "sent" }
            val owner = model.runtimeMap.values.first { it.workspace.id == SampleIds.STUDIO }
            val messages = kotlinx.coroutines.runBlocking { owner.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500) }
            assertEquals(1, messages.count { it.clientRequestId == record.recording })
            compose.runOnIdle { remote.receive(device, command("finish")); remote.receive(device, command("cancel")); remote.receive(device, command("start")) }
            assertEquals("sent", remote.sessions.first { it.session == session }.status)
            assertNull(model.voice.state.value)
        }
    }

}
