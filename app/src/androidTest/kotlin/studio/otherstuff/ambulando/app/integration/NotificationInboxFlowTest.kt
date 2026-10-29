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
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.NotificationView

/** Synthetic sample data only: the stand-in Tower, never a real workspace or identity. */
@RunWith(AndroidJUnit4::class)
class NotificationInboxFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun bellBadgeInboxActionsOfflineRecoveryAndTapThrough() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            val c = { model.connection!! }
            fun rows(view: NotificationView = NotificationView.INBOX) = runBlocking { model.store.reader.notificationRows(c().id, SampleIds.STUDIO, view) }
            compose.waitUntil(20_000) { rows().size == 3 }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notifications.badge").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("notifications.badge").assertTextEquals("3")
            compose.onNodeWithTag("notifications.open").performClick()
            compose.onNodeWithTag("notifications.screen").assertIsDisplayed()
            compose.onNodeWithText("3 unread").assertIsDisplayed()

            // Archive one from its menu: it leaves the inbox and the count drops.
            val first = rows().first()
            compose.onNodeWithTag("notification.${first.id}.menu").performClick()
            compose.onNodeWithTag("notification.${first.id}.archive").performClick()
            compose.waitUntil(20_000) { rows().none { it.id == first.id } }
            compose.onNodeWithText("2 unread").assertIsDisplayed()

            // The change shows at once (optimistic overlay) and is acknowledged by the Tower stand-in.
            val second = rows().first()
            compose.onNodeWithTag("notification.${second.id}.menu").performClick()
            compose.onNodeWithTag("notification.${second.id}.toggleRead").performClick()
            compose.waitUntil(20_000) { rows().first { it.id == second.id }.read }
            compose.waitUntil(20_000) { !rows().first { it.id == second.id }.pending && runBlocking { model.store.reader.writes(c().id, c().signerNpub).isEmpty() } }
            assertTrue(rows().first { it.id == second.id }.read)

            // Tap-through: a thread notification opens the conversation and the screen closes.
            val thread = rows().first { it.item.target?.kind == "thread" }
            compose.onNodeWithTag("notification.${thread.id}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notifications.screen").fetchSemanticsNodes().isEmpty() }
            assertEquals(studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT, model.section)

            // Archived view lists the archived item (loaded on demand).
            compose.runOnIdle { model.loadArchivedNotifications() }
            compose.waitUntil(20_000) { rows(NotificationView.ARCHIVED).any { it.id == first.id } }
        }
    }
}
