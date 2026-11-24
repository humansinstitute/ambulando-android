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

    @Test fun sectionBarSelectsAndReselects() {
        val events = mutableListOf<String>()
        compose.setContent {
            AmbulandoTheme {
                SectionBar(AppSection.visible(showAgents = false, showContext = false), AppSection.CHAT,
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

}
