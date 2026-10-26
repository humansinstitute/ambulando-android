package studio.otherstuff.ambulando.core.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import studio.otherstuff.ambulando.core.chat.ComposerText
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.chat.MentionType
import studio.otherstuff.ambulando.core.context.ThreadSummary
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.chat.TextAttachmentInfo
import studio.otherstuff.ambulando.core.transport.MessageAttachment
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ThreadRecord
import studio.otherstuff.ambulando.core.transport.string
import java.time.Instant
import java.util.UUID

// Chat: drafts, the durable outbox and its state machine, threads, the member directory and per-channel catch-up
// cursors (iOS ChatStore.swift).

/**
 * Where a composer writes: one channel (new conversation) or one thread (reply), under one connection. It is captured
 * by the screen that owns the composer, never read from a global selection, so a workspace switch or lock between
 * typing and sending cannot redirect a message.
 */
data class ChatTarget(val connectionId: String, val workspaceId: String, val channelId: String, val threadId: String? = null) {
    val draftKey: String get() = listOf(connectionId, workspaceId, channelId, threadId ?: "_channel").joinToString(":")
}

/**
 * Composer state for one target. `updatedAt` is when the stored draft was last written (millisecond precision), so a
 * composer can tell a draft written after its own send (an Edit restore) from one read before it.
 */
data class Draft(val text: String, val mentions: List<Mention>, val updatedAt: Instant? = null, val attachments: List<PendingAttachment> = emptyList())

/** Outbox lifecycle. A row is deleted when Tower acknowledges it (or a synced copy with its `client_request_id` arrives). */
enum class OutboxState(val wire: String) {
    /** Written while the identity was locked; it is signed only after an explicit unlock. */
    AWAITING_SIGNATURE("awaiting_signature"),
    /** Request frozen (body + kind 33358 signature + client_request_id); ready to send. */
    QUEUED("queued"),
    SENDING("sending"),
    /** Network/server problem; retried automatically with backoff using the same frozen request. */
    RETRYING("retrying"),
    /** Tower rejected the request permanently; the user chooses retry, edit or discard. */
    FAILED("failed");

    companion object {
        fun from(wire: String?): OutboxState = entries.firstOrNull { it.wire == wire } ?: FAILED
    }
}

/**
 * An image staged on the device for a chat message. Its bytes are already privacy-stripped. Stored as JSON in the
 * outbox row (field names as on iOS).
 */
@Serializable
data class PendingAttachment(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    /** File name inside the staging directory. */
    val staged: String,
    val objectId: String? = null,
    val uploadURL: String? = null,
    val complete: Boolean = false,
    /** `image` for privacy-stripped photos, `file` for text attachments. */
    val kind: String = "image",
    /** Set for a pasted or edited text attachment (shared contract v1 marker). */
    val text: TextAttachmentInfo? = null,
) {
    val wire: MessageAttachment? get() = objectId?.let {
        MessageAttachment(kind, it, filename, mimeType, sizeBytes, sha256.takeIf { _ -> text != null }, text)
    }
    val isText: Boolean get() = text != null
}

class OutboxItem(
    /** Also the client_request_id. */
    val id: String,
    val connectionId: String,
    val origin: String,
    val signerNpub: String,
    val workspaceId: String,
    val appNpub: String,
    val channelId: String,
    val threadId: String?,
    val createThread: Boolean,
    /** Exact trimmed wire body, mention tokens included. */
    val body: String,
    val mentions: List<Mention>,
    /** Frozen exact JSON bytes, once signed. */
    val request: ByteArray?,
    val state: OutboxState,
    val attempts: Int,
    val lastStatus: Int?,
    val lastCode: String?,
    val lastError: String?,
    val createdAt: String,
    /** Images to upload before the message is signed (their storage ids become part of the signed request). */
    val attachments: List<PendingAttachment> = emptyList(),
) {
    val target: ChatTarget get() = ChatTarget(connectionId, workspaceId, channelId, threadId)

    private fun fields(): List<Any?> = listOf(id, connectionId, origin, signerNpub, workspaceId, appNpub, channelId, threadId, createThread,
        body, mentions, request?.toList(), state, attempts, lastStatus, lastCode, lastError, createdAt, attachments)

    override fun equals(other: Any?): Boolean = other is OutboxItem && other.fields() == fields()
    override fun hashCode(): Int = fields().hashCode()
    override fun toString(): String = "OutboxItem(id=$id, state=$state, attempts=$attempts)"
}

data class FeedPost(val message: MessageRecord, val replyCount: Int, val lastReplyAt: String?) {
    val id: String get() = message.id
}

data class ChannelSyncState(
    val resumeCursor: String?,
    val resumeAfterCreatedAt: String?,
    val resumeAfterId: String?,
    val complete: Boolean,
    val syncedAt: String,
)

/** `(created_at, id)` position in Tower's message order. */
data class MessageKey(val createdAt: String, val id: String) : Comparable<MessageKey> {
    override fun compareTo(other: MessageKey): Int = compareValuesBy(this, other, { it.createdAt }, { it.id })
}

internal object JsonText {
    private val json = Json { ignoreUnknownKeys = true }
    private val attachmentList = ListSerializer(PendingAttachment.serializer())

    fun mentions(value: List<Mention>): String = JsonArray(value.map { m ->
        JsonObject(mapOf("type" to JsonPrimitive(m.type.raw), "npub" to JsonPrimitive(m.npub), "label" to JsonPrimitive(m.label)))
    }).toString()

    fun mentions(text: String?): List<Mention> = runCatching {
        json.parseToJsonElement(text ?: return emptyList()).jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val type = MentionType.fromRaw(o.string("type") ?: return@mapNotNull null) ?: return@mapNotNull null
            Mention(type, o.string("npub") ?: return@mapNotNull null, o.string("label") ?: "")
        }
    }.getOrDefault(emptyList())

    fun attachments(value: List<PendingAttachment>): String = json.encodeToString(attachmentList, value)
    fun attachments(text: String?): List<PendingAttachment> =
        runCatching { json.decodeFromString(attachmentList, text ?: return emptyList()) }.getOrDefault(emptyList())
}

// MARK: Writer — drafts

/** Saves (or clears, when empty) the composer state for one target. */
suspend fun StoreWriter.saveDraft(target: ChatTarget, draft: Draft) = write {
    val empty = draft.text.isBlank() && draft.mentions.isEmpty()
    // Attachments are owned by setDraftAttachments; a text save never touches them.
    val keptAttachments = db.textValue("SELECT attachments FROM drafts WHERE key = ?", target.draftKey)?.let { it != "[]" } == true
    if (empty && !keptAttachments) {
        db.run("DELETE FROM drafts WHERE key = ?", target.draftKey)
    } else {
        db.run(
            """INSERT INTO drafts(key, connection_id, workspace_id, channel_id, thread_id, text, mentions, updated_at, attachments) VALUES(?,?,?,?,?,?,?,?,'[]')
               ON CONFLICT(key) DO UPDATE SET text = excluded.text, mentions = excluded.mentions, updated_at = excluded.updated_at""",
            target.draftKey, target.connectionId, target.workspaceId, target.channelId, target.threadId, draft.text,
            JsonText.mentions(draft.mentions), StoreTime.precise(),
        )
    }
    emit(StoreTable.DRAFTS, target.workspaceId)
}

/**
 * Changes one target's staged composer attachments (pasted text files) in a single writer step: [transform] sees the
 * list as stored and returns the new one, so quick successive pastes never overwrite each other. The list is durable
 * with the draft (refresh, relaunch and context changes keep it); an empty list with no text removes the draft row.
 * Returns the stored list.
 */
suspend fun StoreWriter.editDraftAttachments(
    target: ChatTarget, transform: (List<PendingAttachment>) -> List<PendingAttachment>,
): List<PendingAttachment> = write {
    val current = db.textValue("SELECT attachments FROM drafts WHERE key = ?", target.draftKey)?.let { JsonText.attachments(it) } ?: emptyList()
    val next = transform(current)
    if (next.isEmpty()) {
        db.run("UPDATE drafts SET attachments = '[]', updated_at = ? WHERE key = ?", StoreTime.precise(), target.draftKey)
        db.run("DELETE FROM drafts WHERE key = ? AND trim(text) = '' AND mentions = '[]'", target.draftKey)
    } else {
        db.run(
            """INSERT INTO drafts(key, connection_id, workspace_id, channel_id, thread_id, text, mentions, updated_at, attachments) VALUES(?,?,?,?,?,'','[]',?,?)
               ON CONFLICT(key) DO UPDATE SET attachments = excluded.attachments, updated_at = excluded.updated_at""",
            target.draftKey, target.connectionId, target.workspaceId, target.channelId, target.threadId, StoreTime.precise(), JsonText.attachments(next),
        )
    }
    emit(StoreTable.DRAFTS, target.workspaceId)
    next
}

suspend fun StoreWriter.setDraftAttachments(target: ChatTarget, attachments: List<PendingAttachment>) { editDraftAttachments(target) { attachments } }

/**
 * Replaces the first `token` in one target's stored draft (read and written in this writer step, so no other draft can
 * receive it). Returns false when the draft does not hold the token.
 */
suspend fun StoreWriter.replaceDraftToken(target: ChatTarget, token: String, replacement: String): Boolean = write {
    val text = db.textValue("SELECT text FROM drafts WHERE key = ?", target.draftKey) ?: return@write false
    val next = ComposerText.replacing(token, replacement, text, null) ?: return@write false
    db.run("UPDATE drafts SET text = ?, updated_at = ? WHERE key = ?", next.text, StoreTime.precise(), target.draftKey)
    emit(StoreTable.DRAFTS, target.workspaceId)
    true
}

// MARK: Writer — outbox

/**
 * Moves a composed message into the outbox and clears that target's draft in one transaction: the text is never held
 * only in memory between the two. The row starts `AWAITING_SIGNATURE`.
 */
suspend fun StoreWriter.enqueueSend(
    target: ChatTarget, origin: String, signerNpub: String, appNpub: String, body: String, mentions: List<Mention>,
    attachments: List<PendingAttachment> = emptyList(), id: String = UUID.randomUUID().toString().lowercase(),
): OutboxItem = write {
    val stamp = StoreTime.now()
    db.run(
        """INSERT INTO outbox(id, connection_id, origin, signer_npub, workspace_id, app_npub, channel_id, thread_id, create_thread, body, mentions,
                              request, state, attempts, created_at, updated_at, attachments)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL,?,0,?,?,?)""",
        id, target.connectionId, origin, signerNpub, target.workspaceId, appNpub, target.channelId, target.threadId, target.threadId == null,
        body, JsonText.mentions(mentions), OutboxState.AWAITING_SIGNATURE.wire, stamp, stamp, JsonText.attachments(attachments),
    )
    db.run("DELETE FROM drafts WHERE key = ?", target.draftKey)
    emit(StoreTable.OUTBOX, target.workspaceId)
    emit(StoreTable.DRAFTS, target.workspaceId)
    OutboxItem(id, target.connectionId, origin, signerNpub, target.workspaceId, appNpub, target.channelId, target.threadId, target.threadId == null,
        body, mentions, null, OutboxState.AWAITING_SIGNATURE, 0, null, null, null, stamp, attachments)
}

/** Records upload progress for an unsigned row's attachments (never after the request is frozen). */
suspend fun StoreWriter.updateOutboxAttachments(id: String, attachments: List<PendingAttachment>) = write {
    db.run("UPDATE outbox SET attachments = ?, updated_at = ? WHERE id = ? AND request IS NULL", JsonText.attachments(attachments), StoreTime.now(), id)
    Unit
}

/**
 * Stores the signed request bytes exactly once and moves the row to `QUEUED`. A row that is already frozen keeps its
 * original request, so every retry resends identical bytes under the same client_request_id.
 */
suspend fun StoreWriter.freezeOutbox(id: String, request: ByteArray): Boolean = write {
    val frozen = db.run("UPDATE outbox SET request = ?, state = ?, updated_at = ? WHERE id = ? AND request IS NULL",
        request, OutboxState.QUEUED.wire, StoreTime.now(), id) > 0
    db.outboxWorkspace(id)?.let { emit(StoreTable.OUTBOX, it) }
    frozen
}

/** Sets a row's state and last outcome; `countAttempt` adds one attempt. */
suspend fun StoreWriter.markOutbox(
    id: String, state: OutboxState, status: Int? = null, code: String? = null, error: String? = null, countAttempt: Boolean = false,
) = write {
    db.run(
        "UPDATE outbox SET state = ?, last_status = ?, last_code = ?, last_error = ?, attempts = attempts + ?, updated_at = ? WHERE id = ?",
        state.wire, status, code, error, if (countAttempt) 1 else 0, StoreTime.now(), id,
    )
    db.outboxWorkspace(id)?.let { emit(StoreTable.OUTBOX, it) }
}

/** Tower accepted (or replayed) the send: store its message and thread and retire the outbox row together. */
suspend fun StoreWriter.acknowledgeSend(id: String, message: MessageRecord, thread: ThreadRecord?) = write {
    emit(StoreTable.OUTBOX, message.workspaceId)
    writeMessages(listOf(message))
    if (thread != null) { writeThreads(listOf(thread)); emit(StoreTable.THREADS, thread.workspaceId) }
    db.run("DELETE FROM outbox WHERE id = ?", id)
}

/** User-initiated retry: back to the send queue (or to signing, if never signed). Only from `FAILED` or `RETRYING`. */
suspend fun StoreWriter.retryOutbox(id: String) = write {
    db.run(
        "UPDATE outbox SET state = CASE WHEN request IS NULL THEN ? ELSE ? END, updated_at = ? WHERE id = ? AND state IN (?, ?)",
        OutboxState.AWAITING_SIGNATURE.wire, OutboxState.QUEUED.wire, StoreTime.now(), id, OutboxState.FAILED.wire, OutboxState.RETRYING.wire,
    )
    db.outboxWorkspace(id)?.let { emit(StoreTable.OUTBOX, it) }
}

/**
 * Removes an unsent message (never one that is `SENDING`). With `restoreDraft`, its text returns to the composer it
 * came from (when that composer is empty), so the user can edit and resend it.
 */
suspend fun StoreWriter.discardOutbox(id: String, restoreDraft: Boolean) = write {
    val item = db.outboxRow(id) ?: return@write
    if (item.state == OutboxState.SENDING) return@write
    if (restoreDraft) {
        val decoded = MentionCodec.decode(item.body)
        // Text attachments return to the composer (merged after any attachments already there); images do not.
        val restored = item.attachments.filter { it.isText }.map { it.copy(objectId = null, uploadURL = null, complete = false) }
        val existing = db.textValue("SELECT attachments FROM drafts WHERE key = ?", item.target.draftKey)?.let { JsonText.attachments(it) } ?: emptyList()
        db.run(
            """INSERT INTO drafts(key, connection_id, workspace_id, channel_id, thread_id, text, mentions, updated_at, attachments) VALUES(?,?,?,?,?,?,?,?,?)
               ON CONFLICT(key) DO UPDATE SET attachments = excluded.attachments""",
            item.target.draftKey, item.connectionId, item.workspaceId, item.channelId, item.threadId, decoded.text,
            JsonText.mentions(decoded.mentions), StoreTime.precise(), JsonText.attachments(existing + restored),
        )
    }
    db.run("DELETE FROM outbox WHERE id = ?", id)
    emit(StoreTable.OUTBOX, item.workspaceId)
    emit(StoreTable.DRAFTS, item.workspaceId)
}

/** After relaunch nothing is in flight: a send interrupted mid-request is queued again (idempotent replay). */
suspend fun StoreWriter.resetInterruptedSends() = write {
    if (db.run("UPDATE outbox SET state = ? WHERE state = ?", OutboxState.QUEUED.wire, OutboxState.SENDING.wire) > 0) emit(StoreTable.OUTBOX)
}

private fun SqlConnection.outboxWorkspace(id: String): String? = textValue("SELECT workspace_id FROM outbox WHERE id = ?", id)

private const val OUTBOX_COLUMNS = """id, connection_id, origin, signer_npub, workspace_id, app_npub, channel_id, thread_id, create_thread, body,
    mentions, request, state, attempts, last_status, last_code, last_error, created_at, attachments"""

private fun outboxOf(r: SqlRow) = OutboxItem(
    r.text(0)!!, r.text(1)!!, r.text(2)!!, r.text(3)!!, r.text(4)!!, r.text(5)!!, r.text(6)!!, r.text(7), r.bool(8), r.text(9)!!,
    JsonText.mentions(r.text(10)), if (r.isNull(11)) null else r.blob(11), OutboxState.from(r.text(12)), r.int(13),
    if (r.isNull(14)) null else r.int(14), r.text(15), r.text(16), r.text(17)!!, JsonText.attachments(r.text(18)),
)

internal fun SqlConnection.outboxRow(id: String): OutboxItem? = first("SELECT $OUTBOX_COLUMNS FROM outbox WHERE id = ?", id, row = ::outboxOf)

// MARK: Writer — threads, members, catch-up

/** Upserts threads under the row-version guard. Threads of a flagged channel are not written. */
internal fun WriteTx.writeThreads(records: List<ThreadRecord>): Int {
    var changed = 0
    val removed = HashMap<String, Set<String>>()
    db.prepare(
        """INSERT INTO threads(workspace_id, id, channel_id, source_message_id, title, latest, archived, deleted, row_version, updated_at, raw)
           VALUES(?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET channel_id=excluded.channel_id, source_message_id=excluded.source_message_id,
             title=excluded.title, latest=excluded.latest, archived=excluded.archived, deleted=excluded.deleted,
             row_version=excluded.row_version, updated_at=excluded.updated_at, raw=excluded.raw, access_removed=0
           ${newerGuard("threads")}""",
    ).use { s ->
        for (r in records) {
            if (r.channelId in removed.getOrPut(r.workspaceId) { db.removedChannelIds(r.workspaceId) }) continue
            changed += s.run(r.workspaceId, r.id, r.channelId, r.sourceMessageId, r.title, r.latest, r.archived, r.deleted, r.rowVersion,
                r.updatedAt, canonical(r.raw))
        }
    }
    return changed
}

suspend fun StoreWriter.upsertThreads(workspaceId: String, records: List<ThreadRecord>): Int = write {
    val changed = writeThreads(records.filter { it.workspaceId == workspaceId })
    if (changed > 0) emit(StoreTable.THREADS, workspaceId)
    changed
}

/** The directory is small and authoritative: replace it whole. */
suspend fun StoreWriter.replaceMembers(workspaceId: String, records: List<MemberRecord>) = write {
    db.run("DELETE FROM members WHERE workspace_id = ?", workspaceId)
    db.prepare("INSERT OR REPLACE INTO members(workspace_id, npub, actor_id, kind, display_name) VALUES(?,?,?,?,?)").use { s ->
        for (r in records) if (r.workspaceId == workspaceId) s.run(workspaceId, r.npub, r.actorId, r.kind, r.displayName)
    }
    emit(StoreTable.MEMBERS, workspaceId)
}

/**
 * Local messages Tower no longer lists, but only inside the range a completed catch-up actually covered: strictly after
 * `after` (or from the start) and no later than `through`. Rows outside it are untouched, so a message acknowledged
 * while the catch-up was paging is never affected. Android: such messages are flagged `access_removed`, not deleted
 * (real deletions arrive as tombstones). Returns rows newly flagged.
 */
suspend fun StoreWriter.reconcileMessages(
    workspaceId: String, channelId: String, threadId: String?, after: MessageKey?, through: MessageKey, keep: Set<String>,
): Int = write {
    val start = after ?: MessageKey("", "")
    val gone = db.query(
        """SELECT id FROM messages WHERE workspace_id = ? AND channel_id = ? AND (? IS NULL OR thread_id = ?) AND access_removed = 0
             AND (created_at > ? OR (created_at = ? AND id > ?)) AND (created_at < ? OR (created_at = ? AND id <= ?))""",
        workspaceId, channelId, threadId, threadId, start.createdAt, start.createdAt, start.id, through.createdAt, through.createdAt, through.id,
    ) { it.text(0)!! }.filter { it !in keep }
    var n = 0
    db.prepare("UPDATE messages SET access_removed = 1 WHERE workspace_id = ? AND id = ?").use { s -> for (id in gone) n += s.run(workspaceId, id) }
    if (n > 0) { emit(StoreTable.MESSAGES, workspaceId); emit(StoreTable.ACCESS, workspaceId) }
    n
}

/** Saves a channel's catch-up position. `complete` only ever moves from false to true. */
suspend fun StoreWriter.saveChannelSync(workspaceId: String, channelId: String, resumeCursor: String?, resumeAfter: MessageKey?, complete: Boolean) = write {
    db.run(
        """INSERT INTO channel_sync(workspace_id, channel_id, resume_cursor, resume_after_created_at, resume_after_id, complete, synced_at)
           VALUES(?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, channel_id) DO UPDATE SET resume_cursor = excluded.resume_cursor,
             resume_after_created_at = excluded.resume_after_created_at, resume_after_id = excluded.resume_after_id,
             complete = MAX(channel_sync.complete, excluded.complete), synced_at = excluded.synced_at""",
        workspaceId, channelId, resumeCursor, resumeAfter?.createdAt, resumeAfter?.id, complete, StoreTime.now(),
    )
    emit(StoreTable.MESSAGES, workspaceId)
}

// MARK: Reader

private val PREFIXED_MESSAGE_COLUMNS = Rows.MESSAGE_COLUMNS.split(",").joinToString(", ") { "m." + it.trim() }

/** Newest `limit` channel-level posts (thread roots), oldest first, each with its reply count. */
suspend fun StoreReader.channelFeed(workspaceId: String, channelId: String, limit: Int): List<FeedPost> = read {
    query(
        """SELECT $PREFIXED_MESSAGE_COLUMNS,
             (SELECT COUNT(*) FROM messages r WHERE r.workspace_id = m.workspace_id AND r.thread_id = m.thread_id AND r.id <> m.id AND r.deleted = 0),
             (SELECT MAX(r.created_at) FROM messages r WHERE r.workspace_id = m.workspace_id AND r.thread_id = m.thread_id AND r.id <> m.id AND r.deleted = 0)
           FROM messages m
           WHERE m.workspace_id = ? AND m.channel_id = ? AND m.deleted = 0 AND (
             m.thread_id IS NULL OR m.thread_source_id = m.id
             -- A thread with no stored source message (Tower thread without source_message_id, or the source was deleted)
             -- is shown by its earliest stored message, so its conversation is not hidden. No source is invented.
             OR (NOT EXISTS (SELECT 1 FROM messages s WHERE s.workspace_id = m.workspace_id AND s.thread_id = m.thread_id
                             AND s.deleted = 0 AND s.thread_source_id = s.id)
                 AND NOT EXISTS (SELECT 1 FROM messages e WHERE e.workspace_id = m.workspace_id AND e.thread_id = m.thread_id AND e.deleted = 0
                                 AND (e.created_at < m.created_at OR (e.created_at = m.created_at AND e.id < m.id)))))
           ORDER BY m.created_at DESC, m.id DESC LIMIT ?""",
        workspaceId, channelId, limit,
    ) { FeedPost(Rows.message(it, workspaceId), it.int(14), it.text(15)) }.asReversed()
}

/** Messages of one thread (root first), newest `limit`, oldest first. */
suspend fun StoreReader.threadMessages(workspaceId: String, threadId: String, limit: Int): List<MessageRecord> = read {
    query(
        "SELECT ${Rows.MESSAGE_COLUMNS} FROM messages WHERE workspace_id = ? AND thread_id = ? AND deleted = 0 ORDER BY created_at DESC, id DESC LIMIT ?",
        workspaceId, threadId, limit,
    ) { Rows.message(it, workspaceId) }.asReversed()
}

internal fun SqlConnection.messageRow(workspaceId: String, id: String): MessageRecord? =
    first("SELECT ${Rows.MESSAGE_COLUMNS} FROM messages WHERE workspace_id = ? AND id = ?", workspaceId, id) { Rows.message(it, workspaceId) }

suspend fun StoreReader.message(workspaceId: String, id: String): MessageRecord? = read { messageRow(workspaceId, id) }

internal fun SqlConnection.threadRow(workspaceId: String, id: String): ThreadRecord? = first(
    "SELECT id, channel_id, source_message_id, title, latest, archived, deleted, row_version, updated_at, raw, access_removed FROM threads WHERE workspace_id = ? AND id = ?",
    workspaceId, id,
) {
    ThreadRecord(workspaceId, it.text(0)!!, it.text(1)!!, it.text(2), it.text(3), it.text(4), it.bool(5), it.bool(6), it.long(7), it.text(8)!!,
        parseRaw(it.text(9)), it.bool(10))
}

suspend fun StoreReader.thread(workspaceId: String, id: String): ThreadRecord? = read { threadRow(workspaceId, id) }

/** Unsent messages shown inline in one conversation: the channel level (new posts) or one thread (replies). */
suspend fun StoreReader.outbox(target: ChatTarget): List<OutboxItem> = read {
    query(
        """SELECT $OUTBOX_COLUMNS FROM outbox
           WHERE connection_id = ? AND workspace_id = ? AND channel_id = ? AND ((? IS NULL AND thread_id IS NULL) OR thread_id = ?)
           ORDER BY created_at, rowid""",
        target.connectionId, target.workspaceId, target.channelId, target.threadId, target.threadId, row = ::outboxOf,
    )
}

/** Every unsent row for one connection and signer, in creation order (the sender's work list). */
suspend fun StoreReader.outbox(connectionId: String, signerNpub: String): List<OutboxItem> = read {
    query("SELECT $OUTBOX_COLUMNS FROM outbox WHERE connection_id = ? AND signer_npub = ? ORDER BY created_at, rowid", connectionId, signerNpub, row = ::outboxOf)
}

suspend fun StoreReader.outboxItem(id: String): OutboxItem? = read { outboxRow(id) }

suspend fun StoreReader.outboxCount(connectionId: String): Int = read {
    longValue("SELECT COUNT(*) FROM outbox WHERE connection_id = ?", connectionId)?.toInt() ?: 0
}

suspend fun StoreReader.draft(target: ChatTarget): Draft? = read {
    first("SELECT text, mentions, updated_at, attachments FROM drafts WHERE key = ?", target.draftKey) {
        Draft(it.text(0) ?: "", JsonText.mentions(it.text(1)), StoreTime.parse(it.text(2)), JsonText.attachments(it.text(3)))
    }
}

/** Every staged composer attachment of one connection (to delete their bytes with the connection). */
suspend fun StoreReader.draftAttachments(connectionId: String): List<PendingAttachment> = read {
    query("SELECT attachments FROM drafts WHERE connection_id = ?", connectionId) { JsonText.attachments(it.text(0)) }.flatten()
}

internal fun SqlConnection.memberRows(workspaceId: String): List<MemberRecord> = query(
    "SELECT npub, actor_id, kind, display_name FROM members WHERE workspace_id = ? ORDER BY display_name COLLATE NOCASE, npub", workspaceId,
) { MemberRecord(workspaceId, it.text(1)!!, it.text(0)!!, it.text(2), it.text(3)) }

suspend fun StoreReader.members(workspaceId: String): List<MemberRecord> = read { memberRows(workspaceId) }

suspend fun StoreReader.channelSync(workspaceId: String, channelId: String): ChannelSyncState? = read {
    first(
        "SELECT resume_cursor, resume_after_created_at, resume_after_id, complete, synced_at FROM channel_sync WHERE workspace_id = ? AND channel_id = ?",
        workspaceId, channelId,
    ) { ChannelSyncState(it.text(0), it.text(1), it.text(2), it.bool(3), it.text(4) ?: "") }
}

/**
 * Threads of a channel with replies, newest activity first. Threads known only from their messages (no thread record,
 * or no stored source message) are included, so no synced conversation is hidden.
 */
suspend fun StoreReader.channelThreads(workspaceId: String, channelId: String, limit: Int = 200): List<ThreadSummary> = read {
    query(
        """WITH ids(id) AS (
             SELECT id FROM threads WHERE workspace_id = ?1 AND channel_id = ?2 AND deleted = 0
             UNION SELECT DISTINCT thread_id FROM messages WHERE workspace_id = ?1 AND channel_id = ?2 AND thread_id IS NOT NULL AND deleted = 0
           ),
           stats AS (
             SELECT ids.id AS id,
               (SELECT COUNT(*) FROM messages m WHERE m.workspace_id = ?1 AND m.thread_id = ids.id AND m.deleted = 0) AS total,
               (SELECT MAX(m.created_at) FROM messages m WHERE m.workspace_id = ?1 AND m.thread_id = ids.id AND m.deleted = 0) AS last_at
             FROM ids
           )
           SELECT stats.id, t.title, t.archived, stats.total, stats.last_at,
             (SELECT body FROM messages m WHERE m.workspace_id = ?1 AND m.thread_id = stats.id AND m.deleted = 0 ORDER BY created_at, id LIMIT 1),
             (SELECT COALESCE(sender_label, sender_npub) FROM messages m WHERE m.workspace_id = ?1 AND m.thread_id = stats.id AND m.deleted = 0 ORDER BY created_at, id LIMIT 1),
             (SELECT body FROM messages m WHERE m.workspace_id = ?1 AND m.thread_id = stats.id AND m.deleted = 0 ORDER BY created_at DESC, id DESC LIMIT 1),
             (SELECT sender_npub FROM messages m WHERE m.workspace_id = ?1 AND m.thread_id = stats.id AND m.deleted = 0 ORDER BY created_at DESC, id DESC LIMIT 1),
             (SELECT sender_npub FROM messages m WHERE m.workspace_id = ?1 AND m.thread_id = stats.id AND m.deleted = 0 ORDER BY created_at, id LIMIT 1)
           FROM stats LEFT JOIN threads t ON t.workspace_id = ?1 AND t.id = stats.id
           WHERE stats.total > 1
           ORDER BY stats.last_at DESC, stats.id LIMIT ?3""",
        workspaceId, channelId, limit,
    ) {
        ThreadSummary(
            id = it.text(0)!!, channelId = channelId, title = it.text(1), rootBody = it.text(5) ?: "", rootSender = it.text(9) ?: it.text(6),
            latestBody = it.text(7) ?: "", latestSender = it.text(8), replyCount = maxOf(0, it.int(3) - 1), lastActivityAt = it.text(4) ?: "",
            archived = it.bool(2),
        )
    }
}

/** Thread titles for a channel, for labelling thread roots in its message list. */
suspend fun StoreReader.threadTitles(workspaceId: String, channelId: String): Map<String, String> = read {
    query(
        "SELECT id, title FROM threads WHERE workspace_id = ? AND channel_id = ? AND deleted = 0 AND title IS NOT NULL AND title <> ''",
        workspaceId, channelId,
    ) { it.text(0)!! to it.text(1)!! }.toMap()
}
