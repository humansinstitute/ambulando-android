package studio.otherstuff.ambulando.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/** The goal form's keyboard contract (labels above, first field focused, Tab order, Enter, Escape, Ctrl+Enter). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class GoalFormUiTest {
    @get:Rule val compose = createComposeRule()
    private var saved: List<String?>? = null
    private var dismissed = 0

    private fun open() {
        compose.setContent { AmbulandoTheme { GoalEditorDialog(null, onDismiss = { dismissed++ }) { n, d, s, t -> saved = listOf(n, d, s, t) } } }
        compose.waitUntil(3_000) { runCatching { compose.onNodeWithTag("goal.input.name").assertIsFocused() }.isSuccess }
    }

    @Test fun labelsAboveAndSaveDisabledWithReasonUntilValid() {
        open()
        compose.onNodeWithTag("goal.input.name.label").assertTextEquals("Name")
        compose.onNodeWithTag("goal.input.description.label").assertTextEquals("Description")
        val label = compose.onNodeWithTag("goal.input.name.label").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("goal.input.name").fetchSemanticsNode().boundsInRoot
        assertTrue("label sits above its field", label.bottom <= field.top)
        assertEquals("label and text share the leading edge", label.left, field.left, 24f)
        compose.onNodeWithTag("goal.input.save").assertIsNotEnabled()
        compose.onNodeWithTag("goal.input.issue").assertTextEquals("Add a name.")
        compose.onNodeWithTag("goal.input.name").performTextInput("Ship")
        compose.onNodeWithTag("goal.input.issue").assertTextEquals("Add a description.")
        compose.onNodeWithTag("goal.input.description").performTextInput("Use it")
        compose.onAllNodesWithTag("goal.input.issue").assertCountEquals(0)
        compose.onNodeWithTag("goal.input.save").assertIsEnabled()
    }

    @Test fun enterMovesFromNameAndStartsNewLineInDescription() {
        open()
        compose.onNodeWithTag("goal.input.name").performTextInput("Ship")
        compose.onNodeWithTag("goal.input.name").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("goal.input.description").assertIsFocused()
        compose.onNodeWithTag("goal.input.name").assertTextContains("Ship")
        compose.onNodeWithTag("goal.input.description").performTextInput("Line one")
        compose.onNodeWithTag("goal.input.description").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("goal.input.description").performTextInput("Line two")
        assertTrue(compose.onNodeWithTag("goal.input.description").fetchSemanticsNode().config.toString().contains("Line one\nLine two"))
        assertNull("plain Enter never saves", saved)
    }

    @Test fun tabAndShiftTabMoveBetweenFields() {
        open()
        compose.onNodeWithTag("goal.input.name").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("goal.input.description").assertIsFocused()
        compose.onNodeWithTag("goal.input.description").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("goal.input.start").assertIsFocused()
        compose.onNodeWithTag("goal.input.start").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        compose.onNodeWithTag("goal.input.description").assertIsFocused()
        assertFalse("Tab is not typed into the description", compose.onNodeWithTag("goal.input.description").fetchSemanticsNode().config.toString().contains("\t"))
    }

    @Test fun ctrlEnterSavesAndEscapeCancels() {
        open()
        compose.onNodeWithTag("goal.input.name").performTextInput("Ship")
        compose.onNodeWithTag("goal.input.description").performTextInput("Use it")
        compose.onNodeWithTag("goal.input.description").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        compose.waitUntil(3_000) { saved != null }
        assertEquals(listOf("Ship", "Use it", null, null), saved)
        compose.onNodeWithTag("goal.input.description").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, dismissed)
    }

    @Test fun targetBeforeStartIsShownBeforeSaving() {
        compose.setContent { AmbulandoTheme { GoalEditorDialog(studio.otherstuff.ambulando.core.goals.Goal("Ship", "Use it", "2026-12-18", "2026-11-26"), onDismiss = {}) { _, _, _, _ -> } } }
        compose.onNodeWithTag("goal.input.issue").assertTextEquals("The target date is before the start date.")
        compose.onNodeWithTag("goal.input.save").assertIsNotEnabled()
        compose.onNodeWithTag("goal.input.start.clear").performClick()
        compose.onAllNodesWithTag("goal.input.issue").assertCountEquals(0)
    }
}
