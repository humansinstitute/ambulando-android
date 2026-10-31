package studio.otherstuff.ambulando.app.ui.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.nav.AppServices
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.ScreenScope
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.store.LiveQueryState
import studio.otherstuff.ambulando.core.store.TaskRelationshipState
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.transport.TaskRecord

/** The task detail's subtask list for each stored relationship state, with the read outcome the device just had. */
@RunWith(RobolectricTestRunner::class)
class TaskHierarchyStatesTest {
    @get:Rule val compose = createComposeRule()

    private val w = "00000000-0000-4000-8000-000000000001"
    private val c = "00000000-0000-4000-8000-000000000c01"
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun task(n: Int, parent: Int? = null, state: String = "new"): TaskRecord = TaskRecord(
        w, id(n), null, c, null, "Task $n", null, state, "sand", null, null, 1, 0, "2026-10-01T10:00:%02d.000Z".format(n), "2026-10-01T10:00:%02d.000Z".format(n),
        JsonObject(mapOf("metadata" to JsonObject(mapOf("parent_task_id" to (parent?.let { JsonPrimitive(id(it)) } ?: JsonNull), "predecessor_task_ids" to JsonArray(emptyList()))))))

    private fun row(n: Int, parent: Int? = null, state: String = "new") = TaskRow.of(task(n, parent, state), emptyList())

    private fun state(parent: Int, complete: Boolean = false, restricted: Boolean = false, failure: String? = null, cursor: String? = null, pages: Int = 0) =
        TaskRelationshipState(id(parent), complete, restricted, failure, 1, cursor, pages, if (complete) "2026-10-30T09:15:00Z" else null, null)

    private class Intents(var result: RelationshipStatus, val reads: MutableList<String> = ArrayList()) : TasksIntents {
        override fun submit(intent: studio.otherstuff.ambulando.core.store.WriteIntent) = true
        override fun refresh(force: Boolean) {}
        override fun openTask(taskId: String, force: Boolean, markViewed: Boolean) {}
        override suspend fun loadRelationships(taskId: String): RelationshipStatus { reads += taskId; return result }
        override fun retryWrite(item: WriteItem) {}
        override fun applyMine(item: WriteItem) {}
        override fun discardWrite(item: WriteItem) {}
    }

    private fun show(snapshot: TaskDetailSnapshot, intents: TasksIntents) {
        val scope = ScreenScope("conn", w, "npub1app", "npub1me", null, c)
        val services = AppServices(null, {}, {}, {}, { _, _ -> }, unlocked = true, sample = false)
        compose.setContent {
            AmbulandoTheme { CompositionLocalProvider(LocalAppServices provides services) { TaskDetailContent(LiveQueryState(snapshot, loaded = true), id(1), scope, intents) } }
        }
        compose.waitForIdle()
    }

    private fun snapshot(children: Map<Int, List<TaskRow>>, states: Map<Int, TaskRelationshipState>) = TaskDetailSnapshot(
        row = row(1), children = children.mapKeys { id(it.key) }, relationships = states.mapKeys { id(it.key) })

    @Test fun aConfirmedListHasACertainCountAndSaysNothingElse() {
        show(snapshot(mapOf(1 to listOf(row(2, 1), row(3, 1, "archive"))), mapOf(1 to state(1, complete = true, pages = 1))), Intents(RelationshipStatus.COMPLETE))
        compose.onNodeWithTag("task.subtasks.count").performScrollTo().assertTextEquals("Subtasks 2")
        compose.onNodeWithTag("task.subtask.${id(3)}").assertIsDisplayed()
        compose.onNodeWithTag("task.subtasks.state").assertDoesNotExist()
    }

    @Test fun anUnreadParentOfflineShowsSavedChildrenWithAnUncertainCount() {
        show(snapshot(mapOf(1 to listOf(row(2, 1))), emptyMap()), Intents(RelationshipStatus.OFFLINE))
        compose.onNodeWithTag("task.subtasks.count").performScrollTo().assertTextEquals("Subtasks 1+")
        compose.onNodeWithTag("task.subtasks.state").assertTextContains("Offline or locked", substring = true)
    }

    @Test fun aCachedListWhoseRefreshFailedKeepsItsChildrenAndSaysItMayBeOld() {
        show(snapshot(mapOf(1 to listOf(row(2, 1))), mapOf(1 to state(1, complete = true, failure = "failed", pages = 1))), Intents(RelationshipStatus.COMPLETE))
        compose.onNodeWithTag("task.subtasks.count").performScrollTo().assertTextEquals("Subtasks 1+")
        compose.onNodeWithTag("task.subtasks.state").assertTextContains("may be out of date", substring = true)
        compose.onNodeWithTag("task.subtasks.state").assertTextContains("2026-10-30 09:15", substring = true)
    }

    @Test fun aPartialListSaysThereMayBeMoreAndRefreshReadsAgain() {
        val intents = Intents(RelationshipStatus.PARTIAL)
        show(snapshot(mapOf(1 to listOf(row(2, 1))), mapOf(1 to state(1, cursor = "c1", pages = 1))), intents)
        compose.onNodeWithTag("task.subtasks.count").performScrollTo().assertTextEquals("Subtasks 1+")
        compose.onNodeWithTag("task.subtasks.state").assertTextContains("there may be more", substring = true)
        val before = intents.reads.size
        compose.onNodeWithTag("task.subtasks.refresh").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("refresh reads the direct list again", intents.reads.size > before)
        assertEquals(setOf(id(1)), intents.reads.toSet())
    }

    @Test fun aRestrictedListDoesNotClaimToEnumerateAndHasNoNoSubtasksClaim() {
        show(snapshot(mapOf(1 to emptyList()), mapOf(1 to state(1, restricted = true))), Intents(RelationshipStatus.RESTRICTED))
        compose.onNodeWithTag("task.subtasks.count").performScrollTo().assertTextEquals("Subtasks 0+")
        compose.onNodeWithTag("task.subtasks.state").assertTextContains("may not be visible", substring = true)
        compose.onNodeWithTag("task.subtasks.state").assertIsDisplayed()
        compose.onNodeWithText("No subtasks.").assertDoesNotExist()
    }

    @Test fun aConfirmedEmptyListSaysNoSubtasks() {
        show(snapshot(mapOf(1 to emptyList()), mapOf(1 to state(1, complete = true, pages = 1))), Intents(RelationshipStatus.COMPLETE))
        compose.onNodeWithTag("task.subtasks.count").performScrollTo().assertTextEquals("Subtasks 0")
        compose.onNodeWithText("No subtasks.").assertIsDisplayed()
    }

    @Test fun anExpandedChildLoadsItsOwnDirectListAndShowsItsOwnState() {
        val intents = Intents(RelationshipStatus.COMPLETE)
        show(snapshot(mapOf(1 to listOf(row(2, 1)), 2 to listOf(row(3, 2))), mapOf(1 to state(1, complete = true, pages = 1), 2 to state(2, restricted = true))), intents)
        compose.onNodeWithTag("task.subtask.expand.${id(2)}").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("expanding reads the child's own list", id(2) in intents.reads)
        compose.onNodeWithTag("task.subtask.${id(3)}").assertIsDisplayed()
        compose.onNodeWithTag("task.subtask.state.${id(2)}").assertTextContains("may not be visible", substring = true)
    }

    @Test fun aCycleIsShownButNeverExpanded() {
        // Task 1 heads the tree and is also listed as a child of its own descendant 2.
        show(snapshot(mapOf(1 to listOf(row(2, 1)), 2 to listOf(row(1, 2))), mapOf(1 to state(1, complete = true, pages = 1), 2 to state(2, complete = true, pages = 1))), Intents(RelationshipStatus.COMPLETE))
        compose.onNodeWithTag("task.subtask.expand.${id(2)}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("task.subtask.${id(1)}").assertTextContains("already shown above", substring = true)
        compose.onNodeWithTag("task.subtask.expand.${id(1)}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("task.subtask.state.${id(1)}").assertDoesNotExist()
    }
}
