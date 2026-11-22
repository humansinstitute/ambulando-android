package studio.otherstuff.ambulando.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/** The real section host, using an editor stand-in so no Chromium or network is needed. */
@RunWith(RobolectricTestRunner::class)
class FocusedEditorNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun filesAndArtefactsEditorsOwnChromeBackAndRestoreListState() {
        val stacks = SectionStacks()
        var section by mutableStateOf(AppSection.FILES)
        val closed = mutableListOf<String>()
        val actions = object : ShellActions {
            override fun select(section: AppSection) {}
            override fun reselect(section: AppSection) {}
            override fun openSidebar() {}
            override fun closeSidebar() {}
            override fun chooseHome() {}
            override fun toggleViewLock() {}
            override fun search() {}
            override fun notifications() {}
            override fun showBrowser() {}
            override fun settings() {}
            override fun unlock() {}
        }
        compose.setContent {
            AmbulandoTheme {
                WorkspaceShell(
                    ShellChromeState(section, listOf(AppSection.FILES, AppSection.DOCS), "Workspace", null, null,
                        true, false, false, 0, null, false, false, false), actions, stacks, sidebar = {},
                    sectionRoot = {
                        var filter by rememberSaveable { mutableStateOf("") }
                        Column {
                            TextField(filter, { filter = it }, Modifier.testTag("filter"))
                            Button(onClick = { stacks.push(section, WorkRoute.File("w", "board")) }) { Text("Open board") }
                            Button(onClick = { stacks.push(section, WorkRoute.File("w", "other")) }) { Text("Open other file") }
                            LazyColumn(Modifier.weight(1f).testTag("list")) {
                                items((0..99).toList()) { index -> Text("Row $index", Modifier.height(androidx.compose.ui.unit.Dp(44f))) }
                            }
                        }
                    },
                    routeScreen = { _, route ->
                        if ((route as WorkRoute.File).fileId == "board") {
                            OwnEditorNavigation()
                            val exit = LocalRouteExit.current!!
                            fun close() { closed += "draft preserved"; exit() }
                            BackHandler(enabled = LocalEditorActive.current) { close() }
                            Column(Modifier.fillMaxSize()) {
                                Button(onClick = ::close, Modifier.testTag("editor.back")) { Text("Back") }
                                Box(Modifier.weight(1f).fillMaxWidth().testTag("editor.viewport"))
                            }
                        } else Text("Other file")
                    },
                )
            }
        }
        listOf(AppSection.FILES, AppSection.DOCS).forEach { origin ->
            compose.runOnIdle { section = origin }
            compose.onNodeWithTag("filter").performTextReplacement("kept filter")
            repeat(2) { pass ->
                compose.onNodeWithTag("list").performScrollToIndex(12)
                compose.onNodeWithText("Open board").performClick()
                compose.onNodeWithTag("editor.back").assertIsDisplayed()
                compose.onNodeWithTag("nav.back").assertDoesNotExist()
                compose.onNodeWithTag("section.bar").assertDoesNotExist()
                compose.onNodeWithTag("search").assertDoesNotExist()
                compose.onNodeWithTag("editor.viewport").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(200f))
                if (pass == 0) compose.onNodeWithTag("editor.back").performClick()
                else compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("filter").assertTextEquals("kept filter")
                compose.onNodeWithText("Row 12").assertIsDisplayed()
                compose.onNodeWithTag("section.bar").assertIsDisplayed()
                compose.onNodeWithTag("search").assertIsDisplayed()
            }
            compose.onNodeWithText("Open other file").performClick()
            compose.onNodeWithTag("nav.back").assertIsDisplayed().performClick()
            compose.onNodeWithTag("section.bar").assertIsDisplayed()
        }
        assertEquals(List(4) { "draft preserved" }, closed)
    }
}
