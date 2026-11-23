package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.*

/** Exercises the real keyed scheduler, API parsing and SQLite projection without a live identity. */
class AgentProgressSyncTests {
    private val thread = "00000000-0000-4000-8000-000000000010"
    private val id = "00000000-0000-4000-8000-000000000012"
    private fun entry(n: Int) = mapOf("activity_id" to "activity", "turn_id" to "turn", "sequence" to n, "body" to "Update $n")
    private fun row(cursor: String, history: List<Map<String, Any>>, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "id" to id, "workspace_id" to Fixtures.WORKSPACE_ID, "channel_id" to Fixtures.CHANNEL_1,
        "thread_id" to thread, "activity_id" to "activity", "turn_id" to "turn", "visibility" to "user_visible",
        "state" to "completed", "sequence" to 100, "commentary_cursor" to cursor, "commentary_history" to history
    ) + extra

    @Test fun reconnectPagesMissedDeliveryHistoryAndDoesNotAdvancePastInterruptedRecovery() = runBlocking {
        val store = LocalStore(tempDir())
        val engine = SyncEngine(retryDelay = { 60_000 })
        val connection = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord(Fixtures.WORKSPACE_ID, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
        var failSecond = true
        val wire = StubTransport { request ->
            val query = queryOf(request.url)
            assertEquals(thread, query["thread_id"])
            val value = when (query["after_commentary_cursor"]) {
                "1" -> row("4", listOf(entry(2)), mapOf("commentary_next_cursor" to "2"))
                "2" -> {
                    if (failSecond) throw java.net.SocketTimeoutException()
                    row("4", listOf(entry(3), entry(4)), mapOf("commentary_next_cursor" to null))
                }
                else -> row("4", listOf(entry(4)), mapOf("commentary_next_before_sequence" to 4))
            }
            200 to mapOf("agent_activities" to listOf(value), "next_cursor" to null)
        }
        try {
            store.writer.replaceAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1,
                listOf(AgentActivityRecord.parse(Fixtures.obj(row("1", listOf(entry(1)))))))
            val sync = WorkspaceSync(store, engine, makeIdentity(), wire)
            sync.refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1, force = true, threadId = thread)
            withTimeout(10_000) { while (engine.status.value.connectionFailures.isEmpty() || engine.status.value.running.isNotEmpty()) delay(10) }
            val interrupted = store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single()
            assertEquals("2", interrupted.raw.string("android_recovered_cursor"))
            assertEquals(listOf(1L, 2L), interrupted.commentary.map { it.sequence })
            assertTrue(engine.status.value.connectionFailures.isNotEmpty())
            failSecond = false
            sync.refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1, force = true, threadId = thread)
            withTimeout(4000) { engine.waitUntilIdle() }
            val restored = store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single()
            assertEquals(listOf(1L, 2L, 3L, 4L), restored.commentary.map { it.sequence })
            assertEquals("4", restored.raw.string("android_recovered_cursor"))
            assertTrue(restored.isTerminal)
            assertTrue(engine.status.value.errors.isEmpty())
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    @Test fun repeatedActivityPageCursorFailsWithoutDeletingRetainedHistory() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(retryDelay = { 60_000 })
        val connection = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord(Fixtures.WORKSPACE_ID, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
        val wire = StubTransport { 200 to mapOf("agent_activities" to listOf(row("1", listOf(entry(1)))), "next_cursor" to "repeated") }
        try {
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1, force = true, threadId = thread)
            withTimeout(10_000) { while (engine.status.value.errors.isEmpty() || engine.status.value.running.isNotEmpty()) delay(10) }
            assertEquals(2, wire.recorded.size)
            assertTrue(engine.status.value.errors.isNotEmpty())
            assertEquals(listOf(1L), store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single().commentary.map { it.sequence })
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }
}
