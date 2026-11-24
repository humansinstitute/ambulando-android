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

    @Test fun legitimateLifecycleHistoryBeyondTwentyPagesDoesNotReportMalformedServer() = runBlocking {
        val store = LocalStore(tempDir())
        val engine = SyncEngine(retryDelay = { 60_000 }, describe = { it.message ?: "unknown" })
        val connection = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord(Fixtures.WORKSPACE_ID, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
        val wire = StubTransport { request ->
            val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
            200 to mapOf("agent_activities" to (0 until 50).map { offset ->
                row("1", listOf(entry(1)), mapOf("id" to "00000000-0000-4000-8000-%012d".format(page * 50 + offset + 100),
                    "activity_id" to "activity-${page * 50 + offset}"))
            }, "next_cursor" to if (page < 20) (page + 1).toString() else null)
        }
        try {
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1, force = true)
            withTimeout(10_000) { engine.waitUntilIdle() }
            assertEquals("Valid large history must not surface agent activity page limit", emptyMap<String, String>(), engine.status.value.errors)
            assertEquals(1050, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertEquals(true, store.reader.listSyncComplete(Fixtures.WORKSPACE_ID, WorkspaceSync.agentActivityKey(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)))
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    private fun lifecycle(page: Int, state: String = "completed", sequence: Int = 100) = row("1", listOf(entry(1)), mapOf(
        "id" to "00000000-0000-4000-8000-%012d".format(page + 100), "activity_id" to "lifecycle-$page", "state" to state, "sequence" to sequence))
    private val connection get() = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
    private val workspace get() = WorkspaceRecord(Fixtures.WORKSPACE_ID, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
    private suspend fun idle(engine: SyncEngine) = withTimeout(10_000) { engine.waitUntilIdle() }

    @Test fun exactTwentyAndTwentyOneTerminalPagesAreComplete() = runBlocking {
        for (total in listOf(20, 21)) {
            val store = LocalStore(tempDir()); val engine = SyncEngine()
            val wire = StubTransport { request ->
                val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
                200 to mapOf("agent_activities" to listOf(lifecycle(page)), "next_cursor" to if (page + 1 < total) "${page + 1}" else null)
            }
            try {
                WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1)
                idle(engine)
                assertEquals(total, wire.recorded.size)
                assertEquals(total, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
                assertTrue(engine.status.value.errors.isEmpty())
                assertEquals(true, store.reader.listSyncComplete(Fixtures.WORKSPACE_ID, WorkspaceSync.agentActivityKey(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)))
            } finally { engine.close(); store.close() }
        }
    }

    @Test fun failedPageResumesAcrossStoreAndEngineRecreationWithoutSkippingOrRestartingHistory() = runBlocking {
        val directory = tempDir(); var store = LocalStore(directory); var engine = SyncEngine(retryDelay = { 60_000 })
        val account = connection; var fail = true
        val wire = StubTransport { request ->
            val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
            if (page == 10 && fail) throw java.net.SocketTimeoutException()
            200 to mapOf("agent_activities" to listOf(lifecycle(page)), "next_cursor" to if (page < 22) "${page + 1}" else null)
        }
        try {
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(account, workspace, Fixtures.CHANNEL_1)
            withTimeout(10_000) { while (engine.status.value.connectionFailures.isEmpty() || engine.status.value.running.isNotEmpty()) delay(5) }
            assertEquals(10, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertEquals(false, store.reader.listSyncComplete(Fixtures.WORKSPACE_ID, WorkspaceSync.agentActivityKey(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)))
            engine.cancelAll(); engine.close(); store.close()
            store = LocalStore(directory); engine = SyncEngine(); fail = false
            val count = wire.recorded.size
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(account, workspace, Fixtures.CHANNEL_1, force = true)
            idle(engine)
            assertEquals(listOf(null) + (10..22).map(Int::toString), wire.recorded.drop(count).map { queryOf(it.url)["cursor"] })
            assertEquals(23, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertTrue(engine.status.value.errors.isEmpty())
        } finally { engine.close(); store.close() }
    }

    @Test fun freshHeadRunsBetweenHistoryPagesAndOldReplayCannotResurrectWorkingTurn() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(maxConcurrent = 1)
        val account = connection; val space = workspace; var heads = 0
        lateinit var sync: WorkspaceSync
        val wire = StubTransport { request ->
            val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
            if (page == 1) runBlocking { sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1, force = true) }
            if (page == 0) heads++
            val rows = if (page == 0) listOf(lifecycle(0, if (heads == 1) "working" else "completed", if (heads == 1) 1 else 3))
                else listOf(lifecycle(page), lifecycle(0, "working", 1))
            200 to mapOf("agent_activities" to rows, "next_cursor" to if (page < 22) "${page + 1}" else null)
        }
        try {
            sync = WorkspaceSync(store, engine, makeIdentity(), wire)
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1)
            idle(engine)
            assertEquals(listOf(null, "1", null, "2"), wire.recorded.take(4).map { queryOf(it.url)["cursor"] })
            val rows = store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread)
            assertEquals(23, rows.size); assertTrue(rows.single { it.activityId == "lifecycle-0" }.isTerminal)
            assertTrue(engine.status.value.errors.isEmpty()); assertTrue(engine.status.value.connectionFailures.isEmpty())
        } finally { engine.close(); store.close() }
    }

    @Test fun commentaryBeyondTwentyPagesRecoversLateLowerSequenceAndTerminalState() = runBlocking {
        for (pages in listOf(20, 22)) {
        val store = LocalStore(tempDir()); val engine = SyncEngine(maxConcurrent = 1)
        val wire = StubTransport { request ->
            val after = queryOf(request.url)["after_commentary_cursor"]?.toInt()
            val value = if (after == null) row("${pages + 1}", listOf(entry(100)), mapOf("sequence" to 200))
                else row("${pages + 1}", listOf(entry(if (after == pages) 2 else after + 2)), mapOf(
                    "sequence" to 200, "commentary_next_cursor" to if (after < pages) "${after + 1}" else null))
            200 to mapOf("agent_activities" to listOf(value), "next_cursor" to null)
        }
        try {
            store.writer.replaceAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, listOf(AgentActivityRecord.parse(Fixtures.obj(row("1", listOf(entry(1)))))))
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1, threadId = thread)
            idle(engine)
            val saved = store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single()
            assertEquals("${pages + 1}", saved.raw.string("android_recovered_cursor"))
            assertTrue(saved.commentary.any { it.sequence == 2L }); assertTrue(saved.isTerminal)
            assertEquals(pages + 1, wire.recorded.size); assertTrue(engine.status.value.errors.isEmpty())
        } finally { engine.close(); store.close() }
        }
    }

    @Test fun crossBatchCycleIsAnErrorWithBoundedDurableCheckpoint() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(maxConcurrent = 1)
        val wire = StubTransport { request ->
            val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
            200 to mapOf("agent_activities" to listOf(lifecycle(page)), "next_cursor" to "${if (page < 100) page + 1 else 40}")
        }
        try {
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1)
            idle(engine)
            assertTrue(engine.status.value.errors.values.any { "cursor" in it })
            assertTrue(wire.recorded.size in 101..400)
            val raw = store.reader.read { textValue("SELECT value FROM app_state WHERE key LIKE 'activity-traversal:%'") }!!
            assertTrue(raw.length < 1000)
            assertFalse(store.reader.listSyncComplete(Fixtures.WORKSPACE_ID, WorkspaceSync.agentActivityKey(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1))!!)
            assertEquals(101, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
        } finally { engine.close(); store.close() }
    }

    @Test fun invalidatedCursorRestartsOnceAndRetainsRowsButRepeatedInvalidationIsARealError() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine()
        val wire = StubTransport { request ->
            if (queryOf(request.url)["cursor"] != null) 400 to mapOf("error" to "invalid_cursor")
            else 200 to mapOf("agent_activities" to listOf(lifecycle(0)), "next_cursor" to "gone")
        }
        try {
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1)
            idle(engine)
            assertEquals(listOf(null, "gone", null, "gone"), wire.recorded.map { queryOf(it.url)["cursor"] })
            assertTrue(engine.status.value.errors.isNotEmpty())
            assertEquals(1, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertEquals(false, store.reader.listSyncComplete(Fixtures.WORKSPACE_ID, WorkspaceSync.agentActivityKey(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)))
        } finally { engine.close(); store.close() }
    }

    @Test fun cancellationResumesUncommittedPageAndThreadQueriesCannotShareCursors() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(maxConcurrent = 1)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); var block = true
        val otherThread = "00000000-0000-4000-8000-000000000011"
        val account = connection; val space = workspace
        val wire = StubTransport { request ->
            val q = queryOf(request.url); val page = q["cursor"]?.substringAfterLast("-")?.toInt() ?: 0
            val owner = if (q["thread_id"] == thread) "a" else "b"
            if (q["cursor"] != null) assertTrue(q["cursor"]!!.startsWith(owner))
            200 to mapOf("agent_activities" to listOf(lifecycle(page + if (owner == "a") 0 else 100,
                sequence = 100) + mapOf("thread_id" to q["thread_id"])), "next_cursor" to if (page < 22) "$owner-${page + 1}" else null)
        }
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if (block && queryOf(request.url)["cursor"] == "a-10") { entered.complete(Unit); gate.await() }
                return wire.send(request)
            }
        }
        try {
            val sync = WorkspaceSync(store, engine, makeIdentity(), transport)
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1, threadId = thread)
            withTimeout(10_000) { entered.await() }
            engine.cancelAll(); block = false; gate.complete(Unit)
            idle(engine)
            assertEquals(10, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            val count = wire.recorded.size
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1, threadId = thread, force = true)
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1, threadId = otherThread, force = true)
            idle(engine)
            val resumed = wire.recorded.drop(count).filter { queryOf(it.url)["thread_id"] == thread }.map { queryOf(it.url)["cursor"] }
            assertEquals(listOf(null) + (10..22).map { "a-$it" }, resumed)
            assertEquals(23, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertEquals(23, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, otherThread).size)
            assertTrue(engine.status.value.errors.isEmpty())
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    @Test fun failedPagePersistenceRollsBackRowsAndCursorTogether() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(); val account = connection; val space = workspace
        val wire = StubTransport { request ->
            val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
            200 to mapOf("agent_activities" to if (page == 0) listOf(lifecycle(0)) else listOf(lifecycle(1), lifecycle(2)),
                "next_cursor" to if (page == 0) "1" else null)
        }
        try {
            store.writer.write { db.execute("CREATE TRIGGER reject_activity BEFORE INSERT ON agent_activities WHEN NEW.activity_id = 'lifecycle-2' BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END") }
            val sync = WorkspaceSync(store, engine, makeIdentity(), wire)
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1); idle(engine)
            assertTrue(engine.status.value.errors.isNotEmpty())
            assertEquals(1, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            val raw = store.reader.read { textValue("SELECT value FROM app_state WHERE key LIKE 'activity-traversal:%'") }!!
            assertEquals("1", kotlinx.serialization.json.Json.decodeFromString<AgentActivityTraversal>(raw).next)
            assertEquals(false, store.reader.listSyncComplete(Fixtures.WORKSPACE_ID, WorkspaceSync.agentActivityKey(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1)))
            store.writer.write { db.execute("DROP TRIGGER reject_activity") }
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1, force = true); idle(engine)
            assertEquals(3, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertTrue(engine.status.value.errors.isEmpty())
        } finally { engine.close(); store.close() }
    }

    @Test fun sharedEngineKeepsSeparateAccountStoresAndChannelAccessLossStopsContinuation() = runBlocking {
        val a = LocalStore(tempDir()); val b = LocalStore(tempDir()); val engine = SyncEngine(maxConcurrent = 1)
        val wireA = StubTransport { request ->
            val cursor = queryOf(request.url)["cursor"]
            assertTrue(cursor == null || cursor.startsWith("a"))
            200 to mapOf("agent_activities" to listOf(lifecycle(0)), "next_cursor" to if (cursor == null) "a1" else null)
        }
        val wireB = StubTransport { request ->
            val cursor = queryOf(request.url)["cursor"]
            assertTrue(cursor == null || cursor.startsWith("b"))
            200 to mapOf("agent_activities" to listOf(lifecycle(100)), "next_cursor" to if (cursor == null) "b1" else null)
        }
        try {
            WorkspaceSync(a, engine, makeIdentity(), wireA).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1)
            WorkspaceSync(b, engine, makeIdentity(), wireB).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1)
            idle(engine)
            assertEquals(2, wireA.recorded.size); assertEquals(2, wireB.recorded.size)
            assertEquals(listOf("lifecycle-0"), a.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).map { it.activityId })
            assertEquals(listOf("lifecycle-100"), b.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).map { it.activityId })
            val deniedWire = StubTransport { request ->
                val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
                if (page == 3) 403 to mapOf("error" to "permission_denied") else {
                    if (page == 2) runBlocking { a.writer.markAccessRemoved(Fixtures.WORKSPACE_ID, AccessTarget.Channel(Fixtures.CHANNEL_1)) }
                    200 to mapOf("agent_activities" to listOf(lifecycle(page)), "next_cursor" to "${page + 1}")
                }
            }
            // Seed a real channel authority row so a mid-flight ACL update cancels its pending continuation.
            a.writer.upsertChannels(Fixtures.WORKSPACE_ID, Fixtures.SCOPE_A, listOf(ChannelRecord.parse(Fixtures.obj(mapOf(
                "id" to Fixtures.CHANNEL_1, "workspace_id" to Fixtures.WORKSPACE_ID, "scope_id" to Fixtures.SCOPE_A, "name" to "Synthetic")))), complete = false)
            WorkspaceSync(a, engine, makeIdentity(), deniedWire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1)
            idle(engine)
            assertEquals(3, deniedWire.recorded.size)
            assertEquals(2, a.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertTrue(a.reader.isChannelAccessRemoved(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1))
            assertTrue(engine.status.value.errors.isEmpty())
        } finally { engine.cancelAll(); engine.close(); a.close(); b.close() }
    }

    @Test fun headPollDoesNotReplayRecentCompleteHistoryButDatedSweepRecoversOlderChanges() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(); val account = connection; val space = workspace
        var total = 23
        val wire = StubTransport { request ->
            val page = queryOf(request.url)["cursor"]?.toInt() ?: 0
            200 to mapOf("agent_activities" to listOf(lifecycle(page)), "next_cursor" to if (page + 1 < total) "${page + 1}" else null)
        }
        try {
            val sync = WorkspaceSync(store, engine, makeIdentity(), wire)
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1); idle(engine)
            val key = store.reader.read { textValue("SELECT key FROM app_state WHERE key LIKE 'activity-traversal:%'") }!!
            val completedAt = store.reader.agentActivityTraversal(key)!!.completedAt
            assertTrue(completedAt > 0)
            delay(5)
            val before = wire.recorded.size; total = 24
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1); idle(engine)
            assertEquals(1, wire.recorded.size - before)
            assertTrue(engine.status.value.isIdle); assertTrue(engine.status.value.errors.isEmpty())
            val complete = store.reader.agentActivityTraversal(key)!!
            assertEquals(completedAt, complete.completedAt)
            store.writer.setState(key, kotlinx.serialization.json.Json.encodeToString(complete.copy(completedAt = 0)))
            sync.refreshAgentActivity(account, space, Fixtures.CHANNEL_1); idle(engine)
            assertEquals(24, store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).size)
            assertEquals(48, wire.recorded.size)
        } finally { engine.close(); store.close() }
    }

    @Test fun commentaryRepeatedCursorStillFailsWithoutAdvancingRecovery() = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine()
        val wire = StubTransport { request ->
            val after = queryOf(request.url)["after_commentary_cursor"]
            200 to mapOf("agent_activities" to listOf(row("30", listOf(entry(30)),
                if (after == null) emptyMap() else mapOf("commentary_next_cursor" to after))), "next_cursor" to null)
        }
        try {
            store.writer.replaceAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, listOf(AgentActivityRecord.parse(Fixtures.obj(row("1", listOf(entry(1)))))))
            WorkspaceSync(store, engine, makeIdentity(), wire).refreshAgentActivity(connection, workspace, Fixtures.CHANNEL_1, threadId = thread)
            idle(engine)
            assertTrue(engine.status.value.errors.values.any { "cursor" in it })
            val saved = store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single()
            assertEquals("1", saved.raw.string("android_recovered_cursor")); assertTrue(saved.commentary.any { it.sequence == 1L })
            assertEquals(2, wire.recorded.size)
        } finally { engine.close(); store.close() }
    }

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
            // Fresh head content is readable while the missing delivery gap remains checkpointed at 2.
            assertEquals(listOf(1L, 2L, 4L), interrupted.commentary.map { it.sequence })
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
            val failedKey = engine.status.value.errors.keys.single()
            assertTrue(failedKey.startsWith(agentActivitySyncPrefix(connection.id, workspace.id, Fixtures.CHANNEL_1, thread)))
            assertFalse(failedKey.startsWith(agentActivitySyncPrefix(connection.id, workspace.id, Fixtures.CHANNEL_1, null)))
            assertFalse(failedKey.startsWith(agentActivitySyncPrefix("another-account", workspace.id, Fixtures.CHANNEL_1, thread)))
            assertFalse(failedKey.startsWith(agentActivitySyncPrefix(connection.id, workspace.id, Fixtures.CHANNEL_2, thread)))
            assertEquals(listOf(1L), store.reader.conversationAgentActivities(Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, thread).single().commentary.map { it.sequence })
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }
}
