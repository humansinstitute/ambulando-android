package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.chat.ComposerUploadError
import studio.otherstuff.ambulando.core.chat.ComposerUploadException
import studio.otherstuff.ambulando.core.chat.OutboxSender
import studio.otherstuff.ambulando.core.chat.UploadToken
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.crypto.MessageInstructionSignature
import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.docs.DocumentEditError
import studio.otherstuff.ambulando.core.docs.LeaseException
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.workspaces
import studio.otherstuff.ambulando.core.store.isChannelAccessRemoved
import studio.otherstuff.ambulando.core.store.isAccessRemoved
import studio.otherstuff.ambulando.core.store.FlaggedTable
import studio.otherstuff.ambulando.core.store.WriteAcknowledgement
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.WriteState
import studio.otherstuff.ambulando.core.store.acknowledgeWrite
import studio.otherstuff.ambulando.core.store.enqueueWrite
import studio.otherstuff.ambulando.core.store.freezeWrite
import studio.otherstuff.ambulando.core.store.markWrite
import studio.otherstuff.ambulando.core.store.message
import studio.otherstuff.ambulando.core.store.retryWrite
import studio.otherstuff.ambulando.core.store.updateWritePayload
import studio.otherstuff.ambulando.core.store.writeItem
import studio.otherstuff.ambulando.core.store.writes
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.surfaces.personalPins
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.Exchange
import studio.otherstuff.ambulando.core.transport.FileRecord
import studio.otherstuff.ambulando.core.transport.FileTransferError
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.LeaseError
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ObjectTransfers
import studio.otherstuff.ambulando.core.transport.PreparedObject
import studio.otherstuff.ambulando.core.transport.ReactionRecord
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.ViewStateRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.channelDocs
import studio.otherstuff.ambulando.core.transport.channelFiles
import studio.otherstuff.ambulando.core.transport.docComments
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.message
import studio.otherstuff.ambulando.core.transport.newRequestId
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.reactions
import studio.otherstuff.ambulando.core.transport.store
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.stringOrNull
import studio.otherstuff.ambulando.core.transport.task
import studio.otherstuff.ambulando.core.transport.taskComments
import java.io.File

/** How one write attempt ended. */
sealed class WriteOutcome {
    data class Done(val ack: WriteAcknowledgement) : WriteOutcome()
    data class Retryable(val status: Int?, val code: String?, val message: String) : WriteOutcome()
    data class Permanent(val status: Int?, val code: String?, val message: String) : WriteOutcome()
    data class Conflict(val message: String) : WriteOutcome()
}

/**
 * Sends the durable write queue through [SyncEngine] at `USER` priority, like the chat outbox.
 *
 * - Only rows for this connection, origin and the unlocked signer are sent; requests are built from the row.
 * - Writes to one record run in order; a retryable failure holds the later writes of that record.
 * - Idempotency: task creation reuses `client_request_id` (Tower replays). Comments carry `metadata.client_record_id`
 *   and reactions are looked up by author and emoji, and an attempt that may have reached Tower (lost response,
 *   relaunch mid-send) is reconciled by reading before it is sent again. Set-style writes (state, read position,
 *   assignee) are naturally idempotent. Message edits are signed once and frozen. Pins carry `expected_row_version`
 *   and a `mutation_id` Tower replays.
 * - Conflicts: task title/description/priority edits are checked against the version they were made on, because
 *   Tower's PATCH does not check `row_version` for those fields. Someone else's change is never overwritten silently;
 *   the row becomes `CONFLICT` and keeps the user's text until they apply it anyway ([retry] with `force`) or discard it.
 */
class WriteSender(
    val store: LocalStore,
    val engine: SyncEngine,
    val identity: IdentityService,
    val transport: HttpTransport,
    /** Directory holding staged upload bytes until their write is accepted. */
    val staging: File,
    /** Out-of-process transfers for presigned uploads (null: uploads run in-process). */
    val transfers: ObjectTransfers? = null,
    private val random: () -> Double = { Math.random() },
) {
    companion object {
        fun key(connection: ConnectionRecord): String = "writes:${connection.id}"

        fun classify(status: Int, body: ByteArray, attempts: Int, what: String): WriteOutcome {
            val o = parseJsonObjectOrNull(body)
            val code = o?.string("code") ?: o?.string("error")
            val field = o?.obj("details")?.objects("fields")?.firstOrNull()?.string("message")
            return when {
                status == 401 && attempts < 3 -> WriteOutcome.Retryable(status, code, "Tower did not accept the signature; retrying with a fresh one.")
                status == 401 -> WriteOutcome.Permanent(status, code, "Tower did not accept this identity's signature.")
                status == 403 -> WriteOutcome.Permanent(status, code, "You do not have permission to $what.")
                status == 404 -> WriteOutcome.Permanent(status, code, "This ${what.split(' ').lastOrNull() ?: "record"} no longer exists.")
                status == 408 || status == 425 || status == 429 || status in 500..599 ->
                    WriteOutcome.Retryable(status, code, "Tower is unavailable (HTTP $status); will retry.")
                else -> WriteOutcome.Permanent(
                    status, code, field?.let { "Tower rejected this: $it" } ?: "Tower rejected this (HTTP $status${code?.let { ", $it" } ?: ""}).",
                )
            }
        }

        fun classify(error: Throwable, attempts: Int): WriteOutcome = when (error) {
            is TowerError.Http -> classify(error.status, jsonBody(JsonObject(mapOf("code" to JsonPrimitive(error.code ?: "")))), attempts, "make this change")
            is LeaseError, is LeaseException, is FileTransferError, is DocumentEditError ->
                WriteOutcome.Permanent(null, null, error.message ?: "Not saved.")
            is TowerError.Malformed -> WriteOutcome.Retryable(null, null, "Tower's ${error.what} reply could not be read; will check again.")
            else -> WriteOutcome.Retryable(null, null, OutboxSender.networkMessage(error))
        }

        private val AMBIGUOUS = mapOf("ambiguous" to JsonPrimitive(true))
    }

    /** Queues a write for this connection and workspace (never the currently selected one) and starts sending. */
    suspend fun submit(intent: WriteIntent, connection: ConnectionRecord, workspace: WorkspaceRecord, id: String = newRequestId()): WriteItem {
        if (workspace.id.isEmpty() || connection.id.isEmpty()) throw TowerError.UnsafePath
        if (UploadToken.containsAny(intent.payload.toString())) throw ComposerUploadException(ComposerUploadError.UNRESOLVED)
        val item = store.writer.enqueueWrite(connection, workspace.id, workspace.appNpub, intent, id)
        kick(connection)
        return item
    }

    /** User Retry (from failed, retrying or conflict); `force` is "Apply mine" for a conflict. */
    suspend fun retry(id: String, connection: ConnectionRecord, force: Boolean = false) {
        store.writer.retryWrite(id, force)
        kick(connection, force = true)
    }

    suspend fun kick(connection: ConnectionRecord, force: Boolean = false, delayMillis: Long? = null) {
        engine.enqueue(key(connection), SyncPriority.USER, force, delayMillis) { drain(connection) }
    }

    internal suspend fun drain(connection: ConnectionRecord) {
        val state = identity.state()
        if (state !is IdentityState.Unlocked || state.npub != connection.signerNpub) return
        val npub = state.npub
        val client = TowerClient(TowerOrigin(connection.origin), transport, identity)
        val api = TowerAPI(client, transfers)
        val blocked = HashSet<String>()
        var nextRetry: Int? = null
        for (item in store.reader.writes(connection.id, npub)) {
            currentCoroutineContext().ensureActive()
            if (item.origin != connection.origin || item.signerNpub != npub) continue
            val table = when (item.kind) {
                WriteKind.TASK_STATE, WriteKind.TASK_PATCH, WriteKind.TASK_ASSIGN, WriteKind.TASK_COMMENT -> FlaggedTable.TASKS
                WriteKind.DOC_COMMENT -> FlaggedTable.DOCS
                WriteKind.DOC_COMMENT_STATUS -> FlaggedTable.COMMENTS
                WriteKind.MESSAGE_EDIT, WriteKind.MESSAGE_DELETE, WriteKind.REACTION_ADD, WriteKind.REACTION_REMOVE -> FlaggedTable.MESSAGES
                else -> null
            }
            val channel = item.string("channel_id")
            if (store.reader.workspaces(connection.id).any { it.id == item.workspaceId && it.accessRemoved } ||
                channel?.let { store.reader.isChannelAccessRemoved(item.workspaceId, it) } == true ||
                (table != null && item.targetId?.let { store.reader.isAccessRemoved(item.workspaceId, table, it) } == true)) {
                store.writer.markWrite(item.id, WriteState.FAILED, code = "access_removed", error = "No longer available to you. Your change is kept on this device.")
                continue
            }
            if (item.state == WriteState.FAILED || item.state == WriteState.CONFLICT || item.state == WriteState.SENDING) {
                blocked.add(item.groupKey)
                continue
            }
            if (item.groupKey in blocked) continue
            store.writer.markWrite(item.id, WriteState.SENDING, item.lastStatus, item.lastCode, item.lastError)
            val outcome = try {
                perform(item, api, client)
            } catch (e: Throwable) {
                if (e is CancellationException || !currentCoroutineContext().isActive) {
                    withContext(NonCancellable) {
                        runCatching { store.writer.updateWritePayload(item.id, AMBIGUOUS) }
                        runCatching { store.writer.markWrite(item.id, WriteState.QUEUED, item.lastStatus, item.lastCode, item.lastError) }
                    }
                    throw e as? CancellationException ?: CancellationException("cancelled").also { it.initCause(e) }
                }
                classify(e, item.attempts + 1).also { if (it is WriteOutcome.Retryable) runCatching { store.writer.updateWritePayload(item.id, AMBIGUOUS) } }
            }
            when (outcome) {
                is WriteOutcome.Done -> {
                    store.writer.acknowledgeWrite(item.id, item.workspaceId, outcome.ack)
                    if (item.kind == WriteKind.FILE_UPLOAD || item.kind == WriteKind.DOC_CREATE) item.string("staged")?.let { stagedFile(it)?.delete() }
                }
                is WriteOutcome.Retryable -> {
                    store.writer.markWrite(item.id, WriteState.RETRYING, outcome.status, outcome.code, outcome.message, countAttempt = true)
                    blocked.add(item.groupKey)
                    nextRetry = minOf(nextRetry ?: Int.MAX_VALUE, item.attempts + 1)
                }
                is WriteOutcome.Permanent -> {
                    store.writer.markWrite(item.id, WriteState.FAILED, outcome.status, outcome.code, outcome.message, countAttempt = true)
                    blocked.add(item.groupKey)
                }
                is WriteOutcome.Conflict -> {
                    store.writer.markWrite(item.id, WriteState.CONFLICT, 409, "conflict", outcome.message, countAttempt = true)
                    blocked.add(item.groupKey)
                }
            }
        }
        nextRetry?.let { kick(connection, delayMillis = SyncBackoff.delayMillis(it, random())) }
    }

    /** A staged file inside [staging] (names never leave it). */
    private fun stagedFile(name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") return null
        return File(staging, name)
    }

    // MARK: Execution

    private fun reply(data: ByteArray): JsonObject = parseJsonObjectOrNull(data) ?: JsonObject(emptyMap())

    private fun <T> record(data: ByteArray, key: String, make: (JsonObject) -> T): T? {
        val row = reply(data).obj(key) ?: return null
        return runCatching { make(row) }.getOrNull()
    }

    private fun JsonObject.int(key: String): Long? = flexibleLong(this[key])

    private fun body(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

    private fun permanent(message: String) = WriteOutcome.Permanent(null, null, message)

    internal suspend fun perform(item: WriteItem, api: TowerAPI, client: TowerClient): WriteOutcome {
        val p = item.payloadObject
        val w = item.workspaceId
        val app = item.appNpub
        val base = TowerAPI.base(w)
        val ambiguous = p.bool("ambiguous") == true
        val attempts = item.attempts + 1
        suspend fun send(method: String, path: String, json: JsonObject?): Exchange = client.exchange(method, path, app, json?.let { jsonBody(it) })
        fun ok(r: Exchange) = r.status in 200..299

        when (item.kind) {
            WriteKind.TASK_CREATE -> {
                val channel = p.string("channel_id") ?: return permanent("Missing channel.")
                val o = linkedMapOf<String, JsonElement>(
                    "client_request_id" to JsonPrimitive(item.id), "title" to (p["title"] ?: JsonPrimitive("")),
                    "state" to (p["state"] ?: JsonPrimitive("new")), "priority" to (p["priority"] ?: JsonPrimitive("sand")),
                )
                p.string("description")?.takeIf { it.isNotEmpty() }?.let { o["description"] = JsonPrimitive(it) }
                p.string("assigned_to_npub")?.takeIf { it.isNotEmpty() }?.let { o["assigned_to_npub"] = JsonPrimitive(it) }
                val r = send("POST", base + "/channels/" + TowerAPI.uuid(channel) + "/tasks", JsonObject(o))
                if (ok(r)) record(r.body, "task", TaskRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(tasks = listOf(it))) }
                if (r.status == 409) return WriteOutcome.Permanent(409, "idempotency_conflict", "Tower already has a different task with this request.")
                return classify(r.status, r.body, attempts, "create tasks in this channel")
            }

            WriteKind.TASK_STATE -> {
                val task = item.targetId
                val state = p.string("state")
                if (task == null || state == null) return permanent("Missing task.")
                val r = send("POST", base + "/tasks/" + TowerAPI.uuid(task) + "/state", body("state" to JsonPrimitive(state)))
                if (ok(r)) record(r.body, "task", TaskRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(tasks = listOf(it))) }
                return classify(r.status, r.body, attempts, "change this task")
            }

            WriteKind.TASK_PATCH -> {
                val taskId = item.targetId
                val patch = p.obj("patch")
                val baseValues = p.obj("base")
                val baseVersion = p.int("base_row_version")
                if (taskId == null || patch == null || baseValues == null || baseVersion == null) return permanent("Missing task edit.")
                val current = api.task(w, app, taskId)
                val currentValues = mapOf("title" to current.title, "description" to (current.description ?: ""), "priority" to current.priority)
                val wanted = patch.mapValues { it.value.stringOrNull() ?: "" }
                if (wanted.all { currentValues[it.key] == it.value }) return WriteOutcome.Done(WriteAcknowledgement(tasks = listOf(current)))
                if (current.rowVersion != baseVersion && p.bool("force") != true) {
                    val changed = wanted.keys.filter { currentValues[it] != (baseValues.string(it) ?: "") }
                    if (changed.isNotEmpty()) {
                        return WriteOutcome.Conflict("Someone else changed the ${changed.sorted().joinToString(" and ")} while you were editing. Your text is kept.")
                    }
                }
                val o = LinkedHashMap<String, JsonElement>(patch)
                if (o["description"]?.stringOrNull()?.isEmpty() == true) o["description"] = JsonNull
                val r = send("PATCH", base + "/tasks/" + TowerAPI.uuid(taskId), JsonObject(o))
                if (ok(r)) record(r.body, "task", TaskRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(tasks = listOf(it))) }
                return classify(r.status, r.body, attempts, "edit this task")
            }

            WriteKind.TASK_ASSIGN -> {
                val taskId = item.targetId
                val baseVersion = p.int("base_row_version")
                if (taskId == null || baseVersion == null) return permanent("Missing task.")
                val wanted = p.string("assigned_to_npub")?.takeIf { it.isNotEmpty() }
                val previous = p.string("base_assigned_to_npub")?.takeIf { it.isNotEmpty() }
                var version = baseVersion
                repeat(2) {
                    val r = send("PATCH", base + "/tasks/" + TowerAPI.uuid(taskId),
                        body("assigned_to_npub" to (wanted?.let(::JsonPrimitive) ?: JsonNull), "row_version" to JsonPrimitive(version)))
                    if (ok(r)) record(r.body, "task", TaskRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(tasks = listOf(it))) }
                    if (r.status != 409) return classify(r.status, r.body, attempts, "assign this task")
                    val current = api.task(w, app, taskId)
                    if (current.assignedToNpub == wanted) return WriteOutcome.Done(WriteAcknowledgement(tasks = listOf(current)))
                    // Only the row version moved (another field changed): the assignee is still what the user saw.
                    if (current.assignedToNpub != previous && p.bool("force") != true) {
                        return WriteOutcome.Conflict("Someone else changed who this task is assigned to.")
                    }
                    version = current.rowVersion
                }
                return WriteOutcome.Retryable(409, "stale_row_version", "The task kept changing; will retry.")
            }

            WriteKind.TASK_COMMENT, WriteKind.DOC_COMMENT -> {
                val isTask = item.kind == WriteKind.TASK_COMMENT
                val parent = item.targetId
                val text = p.string("body")
                if (parent == null || text == null) return permanent("Missing comment.")
                val parentType = if (isTask) CommentRecord.Parent.TASK else CommentRecord.Parent.DOC
                if (ambiguous) {
                    val existing = if (isTask) api.taskComments(w, app, parent) else api.docComments(w, app, parent)
                    existing.firstOrNull { it.clientRecordId == item.id }?.let { return WriteOutcome.Done(WriteAcknowledgement(comments = listOf(it))) }
                }
                val metadata = linkedMapOf<String, JsonElement>("client_record_id" to JsonPrimitive(item.id))
                if (!isTask) {
                    p.obj("metadata")?.forEach { (k, v) -> metadata.putIfAbsent(k, v) }
                    metadata["comment_status"] = JsonPrimitive("open")
                }
                val o = linkedMapOf<String, JsonElement>("body" to JsonPrimitive(text), "metadata" to JsonObject(metadata))
                p.string("parent_comment_id")?.let { o["parent_comment_id"] = JsonPrimitive(it) }
                val r = send("POST", base + (if (isTask) "/tasks/" else "/docs/") + TowerAPI.uuid(parent) + "/comments", JsonObject(o))
                if (ok(r)) record(r.body, "comment") { CommentRecord.parse(it, parentType) }?.let { return WriteOutcome.Done(WriteAcknowledgement(comments = listOf(it))) }
                return classify(r.status, r.body, attempts, if (isTask) "comment on this task" else "comment on this document")
            }

            WriteKind.DOC_COMMENT_STATUS -> {
                val comment = item.targetId
                val doc = p.string("doc_id")
                val status = p.string("status")
                var version = p.int("row_version")
                if (comment == null || doc == null || status == null || version == null) return permanent("Missing comment.")
                repeat(2) {
                    val r = send("PATCH", base + "/docs/" + TowerAPI.uuid(doc) + "/comments/" + TowerAPI.uuid(comment),
                        body("comment_status" to JsonPrimitive(status), "row_version" to JsonPrimitive(version)))
                    if (ok(r)) record(r.body, "comment") { CommentRecord.parse(it, CommentRecord.Parent.DOC) }
                        ?.let { return WriteOutcome.Done(WriteAcknowledgement(comments = listOf(it))) }
                    if (r.status != 409) return classify(r.status, r.body, attempts, "resolve this comment")
                    val current = api.docComments(w, app, doc).firstOrNull { it.id == comment }
                        ?: return WriteOutcome.Permanent(404, null, "This comment no longer exists.")
                    if (current.status == status) return WriteOutcome.Done(WriteAcknowledgement(comments = listOf(current)))
                    version = current.rowVersion
                }
                return WriteOutcome.Retryable(409, "stale_row_version", "The comment kept changing; will retry.")
            }

            WriteKind.MESSAGE_EDIT -> {
                val messageId = item.targetId ?: return permanent("Missing message.")
                val wantedBody = p.string("body") ?: ""
                var request = item.request
                if (request == null) {
                    val channel = p.string("channel_id")
                    val version = p.int("row_version")
                    if (channel == null || version == null) return permanent("Missing message.")
                    val signed = MessageInstructionSignature.sign(wantedBody, w, channel, p.string("thread_id"), messageId, (version + 1).toInt(), identity)
                    if (signed.signerNpub != item.signerNpub) throw IdentityError.SignerMismatch
                    val built = jsonBody(body("body" to JsonPrimitive(wantedBody), "row_version" to JsonPrimitive(version),
                        "mentions" to (p["mentions"] ?: JsonArray(emptyList())), "message_signature" to signed.requestObject))
                    store.writer.freezeWrite(item.id, built)
                    request = store.reader.writeItem(item.id)?.request
                }
                if (request == null) return permanent("Missing message.")
                val r = client.exchange("PATCH", base + "/messages/" + TowerAPI.uuid(messageId), app, request)
                if (ok(r)) record(r.body, "message", MessageRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(messages = listOf(it))) }
                if (r.status == 409) {
                    val current = api.message(w, app, messageId)
                    if (current != null && current.body == wantedBody) return WriteOutcome.Done(WriteAcknowledgement(messages = listOf(current)))
                    return WriteOutcome.Conflict("This message changed on another device. Your edit was not applied.")
                }
                if (r.status == 403) return WriteOutcome.Permanent(403, "message_author_required", "Only the author can edit this message.")
                return classify(r.status, r.body, attempts, "edit this message")
            }

            WriteKind.MESSAGE_DELETE -> {
                val messageId = item.targetId
                val version = p.int("row_version")
                if (messageId == null || version == null) return permanent("Missing message.")
                val r = client.exchange("DELETE", base + "/messages/" + TowerAPI.uuid(messageId), app, null, listOf("row_version" to version.toString()))
                if (ok(r)) record(r.body, "message", MessageRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(messages = listOf(it.asDeleted))) }
                if (r.status == 404) return WriteOutcome.Done(WriteAcknowledgement(messages = deletedLocal(w, messageId)))
                if (r.status == 409) {
                    val current = api.message(w, app, messageId)
                    if (current == null || current.deleted) return WriteOutcome.Done(WriteAcknowledgement(messages = deletedLocal(w, messageId)))
                    return WriteOutcome.Conflict("This message changed on another device, so it was not deleted.")
                }
                return classify(r.status, r.body, attempts, "delete this message")
            }

            WriteKind.REACTION_ADD -> {
                val target = item.targetId
                val emoji = p.string("emoji")
                val type = p.string("target_type")
                if (target == null || emoji == null || type == null) return permanent("Missing reaction.")
                if (ambiguous) {
                    api.reactions(w, app, type, target).firstOrNull { it.reactorNpub == item.signerNpub && it.emoji == emoji }
                        ?.let { return WriteOutcome.Done(WriteAcknowledgement(reactions = listOf(it))) }
                }
                val r = send("POST", "$base/reactions", body("target_type" to JsonPrimitive(type), "target_id" to JsonPrimitive(target), "emoji" to JsonPrimitive(emoji)))
                if (ok(r)) record(r.body, "reaction", ReactionRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(reactions = listOf(it))) }
                return classify(r.status, r.body, attempts, "react here")
            }

            WriteKind.REACTION_REMOVE -> {
                val reaction = p.string("reaction_id") ?: return permanent("Missing reaction.")
                val r = send("DELETE", base + "/reactions/" + TowerAPI.uuid(reaction), null)
                if (ok(r) || r.status == 404) return WriteOutcome.Done(WriteAcknowledgement(removedReactionIds = listOf(reaction)))
                return classify(r.status, r.body, attempts, "remove this reaction")
            }

            WriteKind.VIEW_MARK -> {
                val resources = p.objects("resources") ?: emptyList()
                if (resources.isEmpty()) return WriteOutcome.Done(WriteAcknowledgement())
                val single = resources.singleOrNull()
                val type = single?.string("resource_type")
                val id = single?.string("resource_id")
                val version = single?.int("viewed_activity_version")
                val r: Exchange
                if (type != null && id != null && version != null) {
                    r = send("PUT", base + "/resource-view-states/" + type + "/" + TowerAPI.uuid(id), body("viewed_activity_version" to JsonPrimitive(version)))
                    if (ok(r)) record(r.body, "state", ViewStateRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(viewStates = listOf(it))) }
                    // Gone, or already read past this version: nothing left to tell Tower.
                    if (r.status == 404 || r.status == 409) return WriteOutcome.Done(WriteAcknowledgement())
                } else {
                    val list = resources.take(500).mapNotNull { o ->
                        val t = o.string("resource_type") ?: return@mapNotNull null
                        val i = o.string("resource_id") ?: return@mapNotNull null
                        body("resource_type" to JsonPrimitive(t), "resource_id" to JsonPrimitive(i))
                    }
                    r = send("POST", "$base/resource-view-states/mark-viewed", body("resources" to JsonArray(list)))
                    if (ok(r)) {
                        val states = (reply(r.body).objects("states") ?: emptyList()).mapNotNull { runCatching { ViewStateRecord.parse(it) }.getOrNull() }
                        return WriteOutcome.Done(WriteAcknowledgement(viewStates = states))
                    }
                }
                return classify(r.status, r.body, attempts, "mark this read")
            }

            WriteKind.PIN_SET -> return setPin(item, api, ::send)

            WriteKind.FILE_UPLOAD, WriteKind.DOC_CREATE -> {
                val channel = p.string("channel_id")
                val staged = p.string("staged")
                val contentType = p.string("content_type")
                val name = p.string("file_name")
                if (channel == null || staged == null || contentType == null || name == null) return permanent("Missing upload.")
                val file = stagedFile(staged)?.takeIf { it.isFile } ?: return permanent("The file to upload is no longer on this device.")
                val bytes = file.readBytes()
                val previous = p.string("prepared_object_id")?.let { id -> p.string("prepared_upload_url")?.let { PreparedObject(id, it) } }
                val objectId = api.store(w, app, bytes, contentType, name, previous) { fresh ->
                    store.writer.updateWritePayload(item.id, mapOf("prepared_object_id" to JsonPrimitive(fresh.objectId),
                        "prepared_upload_url" to JsonPrimitive(fresh.uploadURL)))
                }
                currentCoroutineContext().ensureActive()
                if (item.kind == WriteKind.FILE_UPLOAD) {
                    suspend fun found(): FileRecord? = api.channelFiles(w, app, channel).items.firstOrNull { it.storageObjectId == objectId }
                    if (ambiguous) found()?.let { return WriteOutcome.Done(WriteAcknowledgement(files = listOf(it))) }
                    val o = linkedMapOf<String, JsonElement>("storage_object_id" to JsonPrimitive(objectId), "display_name" to (p["display_name"] ?: JsonPrimitive(name)))
                    p.string("folder_id")?.let { o["folder_id"] = JsonPrimitive(it) }
                    val r = send("POST", base + "/channels/" + TowerAPI.uuid(channel) + "/files", JsonObject(o))
                    if (ok(r)) record(r.body, "file", FileRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(files = listOf(it))) }
                    if (r.status == 409) found()?.let { return WriteOutcome.Done(WriteAcknowledgement(files = listOf(it))) }
                    return classify(r.status, r.body, attempts, "upload files to this channel")
                } else {
                    suspend fun found(): DocRecord? = api.channelDocs(w, app, channel).items.firstOrNull { it.storageObjectId == objectId }
                    if (ambiguous) found()?.let { return WriteOutcome.Done(WriteAcknowledgement(docs = listOf(it))) }
                    val summary = DocumentBody(bytes, contentType).markdown
                    val r = send("POST", base + "/channels/" + TowerAPI.uuid(channel) + "/docs", body(
                        "title" to (p["title"] ?: JsonPrimitive("Untitled document")), "storage_object_id" to JsonPrimitive(objectId),
                        "summary" to JsonPrimitive(summary)))
                    if (ok(r)) record(r.body, "doc", DocRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(docs = listOf(it))) }
                    if (r.status == 409) found()?.let { return WriteOutcome.Done(WriteAcknowledgement(docs = listOf(it))) }
                    return classify(r.status, r.body, attempts, "create documents in this channel")
                }
            }
        }
    }

    /**
     * Pin or unpin. Tower checks `expected_row_version` and replays a known `mutation_id`, so resending after a lost
     * reply is safe. On a version conflict the pins are re-read: if Tower already has what the user asked for, the write
     * is done; otherwise it is resent once against the current version with a fresh mutation id (the user's latest intent
     * wins, as it does on the web). A target the viewer may no longer read is refused permanently and shown as failed.
     */
    private suspend fun setPin(item: WriteItem, api: TowerAPI, send: suspend (String, String, JsonObject?) -> Exchange): WriteOutcome {
        val p = item.payloadObject
        val type = PinRecord.ResourceType.from(p.string("resource_type"))
        val resource = item.targetId
        val pinned = p.bool("pinned")
        var expected = p.int("expected_row_version")
        var mutation = p.string("mutation_id")
        if (type == null || resource == null || pinned == null || expected == null || mutation == null) return permanent("Missing pin.")
        val path = TowerAPI.base(item.workspaceId) + "/me/pins/" + type.wire + "/" + TowerAPI.uuid(resource)
        for (attempt in 0 until 2) {
            val r = send("PUT", path, body("pinned" to JsonPrimitive(pinned), "expected_row_version" to JsonPrimitive(expected),
                "mutation_id" to JsonPrimitive(mutation)))
            if (r.status in 200..299) record(r.body, "pin", PinRecord::parse)?.let { return WriteOutcome.Done(WriteAcknowledgement(pins = listOf(it))) }
            val code = reply(r.body).string("code") ?: reply(r.body).string("error")
            if (!(r.status == 409 && attempt == 0 && (code == "thread_pin_conflict" || code == "mutation_id_reused"))) {
                if (r.status == 403) return WriteOutcome.Permanent(403, code, "You can no longer pin this.")
                return classify(r.status, r.body, item.attempts + 1, "change this pin")
            }
            val current = api.personalPins(item.workspaceId, item.appNpub).pins.firstOrNull { it.resourceType == type && it.resourceId == resource }
            if ((current?.pinned ?: false) == pinned) return WriteOutcome.Done(WriteAcknowledgement(pins = listOfNotNull(current)))
            expected = current?.rowVersion ?: 0
            mutation = newRequestId()
            store.writer.updateWritePayload(item.id, mapOf("expected_row_version" to JsonPrimitive(expected), "mutation_id" to JsonPrimitive(mutation)))
        }
        return WriteOutcome.Conflict("Your pin changed on another device; tap Retry to apply this change.")
    }

    private suspend fun deletedLocal(workspaceId: String, id: String): List<MessageRecord> =
        listOfNotNull(store.reader.message(workspaceId, id)?.asDeleted)
}

/** A tombstone that wins the row-version guard over the local copy. */
val MessageRecord.asDeleted: MessageRecord
    get() = copy(body = if (deleted) body else "", rowVersion = if (deleted) rowVersion else rowVersion + 1, deleted = true)
