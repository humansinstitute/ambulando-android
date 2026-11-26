package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.FileRecord

class GoalHomeTests {
    private fun doc(id: String, goal: Goal) = GoalDocument(
        FileRecord("11111111-1111-4111-8111-111111111111", id, null, "22222222-2222-4222-8222-222222222222", null, null, "${goal.name}.goal.json", contentType = Goal.MIME, sizeBytes = null, sha256 = null, createdAt = "", updatedAt = ""),
        "v-$id", goal,
    )

    @Test fun formIssuesMatchSaveRules() {
        assertEquals("Add a name.", GoalForm.goalIssue(" ", "x", null, null))
        assertEquals("Add a description.", GoalForm.goalIssue("x", "", null, null))
        assertNotNull(GoalForm.goalIssue("x".repeat(Goal.MAX_NAME + 1), "x", null, null))
        assertNotNull(GoalForm.goalIssue("x", "y".repeat(Goal.MAX_DESCRIPTION + 1), null, null))
        assertEquals("The target date is before the start date.", GoalForm.goalIssue("x", "y", "2026-12-18", "2026-11-26"))
        assertNull(GoalForm.goalIssue("x", "y", "2026-11-26", "2026-11-26"))
        assertNull(GoalForm.goalIssue("x", "y\nwith a second line", null, "2026-12-18"))
        assertEquals("Add a title.", GoalForm.checkpointIssue("", ""))
        assertNull(GoalForm.checkpointIssue("Step", ""))
        // Everything the form accepts, the model accepts.
        Goal.create("x", "y", "2026-11-26", "2026-11-26")
    }

    /** The Mac app's GoalBriefingTests vectors. */
    private val briefingGoals = listOf(
        doc("1", GoalFocus.setting(Goal("Ship", "d", "2026-11-26", "2026-12-18", checkpoints = listOf(
            GoalCheckpoint("a", "Agree", "2026-11-26", reached = true), GoalCheckpoint("b", "Android", "2026-12-05"), GoalCheckpoint("c", "Users", "2026-12-18"))), "V")),
        doc("2", GoalFocus.setting(Goal("Run", "d", checkpoints = listOf(GoalCheckpoint("a", "10 km", "2026-11-20"))), "l")),
        doc("3", Goal("Later", "d", targetDate = "2026-11-26", checkpoints = listOf(GoalCheckpoint("a", "Out of focus next")))),
        doc("4", Goal("Done", "d", targetDate = "2026-11-26", status = GoalStatus.COMPLETED)),
    )

    @Test fun todayListsNextForGoalsInFocusDatedAndPastDates() {
        assertEquals(listOf(
            GoalBriefing.Fact.Next("Ship", "Android", "2026-12-05"),
            GoalBriefing.Fact.Next("Run", "10 km", "2026-11-20"),
            GoalBriefing.Fact.StartDate("Ship"),
            GoalBriefing.Fact.Dated("Ship", "Agree", true),
            GoalBriefing.Fact.PastDate("Run", "10 km", "2026-11-20"),
            GoalBriefing.Fact.TargetDate("Later"),
        ), GoalBriefing.facts(briefingGoals, "2026-11-26", "2026-11-26", focus = 2))
    }

    @Test fun anotherDayListsOnlyWhatIsDatedThatDay() {
        assertEquals(listOf(GoalBriefing.Fact.Dated("Ship", "Android", false)), GoalBriefing.facts(briefingGoals, "2026-12-05", "2026-11-26"))
        assertTrue(GoalBriefing.facts(briefingGoals, "2026-12-06", "2026-11-26").isEmpty())
    }
}
