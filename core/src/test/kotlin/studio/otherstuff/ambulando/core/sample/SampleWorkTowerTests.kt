package studio.otherstuff.ambulando.core.sample

import studio.otherstuff.ambulando.core.surfaces.createBoardFile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.LeaseError
import studio.otherstuff.ambulando.core.transport.acquireLease
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.channelDocs
import studio.otherstuff.ambulando.core.transport.channelFiles
import studio.otherstuff.ambulando.core.transport.channelFolders
import studio.otherstuff.ambulando.core.transport.channelTasks
import studio.otherstuff.ambulando.core.transport.docBody
import studio.otherstuff.ambulando.core.transport.docComments
import studio.otherstuff.ambulando.core.transport.events
import studio.otherstuff.ambulando.core.transport.fileObject
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.reactions
import studio.otherstuff.ambulando.core.transport.releaseLease
import studio.otherstuff.ambulando.core.transport.renewLease
import studio.otherstuff.ambulando.core.transport.store
import studio.otherstuff.ambulando.core.transport.storageContent
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.task
import studio.otherstuff.ambulando.core.transport.taskComments
import studio.otherstuff.ambulando.core.transport.viewStates
import java.io.IOException

/**
 * Task, document, file, storage, lease, inbox and reaction routes of [SampleWorkTower] (the tower rules behind the iOS
 * `WorkTests`), driven through the real API wrappers and NIP-98 signing.
 */
class SampleWorkTowerTests {
    private val w = SampleIds.STUDIO
    private val general = SampleIds.GENERAL
    private val a = SampleIds.SCOPE_AMBULANDO

    private fun harness() = SampleHarness(prepare = false).also { it.tower.actorNpub = TestKeys.NPUB }

    @Test fun taskListIsPerChannelAndFollowsExternalEdits() = runBlocking {
        val h = harness()
        val alpha = h.tower.work.seedTask(scope = a, channel = general, title = "Alpha")
        h.tower.work.seedTask(scope = a, channel = SampleIds.DESIGN, title = "Beta", state = "in_progress")
        assertEquals(listOf("Alpha"), h.api.channelTasks(w, h.app, general).map { it.title })
        h.tower.work.externalTaskEdit(alpha, mapOf("channel_id" to SampleIds.DESIGN))
        assertTrue(h.api.channelTasks(w, h.app, general).isEmpty())
        assertEquals(setOf("Alpha", "Beta"), h.api.channelTasks(w, h.app, SampleIds.DESIGN).map { it.title }.toSet())
        assertTrue(h.api.channelTasks(SampleIds.LAB, h.app, SampleIds.NOTES).isEmpty())
    }

    @Test fun taskCreateReplaysAfterLostResponse() = runBlocking {
        val h = harness()
        h.tower.work.inject(SampleFault.DropResponseAfterCommit, "/channels/$general/tasks")
        val body = mapOf("title" to "Ship build 3", "state" to "ready", "priority" to "pebble", "client_request_id" to "5a000000-0000-4000-8000-00000000a001")
        try {
            h.exchange("POST", h.base() + "/channels/$general/tasks", body); fail("response lost")
        } catch (e: IOException) { assertTrue(e is SampleConnectionLostException) }
        val replay = h.exchange("POST", h.base() + "/channels/$general/tasks", body)
        assertEquals(200, replay.status)
        assertEquals(true, replay.json().bool("replayed"))
        assertEquals(listOf("Ship build 3"), h.api.channelTasks(w, h.app, general).map { it.title })
        val conflict = h.exchange("POST", h.base() + "/channels/$general/tasks", body + ("title" to "Other"))
        assertEquals(409 to "idempotency_conflict", conflict.status to conflict.json().string("code"))
    }

    @Test fun taskStateOfflineFaultThenApplied() = runBlocking {
        val h = harness()
        val id = h.tower.work.seedTask(scope = a, channel = general, title = "T")
        h.tower.work.inject(SampleFault.Offline, "/state")
        try {
            h.exchange("POST", h.base() + "/tasks/$id/state", mapOf("state" to "done")); fail("offline")
        } catch (e: IOException) { assertTrue(e is SampleOfflineException) }
        assertEquals("new", h.api.task(w, h.app, id).state)
        assertEquals(400, h.exchange("POST", h.base() + "/tasks/$id/state", mapOf("state" to "finished")).status)
        assertEquals(200, h.exchange("POST", h.base() + "/tasks/$id/state", mapOf("state" to "done")).status)
        assertEquals("done", h.api.task(w, h.app, id).state)
        // Each accepted change is an event.
        assertTrue(h.api.events(w, h.app, null).events.any { it.entityType == "task" && it.entityId == id && it.operation == "updated" })
    }

    @Test fun assignmentChecksRowVersionButTitleEditsDoNot() = runBlocking {
        val h = harness()
        val id = h.tower.work.seedTask(scope = a, channel = general, title = "Original", description = "d")
        h.tower.work.externalTaskEdit(id, mapOf("title" to "Theirs"))
        assertEquals(200, h.exchange("PATCH", h.base() + "/tasks/$id", mapOf("title" to "Mine")).status)
        val stale = h.exchange("PATCH", h.base() + "/tasks/$id", mapOf("assigned_to_npub" to TestKeys.NPUB, "row_version" to 1))
        assertEquals(409 to "stale_row_version", stale.status to stale.json().string("code"))
        val current = h.api.task(w, h.app, id)
        assertEquals(200, h.exchange("PATCH", h.base() + "/tasks/$id", mapOf("assigned_to_npub" to TestKeys.NPUB, "row_version" to current.rowVersion)).status)
        assertEquals(TestKeys.NPUB, h.api.task(w, h.app, id).assignedToNpub)
        assertEquals(400, h.exchange("PATCH", h.base() + "/tasks/$id", mapOf("priority" to "boulder")).status)
    }

    @Test fun commentsArePagedPerTaskAndRefuseEmptyBodies() = runBlocking {
        val h = harness()
        val id = h.tower.work.seedTask(scope = a, channel = general, title = "T")
        h.tower.work.seedTaskComment(id, "first", SampleIds.ANA)
        assertEquals(201, h.exchange("POST", h.base() + "/tasks/$id/comments", mapOf("body" to "  second ")).status)
        assertEquals(400, h.exchange("POST", h.base() + "/tasks/$id/comments", mapOf("body" to "   ")).status)
        val comments = h.api.taskComments(w, h.app, id)
        assertEquals(listOf("first", "second"), comments.map { it.body })
        assertEquals(TestKeys.NPUB, comments[1].senderNpub)
    }

    @Test fun documentBodyIsVerifiedAndSaveNeedsLeaseAndBase() = runBlocking {
        val h = harness()
        val body = "# Plan\n\nText".toByteArray()
        val doc = h.tower.work.seedDoc(scope = a, channel = general, title = "Plan", body = body, contentType = "text/markdown")
        assertEquals(listOf("Plan"), h.api.channelDocs(w, h.app, general).items.map { it.title })
        val (record, fetched) = h.api.docBody(w, h.app, doc)
        assertTrue(fetched.bytes.contentEquals(body))
        // A new body: store it, take the lease, save with the base.
        val next = "# Plan\n\nEdited".toByteArray()
        val objectId = h.api.store(w, h.app, next, "text/markdown", "plan.md", null) {}
        val noLease = h.exchange("PATCH", h.base() + "/docs/$doc", mapOf("storage_object_id" to objectId, "row_version" to record.rowVersion,
            "base_body_sha256_hex" to fetched.sha256, "lease_token" to "none"))
        assertEquals(409 to "lease_required", noLease.status to noLease.json().string("code"))
        val lease = h.api.acquireLease(w, h.app, "document", doc)
        val renewed = h.api.renewLease(w, h.app, lease)
        val saved = h.exchange("PATCH", h.base() + "/docs/$doc", mapOf("storage_object_id" to objectId, "row_version" to record.rowVersion,
            "base_body_sha256_hex" to fetched.sha256, "lease_token" to renewed.token))
        assertEquals(200, saved.status)
        assertEquals(Sha256Hex.of(next), saved.json().obj("canonical_version")!!.string("body_sha256_hex"))
        assertTrue(h.tower.work.docBody(doc)!!.contentEquals(next))
        // A stale base becomes a recovery copy.
        val stale = h.exchange("PATCH", h.base() + "/docs/$doc", mapOf("storage_object_id" to objectId, "row_version" to record.rowVersion,
            "base_body_sha256_hex" to fetched.sha256, "lease_token" to renewed.token))
        assertEquals(409 to "document_recovery_created", stale.status to stale.json().string("code"))
        assertEquals(1, h.tower.work.recoveryCount)
        h.api.releaseLease(w, h.app, renewed)
    }

    @Test fun externalSaveSwitchAndHeldLease() = runBlocking {
        val h = harness()
        val body = DocumentBody.newDocument("Original")
        val doc = h.tower.work.seedDoc(scope = a, channel = general, title = "D", body = body.bytes, contentType = body.contentType)
        h.tower.work.holdLease(doc, SampleIds.ORBIT)
        try {
            h.api.acquireLease(w, h.app, "document", doc); fail("held")
        } catch (e: LeaseError.Held) { assertEquals(SampleIds.ORBIT, e.by) }
        h.tower.work.releaseHeldLease(doc)
        val lease = h.api.acquireLease(w, h.app, "document", doc)
        val (record, fetched) = h.api.docBody(w, h.app, doc)
        h.tower.work.externalSaveBeforeNextDocBodySave = true
        val objectId = h.api.store(w, h.app, DocumentBody.newDocument("Mine").bytes, body.contentType, "d.json", null) {}
        val r = h.exchange("PATCH", h.base() + "/docs/$doc", mapOf("storage_object_id" to objectId, "row_version" to record.rowVersion,
            "base_body_sha256_hex" to fetched.sha256, "lease_token" to lease.token))
        assertEquals(409, r.status)
        assertFalse(h.tower.work.externalSaveBeforeNextDocBodySave)
        assertTrue(DocumentBody(h.tower.work.docBody(doc)!!, body.contentType).markdown.contains("Someone else"))
    }

    @Test fun documentCommentsAndStatus() = runBlocking {
        val h = harness()
        val doc = h.tower.work.seedDoc(scope = a, channel = general, title = "D", body = "x".toByteArray(), contentType = "text/markdown")
        val c = h.exchange("POST", h.base() + "/docs/$doc/comments", mapOf("body" to "Looks good")).json().obj("comment")!!
        val id = c.string("id")!!
        assertEquals(409, h.exchange("PATCH", h.base() + "/docs/$doc/comments/$id", mapOf("row_version" to 5, "comment_status" to "resolved")).status)
        assertEquals(200, h.exchange("PATCH", h.base() + "/docs/$doc/comments/$id", mapOf("row_version" to 1, "comment_status" to "resolved")).status)
        assertEquals("resolved", h.api.docComments(w, h.app, doc).single().status)
    }

    @Test fun createBoardRegistersAnEmptyExcalidrawFileInTheChannel() = runBlocking {
        val h = harness()
        val id = h.api.createBoardFile(w, h.app, general, "  Launch ideas ")
        val listed = h.api.channelFiles(w, h.app, general).items.single { it.id == id }
        assertEquals("Launch ideas.excalidraw", listed.displayName)
        val scene = studio.otherstuff.ambulando.core.transport.parseJsonObject(h.api.fileObject(w, h.app, id).bytes, "board")
        assertEquals("excalidraw", scene.string("type"))
        assertTrue("a new board starts empty", (scene["elements"] as kotlinx.serialization.json.JsonArray).isEmpty())
    }

    @Test fun uploadUsesUnsignedPresignedPutThenFileRecord() = runBlocking {
        val h = harness()
        val bytes = "quarterly numbers".toByteArray()
        val objectId = h.api.store(w, h.app, bytes, "text/plain", "numbers.txt", null) {}
        assertTrue("the identity never reaches the object store", h.tower.work.credentialLeaks.isEmpty())
        assertTrue(h.tower.work.requestLog.any { it.startsWith("PUT presigned") })
        val file = h.exchange("POST", h.base() + "/channels/$general/files", mapOf("storage_object_id" to objectId, "display_name" to "numbers.txt"))
        assertEquals(201, file.status)
        assertEquals(409, h.exchange("POST", h.base() + "/channels/$general/files", mapOf("storage_object_id" to objectId)).status)
        val listed = h.api.channelFiles(w, h.app, general).items.single()
        assertEquals("numbers.txt", listed.displayName)
        val content = h.api.fileObject(w, h.app, listed.id)
        assertTrue(content.bytes.contentEquals(bytes))
        assertTrue(h.api.storageContent(objectId).contentEquals(bytes))
        // A wrong hash is refused at completion.
        val prepared = h.exchange("POST", h.base() + "/storage/prepare", mapOf("content_type" to "text/plain", "file_name" to "x.txt")).json()
        val other = prepared.string("object_id")!!
        h.client.transport.send(studio.otherstuff.ambulando.core.transport.TransferPolicy.unsignedRequest(prepared.string("upload_url")!!, "PUT", "text/plain", "abc".toByteArray()))
        val bad = h.exchange("POST", "/api/v4/storage/$other/complete", mapOf("size_bytes" to 3, "sha256_hex" to Sha256Hex.of("abd")))
        assertEquals(409, bad.status)
    }

    @Test fun fallbackUploadIsSignedOnTheTowerOrigin() = runBlocking {
        val h = harness()
        h.tower.work.useFallbackUploads(true)
        val bytes = "fallback".toByteArray()
        val objectId = h.api.store(w, h.app, bytes, "text/plain", "f.txt", null) {}
        assertTrue(h.tower.work.completedObjectBytes(objectId)!!.contentEquals(bytes))
        assertTrue(h.tower.work.requestLog.any { it == "PUT /api/v4/storage/$objectId" })
    }

    @Test fun seededFoldersViewStatesAndMarkViewed() = runBlocking {
        val h = harness()
        val folder = h.tower.work.seedFolder(general, a, "Reports")
        h.tower.work.seedFile(general, a, "q.csv", "a,b\n".toByteArray(), "text/csv", folder)
        assertEquals(folder, h.api.channelFolders(w, h.app, general).items.single().id)
        val task = h.tower.work.seedTask(scope = a, channel = general, title = "T")
        h.tower.work.seedViewState("task", task, a, general, activity = 3, viewed = 1, attention = 3)
        assertEquals(3L, h.api.viewStates(w, h.app).single().activityVersion)
        assertEquals(409, h.exchange("PUT", h.base() + "/resource-view-states/task/$task", mapOf("viewed_activity_version" to 4)).status)
        assertEquals(200, h.exchange("PUT", h.base() + "/resource-view-states/task/$task", mapOf("viewed_activity_version" to 2)).status)
        val marked = h.exchange("POST", h.base() + "/resource-view-states/mark-viewed", mapOf("resources" to listOf(mapOf("resource_type" to "task", "resource_id" to task))))
        assertEquals(200, marked.status)
        assertEquals(3L, h.api.viewStates(w, h.app).single().viewedActivityVersion)
    }

    @Test fun reactionsAddRemove() = runBlocking {
        val h = harness()
        val target = SampleIds.message(1)
        assertEquals(400, h.exchange("POST", h.base() + "/reactions", mapOf("target_type" to "message", "target_id" to target, "emoji" to "rocket")).status)
        val added = h.exchange("POST", h.base() + "/reactions", mapOf("target_type" to "message", "target_id" to target, "emoji" to "heart"))
        assertEquals(201, added.status)
        val reactions = h.api.reactions(w, h.app, "message", target)
        assertEquals(listOf("heart"), reactions.map { it.emoji })
        assertEquals(200, h.exchange("DELETE", h.base() + "/reactions/${reactions[0].id}", null).status)
        assertTrue(h.api.reactions(w, h.app, "message", target).isEmpty())
        assertNotNull(h.tower.work.reactionRows())
    }
}
