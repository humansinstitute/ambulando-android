package studio.otherstuff.ambulando.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.app.ui.shell.SectionStacks
import studio.otherstuff.ambulando.app.ui.shell.ShellActions
import studio.otherstuff.ambulando.app.ui.shell.ShellChromeState
import studio.otherstuff.ambulando.app.ui.shell.WorkspaceShell
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/**
 * The shell's adaptive navigation: a wide window keeps the sections and context beside the content (the iPad's rule) with the menu
 * button folding it away, a narrow one keeps the bottom bar and the drawer, and resizing between them keeps what a section holds.
 */
@RunWith(AndroidJUnit4::class)
class TabletShellTest {
    @get:Rule val compose = createComposeRule()

    private val sections = listOf(AppSection.DECK, AppSection.CHAT, AppSection.TASKS)
    private var current by mutableStateOf(AppSection.CHAT)
    private var drawerOpen by mutableStateOf(false)
    private var width by mutableStateOf(900.dp)
    private val actions = object : ShellActions {
        override fun select(section: AppSection) { current = section }
        override fun reselect(section: AppSection) {}
        override fun openSidebar() { drawerOpen = true }
        override fun closeSidebar() { drawerOpen = false }
        override fun chooseHome() {}
        override fun toggleViewLock() {}
        override fun search() {}
        override fun notifications() {}
        override fun showBrowser() {}
        override fun settings() {}
        override fun unlock() {}
    }

    private fun chrome() = ShellChromeState(current, sections, "Studio", "Scope", "general", false, false, false, 0, null, false, false, drawerOpen)

    private fun show() = compose.setContent {
        AmbulandoTheme {
            // The host window is phone-sized: the configuration the shell reads says how wide the window under test is.
            val configuration = android.content.res.Configuration(LocalConfiguration.current).apply { screenWidthDp = width.value.toInt() }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                WorkspaceShell(chrome(), actions, remember { SectionStacks() }, sidebar = { Text("Context content", Modifier.testTag("sidebar.content")) },
                    sectionRoot = { section -> Text("Body ${section.key}", Modifier.testTag("body.${section.key}")) }, routeScreen = { _, _: WorkRoute -> })
            }
        }
    }

    @Test fun aWideWindowShowsTheSectionsAndContextBesideTheContentWithoutABottomBar() {
        show()
        compose.onNodeWithTag("tablet.sidebar").assertIsDisplayed()
        compose.onNodeWithTag("sidebar.content").assertIsDisplayed()
        sections.forEach { compose.onNodeWithTag("section.${it.key}").assertIsDisplayed() }
        // Choosing a section in the sidebar switches the body.
        compose.onNodeWithTag("section.tasks").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("body.tasks").assertIsDisplayed()
        assertEquals(AppSection.TASKS, current)
        // The menu button folds the sidebar away (the bottom bar returns) and brings it back; the drawer is never opened.
        compose.onAllNodesWithTag("sidebar.open")[0].performClick()
        compose.onNodeWithTag("tablet.sidebar").assertDoesNotExist()
        compose.onNodeWithTag("section.bar").assertIsDisplayed()
        compose.onAllNodesWithTag("sidebar.open")[0].performClick()
        compose.onNodeWithTag("tablet.sidebar").assertIsDisplayed()
        assertEquals(false, drawerOpen)
    }

    @Test fun aNarrowWindowKeepsTheBottomBarAndTheDrawer() {
        width = 400.dp
        show()
        compose.onNodeWithTag("tablet.sidebar").assertDoesNotExist()
        compose.onNodeWithTag("section.bar").assertIsDisplayed()
        compose.onNodeWithTag("sidebar.content").assertDoesNotExist()
        compose.onAllNodesWithTag("sidebar.open")[0].performClick()
        assertEquals(true, drawerOpen)
        compose.waitForIdle()
        compose.onNodeWithTag("sidebar.content").assertIsDisplayed()
    }

    @Test fun resizingAcrossTheWidthRuleSwapsTheNavigationAndKeepsTheOpenSection() {
        width = 400.dp
        show()
        compose.onNodeWithTag("body.chat").assertIsDisplayed()
        width = 900.dp
        compose.waitForIdle()
        compose.onNodeWithTag("tablet.sidebar").assertIsDisplayed()
        compose.onNodeWithTag("body.chat").assertIsDisplayed()
        width = 400.dp
        compose.waitForIdle()
        compose.onNodeWithTag("tablet.sidebar").assertDoesNotExist()
        compose.onNodeWithTag("body.chat").assertIsDisplayed()
    }
}
