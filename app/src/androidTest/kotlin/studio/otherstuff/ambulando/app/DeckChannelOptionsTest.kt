package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleViewer
import studio.otherstuff.ambulando.core.store.AccessTarget
import studio.otherstuff.ambulando.core.store.markAccessRemoved

/** Full native menu/settings flow on a dedicated emulator with synthetic identity/Tower only. */
@RunWith(AndroidJUnit4::class)
class DeckChannelOptionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { val node = compose.onNodeWithTag(tag); runCatching { node.performScrollTo() }; node.performClick() }
    private val readActions = listOf("Mark all tasks as read", "Mark all docs as read", "Mark all chats as read", "Mark everything as read")

    @Test fun channelMenuKeepsExactTargetReadActionsPermissionsAndDismissal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            val tower = model.sampleTower!!
            try {
            tower.administration.viewer = SampleViewer.MEMBER
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.DESIGN } }
            fun select(channelId: String?) {
                compose.runOnIdle { model.selectContext(if (channelId == null) null else SampleIds.SCOPE_AMBULANDO, channelId) }
                compose.waitUntil(30_000) { model.context.channelId == channelId }
                waitTag("deck.inbox.readMenu")
            }
            fun openMenu() { click("deck.inbox.readMenu"); compose.onNodeWithText(readActions.first()).assertExists() }
            fun back() { androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle() }

            // Workspace-wide and all-channel scopes have no channel action, while read actions remain available.
            select(null)
            openMenu(); compose.onNodeWithTag("deck.inbox.channelOptions").assertDoesNotExist(); back()
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, null) }
            compose.waitUntil(30_000) { model.context.scopeId == SampleIds.SCOPE_AMBULANDO }
            openMenu(); compose.onNodeWithTag("deck.inbox.channelOptions").assertDoesNotExist(); back()

            // Two channels ensure this opens the Inbox target, rather than an earlier/default channel.
            for (channelId in listOf(SampleIds.GENERAL, SampleIds.DESIGN)) {
                select(channelId)
                val channel = model.channels.first { it.id == channelId }
                openMenu()
                readActions.forEach { compose.onNodeWithText(it).assertExists() }
                click("deck.inbox.channelOptions")
                waitTag("admin.scopesPage")
                compose.onNodeWithText("#${channel.name}").assertExists()
                // This synthetic member has channel.manage but not channel.grants.manage.
                compose.onNodeWithText("Rename channel").assertExists()
                click("management.channelAccess")
                waitTag("admin.channel.readOnly")
                compose.onNodeWithTag("admin.channel.grant").assertIsNotEnabled()
                click("admin.back")
                waitTag("admin.scopesPage")
                assertEquals(channelId, model.context.channelId)
                if (channelId == SampleIds.GENERAL) click("admin.back") else back()
                waitTag("deck.inbox.readMenu")
                compose.onNodeWithTag("deck.inbox.channelOptions").assertDoesNotExist()
            }
            // Each existing read command still runs and closes the same dropdown.
            readActions.forEach { label -> openMenu(); compose.onNodeWithText(label).performClick(); compose.onNodeWithText(label).assertDoesNotExist() }
            // An outside touch dismisses the popup without invoking a channel or read action.
            openMenu()
            val metrics = context.resources.displayMetrics
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "input tap ${metrics.widthPixels / 2} ${metrics.heightPixels * 85 / 100}"
            ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("deck.inbox.channelOptions").fetchSemanticsNodes().isEmpty() }
            // System Back dismisses only the dropdown, retaining Deck and its context.
            openMenu(); back(); compose.onNodeWithTag("deck.inbox.channelOptions").assertDoesNotExist()
            assertEquals(SampleIds.DESIGN, model.context.channelId)
            assertTrue(tower.administration.writes().isEmpty())
            // Access removed while the dropdown is open removes the action instead of retargeting it.
            openMenu()
            tower.offline = true
            kotlinx.coroutines.runBlocking { model.store.writer.markAccessRemoved(SampleIds.STUDIO, AccessTarget.Channel(SampleIds.DESIGN)) }
            compose.waitUntil(30_000) { model.channels.firstOrNull { it.id == SampleIds.DESIGN }?.accessRemoved != false }
            compose.onNodeWithTag("deck.inbox.channelOptions").assertDoesNotExist()
            back()
            // An unresolved context must not fall back to a different available channel.
            select("00000000-0000-4000-8000-000000000099")
            openMenu(); compose.onNodeWithTag("deck.inbox.channelOptions").assertDoesNotExist(); back()
            } finally { tower.offline = false; tower.administration.viewer = SampleViewer.MEMBER }
        }
    }
}
