package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.chat.catchUpChannel
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/** Synthetic Tower only. Tests real SQLite, Compose navigation and Android recreation without a personal account. */
@RunWith(AndroidJUnit4::class)
class ConversationInboxTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/chat84-$name.png").close()
    }
    @Test fun exactOldReplyHitRestoresInboxAndDraftAndShowsMalformedQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .putExtra(AppEnvironment.RESTORE_NAVIGATION_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            runBlocking { model.catalog.writer.setState("settings.chat", null) }
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            model.sampleTower!!.seedLongConversation(SampleIds.thread(39))
            runBlocking { model.sync.catchUpChannel(model.connection!!, model.workspace!!, SampleIds.GENERAL, force = true) }
            compose.waitUntil(30_000) { runBlocking { model.store.reader.threadMessageCount(SampleIds.STUDIO, SampleIds.thread(39)) } > 70 }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.CHAT; model.showChat = true }
            // This test also runs after tests which deliberately persist an open thread.
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.inbox.search").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("nav.back").fetchSemanticsNodes().isNotEmpty() }
            repeat(10) { if (compose.onAllNodesWithTag("nav.back").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("nav.back").performClick() }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.inbox.search").fetchSemanticsNodes().isNotEmpty() }
            capture("inbox")
            model.sampleTower!!.offline = true
            compose.onNodeWithTag("chat.inbox.search").performTextReplacement("body:”deployment checklist”")
            val resultRow = SemanticsMatcher("Conversation result row") { node -> node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("chat.inbox.row.") == true }
            compose.waitUntil(30_000) { compose.onAllNodes(resultRow).fetchSemanticsNodes().size == 1 && compose.onAllNodesWithTag("chat.inbox.row.${SampleIds.thread(39)}").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("chat.inbox.row.${SampleIds.thread(36)}").assertDoesNotExist()
            val excerpt = compose.onNodeWithText("Older deployment checklist anchor", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            assertTrue("The matching phrase is highlighted", excerpt.spanStyles.isNotEmpty())
            capture("search")
            val hit = java.util.UUID.nameUUIDFromBytes("search-history-${SampleIds.thread(39)}-0".toByteArray()).toString()
            compose.onNodeWithTag("chat.inbox.row.${SampleIds.thread(39)}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.message.$hit").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.message.$hit").assertIsDisplayed()
            compose.onNodeWithTag("thread.header").assertIsDisplayed()
            capture("old-hit")
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.loadLater"))
            compose.onNodeWithTag("chat.loadLater").performClick()
            compose.onNodeWithTag("chat.composer").performTextReplacement("Saved inbox reply draft")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("nav.back").performClick()
            compose.onNodeWithTag("chat.inbox.search").assertTextContains("body:”deployment checklist”")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.inbox.row.${SampleIds.thread(39)}").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("chat.inbox.row.${SampleIds.thread(39)}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextEquals("Saved inbox reply draft")
            scenario.recreate()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextEquals("Saved inbox reply draft")
            compose.runOnIdle { assertTrue(app.activeModel!!.showChat) }
            compose.onNodeWithTag("nav.back").performClick()
            compose.onNodeWithTag("chat.inbox.search").performTextReplacement("title:\"unclosed")
            compose.onNodeWithText("Close the quoted phrase with a quotation mark.").assertExists()
            compose.onNodeWithTag("chat.inbox.search").performTextReplacement("")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.runOnIdle { app.activeModel!!.sampleTower!!.offline = false }
            compose.onNodeWithTag("chat.inbox.new").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.compose").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.compose.destination", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("chat.composer").assertExists()
            compose.onNodeWithTag("deck.compose.cancel").performClick()
            compose.onNodeWithTag("chat.inbox").assertExists()
            compose.runOnIdle { app.activeModel!!.selectContext(null, null) }
            compose.onNodeWithTag("chat.inbox.new").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.compose.channel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.compose.channel").assertExists()
            compose.onNodeWithTag("deck.compose.cancel").performClick()
        }
    }
}
