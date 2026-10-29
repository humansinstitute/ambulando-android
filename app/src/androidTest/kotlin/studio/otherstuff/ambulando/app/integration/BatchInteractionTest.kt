package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }); model.section = AppSection.DECK }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.page.pinned")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.pinned").performClick()
            val c = model.connection!!
            val initial = runBlocking { model.store.reader.pinnedItems(c.id, SampleIds.STUDIO) }
            assertTrue(initial.size >= 2)
            val target = initial[1].pin.resourceId
            compose.onNodeWithTag("pin.drag.$target").performTouchInput { swipe(center, center + Offset(0f, -180f), 800) }
            compose.waitUntil(20_000) { runBlocking { model.store.reader.pinnedItems(c.id, SampleIds.STUDIO).firstOrNull()?.pin?.resourceId == target && model.store.reader.workspaceWrites(c.id, SampleIds.STUDIO).isEmpty() } }
            compose.onNodeWithTag("deck.page.pinned").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithTag("deck.page.pinned").performClick()
            // A physical keyboard event leaves Android touch mode before a button may receive focus.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("pin.down.$target").assertIsEnabled()
            compose.onNodeWithTag("pin.down.$target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("pin.down.$target").assertIsFocused()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(20_000) { runBlocking { model.store.reader.pinnedItems(c.id, SampleIds.STUDIO).firstOrNull()?.pin?.resourceId == initial[0].pin.resourceId && model.store.reader.workspaceWrites(c.id, SampleIds.STUDIO).isEmpty() } }
        }
    }
}
