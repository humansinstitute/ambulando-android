package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*

class GoalsControllerTests {
    private val w = SampleIds.STUDIO
    private fun controller(h: SampleHarness, ok: () -> Boolean = { true }) = GoalsController(h.api, w, h.app, TestKeys.NPUB, ok)
    /** Sets the controller up in the owner-only fallback, the one container the sample Tower can model. */
    private fun ready(h: SampleHarness, c: GoalsController = controller(h)) = runBlocking { c.refresh(); if (c.state.value.space is GoalsSpaceState.NeedsSetup) c.setUp(null, emptyList()); c }

    @Test fun twoClientsSeeTheSameGoalsAndEditsSyncBothWays() = runBlocking {
        val h = SampleHarness(); val phone = ready(h); val other = controller(h)
        assertTrue(phone.state.value.loaded); assertTrue(phone.state.value.goals.isEmpty())
        val created = phone.create(Goal.create("Ship Ambulando", "Daily driver", null, "2026-12-18"))
        other.refresh(); assertEquals(listOf("Ship Ambulando"), other.state.value.goals.map { it.goal.name })
        other.edit(created.id, GoalEdit.AddCheckpoint(GoalCheckpoint("a", "Agree")), GoalEdit.SetReached("a", false, true))
        phone.refresh(); assertEquals(1, phone.state.value.goals.single().goal.reachedCount)
    }

    @Test fun completedAndArchivedKeepWorkAndSortAfterActive() = runBlocking {
        val h = SampleHarness(); val c = ready(h)
        val a = c.create(Goal.create("A", "d")); val b = c.create(Goal.create("B", "d"))
        val link = GoalLink(GoalLinkType.TASK, "11111111-1111-4111-8111-111111111111")
        c.edit(a.id, GoalEdit.Link(link), GoalEdit.SetStatus(GoalStatus.ACTIVE, GoalStatus.ARCHIVED))
        assertEquals(listOf("B", "A"), c.state.value.goals.map { it.goal.name })
        c.refresh()
        assertEquals(listOf(link), c.state.value.goals.last().goal.links)   // archived goal retains its references
        c.edit(a.id, GoalEdit.SetStatus(GoalStatus.ARCHIVED, GoalStatus.ACTIVE)); assertEquals(GoalStatus.ACTIVE, c.goal(a.id)!!.goal.status)
    }

    @Test fun unsupportedGoalIsListedAsUnreadableAndNeverRewritten() = runBlocking {
        val h = SampleHarness(); val c = ready(h)
        val g = c.create(Goal.create("Old", "d"))
        val newer = kotlinx.serialization.json.JsonObject(g.goal.toJson() + ("schema_version" to kotlinx.serialization.json.JsonPrimitive(9))).toString().toByteArray()
        val obj = h.api.store(w, h.app, newer, Goal.MIME, "n.goal.json", null) { }
        h.api.addFileVersion(w, h.app, g.id, g.versionId, obj, java.util.UUID.randomUUID().toString())
        c.refresh()
        assertTrue(c.state.value.goals.isEmpty()); assertEquals(1, c.state.value.unreadable.size)
    }

    @Test fun wrongTypedCheckpointsAreUnreadableAndAnEditCannotEraseThem() = runBlocking {
        val h = SampleHarness(); val c = ready(h)
        val g = c.create(Goal.create("Damaged", "d"))
        val damaged = kotlinx.serialization.json.JsonObject(g.goal.toJson() + ("checkpoints" to kotlinx.serialization.json.JsonPrimitive("oops"))).toString().toByteArray()
        val obj = h.api.store(w, h.app, damaged, Goal.MIME, "n.goal.json", null) { }
        h.api.addFileVersion(w, h.app, g.id, g.versionId, obj, java.util.UUID.randomUUID().toString())
        c.refresh()
        assertTrue(c.state.value.goals.isEmpty()); assertEquals("Damaged.goal.json", c.state.value.unreadable.single().name)
        assertTrue(runCatching { c.edit(g.id, GoalEdit.SetName("Damaged", "Renamed")) }.exceptionOrNull() is GoalError.Invalid)
        // Nothing was written: the stored version is still the damaged one.
        assertEquals(2, h.api.fileVersions(w, h.app, g.id).versions.size)
    }

    @Test fun lostAccessBlocksReadsAndWrites() = runBlocking {
        var ok = true; val h = SampleHarness(); val c = ready(h, controller(h) { ok })
        val g = c.create(Goal.create("A", "d")); ok = false
        c.refresh(); assertNotNull(c.state.value.error)
        assertTrue(runCatching { c.edit(g.id, GoalEdit.SetName("A", "B")) }.isFailure)
        ok = true; assertEquals("A", h.api.readGoal(w, h.app, g.id).goal.name)
    }
}
