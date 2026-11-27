package studio.otherstuff.ambulando.app.integration

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.PersonalDailyBriefing
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.briefing.*
import java.time.*

/** Synthetic native card validation; transport/ACL/hash checks are tested separately in BriefingAPITest. */
@RunWith(AndroidJUnit4::class)
class PersonalBriefingFlowTest {
    @get:Rule val compose=createComposeRule()
    @Test fun savedMorningHistoryLinksAndUnavailableStates() {
        val today=LocalDate.parse("2026-11-27");val prior=today.minusDays(1)
        var day by mutableStateOf(today);var loaded by mutableStateOf(false);var failure by mutableStateOf<String?>(null)
        var content by mutableStateOf<Briefing?>(null);var opened:String?=null
        val entry=BriefingEntry(Instant.parse("2026-11-26T21:30:00Z"),listOf("Synthetic progress since yesterday.","Synthetic suggested focus."),listOf(BriefingLink("task","synthetic-task","synthetic-channel","Underlying task")),"Synthetic authorized source coverage.")
        compose.setContent {AmbulandoTheme {Column(Modifier.verticalScroll(rememberScrollState())) {PersonalDailyBriefing(content,loaded,failure,day,today,false,{day=it},{},{opened=it.recordId})}}}
        compose.onNodeWithTag("home.briefing.loading").assertExists()
        compose.runOnIdle {content=Briefing("w","o","a",mapOf(today to entry,prior to entry.copy(paragraphs=listOf("Synthetic previous day."))),authorName="Synthetic author");loaded=true}
        compose.onNodeWithText("Synthetic progress since yesterday.").assertExists()
        compose.onNodeWithTag("home.briefing.next").assertIsNotEnabled()
        compose.onNodeWithTag("home.briefing.source").assertTextContains("Synthetic author",substring=true)
        compose.onNodeWithTag("home.briefing.link").performScrollTo().performClick();assertEquals("synthetic-task",opened)
        compose.onNodeWithTag("home.briefing.prev").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic previous day.").assertExists()
        compose.onNodeWithTag("home.briefing.day").assertTextEquals("26 Nov 2026")
        compose.onNodeWithTag("home.briefing.today").performClick()
        compose.runOnIdle {failure="Your briefing is unavailable. Check your connection."}
        compose.onNodeWithTag("home.briefing.unavailable").assertExists()
        compose.onAllNodesWithTag("home.briefing.paragraph").assertCountEquals(0)
        compose.runOnIdle {failure=null;content=content?.copy(entries=mapOf(prior to entry));day=today}
        compose.onNodeWithTag("home.briefing.empty").assertTextEquals("Today’s briefing hasn’t arrived yet.")
        compose.onNodeWithTag("home.briefing.stale").performClick();assertEquals(prior,day)
    }

    @Test fun perthRolloverKeepsSavedDayExplicitAndErrorsHideHistory() {
        val yesterday=LocalDate.parse("2026-11-27")
        var today by mutableStateOf(yesterday);var day by mutableStateOf(yesterday)
        var failure by mutableStateOf<String?>(null)
        val entry=BriefingEntry(Instant.parse("2026-11-26T21:30:00Z"),listOf("Saved before Perth rollover."),emptyList())
        val saved=Briefing("w","o","a",mapOf(yesterday to entry),authorName="Synthetic author")
        compose.setContent {AmbulandoTheme {Column(Modifier.verticalScroll(rememberScrollState())) {PersonalDailyBriefing(saved,true,failure,day,today,false,{day=it},{},{})}}}
        compose.onNodeWithText("Saved before Perth rollover.").assertExists()
        compose.runOnIdle {today=Briefing.today(Instant.parse("2026-11-27T16:00:00Z"));day=today}
        compose.onNodeWithTag("home.briefing.day").assertTextEquals("Today, 28 Nov 2026")
        compose.onNodeWithTag("home.briefing.empty").assertExists()
        compose.onNodeWithTag("home.briefing.stale").performScrollTo().performClick()
        compose.onNodeWithTag("home.briefing.day").assertTextEquals("27 Nov 2026")
        compose.onNodeWithText("Saved before Perth rollover.").assertExists()
        compose.runOnIdle {failure="Access verification unavailable."}
        compose.onNodeWithTag("home.briefing.unavailable").assertExists()
        compose.onAllNodesWithTag("home.briefing.paragraph").assertCountEquals(0)
    }
}
