package studio.otherstuff.ambulando.app.ui

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.ui.nav.RouteState
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.core.context.WorkContext

/** Home is its own section: where an unfiltered workspace lands, never a strip button, and Deck stays separate. */
class HomeRoutingTest {
    @Test fun unfilteredWorkspaceLandsOnHomeAndAChosenContextOnDeck() {
        assertEquals(AppSection.HOME, AppSection.landing(WorkContext.HOME))
        assertEquals(AppSection.DECK, AppSection.landing(WorkContext("scope", null)))
        assertEquals(AppSection.DECK, AppSection.landing(WorkContext("scope", "channel")))
    }

    @Test fun homeIsNotAStripSectionAndNotContextBound() {
        assertFalse(AppSection.HOME in AppSection.visible(true, true, true))
        assertFalse(AppSection.HOME.contextBound)
        assertTrue(AppSection.DECK in AppSection.visible(false, false))
    }

    @Test fun chatOpenedFromHomeStaysOnHomesStackAndRestores() {
        val thread = WorkRoute.Thread("w", "c", "t")
        assertEquals(AppSection.HOME, chatEntrySection(AppSection.HOME))
        assertEquals(AppSection.CHAT, chatEntrySection(AppSection.HOME, overlay = true))
        val stacks = SectionStacks().apply { push(AppSection.HOME, WorkRoute.Goal("w", "goal")); push(AppSection.HOME, thread) }
        stacks.resetContextBound()
        assertEquals("a new context does not drop Home's records", 2, stacks.of(AppSection.HOME).size)
        val saved = RouteState.decode(RouteState.encode("home", stacks.snapshot()), "w")!!
        assertEquals(AppSection.HOME, AppSection.fromKey(saved.section))
        assertEquals(listOf(WorkRoute.Goal("w", "goal"), thread), SectionStacks().apply { restore(saved.stacks) }.of(AppSection.HOME).toList())
    }

    @Test fun olderSavedDeckStillOpensDeck() {
        val saved = RouteState.decode(RouteState.encode("deck", emptyMap()), "w")!!
        assertEquals(AppSection.DECK, AppSection.fromKey(saved.section))
    }
}
