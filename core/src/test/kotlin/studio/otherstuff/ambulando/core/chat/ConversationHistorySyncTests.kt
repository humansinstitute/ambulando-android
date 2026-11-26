package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.IOException

class ConversationHistorySyncTests {
    @Test fun completeHistoryRechecksMetadataOnlyThreadsAndOnlyRecordAccessLossHidesThem() = runBlocking {
        for (status in listOf(403, 404, 401, 500, -1)) {
            StoreFixtures.store().use { store ->
                val engine = SyncEngine()
                try {
                    val w = Fixtures.WORKSPACE_ID; val channel = Fixtures.CHANNEL_1; val scope = Fixtures.SCOPE_A
                    val id = Fixtures.newId()
                    val connection = StoreFixtures.connect(store)
                    val workspace = StoreFixtures.workspace(w, "Synthetic")
                    store.writer.upsertScopes(w, listOf(StoreFixtures.scope(scope, "Scope")), true)
                    store.writer.upsertChannels(w, scope, listOf(StoreFixtures.channel(channel, scope, "general")), true)
                    val raw = Fixtures.obj(mapOf("id" to id, "title" to "cached metadata", "row_version" to 1))
                    val thread = ThreadRecord(w, id, channel, null, "cached metadata", updatedAt = "2026-10-01", raw = raw)
                    store.writer.upsertThreads(w, listOf(thread))
                    val target = ChatTarget(connection.id, w, channel, id)
                    store.writer.saveDraft(target, Draft("retained draft", emptyList()))
                    val pending = store.writer.enqueueSend(target, connection.origin, connection.signerNpub, workspace.appNpub, "retained send", emptyList(), preserveDraft = true)
                    val transport = StubTransport { request ->
                        when {
                            pathOf(request.url).endsWith("/messages") -> 200 to mapOf("messages" to emptyList<Any>(), "next_cursor" to null)
                            pathOf(request.url).endsWith("/threads") -> 200 to mapOf("threads" to emptyList<Any>(), "next_cursor" to null)
                            pathOf(request.url).endsWith("/threads/$id") -> if (status == -1) throw IOException("synthetic timeout") else status to mapOf("error" to "synthetic")
                            else -> error("Unexpected route ${pathOf(request.url)}")
                        }
                    }
                    val sync = WorkspaceSync(store, engine, makeIdentity(), transport)
                    val outcome = runCatching { sync.runChannelCatchUp(connection, workspace, channel, fromStart = true) }
                    assertTrue(transport.recorded.any { pathOf(it.url).endsWith("/threads/$id") })
                    assertEquals(status == 403 || status == 404, store.reader.thread(w, id)!!.accessRemoved)
                    assertEquals(status == 403 || status == 404, outcome.isSuccess)
                    assertEquals("retained draft", store.reader.draft(target)!!.text)
                    assertEquals(pending, store.reader.outboxItem(pending.id))
                    val expected = if (status == 403 || status == 404) 0 else 1
                    assertEquals(expected, store.reader.conversations(w, setOf(channel), connection.signerNpub, "cached").size)
                    store.writer.upsertThreads(w, listOf(thread.copy(rowVersion = 2, raw = Fixtures.obj(mapOf("id" to id, "title" to "cached metadata", "row_version" to 2)))))
                    assertFalse(store.reader.thread(w, id)!!.accessRemoved)
                    assertEquals(1, store.reader.conversations(w, setOf(channel), connection.signerNpub, "cached").size)
                } finally { engine.cancelAll(); engine.close() }
            }
        }
    }
}
