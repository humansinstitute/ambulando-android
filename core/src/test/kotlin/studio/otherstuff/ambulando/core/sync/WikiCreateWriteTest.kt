package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.docs.WikiBindError
import studio.otherstuff.ambulando.core.docs.WikiCreate
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.WriteState
import studio.otherstuff.ambulando.core.store.enqueueWrite
import studio.otherstuff.ambulando.core.store.retryWrite
import studio.otherstuff.ambulando.core.store.saveConnection
import studio.otherstuff.ambulando.core.store.workspaceWrites
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.util.Base64
import java.util.UUID

/** "Create and link" as a durable write: one page, however many retries, and the source's link recorded before the write is retired. */
class WikiCreateWriteTest {
    /** A Tower that stores objects and channel documents. */
    private class DocsTower : HttpTransport {
        class Obj(val sha: String, val size: Long, var bytes: ByteArray? = null, var completed: Boolean = false)
        val objects = LinkedHashMap<String, Obj>()
        val docs = LinkedHashMap<String, JsonObject>()
        var offline = false
        var loseNextCreateAnswer = false
        var hideFromChannelList = false
        var creates = 0

        private fun json(status: Int, body: Any?) = HttpResponse(status, toJsonElement(body).toString().toByteArray())

        override suspend fun send(request: HttpRequest): HttpResponse {
            if (offline) throw java.net.ConnectException("offline")
            val path = request.url.substringAfter("//").substringAfter('/').substringBefore('?').let { "/$it" }
            val body = request.body?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(String(it)).jsonObject }.getOrNull() }
            val base = "/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}"
            when {
                request.method == "POST" && path == "$base/storage/prepare" -> {
                    val id = UUID.randomUUID().toString()
                    objects[id] = Obj(body!!["sha256_hex"]!!.jsonPrimitive.content, body["size_bytes"]!!.jsonPrimitive.content.toLong())
                    return json(200, mapOf("object_id" to id, "upload_url" to "${Fixtures.ORIGIN}/api/v4/storage/$id", "is_public" to false))
                }
                path.startsWith("/api/v4/storage/") -> {
                    val rest = path.removePrefix("/api/v4/storage/").split('/')
                    val id = rest[0]
                    val o = objects[id] ?: return json(404, mapOf("error" to "not_found"))
                    return when {
                        request.method == "PUT" -> { o.bytes = Base64.getDecoder().decode(body!!["base64_data"]!!.jsonPrimitive.content); json(200, mapOf("ok" to true)) }
                        request.method == "POST" && rest.getOrNull(1) == "complete" -> { o.completed = true; json(200, mapOf("completed_at" to "2026-10-26T00:00:00.000Z")) }
                        request.method == "GET" -> json(200, mapOf("id" to id, "completed_at" to if (o.completed) "2026-10-26T00:00:00.000Z" else null,
                            "sha256_hex" to o.sha, "size_bytes" to o.size, "is_public" to false))
                        else -> json(400, mapOf("error" to "bad"))
                    }
                }
                request.method == "GET" && path.substringBeforeLast('/').endsWith("/docs") ->
                    return docs[path.substringAfterLast('/')]?.let { json(200, mapOf("doc" to it)) } ?: json(404, mapOf("error" to "not_found"))
                request.method == "GET" && path.endsWith("/docs") -> return json(200, mapOf("docs" to (if (hideFromChannelList) emptyList() else docs.values.toList()), "next_cursor" to null))
                request.method == "POST" && path.endsWith("/docs") -> {
                    creates++
                    val channel = path.removeSuffix("/docs").substringAfterLast('/')
                    val id = UUID.randomUUID().toString()
                    val row = buildJsonObject {
                        put("id", id); put("workspace_id", Fixtures.WORKSPACE_ID); put("channel_id", channel)
                        put("title", body!!["title"]!!.jsonPrimitive.content); put("storage_object_id", body["storage_object_id"]!!.jsonPrimitive.content)
                        put("row_version", 1); put("created_at", "2026-10-26T00:00:00.000Z"); put("updated_at", "2026-10-26T00:00:00.000Z")
                    }
                    docs[id] = row
                    if (loseNextCreateAnswer) { loseNextCreateAnswer = false; throw java.net.SocketTimeoutException("lost answer") }
                    return json(201, mapOf("doc" to row))
                }
            }
            return json(404, mapOf("error" to "no_route:${request.method} $path"))
        }
    }

    private class Rig(val tower: DocsTower = DocsTower()) {
        val store = LocalStore(tempDir())
        val engine = SyncEngine()
        val staging = tempDir()
        lateinit var connection: ConnectionRecord
        lateinit var sender: WriteSender
        val bindCalls = mutableListOf<String>()
        var bindFailure: Throwable? = null

        suspend fun start() {
            connection = store.writer.saveConnection(ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null))
            sender = WriteSender(store, engine, makeIdentity(), tower, staging)
            sender.afterWikiCreate = { _, item, created ->
                bindCalls.add(created.id + ":" + item.string(WikiCreate.TITLE_KEY))
                bindFailure?.let { throw it }
            }
        }

        suspend fun enqueue(withLink: Boolean = true, title: String = "Fresh page"): String {
            val name = "staged-" + UUID.randomUUID()
            java.io.File(staging, name).writeBytes(DocumentBody.newDocument("").bytes)
            val payload = buildJsonObject {
                put("channel_id", Fixtures.CHANNEL_1); put("title", title); put("staged", name); put("file_name", "$title.document.json")
                put("content_type", DocumentBody.ENVELOPE_CONTENT_TYPE)
                if (withLink) WikiCreate.linkPayload("00000000-0000-4000-8000-0000000000a3", title, 0, 1, "request-1").forEach { (k, v) -> put(k, v) }
            }
            return store.writer.enqueueWrite(connection, Fixtures.WORKSPACE_ID, TestKeys.NPUB, WriteIntent(WriteKind.DOC_CREATE, Fixtures.CHANNEL_1, payload)).id
        }

        suspend fun writes() = store.reader.workspaceWrites(connection.id, Fixtures.WORKSPACE_ID)
        fun close() { engine.close(); store.close() }
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking {
        val r = Rig()
        try { r.start(); r.block() } finally { r.close() }
    }

    @Test fun theLinkIsRecordedInTheSourceBeforeTheWriteIsRetired() = rig {
        enqueue()
        sender.drain(connection)
        assertEquals(1, tower.creates)
        assertEquals(1, bindCalls.size)
        assertTrue(bindCalls.single().endsWith(":Fresh page"))
        assertTrue("retired only after the link was recorded", writes().isEmpty())
    }

    @Test fun aSourceThatCannotBeLinkedYetKeepsTheWriteQueuedWithThePageLocationAndNeverMakesASecondPage() = rig {
        enqueue()
        bindFailure = WikiBindError.SourceUnavailable
        sender.drain(connection)
        val waiting = writes().single()
        assertEquals(WriteState.RETRYING, waiting.state)
        assertEquals("wiki_link_pending", waiting.lastCode)
        assertTrue(waiting.lastError!!.startsWith("The page was created."))
        val createdId = waiting.payloadObject[WikiCreate.CREATED_KEY]!!.jsonPrimitive.content
        assertEquals("the page's location is kept for Open the created page", tower.docs.keys.single(), createdId)
        // The source becomes available; the retry finds the page that already exists.
        bindFailure = null
        store.writer.retryWrite(waiting.id, force = true)
        sender.drain(connection)
        assertEquals("still exactly one page", 1, tower.docs.size)
        assertEquals(1, tower.creates)
        assertEquals(2, bindCalls.size)
        assertTrue(writes().isEmpty())
    }

    @Test fun aRetryFindsTheCreatedPageByIdEvenAfterItWasEditedOrTheChannelHasManyPages() = rig {
        enqueue()
        bindFailure = WikiBindError.SourceUnavailable
        sender.drain(connection)
        val waiting = writes().single()
        val id = tower.docs.keys.single()
        // Someone edits the page before the link can be added: its storage object is no longer the one this write uploaded, and
        // the channel list (first 200 rows only) no longer reaches it.
        tower.docs[id] = JsonObject(tower.docs.getValue(id) + ("storage_object_id" to kotlinx.serialization.json.JsonPrimitive(UUID.randomUUID().toString())))
        tower.hideFromChannelList = true
        bindFailure = null
        store.writer.retryWrite(waiting.id, force = true)
        sender.drain(connection)
        assertEquals("no second page was made", 1, tower.docs.size)
        assertEquals(1, tower.creates)
        assertEquals(2, bindCalls.size)
        assertTrue(bindCalls.all { it.startsWith("$id:") })
        assertTrue(writes().isEmpty())
    }

    @Test fun aLostAnswerAfterTowerCreatedThePageIsReconciledWithoutADuplicate() = rig {
        enqueue()
        tower.loseNextCreateAnswer = true
        sender.drain(connection)
        assertEquals(1, tower.docs.size)
        assertTrue("the lost answer is retried, not treated as done", bindCalls.isEmpty())
        val waiting = writes().single()
        assertEquals(WriteState.RETRYING, waiting.state)
        store.writer.retryWrite(waiting.id, force = true)
        sender.drain(connection)
        assertEquals("the retry found the page Tower made", 1, tower.docs.size)
        assertEquals(1, bindCalls.size)
        assertTrue(writes().isEmpty())
    }

    @Test fun offlineCreationStaysPendingUntilTheConnectionReturns() = rig {
        enqueue()
        tower.offline = true
        sender.drain(connection)
        assertEquals(WriteState.RETRYING, writes().single().state)
        assertTrue(bindCalls.isEmpty() && tower.docs.isEmpty())
        tower.offline = false
        store.writer.retryWrite(writes().single().id, force = true)
        sender.drain(connection)
        assertEquals(1, tower.docs.size)
        assertEquals(1, bindCalls.size)
    }

    @Test fun anOrdinaryCreateDoesNotTouchTheWikiHook() = rig {
        enqueue(withLink = false, title = "Plain")
        sender.drain(connection)
        assertEquals(1, tower.creates)
        assertTrue(bindCalls.isEmpty())
        assertNull(writes().firstOrNull())
    }
}
