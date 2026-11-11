package studio.otherstuff.ambulando.app.integration

import android.app.NotificationManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.app.voice.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.MessageAttachment

/** Real Android MediaRecorder/service/lock/playback; all uploads use the labelled in-process synthetic Tower. */
@RunWith(AndroidJUnit4::class)
class VoiceRecordingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    @Test fun newThreadRetainsUploadedAudioAfter409AndOffersSafeRetryAfterReopen() {
        val context = instrumentation.targetContext
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            val general = model.channels.first { it.id == SampleIds.GENERAL }
            compose.runOnIdle { model.voice.cancel(); model.selectContext(general.scopeId, general.id); model.section = AppSection.DECK }
            compose.onNodeWithTag("deck.page.inbox").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.fab.new").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.fab.new").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").performTextReplacement("Keep typing beside the recording")
            compose.onNodeWithTag("chat.attach").performClick()
            compose.onNodeWithTag("voice.record").performClick()
            compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.RECORDING }
            val draft = model.voice.state.value!!
            Thread.sleep(1500)
            compose.runOnIdle { model.voice.stop() }
            compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.REVIEW }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            model.sampleTower!!.attachmentConflictNextSend = true
            compose.onNodeWithTag("voice.send").performClick()
            val runtime = model.runtimeMap.values.first { it.workspace.id == SampleIds.STUDIO }
            compose.waitUntil(30_000) { runBlocking { runtime.store.reader.outboxItem(draft.id)?.lastStatus == 409 } }
            val failed = runBlocking { runtime.store.reader.outboxItem(draft.id)!! }
            assertEquals("message_attachment_conflict", failed.lastCode)
            assertTrue(failed.attachments.single().complete)
            val staged = java.io.File(runtime.outbox.staging!!, failed.attachments.single().staged)
            assertTrue(staged.isFile)
            assertTrue(staged.length() > 0)
            assertEquals("Keep typing beside the recording", runBlocking { runtime.store.reader.draft(draft.destination.target)!!.text })
            compose.onNodeWithTag("deck.compose.retry").assertExists()
            shell("screencap -p /sdcard/Download/ambulando-audio409-retained.png")
            scenario.recreate()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.compose.retry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.compose.cancel").performClick()
            // Navigate away then return: recovery stays bound to the original channel's durable row.
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.DESIGN) }
            compose.runOnIdle { model.selectContext(general.scopeId, general.id) }
            compose.onNodeWithTag("deck.fab.new").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.compose.recover").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("deck.compose.recover").onLast().performClick()
            compose.onNodeWithTag("deck.compose.retry").performClick()
            compose.waitUntil(30_000) { runBlocking { runtime.store.reader.outboxItem(draft.id) == null } }
            val saved = runBlocking { runtime.store.reader.latestMessages(SampleIds.STUDIO, general.id, 500).filter { it.clientRequestId == draft.id } }
            assertEquals(1, saved.size)
            assertNotNull(saved.single().threadId)
            assertEquals(failed.attachments.single().objectId, MessageAttachment.from(saved.single().raw).single().storageObjectId)
            assertFalse(staged.exists())
            assertEquals("Keep typing beside the recording", runBlocking { runtime.store.reader.draft(draft.destination.target)!!.text })
        }
    }

    @Test fun realCaptureAcrossRotationNavigationAndScreenOffThenNotificationStopAndOfflineRetry() {
        val context = instrumentation.targetContext
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.CHAT; model.voice.cancel() }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.attach") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").performTextReplacement("Preserve the typed draft")
            compose.onNodeWithTag("chat.attach").performClick()
            compose.onNodeWithTag("voice.record").performClick()
            compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.RECORDING }
            val draft = model.voice.state.value!!
            shell("screencap -p /sdcard/Download/ambulando-voice-recording.png")
            scenario.recreate()
            assertEquals(draft.id, model.voice.state.value!!.id)
            compose.runOnIdle { model.channels.first { it.id != SampleIds.GENERAL }.let { model.selectContext(it.scopeId, it.id) } }
            assertEquals(SampleIds.GENERAL, model.voice.state.value!!.destination.channelId)
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            Thread.sleep(6500)
            assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
            assertEquals(VoicePhase.RECORDING, model.voice.state.value!!.phase)
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.notification.channelId == "voice_recording_v1" }
            assertTrue(notification.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0)
            // Invoke the real notification's Stop PendingIntent while the screen is still off.
            notification.notification.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
            Thread.sleep(1500)
            assertEquals(VoicePhase.REVIEW, model.voice.state.value!!.phase)
            val audio = model.voice.drafts.audio(draft)
            val metadata = MediaMetadataRetriever()
            val duration = try { metadata.setDataSource(audio.absolutePath); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() } finally { metadata.release() }
            assertTrue("AAC duration includes the screen-off period: $duration", duration >= 6000)
            val player = MediaPlayer()
            try { player.setDataSource(audio.absolutePath); player.prepare(); player.start(); Thread.sleep(250); assertTrue(player.isPlaying) } finally { player.release() }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            context.startActivity(intent)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("voice.send").fetchSemanticsNodes().isNotEmpty() }
            shell("screencap -p /sdcard/Download/ambulando-voice-review.png")
            model.sampleTower!!.offline = true
            compose.runOnIdle { model.sendVoice() }
            compose.waitUntil(20_000) { model.voice.state.value?.error != null }
            assertEquals(draft.id, model.voice.state.value!!.id)
            assertTrue(audio.isFile)
            model.sampleTower!!.offline = false
            compose.runOnIdle { model.refresh() }
            // Foreground runtime reconnects its existing write gate; retry is explicit and keeps the original request id.
            Thread.sleep(3000)
            compose.runOnIdle { model.sendVoice(); model.sendVoice() }
            compose.waitUntil(30_000) { model.voice.state.value == null }
            val originalRuntime = model.runtimeMap.values.first { it.workspace.id == SampleIds.STUDIO }
            compose.waitUntil(30_000) { runBlocking { originalRuntime.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.clientRequestId == draft.id } } }
            val messages = runBlocking { originalRuntime.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).filter { it.clientRequestId == draft.id } }
            assertEquals(1, messages.size)
            val attachment = MessageAttachment.from(messages.single().raw).single()
            assertEquals("audio/mp4", attachment.mimeType)
            assertTrue(attachment.filename.endsWith(".m4a"))
            assertEquals("Preserve the typed draft", runBlocking { originalRuntime.store.reader.draft(draft.destination.target)!!.text })
            assertFalse(audio.exists())
            // Explicit cancellation removes both audio and destination journal, without producing a message.
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("chat.attach") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.attach").performClick()
            compose.onNodeWithTag("voice.record").performClick()
            compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.RECORDING }
            val cancelled = model.voice.state.value!!
            Thread.sleep(1100)
            compose.onNodeWithTag("voice.cancel").performClick()
            assertNull(model.voice.state.value)
            assertFalse(model.voice.drafts.audio(cancelled).exists())
        }
    }
}
