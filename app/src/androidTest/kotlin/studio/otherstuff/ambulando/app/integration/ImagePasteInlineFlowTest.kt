package studio.otherstuff.ambulando.app.integration

import android.content.*
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
import studio.otherstuff.ambulando.core.chat.ComposerUpload
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.MessageRecord

/** Platform input/clipboard and UI are real; Tower/storage responses use the labelled synthetic backend. */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class ImagePasteInlineFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun launch(block: (AppModel) -> Unit) {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { model.sampleTower!!.offline = false }
            try { block(model) } finally { compose.runOnIdle { model.sampleTower!!.offline = false } }
        }
    }
    private fun input() = compose.onNodeWithTag("chat.composer")
    private fun text() = input().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
    private fun ime(op: String): String {
        input().performClick()
        var result = ""
        compose.waitUntil(10_000) {
            result = shell("am broadcast -a ${ComposerProbeIme.ACTION} --es op $op")
            Thread.sleep(100)
            result.contains("accepted=true")
        }
        assertTrue(result, result.contains("mime=image/*"))
        android.util.Log.i("ImagePasteEvidence", result)
        return result
    }
    private fun edit(message: MessageRecord) {
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${message.id}"))
        compose.onNodeWithTag("message.${message.id}.actions", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("message.${message.id}.menu.edit", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("chat.edit.banner").assertExists()
        compose.onNodeWithTag("chat.edit.field").assertDoesNotExist()
    }
    private fun clickNativePaste(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.text?.toString() == "Paste") {
            if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) || node.parent?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        for (i in 0 until node.childCount) if (clickNativePaste(node.getChild(i))) return true
        return false
    }
    @Test fun actualKeyboardCommitAndContextAndExplicitImagePaste() = launch { model ->
        // Driver shares a synthetic image through the verified released 2clip APK before this test.
        input().performTextReplacement("Image paste ")
        val start = text()
        ime("image")
        compose.waitUntil(20_000) { text().contains("storage://") }
        assertTrue(text().startsWith(start)); assertFalse(text().contains("content://"))
        assertTrue(model.uploads.items.value.none { it.phase is ComposerUpload.Phase.Failed })
        input().performTextReplacement("Context ")
        ime("context")
        compose.waitUntil(20_000) { text().contains("storage://") }
        input().performTextReplacement("Toolbar ")
        input().performTouchInput { longClick(androidx.compose.ui.geometry.Offset(20f, 20f)) }
        shell("screencap -p /sdcard/Download/image-paste-toolbar.png")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        compose.waitUntil(10_000) {
            val native = instrumentation.uiAutomation.windows.any { clickNativePaste(it.root) }
            val menu = compose.onAllNodesWithText("Paste").fetchSemanticsNodes()
            if (!native && menu.size == 1) { compose.onNodeWithText("Paste").performClick(); true } else native
        }
        compose.waitUntil(20_000) { text().contains("storage://") }
        input().performTextReplacement("Explicit ")
        compose.onNodeWithTag("chat.attach").performClick()
        compose.onNodeWithTag("chat.attach.paste").performClick()
        compose.waitUntil(20_000) { text().contains("storage://") }
        input().performTextReplacement("Legacy ")
        ime("legacy")
        compose.waitUntil(20_000) { text().contains("storage://") }
        input().performTextReplacement("Text ")
        compose.runOnIdle { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("synthetic", "normal paste")) }
        ime("context")
        compose.waitUntil(5_000) { text().contains("normal paste") }
        assertFalse(text().contains("storage://"))
        shell("screencap -p /sdcard/Download/image-paste-verified.png")
    }
    @Test fun channelAndThreadEditMentionSaveCancelDraftAndQueuedUpdate() = launch { model ->
        val unique = "Inline original ${System.nanoTime()}"
        input().performTextReplacement(unique); compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).any { it.message.body == unique } } }
        val original = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).first { it.message.body == unique }.message }
        val target = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL)
        input().performTextReplacement("Reply draft keep")
        input().performTextInputSelection(androidx.compose.ui.text.TextRange(5))
        edit(original)
        input().assertTextContains(unique)
        input().performTextReplacement("Cancelled edit")
        compose.onNodeWithTag("chat.edit.cancel").performClick()
        assertEquals("Reply draft keep", text())
        assertEquals(androidx.compose.ui.text.TextRange(5), input().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange])
        edit(original)
        input().performTextReplacement("Edited $unique ")
        compose.onNodeWithTag("chat.mention").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat.mentions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ana sample", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("chat.attach").assertIsEnabled()
        compose.runOnIdle { model.sampleTower!!.offline = true }
        compose.onNodeWithTag("chat.edit.save").performClick()
        assertEquals("Reply draft keep", text())
        compose.waitUntil(10_000) { runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, SampleIds.STUDIO).any { it.kind == WriteKind.MESSAGE_EDIT && it.targetId == original.id } } }
        val queued = runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, SampleIds.STUDIO).first { it.kind == WriteKind.MESSAGE_EDIT && it.targetId == original.id } }
        assertTrue(queued.string("body")!!.contains("mention:")); assertTrue(queued.payloadObject["mentions"].toString().contains("npub"))
        assertEquals("Reply draft keep", runBlocking { model.store.reader.draft(target) }!!.text)
        compose.waitUntil(15_000) { runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, SampleIds.STUDIO).any { it.id == queued.id && it.state == WriteState.RETRYING } } }
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("message.${original.id}.edit.status"))
        compose.onNodeWithTag("message.${original.id}.edit.status").assertExists()
        compose.runOnIdle { model.sampleTower!!.offline = false }
        compose.onNode(hasTestTag("write.retry") and hasAnyAncestor(hasTestTag("message.${original.id}.edit.status"))).performClick()
        compose.waitUntil(30_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).any { it.message.id == original.id && it.message.body.contains("Edited") } } }
        assertEquals(1, model.sampleTower!!.messageBodies(SampleIds.STUDIO).count { it.contains("Edited $unique") })
        val threadPost = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).first { it.replyCount > 0 } }
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${threadPost.message.id}"))
        compose.onNodeWithTag("chat.thread.${threadPost.message.threadId}", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("thread.actions").fetchSemanticsNodes().isNotEmpty() }
        val reply = "Thread original ${System.nanoTime()}"
        input().performTextReplacement(reply); compose.onNodeWithTag("chat.send").performClick()
        val tid = threadPost.message.threadId!!
        compose.waitUntil(20_000) { runBlocking { model.store.reader.threadMessages(SampleIds.STUDIO, tid, 300).any { it.body == reply } } }
        val message = runBlocking { model.store.reader.threadMessages(SampleIds.STUDIO, tid, 300).first { it.body == reply } }
        input().performTextReplacement("Thread draft")
        edit(message); input().performTextReplacement("Thread edited")
        compose.onNodeWithTag("chat.edit.save").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.threadMessages(SampleIds.STUDIO, tid, 300).any { it.id == message.id && it.body == "Thread edited" } } }
        assertEquals("Thread draft", text())
        shell("screencap -p /sdcard/Download/inline-edit-verified.png")
    }
    @Test fun aCancelledAndSwitchedImageEditsCannotChangeReplyDraft() = launch { model ->
        val suffix = System.nanoTime()
        val originals = (1..2).map { n ->
            val body = "Isolation $suffix message$n"
            input().performTextReplacement(body); compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).any { it.message.body == body } } }
            runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).first { it.message.body == body }.message }
        }
        input().performTextReplacement("Isolation reply keep")
        val target = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL)
        edit(originals[0])
        compose.runOnIdle { model.sampleTower!!.offline = true }
        ime("image")
        compose.waitUntil(15_000) { model.uploads.items.value.any { it.phase is ComposerUpload.Phase.Failed && it.retryable } }
        val cancelledIds = model.uploads.items.value.map { it.id }.toSet()
        edit(originals[1]) // switching cancels only the previous edit's uploads, keeps the original reply
        assertEquals(originals[1].body, text())
        assertTrue(model.uploads.items.value.none { it.id in cancelledIds })
        ime("image")
        compose.waitUntil(15_000) { model.uploads.items.value.any { it.phase is ComposerUpload.Phase.Failed && it.retryable } }
        compose.onNodeWithTag("chat.edit.cancel").performClick()
        assertEquals("Isolation reply keep", text())
        compose.runOnIdle { model.sampleTower!!.offline = false }
        compose.waitUntil(10_000) { model.uploads.items.value.isEmpty() }
        assertEquals("Isolation reply keep", runBlocking { model.store.reader.draft(target) }!!.text)
        assertEquals("Isolation reply keep", text())
        originals.forEach { message -> assertEquals(message.body, runBlocking { model.store.reader.message(SampleIds.STUDIO, message.id) }!!.body) }
    }

}
