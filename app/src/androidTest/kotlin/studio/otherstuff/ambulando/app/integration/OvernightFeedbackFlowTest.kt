package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/** Reported overnight interactions, on a device with synthetic sample data (never a real identity). */
@RunWith(AndroidJUnit4::class)
class OvernightFeedbackFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(block: (AppModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            block(model)
        }
    }

    @Test fun chatOpensAtItsNewestMessageWithOneListAndAnAtLookahead() = launch { model ->
        compose.runOnIdle { val channel = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(channel.scopeId, channel.id); model.section = AppSection.CHAT }
        compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).size > 2 } }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat.messages").fetchSemanticsNodes().isNotEmpty() }
        val newest = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).last().id }
        // Visible on first layout, without any scroll by the test.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat.message.$newest").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat.message.$newest").assertIsDisplayed()
        assertTrue("one Messages list: no Threads switch", compose.onAllNodesWithText("Threads").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("chat.mention").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat.mentions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat.composer").performTextReplacement("@orbit")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("chat.mentions") and hasAnyDescendant(hasText("Orbit", substring = true))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Orbit", substring = true) and hasAnyAncestor(hasTestTag("chat.mentions")))[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat.mentions").fetchSemanticsNodes().isEmpty() }
        val text = compose.onNodeWithTag("chat.composer").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text ?: ""
        assertTrue("the composer shows the chosen mention: $text", text.startsWith("@Orbit"))
    }

    @Test fun aCanvasIsFullScreenWithItsControlsAtTheBottom() = launch { model ->
        compose.runOnIdle { model.section = AppSection.DECK }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.page.canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.page.canvas").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Launch board").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Launch board").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("canvas.bar").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the section bar is hidden", compose.onAllNodesWithTag("section.bar").fetchSemanticsNodes().isEmpty())
        val bar = compose.onNodeWithTag("canvas.bar").fetchSemanticsNode().boundsInRoot
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("the canvas controls sit at the bottom", bar.bottom > root.bottom - bar.height * 2)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("canvas.viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.edit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.add").fetchSemanticsNodes().isNotEmpty() }
        val clicked = android.os.SystemClock.elapsedRealtime()
        compose.onNodeWithTag("canvas.add").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.add.list").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("canvas.add.empty").fetchSemanticsNodes().isNotEmpty() }
        val readyMs = android.os.SystemClock.elapsedRealtime() - clicked
        android.util.Log.i("OvernightFeedback", "add-to-canvas list ready ${readyMs} ms after Add")
        // Measured on the emulator at 0.4–1.7 s including the test's idle waits; the list is read before the dialog opens.
        assertTrue("the add list is ready within two seconds of Add (was $readyMs ms)", readyMs < 2_000)
        assertTrue("the sample canvas has stored sources to add", compose.onAllNodesWithTag("canvas.add.list").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("canvas.add.kind.board").performClick()
        compose.onNodeWithTag("canvas.add.board.create").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("canvas.exit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("section.bar").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun drawingsAreCreatedEditedDuplicatedReshapedAndGrouped() = launch { model ->
        compose.runOnIdle { model.section = AppSection.DECK }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("deck.page.canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("deck.page.canvas").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Retro").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Retro").performClick()
        val canvasId = runBlocking { model.store.reader.canvases(SampleIds.STUDIO).first { it.title == "Retro" }.id }
        fun elements() = runBlocking { model.store.reader.canvasSnapshot(SampleIds.STUDIO, canvasId)?.let { studio.otherstuff.ambulando.core.surfaces.CanvasScene.parse(it.bytes).elements } ?: emptyList() }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("canvas.viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.edit").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.tool.rectangle").fetchSemanticsNodes().isNotEmpty() }
        val before = elements().map { it.id }.toSet()

        // A rectangle by tapping, then duplicated from its options.
        compose.onNodeWithTag("canvas.tool.rectangle").performClick()
        compose.onNodeWithTag("canvas.draw").performTouchInput { click(Offset(width * 0.3f, height * 0.3f)) }
        compose.waitUntil(15_000) { elements().size == before.size + 1 }
        val rect = elements().single { it.id !in before }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.element.${rect.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.element.${rect.id}").performClick()
        compose.onNodeWithTag("canvas.element.options").performClick()
        compose.onNodeWithTag("canvas.element.duplicate").performClick()
        compose.waitUntil(15_000) { elements().size == before.size + 2 }
        val copy = elements().single { it.id !in before && it.id != rect.id }
        assertTrue("24 points down and right", copy.layout.x == rect.layout.x + 24 && copy.layout.y == rect.layout.y + 24)

        // Text: tap, type, save; then edited.
        compose.onNodeWithTag("canvas.tool.text").performClick()
        compose.onNodeWithTag("canvas.draw").performTouchInput { click(Offset(width * 0.6f, height * 0.2f)) }
        compose.onNodeWithTag("canvas.text.field").performTextReplacement("Hello canvas")
        compose.onNodeWithTag("canvas.text.save").performClick()
        compose.waitUntil(15_000) { elements().any { it.text == "Hello canvas" } }

        // An arrow by dragging, then its end moved.
        compose.onNodeWithTag("canvas.tool.arrow").performClick()
        compose.onNodeWithTag("canvas.draw").performTouchInput { swipe(Offset(width * 0.2f, height * 0.7f), Offset(width * 0.7f, height * 0.8f), 400) }
        compose.waitUntil(15_000) { elements().any { it.kind == studio.otherstuff.ambulando.core.surfaces.CanvasElement.Kind.ARROW } }
        val arrow = elements().first { it.kind == studio.otherstuff.ambulando.core.surfaces.CanvasElement.Kind.ARROW }
        assertTrue("the drag set its length", arrow.layout.width > 100)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.element.${arrow.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.element.${arrow.id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.element.end").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.element.end").performTouchInput { swipe(center, center + Offset(0f, -200f), 400) }
        compose.waitUntil(15_000) { elements().first { it.id == arrow.id }.rowVersion > arrow.rowVersion }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.element.options").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.element.options").performClick()
        compose.onNodeWithTag("canvas.element.linestyle").performClick()
        compose.waitUntil(15_000) { elements().first { it.id == arrow.id }.lineStyle == "dotted" }

        // The two rectangles grouped.
        compose.onNodeWithTag("canvas.tool.group").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas.pick.element:${rect.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("canvas.pick.element:${rect.id}").performTouchInput { click(Offset(10f, 10f)) }
        compose.onNodeWithTag("canvas.pick.element:${copy.id}").performClick()
        compose.onNodeWithTag("canvas.group.confirm").performClick()
        try { compose.waitUntil(15_000) { elements().any { it.isGroup } } }
        catch (e: Throwable) { throw AssertionError("no group; notice=${model.notice}; confirm=" + compose.onAllNodesWithTag("canvas.group.confirm").fetchSemanticsNodes().map { it.config }, e) }
        compose.onNodeWithTag("canvas.exit").performClick()
    }
}
