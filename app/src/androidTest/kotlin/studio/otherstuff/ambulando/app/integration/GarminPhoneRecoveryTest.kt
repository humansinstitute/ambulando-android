package studio.otherstuff.ambulando.app.integration

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garmin.android.connectiq.IQDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.garmin.*
import studio.otherstuff.ambulando.app.voice.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.latestMessages

/** Native Activity/lifecycle checks; no Compose test clock and no physical headset success claim. */
@RunWith(AndroidJUnit4::class)
class GarminPhoneRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
        fail(label)
    }
    @Test fun phoneConfirmationWithoutAssociationRetainsWaitingUntilVisibleStartAndReportsMissingHeadset() {
        // Permission setup is owned by scripts/test-garmin-phone-recovery.py before instrumentation.
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            waitFor("Sample workspace missing") { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            instrumentation.runOnMainSync { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            waitFor("Channels missing") { model.channels.any { it.id == SampleIds.GENERAL } }
            val remote = model.garmin
            val device = IQDevice(71, "Synthetic Vivoactive")
            val session = "phone_" + System.currentTimeMillis()
            fun command(op: String) = mapOf("v" to 1, "s" to session, "c" to "${session}_$op", "op" to op)
            instrumentation.runOnMainSync {
                model.voice.cancel()
                remote.sessions.filter { it.status == "waiting" }.forEach { remote.dismiss(it.session) }
                val c = model.connection!!; val w = model.workspaces.first { it.id == SampleIds.STUDIO }
                remote.bind(device, VoiceDestination.capture(c, w, ChatTarget(c.id, w.id, SampleIds.GENERAL), "Synthetic original destination"))
                assertFalse(remote.canOpenRecordingScreen())
                remote.receive(device, command("start"))
                assertEquals("waiting", remote.sessions.last().status)
                assertNull(model.voice.state.value)
            }
            lateinit var recorder: studio.otherstuff.ambulando.app.garmin.WatchRecordingActivity
            ActivityScenario.launch<studio.otherstuff.ambulando.app.garmin.WatchRecordingActivity>(Intent(context, studio.otherstuff.ambulando.app.garmin.WatchRecordingActivity::class.java)
                .putExtra(AppEnvironment.SAMPLE_EXTRA, true).putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { recording ->
                recording.onActivity {
                    recorder = it
                    assertEquals(session, it.intent.getStringExtra("session"))
                    assertTrue(AppEnvironment.select(context, it.intent).isSample)
                }
                waitFor("Recorder must be focused") { recorder.hasWindowFocus() }
                // Merely opening a screen without optional access must never start capture.
                Thread.sleep(500)
                assertNull(model.voice.state.value)
                recording.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                instrumentation.runOnMainSync { remote.confirm(session, recorder) }
                assertNull(model.voice.state.value)
                assertEquals("waiting", remote.sessions.last().status)
                recording.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                waitFor("Visible recorder focus") { recorder.hasWindowFocus() }
                // The disposable-emulator script drives the real native permission UI independently.
                val ready = java.io.File(context.cacheDir, "garmin-native-ready")
                val done = java.io.File(context.cacheDir, "garmin-native-done")
                ready.writeText(session)
                waitFor("Real Bluetooth route failure after external native Start/permission retry") { remote.sessions.last().status == "error" && model.voice.state.value?.phase == VoicePhase.INTERRUPTED }
                done.writeText(session)
                waitFor("Native UI evidence captured") { !ready.exists() }
                done.delete()
                assertTrue(model.voice.state.value!!.headsetOnly)
                assertTrue(model.voice.state.value!!.error.orEmpty().contains("Bluetooth"))
                instrumentation.runOnMainSync {
                    remote.receive(device, mapOf("v" to 1, "s" to session, "c" to "${session}_finish", "op" to "finish"))
                    remote.receive(device, mapOf("v" to 1, "s" to session, "c" to "${session}_start", "op" to "start"))
                    assertEquals(VoicePhase.INTERRUPTED, model.voice.state.value!!.phase)
                    model.voice.cancel()
                }
            }
        }
    }
    @Test fun finishRetainsOfflineAudioAndRetriesOneMessageToOriginalDestination() {
        // Real emulator phone microphone is a stand-in; this does not establish Bluetooth route success.
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            waitFor("Sample workspace missing") { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            instrumentation.runOnMainSync { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            waitFor("Channels missing") { model.channels.any { it.id == SampleIds.GENERAL } }
            val remote = model.garmin
            val device = IQDevice(72, "Synthetic stand-in watch")
            val session = "finish_" + System.currentTimeMillis()
            fun command(op: String) = mapOf("v" to 1, "s" to session, "c" to "${session}_$op", "op" to op)
            lateinit var record: RemoteSession
            instrumentation.runOnMainSync {
                model.voice.cancel()
                remote.sessions.filter { it.status == "waiting" }.forEach { remote.dismiss(it.session) }
                val c = model.connection!!; val w = model.workspaces.first { it.id == SampleIds.STUDIO }
                val destination = VoiceDestination.capture(c, w, ChatTarget(c.id, w.id, SampleIds.GENERAL), "Synthetic original destination")
                remote.bind(device, destination)
                record = remote.journal.begin(session)
                model.voice.start(destination, record.recording, headsetOnly = false)
            }
            waitFor("Actual emulator recording") { model.voice.state.value?.phase == VoicePhase.RECORDING }
            Thread.sleep(1200)
            model.sampleTower!!.offline = true
            instrumentation.runOnMainSync {
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.DESIGN)
                remote.receive(IQDevice(999, "Wrong watch"), command("finish"))
                assertEquals(VoicePhase.RECORDING, model.voice.state.value!!.phase)
                remote.receive(device, command("finish"))
                remote.receive(device, command("finish"))
            }
            waitFor("Offline retained draft") { remote.sessions.last().status == "error" }
            assertEquals(record.recording, model.voice.state.value!!.id)
            assertTrue(model.voice.drafts.audio(model.voice.state.value!!).length() > 0)
            model.sampleTower!!.offline = false
            instrumentation.runOnMainSync { model.refresh() }
            Thread.sleep(1500)
            instrumentation.runOnMainSync { remote.receive(device, command("retry")); remote.receive(device, command("retry")) }
            waitFor("Server acknowledged retry") { remote.sessions.last().status == "sent" }
            val owner = model.runtimeMap.values.first { it.workspace.id == SampleIds.STUDIO }
            val messages = kotlinx.coroutines.runBlocking { owner.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500) }
            assertEquals(1, messages.count { it.clientRequestId == record.recording })
            instrumentation.runOnMainSync { remote.receive(device, command("finish")); remote.receive(device, command("cancel")); remote.receive(device, command("start")) }
            assertEquals("sent", remote.sessions.last().status)
            assertNull(model.voice.state.value)
        }
    }

}
