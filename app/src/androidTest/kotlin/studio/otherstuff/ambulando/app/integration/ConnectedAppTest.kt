package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.refreshDocs
import studio.otherstuff.ambulando.core.sync.refreshDoc

@RunWith(AndroidJUnit4::class)
class ConnectedAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun sampleDiscoverySharedContextDurableSendAndDocumentReader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { val channel = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(channel.scopeId, channel.id); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").performTextReplacement("Synthetic durable Android workflow")
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == "Synthetic durable Android workflow" } } }
            assertTrue(runBlocking { model.store.reader.outbox(model.connection!!.id, model.connection!!.signerNpub).isEmpty() })
            scenario.recreate()
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasText("Synthetic durable Android workflow"))
            compose.onNodeWithText("Synthetic durable Android workflow").assertIsDisplayed()
            assertEquals(SampleIds.GENERAL, model.context.channelId)
            compose.runOnIdle { model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.DOCS; model.refresh() }
            compose.waitUntil(20_000) { runBlocking { model.store.reader.docs(SampleIds.STUDIO, model.context).isNotEmpty() } }
            assertEquals(SampleIds.GENERAL, model.context.channelId)
        }
    }
    @Test fun bundledEditorNoOpSavePreservesRichEnvelopeOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.act { model.sync.refreshDocs(model.connection!!, model.workspace!!) } }
            compose.waitUntil(20_000) { runBlocking { model.store.reader.docs(SampleIds.STUDIO).any { it.title == "Native app plan (sample)" } } }
            val doc = runBlocking { model.store.reader.docs(SampleIds.STUDIO).first { it.title == "Native app plan (sample)" } }
            compose.runOnIdle { model.act { model.sync.refreshDoc(model.connection!!, model.workspace!!, doc.id) } }
            compose.waitUntil(20_000) { runBlocking { model.store.reader.docBody(SampleIds.STUDIO, doc.id) != null } }
            val before = runBlocking { model.store.reader.docBody(SampleIds.STUDIO, doc.id)!!.bytes.copyOf() }
            compose.runOnIdle { model.editorDoc = doc.id }
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save").performClick()
            // Nothing changed, so nothing is published; the editor stays open and says so (Save no longer closes it).
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("No changes to save.")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("doc.editor.back").performClick()
            compose.waitUntil(20_000) { model.editorDoc == null }
            val after = runBlocking { model.store.reader.docBody(SampleIds.STUDIO, doc.id)!!.bytes }
            assertArrayEquals(before, after)
            assertNull(runBlocking { model.store.reader.docDraft(studio.otherstuff.ambulando.core.docs.DocDraft.key(model.connection!!.id, SampleIds.STUDIO, doc.id)) })
        }
    }

}
