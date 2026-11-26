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
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

@RunWith(AndroidJUnit4::class)
class ThreadArtefactRecoveryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun dirtyDocumentRemoteRemovalReattachBackgroundExplicitSaveThenSend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.active?.workspace?.id == SampleIds.STUDIO && model.channels.any { it.id == SampleIds.GENERAL } }
            val fixtureServer = requireNotNull(model.sampleTower)
            val document = fixtureServer.work.seedDoc(scope = SampleIds.SCOPE_AMBULANDO, channel = SampleIds.GENERAL,
                title = "Recovery fixture ${System.nanoTime()}", body = "# Local recovery\n\nKeep this source safe.".toByteArray(), contentType = "text/markdown")
            val target = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL, SampleIds.thread(39))
            fixtureServer.seedLongConversation(SampleIds.thread(39), 75)
            val api = model.towerAPI(model.connection!!); val w = model.workspace!!
            fun membership(items: List<ThreadArtefactMember>) = runBlocking {
                val row = api.thread(w.id, w.appNpub, target.threadId!!)
                val base = ThreadArtefactMembershipSync.Row(row.rowVersion, row.raw.getValue("metadata").jsonObject)
                api.updateThreadMetadata(w.id, w.appNpub, row.id, base, ThreadArtefactMembership.metadata(base.metadata, items))
            }
            membership(emptyList())
            runBlocking { model.store.writer.setState("thread.artefacts.${target.draftKey}", null); model.store.writer.saveDraft(target, Draft("", emptyList())) }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat.inbox.row.${target.threadId}")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.inbox.row.${target.threadId}").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("thread.artefacts.open")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.open").performClick(); compose.onNodeWithText("+ Browse").performClick()
            compose.onNodeWithTag("thread.artefacts.search").performTextInput("Recovery fixture")
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("thread.artefacts.row.${document}")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.row.${document}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("doc.editor.title")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("doc.editor.save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val state = model.threadArtefacts.getValue(target.draftKey)
            val tab = state.tabs.single { it.id == document }
            val before = runBlocking { api.doc(w.id, w.appNpub, document).rowVersion }
            compose.onNodeWithTag("doc.editor.title").performTextReplacement("Recovery held until explicit source Save ${System.nanoTime()}")
            compose.waitUntil(15_000) { tab.sourceDirty?.invoke() == true }
            membership(emptyList())
            compose.waitUntil(20_000) { !tab.attached && tab.publicationHeld }
            compose.onNodeWithTag("thread.artefacts").assertDoesNotExist()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(before, runBlocking { api.doc(w.id, w.appNpub, tab.id).rowVersion })
            compose.onNodeWithTag("thread.artefacts.menu").performClick()
            compose.onNodeWithText("Add back to thread").performClick()
            compose.waitUntil(20_000) { tab.attached && !state.busy }
            assertTrue(tab.publicationHeld)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(before, runBlocking { api.doc(w.id, w.appNpub, tab.id).rowVersion })
            compose.onNodeWithTag("thread.artefacts.saveRecovery").performClick()
            compose.waitUntil(30_000) { !tab.publicationHeld && !state.busy }
            val saved = runBlocking { api.doc(w.id, w.appNpub, tab.id) }
            assertEquals(before + 1, saved.rowVersion)
            compose.onNodeWithTag("thread.artefacts.discuss").assertIsEnabled().performClick()
            compose.onNodeWithTag("chat.composer").performTextInput("Discuss after explicit recovery Save")
            compose.onNodeWithTag("chat.send").assertIsEnabled().performClick()
            compose.waitUntil(30_000) { runBlocking { model.store.reader.threadMessages(w.id, target.threadId!!, 300).any { it.body == "Discuss after explicit recovery Save" && it.raw["metadata"]?.toString()?.contains("ambulando_artefact_context") == true } } }
            assertEquals(saved.rowVersion, runBlocking { api.doc(w.id, w.appNpub, tab.id).rowVersion })
        }
    }
}
