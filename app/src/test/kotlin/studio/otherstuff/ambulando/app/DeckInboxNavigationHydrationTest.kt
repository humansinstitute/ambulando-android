package studio.otherstuff.ambulando.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.deck.*
import studio.otherstuff.ambulando.app.ui.nav.AppServices
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.ScreenScope
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.context.*
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.upsertChannels
import studio.otherstuff.ambulando.core.store.upsertMessages
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ViewStateRecord
import java.nio.file.Files

/**
 * The Inbox filters by the channels of the navigation it is given. Navigation hydrates from app state that is not a store
 * event, so the channels can arrive after the first read: the card must then read again rather than keep the empty filter.
 */
@RunWith(RobolectricTestRunner::class)
class DeckInboxNavigationHydrationTest {
    @get:Rule val compose = createComposeRule()
    private val w = "11111111-1111-4111-8111-111111111111"
    private val s = "22222222-2222-4222-8222-222222222222"
    private val c = "33333333-3333-4333-8333-333333333333"
    private val store = LocalStore(Files.createTempDirectory("deck-nav").toFile(), 10)
    @After fun close() = store.close()

    private val intents = object : DeckIntents {
        override fun setPinned(pinned: Boolean, type: PinRecord.ResourceType, id: String) {}
        override fun retryPinWrite(writeId: String) {}
        override fun discardPinWrite(writeId: String) {}
        override fun refreshInbox(context: WorkContext) {}
        override fun markRead(resources: List<ViewStateRecord>) {}
        override fun markDone(taskId: String) {}
        override fun openInChat(channelId: String, threadId: String?) {}
        override fun refreshCanvases(force: Boolean) {}
        override suspend fun createCanvas(title: String): Result<String> = Result.failure(IllegalStateException())
        override fun refreshLaunchers(force: Boolean) {}
        override fun launchApp(url: String) {}
        override fun showToolbox() {}
    }

    @Test fun channelsKnownAtFirstReadShowTheScopedInbox() {
        show(hydrated = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("hydrated hello") > 0 }
    }

    @Test fun channelsThatHydrateAfterTheFirstReadReloadTheScopedInbox() {
        val navigation = show(hydrated = false)
        compose.onNodeWithText("hydrated hello").assertDoesNotExist()
        // Navigation arrives with no store change in between.
        navigation.value = hydratedNavigation
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("hydrated hello") > 0 }
    }

    private val context = WorkContext(s)
    private val hydratedNavigation = WorkNavigation(listOf(SidebarGroup(NavScope(s, "Scope"), listOf(NavChannel(c, s, "general")), false)), emptyMap(), context)

    private fun show(hydrated: Boolean): androidx.compose.runtime.MutableState<WorkNavigation> {
        val at = "2026-10-01T00:00:00.000Z"
        runBlocking {
            store.writer.upsertChannels(w, s, listOf(ChannelRecord(w, c, s, "general", updatedAt = at)), complete = true)
            store.writer.upsertMessages(listOf(MessageRecord(w, "44444444-4444-4444-8444-444444444444", c, body = "hydrated hello", createdAt = at)))
        }
        val navigation = mutableStateOf(if (hydrated) hydratedNavigation else WorkNavigation(context = context))
        val screen = ScreenScope("conn", w, "npub1app", "npub1me", s, null)
        val services = AppServices(null, {}, {}, {}, { _, _ -> }, unlocked = true, sample = false)
        compose.setContent {
            AmbulandoTheme { CompositionLocalProvider(LocalAppServices provides services) {
                DeckInboxCard(store, screen, navigation.value, PinsSnapshot(), intents, SurfaceStatusLookup.NONE, visible = true)
            } }
        }
        compose.waitForIdle()
        return navigation
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
}
