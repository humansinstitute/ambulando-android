package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.UploadToken
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.EditLease
import java.net.UnknownHostException
import java.time.Instant

/** The save flow against an in-memory Tower and store: lease first, draft kept until Tower accepts, no overwrites. */
class DocumentSaverTest {
    private val workspace = "00000000-0000-4000-8000-0000000000a1"
    private val channel = "00000000-0000-4000-8000-0000000000a2"
    private val docId = "00000000-0000-4000-8000-0000000000a3"
    private val target = DocSaveTarget("connection-1", workspace, "npub-app")

    /** A small Tower: one document head, lease holder, storage objects and recovery copies. */
    private inner class FakeTower : DocumentSaveApi {
        var headVersion = 1
        var headSha = ""
        var headBody = ByteArray(0)
        var heldBy: String? = null
        var leaseToken: String? = null
        var offlineStorage = false
        var recoveries = 0
        val objects = mutableMapOf<String, ByteArray>()
        val patches = mutableListOf<JsonObject>()
        var released = 0

        override suspend fun acquireLease(workspaceId: String, appNpub: String, entityType: String, entityId: String): EditLease {
            heldBy?.let { throw LeaseException.Held(it, "2026-01-01T00:00:00Z") }
            val token = "token-${patches.size}-${objects.size}"
            leaseToken = token
            return EditLease("00000000-0000-4000-8000-0000000000b1", token, "2026-01-01T00:02:00Z")
        }

        override suspend fun renewLease(workspaceId: String, appNpub: String, lease: EditLease): EditLease {
            if (lease.token != leaseToken) throw LeaseException.Invalid("lease_expired")
            return lease.copy(expiresAt = "2026-01-01T00:04:00Z")
        }

        override suspend fun releaseLease(workspaceId: String, appNpub: String, lease: EditLease) {
            released++
            if (leaseToken == lease.token) leaseToken = null
        }

        override suspend fun storeObject(workspaceId: String, appNpub: String, bytes: ByteArray, contentType: String, fileName: String): String {
            if (offlineStorage) throw UnknownHostException("offline")
            val id = "00000000-0000-4000-8000-%012d".format(objects.size + 1)
            objects[id] = bytes
            return id
        }

        override suspend fun patchDocument(workspaceId: String, appNpub: String, docId: String, body: JsonObject): SaveReply {
            patches.add(body)
            if (body["lease_token"]?.jsonPrimitive?.content != leaseToken) return reply(409, """{"code":"lease_invalid"}""")
            val base = body["row_version"]!!.jsonPrimitive.int
            val sha = body["base_body_sha256_hex"]!!.jsonPrimitive.content
            if (base != headVersion || sha != headSha) {
                recoveries++
                return reply(409, """{"code":"document_recovery_created","recovery":{"id":"00000000-0000-4000-8000-0000000000c1"}}""")
            }
            headBody = objects[body["storage_object_id"]!!.jsonPrimitive.content]!!
            headVersion++
            headSha = Sha256Hex.of(headBody)
            return reply(200, """{"doc":{"id":"$docId","workspace_id":"$workspace","channel_id":"$channel","row_version":$headVersion},"canonical_version":{"body_sha256_hex":"${headSha.uppercase()}"}}""")
        }

        private fun reply(status: Int, json: String) = SaveReply(status, json.toByteArray())

        /** Someone else saves a new head. */
        fun externalSave(bytes: ByteArray) { headBody = bytes; headVersion++; headSha = Sha256Hex.of(bytes) }
    }

    private class MemoryStore : DocDraftStore {
        val drafts = mutableMapOf<String, DocDraft>()
        var savedBody: SavedDocBody? = null
        var searchText: String? = null
        override suspend fun saveDocDraft(draft: DocDraft) { drafts[draft.key] = draft }
        override suspend fun deleteDocDraft(key: String, workspaceId: String) { drafts.remove(key) }
        override suspend fun saveDocBody(body: SavedDocBody, doc: SavedDocument, searchText: String) { savedBody = body; this.searchText = searchText }
    }

    private class Locked : Exception(), UserFacingError { override val userMessage = "Unlock to continue." }

    private fun setup(): Triple<FakeTower, MemoryStore, DocumentSaver> {
        val tower = FakeTower()
        val original = """{"format":"document_content_v1","content_model":${JsonValue.Obj(ProseMirrorMarkdown.contentModelFields(
            JsonValue.parse("""{"type":"doc","content":[{"type":"paragraph","attrs":{"fdBlockId":"b1"},"content":[{"type":"text","text":"Hello"}]}]}"""))).serializedString()
            .dropLast(1)},"references":["r1"]},"future_field":{"keep":true}}"""
        tower.headBody = original.toByteArray()
        tower.headSha = Sha256Hex.of(tower.headBody)
        val store = MemoryStore()
        val saver = DocumentSaver(store, { tower }, now = { Instant.parse("2026-01-01T00:00:00Z") })
        return Triple(tower, store, saver)
    }

    private fun draft(tower: FakeTower, text: String): DocDraft {
        val body = DocumentBody(tower.headBody, DocumentBody.ENVELOPE_CONTENT_TYPE)
        val state = body.editorState!!
        val edited = DocumentEditor.setText(state, DocumentEditor.blocks(state)[0].path, text)
        val encoded = body.encoded(editorState = edited)
        return DocDraft(target.connectionId, workspace, docId, "Plan: Q1/notes", encoded.bytes, encoded.contentType, tower.headVersion,
            tower.headSha, "00000000-0000-4000-8000-0000000000d1", DocDraft.State.EDITING)
    }

    @Test fun saveUsesLeaseAndPreservesUnknownContent() = runTest {
        val (tower, store, saver) = setup()
        val edited = draft(tower, "Hello from Android")
        store.saveDocDraft(edited)
        val result = saver.save(edited, target)
        assertTrue("not saved: $result", result is DocSaveResult.Saved)
        result as DocSaveResult.Saved
        assertEquals(2, result.doc.rowVersion)
        assertEquals("canonical sha is taken from Tower, lower-cased", tower.headSha, result.body.sha256)
        assertNull("the draft is removed only after Tower accepts", store.drafts[edited.key])
        assertEquals("Hello from Android", store.searchText)
        val root = JsonValue.parse(tower.headBody)
        assertEquals(JsonValue.Bool(true), root["future_field"]?.get("keep"))
        assertEquals(JsonValue.Arr(listOf(JsonValue.Str("r1"))), root["content_model"]?.get("references"))
        assertEquals("Hello from Android", root["content_model"]?.get("content")?.stringValue)
        val patch = tower.patches.last()
        assertEquals(1, patch["row_version"]!!.jsonPrimitive.int)
        assertEquals(tower.leaseToken, patch["lease_token"]!!.jsonPrimitive.content)
        assertEquals(edited.baseSha256, patch["base_body_sha256_hex"]!!.jsonPrimitive.content)
        assertEquals("Plan_Q1_notes-$docId.document.json", DocumentSaver.fileName(edited))
    }

    @Test fun staleSaveBecomesRecoveryAndKeepsDraft() = runTest {
        val (tower, store, saver) = setup()
        val edited = draft(tower, "Mine")
        store.saveDocDraft(edited)
        tower.externalSave(DocumentBody.newDocument("Theirs").bytes)
        val result = saver.save(edited, target)
        assertTrue("expected recovery: $result", result is DocSaveResult.Recovery)
        assertEquals("00000000-0000-4000-8000-0000000000c1", (result as DocSaveResult.Recovery).id)
        assertEquals(1, tower.recoveries)
        assertEquals("the head was not overwritten", "Theirs", DocumentBody(tower.headBody, null).markdown)
        val kept = store.drafts[edited.key]!!
        assertEquals(DocDraft.State.RECOVERY, kept.state)
        assertTrue("the unsaved edit is preserved on the device", kept.body.contentEquals(edited.body))
    }

    @Test fun heldLeaseAndOfflineKeepTheDraft() = runTest {
        val (tower, store, saver) = setup()
        val edited = draft(tower, "Draft")
        store.saveDocDraft(edited)
        tower.heldBy = "npub1" + "q".repeat(58)
        val held = saver.save(edited, target)
        assertTrue("lease should be held: $held", held is DocSaveResult.LeaseHeld)
        assertTrue((held as DocSaveResult.LeaseHeld).message.contains("(npub1qqqqq…qqqq)"))
        assertTrue("nothing saved without the lease", tower.patches.isEmpty())
        assertEquals(DocDraft.State.EDITING, store.drafts[edited.key]!!.state)
        tower.heldBy = null
        tower.offlineStorage = true
        val offline = saver.save(edited, target)
        assertEquals(DocSaveResult.NotSaved("Not saved yet: Offline; will send when the connection returns."), offline)
        assertEquals(DocDraft.State.EDITING, store.drafts[edited.key]!!.state)
        tower.offlineStorage = false
        assertTrue(saver.save(edited, target) is DocSaveResult.Saved)
        saver.acquire(target, docId)
        saver.release(target, docId)
        assertFalse(saver.hasLease(workspace, docId))
        assertEquals(1, tower.released)
    }

    @Test fun lostLeaseIsForgottenAndFailuresAreClassified() = runTest {
        val (tower, store, saver) = setup()
        val edited = draft(tower, "Again")
        saver.acquire(target, docId)
        tower.leaseToken = "replaced"
        val lost = saver.save(edited, target)
        assertEquals(DocSaveResult.NotSaved("The edit lease is no longer valid (lease_invalid). Reopen the editor to continue."), lost)
        assertFalse("a lease Tower no longer honours is forgotten", saver.hasLease(workspace, docId))
        // Renewing an invalid lease forgets it too.
        saver.acquire(target, docId)
        tower.leaseToken = "other"
        try { saver.renew(target, docId) } catch (_: LeaseException.Invalid) {}
        assertFalse(saver.hasLease(workspace, docId))
        // Classification of Tower's answers.
        val row = DocumentSaver.savedDocument(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"${docId.uppercase()}","workspace_id":"$workspace","channel_id":"$channel","row_version":"7"}""") as JsonObject)!!
        assertEquals(docId, row.id)
        assertEquals(7, row.rowVersion)
        assertEquals(DocumentSaver.Outcome.SAVED, DocumentSaver.classify(200, null, row, target, docId))
        assertEquals("a 2xx for another document is not a save", DocumentSaver.Outcome.FAILED,
            DocumentSaver.classify(200, null, row.copy(workspaceId = "00000000-0000-4000-8000-0000000000ff"), target, docId))
        assertEquals(DocumentSaver.Outcome.STALE, DocumentSaver.classify(409, "stale_row_version", null, target, docId))
        assertEquals(DocumentSaver.Outcome.RECOVERY, DocumentSaver.classify(409, "document_recovery_created", null, target, docId))
        assertEquals(DocumentSaver.Outcome.LEASE_LOST, DocumentSaver.classify(409, "lease_expired", null, target, docId))
        assertEquals("You do not have permission to edit this document.", DocumentSaver.failureMessage(403, null))
        assertEquals("Tower is unavailable (HTTP 503); will retry.", DocumentSaver.failureMessage(503, null))
        assertEquals("Tower rejected this (HTTP 422, bad_title).", DocumentSaver.failureMessage(422,
            kotlinx.serialization.json.Json.parseToJsonElement("""{"code":"bad_title"}""") as JsonObject))
        assertTrue(store.drafts.values.all { it.body.contentEquals(edited.body) })
    }

    @Test fun refusesForeignDraftsUnresolvedUploadsAndLockedIdentity() = runTest {
        val (tower, store, _) = setup()
        val edited = draft(tower, "x")
        val saver = DocumentSaver(store, { throw Locked() })
        assertEquals(DocSaveResult.NotSaved("This draft belongs to another workspace."),
            saver.save(edited, target.copy(workspaceId = "00000000-0000-4000-8000-0000000000ff")))
        val pending = DocDraft(edited.connectionId, workspace, docId, "t", DocumentBody.newDocument("a ${UploadToken.make(UploadToken.Kind.IMAGE, "u1")}").bytes,
            DocumentBody.ENVELOPE_CONTENT_TYPE, 1, tower.headSha, "", DocDraft.State.EDITING)
        assertEquals(DocSaveResult.NotSaved("Wait for pasted items to finish uploading, or remove them."), saver.save(pending, target))
        assertEquals(DocSaveResult.NotSaved("Not saved yet: Unlock to continue."), saver.save(edited, target))
        assertEquals(DocDraft.State.EDITING, store.drafts[edited.key]!!.state)
        assertEquals("Not saved yet: Unlock to continue.", store.drafts[edited.key]!!.lastError)
    }
}
