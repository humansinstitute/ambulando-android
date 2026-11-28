package studio.otherstuff.ambulando.app

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.tasks.AssigneeTypeahead
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.profiles.*
import studio.otherstuff.ambulando.core.transport.MemberRecord

@RunWith(RobolectricTestRunner::class)
class AssigneeTypeaheadTest {
    @get:Rule val compose = createComposeRule()
    private val members = listOf(MemberRecord("w", "a", "alice", "human", "Alice"), MemberRecord("w", "b", "bot", "agent", "Helper"))
    private var selected by mutableStateOf<String?>("alice")
    private var calls = 0
    private var accepted = true
    private fun show() {
        compose.setContent { AmbulandoTheme { AssigneeTypeahead(selected, members, null, true) { calls++; if (accepted) selected = it; accepted } } }
        compose.onNodeWithTag("task.assignee").assertContentDescriptionEquals("Assignee: Alice").performClick()
    }
    @Test fun searchAgentSelectCancelThenSaveAndUnassign() {
        show()
        compose.onNodeWithTag("task.assignee.search").performTextInput("Help")
        compose.onNodeWithTag("task.assignee.option.alice").assertDoesNotExist()
        compose.onNodeWithTag("task.assignee.option.bot").performClick()
        compose.onNodeWithTag("task.assignee.cancel").performClick()
        assertEquals("alice", selected); assertEquals(0, calls)
        compose.onNodeWithTag("task.assignee").performClick()
        compose.onNodeWithTag("task.assignee.search").performTextInput("Help")
        compose.onNodeWithTag("task.assignee.option.bot").performClick()
        compose.onNodeWithTag("task.assignee.save").performClick()
        assertEquals("bot", selected)
        compose.onNodeWithTag("task.assignee").assertContentDescriptionEquals("Assignee: Helper").performClick()
        compose.onNodeWithTag("task.assignee.option.unassigned").performClick()
        compose.onNodeWithTag("task.assignee.save").performClick()
        assertNull(selected)
    }
    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardSelectionEscapeAndFailedSaveStayVisible() {
        show()
        compose.onNodeWithTag("task.assignee.search").performTextInput("Help")
        compose.onNodeWithTag("task.assignee.search").performKeyInput { pressKey(Key.Enter) }
        accepted = false
        compose.onNodeWithTag("task.assignee.save").performClick()
        compose.onNodeWithText("Assignment could not be saved. Try again.").assertIsDisplayed()
        assertEquals("alice", selected)
        compose.onNodeWithTag("task.assignee.search").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("task.assignee.dialog").assertDoesNotExist()
    }
    @Test fun searchMatchesDisplayedProfileNameAndShowsAvatarInitials() {
        val people = PeopleDirectory(listOf(DirectoryMember("alice", "Alicia Profile")), emptyMap(), emptyList())
        compose.setContent { AmbulandoTheme { AssigneeTypeahead("alice", members, people, true) { true } } }
        compose.onNodeWithTag("task.assignee").assertContentDescriptionEquals("Assignee: Alicia Profile").performClick()
        compose.onNodeWithTag("task.assignee.search").performTextInput("Alicia Profile")
        compose.onNodeWithTag("task.assignee.option.alice").assertIsDisplayed()
        compose.onNodeWithTag("task.assignee.option.bot").assertDoesNotExist()
        compose.onAllNodesWithText("AP", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("Selected and option fallback avatars", it.size >= 2) }
    }

}
