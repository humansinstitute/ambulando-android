package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.support.*

class RemindersTest {
    private val w = Fixtures.WORKSPACE_ID
    private val c = "22222222-2222-2222-2222-222222222222"
    private val s = "33333333-3333-3333-3333-333333333333"
    private val m = "44444444-4444-4444-4444-444444444444"
    private fun channel(human: String, workspace: String = w, kind: String = "dm", participants: List<String> = listOf(human)) = ChannelRecord.parse(jsonObjectOf(
        "id" to c, "workspace_id" to workspace, "scope_id" to s, "kind" to kind, "name" to "Note to self", "participant_npubs" to participants))
    private fun message(body: String = "**Keep** [this](https://example.com)\n\nSecond paragraph") = MessageRecord.parse(jsonObjectOf(
        "id" to m, "workspace_id" to w, "channel_id" to c, "body" to body, "row_version" to 7))
    private fun task(request: JsonObject, workspace: String = w, human: String = "alice") = TaskRecord.parse(jsonObjectOf(
        "id" to "55555555-5555-5555-5555-555555555555", "workspace_id" to workspace, "channel_id" to request.string("channel_id"),
        "title" to request.string("title"), "description" to request.string("description"), "assigned_to_npub" to human, "state" to "ready"))
    private class Journal : ReminderJournal {
        val rows = mutableMapOf<String, JsonObject>()
        override suspend fun read(key: String) = rows[key]
        override suspend fun write(key: String, body: JsonObject) { rows[key] = body }
    }
    @Test fun neverUsesNameAsPersonalAuthority() {
        assertTrue(Reminders.isSelf(channel("alice"), w, "alice"))
        assertFalse(Reminders.isSelf(channel("bob"), w, "alice"))
        assertFalse(Reminders.isSelf(channel("alice", kind = "public"), w, "alice"))
        assertFalse(Reminders.isSelf(channel("alice", participants = listOf("alice", "bob")), w, "alice"))
        assertFalse(Reminders.isSelf(channel("alice"), "66666666-6666-6666-6666-666666666666", "alice"))
        assertFalse(Reminders.isSelf(channel("alice").copy(accessRemoved = true), w, "alice"))
    }
    @Test fun bodyPreservesFormattingSourceAndFullTitle() {
        val title = "Long title ".repeat(500)
        val request = Reminders.body(c, "alice", title, message())
        assertEquals(title.trim().take(200), request.string("title"))
        assertEquals(message().body + "\n\nSource: @[Original message](mention:chat:$c#$m)", request.string("description"))
        assertEquals("ready", request.string("state")); assertEquals("alice", request.string("assigned_to_npub"))
        assertEquals("Saved conversation", Reminders.body(c, "alice", "   ", message("")).string("title"))
        assertEquals("**Keep** this", Reminders.body(c, "alice", null, message("**Keep** this\nsecond")).string("title"))
    }
    @Test fun lostResponseRestartAndDoubleTapReuseFrozenRequest() = runBlocking {
        val journal = Journal(); var saved: TaskRecord? = null; var creations = 0; val received = mutableListOf<JsonObject>()
        suspend fun post(request: JsonObject): TaskRecord {
            received += request
            if (saved == null) { creations++; saved = task(request); throw java.io.IOException("response lost") }
            return saved!!
        }
        try { ReminderSaver(journal).save("tower", w, "alice", m, { Reminders.body(c, "alice", "Original", message()) }, ::post); fail() }
        catch (_: java.io.IOException) { }
        val restarted = ReminderSaver(journal)
        coroutineScope { (1..2).map { async { restarted.save("tower", w, "alice", m, { error("Must not refreeze edited source") }, ::post) } }.awaitAll() }
        assertEquals(1, creations); assertEquals(3, received.size); assertEquals(1, received.distinct().size)
    }
    @Test fun boundariesAndOtherHumansNeverReuseJournals() = runBlocking {
        val journal = Journal(); val saver = ReminderSaver(journal)
        for ((boundary, human) in listOf("tower-a" to "alice", "tower-b" to "alice", "tower-a" to "bob")) {
            saver.save(boundary, w, human, m, { Reminders.body(c, human, "Title", message()) }, { task(it, human = human) })
        }
        assertEquals(3, journal.rows.size)
        saver.save("tower-a", "66666666-6666-6666-6666-666666666666", "alice", m, { Reminders.body(c, "alice", "Title", message()) }, { task(it, workspace = "66666666-6666-6666-6666-666666666666") })
        assertEquals(4, journal.rows.size)
    }
    @Test fun failureNeverReturnsSuccessAndRetainsRequest() = runBlocking {
        val journal = Journal(); val saver = ReminderSaver(journal)
        try { saver.save("tower", w, "alice", m, { Reminders.body(c, "alice", "Title", message()) }, { throw SecurityException("denied") }); fail() }
        catch (_: SecurityException) { }
        assertEquals(1, journal.rows.size)
        try { saver.save("tower", w, "alice", m, { error("frozen") }, { task(it, human = "bob") }); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun discoveryDoesNotCreateMissingOrChooseAmbiguousBoard() = runBlocking {
        for (count in listOf(0, 2)) {
            val transport = StubTransport { request ->
                when {
                    pathOf(request.url).endsWith("/scopes") -> 200 to mapOf("scopes" to listOf(mapOf("id" to s, "workspace_id" to w, "kind" to "dm")), "next_cursor" to null)
                    pathOf(request.url).endsWith("/channels") -> 200 to mapOf("channels" to (0 until count).map { channel("alice").raw + ("id" to JsonPrimitive(if (it == 0) c else m)) }, "next_cursor" to null)
                    else -> error("Unexpected mutation or route")
                }
            }
            val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity()))
            try { api.reminderBoard(w, Fixtures.APP_NPUB, "alice"); fail() } catch (_: IllegalStateException) { }
            assertTrue(transport.recorded.all { it.method == "GET" })
        }
    }
    @Test fun humanVerificationRejectsBotOwnerAndWrongWorkspace() {
        fun me(npub: String, kind: String = "human", workspace: String = w) = jsonObjectOf("actor" to mapOf("npub" to npub, "kind" to kind), "identity" to mapOf("workspace_id" to workspace))
        Reminders.requireHuman(me("alice"), "alice", w)
        for (row in listOf(me("bot", "agent"), me("owner"), me("alice", workspace = "other"))) {
            try { Reminders.requireHuman(row, "alice", w); fail() } catch (_: IllegalArgumentException) { }
        }
        for (state in listOf("archived", "deleted", "unavailable")) {
            assertFalse(Reminders.isSelf(channel("alice").copy(raw = JsonObject(channel("alice").raw + ("record_state" to JsonPrimitive(state)))), w, "alice"))
        }
        assertFalse(Reminders.active(jsonObjectOf("archived_at" to "2026-01-01")))
        assertFalse(Reminders.active(jsonObjectOf("deleted_at" to "2026-01-01")))
    }
    @Test fun realTaskRouteReplaysFrozenRequestAfterLostResponse() = runBlocking {
        var creations = 0; var accepted: JsonObject? = null; val bodies = mutableListOf<JsonObject>()
        val transport = StubTransport { http ->
            assertEquals("POST", http.method)
            assertTrue(pathOf(http.url).endsWith("/channels/$c/tasks"))
            val body = parseJsonObject(http.body!!, "task")
            bodies += body
            assertFalse(body.containsKey("channel_id"))
            val reply = task(JsonObject(body + ("channel_id" to JsonPrimitive(c)))).raw
            if (accepted == null) { creations++; accepted = reply; throw java.io.IOException("lost after commit") }
            200 to mapOf("task" to accepted, "replayed" to true)
        }
        val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity()))
        val journal = Journal()
        suspend fun save() = ReminderSaver(journal).save("tower", w, "alice", m,
            { Reminders.body(c, "alice", "Title", message()) }, { api.saveReminder(w, Fixtures.APP_NPUB, it) })
        try { save(); fail() } catch (_: java.io.IOException) { }
        val replay = save()
        assertEquals("ready", replay.state); assertEquals(1, creations); assertEquals(bodies[0], bodies[1])
    }

}
