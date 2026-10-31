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

/** Why a rename did not happen, worded for the person (the typed route's refusals, not raw HTTP). */
class ThreadRenameException(message: String) : Exception(message)

/** The title the web sends: whitespace collapsed and trimmed (up to [MAX] characters). */
object ThreadTitle {
    const val MAX = 120

    fun normalized(value: String): String {
        val title = value.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (title.isEmpty()) throw ThreadRenameException("Enter a thread title.")
        if (studio.otherstuff.ambulando.core.docs.TextSupport.graphemeCount(title) > MAX) throw ThreadRenameException("Thread titles can be up to $MAX characters.")
        return title
    }
}

/**
 * Renames a conversation like the web and iPhone: `PATCH /threads/:t` with the new `title` and the stored `row_version`.
 * It is a confirmed online request, not queued; Tower decides who may rename. A stale version is refreshed from Tower once
 * and retried only when the title is still the one the person started from, otherwise the other change is reported and
 * nothing is overwritten.
 */
suspend fun WorkspaceSync.renameThread(connection: ConnectionRecord, workspace: WorkspaceRecord, threadId: String, rawTitle: String,
    rowVersion: Long?, originalTitle: String?): ThreadRecord = engine.perform("rename-thread:${workspace.id}:$threadId", SyncPriority.USER) {
    val title = ThreadTitle.normalized(rawTitle)
    if (rowVersion == null || rowVersion <= 0) throw ThreadRenameException("The original thread version is unavailable. Reopen the thread to load it.")
    if (workspace.accessRemoved || store.reader.isAccessRemoved(workspace.id, FlaggedTable.THREADS, threadId)) throw ThreadRenameException("This thread is no longer available to you.")
    try { identity.requireSigner(connection.signerNpub) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { throw ThreadRenameException("Unlock to rename this thread.") }
    val path = TowerAPI.base(workspace.id) + "/threads/" + TowerAPI.uuid(threadId)
    val client = api(connection).client
    suspend fun exchange(method: String, body: JsonObject?): Pair<Int, JsonObject?> = try {
        val reply = client.exchange(method, path, workspace.appNpub, body?.let(::jsonBody))
        reply.status to parseJsonObjectOrNull(reply.body)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        throw ThreadRenameException("Could not reach Tower. Check your connection and try again.")
    }
    fun stale(reply: Pair<Int, JsonObject?>) = reply.first == 409 && (reply.second?.string("code") ?: reply.second?.string("error")) == "stale_row_version"
    fun refuse(status: Int): Nothing = throw ThreadRenameException(when (status) {
        401, 403 -> "You don't have permission to rename this thread."
        404 -> "This thread is no longer available."
        else -> "Tower could not rename this thread (HTTP $status). Nothing was changed."
    })
    fun patch(version: Long) = buildJsonObject { put("title", title); put("row_version", version) }
    var reply = exchange("PATCH", patch(rowVersion))
    if (stale(reply)) {
        val fresh = exchange("GET", null)
        if (fresh.first !in 200..299) refuse(fresh.first)
        val row = fresh.second?.obj("thread")
        val version = (row?.get("row_version") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            ?: throw ThreadRenameException("Tower did not return the thread's current version. Nothing was changed.")
        val current = ThreadRecord.parse(row)
        store.writer.upsertThreads(workspace.id, listOf(current))
        if (current.title != originalTitle) throw ThreadRenameException("This thread was renamed elsewhere. Current title: “${current.title ?: "Untitled"}”. Cancel and reopen to edit the current title.")
        reply = exchange("PATCH", patch(version))
        if (stale(reply)) throw ThreadRenameException("This thread was renamed elsewhere while you were saving. Reopen it and try again.")
    }
    if (reply.first !in 200..299) refuse(reply.first)
    val row = reply.second?.obj("thread") ?: throw ThreadRenameException("Tower's answer had no thread.")
    val thread = ThreadRecord.parse(row)
    if (thread.workspaceId != workspace.id || thread.id != threadId.lowercase()) throw ThreadRenameException("Tower's answer was for a different thread. Nothing was changed.")
    store.writer.upsertThreads(workspace.id, listOf(thread))
    thread
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
