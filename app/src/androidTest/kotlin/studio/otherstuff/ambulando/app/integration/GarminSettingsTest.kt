package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.garmin.RemoteJournal
import studio.otherstuff.ambulando.core.sample.SampleIds
import java.io.File

/** Real Settings widgets + SDK Binder connected-watch data; synthetic Tower only. */
@RunWith(AndroidJUnit4::class)
class GarminSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun selectsDestinationPersistsChangesAndDisablesWithoutComposerSetup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle {
                model.voice.cancel()
                model.garmin.sessions.filter { it.status == "waiting" }.forEach { model.garmin.dismiss(it.session) }
                model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO })
            }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle {
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)
                model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("watch.setup").assertDoesNotExist()
            compose.onNodeWithText("Watch recorder").assertDoesNotExist()
            scenario.onActivity { activity -> activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply { setContent { MaterialTheme { NativeSettings(model) {} } } }) }
            fun choose(channel: String) {
                compose.onNodeWithTag("watch.setup").performScrollTo().performClick()
                compose.onNodeWithTag("watch.workspaces").performClick()
                compose.onNodeWithTag("watch.workspace.${SampleIds.STUDIO}").performScrollTo().performClick()
                compose.onNodeWithTag("watch.channels").performClick()
                compose.onNodeWithTag("watch.channel.$channel").performScrollTo().performClick()
                compose.waitUntil(15_000) { model.garmin.devices.any { it.deviceIdentifier == 71L } }
                compose.onNodeWithTag("watch.devices").performClick()
                compose.onNodeWithTag("watch.device.71").performScrollTo().performClick()
                compose.onNodeWithTag("watch.save").performScrollTo().performClick()
            }
            choose(SampleIds.GENERAL)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/garmin-settings-saved.png")).use { it.readBytes() }
            compose.runOnIdle {
                assertEquals(SampleIds.GENERAL, model.garmin.binding!!.destination.channelId)
                assertEquals(model.garmin.binding, RemoteJournal(File(model.environment.baseDir, "garmin/remote.json")).data.binding)
            }
            val originalDestination = model.garmin.binding!!.destination
            compose.runOnIdle {
                model.voice.drafts.save(studio.otherstuff.ambulando.app.voice.VoiceDraft(destination = originalDestination, phase = studio.otherstuff.ambulando.app.voice.VoicePhase.RECORDING))
            }
            compose.onNodeWithTag("watch.setup").assertIsNotEnabled()
            compose.onNodeWithTag("watch.enable").assertIsNotEnabled()
            compose.runOnIdle { model.voice.drafts.save(model.voice.state.value!!.copy(phase = studio.otherstuff.ambulando.app.voice.VoicePhase.INTERRUPTED, error = "Retained fixture draft")) }
            val retainedDraft = model.voice.state.value!!
            val other = model.channels.first { it.id != SampleIds.GENERAL && !it.accessRemoved }.id
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id != SampleIds.STUDIO }) }
            compose.runOnIdle { assertEquals(SampleIds.GENERAL, model.garmin.binding!!.destination.channelId) }
            choose(other)
            compose.runOnIdle { assertEquals(other, model.garmin.binding!!.destination.channelId) }
            compose.runOnIdle { model.garmin.disable() }
            compose.runOnIdle { assertEquals(retainedDraft, model.voice.state.value); assertEquals(originalDestination, model.voice.state.value!!.destination); assertEquals(model.garmin.binding, RemoteJournal(File(model.environment.baseDir, "garmin/remote.json")).data.binding); assertNull(model.garmin.journal.data.consent) }
            compose.onNodeWithTag("watch.saved").assertExists()
        }
    }
}
