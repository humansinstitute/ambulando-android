package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Dedicated synthetic emulator fixture; does not access a member account. */
@RunWith(AndroidJUnit4::class)
class ChannelArtefactsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun channelListFindsAndOpensSharedWhiteboardWithoutCanvas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.section = AppSection.DOCS }
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.filter.excalidraw")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.filter.excalidraw").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.row")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("docs.filter").performTextInput("Architecture")
            compose.onNodeWithTag("artefacts.row").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.title")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.title").assertTextContains("Architecture", substring = true)
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("board.version")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.reload").assertIsDisplayed()
        }
    }
    @Test fun htmlCanBeCreatedEditedSavedAndBrowsedFromChannelArtefacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.DOCS }
            compose.waitUntil(20_000) { model.context.channelId == SampleIds.GENERAL }
            compose.onNodeWithTag("artefacts.new").performClick()
            val name = "HTML runtime " + System.currentTimeMillis()
            compose.onNodeWithTag("artefacts.title").performTextInput(name)
            compose.onNode(hasText("HTML") and hasAnyAncestor(isDialog())).performClick()
            compose.onNode(hasText("general", substring = true) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithTag("artefacts.create").assertIsEnabled().performClick()
            compose.onNodeWithTag("docs.filter").performTextReplacement("")
            compose.onNodeWithTag("artefacts.filter.html").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("$name.html")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTag("artefacts.row") and hasAnyDescendant(hasText("$name.html")), useUnmergedTree = true).performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.editSource")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.editSource").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("artefacts.source")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.source").performTextReplacement("<h1>Saved on Android</h1>")
            compose.onNodeWithTag("artefacts.saveSource").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.source")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("artefacts.history").performClick()
            compose.onNodeWithText("v2").assertIsDisplayed()
            compose.onNodeWithText("v1").performClick()
            compose.onNodeWithTag("artefacts.editSource").assertTextContains("Restore source")
        }
    }

}
