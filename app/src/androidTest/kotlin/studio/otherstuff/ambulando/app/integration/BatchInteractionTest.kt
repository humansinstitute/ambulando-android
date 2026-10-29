package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import kotlinx.serialization.json.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/** Runs only on a dedicated synthetic test target. Exercises actual Compose touch targets and durable server writes. */
@RunWith(AndroidJUnit4::class)
class BatchInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun composerBlankAreaKeepsDraftAndButtonsAndToolbarOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { val channel = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(channel.scopeId, channel.id); model.section = AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val composer = compose.onNodeWithTag("chat.composer")
            composer.performTextReplacement("Retained batch draft")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("chat.composer.area").performTouchInput { click(Offset(width - 2f, 2f)) }
            composer.assertIsFocused().assertTextContains("Retained batch draft")
            compose.onNodeWithTag("chat.attach").performClick()
            compose.onNodeWithTag("chat.attach.pick").assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            composer.assertTextContains("Retained batch draft")
            val positions = listOf("search", "notifications.open", "pin.toggle", "browser.open", "settings").map { tag -> compose.onAllNodesWithTag(tag).fetchSemanticsNodes().filter { it.boundsInRoot.top > 0 }.minBy { it.boundsInRoot.top }.boundsInRoot.left }
            assertTrue(positions.toString(), positions.zipWithNext().all { (a,b) -> a < b })
        }
    }

    @Test fun dragMixedPinsPersistsWithoutOpeningAndKeyboardMoveRestores() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }); model.section = AppSection.DECK }
            val c = model.connection!!
            compose.onNodeWithTag("deck.page.pinned").performClick()
            // Recover only a prior failed synthetic move, through its actual failure affordance.
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Discard move").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Discard move").performClick()
            }
            fun rows() = runBlocking { model.store.reader.pinnedItems(c.id, SampleIds.STUDIO) }
            fun waitSaved(expected: List<String>) {
                try {
                    compose.waitUntil(20_000) { rows().map { it.pin.resourceId } == expected && runBlocking { model.store.reader.workspaceWrites(c.id, SampleIds.STUDIO).isEmpty() } }
                } catch (failure: Throwable) {
                    throw AssertionError("Expected $expected; actual ${rows().map { it.pin.resourceId }}; writes ${runBlocking { model.store.reader.workspaceWrites(c.id, SampleIds.STUDIO) }}", failure)
                }
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithTag("pin.drag.${expected.first()}").fetchSemanticsNodes().firstOrNull()
                        ?.config?.getOrNull(SemanticsActions.CustomActions)?.isNotEmpty() == true
                }
                compose.waitForIdle()
            }
            if (rows().none { it.pin.resourceId == SampleIds.CANVAS_TASK }) {
                compose.runOnIdle { model.submit(WriteIntent(WriteKind.PIN_SET, SampleIds.CANVAS_TASK, buildJsonObject {
                    put("resource_type", "task"); put("pinned", true)
                    put("expected_row_version", runBlocking { model.store.reader.pin(c.id, SampleIds.STUDIO, studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.TASK, SampleIds.CANVAS_TASK) }?.rowVersion ?: 0)
                    put("mutation_id", java.util.UUID.randomUUID().toString())
                })) }
            }
            compose.waitUntil(20_000) { rows().size >= 3 && runBlocking { model.store.reader.workspaceWrites(c.id, SampleIds.STUDIO).isEmpty() } }
            compose.onNodeWithTag("deck.page.pinned").performClick()
            val initial = rows().map { it.pin.resourceId }
            val target = initial.last()
            val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "android-build12-narrow"
            compose.waitForIdle()
            captureTextReview("$prefix-idle")
            // No permanent arrange UI. Moves are custom actions on the same card that opens on a short tap.
            compose.onAllNodesWithContentDescription("Move up").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("Move down").assertCountEquals(0)
            compose.onAllNodesWithText("Move to end").assertCountEquals(0)
            val actions = compose.onNodeWithTag("pin.drag.$target").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals(listOf("Move up", "Move down", "Move to start", "Move to end"), actions.map { it.label })
            fun dragTo(id: String, destination: Offset) {
                val node = compose.onNodeWithTag("pin.drag.$id")
                val origin = node.fetchSemanticsNode().boundsInRoot.topLeft
                node.performTouchInput {
                    down(center)
                    advanceEventTime(700)
                    moveTo(center) // activate long press before moving
                    moveTo(destination - origin, 600)
                }
                compose.waitForIdle()
                captureTextReview("$prefix-active-drag")
                node.performTouchInput { up() }
                compose.waitForIdle()
                captureTextReview("$prefix-drop")
            }
            // Last pin before first; then after the middle pin; finally to the end via the last row's lower half.
            val firstBounds = compose.onNodeWithTag("pin.drag.${initial.first()}").fetchSemanticsNode().boundsInRoot
            dragTo(target, Offset(firstBounds.center.x, firstBounds.top + 2f))
            val before = listOf(target) + initial.dropLast(1)
            waitSaved(before)
            compose.onNodeWithTag("deck.page.pinned").assertIsDisplayed()
            compose.onAllNodesWithTag("task.title").assertCountEquals(0)
            val middleBounds = compose.onNodeWithTag("pin.drag.${before[1]}").fetchSemanticsNode().boundsInRoot
            dragTo(target, Offset(middleBounds.center.x, middleBounds.bottom - 2f))
            val after = listOf(before[1], target) + before.drop(2)
            waitSaved(after)
            val lastBounds = compose.onNodeWithTag("pin.drag.${after.last()}").fetchSemanticsNode().boundsInRoot
            dragTo(target, Offset(lastBounds.center.x, lastBounds.bottom - 2f))
            val ended = after.filter { it != target } + target
            waitSaved(ended)
            captureTextReview("$prefix-after-drag")
            scenario.recreate()
            compose.onNodeWithTag("deck.page.pinned").performClick()
            waitSaved(ended)
            captureTextReview("$prefix-reloaded")
            // Physical keyboard moves without a visible button, using the focused opening target.
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("pin.drag.$target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("pin.drag.$target").assertIsFocused()
            compose.onNodeWithTag("pin.drag.$target").performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionUp); keyUp(Key.AltLeft) }
            val keyboard = ended.toMutableList().apply { add(size - 2, removeAt(lastIndex)) }
            waitSaved(keyboard)
            // Accessibility move-to-end uses the same target/anchor path.
            val moveToEnd = compose.onNodeWithTag("pin.drag.$target").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move to end" }
            compose.runOnIdle { assertTrue(moveToEnd.action()) }
            waitSaved(ended)
            // A short touch opens independently of hold drag.
            compose.onNodeWithTag("pin.drag.${SampleIds.CANVAS_TASK}").performTouchInput { click() }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nav.back").performClick()
            compose.onNodeWithTag("section.deck").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("deck.page.pinned").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.pinned").performClick()
            val task = rows().first { it.pin.resourceId == SampleIds.CANVAS_TASK }
            compose.onNodeWithContentDescription("Unpin ${task.title}").performTouchInput { click() }
            waitSaved(ended.filter { it != SampleIds.CANVAS_TASK })
            compose.onNodeWithTag("pin.drag.${SampleIds.CANVAS_TASK}").assertDoesNotExist()
            compose.onAllNodesWithTag("task.title").assertCountEquals(0)
        }
    }
}
