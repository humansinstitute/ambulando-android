package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.members
import studio.otherstuff.ambulando.core.chat.messagesPage
import studio.otherstuff.ambulando.core.chat.scopeOrder
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.chat.threads
import studio.otherstuff.ambulando.core.crypto.MessageInstructionSignature
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.Exchange
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.SSEFrame
import studio.otherstuff.ambulando.core.transport.SSEParser
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerEvent
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.eventHead
import studio.otherstuff.ambulando.core.transport.events
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.message
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.streamRequest
import studio.otherstuff.ambulando.core.transport.string
import java.io.IOException

/** End to end: the real client, API wrappers and signing against the sample Tower. */
class SampleTowerTests {
    private val studio = SampleIds.STUDIO

    @Test fun discoverScopesChannelsMessagesSendReplayAndEvent() = runBlocking {
        val identity = makeIdentity()
        val h = SampleHarness(signer = identity)
        // Discovery: descriptor (unsigned), then the signed workspace list, all labelled as sample data.
        val descriptor = h.api.descriptor()
        assertEquals(SampleHosts.ORIGIN, descriptor.tower?.endpoint)
        val workspaces = h.api.workspaces()
        assertEquals(listOf(SampleIds.STUDIO, SampleIds.LAB), workspaces.map { it.id })
        assertTrue(workspaces.all { it.label.contains("Sample") })
        val scopes = h.api.scopes(studio, h.app)
        assertEquals(listOf("Ambulando", "Operations"), scopes.map { it.name })
        val channels = h.api.channels(studio, SampleIds.SCOPE_AMBULANDO, h.app)
        assertEquals(listOf("general", "design"), channels.map { it.name })

        // Forward paging with an opaque cursor.
        val first = h.api.messagesPage(studio, h.app, SampleIds.GENERAL, cursor = null, limit = 20)
        assertEquals(20, first.messages.size)
        assertNotNull(first.nextCursor)
        val second = h.api.messagesPage(studio, h.app, SampleIds.GENERAL, cursor = first.nextCursor, limit = 200)
        assertNull(second.nextCursor)
        assertEquals(43, first.messages.size + second.messages.size)
        val head = h.api.eventHead(studio, h.app)

        // A signed send, then the same frozen bytes again (replayed, not duplicated).
        val bytes = h.messageBody(SampleIds.GENERAL, "  Hello from the sample test  ", "5a000000-0000-4000-8000-00000000f001")
        val sent = h.send(SampleIds.GENERAL, bytes)
        assertEquals(201, sent.status)
        val created = sent.json().obj("message")!!
        assertEquals("Hello from the sample test", created.string("body"))
        assertEquals(TestKeys.NPUB, created.string("sender_npub"))
        assertNull("Tower's create reply has no thread source", created["thread_source_message_id"])
        val replay = h.send(SampleIds.GENERAL, bytes)
        assertEquals(200, replay.status)
        assertEquals(true, replay.json().bool("replayed"))
        assertEquals(created.string("id"), replay.json().obj("message")!!.string("id"))
        // A different message under the same request id.
        val conflict = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "Something else", "5a000000-0000-4000-8000-00000000f001"))
        assertEquals(409, conflict.status)
        assertEquals("idempotency_conflict", conflict.json().string("code"))

        // The new message is an event after the head, and in the channel.
        val page = h.api.events(studio, h.app, cursor = head)
        val event = page.events.single()
        assertEquals("message", event.entityType)
        assertEquals(created.string("id"), event.entityId)
        assertEquals(SampleIds.GENERAL, event.channelId)
        assertEquals(SampleIds.SCOPE_AMBULANDO, event.scopeId)
        assertEquals(created.string("thread_id"), event.threadId)
        val all = h.api.messagesPage(studio, h.app, SampleIds.GENERAL, cursor = null).messages
        assertEquals(1, all.count { it.id == created.string("id") })
        // A reply in the new thread.
        val reply = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "A reply", "5a000000-0000-4000-8000-00000000f002", thread = created.string("thread_id")))
        assertEquals(201, reply.status)
        assertEquals(2, h.api.messagesPage(studio, h.app, SampleIds.GENERAL, threadId = created.string("thread_id"), cursor = null).messages.size)
    }

    @Test fun tamperedNip98IsRejected() = runBlocking {
        val h = SampleHarness()
        val path = h.base() + "/channels/${SampleIds.GENERAL}/messages"
        val url = h.client.origin.url(path, listOf("limit" to "5"))
        val ok = h.tower.send(handSigned("GET", url, null))
        assertEquals(200, ok.status)
        // Wrong URL (another query), wrong method, stale time, wrong kind, no header.
        for (request in listOf(
            handSigned("GET", url, null, signedURL = h.client.origin.url(path, listOf("limit" to "6"))),
            handSigned("GET", url, null, signedMethod = "POST"),
            handSigned("GET", url, null, createdAt = System.currentTimeMillis() / 1000 - 301),
            handSigned("GET", url, null, kind = 1),
            HttpRequest("GET", url),
        )) {
            val r = h.tower.send(request)
            assertEquals(request.toString(), 401, r.status)
            assertEquals("unauthorized", parseJsonObject(r.body, "e").string("code"))
        }
        // Tampered payload hash on a body.
        val postURL = h.client.origin.url(path)
        val body = h.messageBody(SampleIds.GENERAL, "hi", "5a000000-0000-4000-8000-00000000f010")
        val tampered = h.tower.send(handSigned("POST", postURL, body, payload = "0".repeat(64)))
        assertEquals(401, tampered.status)
        assertTrue(parseJsonObject(tampered.body, "e").string("error")!!.contains("payload"))
        // A body the event does not cover (signed over other bytes).
        val signedOther = handSigned("POST", postURL, "{}".toByteArray())
        val swapped = HttpRequest("POST", postURL, signedOther.headers, body)
        assertEquals(401, h.tower.send(swapped).status)
        assertEquals(201, h.tower.send(handSigned("POST", postURL, body)).status)
        // Work, surface and stream routes check it too.
        assertEquals(401, h.tower.send(handSigned("GET", h.client.origin.url(h.base() + "/canvases"), null, signedMethod = "PUT")).status)
        assertEquals(401, h.tower.send(handSigned("GET", h.client.origin.url(h.base() + "/tasks/${SampleIds.CANVAS_TASK}"), null, signedMethod = "PUT")).status)
        val (head, _) = h.tower.events.open(handSigned("GET", h.client.origin.url(h.base() + "/events/stream"), null, signedMethod = "POST"))
        assertEquals(401, head.status)
    }

    @Test fun messageSignatureMustMatchBodyChannelAndSigner() = runBlocking {
        val h = SampleHarness()
        fun code(e: Exchange) = e.status to e.json().string("code")
        val wrongChannel = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "x", "5a000000-0000-4000-8000-00000000f020", signedChannel = SampleIds.DESIGN))
        assertEquals(400 to "validation_error", code(wrongChannel))
        val wrongBody = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "x", "5a000000-0000-4000-8000-00000000f021", signedBody = "y"))
        assertEquals(400 to "validation_error", code(wrongBody))
        val otherSigner = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "x", "5a000000-0000-4000-8000-00000000f022", signer = SampleSigners.other))
        assertEquals(400 to "validation_error", code(otherSigner))
        val wrongWorkspace = h.send(SampleIds.NOTES, h.messageBody(SampleIds.NOTES, "x", "5a000000-0000-4000-8000-00000000f023"), workspace = SampleIds.LAB)
        assertEquals(400 to "validation_error", code(wrongWorkspace))
        val missingThread = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "x", "5a000000-0000-4000-8000-00000000f024", thread = "5a000000-0000-4000-8000-0000000eeeee"))
        assertEquals(404 to "thread_not_found", code(missingThread))
        val noChannel = h.send("5a000000-0000-4000-8000-0000000eeeee", h.messageBody("5a000000-0000-4000-8000-0000000eeeee", "x", "5a000000-0000-4000-8000-00000000f025"))
        assertEquals(404 to "channel_not_found", code(noChannel))
        // Lab workspace send with its own tags works.
        assertEquals(201, h.send(SampleIds.NOTES, h.messageBody(SampleIds.NOTES, "lab", "5a000000-0000-4000-8000-00000000f026", workspace = SampleIds.LAB), SampleIds.LAB).status)
    }

    @Test fun togglesOfflineRejectAndFailingScopes() = runBlocking {
        val h = SampleHarness()
        h.tower.offline = true
        try {
            h.api.workspaces(); fail("offline")
        } catch (e: IOException) { assertTrue(e is SampleOfflineException) }
        h.tower.offline = false
        h.tower.failScopes = true
        try {
            h.api.scopes(studio, h.app); fail("scopes fail")
        } catch (e: TowerError.Http) {
            assertEquals(500, e.status)
            assertEquals("internal_error", e.code)
        }
        h.tower.failScopes = false
        assertEquals(2, h.api.scopes(studio, h.app).size)
        h.tower.rejectNextSend = true
        val rejected = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "x", "5a000000-0000-4000-8000-00000000f030"))
        assertEquals(400, rejected.status)
        assertFalse(h.tower.rejectNextSend)
        assertEquals(201, h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "x", "5a000000-0000-4000-8000-00000000f030")).status)
        // Discovery reads are counted for the "store workspace list again" control.
        val before = h.tower.workspaceListReads
        h.api.workspaces()
        assertEquals(before + 1, h.tower.workspaceListReads)
    }

    @Test fun streamDeliversLiveMemberPostsFromACursor() = runBlocking {
        val h = SampleHarness()
        val head = h.api.eventHead(studio, h.app)
        val (response, chunks) = h.tower.events.open(h.api.streamRequest(studio, h.app, head))
        assertEquals(200, response.status)
        val parser = SSEParser()
        suspend fun nextFrame(): SSEFrame = withTimeout(5_000) {
            var frame: SSEFrame? = null
            while (frame == null) frame = parser.feed(chunks.next() ?: error("stream ended")).firstOrNull()
            frame!!
        }
        assertEquals("connected", nextFrame().event)
        val id = h.tower.postAsMember(SampleIds.GENERAL, "Another member posts while you type")
        val frame = nextFrame()
        assertEquals("flightdeck_pg.event", frame.event)
        val event = TowerEvent.parse(parseJsonObject(frame.data.toByteArray(), "event"))!!
        assertEquals(id, event.entityId)
        assertEquals(frame.id, event.rowVersion.toString())
        assertNotNull(h.api.message(studio, h.app, id))
        // Going offline drops the connection.
        h.tower.offline = true
        try {
            withTimeout(5_000) { while (chunks.next() != null) Unit }
            fail("stream should fail")
        } catch (e: IOException) { assertTrue(e is SampleConnectionLostException) }
        chunks.cancel()
        h.tower.offline = false
        // A cursor the tower never issued is 400.
        val (bad, badChunks) = h.tower.events.open(h.api.streamRequest(studio, h.app, "bm90LWEtY3Vyc29y"))
        assertEquals(400, bad.status)
        assertNull(badChunks.next())
    }

    @Test fun messageEditNeedsAuthorRevisionSignatureAndVersion() = runBlocking {
        val h = SampleHarness()
        val sent = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "Draft", "5a000000-0000-4000-8000-00000000f040")).json().obj("message")!!
        val id = sent.string("id")!!
        val path = h.base() + "/messages/$id"
        suspend fun edit(body: String, version: Int, revision: Int = version + 1, signerId: String = id) = h.client.exchange(
            "PATCH", path, h.app,
            jsonBody(jsonObjectOf("body" to body, "row_version" to version, "message_signature" to MessageInstructionSignature.sign(body, studio, SampleIds.GENERAL,
                messageId = signerId, revision = revision, signer = SampleSigners.primary).requestObject)),
        )
        assertEquals(400, edit("Edited", 1, revision = 1).status)
        assertEquals(409, edit("Edited", 3).status)
        val ok = edit("Edited", 1)
        assertEquals(200, ok.status)
        assertEquals(2L, ok.json().obj("message")!!["row_version"].toString().toLong())
        // Not the author: a seeded message from another member.
        val theirs = h.client.exchange(
            "PATCH", h.base() + "/messages/${SampleIds.message(1)}", h.app,
            jsonBody(jsonObjectOf("body" to "x", "row_version" to 1, "message_signature" to MessageInstructionSignature.sign("x", studio, SampleIds.GENERAL,
                messageId = SampleIds.message(1), revision = 2, signer = SampleSigners.primary).requestObject)),
        )
        assertEquals(403, theirs.status)
        assertEquals("message_author_required", theirs.json().string("code"))
        assertEquals(200, h.client.exchange("DELETE", path, h.app, null, listOf("row_version" to "2")).status)
        assertNull("deleted messages read as gone", h.api.message(studio, h.app, id)?.takeIf { !it.deleted })
    }

    @Test fun threadsMembersScopeOrderAndManagement() = runBlocking {
        val h = SampleHarness()
        val threads = h.api.threads(studio, h.app, SampleIds.GENERAL)
        assertEquals(40, threads.items.size)
        assertEquals(SampleIds.message(39), h.api.thread(studio, h.app, SampleIds.thread(39)).sourceMessageId)
        val members = h.api.members(studio, h.app)
        assertTrue(members.any { it.npub == TestKeys.NPUB && it.label.contains("sample") })
        assertEquals(listOf(SampleIds.SCOPE_OPS, SampleIds.SCOPE_AMBULANDO), h.api.scopeOrder(studio, h.app))
        // Create a channel; Operations is refused; scope order conflicts once.
        val created = h.exchange("POST", h.base() + "/scopes/${SampleIds.SCOPE_AMBULANDO}/channels", mapOf("name" to "releases", "kind" to "channel"))
        assertEquals(201, created.status)
        assertEquals(3, h.api.channels(studio, SampleIds.SCOPE_AMBULANDO, h.app).size)
        assertEquals(409, h.exchange("POST", h.base() + "/scopes/${SampleIds.SCOPE_AMBULANDO}/channels", mapOf("name" to "releases")).status)
        assertEquals(403, h.exchange("PATCH", h.base() + "/channels/${SampleIds.DEPLOYS}", mapOf("name" to "x")).status)
        val newId = created.json().obj("channel")!!.string("id")!!
        assertEquals(200, h.exchange("POST", h.base() + "/channels/$newId/reorder", mapOf("position" to 1)).status)
        assertEquals("releases", h.api.channels(studio, SampleIds.SCOPE_AMBULANDO, h.app).first().name)
        h.tower.scopeOrderConflict = true
        val order = mapOf("scope_ids" to listOf(SampleIds.SCOPE_AMBULANDO), "expected_row_version" to 1, "mutation_id" to "5a000000-0000-4000-8000-00000000f050")
        assertEquals(409, h.exchange("PUT", h.base() + "/me/scope-order", order).status)
        assertEquals(200, h.exchange("PUT", h.base() + "/me/scope-order", order + ("expected_row_version" to 2)).status)
        assertEquals(listOf(SampleIds.SCOPE_AMBULANDO), h.api.scopeOrder(studio, h.app))
        // Management changes are events.
        val events = h.api.events(studio, h.app, cursor = null).events
        assertTrue(events.any { it.entityType == "channel" && it.entityId == newId })
    }

    @Test fun stateSurvivesARelaunchAndSeedsOnce() = runBlocking {
        val dir = tempDir()
        val h = SampleHarness(SampleTower(dir))
        val sent = h.send(SampleIds.GENERAL, h.messageBody(SampleIds.GENERAL, "Persisted", "5a000000-0000-4000-8000-00000000f060")).json().obj("message")!!
        val files = h.tower.work.files().size
        val again = SampleHarness(SampleTower(dir))
        assertEquals(files, again.tower.work.files().size)
        assertNotNull(again.api.message(studio, again.app, sent.string("id")!!))
        // The replay record survived too.
        assertEquals(200, again.send(SampleIds.GENERAL, again.messageBody(SampleIds.GENERAL, "Persisted", "5a000000-0000-4000-8000-00000000f060")).status)
        assertTrue(again.api.events(studio, again.app, cursor = null).events.any { it.entityId == sent.string("id") })
    }

    @Test fun sampleIdentityAndContentAreSynthetic() {
        val nsec = SampleIdentity.generateNsec()
        assertNotNull(NostrKeys.secretKey(nsec))
        assertTrue(SampleContent.samplePNG().copyOfRange(1, 4).contentEquals("PNG".toByteArray()))
        assertTrue(SampleContent.avatar(SampleHosts.AVATARS + "ana.png")!!.size > 50)
        assertNull(SampleContent.avatar("https://example.com/ana.png"))
        val profiles = runBlocking { SampleContent.profileSource({ TestKeys.NPUB }).profiles(listOf(TestKeys.PUBKEY_HEX)) }
        assertEquals("Sample Explorer", profiles.single().displayName)
    }
}
