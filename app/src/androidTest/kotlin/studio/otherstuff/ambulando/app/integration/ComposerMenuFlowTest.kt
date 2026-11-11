package studio.otherstuff.ambulando.app.integration

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.app.voice.VoicePhase
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.channelFeed

/** Real Android popup/picker/permissions/IME; workspace and uploads are synthetic fixtures. */
@RunWith(AndroidJUnit4::class)
class ComposerMenuFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun capture(name: String) { shell("screencap -p /sdcard/Download/composer-$name.png") }
    private fun launch(block: (AppModel) -> Unit) {
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle {
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)
                model.section = AppSection.CHAT
                model.voice.cancel()
            }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.attach") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            block(model)
        }
    }
    private fun assertCollapsed() {
        compose.onNodeWithTag("voice.panel").assertDoesNotExist()
        compose.onNodeWithTag("voice.record").assertDoesNotExist()
        compose.onNodeWithTag("chat.mention").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("chat.attach").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
    private fun openMenu() {
        compose.onNodeWithTag("chat.attach").performClick()
        compose.onNodeWithTag("voice.record").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat.attach.pick").assertIsDisplayed()
        compose.onNodeWithTag("chat.mention").assertIsDisplayed()
    }
    @Test fun menuPickerMentionDraftKeyboardAndReplyRecording() = launch { model ->
        assertCollapsed()
        capture("collapsed")
        compose.onNodeWithTag("chat.composer").performTextReplacement("Keep this draft")
        openMenu()
        capture("expanded")
        Espresso.pressBack()
        assertCollapsed()
        compose.onNodeWithTag("chat.composer").assertTextContains("Keep this draft", substring = true)
        openMenu()
        // Outside the anchored popup, in the app header. Dismissal must not select an action.
        shell("input tap 1000 150")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice.record").fetchSemanticsNodes().isEmpty() }
        assertNull(model.voice.state.value)
        compose.onNodeWithTag("chat.mention").performClick()
        compose.onNodeWithTag("chat.composer").assertTextContains("@", substring = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat.mentions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat.attach").assertIsDisplayed()
        compose.onNodeWithTag("chat.mention").assertIsDisplayed()
        compose.onNodeWithTag("chat.composer.area").assertHeightIsAtLeast(96.dp)
        capture("keyboard")
        assertTrue("Real IME is shown", shell("dumpsys input_method").contains("mInputShown=true"))
        compose.onNodeWithText("Ana sample", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("chat.composer").assertTextContains("@Ana sample", substring = true)
        openMenu()
        capture("keyboard-expanded")
        compose.onNodeWithTag("chat.attach.pick").performClick()
        Thread.sleep(1200)
        assertTrue("Android document picker opened", shell("dumpsys activity activities").contains("com.google.android.documentsui"))
        capture("picker")
        shell("input keyevent KEYCODE_BACK")
        compose.waitForIdle()
        compose.onNodeWithTag("voice.record").assertDoesNotExist()
        compose.onNodeWithTag("chat.composer").assertTextContains("Keep this draft", substring = true)
        compose.onNodeWithTag("chat.composer").performTextReplacement("Composer menu send ${System.nanoTime()}")
        val body = compose.onNodeWithTag("chat.composer").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).any { it.message.body == body } } }
        Espresso.closeSoftKeyboard()
        val post = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300) }.first { it.replyCount > 0 }
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${post.message.id}"))
        compose.onNodeWithTag("chat.thread.${post.message.threadId}", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("thread.actions").fetchSemanticsNodes().isNotEmpty() }
        assertCollapsed()
        compose.onNodeWithTag("chat.composer").performTextReplacement("Reply draft")
        openMenu()
        capture("reply-menu")
        compose.onNodeWithTag("voice.record").performClick()
        compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.RECORDING }
        assertEquals(post.message.threadId, model.voice.state.value!!.destination.threadId)
        compose.onNodeWithTag("voice.record").assertDoesNotExist()
        compose.onNodeWithTag("chat.mention").assertIsDisplayed()
        capture("reply-recording")
        compose.onNodeWithTag("voice.stop").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.REVIEW }
        compose.onNodeWithTag("voice.send").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice.cancel").performScrollTo().performClick()
        assertCollapsed()
        compose.onNodeWithTag("chat.composer").assertTextContains("Reply draft", substring = true)
    }
    @Test fun newThreadKeepsMentionOutsideTheMenuAndCapturesChannel() = launch { model ->
        compose.runOnIdle { model.section = AppSection.DECK }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.page.inbox").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.page.inbox").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.fab.new").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.fab.new").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.attach") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertCollapsed()
        compose.onNodeWithTag("chat.mention").performClick()
        compose.onNodeWithTag("chat.composer").assertTextContains("@", substring = true)
        openMenu()
        capture("new-thread-menu")
        compose.onNodeWithTag("voice.record").performClick()
        compose.waitUntil(10_000) { model.voice.state.value?.phase == VoicePhase.RECORDING }
        assertEquals(SampleIds.GENERAL, model.voice.state.value!!.destination.channelId)
        assertNull(model.voice.state.value!!.destination.threadId)
        compose.onNodeWithTag("voice.cancel").performScrollTo().performClick()
        assertCollapsed()
    }
    @Test fun deniedMicrophoneLeavesDraftMentionAndAttachmentUsable() = launch { model ->
        // Run this method separately after revoking RECORD_AUDIO, before launching instrumentation.
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        compose.onNodeWithTag("chat.composer").performTextReplacement("Permission draft")
        openMenu()
        compose.onNodeWithTag("voice.record").performClick()
        fun deny(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.viewIdResourceName?.endsWith(":id/permission_deny_button") == true) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            for (i in 0 until node.childCount) if (deny(node.getChild(i))) return true
            return false
        }
        var denied = false
        compose.waitUntil(10_000) { deny(instrumentation.uiAutomation.rootInActiveWindow).also { denied = it } }
        assertTrue(denied)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("voice.permission.error").fetchSemanticsNodes().isNotEmpty() }
        assertNull(model.voice.state.value)
        compose.onNodeWithTag("voice.record").assertDoesNotExist()
        compose.onNodeWithTag("chat.composer").assertTextContains("Permission draft", substring = true)
        compose.onNodeWithTag("chat.mention").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat.composer").assertTextContains("@", substring = true)
        openMenu()
        compose.onNodeWithTag("chat.attach.pick").assertIsEnabled()
        capture("permission-denied")
        Espresso.pressBack()
    }
}
