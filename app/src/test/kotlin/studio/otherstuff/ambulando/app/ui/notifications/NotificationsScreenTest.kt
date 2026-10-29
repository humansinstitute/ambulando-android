package studio.otherstuff.ambulando.app.ui.notifications

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.notifications.NotificationEmptyReason
import studio.otherstuff.ambulando.core.notifications.NotificationFilter
import studio.otherstuff.ambulando.core.store.NotificationRow
import studio.otherstuff.ambulando.core.store.NotificationSyncState
import studio.otherstuff.ambulando.core.sync.NotificationPreferences
import studio.otherstuff.ambulando.core.transport.NotificationCategoryInfo
import studio.otherstuff.ambulando.core.transport.NotificationItem
import studio.otherstuff.ambulando.core.transport.NotificationRule
import studio.otherstuff.ambulando.core.transport.NotificationView
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NotificationsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val W = "00000000-0000-4000-8000-000000000001"
    private val CH = "00000000-0000-4000-8000-000000000c01"
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(900 + n)

    private fun item(n: Int, read: Boolean = false, access: String = "ok", title: String = "Morgan mentioned you", count: Int = 1, status: String = "active"): NotificationItem =
        NotificationItem.parse(toJsonElement(mapOf(
            "id" to id(n), "workspace_id" to W, "change_seq" to 10L + n, "row_version" to 1, "category" to "mention", "status" to status,
            "source" to mapOf("app_id" to "flightdeck.core", "label" to "Flight Deck"), "group" to mapOf("key" to "k", "event_count" to count),
            "presentation" to mapOf("title" to title, "body" to "Thread title"),
            "action" to mapOf("type" to "open", "target" to mapOf("kind" to "thread", "channel_id" to CH, "thread_id" to "00000000-0000-4000-8000-000000000d01")),
            "occurred_at" to "2026-10-26T10:00:00Z", "state" to mapOf("read" to read, "dismissed" to false, "archived" to false), "access" to access,
        )) as JsonObject)

    private fun row(n: Int, pending: Boolean = false, read: Boolean = false, access: String = "ok", count: Int = 1) =
        item(n, read, access, count = count).let { NotificationRow(it, it.state.read, false, false, "inbox", pending, null) }

    private class Recorder : NotificationsActions {
        val events = mutableListOf<String>()
        override fun open(row: NotificationRow) { events += "open:${row.id.takeLast(3)}" }
        override fun setFilter(filter: NotificationFilter) { events += "filter:${filter.view.wire}:${filter.category}" }
        override fun setRead(row: NotificationRow, read: Boolean) { events += "read:${row.id.takeLast(3)}:$read" }
        override fun dismiss(row: NotificationRow) { events += "dismiss:${row.id.takeLast(3)}" }
        override fun setArchived(row: NotificationRow, archived: Boolean) { events += "archive:${row.id.takeLast(3)}:$archived" }
        override fun markAllRead() { events += "markAll" }
        override fun refresh() { events += "refresh" }
        override fun dismissMessage() { events += "dismissMessage" }
        override fun close() { events += "close" }
    }

    private fun state(rows: List<NotificationRow>, empty: NotificationEmptyReason? = null, canChange: Boolean = true, message: String? = null, error: String? = null, filter: NotificationFilter = NotificationFilter()) =
        NotificationsUiState(rows, filter, listOf("mention"), rows.count { it.unread }, empty, false, error, message, canChange, Instant.parse("2026-10-26T10:05:00Z"))

    @Test fun listShowsUnreadStateCountAndOpensTheTappedItem() {
        val a = Recorder()
        compose.setContent { AmbulandoTheme { NotificationsScreen(state(listOf(row(1), row(2, read = true, count = 3))), a) } }
        compose.onNodeWithTag("notifications.unread").assertIsDisplayed()
        compose.onNodeWithText("1 unread").assertIsDisplayed()
        compose.onNodeWithText("Flight Deck · Mentions · 3 updates · 5 min ago").assertIsDisplayed()
        compose.onNodeWithTag("notification.${id(1)}").performClick()
        assertEquals(listOf("open:901"), a.events)
    }

    @Test fun rowMenuMarksArchivesAndDismissesAndFiltersAreReported() {
        val a = Recorder()
        compose.setContent { AmbulandoTheme { NotificationsScreen(state(listOf(row(1))), a) } }
        compose.onNodeWithTag("notification.${id(1)}.menu").performClick()
        compose.onNodeWithTag("notification.${id(1)}.toggleRead").performClick()
        compose.onNodeWithTag("notification.${id(1)}.menu").performClick()
        compose.onNodeWithTag("notification.${id(1)}.archive").performClick()
        compose.onNodeWithTag("notification.${id(1)}.menu").performClick()
        compose.onNodeWithTag("notification.${id(1)}.dismiss").performClick()
        compose.onNodeWithTag("notifications.view.unread").performClick()
        compose.onNodeWithTag("notifications.category.mention").performClick()
        compose.onNodeWithTag("notifications.markAll").performClick()
        compose.onNodeWithTag("notifications.refresh").performClick()
        assertEquals(listOf("read:901:true", "archive:901:true", "dismiss:901", "filter:unread:null", "filter:inbox:mention", "markAll", "refresh"), a.events)
    }

    @Test fun lockedIdentityMakesTheInboxReadOnlyAndSaysWhy() {
        val a = Recorder()
        compose.setContent { AmbulandoTheme { NotificationsScreen(state(listOf(row(1)), canChange = false), a) } }
        compose.onNodeWithTag("notifications.locked").assertIsDisplayed()
        compose.onNodeWithTag("notifications.markAll").assertIsNotEnabled()
        compose.onNodeWithTag("notification.${id(1)}.menu").performClick()
        compose.onNodeWithTag("notification.${id(1)}.dismiss").assertIsNotEnabled()
    }

    @Test fun unavailableItemShowsNoPrivateTextAndCannotBeOpened() {
        val a = Recorder()
        val unavailable = row(1, access = "unavailable").let { it.copy(item = it.item) }
        compose.setContent { AmbulandoTheme { NotificationsScreen(state(listOf(unavailable)), a) } }
        compose.onNodeWithText("Item no longer available").assertIsDisplayed()
        compose.onNodeWithText("Morgan mentioned you").assertDoesNotExist()
        compose.onNodeWithTag("notification.${id(1)}").performClick()
        assertTrue("an unavailable item is not tappable", a.events.isEmpty())
    }

    @Test fun pendingWriteIsLabelled() {
        compose.setContent { AmbulandoTheme { NotificationsScreen(state(listOf(row(1, pending = true))), Recorder()) } }
        compose.onNodeWithTag("notification.${id(1)}.pending", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun emptyStatesAreHonestAboutWhy() {
        val reasonState = androidx.compose.runtime.mutableStateOf<NotificationEmptyReason>(NotificationEmptyReason.BackendNotSupported)
        compose.setContent { AmbulandoTheme { NotificationsScreen(state(emptyList(), empty = reasonState.value), Recorder()) } }
        compose.onNodeWithText("Notifications aren't available on this Tower").assertIsDisplayed()
        compose.onNodeWithText("Not available on this Tower").assertIsDisplayed()
        compose.onNodeWithText("All caught up").assertDoesNotExist()
        reasonState.value = NotificationEmptyReason.AccessLost
        compose.onNodeWithText("No access to this workspace's notifications").assertIsDisplayed()
        reasonState.value = NotificationEmptyReason.InboxStarted("2026-10-26T09:00:00Z")
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
        compose.onNodeWithText("Your inbox started on 2026-10-26. Earlier activity is not imported.").assertIsDisplayed()
    }

    @Test fun messageAndErrorBannersAreShownAndDismissable() {
        val a = Recorder()
        compose.setContent { AmbulandoTheme { NotificationsScreen(state(listOf(row(1)), message = "That notification had new activity.", error = "offline"), a) } }
        compose.onNodeWithText("That notification had new activity.").assertIsDisplayed()
        compose.onNodeWithTag("notifications.error").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismissMessage"), a.events)
    }

    // MARK: Settings

    private val core = NotificationCategoryInfo("flightdeck.core", "Flight Deck", "mention", "Mentions", "normal", true, true, false)
    private val locked = NotificationCategoryInfo("flightdeck.core", "Flight Deck", "security", "Security", "high", true, true, true)

    private class SettingsRecorder : NotificationSettingsActions {
        val events = mutableListOf<String>()
        override fun setInbox(category: NotificationCategoryInfo, on: Boolean) { events += "${category.category}:$on" }
        override fun reload() { events += "reload" }
    }

    @Test fun settingsTogglesCategoriesLocksMandatoryOnesAndDoesNotFakeDeferredFeatures() {
        val a = SettingsRecorder()
        val prefs = NotificationPreferences(listOf(core, locked), listOf(NotificationRule("flightdeck.core", "mention", "*", "inbox", "off", null, 2)))
        compose.setContent { AmbulandoTheme { NotificationSettingsContent(NotificationSettingsState.Ready(prefs, "2026-10-26T09:00:00Z"), NotificationSyncState.Phase.READY, a) } }
        compose.onNodeWithTag("settings.notifications.flightdeck.core.mention").performClick()
        assertEquals(listOf("mention:true"), a.events)            // it was off, the tap asks to turn it on
        compose.onNodeWithTag("settings.notifications.flightdeck.core.security").assertIsNotEnabled()
        compose.onNodeWithTag("settings.notifications.push").assertIsDisplayed()
        compose.onNodeWithTag("settings.notifications.deferred").assertIsDisplayed()
        compose.onNodeWithTag("settings.notifications.started").assertIsDisplayed()
    }

    @Test fun settingsOnAnOlderTowerShowsNoToggles() {
        compose.setContent { AmbulandoTheme { NotificationSettingsContent(NotificationSettingsState.NotSupported, NotificationSyncState.Phase.UNSUPPORTED, SettingsRecorder()) } }
        compose.onNodeWithTag("settings.notifications.unsupported").assertIsDisplayed()
        compose.onNodeWithTag("settings.notifications.flightdeck.core.mention").assertDoesNotExist()
    }
}
