package studio.otherstuff.ambulando.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

@RunWith(RobolectricTestRunner::class)
class DeckRootStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bothBackActionsRestoreCoveredRootState() {
        val stacks = SectionStacks()
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
                    ShellChromeState(AppSection.DECK, listOf(AppSection.DECK), "Workspace", null, null,
                        true, false, false, 0, null, false, false, false), actions, stacks, sidebar = {},
                    sectionRoot = {
                        var search by rememberSaveable { mutableStateOf("") }
                        Column {
                            TextField(search, { search = it }, Modifier.testTag("fixture.search"))
                            Button(onClick = { stacks.push(chatEntrySection(AppSection.DECK), WorkRoute.Thread("w", "c", "t")) }) { Text("Open chat") }
                        }
                    }, routeScreen = { _, _ ->
                        val exit = LocalRouteExit.current!!
                        Column {
                            Text("Conversation")
                            Button(onClick = exit, modifier = Modifier.testTag("nav.back")) { Text("Back") }
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("fixture.search").performTextInput("retained filter")
        repeat(2) { pass ->
            compose.onNodeWithText("Open chat").performClick()
            compose.onNodeWithText("Conversation").assertExists()
            compose.onAllNodesWithTag("nav.back").assertCountEquals(1)
            compose.onNodeWithTag("search").assertDoesNotExist()
            compose.onNodeWithTag("settings").assertDoesNotExist()
            if (pass == 0) compose.onNodeWithTag("nav.back").performClick()
            else compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("fixture.search").assertTextEquals("retained filter")
            compose.onNodeWithTag("nav.back").assertDoesNotExist()
        }
    }
}
