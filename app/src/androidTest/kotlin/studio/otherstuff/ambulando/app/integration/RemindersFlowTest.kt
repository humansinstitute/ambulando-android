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
import studio.otherstuff.ambulando.core.management.ScopeChannelRequests
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** Isolated synthetic device flow. Provisioning below is test setup, never the reminder action. */
@RunWith(AndroidJUnit4::class)
class RemindersFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun saveOpenSourceAndPinnedBoard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            val c = model.connection!!; val w = model.workspace!!
            val api = model.towerAPI(c)
            fun post(path: String, body: JsonObject) = runBlocking {
                Json.parseToJsonElement(api.client.request(method = "POST", path = path, appNpub = w.appNpub, jsonBody = jsonBody(body)).decodeToString()).jsonObject
            }
            val base = TowerAPI.base(w.id)
            val scope = ScopeRecord.parse(post("$base/scopes", jsonObjectOf("name" to "Reminder test private conversations", "kind" to "dm")).getValue("scope").jsonObject)
            val request = ScopeChannelRequests.createDirectMessage(w.id, scope.id, c.signerNpub, c.signerNpub)
            val board = ChannelRecord.parse(post(request.path, request.body!!).getValue("channel").jsonObject)
            runBlocking { model.sync.refreshWorkspace(c, w); model.sharedEngine.waitUntilIdle() }
            compose.waitUntil(20_000) { model.channels.any { it.id == board.id } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("section.chat").fetchSemanticsNodes().isNotEmpty() }
            // Open the known seeded conversation through its native record route.
            compose.runOnIdle { model.pendingRecordOpen = "thread" to SampleIds.thread(1) }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("thread.actions").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.actions").performClick()
            compose.onNodeWithTag("thread.menu.remind").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Reminder saved").fetchSemanticsNodes().isNotEmpty() }
            val saved = runBlocking { model.store.reader.channelTasks(w.id, board.id).single() }
            assertEquals("ready", saved.state); assertEquals(c.signerNpub, saved.assignedToNpub)
            assertTrue(saved.description!!.contains("mention:chat:${SampleIds.GENERAL}#${SampleIds.message(1)}"))
            compose.onNodeWithTag("reminder.openTask").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Original message", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Original message", substring = true).performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat.message.${SampleIds.message(1)}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.message.${SampleIds.message(1)}").assertIsDisplayed()
            // Return to a section root, then open the permanent shortcut.
            compose.runOnIdle { model.pendingRecordOpen = "channel" to SampleIds.GENERAL }
            repeat(4) { if (compose.onAllNodesWithTag("sidebar.open").fetchSemanticsNodes().isEmpty()) androidx.test.espresso.Espresso.pressBack() }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("sidebar.open").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sidebar.open").performClick()
            compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("sidebar"))).performScrollToNode(hasContentDescription("Reminders in ${w.label}"))
            compose.onNodeWithContentDescription("Reminders in ${w.label}").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("reminders.list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminders.rows").performScrollToNode(hasTestTag("reminder.${saved.id}"))
            compose.onNodeWithTag("reminder.${saved.id}").assertIsDisplayed()
            assertEquals(1, runBlocking { api.channelTasks(w.id, w.appNpub, board.id).size })
        }
    }
}
