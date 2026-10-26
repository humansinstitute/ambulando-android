package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import studio.otherstuff.ambulando.core.chat.PastedTextPolicy
import studio.otherstuff.ambulando.core.chat.TextAttachments
import studio.otherstuff.ambulando.core.chat.TextEditState
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.MessageAttachment

/** Paste → card → modal → send → sent card → edit → versions, in the app against the synthetic Tower. */
@RunWith(AndroidJUnit4::class)
class TextAttachmentFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun hasTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }
    private fun editableText(): String = compose.onNodeWithTag("chat.composer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    @Test fun pasteBecomesAFileSendsOnceAndIsEditedIntoAVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { val ch = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(ch.scopeId, ch.id); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Send").assertIsNotEnabled()

            // 2500 code points stay in the composer; 2501 become a file and leave the composer untouched.
            compose.onNodeWithTag("chat.composer").performTextReplacement("a".repeat(PastedTextPolicy.PASTE_THRESHOLD))
            compose.waitForIdle()
            assertEquals(PastedTextPolicy.PASTE_THRESHOLD, editableText().length)
            compose.onAllNodes(hasTagPrefix("chat.draft.attachment.") and hasTagSuffix(".open")).assertCountEquals(0)
            compose.onNodeWithTag("chat.composer").performTextReplacement("keep me")
            val pasted = "first line é 😀\r\n" + "z".repeat(3000) + "\r\nlast"
            compose.onNodeWithTag("chat.composer").performTextInput(pasted)
            compose.waitUntil(20_000) { compose.onAllNodes(hasTagPrefix("chat.draft.attachment.") and hasTagSuffix(".name")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("existing composer text is retained", "keep me", editableText())
            compose.onNodeWithTag("chat.paste.undo").assertExists()
            captureTextReview("draft-card")
            val draft = runBlocking { model.store.reader.draft(ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL))!! }
            assertEquals("Pasted text.txt", draft.attachments.single().filename)
            assertEquals(pasted, model.outbox.readStagedText(draft.attachments.single()))
            compose.onNodeWithText("Pasted text.txt").assertExists()

            // Survives a rotation / recreation of the activity (draft is durable).
            scenario.recreate()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTagPrefix("chat.draft.attachment.") and hasTagSuffix(".name")).fetchSemanticsNodes().isNotEmpty() }

            // Open the modal, rename, save.
            compose.onNode(hasTagPrefix("chat.draft.attachment.") and hasTagSuffix(".open")).performClick()
            compose.onNodeWithTag("text.filename").assertIsDisplayed()
            captureTextReview("draft-editor")
            compose.onNodeWithTag("text.filename").performTextReplacement("notes")
            compose.onNodeWithTag("text.save").performClick()
            compose.waitUntil(20_000) { runBlocking { model.store.reader.draft(ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL))!!.attachments.single().filename == "notes.txt" } }

            // Send with text; it arrives once, with the attachment metadata and no text in it.
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(30_000) { runBlocking { model.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == "keep me" } } }
            val sent = runBlocking { model.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).filter { it.body == "keep me" } }
            assertEquals(1, sent.size)
            val attachment = MessageAttachment.from(sent[0].raw).single()
            assertEquals("notes.txt", attachment.filename)
            assertEquals(TextAttachments.bytes(pasted).size.toLong(), attachment.sizeBytes)
            assertEquals(3, attachment.textAttachment?.lines)
            assertFalse(sent[0].raw.toString().contains("zzzzzzzzzz"))

            // The sent card opens read-only, then Edit saves a new version; the original stays.
            compose.onNodeWithTag("chat.messages").performScrollToNode(hasTestTag("chat.attachment.${attachment.storageObjectId}.open"))
            compose.onNodeWithTag("chat.attachment.${attachment.storageObjectId}.open").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("text.edit") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("text.edit").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("text.editor")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("text.editor").performTextInput("EDITED ")
            compose.onNodeWithTag("text.save").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("text.history.Edit 1")).fetchSemanticsNodes().isNotEmpty() }
            captureTextReview("sent-history")
            // A concurrent synthetic save must retain this user's edit and expose an explicit conflict.
            compose.onNodeWithTag("text.edit").performClick()
            compose.onNodeWithTag("text.editor").performTextInput("MY CONFLICTING EDIT ")
            val c = model.connection!!; val w = model.workspace!!
            val service = model.textAttachments(c, w.id)
            runBlocking {
                val h = service.history(model.towerAPI(c), w.id, w.appNpub, SampleIds.GENERAL, sent[0].id, attachment)
                val other = service.beginEdit(studio.otherstuff.ambulando.core.transport.newRequestId(), c.id, w.id,
                    SampleIds.GENERAL, w.appNpub, sent[0].id, attachment, "notes.txt", "Another synthetic writer",
                    h.fileId, h.currentVersionId, c.origin)
                assertEquals(TextEditState.SAVED, service.save(model.towerAPI(c), other.id).state)
            }
            compose.onNodeWithTag("text.save").performClick()
            compose.waitUntil(30_000) { service.edits.forAttachment(c.id, c.origin, w.id, sent[0].id, attachment.storageObjectId)?.state == TextEditState.CONFLICT }
            val mine = service.edits.forAttachment(c.id, c.origin, w.id, sent[0].id, attachment.storageObjectId)!!
            assertTrue(service.edits.text(mine)!!.contains("MY CONFLICTING EDIT"))
            // Conflict actions are immediately available; closing a journaled edit never claims it is discarded.
            compose.onNodeWithTag("text.saveOverLatest").assertIsEnabled()
            compose.waitForIdle()
            captureTextReview("sent-conflict-actions")
            compose.onNodeWithTag("text.close").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("text.filename")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Unsaved changes").assertDoesNotExist()
            assertEquals(mine.staged, service.edits.get(mine.id)!!.staged)
            compose.onNodeWithTag("chat.attachment.${attachment.storageObjectId}.open").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("text.resume")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("text.resume").performClick()
            compose.onNodeWithTag("text.editor").assertTextContains("MY CONFLICTING EDIT", substring = true)
            // Fresh history must not silently rebase additional local edits over the newer remote version.
            compose.onNodeWithTag("text.editor").performTextInput("NEWER LOCAL TEXT ")
            compose.onNodeWithTag("text.save").performClick()
            compose.waitUntil(30_000) {
                service.edits.get(mine.id)?.let { it.state == TextEditState.CONFLICT && service.edits.text(it)?.contains("NEWER LOCAL TEXT") == true } == true
            }
            assertEquals(mine.baseVersionId, service.edits.get(mine.id)!!.baseVersionId)
            compose.onNodeWithTag("text.saveOverLatest").assertIsEnabled().performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("text.history.Edit 3")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("text.editor").assertDoesNotExist()
            compose.onNodeWithTag("text.history.Edit 3").assertIsSelected()
            compose.waitForIdle()
            captureTextReview("sent-conflict-resolved")
            assertNull(service.edits.get(mine.id))

            // Unsaved modal text can be kept locally without publishing, then explicitly discarded separately.
            compose.onNodeWithTag("text.edit").performClick()
            compose.onNodeWithTag("text.editor").performTextInput("KEEP ON DEVICE ")
            compose.onNodeWithTag("text.close").performClick()
            compose.onNodeWithTag("text.keepLocalClose").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("text.filename")).fetchSemanticsNodes().isEmpty() }
            val local = service.edits.forAttachment(c.id, c.origin, w.id, sent[0].id, attachment.storageObjectId)!!
            assertTrue(local.localOnly)
            assertTrue(service.edits.text(local)!!.contains("KEEP ON DEVICE"))
            compose.runOnIdle { model.refresh(force = true) }
            compose.waitForIdle()
            runBlocking { kotlinx.coroutines.withTimeout(20_000) { model.engine.waitUntilIdle() } }
            assertTrue(service.edits.get(local.id)!!.localOnly)
            assertEquals(4, runBlocking { service.history(model.towerAPI(c), w.id, w.appNpub, SampleIds.GENERAL, sent[0].id, attachment).entries.size })
            compose.onNodeWithTag("chat.attachment.${attachment.storageObjectId}.open").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("text.discardSaved")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("text.discardSaved").performClick()
            compose.onNodeWithTag("text.confirmDiscardSaved").performClick()
            compose.waitUntil(20_000) { service.edits.get(local.id) == null }
            assertEquals(4, runBlocking { service.history(model.towerAPI(c), w.id, w.appNpub, SampleIds.GENERAL, sent[0].id, attachment).entries.size })
            compose.onNodeWithTag("text.history.Original").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("first line", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun hasTagSuffix(suffix: String) = SemanticsMatcher("tag ends with $suffix") { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith(suffix) == true }
}
