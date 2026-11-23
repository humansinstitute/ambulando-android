package studio.otherstuff.ambulando.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.app.ui.deck.DeckInbox
import studio.otherstuff.ambulando.core.store.ChangeKey
import studio.otherstuff.ambulando.core.store.StoreTable

class DeckProfileInvalidationTest {
    @Test fun connectionWideProfileArrivalsInvalidateAnExistingWorkspaceInbox() {
        val matches = DeckInbox.matches("workspace")
        // Profile writes carry no workspace id: waiting for a workspace event leaves old card labels visible.
        assertTrue(matches(ChangeKey(StoreTable.PROFILES)))
        assertTrue(matches(ChangeKey(StoreTable.MEMBERS, "workspace")))
        assertTrue(matches(ChangeKey(StoreTable.CHANNELS, "workspace")))
        assertFalse(matches(ChangeKey(StoreTable.CHANNELS, "another-workspace")))
    }
}
