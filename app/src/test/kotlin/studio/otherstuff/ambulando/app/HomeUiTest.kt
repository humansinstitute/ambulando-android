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
import java.time.Instant
import studio.otherstuff.ambulando.core.briefing.*

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

    private val today=LocalDate.parse("2026-11-27")
    private val entry=BriefingEntry(Instant.parse("2026-11-26T21:30:00Z"),listOf("What changed and progress.","Suggested focus."),listOf(BriefingLink("task","task","channel","Underlying task")),"Only authorized sources were available.")
    private fun briefing(day:LocalDate=today)=Briefing("workspace","owner","author",mapOf(day to entry))

    @Test fun briefingShowsSavedProseAndLinksWithoutInferringGoals() {
        var opened:String?=null
        compose.setContent {AmbulandoTheme {PersonalDailyBriefing(briefing(),true,null,today,today,false,{}, {}, {opened=it.recordId})}}
        compose.onAllNodesWithTag("home.briefing.paragraph").assertCountEquals(2)
        compose.onNodeWithText("What changed and progress.").assertExists()
        compose.onNodeWithTag("home.briefing.source").assertTextContains("27 Nov 2026, 05:30 Perth",substring=true)
        compose.onNodeWithTag("home.briefing.coverage").assertExists()
        compose.onNodeWithTag("home.briefing.next").assertIsNotEnabled()
        compose.onNodeWithTag("home.briefing.link").performClick();assertEquals("task",opened)
    }
    @Test fun pendingAndMissingDayNeverUsePreviousAsToday() {
        var selected:LocalDate?=null
        compose.setContent {AmbulandoTheme {PersonalDailyBriefing(briefing(today.minusDays(1)),true,null,today,today,true,{selected=it},{},{})}}
        compose.onNodeWithTag("home.briefing.empty").assertTextEquals("Your morning briefing is due at 05:30 Perth.")
        compose.onAllNodesWithTag("home.briefing.paragraph").assertCountEquals(0)
        compose.onNodeWithTag("home.briefing.stale").performClick();assertEquals(today.minusDays(1),selected)
    }
    @Test fun failedRefreshDoesNotShowCachedAnalysisAsCurrent() {
        compose.setContent {AmbulandoTheme {PersonalDailyBriefing(briefing(),true,"Your briefing is unavailable.",today,today,false,{},{},{})}}
        compose.onNodeWithTag("home.briefing.unavailable").assertTextEquals("Your briefing is unavailable.")
        compose.onAllNodesWithTag("home.briefing.paragraph").assertCountEquals(0)
    }
    @Test fun historicalDayShowsItsSavedEntryAndCanReturnToToday() {
        val previous=today.minusDays(1);var selected:LocalDate?=null
        compose.setContent {AmbulandoTheme {PersonalDailyBriefing(briefing(previous),true,null,previous,today,false,{selected=it},{},{})}}
        compose.onNodeWithTag("home.briefing.day").assertTextEquals("26 Nov 2026")
        compose.onAllNodesWithTag("home.briefing.paragraph").assertCountEquals(2)
        compose.onNodeWithTag("home.briefing.today").performClick();assertEquals(today,selected)
    }
}
