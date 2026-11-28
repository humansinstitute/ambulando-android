package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.chat.ArtefactChatContext
import studio.otherstuff.ambulando.core.chat.ThreadArtefactMembership
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** Dedicated synthetic emulator; selection must never post a placeholder thread. */
@RunWith(AndroidJUnit4::class)
class NewThreadArtefactFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun selectRemoveReselectAndSendExactBoardContextBeforeFirstMessage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.active?.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            val target = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL)
            runBlocking {
                model.store.writer.saveDraft(target, Draft("", emptyList()))
                model.store.writer.setState("thread.artefacts.${target.draftKey}", null)
            }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.CHAT }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.inbox.new").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.inbox.new").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("thread.artefacts.open").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").performTextInput("Discuss the selected board")
            fun selectBoard() {
                compose.onNodeWithTag("thread.artefacts.open").performClick()
                compose.onNodeWithText("+ Browse").performClick()
                compose.onNodeWithTag("thread.artefacts.search").performTextInput("Architecture")
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("thread.artefacts.row.${SampleIds.BOARD_FILE}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("thread.artefacts.row.${SampleIds.BOARD_FILE}").performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("thread.artefacts.discuss").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { println("PRE_SEND_STATE " + model.threadArtefacts.mapValues { it.value.snapshot() }) }
                compose.onNodeWithTag("thread.artefacts.discuss").performClick()
                compose.waitForIdle()
                val diagnostic = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.getExternalFilesDir(null), "pre-send-switch.png").outputStream().use { out -> diagnostic.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out) }; diagnostic.recycle()
                compose.onNodeWithTag("thread.artefacts.target", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("chat.composer").assertTextContains("Discuss the selected board")
            }
            selectBoard()
            assertTrue(runBlocking { model.store.reader.outbox(target).isEmpty() })
            compose.onNodeWithText("Switch").performClick()
            compose.onNodeWithTag("thread.artefacts.remove.${SampleIds.BOARD_FILE}").performClick()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("thread.artefacts.target", useUnmergedTree = true).assertDoesNotExist()
            selectBoard()
            compose.onNodeWithTag("thread.artefacts.tag").performClick()
            compose.onNodeWithTag("chat.composer").assertTextContains("@Architecture", substring = true)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.getExternalFilesDir(null), "pre-send-selected-context.png").outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out) }
            bitmap.recycle()
            // Keep this device flow focused on successful first-send promotion and canonical membership.
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(30_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).map { it.message }.any { it.body.startsWith("Discuss the selected board") } } }
            val sent = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).map { it.message }.filter { it.body.startsWith("Discuss the selected board") } }
            assertEquals(1, sent.size)
            assertNotNull(sent.single().threadId)
            assertTrue(sent.single().body.contains("mention:file:${SampleIds.BOARD_FILE}"))
            val captured = sent.single().raw.getValue("metadata").jsonObject.getValue(ArtefactChatContext.KEY).jsonObject
            assertEquals(JsonNull, captured["threadID"])
            assertEquals(JsonPrimitive(SampleIds.BOARD_FILE), captured["visibleRecordID"])
            val createdTarget = target.copy(threadId = sent.single().threadId)
            compose.waitUntil(30_000) { model.threadArtefacts[createdTarget.draftKey]?.members?.any { it.id == SampleIds.BOARD_FILE } == true }
            val canonical = runBlocking { model.towerAPI(model.connection!!).thread(SampleIds.STUDIO, model.workspace!!.appNpub, requireNotNull(createdTarget.threadId)) }
            assertTrue(ThreadArtefactMembership.read(canonical.raw.getValue("metadata").jsonObject)!!.any { it.id == SampleIds.BOARD_FILE })
            compose.onNodeWithTag("thread.artefacts.target", useUnmergedTree = true).assertExists()
            assertTrue(captured.getValue("open").jsonArray.single().jsonObject.getValue("revision").jsonPrimitive.content.isNotBlank())
        }
    }
}
