package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.transport.TowerError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.Ids
import studio.otherstuff.ambulando.core.obj

/** Task tree projection: children below, successors to the right, collapse and bounds; relationship loading. */
class TaskTreeTests {
    private val w = Ids.workspace
    private val c = Ids.channel1

    private fun id(i: Int) = Ids.uuid(i, "0007")

    private fun row(n: Int, parent: Int? = null, after: Int? = null): JsonObject {
        val metadata = LinkedHashMap<String, Any?>()
        if (parent != null) metadata["parent_task_id"] = id(parent)
        if (after != null) metadata["predecessor_task_ids"] = listOf(id(after), id(after))
        return obj("id" to id(n), "workspace_id" to w, "channel_id" to c, "title" to "T$n", "state" to "new",
            "created_at" to "2026-10-25T10:00:%02d.000Z".format(n), "metadata" to metadata)
    }

    private fun task(n: Int, parent: Int? = null, after: Int? = null): TaskTreeNode = TaskTreeNode.fromRow(row(n, parent, after))!!

    @Test fun childrenGoBelowAndSuccessorsToTheRight() {
        val tasks = listOf(task(1), task(2, parent = 1), task(3, parent = 1, after = 2), task(4, after = 1))
        assertEquals("duplicate predecessors are dropped", listOf(id(2)), tasks[2].predecessors)
        val graph = TaskTreeGraph.project(tasks, root = tasks[0].id)
        assertEquals(4, graph.nodes.size)
        val p = graph.positions
        assertEquals(TaskTreeGraph.Cell(0, 0), p[tasks[0].id])
        assertEquals("child below its parent", TaskTreeGraph.Cell(0, 1), p[tasks[1].id])
        assertEquals("a child successor goes right of its predecessor", p[tasks[1].id]!!.x + 1, p[tasks[2].id]!!.x)
        assertEquals("successor to the right of the root", TaskTreeGraph.Cell(1, 0), p[tasks[3].id])
        assertTrue(graph.edges.contains(TaskTreeGraph.Edge(tasks[0].id, tasks[1].id, true)))
        assertTrue(graph.edges.contains(TaskTreeGraph.Edge(tasks[1].id, tasks[2].id, false)))
        assertFalse(graph.bounded)
    }

    @Test fun collapsedBranchesAndBoundsLimitTheTree() {
        val tasks = listOf(task(1), task(2, parent = 1), task(3, parent = 2))
        assertEquals(listOf("T1", "T2"), TaskTreeGraph.project(tasks, root = tasks[0].id, collapsed = listOf(tasks[1].id)).nodes.map { it.title })
        val bounded = TaskTreeGraph.project(tasks, root = tasks[0].id, limit = 2)
        assertEquals(2, bounded.nodes.size)
        assertTrue(bounded.bounded)
    }

    @Test fun referencesOutsideTheTreeAreListed() {
        val orphan = task(5, parent = 9, after = 8)
        val graph = TaskTreeGraph.project(listOf(orphan, task(8)), root = orphan.id)
        assertEquals(listOf(TaskTreeGraph.Reference(orphan.id, id(9), parent = true, available = false),
            TaskTreeGraph.Reference(orphan.id, id(8), parent = false, available = true)), graph.references)
    }

    @Test fun viewRoundTripsThroughTheSavedLayout() {
        val view = TaskTreeView("inst-1", rootTaskId = id(1), collapsed = listOf(id(2)), upstream = listOf(id(3)), scale = 1.5)
        val layout = obj("x" to 0, "task_tree" to view.toJson())
        assertEquals(view, TaskTreeView.fromLayout(layout, napletId = "n1"))
        val fallback = TaskTreeView.fromLayout(obj("task_tree" to mapOf("viewport" to mapOf("scale" to 9))), napletId = "n1")
        assertEquals("n1", fallback.instanceId)
        assertEquals(2.0, fallback.scale, 0.0)
        assertTrue(view.toJson().containsKey("root_task_id"))
    }

    @Test fun loadFollowsPagesAndSkipsWithheldBranches() = runBlocking {
        val pages = mapOf(
            (id(1) to null) to TaskTreeService.RelationshipsPage(row(1), listOf(row(2, parent = 1)), emptyMap(), false, "c2"),
            (id(1) to "c2") to TaskTreeService.RelationshipsPage(row(1), listOf(row(3, parent = 1)), emptyMap(), false, null),
            (id(2) to null) to TaskTreeService.RelationshipsPage(row(2, parent = 1), emptyList(), emptyMap(), true, null),
        )
        val reader = TaskTreeService.RelationshipsReader { taskId, cursor ->
            pages[taskId to cursor] ?: throw SurfaceHttpException(403, "forbidden")
        }
        val loaded = TaskTreeService.load(reader, root = id(1))
        assertEquals(setOf(id(1), id(2), id(3)), loaded.tasks.map { it.id }.toSet())
        assertTrue("a withheld task (403) and Tower's own flag both mark the tree incomplete", loaded.unavailable)
        assertFalse(loaded.partial)
        val root = runCatching { TaskTreeService.load(reader, root = id(7)) }.exceptionOrNull()
        assertEquals(TaskTreeError.Unavailable, root)
    }

    @Test fun relationshipAnswersAreCheckedAndCreateBodiesCarryTheLinks() {
        val answer = obj("workspace_id" to w, "task" to row(1), "tasks" to listOf(row(2, parent = 1)), "has_more" to true, "next_cursor" to "x")
        val page = TaskTreeService.parseRelationships(answer, w, id(1), null)
        assertEquals("x", page.next)
        assertTrue(runCatching { TaskTreeService.parseRelationships(answer, w, id(1), "x") }.exceptionOrNull() is TowerError.Malformed)
        assertTrue(runCatching { TaskTreeService.parseRelationships(answer, Ids.workspace2, id(1), null) }.exceptionOrNull() is TowerError.Malformed)
        val successor = TaskTreeService.createBody("  Next  ", predecessor = task(2, parent = 1), requestId = "r1")
        assertEquals("Next", successor["title"]!!.jsonPrimitive.content)
        val metadata = successor["metadata"]!!.jsonObject
        assertEquals("a successor joins its predecessor's parent", id(1), metadata["parent_task_id"]!!.jsonPrimitive.content)
        assertEquals(id(2), (metadata["predecessor_task_ids"] as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content)
        assertEquals(TaskTreeError.Failed("Enter a title."), runCatching { TaskTreeService.createBody(" ", requestId = "r") }.exceptionOrNull())
        assertEquals(id(1), TaskTreeService.confirmCreated(201, obj("task" to row(1)), c, null)["id"]!!.jsonPrimitive.content)
        assertEquals(TaskTreeError.Failed("You can't add tasks in this channel."), runCatching { TaskTreeService.confirmCreated(403, null, c, null) }.exceptionOrNull())
        assertEquals(TaskTreeError.Failed("Task destination was not confirmed. Retry the same entry."),
            runCatching { TaskTreeService.confirmCreated(201, obj("task" to row(1)), Ids.channel2, null) }.exceptionOrNull())
    }
}
