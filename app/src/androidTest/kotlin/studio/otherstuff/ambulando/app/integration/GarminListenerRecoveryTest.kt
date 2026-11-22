package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garmin.android.connectiq.IQDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.garmin.*
import studio.otherstuff.ambulando.app.voice.VoiceDestination
import studio.otherstuff.ambulando.app.voice.VoicePhase
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.ChatTarget
import java.io.File

/** Real foreground service loss/resume; synthetic watch commands never stand in for headset success. */
@RunWith(AndroidJUnit4::class)
class GarminListenerRecoveryTest {
    @Test fun stoppedListenerRecoversWithoutRenewingConsentOrReplayingStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".watchtest"))
        WatchNativeConsent.shell("input keyevent KEYCODE_WAKEUP")
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            WatchNativeConsent.waitFor("sample model") { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            val remote = model.garmin
            instrumentation.runOnMainSync {
                remote.disable(); model.voice.cancel()
                model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO })
            }
            WatchNativeConsent.waitFor("channels") { model.channels.any { it.id == SampleIds.GENERAL } }
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            WatchNativeConsent.grant(activity)
            WatchNativeConsent.overlay(activity, true)
            val device = IQDevice(71L, "Synthetic watch")
            scenario.onActivity {
                val c = model.connection!!; val w = model.workspaces.first { it.id == SampleIds.STUDIO }
                remote.bind(device, VoiceDestination.capture(c, w, ChatTarget(c.id, w.id, SampleIds.GENERAL), "Synthetic recovery destination"))
                remote.enable(it)
            }
            WatchNativeConsent.waitFor("listener started") { remote.listening }
            val consent = remote.journal.data.consent!!
            // Stop only the service, as Android may do, preserving explicit user consent.
            instrumentation.runOnMainSync { context.stopService(Intent(context, WatchControlService::class.java)) }
            WatchNativeConsent.waitFor("listener destroyed") { !remote.listening }
            assertTrue(remote.enabled)
            fun command(id: String) = mapOf("v" to 1, "b" to 38, "s" to id, "op" to "start", "c" to "${id}_start")
            Thread.sleep(1200)
            val lost = "${System.currentTimeMillis()/1000}_lost"
            instrumentation.runOnMainSync {
                remote.receive(device, command(lost))
                assertEquals("error", remote.journal.find(lost)!!.status)
                assertTrue(remote.journal.find(lost)!!.detail.contains("listener stopped"))
                assertTrue(remote.diagnostics.contains("A1"))
                assertNull(model.voice.state.value)
            }
            scenario.moveToState(Lifecycle.State.STARTED)
            instrumentation.runOnMainSync { remote.recoverListener(activity) }
            assertFalse(remote.listening) // No foreground-service restoration from a hidden Activity.
            scenario.moveToState(Lifecycle.State.RESUMED)
            WatchNativeConsent.waitFor("resume recovered listener") { remote.listening }
            scenario.onActivity { a ->
                a.setContentView(androidx.compose.ui.platform.ComposeView(a).apply {
                    setContent { androidx.compose.material3.MaterialTheme { GarminSetup(model) } }
                })
            }
            WatchNativeConsent.waitFor("current on state distinct from previous error") {
                val text = WatchNativeConsent.tree()
                text.contains("Watch control on") && text.contains("Last request: error") && text.contains("Watch control listening")
            }
            assertSame(remote, app.model(AppEnvironment.Sample(context)).garmin)
            assertEquals(consent, remote.journal.data.consent)
            assertEquals(consent, RemoteJournal(File(model.environment.baseDir, "garmin/remote.json")).data.consent)
            val fresh = "${System.currentTimeMillis()/1000}_fresh"
            instrumentation.runOnMainSync {
                remote.receive(device, command(lost))
                assertEquals("error", remote.journal.find(lost)!!.status)
                remote.receive(device, command(fresh))
                assertEquals("Confirming this Start with watch", remote.journal.find(fresh)!!.detail)
                assertNull(model.voice.state.value) // Fresh Start still needs the real wrist nonce receipt.
            }
            // Losing the listener during the receipt wait must not leave an immortal active session.
            instrumentation.runOnMainSync { context.stopService(Intent(context, WatchControlService::class.java)) }
            WatchNativeConsent.waitFor("pending Start terminated on listener loss") { !remote.listening && remote.journal.find(fresh)?.status == "error" }
            assertEquals(consent, remote.journal.data.consent)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            WatchNativeConsent.waitFor("second recovery") { remote.listening }
            val next = "${System.currentTimeMillis()/1000}_next"
            instrumentation.runOnMainSync {
                remote.receive(device, command(fresh))
                assertEquals("error", remote.journal.find(fresh)!!.status)
                remote.receive(device, command(next))
                assertEquals("Confirming this Start with watch", remote.journal.find(next)!!.detail)
                assertNull(model.voice.state.value)
                // Phone microphone is an explicit emulator stand-in solely for retained-audio cleanup.
                val record = remote.journal.find(next)!!
                model.voice.start(record.destination, record.recording, headsetOnly = false)
            }
            WatchNativeConsent.waitFor("stand-in recording") { model.voice.state.value?.phase == VoicePhase.RECORDING && remote.journal.find(next)?.status == "recording" }
            Thread.sleep(1200)
            instrumentation.runOnMainSync { context.stopService(Intent(context, WatchControlService::class.java)) }
            WatchNativeConsent.waitFor("recording stopped and retained") { !remote.listening && remote.journal.find(next)?.status == "error" && model.voice.state.value?.phase == VoicePhase.REVIEW }
            val retained = model.voice.state.value!!
            assertTrue(model.voice.drafts.audio(retained).length() > 0)
            assertEquals(consent, remote.journal.data.consent)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            WatchNativeConsent.waitFor("listener recovery keeps retained audio") { remote.listening }
            assertEquals(retained, model.voice.state.value)
            val disabled = "${System.currentTimeMillis()/1000}_disabled"
            instrumentation.runOnMainSync {
                model.voice.cancel()
                remote.receive(device, command(disabled))
                assertEquals("Confirming this Start with watch", remote.journal.find(disabled)!!.detail)
                remote.disable()
                remote.recoverListener(activity)
                assertFalse(remote.listening)
                assertNull(remote.journal.data.consent)
                assertNotNull(remote.binding)
                remote.receive(device, command(disabled))
                assertEquals("cancelled", remote.journal.find(disabled)!!.status)
                assertNull(model.voice.state.value)
            }
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse(remote.listening) // Disable cannot be undone by lifecycle recovery.
        }
    }
}
