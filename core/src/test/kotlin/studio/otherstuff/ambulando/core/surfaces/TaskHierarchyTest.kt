package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.TaskRecord

class TaskHierarchyTest {
    private val w = "11111111-1111-1111-1111-111111111111"
    private val c = "22222222-2222-2222-2222-222222222222"
    private fun id(n: Int) = "00000000-0000-0000-0000-%012d".format(n)

    private fun task(n: Int, parent: Int? = null, predecessor: Int? = null, state: String = "new", created: String = "2026-01-0${n}T00:00:00Z"): TaskRecord {
        val metadata = JsonObject(linkedMapOf(
            "parent_task_id" to (parent?.let { JsonPrimitive(id(it)) } ?: JsonNull),
            "predecessor_task_ids" to JsonArray(predecessor?.let { listOf(JsonPrimitive(id(it))) } ?: emptyList())))
        return TaskRecord(w, id(n), null, c, null, "Task $n", null, state, "sand", null, null, 1, 0, created, created, JsonObject(mapOf("metadata" to metadata)))
    }

    @Test fun directChildrenIncludeArchivedAndExcludeDependencies() {
        val index = TaskHierarchy.index(listOf(task(1), task(2, parent = 1), task(3, parent = 1, state = "archive"), task(4, predecessor = 1), task(5, parent = 2)))
        assertEquals(listOf(id(2), id(3)), index[id(1)]!!.map { it.id })
        assertEquals(listOf(id(5)), index[id(2)]!!.map { it.id })
        assertNull(index[id(4)])
    }

    @Test fun childrenAreOldestFirstAndASelfParentIsIgnored() {
        val index = TaskHierarchy.index(listOf(task(3, parent = 1, created = "2026-01-01T00:00:00Z"), task(2, parent = 1, created = "2026-02-01T00:00:00Z"), task(1, parent = 1)))
        assertEquals(listOf(id(3), id(2)), index[id(1)]!!.map { it.id })
    }

    @Test fun cycleIsDetectedAlongThePath() {
        assertTrue(TaskHierarchy.closesCycle(listOf(id(1), id(2)), id(1).uppercase()))
        assertFalse(TaskHierarchy.closesCycle(listOf(id(1), id(2)), id(3)))
    }

    @Test fun anUncertainCountCarriesAPlus() {
        assertEquals("3", TaskHierarchy.countLabel(3, true))
        assertEquals("3+", TaskHierarchy.countLabel(3, false))
    }

    @Test fun parentOfABaseTaskIsNull() {
        assertNull(TaskHierarchy.parentId(task(1)))
        assertEquals(id(1), TaskHierarchy.parentId(task(2, parent = 1)))
    }

    private fun state(complete: Boolean = false, restricted: Boolean = false, failure: String? = null, cursor: String? = null, pages: Int = 0) =
        studio.otherstuff.ambulando.core.store.TaskRelationshipState(id(1), complete, restricted, failure, 1, cursor, pages, null, null)

    @Test fun unloadedCachedPartialOfflineAndRestrictedAreDistinct() {
        val kind = TaskHierarchy::kind
        assertEquals(TaskHierarchy.Kind.UNLOADED, kind(null, true))
        assertEquals(TaskHierarchy.Kind.UNLOADED, kind(state(), true))
        assertEquals(TaskHierarchy.Kind.OFFLINE, kind(null, false))
        assertEquals("a failed first read with nothing stored", TaskHierarchy.Kind.OFFLINE, kind(state(failure = "failed"), true))
        assertEquals(TaskHierarchy.Kind.COMPLETE, kind(state(complete = true, pages = 2), true))
        assertEquals(TaskHierarchy.Kind.CACHED, kind(state(complete = true, pages = 2), false))
        assertEquals("a failed refresh of a confirmed list", TaskHierarchy.Kind.CACHED, kind(state(complete = true, failure = "failed"), true))
        assertEquals(TaskHierarchy.Kind.PARTIAL, kind(state(pages = 1, cursor = "c1"), true))
        assertEquals("still partial when the device goes offline", TaskHierarchy.Kind.PARTIAL, kind(state(pages = 1, cursor = "c1"), false))
        assertEquals(TaskHierarchy.Kind.RESTRICTED, kind(state(complete = true, restricted = true), true))
        assertEquals(TaskHierarchy.Kind.RESTRICTED, kind(state(restricted = true), false))
        assertTrue(TaskHierarchy.isConfirmed(TaskHierarchy.Kind.COMPLETE))
        listOf(TaskHierarchy.Kind.UNLOADED, TaskHierarchy.Kind.CACHED, TaskHierarchy.Kind.PARTIAL, TaskHierarchy.Kind.OFFLINE, TaskHierarchy.Kind.RESTRICTED)
            .forEach { assertFalse("$it carries an uncertain count", TaskHierarchy.isConfirmed(it)) }
    }
}
