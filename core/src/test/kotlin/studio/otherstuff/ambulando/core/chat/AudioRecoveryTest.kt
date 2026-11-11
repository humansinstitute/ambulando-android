package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.SyncEngine
import java.io.File
import java.util.UUID

class AudioRecoveryTest {
    @Test fun uploadedRecordingSurvivesConflictReopenAndExactRetryWithoutTouchingTypedDraft() = runBlocking {
        val directory = tempDir(); val staging = tempDir(); val engine = SyncEngine()
        val identity = makeIdentity()
        val c = ConnectionRecord("connection", Fixtures.ORIGIN, TestKeys.NPUB, TestKeys.NPUB, "")
        val target = ChatTarget(c.id, Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)
        val id = UUID.randomUUID().toString(); val bytes = "synthetic audio bytes".toByteArray()
        var frozen: ByteArray? = null
        try {
            LocalStore(directory).use { store ->
                store.writer.saveConnection(c)
                store.writer.saveDraft(target, Draft("Keep this typing", emptyList()))
                val sender = OutboxSender(store, engine, identity, StubTransport { error("No upload or message expected") }, staging)
                val attachment = sender.retainUploadedAudio(id, bytes, "Voice-$id.m4a", Fixtures.newId())
                val row = store.writer.enqueueSend(target, c.origin, c.signerNpub, Fixtures.APP_NPUB,
                    StorageMarkdown.file(attachment.objectId!!, attachment.filename), emptyList(), listOf(attachment),
                    id = id, preserveDraft = true, once = true)
                frozen = OutboxRequest.build(row, identity)
                store.writer.freezeOutbox(id, frozen!!)
                store.writer.markOutbox(id, OutboxState.FAILED, 409, "message_attachment_conflict", "Rejected")
                assertArrayEquals(bytes, File(staging, attachment.staged).readBytes())
            }
            LocalStore(directory).use { store ->
                store.writer.retryOutbox(id)
                val row = store.reader.outboxItem(id)!!
                assertEquals(OutboxState.QUEUED, row.state)
                assertArrayEquals(frozen, row.request)
                assertEquals(target, row.target)
                assertTrue(row.attachments.single().complete)
                assertArrayEquals(bytes, File(staging, row.attachments.single().staged).readBytes())
                assertEquals("Keep this typing", store.reader.draft(target)!!.text)
                assertEquals(1, store.reader.outbox(target).size)
            }
        } finally { engine.close() }
    }

    @Test fun priorBuildRecoveryChecksOriginalChannelKeepsSignatureAndCannotRewriteAmbiguousSend() = runBlocking {
        val directory = tempDir(); val engine = SyncEngine(); val identity = makeIdentity()
        val c = ConnectionRecord("connection", Fixtures.ORIGIN, TestKeys.NPUB, TestKeys.NPUB, "")
        val target = ChatTarget(c.id, Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)
        val objectId = Fixtures.newId(); val id = Fixtures.newId()
        val requests = mutableListOf<studio.otherstuff.ambulando.core.transport.HttpRequest>()
        val transport = StubTransport { request ->
            requests.add(request)
            if (request.method == "GET") {
                assertTrue(request.url.contains("/channels/${target.channelId}/files"))
                200 to mapOf("files" to listOf(mapOf("id" to Fixtures.newId(), "workspace_id" to target.workspaceId,
                    "channel_id" to target.channelId, "storage_object_id" to objectId)))
            } else {
                val sent = studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull(request.body!!)!!
                assertNull(sent["metadata"])
                201 to mapOf("message" to (Fixtures.message(Fixtures.newId(), target.channelId,
                    StorageMarkdown.file(objectId, "Voice.m4a"), Fixtures.newId()) + mapOf("client_request_id" to id)))
            }
        }
        try { LocalStore(directory).use { store ->
            store.writer.saveConnection(c)
            val attachment = PendingAttachment(id = id, filename = "Voice.m4a", mimeType = "audio/mp4", sizeBytes = 3,
                sha256 = "0".repeat(64), staged = "", objectId = objectId, complete = true, kind = "file")
            val item = store.writer.enqueueSend(target, c.origin, c.signerNpub, Fixtures.APP_NPUB,
                StorageMarkdown.file(objectId, attachment.filename), emptyList(), listOf(attachment), id = id, preserveDraft = true)
            val original = OutboxRequest.build(item, identity)
            store.writer.freezeOutbox(id, original)
            val sender = OutboxSender(store, engine, identity, transport)
            val frozen = store.reader.outboxItem(id)!!
            val replacement = sender.legacyAudioFileReference(frozen)!!
            val oldJson = studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull(original)!!
            val newJson = studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull(replacement)!!
            assertEquals(oldJson["message_signature"], newJson["message_signature"])
            assertEquals(oldJson["client_request_id"], newJson["client_request_id"])
            assertEquals(oldJson["body"], newJson["body"])
            store.writer.markOutbox(id, OutboxState.RETRYING, 503, "unavailable", "ambiguous")
            assertFalse(store.writer.recoverRejectedAudioRequest(id, original, replacement))
            assertArrayEquals(original, store.reader.outboxItem(id)!!.request)
            store.writer.markOutbox(id, OutboxState.FAILED, 409, "message_attachment_conflict", "rejected")
            sender.retry(id, c)
            kotlinx.coroutines.withTimeout(10_000) { engine.waitUntilIdle() }
            assertNull(store.reader.outboxItem(id))
            assertNotNull(store.reader.state("outbox.audio-file-recovery.$id"))
            assertEquals(listOf("GET", "POST"), requests.map { it.method })
            assertEquals(1, store.reader.latestMessages(target.workspaceId, target.channelId, 20).count { it.clientRequestId == id })
        } } finally { engine.close() }
    }

    @Test fun missingOriginalFileOrWrongConnectionCannotRetargetOrRewriteOldRecording() = runBlocking {
        val engine = SyncEngine(); val identity = makeIdentity()
        val c = ConnectionRecord("connection", Fixtures.ORIGIN, TestKeys.NPUB, TestKeys.NPUB, "")
        val target = ChatTarget(c.id, Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)
        val objectId = Fixtures.newId(); val id = Fixtures.newId()
        var calls = 0
        val transport = StubTransport { request ->
            calls++; assertEquals("GET", request.method)
            200 to mapOf("files" to emptyList<Any>())
        }
        try { LocalStore(tempDir()).use { store ->
            store.writer.saveConnection(c)
            val audio = PendingAttachment(id = id, filename = "Voice.m4a", mimeType = "audio/mp4", sizeBytes = 3,
                sha256 = "0".repeat(64), staged = "", objectId = objectId, complete = true, kind = "file")
            val item = store.writer.enqueueSend(target, c.origin, c.signerNpub, Fixtures.APP_NPUB,
                StorageMarkdown.file(objectId, audio.filename), emptyList(), listOf(audio), id = id)
            val frozen = OutboxRequest.build(item, identity)
            store.writer.freezeOutbox(id, frozen)
            store.writer.markOutbox(id, OutboxState.FAILED, 409, "message_attachment_conflict", "rejected")
            val sender = OutboxSender(store, engine, identity, transport)
            try { sender.retry(id, c.copy(id = "different")); fail("wrong connection") } catch (_: studio.otherstuff.ambulando.core.transport.TowerError) { }
            assertEquals(0, calls)
            sender.retry(id, c)
            assertEquals(1, calls)
            val kept = store.reader.outboxItem(id)!!
            assertEquals(OutboxState.FAILED, kept.state)
            assertArrayEquals(frozen, kept.request)
            assertTrue(kept.lastError!!.contains("could not be verified"))
            assertEquals(target, kept.target)
        } } finally { engine.close() }
    }

    @Test fun conflictsRemainFailuresAndDiagnosticsDoNotExposeServerText() {
        val server = """{"code":"message_attachment_conflict","message":"private object/person/server details","details":{"fields":[{"message":"private validation detail"}]}}""".toByteArray()
        val result = OutboxRequest.classify(409, server, 1) as SendOutcome.Permanent
        assertEquals("message_attachment_conflict", result.code)
        assertTrue(result.message.contains("POST channels/:channel/messages; HTTP 409; message_attachment_conflict"))
        assertFalse(result.message.contains("private"))
        assertTrue(OutboxRequest.classify(409, "{}".toByteArray(), 1) is SendOutcome.Permanent)
        val injected = OutboxRequest.classify(409, """{"code":"private /secret"}""".toByteArray(), 1) as SendOutcome.Permanent
        assertNull(injected.code)
        assertFalse(injected.message.contains("secret"))
    }
}
