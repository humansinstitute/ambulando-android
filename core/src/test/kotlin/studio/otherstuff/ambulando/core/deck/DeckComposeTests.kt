package studio.otherstuff.ambulando.core.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.otherstuff.ambulando.core.context.WorkContext

/** Which channel Deck's New thread composer starts in, and when a change of context moves it. */
class DeckComposeTests {
    private val scope = "scope-a"

    @Test fun theContextChannelIsPreselectedWhileEligible() {
        assertEquals("c2", DeckCompose.initialChannel(WorkContext(scope, "c2"), listOf("c1", "c2")))
    }

    @Test fun anIneligibleContextChannelFallsBackToTheOnlyEligibleOne() {
        assertEquals("c1", DeckCompose.initialChannel(WorkContext(scope, "gone"), listOf("c1")))
        assertEquals("c1", DeckCompose.initialChannel(WorkContext(scope), listOf("c1")))
    }

    @Test fun severalEligibleChannelsAndNoContextChannelAsksTheMember() {
        assertNull(DeckCompose.initialChannel(WorkContext(scope), listOf("c1", "c2")))
        assertNull(DeckCompose.initialChannel(WorkContext.HOME, emptyList()))
    }

    @Test fun aChosenChannelIsKeptWithinTheSameScope() {
        assertEquals("c2", DeckCompose.reconcile("c2", WorkContext(scope), WorkContext(scope, "c1"), listOf("c1", "c2")))
    }

    @Test fun aChosenChannelThatIsNoLongerEligibleOrACrossScopeChangeStartsTheChoiceAgain() {
        assertNull(DeckCompose.reconcile("c2", WorkContext(scope), WorkContext(scope), listOf("c1", "c3")))
        assertEquals("c9", DeckCompose.reconcile("c2", WorkContext(scope), WorkContext("scope-b", "c9"), listOf("c2", "c9")))
    }
}
