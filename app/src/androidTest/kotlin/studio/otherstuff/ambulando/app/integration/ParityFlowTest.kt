package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/** The Android parity workflows, on a dedicated emulator with synthetic sample data (never a real identity or Tower). */
@RunWith(AndroidJUnit4::class)
class ParityFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(block: (AppModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            try { block(model) } finally {
                // The model and the sample store outlive one test: leave neither the editor nor the Edit default behind.
                compose.runOnIdle { model.editorDoc = null; model.documentOpensInEdit = false; model.autoEditedDoc = null }
                runBlocking { model.catalog.writer.setState("settings.documentOpening", "view") }
            }
        }
    }

    private fun openGeneralChat(model: AppModel) {
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.CHAT }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun reactionsGoByWireNameAndAnEditKeepsTheMessageAndItsThreadCanBeRenamed() = launch { model ->
        openGeneralChat(model)
        val text = "Parity flow ${System.nanoTime()}"
        compose.onNodeWithTag("chat.composer").performTextReplacement(text)
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).any { it.message.body == text } } }
        val root = runBlocking { model.store.reader.channelFeed(SampleIds.STUDIO, SampleIds.GENERAL, 300).first { it.message.body == text }.message }
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.message.${root.id}"))

        // React from the picker: the stored reaction carries Tower's wire name, never the emoji character.
        val inMessage = hasAnyAncestor(hasTestTag("chat.message.${root.id}"))
        compose.onNode(hasTestTag("reaction.add") and inMessage).performClick()
        compose.onNodeWithTag("reaction.pick.heart").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.reactions(SampleIds.STUDIO, listOf(root.id)).any { it.emoji == "heart" && !it.deleted } } }
        assertTrue(runBlocking { model.store.reader.reactions(SampleIds.STUDIO, listOf(root.id)) }.none { it.emoji == "❤️" })
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("reaction.heart") and inMessage).fetchSemanticsNodes().isNotEmpty() }

        // Edit through the dialog; the body changes and nothing else about the message does.
        compose.onNodeWithTag("message.edit.${root.id}").performClick()
        // The edit dialog is its own window: wait for it to be composed before typing into it.
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat.edit.field").fetchSemanticsNodes().isNotEmpty() }
        val edited = "$text (edited)"
        compose.onNodeWithTag("chat.edit.field").performTextReplacement(edited)
        compose.onNodeWithTag("chat.edit.save").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.message(SampleIds.STUDIO, root.id)?.body == edited } }

        // Rename the conversation: the typed route answers and the stored title follows.
        compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.thread.${root.threadId}"))
        compose.onNodeWithTag("chat.thread.${root.threadId}").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("thread.actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("thread.actions").performClick()
        compose.onNodeWithTag("thread.menu.rename").performClick()
        compose.onNodeWithTag("thread.rename.field").performTextReplacement("Renamed on Android")
        compose.onNodeWithTag("thread.rename.save").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.thread(SampleIds.STUDIO, root.threadId!!)?.title == "Renamed on Android" } }
    }

    @Test fun aTaskShowsItsSubtasksAndAddingOneKeepsTheParentChannel() = launch { model ->
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.TASKS }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.Review the native inbox").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.Review the native inbox").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.hierarchy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.subtask.add").performScrollTo().performClick()
        compose.waitForIdle()
        runCatching { compose.waitUntil(5_000) { compose.onAllNodesWithTag("task.subtask.title").fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { compose.onRoot().printToLog("SUBTASKDBG") }
        val title = "Subtask ${System.nanoTime()}"
        compose.onNodeWithTag("task.subtask.title").performTextReplacement(title)
        compose.onNodeWithTag("task.subtask.create").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.tasks(SampleIds.STUDIO, includeArchived = true).any { it.title == title } } }
        val parent = runBlocking { model.store.reader.tasks(SampleIds.STUDIO).first { it.title == "Review the native inbox" } }
        val child = runBlocking { model.store.reader.tasks(SampleIds.STUDIO, includeArchived = true).first { it.title == title } }
        assertEquals(parent.channelId, child.channelId)
        assertTrue(child.raw.toString().contains(parent.id))
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.subtask.${child.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.subtask.${child.id}").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.parent").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task.parent").assertTextContains("Subtask of Review the native inbox")
    }

    @Test fun searchGroupsResultsByKindWithTheirLocation() = launch { model ->
        compose.runOnIdle { model.section = AppSection.DECK }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search").performClick()
        compose.onNodeWithText("Search downloaded history").performTextInput("native")
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("search.hit").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Tasks and Documents headings", compose.onAllNodesWithText("Tasks").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("Documents").fetchSemanticsNodes().isNotEmpty())
        assertTrue("each hit says where it lives", compose.onAllNodes(hasText("Ambulando", substring = true) and hasText("#", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(hasText("#general", substring = true)).fetchSemanticsNodes().isNotEmpty())
            // Opening a task result moves the shared context to the task's channel, as file and message results do.
        compose.runOnIdle { model.selectContext(null, null) }
        compose.onAllNodesWithText("Review the native inbox")[0].performClick()
        compose.waitUntil(20_000) { model.context.channelId == SampleIds.GENERAL }
        assertEquals(AppSection.TASKS, model.section)
    }

    @Test fun theDefaultDocumentOpeningModeIsSavedAndOpensDocumentsInTheEditor() = launch { model ->
        compose.runOnIdle { model.documentOpensInEdit = true; model.section = AppSection.DOCS }
        runBlocking { model.catalog.writer.setState("settings.documentOpening", "edit") }
        val doc = runBlocking { model.store.reader.docs(SampleIds.STUDIO).first { it.title == "Native app plan (sample)" } }
        compose.runOnIdle { model.autoEditedDoc = null }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Native app plan (sample)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Native app plan (sample)").performClick()
        compose.waitUntil(30_000) { model.editorDoc == doc.id }
        // Closing the editor must not send the person straight back into it.
        compose.runOnIdle { model.editorDoc = null }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("section.bar").fetchSemanticsNodes().isNotEmpty() }
        assertNull(model.editorDoc)
        // Navigating to the document again opens the editor again (the mode is a default, not a lock).
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Native app plan (sample)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Native app plan (sample)").performClick()
        compose.waitUntil(30_000) { model.editorDoc == doc.id }
    }

    @Test fun aDocumentMovesToAnotherChannelAfterTheConsequencesAreShown() = launch { model ->
        compose.runOnIdle { model.documentOpensInEdit = false; model.section = AppSection.DOCS }
        val doc = runBlocking { model.store.reader.docs(SampleIds.STUDIO).first { it.title == "Native app plan (sample)" } }
        // The sample document stays where an earlier run moved it, so the destination is whichever channel it is not in.
        val destination = if (doc.channelId == SampleIds.DESIGN) SampleIds.GENERAL else SampleIds.DESIGN
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Native app plan (sample)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Native app plan (sample)").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("doc.move").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("doc.move").performClick()
        compose.onNodeWithTag("doc.move.dest.$destination").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("The document moves; it is not copied.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("doc.move.confirm").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.doc(SampleIds.STUDIO, doc.id)?.channelId == destination } }
        assertTrue(runBlocking { model.store.reader.doc(SampleIds.STUDIO, doc.id)!!.rowVersion } > doc.rowVersion)
    }

    @Test fun theOpenSectionAndRecordReturnWhenTheAppIsStartedAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun intent() = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .putExtra(AppEnvironment.RESTORE_NAVIGATION_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val app = context.applicationContext as AmbulandoApplication
        ActivityScenario.launch<MainActivity>(intent()).use {
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.TASKS }
            // Whatever earlier runs saved is restored at start; choosing Tasks again returns to its list, the known starting point.
            compose.waitForIdle()
            compose.onNodeWithTag("section.tasks").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.Review the native inbox").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("task.Review the native inbox").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("task.hierarchy").fetchSemanticsNodes().isNotEmpty() }
            val key = "nav-stacks:${model.connection!!.id}:${SampleIds.STUDIO}"
            // The state is written shortly after the screen changes.
            compose.waitUntil(10_000) { runBlocking { model.store.reader.state(key) }?.contains("Review") == false && runBlocking { model.store.reader.state(key) }?.contains("\"tasks\":[{") == true }
        }
        // A new start (new Activity and composition) reads what was saved and shows the same task.
        ActivityScenario.launch<MainActivity>(intent()).use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("task.hierarchy").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("task.hierarchy").assertExists()
        }
    }
}
