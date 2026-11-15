package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
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
import studio.otherstuff.ambulando.core.sync.*

/** Deterministic sample Tower only: device UI, persistence and read-state preservation, never live account evidence. */
@RunWith(AndroidJUnit4::class)
class AttentionTypesFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun channelSelectionOpensDeckButNotificationLinkStillOpensChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.section = AppSection.TASKS }
            compose.onNodeWithTag("sidebar.open").performClick()
            compose.onAllNodesWithText("general").onLast().performClick()
            compose.waitUntil(10_000) { model.section == AppSection.DECK && model.context.channelId == SampleIds.GENERAL }
            compose.onNodeWithTag("section.deck").assertIsSelected()
            val c = model.connection!!
            compose.waitUntil(20_000) { runBlocking { model.store.reader.notificationRows(c.id, SampleIds.STUDIO).any { it.item.target?.kind == "thread" } } }
            val thread = runBlocking { model.store.reader.notificationRows(c.id, SampleIds.STUDIO).first { it.item.target?.kind == "thread" } }
            compose.onNodeWithTag("notifications.open").performClick()
            compose.onNodeWithTag("notification.${thread.id}").performClick()
            compose.waitUntil(10_000) { model.section == AppSection.CHAT }
        }
    }

    @Test fun settingsSwitchesSaveSequentiallyAndSurviveActivityRecreationWithoutMarkingHiddenRecordsRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO }
            val c = model.connection!!; val store = model.store
            compose.waitUntil(20_000) { runBlocking { store.reader.attentionPreference(c.id, SampleIds.STUDIO).ready } }
            // Retained sample preferences may come from an earlier run; establish the visible initial choices.
            runBlocking { listOf("task", "file", "other").forEach { model.sync.setAttentionType(c, model.workspace!!, it, true) } }
            compose.waitUntil(20_000) { runBlocking { store.reader.attentionPreference(c.id, SampleIds.STUDIO).let { it.pending == null && listOf("task", "file", "other").all(it.types::includes) } } }
            val before = runBlocking { store.reader.viewStates(SampleIds.STUDIO) }
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply { setContent { MaterialTheme { NativeSettings(model) {} } } }) }
            listOf("task", "file", "other").forEach { kind ->
                compose.onNodeWithTag("settings.attentionTypes.$kind").performScrollTo().assertIsEnabled().performClick()
            }
            compose.waitUntil(20_000) { runBlocking { store.reader.attentionPreference(c.id, SampleIds.STUDIO).let { it.pending == null && it.types.disabled.containsAll(listOf("task", "file", "other")) } } }
            assertEquals(before, runBlocking { store.reader.viewStates(SampleIds.STUDIO) })
            scenario.recreate()
            compose.waitUntil(20_000) { app.activeModel?.workspace?.id == SampleIds.STUDIO }
            val restored = app.activeModel!!
            assertTrue(runBlocking { restored.store.reader.attentionPreference(restored.connection!!.id, SampleIds.STUDIO).types.disabled.containsAll(listOf("task", "file", "other")) })
        }
    }
    @Test fun unreadFilterAndBulkReadLeaveHiddenTaskUnread() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO }
            val c = model.connection!!; val store = model.store
            compose.waitUntil(20_000) { runBlocking { store.reader.attentionPreference(c.id, SampleIds.STUDIO).ready && store.reader.notificationRows(c.id, SampleIds.STUDIO).size == 3 } }
            runBlocking { model.sync.setAttentionType(c, model.workspace!!, "task", true) }
            compose.waitUntil(20_000) { runBlocking { store.reader.attentionPreference(c.id, SampleIds.STUDIO).pending == null } }
            val task = runBlocking { store.reader.notificationRows(c.id, SampleIds.STUDIO).first { it.item.target?.kind == "task" } }
            assertTrue(task.unread)
            compose.onNodeWithTag("notifications.open").performClick()
            compose.onNodeWithTag("notifications.view.unread").performClick()
            compose.onNodeWithTag("notification.${task.id}").assertExists()
            // A preference-only update arrives while this view is mounted: max notification sequence is unchanged.
            runBlocking { model.sync.setAttentionType(c, model.workspace!!, "task", false) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notification.${task.id}").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("notification.${task.id}").assertDoesNotExist()
            compose.onNodeWithTag("notifications.markAll").performClick()
            compose.waitUntil(20_000) { runBlocking { store.reader.notificationRows(c.id, SampleIds.STUDIO).filter { it.id != task.id }.none { it.unread } } }
            assertTrue(runBlocking { store.reader.notificationRow(c.id, SampleIds.STUDIO, task.id) }!!.unread)
            compose.onNodeWithTag("notifications.view.inbox").performClick()
            compose.onNodeWithTag("notification.${task.id}").assertExists()
        }
    }

}
