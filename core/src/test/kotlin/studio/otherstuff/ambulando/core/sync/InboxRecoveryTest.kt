package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.support.*
import java.net.SocketTimeoutException

class InboxRecoveryTest {
    @Test fun failedLaterPagePreservesCompleteSnapshotAndEventRefreshClearsFailure() = runBlocking {
        val identity = makeIdentity()
        val store = LocalStore(tempDir())
        val engine = SyncEngine(retryDelay = { 1 })
        val w = Fixtures.WORKSPACE_ID
        val oldId = Fixtures.WORKSPACE_ID_2
        var failing = true
        val transport = StubTransport { request ->
            if (queryOf(request.url).containsKey("cursor")) throw SocketTimeoutException()
            200 to mapOf("states" to listOf(mapOf("workspace_id" to w, "resource_id" to w, "resource_type" to "task")), "next_cursor" to if (failing) "next" else null)
        }
        try {
            val connection = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
            val workspace = WorkspaceRecord(w, TestKeys.NPUB, "Synthetic", null, null, null, null, kotlinx.serialization.json.JsonObject(emptyMap()))
            val original = ViewStateRecord(w, ViewStateRecord.ResourceType.TASK, oldId, null, null, 2, 0, updatedAt = "2026-01-01T00:00:00Z")
            store.writer.upsertViewStates(w, listOf(original), complete = true)
            val sync = WorkspaceSync(store, engine, identity, transport)
            sync.refreshInbox(connection, workspace)
            withTimeout(3000) { engine.waitUntilIdle() }
            assertEquals(listOf(original), store.reader.viewStates(w))
            assertTrue(WorkspaceSync.inboxKey(w) in engine.snapshot().connectionFailures)
            failing = false
            sync.applyEvents(listOf(TowerEvent(1, "1", w, "resource_view_state", null, "updated")), connection, workspace)
            assertTrue(engine.snapshot().connectionFailures.isEmpty())
            assertTrue(engine.snapshot().errors.isEmpty())
            assertEquals(w, store.reader.viewStates(w).single().resourceId)
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }
}
