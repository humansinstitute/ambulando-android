package studio.otherstuff.ambulando.app.ui

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.ui.nav.RouteState
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.*

class ChatEntryNavigationTest {
    private val thread = WorkRoute.Thread("workspace", "channel", "thread", "older-message")

    @Test fun hiddenChatStillRestoresItsConversationAndExplicitEntry() {
        val saved = RouteState.decode(RouteState.encode("chat", mapOf("chat" to listOf(thread))), "workspace")!!
        val stacks = SectionStacks().apply { restore(saved.stacks) }
        assertFalse(AppSection.CHAT in AppSection.visible(false, false, showChat = false))
        assertEquals(AppSection.CHAT, AppSection.fromKey(saved.section))
        assertEquals(listOf(thread), stacks.of(AppSection.CHAT).toList())
        assertEquals(AppSection.CHAT, chatEntrySection(AppSection.TASKS, overlay = true))
        assertEquals(AppSection.DECK, chatEntrySection(AppSection.DECK))
        assertEquals(listOf(thread), stacks.of(AppSection.CHAT).toList())
    }

    @Test fun deckEntryLeavesExistingChatStackAloneAndCannotLoop() {
        val stacks = SectionStacks()
        val prior = thread.copy(threadId = "prior")
        stacks.push(AppSection.CHAT, prior)
        repeat(3) {
            val section = chatEntrySection(AppSection.DECK)
            stacks.push(section, thread)
            assertEquals(AppSection.DECK, section)
            assertTrue(stacks.pop(section))
            assertFalse(stacks.pop(section))
            assertEquals(listOf(prior), stacks.of(AppSection.CHAT).toList())
        }
    }

    @Test fun chatListEntryReturnsToListAndOtherOriginsHaveSafeChatFallback() {
        AppSection.entries.filter { it != AppSection.DECK }.forEach { origin ->
            val stacks = SectionStacks()
            val section = chatEntrySection(origin)
            assertEquals(AppSection.CHAT, section)
            stacks.push(section, thread)
            assertTrue(stacks.pop(section))
            assertFalse(stacks.pop(section))
        }
    }

    @Test fun notificationAndSearchOverDeckUseChatFallback() {
        assertEquals(AppSection.CHAT, chatEntrySection(AppSection.DECK, overlay = true))
    }

    @Test fun deckThreadAndChannelParentSurviveNavigationPersistence() {
        val routes = listOf(WorkRoute.Channel("workspace", "channel"), thread)
        val saved = RouteState.decode(RouteState.encode("deck", mapOf("deck" to routes)), "workspace")!!
        val stacks = SectionStacks().apply { restore(saved.stacks) }
        assertEquals("deck", saved.section)
        assertTrue(stacks.pop(AppSection.DECK))
        assertEquals(routes.first(), stacks.of(AppSection.DECK).single())
        assertTrue(stacks.pop(AppSection.DECK))
        assertFalse(stacks.pop(AppSection.DECK))
    }
}
