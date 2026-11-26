package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.support.TestKeys

class GoalsAPITests {
    private val w = SampleIds.STUDIO
    private val task = "11111111-1111-4111-8111-111111111111"
    private fun cp(id: String, t: String = id) = GoalCheckpoint(id, t)
    /** The owner-only fallback space: the only container the sample Tower can model without per-channel grants. */
    private fun space(h: SampleHarness) = runBlocking { h.api.createGoalsSpace(w, h.app, TestKeys.NPUB, null, emptyList()) }

    @Test fun createReopenEditAndListThroughRealFileContract() = runBlocking {
        val h = SampleHarness(); val api = h.api
        val home = space(h)
        val created = api.createGoal(w, h.app, home.id, Goal.create("Ship Ambulando", "Daily driver", "2026-11-26", "2026-12-18"))
        assertEquals("Ship Ambulando", api.readGoal(w, h.app, created.id).goal.name)
        val listed = api.goalFiles(w, h.app, home.id)
        assertEquals(listOf(created.id), listed.map { it.id })
        val edited = api.editGoal(w, h.app, created.id, listOf(
            GoalEdit.AddCheckpoint(cp("a", "Agree")), GoalEdit.AddCheckpoint(cp("b", "Daily")), GoalEdit.SetReached("a", false, true),
            GoalEdit.Link(GoalLink(GoalLinkType.TASK, task)),
        ))
        assertNotEquals(created.versionId, edited.versionId)
        val reopened = api.readGoal(w, h.app, listed.single())
        assertEquals(1, reopened.goal.reachedCount); assertEquals(listOf("a", "b"), reopened.goal.checkpoints.map { it.id })
        assertEquals(task, reopened.goal.links.single().recordId)
    }

    @Test fun staleBaseRebasesIndependentEditsAndRefusesConflictingOnes() = runBlocking {
        val h = SampleHarness(); val api = h.api
        val home = space(h)
        val g = api.createGoal(w, h.app, home.id, Goal.create("Ship", "Daily driver"))
        // Another device saves between this device's read and write.
        val other = api.editGoal(w, h.app, g.id, listOf(GoalEdit.SetName("Ship", "Ship it")))
        // This device's edit, made against the older goal, still rebases: independent field.
        val mine = api.editGoal(w, h.app, g.id, listOf(GoalEdit.SetDescription("Daily driver", "Everyday")))
        assertEquals("Ship it", mine.goal.name); assertEquals("Everyday", mine.goal.description)
        assertEquals(other.goal.name, mine.goal.name)
        // A conflicting edit of the same field is refused and nothing is saved.
        val before = api.readGoal(w, h.app, g.id).versionId
        assertThrows(GoalError.Conflict::class.java) { runBlocking { api.editGoal(w, h.app, g.id, listOf(GoalEdit.SetName("Ship", "Mine"))) } }
        assertEquals(before, api.readGoal(w, h.app, g.id).versionId)
    }

    @Test fun softDeletedGoalFilesAreNeverGoals() = runBlocking {
        val h = SampleHarness(); val api = h.api
        val g = api.createGoal(w, h.app, space(h).id, Goal.create("Ship", "Daily driver"))
        assertTrue(GoalFiles.isGoal(g.file))
        val raw = g.file.raw
        fun withField(key: String, value: kotlinx.serialization.json.JsonElement) =
            studio.otherstuff.ambulando.core.transport.FileRecord.parse(kotlinx.serialization.json.JsonObject(raw + (key to value)))
        // The live delete leaves record_state "active" and sets deleted_at; that alone must hide the file.
        assertFalse(GoalFiles.isGoal(withField("deleted_at", kotlinx.serialization.json.JsonPrimitive("2026-11-26T08:00:00Z"))))
        assertFalse(GoalFiles.isGoal(withField("record_state", kotlinx.serialization.json.JsonPrimitive("deleted"))))
        assertTrue(GoalFiles.isGoal(withField("deleted_at", kotlinx.serialization.json.JsonNull)))
    }
}
