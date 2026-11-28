package studio.otherstuff.ambulando.app

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.chat.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.store.MentionCandidate

@RunWith(RobolectricTestRunner::class)
class GoalMentionPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allGoalsAreScrollableAndPickCanonicalFile() {
        val candidates = (0 until 25).map { MentionCandidate(Mention(MentionType.FILE, "file-$it", "Goal $it"), "Personal goal") }
        var picked: Mention? = null
        compose.setContent { AmbulandoTheme { MentionLookahead(candidates, { picked = it }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Goal 24"))
        compose.onNodeWithText("Goal 24").performClick()
        compose.runOnIdle { assertEquals("@[Goal 24](mention:file:file-24)", picked?.token) }
    }

    @Test fun boundaryAndQueryChangeImmediatelyHideOldResultsAndFailureIsVisible() {
        val query = mutableStateOf("first"); val boundary = mutableStateOf("one")
        compose.setContent {
            val result = rememberMentionLookup(query.value, boundary.value) {
                if (it == "broken") throw MentionLookupFailure(emptyList(), "Goals unavailable")
                delay(200)
                listOf(MentionCandidate(Mention(MentionType.FILE, boundary.value, it), "Personal goal"))
            }
            AmbulandoTheme { MentionLookahead(result.candidates, {}, status = if (result.loading) "Searching" else result.error) }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("first").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { query.value = "second"; boundary.value = "two" }
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodesWithText("second").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { query.value = "broken" }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Goals unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("second").assertDoesNotExist()
        compose.onNodeWithText("No matches").assertDoesNotExist()
    }
    @Test fun localRowsAreSelectableDuringDiscoveryAndCancelledQueriesCannotRestoreThem() {
        val query=mutableStateOf<String?>("one");val boundary=mutableStateOf("workspace-one")
        var picked:Mention?=null
        val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.setContent {
            val result=rememberMentionLookup(query.value,boundary.value,
                localLookup={listOf(MentionCandidate(Mention(MentionType.AGENT,boundary.value,it),"Agent"))}) {
                release.await();throw IllegalStateException("Offline enrichment")
            }
            AmbulandoTheme{MentionLookahead(result.candidates,{picked=it},status=if(result.loading)"Searching" else result.error)}
        }
        compose.waitUntil(1000){compose.onAllNodesWithText("one").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("one").performClick()
        compose.runOnIdle{assertEquals("@[one](mention:agent:workspace-one)",picked?.token);query.value="two";boundary.value="workspace-two"}
        compose.onNodeWithText("one").assertDoesNotExist()
        compose.waitUntil(1000){compose.onAllNodesWithText("two").fetchSemanticsNodes().isNotEmpty()}
        release.complete(Unit)
        compose.waitUntil(5000){compose.onAllNodesWithText("Mentions unavailable. Try again.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("two").assertIsDisplayed()
        compose.onNodeWithText("one").assertDoesNotExist()
        compose.runOnIdle{query.value=null}
        compose.onNodeWithText("two").assertDoesNotExist()
    }

}
