package studio.otherstuff.ambulando.app

import android.content.Intent
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.conversationAgentActivities
import studio.otherstuff.ambulando.core.store.replaceAgentActivities
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord
import java.time.Instant

/** Full synthetic conversation flow; uses no personal account or production activity. */
@RunWith(AndroidJUnit4::class)
class AgentProgressConversationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val id = java.util.UUID.randomUUID().toString()
    private val activityId = "synthetic-ui-$id"
    private val turnId = "synthetic-turn-$id"
    private fun activity(state: String, sequence: Int, latest: String = "Latest public update") = AgentActivityRecord.parse(buildJsonObject {
        put("id", id); put("workspace_id", SampleIds.STUDIO); put("channel_id", SampleIds.GENERAL)
        put("thread_id", SampleIds.thread(39)); put("trigger_message_id", SampleIds.message(39))
        put("activity_id", activityId); put("turn_id", turnId); put("agent_npub", SampleIds.ORBIT)
        put("visibility", "user_visible"); put("state", state); put("sequence", sequence)
        put("created_at", Instant.now().toString()); put("updated_at", Instant.now().toString())
        put("lease_expires_at", Instant.now().plusSeconds(180).toString())
        putJsonArray("commentary_history") {
            listOf("Earlier public update", latest).forEachIndexed { index, body -> add(buildJsonObject {
                put("turn_id", turnId); put("activity_id", activityId); put("sequence", if (index == 0) 1 else sequence - 1); put("body", body)
            }) }
        }
    })
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "agent-progress-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun conversationWorkingHistoryCompletionReopenPreservesComposer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle {
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.CHAT
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.messages").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { model.store.writer.replaceAgentActivities(SampleIds.STUDIO, SampleIds.GENERAL, listOf(activity("working", 3))) }
            fun openThread() {
                compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${SampleIds.message(39)}"))
                compose.onNodeWithTag("chat.message.${SampleIds.message(39)}").performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.agent.current.$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            }
            openThread()
            compose.onNodeWithTag("chat.agent.current.$id", useUnmergedTree = true).assertTextContains("Working", substring = true)
            compose.onNodeWithTag("chat.agent.latest.$id").assertTextContains("Latest public update")
            capture("working")
            runBlocking { model.store.writer.replaceAgentActivities(SampleIds.STUDIO, SampleIds.GENERAL, listOf(activity("working", 4, "New public update while working"))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New public update while working").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.agent.latest.$id").assertIsDisplayed()
            capture("updated")
            compose.onNodeWithTag("chat.composer").performTextReplacement("Keep my draft")
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.agent.history.$id"))
            compose.onNodeWithTag("chat.agent.history.$id").performClick()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.agent.update.$id.1"))
            compose.onNodeWithTag("chat.agent.update.$id.1").assertTextContains("Earlier public update")
            capture("history")
            runBlocking { model.store.writer.replaceAgentActivities(SampleIds.STUDIO, SampleIds.GENERAL, listOf(activity("completed", 5))) }
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.agent.history.$id"))
            runBlocking { org.junit.Assert.assertEquals("completed", model.store.reader.conversationAgentActivities(SampleIds.STUDIO, SampleIds.GENERAL, SampleIds.thread(39)).single { it.id == id }.state) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Earlier activity · completed", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.agent.current.$id", useUnmergedTree = true).assertTextContains("Earlier activity · completed")
            compose.onNodeWithTag("chat.agent.latest.$id").assertDoesNotExist()
            compose.onNodeWithTag("chat.agent.update.$id.1").assertDoesNotExist()
            compose.onNodeWithText("Working in Sample Studio").assertDoesNotExist()
            compose.onNodeWithText("Workspace: Sample Studio").assertExists()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.agent.history.$id"))
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.agent.$id"))
            capture("completed")
            compose.onNodeWithTag("nav.back").performClick()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${SampleIds.message(39)}"))
            compose.onNodeWithTag("chat.message.${SampleIds.message(39)}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextEquals("Keep my draft")
            compose.onNodeWithTag("chat.agent.update.$id.1").assertDoesNotExist()
            compose.onNodeWithTag("chat.agent.latest.$id").assertDoesNotExist()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.agent.history.$id"))
            capture("reopened-collapsed")
            compose.onNodeWithTag("chat.agent.history.$id").performClick()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.agent.update.$id.1"))
            compose.onNodeWithTag("chat.agent.update.$id.1").assertExists()
            capture("reopened-history")
        }
    }
}
