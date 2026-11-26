package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Measures the actual composer against Android's IME boundary in both Deck and Chat routes. */
@RunWith(AndroidJUnit4::class)
class CompactChatHeaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun singleBarMenuDismissalAndDraftSurviveArtefacts() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.section = AppSection.DECK }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.page.inbox").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.page.inbox").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.inbox.search").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.inbox.search").performTextReplacement("Native chat review")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("deck.inbox.item").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("deck.inbox.item")[0].performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.composer").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("nav.back").assertCountEquals(1)
            compose.onNodeWithTag("search").assertDoesNotExist()
            compose.onNodeWithTag("settings").assertDoesNotExist()
            compose.onNodeWithTag("thread.artefacts.open").assertIsDisplayed()
            compose.onNodeWithTag("chat.composer").performTextReplacement("Retained menu draft")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("thread.actions").performClick()
            listOf("Pin", "Add to goal", "Search", "Notifications", "Browser", "Settings", "Open navigation").forEach {
                compose.onNodeWithText(it).assertExists()
            }
            shell("input keyevent 4"); compose.waitForIdle()
            compose.onNodeWithText("Open navigation").assertDoesNotExist()
            compose.onNodeWithTag("thread.header").assertIsDisplayed()
            compose.onNodeWithTag("chat.composer").assertTextContains("Retained menu draft")
            compose.onNodeWithTag("thread.artefacts.open").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("chat.composer").assertTextContains("Retained menu draft")
            shell("screencap -p /sdcard/Download/compact-chat-header.png")
            val htmlId = model.sampleTower!!.work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO,
                "Single bar HTML.html", "<!doctype html><html><body><h1>Single bar review</h1><p>Preserved source</p></body></html>".toByteArray(), "text/html")
            compose.onNodeWithTag("thread.artefacts.open").performClick()
            compose.onNodeWithText("+ Browse").performClick()
            compose.onNodeWithTag("thread.artefacts.search").performTextInput("Single bar HTML")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("thread.artefacts.row.$htmlId").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.row.$htmlId").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("artefacts.fileTitle").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("thread.chat").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("artefacts.back").assertIsDisplayed()
            compose.onNodeWithTag("thread.artefacts.menu").assertIsDisplayed()
            compose.onNodeWithTag("thread.artefacts.discuss").assertIsDisplayed()
            compose.onNodeWithTag("artefacts.versionPicker").assertDoesNotExist()
            compose.onNodeWithTag("artefacts.menu").performClick()
            compose.onNodeWithTag("artefacts.commentMode").assertExists()
            compose.onNodeWithTag("artefacts.threads").assertExists()
            shell("screencap -p /sdcard/Download/compact-html-menu.png")
            shell("input keyevent 4"); compose.waitForIdle()
            compose.onNodeWithTag("artefacts.fileTitle").assertIsDisplayed()
            shell("screencap -p /sdcard/Download/compact-html-header.png")
            compose.onNodeWithTag("thread.artefacts.discuss").performClick()
            compose.onNodeWithTag("chat.composer").assertTextContains("Retained menu draft")
            compose.onNodeWithTag("thread.artefacts.open").performClick()
            compose.onNodeWithText("+ Browse").performClick()
            compose.onNodeWithTag("thread.artefacts.search").performTextInput("Build 7 launch")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("thread.artefacts.row.${SampleIds.CANVAS_DOC}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.row.${SampleIds.CANVAS_DOC}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("doc.editor.options").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("thread.chat").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("doc.editor.back").assertIsDisplayed()
            compose.onNodeWithTag("doc.editor.save").assertIsDisplayed()
            compose.onNodeWithTag("doc.editor.options").performClick()
            compose.onNodeWithTag("thread.doc.history").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("v1 ·", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText("v1 ·", substring = true))[0].performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("thread.doc.pinned").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.chat").assertIsDisplayed()
            compose.onNodeWithTag("doc.editor.back").assertIsNotDisplayed()
            shell("screencap -p /sdcard/Download/compact-doc-pinned.png")
            compose.onNode(hasContentDescription("Artefact options") and !hasTestTag("artefacts.menu")).performClick()
            compose.onNodeWithTag("thread.doc.latest").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("doc.editor.back").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("thread.chat").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("doc.editor.back").performClick()
            compose.onNodeWithTag("chat.composer").assertTextContains("Retained menu draft")
            compose.onNodeWithTag("nav.back").performClick()
            compose.onNodeWithTag("deck.inbox.search").assertIsDisplayed()
        }
    }
}
