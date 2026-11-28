package studio.otherstuff.ambulando.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.context.*

@RunWith(RobolectricTestRunner::class)
class WorkspaceDestinationsTest {
    @get:Rule val compose = createComposeRule()
    private val w = "workspace"
    private fun state() = SidebarState("Studio",w,listOf(w to "Studio"),true,emptyList(),emptySet(),emptySet(),WorkContext.HOME,emptyMap(),emptySet(),NavigationLoadState.Ready(null),false,destination="goals")
    private class Actions : SidebarActions {
        val opened = mutableListOf<Pair<String?,String>>()
        override fun destination(workspaceId: String?, name: String) { opened += workspaceId to name }
        override fun chooseHome() {}
        override fun chooseScope(scopeId: String) {}
        override fun chooseChannel(scopeId: String, channelId: String) {}
        override fun toggleCollapsed(scopeId: String) {}
        override fun newChannel(scope: NavScope) {}
        override fun channelSettings(channel: NavChannel) {}
        override fun manageScopes() {}
        override fun selectWorkspace(workspaceId: String) {}
        override fun retry() {}
        override fun close() {}
    }
    @Test fun peersHaveDistinctSelectionAndCarryWorkspaceOnTap() {
        val actions=Actions()
        compose.setContent { AmbulandoTheme { SidebarContent(state(),actions) } }
        compose.onNodeWithTag("sidebar.home").assertIsNotSelected()
        compose.onNodeWithTag("sidebar.goals").assertIsSelected()
        for(name in listOf("home","goals","reminders","loops")) compose.onNodeWithTag("sidebar.$name").performClick()
        assertEquals(listOf("home","goals","reminders","loops").map { w to it }, actions.opened)
    }
    @Test fun workspaceGoalsAndLoopsRestoreAndBackReturnsToHome() {
        val stacks=SectionStacks()
        stacks.push(AppSection.HOME,WorkRoute.Goals(w)); stacks.push(AppSection.HOME,WorkRoute.Goal(w,"file"))
        assertTrue(stacks.pop(AppSection.HOME)); assertEquals(WorkRoute.Goals(w),stacks.of(AppSection.HOME).last())
        assertTrue(stacks.pop(AppSection.HOME)); assertTrue(stacks.of(AppSection.HOME).isEmpty())
        stacks.push(AppSection.HOME,WorkRoute.Reminders(w))
        assertEquals(listOf(WorkRoute.Reminders(w)),RouteState.decode(RouteState.encode("home",stacks.snapshot()),w)!!.stacks["home"])
        stacks.popToRoot(AppSection.HOME)
        stacks.push(AppSection.HOME,WorkRoute.Loops(w))
        val encoded=RouteState.encode("home",stacks.snapshot())
        assertEquals(listOf(WorkRoute.Loops(w)),RouteState.decode(encoded,w)!!.stacks["home"])
        assertTrue(RouteState.decode(encoded,"other")!!.stacks["home"]!!.isEmpty())
    }
    @Test fun loopsExplainsStubWithoutClaimingRunningWork() {
        compose.setContent { AmbulandoTheme { LoopsScreen() } }
        compose.onNodeWithText("Regular work and loops we delegate to our agents.").assertIsDisplayed()
        compose.onNodeWithText("Preview · No work is scheduled or running from this page.").assertIsDisplayed()
        compose.onNodeWithText("Example: a weekly review of progress toward your goals.").assertIsDisplayed()
    }
}
