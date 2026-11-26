package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Real touch/scroll on a dedicated synthetic emulator; never a member identity. */
@RunWith(AndroidJUnit4::class)
class NavigationStripTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun offset(): Float = compose.onNodeWithTag("section.scroll").fetchSemanticsNode()
        .config[SemanticsProperties.HorizontalScrollAxisRange].value()

    @Test fun wholeStripHomeAndRouteVersusLiveUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.resources.configuration.screenWidthDp < 1000)
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.entries?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle {
                model.viewLocked = false
                model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL)
                model.section = AppSection.TASKS
            }
            compose.waitUntil(30_000) { model.context.channelId == SampleIds.GENERAL }
            compose.onNodeWithTag("section.home").assertIsNotSelected()
            val home = compose.onNodeWithTag("section.home").fetchSemanticsNode().boundsInRoot
            val strip = compose.onNodeWithTag("section.scroll").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertTrue(home.height >= 56f * density)
            assertEquals((strip.width / density / 4.5f).coerceIn(72f, 96f), home.width / density, 1f)
            compose.runOnIdle { model.viewLocked = true }
            compose.onNodeWithTag("section.home").performTouchInput { click() }
            compose.waitUntil(30_000) { model.context.isHome && model.section == AppSection.HOME }
            compose.onNodeWithTag("section.home").assertIsSelected()
            compose.runOnIdle { assertFalse(model.viewLocked); assertEquals(SampleIds.STUDIO, model.workspace?.id) }
            for (key in listOf("home") + AppSection.visible(false, false).map { it.key }) {
                val tag = "section.$key"
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                val icon = compose.onNodeWithTag("$tag.icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val label = compose.onNodeWithTag("$tag.label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(icon.bottom <= label.top)
                assertEquals(icon.center.x, label.center.x, 2f)
                compose.onNodeWithTag("$tag.label", useUnmergedTree = true)
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        action(results)
                        assertTrue(results.isNotEmpty() && results.all {
                            it.lineCount == 1 && it.layoutInput.style.fontSize.value == 11f &&
                                it.getLineRight(0) - it.getLineLeft(0) <= it.size.width + 1f
                        })
                    }
            }
            compose.onNodeWithTag("section.home").performScrollTo()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                context.getExternalFilesDir(null)!!.resolve("stacked-navigation.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            // Swipe must move Home offscreen without accidentally opening any section.
            compose.onNodeWithTag("section.scroll").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            val scrolled = offset()
            assertTrue(scrolled > 0f)
            compose.onNodeWithTag("section.home").assertIsNotDisplayed()
            compose.runOnIdle { assertEquals(AppSection.HOME, model.section); model.refresh() }
            compose.waitForIdle()
            assertEquals(scrolled, offset(), 1f)
            // Same-screen presentation updates do not reset; a real context change does.
            compose.runOnIdle { model.showAgents = true; model.showContext = true }
            compose.waitForIdle()
            assertEquals(scrolled, offset(), 1f)
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL) }
            compose.waitUntil(30_000) { model.context.channelId == SampleIds.GENERAL }
            compose.onNodeWithTag("section.home").assertIsDisplayed().assertIsNotSelected()
            assertEquals(0f, offset(), 1f)
            compose.onNodeWithTag("section.scroll").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("section.toolbox").performTouchInput { click() }
            compose.waitUntil(30_000) { model.section == AppSection.TOOLBOX }
            assertEquals(0f, offset(), 1f)
            compose.onNodeWithTag("section.home").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(30_000) { model.context.isHome && model.section == AppSection.HOME }
            compose.onNodeWithTag("section.scroll").performTouchInput { swipeLeft() }
            scenario.recreate()
            compose.onNodeWithTag("section.home").assertIsDisplayed()
            assertEquals(0f, offset(), 1f)
        }
    }
    @Test fun wideStripUsesFixedControlsBesideSidebar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.resources.configuration.screenWidthDp >= 1000)
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspace != null }
            compose.onNodeWithTag("tablet.sidebar").assertIsDisplayed()
            compose.onNodeWithTag("section.scroll").assertIsDisplayed()
            val home = compose.onNodeWithTag("section.home").fetchSemanticsNode().boundsInRoot
            val strip = compose.onNodeWithTag("section.scroll").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertEquals(96f, home.width / density, 1f)
            assertTrue(strip.width > home.width * 7)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                context.getExternalFilesDir(null)!!.resolve("stacked-navigation-wide.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithTag("section.home").performTouchInput { click() }
            compose.waitUntil(30_000) { app.activeModel?.context?.isHome == true && app.activeModel?.section == AppSection.HOME }
            compose.onNodeWithTag("section.home").assertIsSelected()
        }
    }

}
