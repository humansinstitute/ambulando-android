package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Runs only against the dedicated synthetic sample-data app. */
@RunWith(AndroidJUnit4::class)
class ChatVisibilityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settingsPersistsWithoutLeavingCurrentConversation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .putExtra(AppEnvironment.RESTORE_NAVIGATION_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspace != null }
            val model = app.activeModel!!
            // A prior run must not supply the preference for this default assertion.
            runBlocking { model.catalog.writer.setState("settings.chat", "false") }
            compose.runOnIdle { model.showChat = false; model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.CHAT }
            compose.onAllNodesWithTag("section.chat").assertCountEquals(0)
            compose.onNodeWithTag("chat.messages").assertExists()
            fun toggle() {
                compose.onNodeWithTag("settings").performClick()
                compose.onNodeWithTag("settings.sections.chat").performScrollTo().performClick()
                compose.onNodeWithTag("settings.done").performClick()
            }
            toggle()
            compose.waitUntil(30_000) { model.showChat }
            compose.onAllNodesWithTag("section.chat").assertCountEquals(if (context.resources.configuration.screenWidthDp >= 1000) 2 else 1)
            compose.onAllNodesWithTag("section.chat").fetchSemanticsNodes().indices.forEach {
                compose.onAllNodesWithTag("section.chat")[it].assertIsSelected()
            }
            compose.runOnIdle { assertEquals(AppSection.CHAT, model.section); assertEquals(SampleIds.GENERAL, model.context.channelId) }
            compose.waitUntil(30_000) { runBlocking { model.catalog.reader.state("settings.chat") } == "true" }
            scenario.recreate()
            compose.waitUntil(30_000) { app.activeModel?.showChat == true }
            compose.onAllNodesWithTag("section.chat").onFirst().assertExists()
            toggle()
            compose.waitUntil(30_000) { app.activeModel?.showChat == false }
            compose.onAllNodesWithTag("section.chat").assertCountEquals(0)
            compose.onNodeWithTag("chat.messages").assertExists()
            compose.waitUntil(30_000) { runBlocking { model.catalog.reader.state("settings.chat") } == "false" }
            scenario.recreate()
            compose.waitUntil(30_000) { app.activeModel?.workspace != null }
            compose.runOnIdle { assertFalse(app.activeModel!!.showChat) }
            compose.onAllNodesWithTag("section.chat").assertCountEquals(0)
        }
    }
}
