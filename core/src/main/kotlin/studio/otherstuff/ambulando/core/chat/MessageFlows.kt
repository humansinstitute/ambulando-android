package studio.otherstuff.ambulando.core.chat

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.deck.PersonalAgentLauncher
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import java.net.URI
import java.util.UUID

/** Header actions use the source message, never the first row of a truncated message window. */
data class ThreadMenuContext(val channelId: String, val rootMessageId: String?, val root: MessageRecord?, val autopilotSource: MessageRecord?) {
    val reference: String? get() = rootMessageId?.let { "@[Chat thread](mention:chat:$channelId#$it)" }
}

suspend fun StoreReader.threadMenu(workspaceId: String, threadId: String): ThreadMenuContext? = read {
    val record = threadRow(workspaceId, threadId) ?: return@read null
    val sourceId = record.sourceMessageId ?: first(
        "SELECT thread_source_id FROM messages WHERE workspace_id = ? AND thread_id = ? AND thread_source_id IS NOT NULL LIMIT 1",
        workspaceId, threadId,
    ) { it.text(0) }
    val root = sourceId?.let { messageRow(workspaceId, it) }?.takeIf {
        it.channelId == record.channelId && !it.deleted
    }
    // Scan only candidate session rows, newest updated first, independently of the visible message window.
    val session = query(
        "SELECT ${Rows.MESSAGE_COLUMNS} FROM messages WHERE workspace_id = ? AND thread_id = ? AND deleted = 0 AND raw LIKE '%autopilot_session%' ORDER BY updated_at DESC, rowid DESC",
        workspaceId, threadId,
    ) { Rows.message(it, workspaceId) }.firstOrNull { AutopilotSessionLink.sessionId(it.raw) != null }
    ThreadMenuContext(record.channelId, sourceId, root, session)
}

/** Existing Tower branch route, as used by iPhone/web. The response remains Tower's authority. */
suspend fun WorkspaceSync.branchThread(connection: ConnectionRecord, workspace: WorkspaceRecord, message: MessageRecord,
    recipient: String?, requestId: String = "branch:${UUID.randomUUID()}"): ThreadRecord =
    engine.perform("branch:${workspace.id}:${message.id}", SyncPriority.USER) {
        identity.requireReader(connection.signerNpub)
        require(message.workspaceId == workspace.id && message.threadId != null && !message.deleted && !message.accessRemoved && !workspace.accessRemoved)
        require(!store.reader.isChannelAccessRemoved(workspace.id, message.channelId) &&
            !store.reader.isAccessRemoved(workspace.id, FlaggedTable.THREADS, message.threadId) &&
            !store.reader.isAccessRemoved(workspace.id, FlaggedTable.MESSAGES, message.id))
        val payload = buildJsonObject {
            put("branch_point_message_id", message.id); put("client_request_id", requestId)
            putJsonObject("metadata") {
                put("source", "flightdeck_branch")
                recipient?.takeIf(NostrKeys::isValidNpub)?.let { put("inherited_agent_recipient_npub", it) }
            }
        }
        val path = TowerAPI.base(workspace.id) + "/channels/" + TowerAPI.uuid(message.channelId) + "/threads/" + TowerAPI.uuid(message.threadId) + "/branches"
        val response = api(connection).client.exchange(method = "POST", path = path, appNpub = workspace.appNpub,
            jsonBody = jsonBody(payload))
        if (response.status !in 200..299) throw TowerError.Http(response.status, "branch_failed", null)
        val row = parseJsonObject(response.body, "branch").obj("thread") ?: throw TowerError.Malformed("branch")
        val created = ThreadRecord.parse(row)
        if (created.workspaceId != workspace.id || created.channelId != message.channelId) throw TowerError.Malformed("branch")
        store.writer.upsertThreads(workspace.id, listOf(created))
        created
    }

object AutopilotSessionLink {
    fun sessionId(raw: JsonObject): String? {
        val metadata = raw["metadata"] as? JsonObject ?: return null
        if (JsonFields.string(metadata["source"])?.trim() != "autopilot_session") return null
        return JsonFields.lowerUuid(JsonFields.string(metadata["session_id"])?.trim())
    }

    /** Android's browser accepts HTTPS only. Discard paths/query/fragment and never carry URL credentials. */
    fun url(sessionId: String, address: String): String? {
        val id = JsonFields.lowerUuid(sessionId) ?: return null
        val base = runCatching { URI(address.trim()) }.getOrNull() ?: return null
        if (base.scheme != "https" || base.host.isNullOrBlank() || base.userInfo != null) return null
        return URI("https", null, base.host, base.port, "/live/$id", null, null).toString()
    }

    fun resolve(message: MessageRecord, workspaceRaw: JsonObject, launchers: List<PersonalAgentLauncher>): String? {
        val id = sessionId(message.raw) ?: return null
        val metadata = (workspaceRaw["metadata"] as? JsonObject)
            ?: ((workspaceRaw["descriptor"] as? JsonObject)?.get("metadata") as? JsonObject)
        listOf("wingman_harness_url", "autopilot_url").forEach { key ->
            JsonFields.string(metadata?.get(key))?.let { url(id, it)?.let { link -> return link } }
        }
        return launchers.firstOrNull { message.senderNpub != null && it.npub == message.senderNpub }?.let { url(id, it.address) }
    }
}

object GetItDone {
    enum class Output(val label: String, val instruction: String) {
        CHAT("Chat response", "Post the final response back into the source chat thread."),
        DOC("Doc", "Create and attach the linked document deliverable."),
        TASK("Task", "Create the follow-up task needed to complete this work."),
    }

    fun excerpt(messages: List<MessageRecord>): String {
        val joined = messages.takeLast(6).asReversed().joinToString("\n") {
            "${it.senderLabel ?: it.senderNpub ?: "Someone"}: ${MentionCodec.plain(it.body).trim().ifEmpty { "[no text]" }}"
        }
        return if (joined.length <= 256) joined else joined.take(255).trimEnd() + "…"
    }

    fun intent(message: MessageRecord, rootId: String, prompt: String, output: Output, extra: String, assignee: String?, messages: List<MessageRecord>): WriteIntent {
        val source = "${message.channelId}#$rootId"
        val description = listOf("## Request", prompt.trim(), "", "## Source", "@[Chat thread](mention:chat:$source)", "",
            "## Output", output.instruction, "", "## Extra Instructions", extra.trim().ifEmpty { "None." }, "",
            "## Latest Thread Excerpt", excerpt(messages).ifEmpty { "No chat text available." }).joinToString("\n")
        return WriteIntent(WriteKind.TASK_CREATE, null, buildJsonObject {
            put("channel_id", message.channelId); put("title", prompt.trim().take(200)); put("description", description)
            put("state", "ready"); put("priority", "sand"); message.threadId?.let { put("thread_id", it) }
            assignee?.let { put("assigned_to_npub", it) }
            putJsonObject("metadata") {
                putJsonArray("source_links") { add(buildJsonObject { put("type", "chat"); put("id", source) }) }
                assignee?.let { put("assigned_to_npub", it) }
            }
        })
    }
}
