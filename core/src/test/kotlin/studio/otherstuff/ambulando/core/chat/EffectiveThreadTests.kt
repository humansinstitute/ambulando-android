package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.*

class EffectiveThreadTests {
    private val w = StoreFixtures.W
    private val parent = "00000000-0000-4000-8000-000000000001"
    private val child = "00000000-0000-4000-8000-000000000002"
    private val grandchild = "00000000-0000-4000-8000-000000000003"
    private fun row(i: Int, owner: String = parent, effective: String? = child) = StoreFixtures.message(
        "10000000-0000-4000-8000-${i.toString().padStart(12, '0')}", "message $i", "2026-10-01T00:00:00Z", thread = owner,
    ).copy(effectiveThreadId = effective)

    @Test fun membershipSurvivesParentSyncColdReopenAndPagesBeyondFiveHundred() = runBlocking {
        val dir = tempDir()
        val inherited = (0 until 620).map { row(it) }
        val own = row(620, child)
        StoreFixtures.store(dir).use { store ->
            inherited.chunked(200).forEach { store.writer.upsertEffectiveThreadPage(w, Fixtures.CHANNEL_1, child, it) }
            store.writer.upsertEffectiveThreadPage(w, Fixtures.CHANNEL_1, child, listOf(own))
            store.writer.finishEffectiveThread(w, Fixtures.CHANNEL_1, child, (inherited + own).mapTo(HashSet()) { it.id })
            // Later parent messages must not leak into the prefix, and canonical refresh must not erase membership.
            store.writer.upsertMessages(inherited.map { it.copy(effectiveThreadId = null) } + row(700, parent, null))
            assertEquals(621, store.reader.threadMessageCount(w, child))
            assertEquals(50, store.reader.threadMessages(w, child, 50).size)
            val all = store.reader.threadMessages(w, child, 650)
            assertEquals((inherited + own).map { it.id }, all.map { it.id })
            assertTrue(all.first().readOnly)
            assertFalse(all.last().readOnly)
            assertEquals(parent, store.reader.message(w, inherited.first().id)?.threadId)
            assertFalse(store.reader.message(w, inherited.first().id)!!.inherited)
        }
        StoreFixtures.store(dir).use { store ->
            assertEquals(621, store.reader.threadMessages(w, child, 650).size)
            // Completion can narrow an inherited prefix without deleting canonical parent rows.
            store.writer.finishEffectiveThread(w, Fixtures.CHANNEL_1, child, setOf(inherited.first().id, own.id))
            assertEquals(listOf(inherited.first().id, own.id), store.reader.threadMessages(w, child, 650).map { it.id })
            assertNotNull(store.reader.message(w, inherited[1].id))
            store.writer.applyTombstones(w, setOf(Tombstone.Message(inherited.first().id)))
            assertEquals(listOf(own.id), store.reader.threadMessages(w, child, 650).map { it.id })
        }
    }

    @Test fun effectivePagesRejectForeignViewsButPreserveCanonicalOwnershipAndAttachments() = runBlocking {
        val transport = StubTransport { 200 to mapOf("messages" to listOf(Fixtures.message(row(1).id, body = "Inherited", thread = child).toMutableMap().apply {
            put("effective_thread_id", child); put("owning_thread_id", parent); put("inherited", true); put("read_only", true)
            put("attachments", listOf(mapOf("storage_object_id" to row(99).id)))
        })) }
        val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity()))
        val parsed = api.messagesPage(w, Fixtures.APP_NPUB, Fixtures.CHANNEL_1, child, null, effectiveTranscript = true).messages.single()
        assertEquals(parent, parsed.threadId)
        assertTrue(parsed.inherited)
        assertTrue(parsed.raw.containsKey("attachments"))
        assertEquals("true", queryOf(transport.recorded.last().url)["effective_transcript"])
        try { api.messagesPage(w, Fixtures.APP_NPUB, Fixtures.CHANNEL_1, grandchild, null, effectiveTranscript = true); fail("foreign effective thread accepted") }
        catch (_: TowerError.Malformed) { }
        val directory = tempDir()
        StoreFixtures.store(directory).use { store ->
            try { store.writer.upsertEffectiveThreadPage(w, Fixtures.CHANNEL_1, grandchild, listOf(parsed)); fail("foreign membership accepted") }
            catch (_: IllegalArgumentException) { }
            assertNull(store.reader.message(w, parsed.id))
            store.writer.upsertEffectiveThreadPage(w, Fixtures.CHANNEL_1, child, listOf(parsed))
        }
        StoreFixtures.store(directory).use { store ->
            assertEquals(parent, store.reader.message(w, parsed.id)?.threadId)
            assertTrue(store.reader.threadMessages(w, child, 50).single().readOnly)
            assertEquals(parent, MessageRecord.parse(store.reader.message(w, parsed.id)!!.raw).threadId)
        }
    }

    @Test fun refreshRequestsEveryEffectivePageAndNestedBranchUsesTheViewedThread() = runBlocking {
        val store = StoreFixtures.store()
        val connection = StoreFixtures.connect(store)
        val workspace = StoreFixtures.workspace(w, "Synthetic")
        val engine = SyncEngine()
        val inheritedId = row(1).id
        val ownId = row(2).id
        val transport = StubTransport { request ->
            when {
                request.method == "POST" -> {
                    assertTrue(pathOf(request.url).endsWith("/threads/$child/branches"))
                    201 to mapOf("thread" to mapOf("id" to grandchild, "workspace_id" to w, "channel_id" to Fixtures.CHANNEL_1))
                }
                pathOf(request.url).endsWith("/messages") -> {
                    val query = queryOf(request.url)
                    assertEquals("true", query["effective_transcript"])
                    val first = query["cursor"] == null
                    val message = Fixtures.message(if (first) inheritedId else ownId, body = "History", thread = if (first) parent else child).toMutableMap().apply { put("effective_thread_id", child) }
                    200 to mapOf("messages" to listOf(message), "next_cursor" to if (first) "second" else null)
                }
                else -> 200 to mapOf("thread" to mapOf("id" to child, "workspace_id" to w, "channel_id" to Fixtures.CHANNEL_1))
            }
        }
        try {
            val sync = WorkspaceSync(store, engine, makeIdentity(), transport)
            sync.refreshThread(connection, workspace, Fixtures.CHANNEL_1, child)
            engine.waitUntilIdle()
            val rows = store.reader.threadMessages(w, child, 50)
            assertEquals(listOf(inheritedId, ownId).toSet(), rows.map { it.id }.toSet())
            sync.branchThread(connection, workspace, rows.first { it.id == inheritedId }, null)
            assertEquals(4, transport.recorded.size)
            val forged = row(77)
            try { sync.branchThread(connection, workspace, forged, null); fail("unlisted inherited source accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(4, transport.recorded.size)
        } finally { engine.close(); store.close() }
    }
}
