package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.deck.PersonalAgentLauncher
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.*

class MessageFlowsTests {
    private val w = StoreFixtures.W
    private val channel = Fixtures.CHANNEL_1
    private val thread = "00000000-0000-4000-8000-000000000001"
    private val root = "00000000-0000-4000-8000-000000000002"
    private val session = "00000000-0000-4000-8000-000000000003"
    private fun message(id: String = root) = StoreFixtures.message(id, "Source text", "2026-10-01T00:00:00Z", thread = thread, source = root)
    private fun sessionRaw(id: String = session) = buildJsonObject {
        putJsonObject("metadata") { put("source", "autopilot_session"); put("session_id", id) }
    }

    @Test fun headerUsesTheRootOutsideTheVisibleWindowAndTheLatestUpdatedSession() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertThreads(w, listOf(ThreadRecord(w, thread, channel, root, "Conversation", updatedAt = "")))
            val rows = (0..305).map { i -> message("10000000-0000-4000-8000-${i.toString().padStart(12, '0')}").copy(createdAt = "2026-10-02T00:00:00Z") }
            val oldSession = message("20000000-0000-4000-8000-000000000001").copy(raw = sessionRaw(), updatedAt = "2026-10-03T00:00:00Z")
            val newSession = oldSession.copy(id = "20000000-0000-4000-8000-000000000002", updatedAt = "2026-10-04T00:00:00Z")
            store.writer.upsertMessages(listOf(message(), oldSession, newSession) + rows)
            assertFalse(store.reader.threadMessages(w, thread, 300).any { it.id == root })
            val menu = store.reader.threadMenu(w, thread)!!
            assertEquals(root, menu.root?.id)
            assertEquals("@[Chat thread](mention:chat:$channel#$root)", menu.reference)
            assertEquals(newSession.id, menu.autopilotSource?.id)
        }
    }

    @Test fun missingRootStillHasReferenceButNeverActsOnAReplyAndUnknownSourceHasNoReference() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertThreads(w, listOf(ThreadRecord(w, thread, channel, null, null, updatedAt = "")))
            store.writer.upsertMessages(listOf(message(session)))
            assertNull(store.reader.threadMenu(w, thread)!!.root)
            assertEquals(root, store.reader.threadMenu(w, thread)!!.rootMessageId)
            store.writer.upsertMessages(listOf(message(session).copy(threadSourceId = null, rowVersion = 2, raw = buildJsonObject { put("row_version", 2) })))
            assertNull(store.reader.threadMenu(w, thread)!!.reference)
        }
    }

    @Test fun explicitSourceMayBeAChannelPostAndDeletedSourceNeverEnablesRootActions() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertThreads(w, listOf(ThreadRecord(w, thread, channel, root, null, updatedAt = "")))
            store.writer.upsertMessages(listOf(message().copy(threadId = null)))
            assertEquals(root, store.reader.threadMenu(w, thread)?.root?.id)
            store.writer.upsertMessages(listOf(message().copy(deleted = true, rowVersion = 2, raw = buildJsonObject { put("record_state", "deleted") })))
            val menu = store.reader.threadMenu(w, thread)!!
            assertNull(menu.root)
            assertEquals(root, menu.rootMessageId)
            assertEquals("@[Chat thread](mention:chat:$channel#$root)", menu.reference)
        }
    }

    @Test fun sessionLinksRequireCorrectMetadataAndUseOnlyWorkspaceOrSenderHost() {
        val post = message().copy(raw = sessionRaw(), senderNpub = TestKeys.NPUB)
        assertEquals(session, AutopilotSessionLink.sessionId(post.raw))
        assertNull(AutopilotSessionLink.sessionId(sessionRaw("not-a-uuid")))
        val workspaceRaw = buildJsonObject { putJsonObject("metadata") { put("autopilot_url", "https://agent.example.com/base?private=1#x") } }
        assertEquals("https://agent.example.com/live/$session", AutopilotSessionLink.resolve(post, workspaceRaw, emptyList()))
        assertNull(AutopilotSessionLink.resolve(post, JsonObject(emptyMap()), listOf(PersonalAgentLauncher("someone-else", "https://wrong.example.com"))))
        assertEquals("https://own.example.com/live/$session", AutopilotSessionLink.resolve(post, JsonObject(emptyMap()), listOf(PersonalAgentLauncher(TestKeys.NPUB, "https://own.example.com/path"))))
        listOf("http://agent.example.com", "https://user:password@agent.example.com", "javascript:alert(1)").forEach { assertNull(AutopilotSessionLink.url(session, it)) }
    }

    @Test fun getItDoneCreatesAReadyTaskWithCanonicalSourceOutputAssignmentAndBoundedExcerpt() = runBlocking {
        val intent = GetItDone.intent(message(), root, "  Deliver this  ", GetItDone.Output.DOC, "Preserve facts", TestKeys.NPUB,
            listOf(message().copy(body = "old"), message(session).copy(body = "new ".repeat(100))))
        val payload = intent.payload
        assertEquals("ready", payload["state"]?.jsonPrimitive?.content)
        assertEquals("Deliver this", payload["title"]?.jsonPrimitive?.content)
        assertEquals(thread, payload["thread_id"]?.jsonPrimitive?.content)
        assertEquals(TestKeys.NPUB, payload["assigned_to_npub"]?.jsonPrimitive?.content)
        val detail = payload["description"]!!.jsonPrimitive.content
        assertTrue(detail.contains("@[Chat thread](mention:chat:$channel#$root)"))
        assertTrue(detail.contains(GetItDone.Output.DOC.instruction))
        assertTrue(GetItDone.excerpt(listOf(message().copy(body = "x".repeat(500)))).length <= 256)
    }

    @Test fun queuedTaskActuallyTransmitsThreadAndSourceMetadata() = runBlocking {
        val identity = makeIdentity()
        val store = StoreFixtures.store()
        val connection = StoreFixtures.connect(store)
        val workspace = StoreFixtures.workspace(w, "Synthetic")
        var sent: JsonObject? = null
        val attempts = mutableListOf<ByteArray>()
        val transport = StubTransport { request ->
            if (request.method == "POST" && pathOf(request.url).endsWith("/tasks")) {
                attempts.add(request.body!!.copyOf())
                sent = JsonFieldsForTest(request.body!!)
                403 to mapOf("error" to "Test stops after observing the wire payload")
            } else 404 to null
        }
        val engine = SyncEngine()
        try {
            val sender = WriteSender(store, engine, identity, transport, tempDir())
            sender.submit(GetItDone.intent(message(), root, "Do this", GetItDone.Output.CHAT, "", null, listOf(message())), connection, workspace)
            engine.waitUntilIdle()
            assertEquals(thread, sent?.get("thread_id")?.jsonPrimitive?.content)
            assertEquals("$channel#$root", sent?.get("metadata")?.jsonObject?.get("source_links")?.jsonArray?.first()?.jsonObject?.get("id")?.jsonPrimitive?.content)
            val item = store.reader.writes(connection.id, connection.signerNpub).single()
            sender.retry(item.id, connection)
            engine.waitUntilIdle()
            assertEquals(2, attempts.size)
            assertArrayEquals("A retry keeps source links and the idempotency key", attempts[0], attempts[1])
        } finally { engine.close(); store.close() }
    }

    private fun JsonFieldsForTest(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    @Test fun branchUsesExistingTypedRouteStoresOnlyMatchingResponseAndRepliesKeepAgent() = runBlocking {
        val identity = makeIdentity()
        val store = StoreFixtures.store()
        val connection = StoreFixtures.connect(store)
        val workspace = StoreFixtures.workspace(w, "Synthetic")
        val engine = SyncEngine()
        val branched = "00000000-0000-4000-8000-000000000009"
        val transport = StubTransport { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v4/flightdeck-pg/workspaces/$w/channels/$channel/threads/$thread/branches", pathOf(request.url))
            val payload = JsonFieldsForTest(request.body!!)
            assertEquals(root, payload["branch_point_message_id"]?.jsonPrimitive?.content)
            assertEquals("branch:synthetic", payload["client_request_id"]?.jsonPrimitive?.content)
            assertEquals(TestKeys.NPUB, payload["metadata"]?.jsonObject?.get("inherited_agent_recipient_npub")?.jsonPrimitive?.content)
            201 to mapOf("thread" to mapOf("id" to branched, "workspace_id" to w, "channel_id" to channel, "source_message_id" to root,
                "title" to "Branch", "row_version" to 1, "updated_at" to "2026-10-27T00:00:00Z",
                "metadata" to mapOf("source" to "flightdeck_branch", "inherited_agent_recipient_npub" to TestKeys.NPUB)))
        }
        try {
            val sync = WorkspaceSync(store, engine, identity, transport)
            val created = sync.branchThread(connection, workspace, message(), TestKeys.NPUB, "branch:synthetic")
            assertEquals(branched, created.id)
            assertEquals(created, store.reader.thread(w, branched))
            val target = ChatTarget(connection.id, w, channel, branched)
            val item = store.writer.enqueueSend(target, connection.origin, connection.signerNpub, workspace.appNpub, "Continue", emptyList())
            val reply = JsonFieldsForTest(OutboxRequest.build(item, identity, TestKeys.NPUB))
            assertEquals(TestKeys.NPUB, reply["metadata"]?.jsonObject?.get("agent_direct_recipient_npub")?.jsonPrimitive?.content)
            val named = store.writer.enqueueSend(target, connection.origin, connection.signerNpub, workspace.appNpub, "@Someone", listOf(Mention(MentionType.PERSON, TestKeys.NPUB, "Someone")))
            assertNull(JsonFieldsForTest(OutboxRequest.build(named, identity, TestKeys.NPUB))["metadata"]?.jsonObject?.get("agent_direct_recipient_npub"))
            store.writer.upsertMessages(listOf(message()))
            store.writer.markAccessRemoved(w, AccessTarget.Message(root))
            try { sync.branchThread(connection, workspace, message(), TestKeys.NPUB); fail("Access-removed source must not branch") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, transport.recorded.size)
        } finally { engine.close(); store.close() }
    }
}
