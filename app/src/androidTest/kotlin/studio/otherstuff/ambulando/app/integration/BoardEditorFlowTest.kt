package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.surfaces.BoardReference

/** Dedicated emulator only: the synthetic sample board, never a real Tower. */
@RunWith(AndroidJUnit4::class)
class BoardEditorFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun theBoardWindowShowsStatusVersionsAndTheRenameAndSaveNoteControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.editorBoard = BoardReference(SampleIds.BOARD_FILE, SampleIds.BOARD_VERSION, 2, "", 0, null, canWrite = true) to "Architecture sketch" }
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.title")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.title").assertTextContains("Architecture sketch")
            // A clean, loaded board shows its version in the status pill.
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.status")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.status").assertTextContains("v1")
            // The gear menu lists the versions (loaded on open), Reload latest and Rename; Save with a note waits for a change.
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("board.version")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.saveNote").assertIsNotEnabled()
            compose.onNodeWithTag("board.reload").assertIsDisplayed()
            compose.onNodeWithTag("board.rename").performClick()
            compose.onNodeWithTag("board.note.field").performTextReplacement("Architecture, renamed")
            compose.onNodeWithTag("board.note.confirm").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("board.message")).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodes(hasTestTag("board.title") and hasText("Architecture, renamed")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodes(hasTestTag("board.title") and hasText("Architecture, renamed")).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("board.back").performClick()
            compose.waitUntil(20_000) { model.editorBoard == null }
        }
    }
}
