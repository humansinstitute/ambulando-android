package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Full Android sample-data flow; never uses an authenticated member or personal device. */
@RunWith(AndroidJUnit4::class)
class DeckChatBackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun deckChatReturnsByVisibleAndSystemBackAndKeepsFilters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.section = AppSection.DECK }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.page.inbox").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.inbox").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.inbox.search").fetchSemanticsNodes().isNotEmpty() }
            val original = model.context
            compose.onNodeWithTag("deck.inbox.type.task").performClick().assertIsNotSelected()
            compose.onNodeWithTag("deck.inbox.search").performTextReplacement("Native chat review")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.inbox.item").fetchSemanticsNodes().isNotEmpty() }
            repeat(2) { pass ->
                compose.onAllNodesWithTag("deck.inbox.item")[0].performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav.back").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
                if (pass == 0) compose.onNodeWithTag("nav.back").performClick()
                else androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithTag("deck.inbox.search").assertTextContains("Native chat review")
                compose.onNodeWithTag("deck.inbox.type.task").assertIsNotSelected()
                compose.onNodeWithTag("nav.back").assertDoesNotExist()
                compose.runOnIdle { assertEquals(original, model.context); assertEquals(AppSection.DECK, model.section) }
            }
            // A regular Chat list remains an independent entry path, even after Deck navigation.
            compose.runOnIdle {
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)
                model.section = AppSection.CHAT
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.messages").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${SampleIds.message(36)}"))
            compose.onNodeWithTag("chat.message.${SampleIds.message(36)}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav.back").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("nav.back").assertDoesNotExist()
            compose.onNodeWithTag("chat.messages").assertExists()
            compose.runOnIdle { assertEquals(AppSection.CHAT, model.section); assertEquals(SampleIds.GENERAL, model.context.channelId) }
        }
    }
}
