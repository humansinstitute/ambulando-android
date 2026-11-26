package studio.otherstuff.ambulando.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import org.robolectric.annotation.Config
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.app.ui.shell.SectionBar
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/** JVM Compose test (Robolectric): the pattern screen tests use without an emulator. */
@RunWith(RobolectricTestRunner::class)
class ShellChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatVisibilityOnlyChangesTheButtonAndRestoresOrder() {
        assertTrue(AppSection.CHAT in AppSection.visible(false, false))
        val showChat = mutableStateOf(false)
        val selected = AppSection.CHAT
        compose.setContent { AmbulandoTheme {
            SectionBar(AppSection.visible(false, false, showChat.value), selected, {}, {})
        } }
        compose.onNodeWithTag("section.chat").assertDoesNotExist()
        assertEquals(listOf(AppSection.DECK, AppSection.TASKS, AppSection.DOCS, AppSection.FILES, AppSection.TOOLBOX),
            AppSection.visible(false, false, showChat = false))
        compose.runOnIdle { showChat.value = true }
        compose.onNodeWithTag("section.chat").assertIsSelected()
        assertEquals(AppSection.entries.filter { it != AppSection.HOME }, AppSection.visible(true, true, true))
        compose.runOnIdle { showChat.value = false }
        compose.onNodeWithTag("section.chat").assertDoesNotExist()
        assertEquals(AppSection.CHAT, AppSection.fromKey("chat"))
    }

    @Test fun sectionBarSelectsAndReselects() {
        val events = mutableListOf<String>()
        compose.setContent {
            AmbulandoTheme {
                SectionBar(AppSection.visible(showAgents = false, showContext = false, showChat = true), AppSection.CHAT,
                    onSelect = { events += "select:${it.key}" }, onReselect = { events += "reselect:${it.key}" })
            }
        }
        compose.onNodeWithTag("section.deck").assertIsDisplayed().performClick()
        compose.onNodeWithTag("section.chat").performClick()
        assertEquals(listOf("select:deck", "reselect:chat"), events)
    }
    @Test @Config(qualifiers = "w1000dp-h800dp")
    fun wideControlsDoNotStretch() {
        compose.setContent { AmbulandoTheme {
            SectionBar(AppSection.visible(false, false), AppSection.DECK, {}, {}, homeSelected = true)
        } }
        val width = compose.onNodeWithTag("section.home").fetchSemanticsNode().boundsInRoot.width
        val strip = compose.onNodeWithTag("section.scroll").fetchSemanticsNode().boundsInRoot.width
        assertEquals(96f, width / compose.density.density, 1f)
        org.junit.Assert.assertTrue(strip > width * 7)
        compose.onNodeWithTag("section.home").assertIsSelected()
        compose.onNodeWithTag("section.toolbox").assertIsDisplayed()
    }

    @Test fun routeIdentityResetsButLivePresentationKeepsOffset() {
        val route = mutableStateOf("workspace1/scope1/tasks")
        val optional = mutableStateOf(false)
        compose.setContent { AmbulandoTheme { Box(Modifier.width(360.dp)) {
            SectionBar(AppSection.visible(optional.value, optional.value), AppSection.TASKS, {}, {},
                navigationIdentity = route.value)
        } } }
        fun offset() = compose.onNodeWithTag("section.scroll").fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        compose.onNodeWithTag("section.scroll").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val before = offset()
        org.junit.Assert.assertTrue(before > 0f)
        compose.runOnIdle { optional.value = true }
        compose.waitForIdle()
        assertEquals(before, offset(), 1f)
        compose.runOnIdle { optional.value = false }
        compose.waitForIdle()
        assertEquals(before, offset(), 1f)
        compose.runOnIdle { route.value = "workspace2/scope1/tasks" }
        compose.waitForIdle()
        assertEquals(0f, offset(), 1f)
        compose.onNodeWithTag("section.home").assertIsDisplayed()
    }
    @Test fun homeSupportsKeyboardActivation() {
        var homes = 0
        compose.setContent { AmbulandoTheme {
            SectionBar(AppSection.visible(false, false), AppSection.TASKS, {}, {}, onHome = { homes++ })
        } }
        compose.onNodeWithTag("section.home").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("section.home").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        assertEquals(1, homes)
    }

    @Test fun narrowControlsKeepOriginalMinimumWidthAndIconSize() {
        compose.setContent { AmbulandoTheme { Box(Modifier.width(300.dp)) {
            SectionBar(AppSection.visible(true, true), AppSection.TASKS, {}, {})
        } } }
        val density = compose.density.density
        val home = compose.onNodeWithTag("section.home").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("section.home.icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("section.home.label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(72f, home.width / density, 1f)
        org.junit.Assert.assertTrue(home.height / density >= 56f)
        assertEquals(24f, icon.width / density, 1f)
        assertEquals(24f, icon.height / density, 1f)
        org.junit.Assert.assertTrue(icon.bottom <= label.top)
        assertEquals(icon.center.x, label.center.x, 1f)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun largeTextRemainsStackedAndEveryDestinationIsReachable() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f)
            ) { AmbulandoTheme { Box(Modifier.width(320.dp)) {
                SectionBar(AppSection.visible(true, true), AppSection.TASKS, {}, {})
            } } }
        }
        for (key in listOf("home") + AppSection.visible(true, true).map { it.key }) {
            val tag = "section.$key"
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val button = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val icon = compose.onNodeWithTag("$tag.icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithTag("$tag.label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(button.height >= 56f && button.width in 72f..96f)
            org.junit.Assert.assertTrue(icon.bottom <= label.top)
            assertEquals(icon.center.x, label.center.x, 1f)
            compose.onNodeWithTag("$tag.label", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    action(results)
                    org.junit.Assert.assertTrue(results.isNotEmpty())
                    // Text metrics are fractional, while measured bounds round to whole pixels.
                    results.forEach {
                        org.junit.Assert.assertTrue(it.lineCount == 1)
                        // Original fixed-width labels may ellipsize at accessibility text sizes.
                        org.junit.Assert.assertTrue(label.left >= button.left && label.right <= button.right)
                    }
                }
        }
    }

}
