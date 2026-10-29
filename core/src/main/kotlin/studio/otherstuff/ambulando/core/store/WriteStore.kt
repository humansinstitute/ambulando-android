package studio.otherstuff.ambulando.core.store

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.FileRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.NotificationItem
import studio.otherstuff.ambulando.core.transport.ReactionRecord
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.ViewStateRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.string
import java.util.UUID

// Non-chat mutations, kept durably until Tower accepts them (iOS WriteStore.swift). The chat outbox stays separate: it
// freezes signed message bytes. Each row is bound to its connection, origin, signer and workspace; requests are built
// only from it.

enum class WriteKind(val wire: String) {
    TASK_CREATE("task.create"),
    TASK_STATE("task.state"),
    TASK_PATCH("task.patch"),
    TASK_ASSIGN("task.assign"),
    TASK_COMMENT("task.comment"),
    DOC_CREATE("doc.create"),
    DOC_COMMENT("doc.comment"),
    DOC_COMMENT_STATUS("doc.comment.status"),
    MESSAGE_EDIT("message.edit"),
    MESSAGE_DELETE("message.delete"),
    REACTION_ADD("reaction.add"),
    REACTION_REMOVE("reaction.remove"),
    VIEW_MARK("view.mark"),
    FILE_UPLOAD("file.upload"),
    /** Pin or unpin a thread, task, document or channel for this viewer (`PUT /me/pins/:type/:id`). */
    PIN_SET("pin.set"),
    /** Versioned relative move through the existing personal pin order contract. */
    PIN_ORDER("pin.order"),
    /** One notification's read/dismissed/archived flags, checked against the row version the user saw (`PATCH .../notifications/items/:id`). */
    NOTIFICATION_STATE("notification.state"),
    /** "Mark all read" and friends, bounded by the change sequence the user observed (`POST .../notifications/bulk`). */
    NOTIFICATION_BULK("notification.bulk");

    companion object {
        fun from(wire: String?): WriteKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class WriteState(val wire: String) {
    QUEUED("queued"),
    SENDING("sending"),
    /** Network or server problem; retried automatically. */
    RETRYING("retrying"),
    /** Tower refused it; the user retries or discards. */
    FAILED("failed"),
    /** Someone else changed the record first; the user's change is kept for them to apply anyway or discard. */
    CONFLICT("conflict");

    companion object {
        fun from(wire: String?): WriteState = entries.firstOrNull { it.wire == wire } ?: FAILED
    }
}

class WriteItem(
    val id: String,
    val connectionId: String,
    val origin: String,
    val signerNpub: String,
    val workspaceId: String,
    val appNpub: String,
    val kind: WriteKind,
    val targetId: String?,
    val groupKey: String,
    /** JSON object bytes, keys sorted. */
    val payload: ByteArray,
    val request: ByteArray?,
    val state: WriteState,
    val attempts: Int,
    val lastStatus: Int?,
    val lastCode: String?,
    val lastError: String?,
    val createdAt: String,
) {
    val payloadObject: JsonObject get() = parseJsonObjectOrNull(payload) ?: JsonObject(emptyMap())
    fun string(key: String): String? = payloadObject.string(key)
    val isPending: Boolean get() = state != WriteState.FAILED && state != WriteState.CONFLICT

    private fun fields(): List<Any?> = listOf(id, connectionId, origin, signerNpub, workspaceId, appNpub, kind, targetId, groupKey,
        payload.toList(), request?.toList(), state, attempts, lastStatus, lastCode, lastError, createdAt)

    override fun equals(other: Any?): Boolean = other is WriteItem && other.fields() == fields()
    override fun hashCode(): Int = fields().hashCode()
    override fun toString(): String = "WriteItem(id=$id, kind=${kind.wire}, state=${state.wire})"
}

/** What the screen intends; the queue fills in identity and grouping. */
data class WriteIntent(val kind: WriteKind, val targetId: String?, val payload: JsonObject) {
    /** Writes to the same record run in order; independent records do not wait on each other. */
    val groupKey: String get() = targetId?.let { "${kind.wire.substringBefore('.')}:$it" } ?: kind.wire
}

/** Records Tower returned for an accepted write, stored in the same transaction that retires it. */
data class WriteAcknowledgement(
    val tasks: List<TaskRecord> = emptyList(),
    val comments: List<CommentRecord> = emptyList(),
    val messages: List<MessageRecord> = emptyList(),
    val reactions: List<ReactionRecord> = emptyList(),
    val removedReactionIds: List<String> = emptyList(),
    val viewStates: List<ViewStateRecord> = emptyList(),
    val files: List<FileRecord> = emptyList(),
    val docs: List<DocRecord> = emptyList(),
    val pins: List<PinRecord> = emptyList(),
    /** Tower's current notification items (the accepted write, or the item a conflict was reported against). */
    val notifications: List<NotificationItem> = emptyList(),
    /** Notification items whose optimistic overlay ends without a replacement row (an acknowledgement replay). */
    val notificationOverlayClears: List<String> = emptyList(),
    /** Notification items whose overlay Tower accepted without a replacement row (a bulk change): the overlay becomes the server flags. */
    val notificationOverlayCommits: List<String> = emptyList(),
)

private const val WRITE_COLUMNS = """id, connection_id, origin, signer_npub, workspace_id, app_npub, kind, target_id, group_key, payload, request, state,
    attempts, last_status, last_code, last_error, created_at"""

private fun writeOf(r: SqlRow): WriteItem? {
    val kind = WriteKind.from(r.text(6)) ?: return null
    return WriteItem(
        r.text(0)!!, r.text(1)!!, r.text(2)!!, r.text(3)!!, r.text(4)!!, r.text(5)!!, kind, r.text(7), r.text(8)!!, r.blob(9),
        if (r.isNull(10)) null else r.blob(10), WriteState.from(r.text(11)), r.int(12), if (r.isNull(13)) null else r.int(13), r.text(14),
        r.text(15), r.text(16)!!,
    )
}

// MARK: Writer

suspend fun StoreWriter.enqueueWrite(
    connection: ConnectionRecord, workspaceId: String, appNpub: String, intent: WriteIntent, id: String = UUID.randomUUID().toString().lowercase(),
): WriteItem = write {
    val stamp = StoreTime.now()
    val payload = jsonBody(intent.payload)
    db.run(
        """INSERT INTO writes(id, connection_id, origin, signer_npub, workspace_id, app_npub, kind, target_id, group_key, payload, request, state,
                              attempts, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,?,0,?,?)""",
        id, connection.id, connection.origin, connection.signerNpub, workspaceId, appNpub, intent.kind.wire, intent.targetId, intent.groupKey,
        payload, WriteState.QUEUED.wire, stamp, stamp,
    )
    emit(StoreTable.WRITES, workspaceId)
    WriteItem(id, connection.id, connection.origin, connection.signerNpub, workspaceId, appNpub, intent.kind, intent.targetId, intent.groupKey,
        payload, null, WriteState.QUEUED, 0, null, null, null, stamp)
}

suspend fun StoreWriter.enqueueWrite(connection: ConnectionRecord, workspace: WorkspaceRecord, intent: WriteIntent): WriteItem =
    enqueueWrite(connection, workspace.id, workspace.appNpub, intent)

suspend fun StoreWriter.markWrite(
    id: String, state: WriteState, status: Int? = null, code: String? = null, error: String? = null, countAttempt: Boolean = false,
) = write {
    db.run(
        "UPDATE writes SET state = ?, last_status = ?, last_code = ?, last_error = ?, attempts = attempts + ?, updated_at = ? WHERE id = ?",
        state.wire, status, code, error, if (countAttempt) 1 else 0, StoreTime.now(), id,
    )
    db.writeWorkspace(id)?.let { emit(StoreTable.WRITES, it) }
}

/** Merges keys into a row's payload (upload progress, the ambiguity flag, "apply anyway"). */
suspend fun StoreWriter.updateWritePayload(id: String, merging: Map<String, JsonElement>) = write { mergePayload(id, merging) }

private fun WriteTx.mergePayload(id: String, merging: Map<String, JsonElement>) {
    val current = db.first("SELECT payload FROM writes WHERE id = ?", id) { it.blob(0) } ?: return
    val payload = LinkedHashMap(parseJsonObjectOrNull(current) ?: JsonObject(emptyMap()))
    payload.putAll(merging)
    db.run("UPDATE writes SET payload = ?, updated_at = ? WHERE id = ?", jsonBody(JsonObject(payload)), StoreTime.now(), id)
}

/** Stores the exact request bytes once (signed instructions); later attempts resend them unchanged. */
suspend fun StoreWriter.freezeWrite(id: String, request: ByteArray): Boolean = write {
    db.run("UPDATE writes SET request = ?, updated_at = ? WHERE id = ? AND request IS NULL", request, StoreTime.now(), id) > 0
}

/** Tower accepted the write: store what it returned and retire the row together. */
suspend fun StoreWriter.acknowledgeWrite(id: String, workspaceId: String, ack: WriteAcknowledgement) {
    write {
        emit(StoreTable.WRITES, workspaceId)
        if (ack.tasks.isNotEmpty()) { writeTasks(ack.tasks.filter { it.workspaceId == workspaceId }); emit(StoreTable.TASKS, workspaceId) }
        if (ack.messages.isNotEmpty()) writeMessages(ack.messages.filter { it.workspaceId == workspaceId })
        if (ack.docs.isNotEmpty()) { writeDocs(ack.docs.filter { it.workspaceId == workspaceId }); emit(StoreTable.DOCS, workspaceId) }
        if (ack.reactions.isNotEmpty()) { writeReactions(ack.reactions.filter { it.workspaceId == workspaceId }); emit(StoreTable.REACTIONS, workspaceId) }
        if (ack.pins.isNotEmpty()) {
            // Stored under the connection that made the write (pins are private to that signer).
            db.textValue("SELECT connection_id FROM writes WHERE id = ?", id)?.let { connection ->
                writePins(connection, workspaceId, ack.pins.filter { it.workspaceId == workspaceId }, complete = false)
                emit(StoreTable.PINS, workspaceId)
            }
        }
        if (ack.notifications.isNotEmpty() || ack.notificationOverlayClears.isNotEmpty() || ack.notificationOverlayCommits.isNotEmpty()) {
            db.textValue("SELECT connection_id FROM writes WHERE id = ?", id)?.let { connection ->
                for (item in ack.notifications) adoptNotificationItem(connection, workspaceId, item)
                for (itemId in ack.notificationOverlayClears) clearNotificationOverlayTx(connection, workspaceId, itemId)
                for (itemId in ack.notificationOverlayCommits) commitNotificationOverlayTx(connection, workspaceId, itemId)
            }
        }
        for (reaction in ack.removedReactionIds) {
            db.run("DELETE FROM reactions WHERE workspace_id = ? AND id = ?", workspaceId, reaction)
            emit(StoreTable.REACTIONS, workspaceId)
        }
        db.run("DELETE FROM writes WHERE id = ?", id)
    }
    // Rows with their own upsert rules (comments, files, view states) use them after the retire commit.
    if (ack.comments.isNotEmpty()) upsertComments(workspaceId, ack.comments)
    if (ack.files.isNotEmpty()) upsertFiles(workspaceId, ack.files)
    if (ack.viewStates.isNotEmpty()) upsertViewStates(workspaceId, ack.viewStates)
}

/** Removes a pending write (never one that is `SENDING`). */
suspend fun StoreWriter.discardWrite(id: String) = write {
    val workspace = db.writeWorkspace(id)
    db.run("DELETE FROM writes WHERE id = ? AND state <> ?", id, WriteState.SENDING.wire)
    workspace?.let { emit(StoreTable.WRITES, it) }
}

/** Removes a write the sender itself is finishing (including one it marked `SENDING`): rejected or superseded work. */
suspend fun StoreWriter.finishWrite(id: String) = write {
    val workspace = db.writeWorkspace(id)
    db.run("DELETE FROM writes WHERE id = ?", id)
    workspace?.let { emit(StoreTable.WRITES, it) }
}

/** User retry from `FAILED`, `RETRYING` or `CONFLICT`; `force` records "apply anyway" in the payload. */
suspend fun StoreWriter.retryWrite(id: String, force: Boolean = false) = write {
    if (force) mergePayload(id, mapOf("force" to JsonPrimitive(true)))
    db.run(
        "UPDATE writes SET state = ?, updated_at = ? WHERE id = ? AND state IN (?, ?, ?)",
        WriteState.QUEUED.wire, StoreTime.now(), id, WriteState.FAILED.wire, WriteState.RETRYING.wire, WriteState.CONFLICT.wire,
    )
    db.writeWorkspace(id)?.let { emit(StoreTable.WRITES, it) }
}

/**
 * After relaunch nothing is in flight. An interrupted write may have reached Tower, so it is flagged `ambiguous` in its
 * payload and reconciled before it is sent again.
 */
suspend fun StoreWriter.resetInterruptedWrites() = write {
    val ids = db.query("SELECT id FROM writes WHERE state = ?", WriteState.SENDING.wire) { it.text(0)!! }
    for (id in ids) mergePayload(id, mapOf("ambiguous" to JsonPrimitive(true)))
    db.run("UPDATE writes SET state = ? WHERE state = ?", WriteState.QUEUED.wire, WriteState.SENDING.wire)
    if (ids.isNotEmpty()) emit(StoreTable.WRITES)
}

private fun SqlConnection.writeWorkspace(id: String): String? = textValue("SELECT workspace_id FROM writes WHERE id = ?", id)

// MARK: Reader

/** Every write of one connection and signer, oldest first (the sender's work list). */
suspend fun StoreReader.writes(connectionId: String, signerNpub: String): List<WriteItem> = read {
    query("SELECT $WRITE_COLUMNS FROM writes WHERE connection_id = ? AND signer_npub = ? ORDER BY created_at, rowid", connectionId, signerNpub, row = ::writeOf)
        .filterNotNull()
}

/** Unfinished writes of one workspace (optionally for one record), oldest first, for pending overlays. */
suspend fun StoreReader.workspaceWrites(connectionId: String, workspaceId: String, targetId: String? = null): List<WriteItem> = read {
    query(
        "SELECT $WRITE_COLUMNS FROM writes WHERE connection_id = ? AND workspace_id = ? AND (? IS NULL OR target_id = ?) ORDER BY created_at, rowid",
        connectionId, workspaceId, targetId, targetId, row = ::writeOf,
    ).filterNotNull()
}

suspend fun StoreReader.writeItem(id: String): WriteItem? = read { first("SELECT $WRITE_COLUMNS FROM writes WHERE id = ?", id, row = ::writeOf) }
