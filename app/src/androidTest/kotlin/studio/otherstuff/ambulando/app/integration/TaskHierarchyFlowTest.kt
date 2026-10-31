package studio.otherstuff.ambulando.app.integration

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.TaskHierarchy
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.util.UUID

/**
 * The task detail's subtask lists against persisted per-parent membership, on the dedicated emulator with the synthetic
 * sample workspace (never a real identity or Tower). Screenshots land in Download/ambulando-hierarchy-review.
 */
@RunWith(AndroidJUnit4::class)
class TaskHierarchyFlowTest {
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
            block(model)
        }
    }

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        // Review screenshots are evidence, not assertions: MediaStore refuses a name after enough repeats, which must not fail a workflow.
        runCatching {
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ambulando-hierarchy-review")
                }
                val resolver = instrumentation.targetContext.contentResolver
                val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                requireNotNull(resolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
        }
    }

    private fun openTask(model: AppModel, title: String) {
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.TASKS }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.$title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.$title").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.hierarchy").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun stored(model: AppModel, title: String): TaskRecord = runBlocking { model.store.reader.tasks(SampleIds.STUDIO, includeArchived = true).first { it.title == title } }
    private fun state(model: AppModel, id: String) = runBlocking { model.store.reader.taskRelationship(SampleIds.STUDIO, id) }
    private fun children(model: AppModel, id: String) = runBlocking { model.store.reader.directSubtasks(SampleIds.STUDIO, id) }

    /** The count follows the stored state through the screen's own read, which may still be settling: wait for it, then assert. */
    private fun awaitCount(text: String) {
        runCatching { compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtasks.count").fetchSemanticsNodes().any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") == text } } }
        compose.onNodeWithTag("task.subtasks.count").performScrollTo().assertTextEquals(text)
    }

    /**
     * Tasks injected into a channel are not on the sample Tower, so a channel task-list sync that completes after the injection
     * correctly flags them removed (Tower's complete list is authoritative), and a task or relationship read that begins after
     * it replaces the membership. Inject only once this device's task syncs have settled: nothing task-related is running or queued.
     */
    private fun awaitTaskSyncsSettled(model: AppModel) = compose.waitUntil(20_000) {
        val status = model.engine.status.value
        (status.running + status.queued).none { it.startsWith("tasks:") || it.startsWith("task:") || it.startsWith("task-relationships:") } &&
            compose.onAllNodesWithText("Refreshing subtasks", substring = true).fetchSemanticsNodes().isEmpty()
    }

    private fun waitComplete(model: AppModel, id: String) = compose.waitUntil(20_000) { state(model, id)?.complete == true }

    private fun addSubtask(title: String) {
        compose.onNodeWithTag("task.subtask.add").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task.subtask.title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.subtask.title").performTextReplacement(title)
        compose.onNodeWithTag("task.subtask.create").performClick()
    }

    private fun child(parentId: String, channel: String, scope: String?, title: String, created: String, version: Long = 1): TaskRecord =
        TaskRecord.parse(toJsonElement(mapOf(
            "id" to UUID.randomUUID().toString(), "workspace_id" to SampleIds.STUDIO, "scope_id" to scope, "channel_id" to channel, "title" to title,
            "state" to "new", "priority" to "sand", "created_at" to created, "updated_at" to created, "row_version" to version,
            "metadata" to mapOf("parent_task_id" to parentId, "predecessor_task_ids" to emptyList<String>()),
        )) as JsonObject)

    @Test fun nestedSubtasksAreCreatedConfirmedAndExpandedWithTheirOwnLists() = launch { model ->
        openTask(model, "Review the native inbox")
        val parent = stored(model, "Review the native inbox")
        waitComplete(model, parent.id)
        compose.onNodeWithTag("task.subtasks.count").performScrollTo()
        shot("01-parent-confirmed")

        val first = "Nested child ${System.nanoTime()}"
        addSubtask(first)
        compose.waitUntil(20_000) { runBlocking { model.store.reader.tasks(SampleIds.STUDIO, includeArchived = true).any { it.title == first } } }
        val child = stored(model, first)
        // The acknowledged creation joins the stored membership and the list; the count stays certain.
        compose.waitUntil(20_000) { child.id in children(model, parent.id).map { it.id } && compose.onAllNodesWithTag("task.subtask.${child.id}").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(state(model, parent.id)!!.complete)
        awaitCount("Subtasks ${children(model, parent.id).size}")

        // The child is its own parent for a second level: open it, add, return, expand.
        compose.onNodeWithTag("task.subtask.${child.id}").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.parent").fetchSemanticsNodes().isNotEmpty() }
        waitComplete(model, child.id)
        val grand = "Grandchild ${System.nanoTime()}"
        addSubtask(grand)
        compose.waitUntil(20_000) { runBlocking { model.store.reader.tasks(SampleIds.STUDIO, includeArchived = true).any { it.title == grand } } }
        val grandchild = stored(model, grand)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtask.${grandchild.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.parent").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtask.expand.${child.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.subtask.expand.${child.id}").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtask.${grandchild.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.subtask.${grandchild.id}").performScrollTo().assertIsDisplayed()
        assertEquals("each expansion has its own stored list", setOf(grandchild.id), children(model, child.id).map { it.id }.toSet())
        assertEquals("a grandchild is not a direct child of the root", false, grandchild.id in children(model, parent.id).map { it.id })
        shot("02-nested-expansion")
    }

    @Test fun staleCachedChildrenAreReplacedByTheConfirmedListAndLiveUpsertsRestrictionAndRelaunchAreObserved() {
        var parentId = ""
        var staleId = ""
        launch { model ->
            val parent = stored(model, "Write release notes for build 3")
            parentId = parent.id
            // A saved task that names the parent but that Tower does not list for it (moved or removed elsewhere).
            val stale = child(parent.id, parent.channelId, parent.scopeId, "Stale cached child", "2026-10-01T09:00:00.000Z")
            staleId = stale.id
            awaitTaskSyncsSettled(model)
            runBlocking { model.store.writer.upsertTasks(SampleIds.STUDIO, listOf(stale)) }
            assertNull("never read before: only saved metadata backs the list", state(model, parent.id))
            assertTrue(stale.id in children(model, parent.id).map { it.id })

            openTask(model, "Write release notes for build 3")
            waitComplete(model, parent.id)
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtask.${stale.id}").fetchSemanticsNodes().isEmpty() }
            awaitCount("Subtasks 0")
            assertEquals("the stale record is neither deleted nor re-parented", parent.id, TaskHierarchy.parentId(runBlocking { model.store.reader.task(SampleIds.STUDIO, stale.id)!! }))
            shot("03-stale-replaced")

            // An authorized upsert whose timestamps are decades off this device's clock still joins the list at once.
            val skewed = child(parent.id, parent.channelId, parent.scopeId, "Skewed live child", "2001-01-01T00:00:00.000Z", version = 5)
            awaitTaskSyncsSettled(model)
            runBlocking { model.store.writer.upsertTasks(SampleIds.STUDIO, listOf(skewed)) }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtask.${skewed.id}").fetchSemanticsNodes().isNotEmpty() }
            awaitCount("Subtasks 1")

            // A restriction recorded by the store is observed with no task change: the count turns uncertain and says why.
            val generation = state(model, parent.id)!!.generation
            runBlocking { model.store.writer.failTaskRelationshipRead(SampleIds.STUDIO, parent.id, generation, restricted = true) }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Some subtasks may not be visible", substring = true).fetchSemanticsNodes().isNotEmpty() }
            awaitCount("Subtasks 1+")
            shot("04-restricted")

            // Refresh reads the direct list again: Tower lists everything, so the restriction lifts.
            compose.onNodeWithTag("task.subtasks.refresh").performScrollTo().performClick()
            compose.waitUntil(20_000) { state(model, parent.id)?.restricted == false && state(model, parent.id)?.complete == true }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Some subtasks may not be visible", substring = true).fetchSemanticsNodes().isEmpty() }
            assertFalse("the skewed child is not on the sample Tower, so a complete read drops it from the list", skewed.id in children(model, parent.id).map { it.id })
            awaitCount("Subtasks 0")
            shot("05-confirmed-again")
        }
        // Relaunch: the stored state and membership are there before any network read.
        launch { model ->
            val state = state(model, parentId)
            assertNotNull(state)
            assertTrue(state!!.complete)
            assertEquals("a replaced list stays replaced after relaunch", false, staleId in children(model, parentId).map { it.id })
            assertEquals(TaskHierarchy.Kind.COMPLETE, TaskHierarchy.kind(state, readable = true))
            assertEquals(TaskHierarchy.Kind.CACHED, TaskHierarchy.kind(state, readable = false))
        }
    }

    @Test fun aPreviouslyCompleteListTurnsUncertainWhenItsRefreshIsInterruptedAndRecoversOnRefresh() {
        var parentId = ""
        var keptId = ""
        launch { model ->
            // This may be the first test on a fresh install: wait for the sample tasks to be stored.
            compose.waitUntil(20_000) { runBlocking { model.store.reader.tasks(SampleIds.STUDIO, includeArchived = true).any { it.title == "Review the native inbox" } } }
            val parent = stored(model, "Review the native inbox")
            parentId = parent.id
            val kept = child(parent.id, parent.channelId, parent.scopeId, "Kept while refreshing", "2026-10-01T09:00:00.000Z")
            keptId = kept.id
            openTask(model, "Review the native inbox")
            waitComplete(model, parent.id)
            // The screen's read has settled on a certain list; remember how many children Tower confirmed.
            val confirmed = children(model, parent.id).size
            awaitCount("Subtasks $confirmed")
            // A child joins the confirmed list (as a live upsert does), then the next refresh stops after a first page that does not list it.
            awaitTaskSyncsSettled(model)
            runBlocking { model.store.writer.upsertTasks(SampleIds.STUDIO, listOf(kept)) }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtask.$keptId").fetchSemanticsNodes().isNotEmpty() }
            awaitCount("Subtasks ${confirmed + 1}")
            runBlocking {
                val read = model.store.writer.beginTaskRelationshipRead(SampleIds.STUDIO, parent.id)
                model.store.writer.applyTaskRelationshipPage(SampleIds.STUDIO, parent.id, read.generation, emptyList(), emptyList(), false, next = "c1", fromCursor = null)
            }
            awaitCount("Subtasks ${confirmed + 1}+")
            compose.onNodeWithTag("task.subtasks.state").assertTextContains("stopped part-way", substring = true)
            assertTrue("the earlier confirmation is retained", state(model, parent.id)!!.complete)
            shot("06-interrupted-refresh-uncertain")

            // A network failure on the resumed read is a different state, with its own wording.
            val generation = state(model, parent.id)!!.generation
            runBlocking { model.store.writer.failTaskRelationshipRead(SampleIds.STUDIO, parent.id, generation, restricted = false, failure = "offline") }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("may be out of date", substring = true).fetchSemanticsNodes().isNotEmpty() }
            awaitCount("Subtasks ${confirmed + 1}+")
            compose.onNodeWithTag("task.subtask.$keptId").assertIsDisplayed()
            shot("07-refresh-failed-cached")

            // Recovery: refresh resumes, completes, and the member Tower never listed is dropped.
            compose.onNodeWithTag("task.subtasks.refresh").performScrollTo().performClick()
            compose.waitUntil(20_000) { state(model, parent.id)?.let { it.nextCursor == null && it.failure == null } == true }
            awaitCount("Subtasks $confirmed")
            assertEquals(TaskHierarchy.Kind.COMPLETE, TaskHierarchy.kind(state(model, parent.id), readable = true))
            shot("08-refresh-recovered")
        }
        // Relaunch: the removed member stays removed.
        launch { model ->
            assertEquals(false, keptId in children(model, parentId).map { it.id })
            assertEquals(TaskHierarchy.Kind.COMPLETE, TaskHierarchy.kind(state(model, parentId), readable = true))
        }
    }
}
