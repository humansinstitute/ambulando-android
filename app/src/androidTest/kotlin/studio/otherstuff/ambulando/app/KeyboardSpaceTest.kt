package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Measures the actual composer against Android's IME boundary in both Deck and Chat routes. */
@RunWith(AndroidJUnit4::class)
class KeyboardSpaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun composerMeetsKeyboardAndGrowsWithText() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.section = AppSection.DECK }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.page.inbox").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.inbox").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.inbox.search").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.inbox.search").performTextReplacement("Native chat review")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.inbox.item").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("deck.inbox.item")[0].performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            fun capture(name: String) { shell("screencap -p /sdcard/Download/keyboard-space-$name.png") }
            fun assertKeyboardGap(name: String) {
                compose.waitUntil(10_000) {
                    var visible = false
                    scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    visible
                }
                // Wait for the platform animation, then compare window-coordinate geometry.
                Thread.sleep(700)
                compose.waitForIdle()
                var boundary = 0
                var density = 1f
                scenario.onActivity {
                    val decor = it.window.decorView
                    boundary = decor.height - ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    density = it.resources.displayMetrics.density
                }
                val bottom = compose.onNodeWithTag("chat.composer.area").fetchSemanticsNode().boundsInWindow.bottom
                val gap = boundary - bottom
                capture(name)
                println("KEYBOARD_SPACE $name boundary=$boundary composerBottom=$bottom gapPx=$gap density=$density")
                assertTrue("$name composer must meet IME (gap=$gap px)", gap >= -2 && gap <= 2 * density)
                compose.onNodeWithTag("chat.attach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("chat.mention").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("chat.send").assertIsDisplayed()
                compose.onNodeWithTag("section.bar").assertDoesNotExist()
            }
            compose.onNodeWithTag("chat.composer").performTextReplacement("")
            compose.onNodeWithTag("chat.composer").performClick()
            assertKeyboardGap("deck-empty")
            compose.onNodeWithTag("chat.composer.area").assertHeightIsEqualTo(64.dp)
            compose.onNodeWithTag("chat.composer").performTextReplacement("Short reply")
            assertKeyboardGap("deck-short")
            val shortHeight = compose.onNodeWithTag("chat.composer").fetchSemanticsNode().size.height
            compose.onNodeWithTag("chat.composer").performTextReplacement((1..6).joinToString("\n") { "Line $it" })
            assertKeyboardGap("deck-multiline")
            assertTrue(compose.onNodeWithTag("chat.composer").fetchSemanticsNode().size.height > shortHeight)
            Espresso.closeSoftKeyboard()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("section.bar").fetchSemanticsNodes().isNotEmpty() }
            capture("deck-hidden")
            compose.onNodeWithTag("chat.composer").performClick()
            assertKeyboardGap("deck-reopened")
            Espresso.closeSoftKeyboard()
            compose.runOnIdle {
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)
                model.section = AppSection.CHAT
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").performTextReplacement("")
            compose.onNodeWithTag("chat.composer").performClick()
            assertKeyboardGap("chat-empty")
            compose.onNodeWithTag("chat.composer.area").assertHeightIsEqualTo(64.dp)
            compose.onNodeWithTag("chat.mention").performClick()
            compose.onNodeWithTag("chat.composer").assertTextContains("@", substring = true)
            compose.onNodeWithTag("chat.attach").performClick()
            compose.onNodeWithTag("chat.attach.pick").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("chat.composer").performTextReplacement("Keyboard regression reply")
            compose.onNodeWithTag("chat.send").assertIsEnabled().performClick()
            compose.waitUntil(20_000) { compose.onNodeWithTag("chat.composer").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty() }
            assertKeyboardGap("chat-sent")
        }
    }

    /** Deck (+) → New thread: the route that has no chat route of its own, so the shell's bar once stayed under the keyboard. */
    @Test fun newThreadComposerMeetsKeyboardAndAsksForNoKnownChannel() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            fun capture(name: String) { shell("screencap -p /sdcard/Download/keyboard-space-$name.png") }
            fun assertGap(name: String) {
                compose.waitUntil(10_000) {
                    var visible = false
                    scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    visible
                }
                Thread.sleep(700)
                compose.waitForIdle()
                var boundary = 0
                var density = 1f
                scenario.onActivity {
                    val decor = it.window.decorView
                    boundary = decor.height - ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    density = it.resources.displayMetrics.density
                }
                val gap = boundary - compose.onNodeWithTag("chat.composer.area").fetchSemanticsNode().boundsInWindow.bottom
                capture(name)
                println("KEYBOARD_SPACE $name gapPx=$gap density=$density")
                assertTrue("$name composer must meet IME (gap=$gap px)", gap >= -2 && gap <= 2 * density)
                compose.onNodeWithTag("section.bar").assertDoesNotExist()
            }
            fun openNewThread(channelId: String?) {
                compose.runOnIdle {
                    if (channelId != null) model.selectContext(SampleIds.SCOPE_AMBULANDO, channelId) else model.selectContext(null, null)
                    model.section = AppSection.DECK
                }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.fab.new").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("deck.fab.new").performClick()
            }
            // A known channel: shown as the destination, no selector, composer meets the keyboard (empty, multiline, reopened).
            openNewThread(SampleIds.GENERAL)
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            capture("newthread-open")
            compose.onNodeWithTag("deck.compose.destination", useUnmergedTree = true).assertTextContains("in #", substring = true)
            compose.onNodeWithTag("deck.compose.channel", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("chat.composer").performTextReplacement("")
            compose.onNodeWithTag("chat.composer").performClick()
            assertGap("newthread-empty")
            compose.onNodeWithTag("chat.composer.area").assertHeightIsEqualTo(64.dp)
            compose.onNodeWithTag("chat.attach").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("chat.mention").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("chat.composer").performTextReplacement((1..6).joinToString("\n") { "Line $it" })
            assertGap("newthread-multiline")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("chat.composer").performClick()
            assertGap("newthread-reopened")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("chat.composer").performTextReplacement("")
            compose.onNodeWithTag("deck.compose.cancel").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("section.bar").fetchSemanticsNodes().isNotEmpty() }
            // No channel known (global Deck with several channels): the destination is genuinely a choice and stays available.
            openNewThread(null)
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.compose.channel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.compose.destination", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("deck.compose.option.${SampleIds.GENERAL}").assertIsDisplayed().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.compose.channel", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("chat.composer").performClick()
            assertGap("newthread-global")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("deck.compose.cancel").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("section.bar").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
