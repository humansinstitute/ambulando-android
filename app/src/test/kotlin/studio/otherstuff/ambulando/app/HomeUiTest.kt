package studio.otherstuff.ambulando.app

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.transport.FileRecord
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class HomeUiTest {
    @get:Rule val compose = createComposeRule()

    private fun doc(id: String, name: String, checkpoints: List<GoalCheckpoint> = emptyList()) = GoalDocument(
        FileRecord("11111111-1111-4111-8111-111111111111", id, null, "22222222-2222-4222-8222-222222222222", null, null, "$name.goal.json", contentType = Goal.MIME, sizeBytes = null, sha256 = null, createdAt = "", updatedAt = ""),
        "v", Goal.create(name, "About $name", "2026-11-26", "2026-12-18").copy(checkpoints = checkpoints),
    )

    @Test fun firstThreeInFocusLaterQuieter() {
        val goals = (1..5).map { doc("g$it", "Goal $it", listOf(GoalCheckpoint("a", "A", reached = true), GoalCheckpoint("b", "B"), GoalCheckpoint("c", "C"))) }
        val moves = mutableListOf<Pair<String, Int>>()
        compose.setContent { AmbulandoTheme { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { HomeGoalList(goals, enabled = true, onOpen = {}, onMove = { id, to -> moves += id to to }) } } }
        for (i in 1..5) {
            val state = compose.onNodeWithTag("home.goal.g$i").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
            assertTrue(state, state.startsWith(if (i <= 3) "In focus, $i of 5" else "Out of focus, $i of 5"))
        }
        compose.onAllNodesWithTag("home.goal.dots", useUnmergedTree = true).assertCountEquals(5)
    }

    @Test fun collapsedShowsGoalsInFocusAndAPeek() {
        val goals = (1..6).map { doc("g$it", "Goal $it") }
        compose.setContent { AmbulandoTheme { HomeGoalList(goals, enabled = true, onOpen = {}, onMove = { _, _ -> }, expanded = false) } }
        for (i in 1..4) compose.onNodeWithTag("home.goal.g$i").assertExists()
        compose.onNodeWithTag("home.goal.g5").assertDoesNotExist()
    }

    @Test fun menuMoves() {
        val goals = (1..5).map { doc("g$it", "Goal $it") }
        val moves = mutableListOf<Pair<String, Int>>()
        compose.setContent { AmbulandoTheme { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { HomeGoalList(goals, enabled = true, onOpen = {}, onMove = { id, to -> moves += id to to }) } } }
        compose.onAllNodesWithTag("home.goal.menu")[3].performScrollTo().performClick()
        compose.onNodeWithTag("home.goal.top").performClick()
        compose.waitForIdle(); assertEquals(moves.toString(), listOf("g4" to 0), moves)
        compose.onAllNodesWithTag("home.goal.menu")[0].performScrollTo().performClick()
        compose.onAllNodesWithTag("home.goal.up").assertCountEquals(0)
        compose.onNodeWithTag("home.goal.down").performClick()
        assertEquals("g1" to 1, moves.last())
    }

    @Test fun briefingStatesGoalFactsAndStepsBackButNotPastToday() {
        val today = LocalDate.parse("2026-12-05")
        var day = today
        val ship = doc("s", "Ship", listOf(GoalCheckpoint("u", "Use it daily", "2026-12-05")))
        compose.setContent { AmbulandoTheme { DailyBriefing(listOf(ship), loaded = true, ready = true, day = day, onDay = { day = it }, today = today) } }
        compose.onNodeWithTag("home.briefing.day").assertTextEquals("Today, 5 Dec 2026")
        compose.onAllNodesWithTag("home.briefing.fact").assertAny(hasText("Next for Ship: Use it daily", substring = true))
        compose.onAllNodesWithTag("home.briefing.fact").assertAny(hasText("Ship: Use it daily (not reached yet)"))
        compose.onNodeWithTag("home.briefing.source").assertTextEquals("From your saved goals. Agent briefings aren’t available yet.")
        compose.onNodeWithTag("home.briefing.next").assertIsNotEnabled()
        compose.onNodeWithTag("home.briefing.prev").performClick()
        assertEquals(LocalDate.parse("2026-12-04"), day)
    }

    @Test fun briefingSaysSoWhenNothingIsDated() {
        val today = LocalDate.parse("2026-12-05")
        compose.setContent { AmbulandoTheme { DailyBriefing(listOf(doc("q", "Quiet").let { it.copy(goal = it.goal.copy(startDate = null, targetDate = null)) }), loaded = true, ready = true, day = today, onDay = {}, today = today) } }
        compose.onNodeWithTag("home.briefing.empty").assertTextEquals("Nothing in your goals is dated today, and no goal in focus has a next checkpoint.")
    }

    @Test fun anotherDayExplainsReachedIsTheStatusNow() {
        val today = LocalDate.parse("2026-12-05")
        val ship = doc("s", "Ship", listOf(GoalCheckpoint("a", "Agree", "2026-11-26", reached = true)))
        compose.setContent { AmbulandoTheme { DailyBriefing(listOf(ship), loaded = true, ready = true, day = LocalDate.parse("2026-11-26"), onDay = {}, today = today) } }
        compose.onNodeWithTag("home.briefing.otherDay").assertExists()
        compose.onAllNodesWithTag("home.briefing.fact").assertAny(hasText("Ship: Agree (reached now)"))
        compose.onNodeWithTag("home.briefing.today").assertExists()
    }
}
