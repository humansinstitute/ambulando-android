package studio.otherstuff.ambulando.app.integration

import android.content.Intent
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
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import androidx.compose.ui.semantics.getOrNull

/** Chat message cards and Deck Inbox rows on synthetic sample data: what each control does, not how it looks. */
@RunWith(AndroidJUnit4::class)
class ChatDeckInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(block: (AppModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            captureOnFailure(compose, model, "chat-deck") { block(model) }
        }
    }

    private fun openGeneralChat(model: AppModel) {
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.CHAT }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theMessageMenuOffersEditAndDeleteOnlyOnYourOwnMessageAndTheQuickReactionIsSentByWireName() = launch { model ->
        openGeneralChat(model)
        val feed = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300) }
        val others = feed.first { it.message.senderNpub != model.connection!!.signerNpub }.message
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${others.id}"))
        compose.onNodeWithTag("message.${others.id}.actions").performClick()
        compose.onNodeWithTag("message.${others.id}.menu.copy").assertExists()
        compose.onNodeWithTag("message.${others.id}.menu.edit").assertDoesNotExist()
        compose.onNodeWithTag("message.${others.id}.menu.delete").assertDoesNotExist()
        compose.onNodeWithTag("message.${others.id}.menu.copy").performClick()

        val text = "Card controls ${System.nanoTime()}"
        compose.onNodeWithTag("chat.composer").performTextReplacement(text)
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).any { it.message.body == text } } }
        val mine = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).first { it.message.body == text }.message }
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${mine.id}"))
        compose.onNodeWithTag("message.${mine.id}.actions").performClick()
        compose.onNodeWithTag("message.${mine.id}.menu.edit").assertExists()
        compose.onNodeWithTag("message.${mine.id}.menu.delete").assertExists()
        compose.onNodeWithTag("message.${mine.id}.menu.copy").performClick()

        val inMine = hasAnyAncestor(hasTestTag("chat.message.${mine.id}"))
        compose.onNode(hasTestTag("reaction.quick") and inMine).performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.reactions(SampleIds.STUDIO, listOf(mine.id)).any { it.emoji == "thumbs_up" && !it.deleted } } }
        // The pill with its count replaces nothing: the quick control and the picker stay.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("reaction.thumbs_up") and inMine).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("reaction.quick") and inMine).assertExists()
        compose.onNode(hasTestTag("reaction.add") and inMine).assertExists()
    }

    @Test fun aChannelPostWithRepliesShowsItsReplyBarAndItOpensTheThread() = launch { model ->
        openGeneralChat(model)
        val withReplies = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300) }.first { it.replyCount > 0 }
        val threadId = withReplies.message.threadId!!
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${withReplies.message.id}"))
        compose.onNodeWithTag("chat.thread.$threadId", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("${withReplies.replyCount} replies") and hasAnyAncestor(hasTestTag("chat.thread.$threadId")), useUnmergedTree = true).assertExists()
        // A post nobody has replied to has no bar; the card itself still opens its conversation.
        val quiet = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300) }.first { it.replyCount == 0 && it.message.threadId != null }
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${quiet.message.id}"))
        compose.onNodeWithTag("chat.thread.${quiet.message.threadId}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${withReplies.message.id}"))
        compose.onNodeWithTag("chat.thread.$threadId", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("thread.actions").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun inboxTypeTogglesSearchAndRowActionsWork() = launch { model ->
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.DECK }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.page.inbox")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.page.inbox").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.inbox.item")).fetchSemanticsNodes().isNotEmpty() }
        val kinds = { compose.onAllNodes(hasTestTag("deck.inbox.item")).fetchSemanticsNodes().map { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty() } }
        assertTrue(kinds().any { it.startsWith("Task") })
        // Turning Tasks off hides the task rows (and leaves the others); turning it on brings them back.
        compose.onNodeWithTag("deck.inbox.type.task").performClick()
        compose.waitUntil(10_000) { kinds().none { it.startsWith("Task") } }
        assertTrue(kinds().isNotEmpty())
        compose.onNodeWithTag("deck.inbox.type.task").performClick()
        compose.waitUntil(10_000) { kinds().any { it.startsWith("Task") } }
        // Search narrows to matching rows and reports no match plainly.
        compose.onNodeWithTag("deck.inbox.search").performTextReplacement("launch notes")
        compose.waitUntil(10_000) { kinds().isNotEmpty() && kinds().all { it.contains("launch notes", ignoreCase = true) } }
        compose.onNodeWithTag("deck.inbox.search").performTextReplacement("zzz no such row")
        compose.waitUntil(10_000) { kinds().isEmpty() }
        compose.onNodeWithText("No matches", substring = true).assertExists()
        compose.onNodeWithTag("deck.inbox.search").performTextReplacement("")
        compose.waitUntil(10_000) { kinds().isNotEmpty() }
        // An unread row's own Mark read clears its unread state.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("deck.inbox.read")).fetchSemanticsNodes().isNotEmpty() }
        val before = compose.onAllNodes(hasTestTag("deck.inbox.read")).fetchSemanticsNodes().size
        compose.onAllNodes(hasTestTag("deck.inbox.read"))[0].performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.inbox.read")).fetchSemanticsNodes().size < before }
    }

    private fun openDeck(model: AppModel, scopeId: String?, channelId: String?, page: String) {
        compose.runOnIdle { model.selectContext(scopeId, channelId); model.section = AppSection.DECK }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.page.$page")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.page.$page").performClick()
    }

    private fun bodies(model: AppModel, text: String) = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300) }.filter { it.message.body == text }

    @Test fun theNewThreadButtonStartsOneThreadInTheContextChannelAndOpensIt() = launch { model ->
        val general = model.channels.first { it.id == SampleIds.GENERAL }
        openDeck(model, general.scopeId, general.id, "inbox")
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.fab.new")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.fab.new").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // The context's channel is chosen already; nothing else in Deck answers while the composer is open.
        compose.onNode(hasText("#${general.name}") and hasAnyAncestor(hasTestTag("deck.compose.channel")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("deck.fab.new").assertDoesNotExist()
        val text = "New thread ${System.nanoTime()}"
        compose.onNodeWithTag("chat.composer").performTextReplacement(text)
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(30_000) { bodies(model, text).any { it.message.threadId != null } }
        // Tower's thread for exactly that send opens (a reply composer and the thread menu), and the send exists once.
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("thread.actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.compose").assertDoesNotExist()
        assertEquals(1, bodies(model, text).size)
        assertEquals(AppSection.CHAT, model.section)
    }

    @Test fun theInboxPlusAsksForAChannelKeepsTheDraftOnCancelAndSendsNothing() = launch { model ->
        val general = model.channels.first { it.id == SampleIds.GENERAL }
        openDeck(model, null, null, "inbox")
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.inbox.new")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.inbox.new").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.compose.option.${general.id}")).fetchSemanticsNodes().isNotEmpty() }
        // Several channels and none chosen: no composer until the member picks one.
        compose.onNodeWithTag("chat.composer").assertDoesNotExist()
        compose.onNodeWithTag("deck.compose.option.${general.id}").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val text = "Unsent thread ${System.nanoTime()}"
        compose.onNodeWithTag("chat.composer").performTextReplacement(text)
        compose.waitUntil(10_000) { runBlocking { model.store.reader.draft(ChatTarget(model.connection!!.id, SampleIds.STUDIO, general.id))?.text } == text }
        compose.onNodeWithTag("deck.compose.cancel").performClick()
        compose.onNodeWithTag("deck.compose").assertDoesNotExist()
        assertTrue(bodies(model, text).isEmpty())
        // Opened again, the same channel's composer has the text back.
        compose.onNodeWithTag("deck.inbox.new").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.compose.option.${general.id}")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.compose.option.${general.id}").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat.composer").assertTextContains(text)
        compose.onNodeWithTag("deck.compose.cancel").performClick()
        assertTrue(bodies(model, text).isEmpty())
    }

    @Test fun theCanvasPlusCreatesACanvasFromItsTitle() = launch { model ->
        openDeck(model, null, null, "canvas")
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("canvas.new")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.new").performClick()
        val title = "Parity board ${System.nanoTime()}"
        compose.onNodeWithTag("canvas.new.title").performTextInput(title)
        compose.onNodeWithTag("canvas.new.create").performClick()
        compose.waitUntil(30_000) { runBlocking { model.store.reader.canvases(SampleIds.STUDIO).any { it.title == title } } }
    }
}
