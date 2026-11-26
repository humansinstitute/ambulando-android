package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.sample.SampleFault
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/**
 * An attached HTML artefact that cannot be read must stay a local failure: the thread still exits, sends and
 * relaunches. Regression for an updated DM artefact that left Back reporting "has not preserved pending changes".
 */
@RunWith(AndroidJUnit4::class)
class ThreadArtefactLoadFailureFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** Tower fails the read (as the updated revision did on the phone): an error with Retry. */
    @Test fun unreadableAttachedArtefactNeverTrapsBackSendOrRelaunch() = scenario(SampleFault.Status(500, "internal"), retry = true)

    /** Tower no longer authorises the file: the saved copy opens read-only and the thread keeps working. */
    @Test fun inaccessibleAttachedArtefactFallsBackWithoutTrappingThread() = scenario(SampleFault.Status(404, "not_found"), retry = false)

    private fun scenario(fault: SampleFault, retry: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val app = context.applicationContext as AmbulandoApplication
        val draftText = "Unsent draft kept ${System.nanoTime()}"
        lateinit var target: ChatTarget
        lateinit var file: String
        fun openThread(model: AppModel) {
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat.inbox.rows").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.inbox.rows").performScrollToNode(hasTestTag("chat.inbox.row.${target.threadId}"))
            compose.onNodeWithTag("chat.inbox.row.${target.threadId}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat.composer")).fetchSemanticsNodes().isNotEmpty() }
        }
        /** The updated artefact can no longer be read (every attempt fails at Tower), as after a relaunch on the phone. */
        fun breakArtefact(model: AppModel, times: Int = 5) = repeat(times) { model.sampleTower!!.work.inject(fault, "/files/$file") }
        /** One-shot faults outlive a phase; clear leftovers so the next phase starts from a known server. */
        fun drainFaults(model: AppModel) = model.sampleTower!!.work.clearFaults("/files/$file")
        fun backLeavesThread(state: ThreadArtefacts) {
            compose.onNodeWithTag("nav.back").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("chat.composer")).fetchSemanticsNodes().isEmpty() || state.failure != null }
            assertNull("Back was blocked: ${state.failure}", state.failure?.takeIf { it.contains("has not preserved") })
            compose.onNodeWithTag("chat.composer").assertDoesNotExist()
        }
        launch().use {
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.active?.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            val tower = requireNotNull(model.sampleTower)
            val html = "<!doctype html><html><body><h1>Projects</h1>${"<p>row</p>".repeat(2000)}</body></html>"
            val name = "Projects ${System.nanoTime()}"
            file = tower.work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, "$name.html", html.toByteArray(), "text/html")
            target = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL, SampleIds.thread(39))
            tower.seedLongConversation(SampleIds.thread(39), 20)
            val api = model.towerAPI(model.connection!!); val w = model.workspace!!
            runBlocking {
                val row = api.thread(w.id, w.appNpub, target.threadId!!)
                val base = ThreadArtefactMembershipSync.Row(row.rowVersion, row.raw.getValue("metadata").jsonObject)
                api.updateThreadMetadata(w.id, w.appNpub, row.id, base, ThreadArtefactMembership.metadata(base.metadata, emptyList()))
                model.store.writer.setState("thread.artefacts.${target.draftKey}", null)
                model.store.writer.saveDraft(target, Draft("", emptyList()))
            }
            compose.runOnIdle { model.threadArtefacts.clear() }
            // Open the readable artefact in the thread, exactly as before the update.
            openThread(model)
            compose.onNodeWithTag("thread.artefacts.open").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("+ Browse").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("+ Browse").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("thread.artefacts.search").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.search").performTextInput(name)
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("thread.artefacts.row.$file")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.row.$file").performClick()
            val state = model.threadArtefacts.getValue(target.draftKey)
            compose.waitUntil(30_000) { state.tabs.any { it.id == file && it.open && it.attached } && !state.busy }
            compose.waitUntil(30_000) { state.tabs.single { it.id == file }.versionLabel.startsWith("v") }
            compose.onNodeWithTag("thread.chat").performClick()
            compose.onNodeWithTag("chat.composer").performTextInput(draftText)
            compose.waitUntil(10_000) { runBlocking { model.store.reader.draft(target)?.text == draftText } }
            backLeavesThread(state)
        }
        // Relaunch: the retained open tab now fails to load.
        app.activeModel!!.threadArtefacts.clear()
        breakArtefact(app.activeModel!!)
        launch().use {
            val model = app.activeModel!!
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            openThread(model)
            val state = model.threadArtefacts.getValue(target.draftKey)
            compose.waitUntil(20_000) { state.restored && state.tabs.any { it.id == file && it.open } }
            compose.waitUntil(60_000) { model.sampleTower!!.work.requestLog.any { it.endsWith("/files/$file") } }
            compose.waitUntil(60_000) { state.tabs.single { it.id == file }.unavailable != null }
            compose.onNodeWithTag("chat.composer").assertTextContains(draftText)
            // Back leaves the thread instead of reporting unpreserved changes.
            backLeavesThread(state)
            assertTrue(state.tabs.single { it.id == file }.open)
            // Plain Send continues while the unreadable artefact stays open.
            openThread(model)
            compose.onNodeWithTag("chat.composer").performTextReplacement("Reporting the broken artefact")
            compose.onNodeWithTag("chat.send").assertIsEnabled().performClick()
            compose.waitUntil(30_000) { state.failure != null || runBlocking { model.store.reader.threadMessages(model.workspace!!.id, target.threadId!!, 300).any { it.body == "Reporting the broken artefact" } } }
            assertNull("Send was blocked: ${state.failure}", state.failure?.takeIf { it.contains("Send paused") })
            compose.waitUntil(10_000) { state.notice?.contains("Sent without") == true }
            // Workspace sync keeps running: another member's new conversation still arrives while the tab stays broken.
            val inbound = model.sampleTower!!.postAsMember(body = "Unrelated update ${System.nanoTime()}")
            compose.waitUntil(60_000) { runBlocking { model.store.reader.message(SampleIds.STUDIO, inbound) != null } }
            assertNotNull(state.tabs.single { it.id == file }.unavailable)
            compose.onNodeWithTag("chat.composer").performTextInput(draftText)
            compose.waitUntil(10_000) { runBlocking { model.store.reader.draft(target)?.text == draftText } }
            backLeavesThread(state)
            assertTrue(state.tabs.single { it.id == file }.open)
        }
        // A second relaunch restores the same failed tab; the draft survives and the thread still exits.
        app.activeModel!!.threadArtefacts.clear()
        breakArtefact(app.activeModel!!)
        launch().use {
            val model = app.activeModel!!
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            openThread(model)
            val state = model.threadArtefacts.getValue(target.draftKey)
            compose.waitUntil(20_000) { state.restored && state.tabs.any { it.id == file && it.open } }
            compose.waitUntil(60_000) { state.tabs.single { it.id == file }.unavailable != null }
            compose.onNodeWithTag("chat.composer").assertTextContains(draftText)
            backLeavesThread(state)
            drainFaults(model)
        }
        if (!retry) return
        // Retry recovers in place once Tower answers again: the editor registers and Send context returns.
        app.activeModel!!.threadArtefacts.clear()
        breakArtefact(app.activeModel!!, times = 1)
        launch().use {
            val model = app.activeModel!!
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            openThread(model)
            val state = model.threadArtefacts.getValue(target.draftKey)
            val tab = state.tabs.single { it.id == file }
            compose.waitUntil(60_000) { tab.unavailable != null }
            compose.runOnIdle { state.selected = file; state.shown = true }
            compose.onNodeWithTag("thread.artefact.retry").performClick()
            runCatching { compose.waitUntil(60_000) { tab.unavailable == null && Regex("v[0-9]+").containsMatchIn(tab.versionLabel) && tab.checkpoint != null } }
                .onFailure { fail("Retry did not recover: unavailable=${tab.unavailable} label=${tab.versionLabel} checkpoint=${tab.checkpoint != null} log=${model.sampleTower!!.work.requestLog.filter { file in it }.takeLast(8)}") }
            assertEquals(listOf(tab), state.sendTabs)
        }
    }
}
