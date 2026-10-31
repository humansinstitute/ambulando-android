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
}
