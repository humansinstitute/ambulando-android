package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import java.io.File

/** Staged text attachments through the durable draft, the outbox and a synthetic Tower. */
class TextAttachmentOutboxTest {
    private class Rig(val store: LocalStore, val engine: SyncEngine, val sender: OutboxSender, val sync: WorkspaceSync, val tower: SampleTower)

    private suspend fun <T> withRig(directory: File = tempDir(), staging: File = tempDir(), body: suspend (Rig, studio.otherstuff.ambulando.core.store.ConnectionRecord, studio.otherstuff.ambulando.core.transport.WorkspaceRecord, ChatTarget) -> T): T {
        val identity = makeIdentity()
        val tower = SampleTower(tempDir()).also { it.prepare(TestKeys.NPUB) }
        val engine = SyncEngine()
        val store = LocalStore(directory)
        try {
            val sync = WorkspaceSync(store, engine, identity, tower)
            val connection = sync.connect(tower.origin)
            sync.discover(connection)
            withTimeout(15_000) { engine.waitUntilIdle() }
            val workspace = store.reader.workspaces(connection.id).first { it.id == SampleIds.STUDIO }
            val sender = OutboxSender(store, engine, identity, tower, staging)
            return body(Rig(store, engine, sender, sync, tower), connection, workspace, ChatTarget(connection.id, workspace.id, SampleIds.GENERAL))
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    @Test fun draftAttachmentsAreDurableAndTextSavesNeverTouchThem() = runBlocking {
        val directory = tempDir(); val staging = tempDir()
        withRig(directory, staging) { r, _, _, target ->
            val a = r.sender.stageText("pasted\r\ntext", "Pasted text.txt", "paste")
            r.store.writer.setDraftAttachments(target, listOf(a))
            r.store.writer.saveDraft(target, Draft("hello", emptyList()))
            r.store.writer.saveDraft(target, Draft("", emptyList()))     // clearing the text must keep the attachment
            val draft = r.store.reader.draft(target)!!
            assertEquals(listOf(a), draft.attachments)
            assertEquals("pasted\r\ntext", r.sender.readStagedText(draft.attachments[0]))
            // Removing the last attachment with no text removes the draft row.
            r.store.writer.setDraftAttachments(target, emptyList())
            assertNull(r.store.reader.draft(target))
        }
    }

    @Test fun stagedBytesAreTheExactUtf8() = runBlocking {
        withRig { r, _, _, _ ->
            val text = "a\r\nb 😀 é\r\n"
            val a = r.sender.stageText(text, "n", "paste")
            assertEquals("n.txt", a.filename)
            assertEquals("file", a.kind)
            assertEquals(TextAttachments.bytes(text).size.toLong(), a.sizeBytes)
            assertEquals(TextAttachmentInfo(1, "paste", PastedTextPolicy.codePoints(text), 3), a.text)
            assertEquals(text, r.sender.readStagedText(a))
        }
    }

    @Test fun attachmentOnlyMessageUploadsThenSendsOnceWithTheContractMetadata() = runBlocking {
        withRig { r, c, w, target ->
            val text = "x".repeat(3000) + "\r\nend"
            val a = r.sender.stageText(text, "Pasted text.txt", "paste")
            r.store.writer.setDraftAttachments(target, listOf(a))
            val item = r.sender.send("", emptyList(), target, c, w, listOf(a))
            assertNotNull(item)
            assertNull("draft cleared in the same step", r.store.reader.draft(target))
            withTimeout(20_000) { r.engine.waitUntilIdle() }
            assertTrue(r.store.reader.outbox(target).isEmpty())
            val sent = r.store.reader.latestMessages(w.id, SampleIds.GENERAL, 500).filter { it.clientRequestId == item!!.id }
            assertEquals(1, sent.size)
            val att = studio.otherstuff.ambulando.core.transport.MessageAttachment.from(sent[0].raw).single()
            assertEquals("file", att.kind)
            assertEquals("Pasted text.txt", att.filename)
            assertEquals(TextAttachments.CONTENT_TYPE, att.mimeType)
            assertEquals(a.sha256, att.sha256)
            assertEquals(a.sizeBytes, att.sizeBytes)
            assertEquals(TextAttachmentInfo(1, "paste", PastedTextPolicy.codePoints(text), 2), att.textAttachment)
            // The text itself is not in the metadata or the body.
            assertFalse(sent[0].raw.toString().contains("x".repeat(100)))
            // Staged bytes are removed once accepted.
            assertFalse(File(r.sender.staging!!, a.staged).exists())
            // A repeated pass never sends it again.
            r.sender.kick(c, force = true)
            withTimeout(20_000) { r.engine.waitUntilIdle() }
            assertEquals(1, r.store.reader.latestMessages(w.id, SampleIds.GENERAL, 500).count { it.clientRequestId == item!!.id })
        }
    }

    @Test fun messagesWithAttachmentsStayInOrderAcrossTheQueue() = runBlocking {
        withRig { r, c, w, target ->
            val a1 = r.sender.stageText("first file", "Pasted text.txt", "paste")
            val a2 = r.sender.stageText("second file", "Pasted text 2.txt", "paste")
            val m1 = r.sender.send("one", emptyList(), target, c, w, listOf(a1))!!
            val m2 = r.sender.send("two", emptyList(), target, c, w, listOf(a2))!!
            withTimeout(20_000) { r.engine.waitUntilIdle() }
            val sent = r.store.reader.latestMessages(w.id, SampleIds.GENERAL, 500)
            val first = sent.single { it.clientRequestId == m1.id }
            val second = sent.single { it.clientRequestId == m2.id }
            assertTrue("first queued, first accepted", first.createdAt <= second.createdAt)
        }
    }

    @Test fun editOfAFailedSendReturnsTextAttachmentsToTheDraft() = runBlocking {
        withRig { r, c, w, target ->
            val a = r.sender.stageText("keep me", "Pasted text.txt", "paste")
            val queued = r.store.writer.enqueueSend(target, c.origin, c.signerNpub, w.appNpub, "body", emptyList(), listOf(a))
            r.store.writer.markOutbox(queued.id, OutboxState.FAILED, error = "x")
            r.sender.discard(queued.id, restoreDraft = true)
            val draft = r.store.reader.draft(target)!!
            assertEquals("body", draft.text)
            assertEquals(listOf(a.copy(objectId = null, uploadURL = null, complete = false)), draft.attachments)
            assertEquals("keep me", r.sender.readStagedText(draft.attachments[0]))
        }
    }

    @Test fun discardWithoutRestoreRemovesStagedBytes() = runBlocking {
        withRig { r, c, w, target ->
            val a = r.sender.stageText("gone", "Pasted text.txt", "paste")
            val queued = r.store.writer.enqueueSend(target, c.origin, c.signerNpub, w.appNpub, "b", emptyList(), listOf(a))
            r.store.writer.markOutbox(queued.id, OutboxState.FAILED, error = "x")
            r.sender.discard(queued.id, restoreDraft = false)
            assertFalse(File(r.sender.staging!!, a.staged).exists())
        }
    }

    @Test fun sendRefusesAnAttachmentWhoseBytesAreGone() = runBlocking {
        withRig { r, c, w, target ->
            val a = r.sender.stageText("x", "Pasted text.txt", "paste")
            File(r.sender.staging!!, a.staged).delete()
            try { r.sender.send("", emptyList(), target, c, w, listOf(a)); fail() } catch (e: studio.otherstuff.ambulando.core.transport.FileTransferError) { }
            assertTrue(r.store.reader.outbox(target).isEmpty())
        }
    }

    @Test fun failedUploadRetriesAndSendsExactlyOnce() = runBlocking {
        val identity = makeIdentity()
        val tower = SampleTower(tempDir()).also { it.prepare(TestKeys.NPUB) }
        val engine = SyncEngine()
        val store = LocalStore(tempDir())
        var failures = 1
        val prepares = java.util.concurrent.atomic.AtomicInteger()
        val messagePosts = java.util.concurrent.atomic.AtomicInteger()
        val flaky = object : studio.otherstuff.ambulando.core.transport.HttpTransport {
            override suspend fun send(request: studio.otherstuff.ambulando.core.transport.HttpRequest): studio.otherstuff.ambulando.core.transport.HttpResponse {
                val url = request.url.substringBefore('?')
                if (request.method == "POST" && url.endsWith("/storage/prepare")) prepares.incrementAndGet()
                if (request.method == "POST" && url.endsWith("/messages")) messagePosts.incrementAndGet()
                if (request.method == "POST" && url.endsWith("/complete") && failures-- > 0) throw java.net.SocketTimeoutException("upload interrupted")
                return tower.send(request)
            }
        }
        try {
            val sync = WorkspaceSync(store, engine, identity, tower)
            val connection = sync.connect(tower.origin)
            sync.discover(connection); withTimeout(15_000) { engine.waitUntilIdle() }
            val workspace = store.reader.workspaces(connection.id).first { it.id == SampleIds.STUDIO }
            val target = ChatTarget(connection.id, workspace.id, SampleIds.GENERAL)
            val sender = OutboxSender(store, engine, identity, flaky, tempDir())
            val a = sender.stageText("retry me", "Pasted text.txt", "paste")
            val item = sender.send("with file", emptyList(), target, connection, workspace, listOf(a))!!
            withTimeout(20_000) { engine.waitUntilIdle() }
            // The interrupted upload is retried by the sender's own backoff; a user retry on top changes nothing.
            sender.retry(item.id, connection)
            withTimeout(20_000) { engine.waitUntilIdle() }
            assertEquals("the interrupted upload was attempted", -1, failures)
            assertEquals("only the finished upload's message is sent", 1, messagePosts.get())
            assertTrue(store.reader.outbox(target).isEmpty())
            assertEquals(1, prepares.get())
            assertEquals(1, store.reader.latestMessages(workspace.id, SampleIds.GENERAL, 500).count { it.clientRequestId == item.id })
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    @Test fun repeatedPastesAreNumberedAndRemoveRenameAndEditKeepTheRest() = runBlocking {
        withRig { r, _, _, target ->
            val a = r.sender.addDraftText(target, "one")
            val b = r.sender.addDraftText(target, "two\r\n")
            val c = r.sender.addDraftText(target, "three")
            assertEquals(listOf("Pasted text.txt", "Pasted text 2.txt", "Pasted text 3.txt"), r.store.reader.draft(target)!!.attachments.map { it.filename })
            r.sender.removeDraftText(target, b.id)
            assertFalse(File(r.sender.staging!!, b.staged).exists())
            // The freed number is reused for the next paste.
            val d = r.sender.addDraftText(target, "four")
            assertEquals("Pasted text 2.txt", d.filename)
            val edited = r.sender.replaceDraftText(target, a.id, "one edited\r\nmore", "notes")!!
            assertEquals("notes.txt", edited.filename)
            assertEquals(TextAttachmentInfo(1, "paste", 16, 2), edited.text)
            assertFalse("old bytes removed", File(r.sender.staging!!, a.staged).exists())
            val list = r.store.reader.draft(target)!!.attachments
            assertEquals(listOf(a.id, c.id, d.id), list.map { it.id })
            assertEquals("one edited\r\nmore", r.sender.readStagedText(list[0]))
            assertEquals("three", r.sender.readStagedText(list[1]))
        }
    }

    @Test fun existingComposerTextAndMentionsSurviveAPaste() = runBlocking {
        withRig { r, _, _, target ->
            r.store.writer.saveDraft(target, Draft("keep this text", emptyList()))
            r.sender.addDraftText(target, "big")
            r.store.writer.saveDraft(target, Draft("keep this text!", emptyList()))
            val draft = r.store.reader.draft(target)!!
            assertEquals("keep this text!", draft.text)
            assertEquals(1, draft.attachments.size)
        }
    }

    @Test fun draftAttachmentsSurviveReopeningTheStore() = runBlocking {
        val dir = tempDir(); val staging = tempDir()
        var id = ""
        withRig(dir, staging) { r, _, _, target -> id = r.sender.addDraftText(target, "persist\r\n").id }
        withRig(dir, staging) { r, _, _, target ->
            val a = r.store.reader.draft(target)!!.attachments.single()
            assertEquals(id, a.id)
            assertEquals("persist\r\n", r.sender.readStagedText(a))
        }
    }

    @Test fun sentAttachmentIsEditedIntoVersionsAndConflictsKeepTheLocalEdit() = runBlocking {
        val identity = makeIdentity()
        withRig { r, c, w, target ->
            val text = "original text\r\nline 2"
            val a = r.sender.stageText(text, "Pasted text.txt", "paste")
            val item = r.sender.send("", emptyList(), target, c, w, listOf(a))!!
            withTimeout(20_000) { r.engine.waitUntilIdle() }
            val message = r.store.reader.latestMessages(w.id, SampleIds.GENERAL, 500).single { it.clientRequestId == item.id }
            val attachment = studio.otherstuff.ambulando.core.transport.MessageAttachment.from(message.raw).single()
            val api = studio.otherstuff.ambulando.core.transport.TowerAPI(
                studio.otherstuff.ambulando.core.transport.TowerClient(studio.otherstuff.ambulando.core.transport.TowerOrigin(c.origin), r.tower, identity),
            )
            val service = TextAttachmentService(TextEditStore(tempDir()), TextCache(tempDir()))
            fun begin(t: String, file: String?, base: String?) = service.beginEdit(
                studio.otherstuff.ambulando.core.transport.newRequestId(), c.id, w.id, message.channelId, w.appNpub, message.id, attachment, "Pasted text.txt", t, file, base,
            )
            val first = service.save(api, begin("edit one", null, null).id)
            assertEquals(TextEditState.SAVED, first.state)
            val second = service.save(api, begin("edit two", first.fileId, first.baseVersionId).id)
            assertEquals(TextEditState.SAVED, second.state)
            val history = service.history(api, w.id, w.appNpub, message.channelId, message.id, attachment)
            assertEquals(listOf("Original", "Edit 1", "Edit 2"), history.entries.map { it.label })
            assertEquals(text, TextAttachments.text(service.read(api, w.id, w.appNpub, history.fileId, history.entries[0])))
            assertEquals("edit one", TextAttachments.text(service.read(api, w.id, w.appNpub, history.fileId, history.entries[1])))
            // A stale base conflicts and keeps the edit; rebasing saves it as the newest version.
            val stale = service.save(api, begin("stale edit", first.fileId, first.baseVersionId).id)
            assertEquals(TextEditState.CONFLICT, stale.state)
            assertEquals("stale edit", service.edits.text(stale))
            service.rebase(api, stale.id)
            assertEquals(TextEditState.SAVED, service.save(api, stale.id).state)
            assertEquals(4, service.history(api, w.id, w.appNpub, message.channelId, message.id, attachment).entries.size)
            // The signed message and its original attachment are unchanged.
            val after = r.store.reader.latestMessages(w.id, SampleIds.GENERAL, 500).single { it.id == message.id }
            assertEquals(message.raw, after.raw)
        }
    }
}
