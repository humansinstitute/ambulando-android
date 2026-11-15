package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.preferences.AttentionTypes
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*
import java.net.SocketTimeoutException

class AttentionPreferenceSyncTest {
    private val w = Fixtures.WORKSPACE_ID
    private fun row(version: Int, disabled: List<String> = emptyList()) = Fixtures.obj(mapOf("workspace_id" to w, "actor_id" to NotificationFixtures.RECIPIENT,
        "preference_key" to AttentionTypes.KEY, "row_version" to version, "value" to buildJsonObject { put("version", 1); put("disabled", JsonArray(disabled.map(::JsonPrimitive))) }.toString()))

    @Test fun offlineMutationSurvivesReopenAndUncertainRetryUsesIdenticalBody() = runBlocking {
        val dir = tempDir(); var store = LocalStore(dir); val identity = makeIdentity(); val engine = SyncEngine(retryDelay = { 60_000 })
        val connection = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord(w, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
        var server: JsonObject? = null; var drop = false
        val writes = mutableListOf<String>()
        val transport = StubTransport { req ->
            if (req.method == "PUT") {
                writes.add(req.body!!.decodeToString())
                server = row(1, listOf("task"))
                if (drop) { drop = false; throw SocketTimeoutException() }
            }
            200 to mapOf("identity" to mapOf("workspace_id" to w), "preference" to server)
        }
        try {
            var sync = WorkspaceSync(store, engine, identity, transport)
            sync.runAttentionPreference(connection, workspace)
            assertTrue(store.reader.attentionPreference(connection.id, w).editable)
            drop = true
            sync.setAttentionType(connection, workspace, "task", false)
            withTimeout(3000) { while (writes.isEmpty()) delay(10) }
            engine.cancelAll()
            assertNotNull(store.reader.attentionPreference(connection.id, w).pending)
            store.close(); store = LocalStore(dir)
            sync = WorkspaceSync(store, engine, identity, transport)
            sync.runAttentionPreference(connection, workspace)
            assertEquals(2, writes.size)
            assertEquals(writes.first(), writes.last())
            assertNull(store.reader.attentionPreference(connection.id, w).pending)
            assertFalse(store.reader.attentionPreference(connection.id, w).types.includes("task"))
            assertTrue(store.reader.attentionPreference(connection.id, Fixtures.WORKSPACE_ID_2).types.includes("task"))
            assertTrue(store.reader.attentionPreference("other-connection", w).types.includes("task"))
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

    @Test fun staleWriteKeepsOtherDeviceChoiceAndFilteringDoesNotTouchViewStates() = runBlocking {
        val store = LocalStore(tempDir()); val identity = makeIdentity(); val engine = SyncEngine(retryDelay = { 60_000 })
        val connection = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord(w, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
        var server = row(1)
        val transport = StubTransport { req ->
            if (req.method == "PUT") 409 to mapOf("code" to "preference_conflict")
            else 200 to mapOf("identity" to mapOf("workspace_id" to w), "preference" to server)
        }
        try {
            val sync = WorkspaceSync(store, engine, identity, transport)
            sync.runAttentionPreference(connection, workspace)
            val original = ViewStateRecord(w, ViewStateRecord.ResourceType.TASK, w, Fixtures.CHANNEL_1, null, 2, 0, updatedAt = "now")
            store.writer.upsertViewStates(w, listOf(original), complete = true)
            server = row(2, listOf("document"))
            sync.setAttentionType(connection, workspace, "task", false)
            withTimeout(3000) { engine.waitUntilIdle() }
            val saved = store.reader.attentionPreference(connection.id, w)
            assertNull(saved.pending)
            assertFalse(saved.types.includes("document"))
            assertTrue(saved.types.includes("task"))
            assertNotNull(saved.message)
            assertEquals(listOf(original), store.reader.viewStates(w))
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }
    @Test fun sequentialOfflineSwitchesCoalesceBehindImmutableIntentAndNullResetsCache() = runBlocking {
        val store = LocalStore(tempDir()); val identity = makeIdentity(); val engine = SyncEngine(retryDelay = { 60_000 })
        val c = ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null)
        val workspace = WorkspaceRecord(w, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))
        var server: JsonObject? = row(1); var offline = false
        var version = 1
        val transport = StubTransport { req ->
            if (offline) throw SocketTimeoutException()
            if (req.method == "PUT") {
                val body = Json.parseToJsonElement(req.body!!.decodeToString()).jsonObject
                server = JsonObject(row(++version) + ("value" to body["value"]!!))
            }
            200 to mapOf("identity" to mapOf("workspace_id" to w), "preference" to server)
        }
        try {
            val sync = WorkspaceSync(store, engine, identity, transport)
            sync.runAttentionPreference(c, workspace)
            offline = true
            sync.setAttentionType(c, workspace, "task", false)
            val pending = store.reader.attentionPreference(c.id, w).pending
            sync.setAttentionType(c, workspace, "file", false)
            sync.setAttentionType(c, workspace, "other", false)
            val local = store.reader.attentionPreference(c.id, w)
            assertEquals(pending, local.pending)
            assertEquals(setOf("task", "file", "other"), local.types.disabled)
            engine.cancelAll()
            offline = false
            sync.runAttentionPreference(c, workspace)
            withTimeout(3000) { engine.waitUntilIdle() }
            val saved = store.reader.attentionPreference(c.id, w)
            assertNull(saved.pending); assertNull(saved.desired)
            assertEquals(setOf("task", "file", "other"), saved.types.disabled)
            server = null
            sync.runAttentionPreference(c, workspace)
            assertTrue(store.reader.attentionPreference(c.id, w).types.disabled.isEmpty())
            assertNull(store.reader.attentionPreference(c.id, w).row)
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }

}
