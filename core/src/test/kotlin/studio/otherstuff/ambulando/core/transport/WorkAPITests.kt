package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.StubTransport
import studio.otherstuff.ambulando.core.support.decodeAuthorization
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.pathOf
import studio.otherstuff.ambulando.core.support.queryOf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/** Minimal storage routes: prepare, presigned or API-fallback upload, complete, and metadata. */
private class FakeStorage(val fallback: Boolean) {
    val objects = LinkedHashMap<String, MutableMap<String, Any?>>()
    var lastPut: HttpRequest? = null

    fun handle(request: HttpRequest): Pair<Int, Any?> {
        val path = pathOf(request.url)
        val body = request.body?.let { parseJsonObjectOrNull(it) }
        return when {
            path.endsWith("/storage/prepare") -> {
                val id = Fixtures.newId()
                val upload = if (fallback) "http://tower.example.com/api/v4/storage/$id" else "https://objects.example.com/bucket/$id?X-Amz-Signature=s"
                objects[id] = mutableMapOf("object_id" to id, "is_public" to false)
                200 to mapOf("object_id" to id, "upload_url" to upload, "is_public" to false)
            }
            request.method == "PUT" -> {
                lastPut = request
                val id = path.substringAfterLast('/')
                val bytes = if (fallback) Base64.getDecoder().decode(body!!.string("base64_data")) else request.body!!
                objects[id]!!["uploaded"] = bytes
                200 to emptyMap<String, Any>()
            }
            path.endsWith("/complete") -> {
                val id = path.removeSuffix("/complete").substringAfterLast('/')
                val bytes = objects[id]!!["uploaded"] as ByteArray
                objects[id]!!.putAll(mapOf("completed_at" to "2026-10-01T00:00:00.000Z", "sha256_hex" to Sha256Hex.of(bytes), "size_bytes" to bytes.size))
                200 to objects[id]!!.filterKeys { it != "uploaded" }
            }
            path.startsWith("/api/v4/storage/") -> objects[path.substringAfterLast('/')]?.let { 200 to it.filterKeys { k -> k != "uploaded" } }
                ?: (404 to mapOf("code" to "not_found"))
            else -> 404 to mapOf("code" to "not_found")
        }
    }
}

class WorkAPITests {
    private val origin = TowerOrigin(Fixtures.ORIGIN)

    @Test fun presignedUploadIsUnsignedAndTowerVerifiesTheResult() = runBlocking {
        val storage = FakeStorage(fallback = false)
        val transport = StubTransport { storage.handle(it) }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        var persisted: PreparedObject? = null
        val bytes = "quarterly numbers".toByteArray()
        val id = api.store(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, bytes, "text/plain", "numbers.txt", null) { persisted = it }
        assertEquals(id, persisted?.objectId)
        val put = storage.lastPut!!
        assertEquals("objects.example.com", put.url.toHttpUrlOrNull()!!.host)
        assertNull("the identity never reaches the object store", put.header("Authorization"))
        assertEquals("text/plain", put.header("Content-Type"))
        val prepare = transport.recorded.first { pathOf(it.url).endsWith("/storage/prepare") }
        val sent = parseJsonObject(prepare.body!!, "prepare")
        assertEquals(listOf("content_type", "file_name", "is_public", "sha256_hex", "size_bytes"), sent.keys.toList())
        assertEquals(Sha256Hex.of(bytes), sent.string("sha256_hex"))
        // A finished upload is not repeated when resumed.
        val puts = transport.recorded.count { it.method == "PUT" }
        api.store(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, bytes, "text/plain", "numbers.txt", persisted) { error("not prepared again") }
        assertEquals(puts, transport.recorded.count { it.method == "PUT" })
    }

    @Test fun fallbackUploadIsSignedOnPinnedOriginAndUnsafeURLsAreRefused() = runBlocking {
        val storage = FakeStorage(fallback = true)
        val transport = StubTransport { storage.handle(it) }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        val objectId = api.store(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "x".toByteArray(), "text/plain", "x.txt", null) {}
        val put = transport.recorded.first { it.method == "PUT" }
        assertEquals("https://tower.example.com/api/v4/storage/$objectId", put.url)
        val event = decodeAuthorization(put)
        assertTrue("NIP-98 covers the exact body", event.tags.contains(listOf("payload", Sha256Hex.of(put.body!!))))

        for (unsafe in listOf(
            "https://tower.example.com/api/v4/flightdeck-pg/workspaces/x", "http://objects.elsewhere.example.com/b/$objectId",
            "https://user:pw@objects.example.com/b/$objectId", "https://objects.example.com/api/v4/storage/$objectId",
            "https://tower.example.com/api/v4/storage/$objectId?x=1", "ftp://objects.example.com/b",
        )) {
            expectError(FileTransferError.UnsafeUploadURL) { api.upload(PreparedObject(objectId, unsafe), "y".toByteArray(), "text/plain") }
        }
        expectError(FileTransferError.FallbackTooLarge) {
            api.upload(PreparedObject(objectId, "https://tower.example.com/api/v4/storage/$objectId"), ByteArray(FALLBACK_UPLOAD_LIMIT + 1), "x")
        }
    }

    @Test fun transferPolicyAcceptsOnlyPresignedHTTPSOnAnotherHost() {
        TransferPolicy.validate("https://objects.example.com/bucket/obj?X-Amz-Signature=abc", "tower.example.com")
        for (raw in listOf(
            "https://tower.example.com/api/v4/storage/x", "https://TOWER.example.com/upload", "http://objects.example.com/x",
            "https://u:p@objects.example.com/x", "https://objects.example.com/api/v4/x", "https://objects.example.com/x#f", "not a url",
        )) {
            expectThrows(TransferError.UnsafeURL) { TransferPolicy.validate(raw, "tower.example.com") }
        }
        val request = TransferPolicy.unsignedRequest("https://objects.example.com/a", "PUT", "image/png", byteArrayOf(1))
        assertNull(request.header("Authorization"))
        assertEquals("image/png", request.header("content-type"))
    }

    @Test fun documentBodyIsVerifiedAgainstTowersHash() = runBlocking {
        val docId = Fixtures.newId()
        val objectId = Fixtures.newId()
        val bytes = "{\"type\":\"doc\"}".toByteArray()
        var reported = Sha256Hex.of(bytes)
        val transport = StubTransport {
            200 to mapOf(
                "doc" to mapOf("id" to docId, "workspace_id" to Fixtures.WORKSPACE_ID, "channel_id" to Fixtures.CHANNEL_1, "storage_object_id" to objectId, "row_version" to "7"),
                "body" to mapOf("encoding" to "base64", "base64_data" to Base64.getEncoder().encodeToString(bytes), "object_id" to objectId.uppercase(), "sha256_hex" to reported, "content_type" to "application/json"),
            )
        }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        val (doc, body) = api.docBody(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, docId)
        assertEquals(7L, doc.rowVersion)
        assertEquals(7L, body.docRowVersion)
        assertTrue(bytes.contentEquals(body.bytes))
        assertTrue(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z").matches(body.fetchedAt))
        reported = "00".repeat(32)
        expectError(DocumentBodyError.Integrity) { api.docBody(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, docId) }
    }

    @Test fun fileObjectChecksHashAndWorkspace() = runBlocking {
        val fileId = Fixtures.newId()
        val bytes = "content".toByteArray()
        var workspace = Fixtures.WORKSPACE_ID
        val transport = StubTransport {
            200 to mapOf(
                "file" to mapOf("id" to fileId, "workspace_id" to workspace, "channel_id" to Fixtures.CHANNEL_1, "display_name" to "a.txt"),
                "object" to mapOf("encoding" to "base64", "base64_data" to Base64.getEncoder().encodeToString(bytes), "sha256_hex" to Sha256Hex.of(bytes), "file_name" to "a.txt"),
            )
        }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        val file = api.fileObject(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, fileId)
        assertTrue(bytes.contentEquals(file.bytes))
        assertEquals("a.txt", file.fileName)
        workspace = Fixtures.WORKSPACE_ID_2
        expectError(TowerError.Malformed("file")) { api.fileObject(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, fileId) }
    }

    @Test fun leasesClassifyHeldAndExpired() = runBlocking {
        val docId = Fixtures.newId()
        var status = 409
        val transport = StubTransport { request ->
            when {
                pathOf(request.url).endsWith("/acquire") && status == 409 ->
                    409 to mapOf("code" to "lease_held", "details" to mapOf("holder_actor_npub" to Fixtures.APP_NPUB, "expires_at" to "later"))
                pathOf(request.url).endsWith("/acquire") ->
                    200 to mapOf("lease" to mapOf("id" to "00000000-0000-4000-8000-0000000000ee", "lease_token" to "tok", "entity_id" to docId.uppercase(), "expires_at" to "soon"))
                pathOf(request.url).endsWith("/renew") -> 409 to mapOf("code" to "lease_expired")
                else -> 200 to emptyMap<String, Any>()
            }
        }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        expectError(LeaseError.Held(Fixtures.APP_NPUB, "later")) { api.acquireLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "doc", docId) }
        status = 200
        val lease = api.acquireLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "doc", docId)
        assertEquals(EditLease("00000000-0000-4000-8000-0000000000ee", "tok", "soon"), lease)
        val body = parseJsonObject(transport.recorded.last().body!!, "lease")
        assertEquals(listOf("entity_id", "entity_type", "ttl_seconds"), body.keys.toList())
        assertEquals("120", body["ttl_seconds"].toString())
        expectError(LeaseError.Invalid("lease_expired")) { api.renewLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, lease) }
        api.releaseLease(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, lease)
        assertTrue(pathOf(transport.recorded.last().url).endsWith("/edit-leases/${lease.id}/release"))
    }

    @Test fun messageNotFoundIsNullAndReactionsAreFiltered() = runBlocking {
        val target = Fixtures.newId()
        val transport = StubTransport { request ->
            if (pathOf(request.url).contains("/messages/")) 404 to mapOf("code" to "message_not_found")
            else 200 to mapOf(
                "reactions" to listOf(
                    mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "target_id" to target, "emoji" to "heart"),
                    mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID_2, "target_id" to target, "emoji" to "eyes"),
                    mapOf("id" to "bad"),
                ),
            )
        }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        assertNull(api.message(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.newId()))
        val reactions = api.reactions(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "message", target)
        assertEquals(listOf("heart"), reactions.map { it.emoji })
        assertEquals(listOf("target_type", "target_id", "limit"), queryOf(transport.recorded.last().url).keys.toList())
    }

    @Test fun pagedTaskListStaysInItsChannel() = runBlocking {
        val transport = StubTransport { request ->
            val cursor = queryOf(request.url)["cursor"]
            val task = { id: String, channel: String -> mapOf("id" to id, "workspace_id" to Fixtures.WORKSPACE_ID, "channel_id" to channel, "title" to "T", "row_version" to 2) }
            if (cursor == null) 200 to mapOf("tasks" to listOf(task(Fixtures.newId(), Fixtures.CHANNEL_1)), "next_cursor" to "n")
            else 200 to mapOf("tasks" to listOf(task(Fixtures.newId(), Fixtures.CHANNEL_1)), "next_cursor" to "")
        }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        val tasks = api.channelTasks(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.CHANNEL_1)
        assertEquals(2, tasks.size)
        assertEquals(TaskState.NEW, tasks[0].taskState)
        assertEquals("sand", tasks[0].priority)
        transport.setHandler {
            200 to mapOf("tasks" to listOf(mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "channel_id" to Fixtures.CHANNEL_2)))
        }
        expectError(TowerError.Malformed("tasks")) { api.channelTasks(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.CHANNEL_1) }
    }
}

class EventsAPITests {
    private val origin = TowerOrigin(Fixtures.ORIGIN)

    private fun event(row: Int, workspace: String = Fixtures.WORKSPACE_ID, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "row_version" to row.toString(), "cursor" to "c$row", "workspace_id" to workspace, "entity_type" to "message",
        "entity_id" to Fixtures.newId(), "operation" to "updated",
    ) + extra

    @Test fun eventPagesSkipUnparseableRowsAndAdvanceTheCursor() = runBlocking {
        val thread = Fixtures.newId()
        val transport = StubTransport {
            200 to mapOf("events" to listOf(event(1, extra = mapOf("payload" to mapOf("thread_id" to thread, "channel_id" to Fixtures.CHANNEL_1))), mapOf("cursor" to "c2")))
        }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        val page = api.events(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, cursor = "c0", limit = 999)
        assertEquals(1, page.events.size)
        assertEquals(1, page.skipped)
        assertEquals("c2", page.nextCursor)
        assertEquals(thread, page.events[0].threadId)
        assertEquals(Fixtures.CHANNEL_1, page.events[0].channelId)
        assertEquals(listOf("cursor", "limit"), queryOf(transport.recorded[0].url).keys.toList())
        assertEquals("200", queryOf(transport.recorded[0].url)["limit"])

        transport.setHandler { 200 to mapOf("events" to listOf(event(1, workspace = Fixtures.WORKSPACE_ID_2))) }
        expectError(TowerError.Malformed("events")) { api.events(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, null) }

        transport.setHandler { 200 to mapOf("through_cursor" to "head-9") }
        assertEquals("head-9", api.eventHead(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB))
        transport.setHandler { 200 to mapOf("through_cursor" to "") }
        expectError(TowerError.Malformed("sync cursor")) { api.eventHead(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB) }
    }

    @Test fun towerEventIdsAreValidatedUUIDs() {
        val parsed = TowerEvent.parse(Fixtures.obj(event(3, extra = mapOf("entity_id" to "../x", "payload" to mapOf("task_id" to Fixtures.SCOPE_A.uppercase())))))!!
        assertNull(parsed.entityId)
        assertEquals(Fixtures.SCOPE_A, parsed.parentId)
        assertEquals(3L, parsed.rowVersion)
        assertNull(TowerEvent.parse(Fixtures.obj(event(3) + ("cursor" to ""))))
        assertNull(TowerEvent.parse(Fixtures.obj(event(3) + ("workspace_id" to "w"))))
    }
}

class SSEParserTests {
    @Test fun framesAcrossChunksLineEndingsAndComments() {
        val parser = SSEParser()
        val stream = "event: connected\r\ndata: {\"cursor\":\"a\"}\r\n\r\n: heartbeat\n\nid: 7\nevent: flightdeck_pg.event\ndata: line1\ndata: line2\n\n"
        val frames = ArrayList<SSEFrame>()
        for (byte in stream.toByteArray()) frames += parser.feed(byteArrayOf(byte))   // worst case: one byte per chunk
        assertEquals(listOf(SSEFrame("connected", "{\"cursor\":\"a\"}"), SSEFrame("flightdeck_pg.event", "line1\nline2", "7")), frames)
        assertEquals(1, parser.takeComments())
        assertEquals(0, parser.takeComments())
        // CR-only line ends and a frame without `event` (default "message").
        assertEquals(listOf(SSEFrame("message", "x")), parser.feed("data:x\r\r".toByteArray()))
        // Multi-byte UTF-8 split across chunks.
        val bytes = "data: é✓\n\n".toByteArray()
        assertTrue(parser.feed(bytes.copyOfRange(0, 7)).isEmpty())
        assertEquals(listOf(SSEFrame("message", "é✓")), parser.feed(bytes.copyOfRange(7, bytes.size)))
    }

    @Test fun unterminatedLineIsBounded() {
        val parser = SSEParser()
        try {
            parser.feed(ByteArray(SSEParser.MAX_LINE + 1) { 0x61 })
            org.junit.Assert.fail("expected LineTooLong")
        } catch (e: SSEParser.LineTooLong) {
            assertNotNull(e.message)
        }
    }
}

class BoundedChunkBufferTests {
    @Test fun deliversInOrderWithinLimit() = runBlocking {
        val buffer = BoundedChunkBuffer(10)
        assertTrue(buffer.append("abcd".toByteArray()))
        assertTrue(buffer.append("efgh".toByteArray()))
        assertEquals(8, buffer.bufferedBytes)
        assertEquals("abcd", String(buffer.next()!!))
        assertEquals("efgh", String(buffer.next()!!))
        assertEquals(0, buffer.bufferedBytes)
        buffer.finish()
        assertNull(buffer.next())
        assertFalse("finished buffers refuse more", buffer.append(byteArrayOf(1)))
    }

    @Test fun slowConsumerOverflowFailsInsteadOfDropping() = runBlocking {
        val buffer = BoundedChunkBuffer(10)
        assertTrue(buffer.append(ByteArray(6) { 1 }))
        assertFalse("producer told to stop", buffer.append(ByteArray(6) { 2 }))
        assertFalse(buffer.append(ByteArray(1) { 3 }))
        try {
            buffer.next()
            org.junit.Assert.fail("overflow must surface")
        } catch (e: BoundedChunkBuffer.Overflow) {
            assertNotNull(e)
        }
    }

    @Test fun waitingConsumerGetsChunkDirectly() = runBlocking {
        val buffer = BoundedChunkBuffer(4)
        val pending = async(kotlinx.coroutines.Dispatchers.Default) { buffer.next() }
        kotlinx.coroutines.delay(50)
        assertTrue(buffer.append(ByteArray(4) { 7 }))
        assertEquals(4, pending.await()?.size)
    }

    @Test fun failureAfterChunksIsReportedOnceTheyAreRead() = runBlocking {
        val buffer = BoundedChunkBuffer(10)
        buffer.append("ab".toByteArray())
        buffer.finish(java.io.IOException("reset"))
        assertEquals("ab", String(buffer.next()!!))
        try {
            buffer.next()
            org.junit.Assert.fail()
        } catch (e: java.io.IOException) {
            assertEquals("reset", e.message)
        }
    }
}
