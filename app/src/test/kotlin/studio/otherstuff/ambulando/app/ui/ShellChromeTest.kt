package studio.otherstuff.ambulando.app.ui

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
}
