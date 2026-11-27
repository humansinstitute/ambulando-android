package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.nostr.Sha256Hex

/** App-owned save-for-later policy; records remain ordinary private-channel tasks. */
object Reminders {
    fun isSelf(channel: ChannelRecord, workspace: String, human: String): Boolean =
        channel.workspaceId == workspace && channel.kind == "dm" && !channel.accessRemoved &&
            active(channel.raw) &&
            channel.raw["participant_npubs"] == JsonArray(listOf(JsonPrimitive(human)))

    fun active(raw: JsonObject): Boolean = raw.bool("is_archived") != true &&
        raw.string("record_state") !in setOf("deleted", "archived", "unavailable") &&
        raw.string("archived_at") == null && raw.string("deleted_at") == null && raw.bool("access_removed") != true

    fun requireHuman(me: JsonObject, human: String, workspace: String) {
        val actor = me.obj("actor")
        require(actor?.string("npub") == human && actor.string("kind") == "human" &&
            (me.obj("identity")?.string("workspace_id")?.let { it == workspace } != false)) {
            "Reminders are available to the signed-in human only."
        }
    }

    private fun bounded(text: String): String = text.substring(0, text.offsetByCodePoints(0, minOf(200, text.codePointCount(0, text.length))))

    fun body(channel: String, human: String, title: String?, message: MessageRecord): JsonObject = buildJsonObject {
        put("channel_id", channel)
        put("title", title?.let(MentionCodec::plain)?.trim()?.takeIf { it.isNotEmpty() }?.let(::bounded)
            ?: MentionCodec.plain(message.body).lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let(::bounded)
            ?: "Saved conversation")
        put("description", message.body + "\n\nSource: @[Original message](mention:chat:${TowerAPI.uuid(message.channelId)}#${TowerAPI.uuid(message.id)})")
        put("state", "ready"); put("priority", "sand"); put("assigned_to_npub", human)
        put("client_request_id", "reminder:v1:${java.util.UUID.randomUUID()}")
        put("metadata", buildJsonObject {
            put("ambulando_reminder", buildJsonObject {
                put("version", 1); put("message_id", message.id); put("channel_id", message.channelId)
                message.threadId?.let { put("thread_id", it) }
                put("message_row_version", message.rowVersion)
            })
        })
    }
}

/** Journal stores a frozen request, never an independent reminder record. Retry cannot adopt edited source text. */
interface ReminderJournal {
    suspend fun read(key: String): JsonObject?
    suspend fun write(key: String, body: JsonObject)
}

class ReminderSaver(private val journal: ReminderJournal, private val mutex: Mutex = Mutex()) {
    suspend fun save(boundary: String, workspace: String, human: String, sourceId: String,
                     prepare: suspend () -> JsonObject, post: suspend (JsonObject) -> TaskRecord): TaskRecord = mutex.withLock {
        val key = "reminder-request:v1:" + Sha256Hex.of("$boundary:$workspace:$human:$sourceId".toByteArray())
        val request = journal.read(key) ?: prepare().also { journal.write(key, it) }
        require(request.string("assigned_to_npub") == human)
        // The server's actor/workspace-scoped idempotency contract returns the original task on replay.
        post(request).also { task ->
            require(task.workspaceId == workspace && task.channelId == request.string("channel_id") && task.assignedToNpub == human) {
                "The saved task did not match your personal board."
            }
        }
    }
}

/** Complete authorized discovery, never display-name matching or implicit personal-channel provisioning. */
suspend fun TowerAPI.reminderBoard(workspace: String, app: String, human: String): ChannelRecord {
    val scopes = pages(TowerAPI.base(workspace) + "/scopes", app, "scopes").map(ScopeRecord::parse)
    require(scopes.all { it.workspaceId == workspace })
    val channels = scopes.filter { it.kind == "dm" && !it.accessRemoved && Reminders.active(it.raw) }.flatMap { scope ->
        pages(TowerAPI.base(workspace) + "/scopes/${TowerAPI.uuid(scope.id)}/channels", app, "channels").map(ChannelRecord::parse).onEach {
            require(it.workspaceId == workspace && it.scopeId == scope.id)
        }
    }.filter { Reminders.isSelf(it, workspace, human) }.distinctBy { it.id }
    return channels.singleOrNull() ?: error(if (channels.isEmpty()) "Your private Note to self board is unavailable. Open or set up Note to self first." else "More than one private Note to self board was found. Choose your personal board before saving reminders.")
}

suspend fun TowerAPI.saveReminder(workspace: String, app: String, request: JsonObject): TaskRecord {
    val channel = requireNotNull(request.string("channel_id"))
    val response = obj(client.request(method = "POST", path = TowerAPI.base(workspace) + "/channels/${TowerAPI.uuid(channel)}/tasks", appNpub = app, jsonBody = jsonBody(JsonObject(request.filterKeys { it != "channel_id" }))), "task")
    return TaskRecord.parse(response.obj("task") ?: throw TowerError.Malformed("task")).also {
        require(!it.accessRemoved && Reminders.active(it.raw)) { "The saved reminder is no longer available." }
        if (response.bool("replayed") != true) require(it.state == "ready" && it.title == request.string("title") && it.description == request.string("description")) {
            "The server did not confirm the requested reminder. Retry the same request."
        }
    }
}
