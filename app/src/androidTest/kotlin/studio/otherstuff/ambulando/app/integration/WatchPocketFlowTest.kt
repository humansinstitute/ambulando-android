package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garmin.android.connectiq.IQDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.garmin.*
import studio.otherstuff.ambulando.app.voice.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.ChatTarget

/** Synthetic external watch SDK inputs; real OS consent, service, focused Activity and absent headset. */
@RunWith(AndroidJUnit4::class)
class WatchPocketFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = WatchNativeConsent.shell(command)
    private fun input(session: String, op: String = "start", extra: String = "") {
        shell("am start -n com.garmin.android.apps.connectmobile/studio.otherstuff.ambulando.garminfixture.SenderActivity --es target ${context.packageName} --es session $session --es op $op $extra")
    }
    @Test fun consentReceiptFocusHeadsetFailureDisableAndReplay() {
        check(context.packageName.endsWith(".watchtest"))
        shell("input keyevent KEYCODE_WAKEUP")
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            WatchNativeConsent.waitFor("native state") { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            val remote = model.garmin
            instrumentation.runOnMainSync {
                remote.disable(); model.voice.cancel()
                model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO })
            }
            WatchNativeConsent.waitFor("native state") { model.channels.any { it.id == SampleIds.GENERAL } }
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            WatchNativeConsent.grant(activity!!)
            WatchNativeConsent.overlay(activity!!, true)
            instrumentation.runOnMainSync {
                val c = model.connection!!; val w = model.workspaces.first { it.id == SampleIds.STUDIO }
                remote.bind(IQDevice(71L, "Synthetic watch"), VoiceDestination.capture(c, w, ChatTarget(c.id, w.id, SampleIds.GENERAL), "Synthetic saved conversation"))
            }
            scenario.onActivity { a -> a.setContentView(androidx.compose.ui.platform.ComposeView(a).apply {
                setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { GarminSetup(model) } } }
            }) }
            // Explicit ordinary application consent, never inferred from pre-existing grants/binding.
            WatchNativeConsent.waitFor("enable button") { WatchNativeConsent.click("Enable watch control") }
            WatchNativeConsent.waitFor("explicit consent button") { WatchNativeConsent.click("Enable automatic headset recording") }
            WatchNativeConsent.waitFor("native state") { remote.enabled && remote.listening && remote.devices.any { it.deviceIdentifier == 71L } }
            assertTrue(shell("dumpsys activity services ${context.packageName}").contains("WatchControlService"))
            assertEquals(remote.binding, remote.journal.data.consent!!.binding)
            Thread.sleep(1200)
            val old = "${System.currentTimeMillis()/1000 - 60}_1"
            input(old)
            WatchNativeConsent.waitFor("native state") { remote.sessions.any { it.session == old && it.status == "error" } }
            assertNull(model.voice.state.value)
            // Drain this old-session reply by its real fixture nonce receipt.
            input(old, "receipt", "--ez echoReply true")
            Thread.sleep(500)
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            Thread.sleep(1000)
            val fresh = "${System.currentTimeMillis()/1000}_2"
            input(fresh)
            WatchNativeConsent.waitFor("native state") { remote.sessions.any { it.session == fresh && it.detail.contains("Confirming") } }
            assertNull(model.voice.state.value) // SDK Start is forgeable; never enough to open/capture.
            assertFalse(shell("dumpsys activity activities").contains("mResumedActivity: ActivityRecord") && shell("dumpsys activity activities").substringAfter("mResumedActivity:").substringBefore('\n').contains("WatchRecordingActivity"))
            input(fresh, "receipt", "--ez echoReply true --es badReceipt nonce")
            Thread.sleep(600)
            assertNull(model.voice.state.value)
            input(fresh, "receipt", "--ez echoReply true")
            WatchNativeConsent.waitFor("native state") { remote.sessions.any { it.session == fresh && it.status == "error" } }
            val draft = requireNotNull(model.voice.state.value)
            assertTrue(draft.headsetOnly)
            assertEquals(VoicePhase.INTERRUPTED, draft.phase)
            assertTrue("Actual missing headset error: ${draft.error}", draft.error.orEmpty().contains("Bluetooth"))
            assertTrue(shell("dumpsys activity activities").contains("WatchRecordingActivity"))
            input(fresh)
            Thread.sleep(600)
            assertEquals(draft.id, model.voice.state.value!!.id)
            instrumentation.runOnMainSync { remote.disable() }
            assertFalse(remote.enabled)
            assertNull(remote.journal.data.consent)
            assertNotNull(remote.binding)
            assertEquals(draft, model.voice.state.value)
            input(fresh, "receipt", "--ez echoReply true")
            Thread.sleep(500)
            assertEquals(draft, model.voice.state.value)
            assertFalse(shell("dumpsys activity services ${context.packageName}").contains("isForeground=true"))
            // Revoke through ordinary OS Settings while a new Start is waiting for its receipt.
            shell("input keyevent KEYCODE_BACK")
            WatchNativeConsent.waitFor("main setup visible") { activity!!.hasWindowFocus() }
            instrumentation.runOnMainSync { model.voice.cancel() }
            WatchNativeConsent.waitFor("enable again") { WatchNativeConsent.click("Enable watch control") }
            WatchNativeConsent.waitFor("consent again") { WatchNativeConsent.click("Enable automatic headset recording") }
            WatchNativeConsent.waitFor("listener enabled again") { remote.enabled && remote.listening }
            Thread.sleep(1200)
            val revoked = "${System.currentTimeMillis()/1000}_3"
            input(revoked)
            WatchNativeConsent.waitFor("receipt pending before revoke") { remote.sessions.last().detail.contains("Confirming") }
            WatchNativeConsent.overlay(activity!!, false)
            WatchNativeConsent.waitFor("revocation invalidates consent") { !remote.enabled && remote.journal.data.consent == null }
            input(revoked, "receipt", "--ez echoReply true")
            Thread.sleep(500)
            assertNull(model.voice.state.value)
            assertTrue(remote.listening) // Retained solely to return an actionable wrist error.
            val denied = "${System.currentTimeMillis()/1000}_4"
            input(denied)
            WatchNativeConsent.waitFor("revoked Start error") { remote.sessions.last().session == denied && remote.sessions.last().status == "error" }
            assertTrue(remote.sessions.last().detail.contains("Display over other apps"))
            assertNull(model.voice.state.value)
            WatchNativeConsent.overlay(activity!!, true)
            WatchNativeConsent.waitFor("enable before channel revoke") { WatchNativeConsent.click("Enable watch control") }
            WatchNativeConsent.waitFor("consent before channel revoke") { WatchNativeConsent.click("Enable automatic headset recording") }
            WatchNativeConsent.waitFor("channel revoke listener ready") { remote.enabled && remote.listening }
            Thread.sleep(1200)
            val blocked = "${System.currentTimeMillis()/1000}_5"
            input(blocked)
            WatchNativeConsent.waitFor("channel revoke receipt pending") { remote.sessions.last().detail.contains("Confirming") }
            WatchNativeConsent.blockWatchChannel(activity!!)
            WatchNativeConsent.waitFor("channel revocation invalidates consent") { !remote.enabled && remote.journal.data.consent == null }
            assertTrue(remote.permissionProblem().orEmpty().contains("watch control notifications"))
            input(blocked, "receipt", "--ez echoReply true")
            Thread.sleep(300)
            assertNull(model.voice.state.value)
            instrumentation.runOnMainSync { remote.disable() }

        }
    }
}
