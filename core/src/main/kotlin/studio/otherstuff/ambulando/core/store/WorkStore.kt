package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.context.ContextQueries
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.deck.DeckActivity
import studio.otherstuff.ambulando.core.docs.DocDraft
import studio.otherstuff.ambulando.core.docs.DocDraftStore
import studio.otherstuff.ambulando.core.docs.SavedDocBody
import studio.otherstuff.ambulando.core.docs.SavedDocument
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.DocBodyRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.FileRecord
import studio.otherstuff.ambulando.core.transport.FolderRecord
import studio.otherstuff.ambulando.core.transport.ReactionRecord
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.ViewStateRecord

// Tasks, documents, files, inbox view states, reactions, list sync state and local search (iOS WorkStore.swift).

data class SearchHit(
    val kind: Kind,
    val recordId: String,
    val title: String,
    val snippet: String,
    val channelId: String?,
    /** Thread of a message hit (for navigation). */
    val threadId: String?,
    /** The record is no longer visible to this identity (shown labelled). */
    val accessRemoved: Boolean = false,
) {
    enum class Kind(val wire: String) { MESSAGE("message"), TASK("task"), DOC("doc"), FILE("file") }
    val id: String get() = "${kind.wire}:$recordId"
}

/**
 * Flags rows of one channel-like partition that Tower no longer lists (Android: non-destructive). Returns rows newly
 * flagged; emits the access key when any were.
 */
internal fun WriteTx.flagMissing(table: String, workspaceId: String, column: String, value: String, keep: Set<String>): Int {
    val gone = db.query("SELECT id FROM $table WHERE workspace_id = ? AND $column = ? AND access_removed = 0", workspaceId, value) { it.text(0)!! }
        .filter { it !in keep }
    var n = 0
    db.prepare("UPDATE $table SET access_removed = 1 WHERE workspace_id = ? AND id = ?").use { s -> for (id in gone) n += s.run(workspaceId, id) }
    if (n > 0) emit(StoreTable.ACCESS, workspaceId)
    return n
}

// MARK: Writer — tasks

/** Upserts tasks under the row-version guard. Tasks of a flagged channel are not written. */
internal fun WriteTx.writeTasks(records: List<TaskRecord>): Int {
    var changed = 0
    val written = ArrayList<TaskRecord>()
    val removed = HashMap<String, Set<String>>()
    db.prepare(
        """INSERT INTO tasks(workspace_id, id, scope_id, channel_id, thread_id, title, description, state, priority, assigned_to_npub, created_by_npub,
                             row_version, activity_version, created_at, updated_at, raw) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET scope_id=excluded.scope_id, channel_id=excluded.channel_id, thread_id=excluded.thread_id,
             title=excluded.title, description=excluded.description, state=excluded.state, priority=excluded.priority,
             assigned_to_npub=excluded.assigned_to_npub, created_by_npub=excluded.created_by_npub, row_version=excluded.row_version,
             activity_version=excluded.activity_version, updated_at=excluded.updated_at, raw=excluded.raw, access_removed=0
           ${newerGuard("tasks")}""",
    ).use { s ->
        for (r in records) {
            if (r.channelId in removed.getOrPut(r.workspaceId) { db.removedChannelIds(r.workspaceId) }) continue
            val wrote = s.run(r.workspaceId, r.id, r.scopeId, r.channelId, r.threadId, r.title, r.description, r.state, r.priority, r.assignedToNpub,
                r.createdByNpub, r.rowVersion, r.activityVersion, r.createdAt, r.updatedAt, canonical(r.raw))
            changed += wrote
            if (wrote > 0) written.add(r)
        }
    }
    // An authorized upsert or write acknowledgement is relationship evidence: known membership follows it in this transaction.
    noteTaskRelationships(written)
    return changed
}

/** Upserts tasks. With `completeChannel`, tasks of that channel Tower no longer lists are flagged `access_removed`. */
suspend fun StoreWriter.upsertTasks(workspaceId: String, records: List<TaskRecord>, completeChannel: String? = null): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId }
    var changed = writeTasks(rows)
    if (completeChannel != null) changed += flagMissing("tasks", workspaceId, "channel_id", completeChannel, rows.mapTo(HashSet()) { it.id })
    if (changed > 0) emit(StoreTable.TASKS, workspaceId)
    changed
}

// MARK: Writer — comments

/**
 * Upserts comments. With `complete` (parent kind and id), that parent's comments Tower no longer lists are flagged.
 */
suspend fun StoreWriter.upsertComments(
    workspaceId: String, records: List<CommentRecord>, complete: Pair<CommentRecord.Parent, String>? = null,
): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId }
    var changed = 0
    db.prepare(
        """INSERT INTO comments(workspace_id, id, parent, parent_id, parent_comment_id, body, sender_npub, status, deleted, client_record_id,
                                row_version, created_at, updated_at, raw) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET body=excluded.body, status=excluded.status, deleted=excluded.deleted,
             parent_comment_id=excluded.parent_comment_id, client_record_id=excluded.client_record_id,
             row_version=excluded.row_version, updated_at=excluded.updated_at, raw=excluded.raw, access_removed=0
           ${newerGuard("comments")}""",
    ).use { s ->
        for (r in rows) changed += s.run(workspaceId, r.id, r.parent.wire, r.parentId, r.parentCommentId, r.body, r.senderNpub, r.status, r.deleted,
            r.clientRecordId, r.rowVersion, r.createdAt, r.updatedAt, canonical(r.raw))
    }
    if (complete != null) {
        val (kind, id) = complete
        val keep = rows.filter { it.parent == kind && it.parentId == id }.mapTo(HashSet()) { it.id }
        val gone = db.query("SELECT id FROM comments WHERE workspace_id = ? AND parent = ? AND parent_id = ? AND access_removed = 0", workspaceId, kind.wire, id) {
            it.text(0)!!
        }.filter { it !in keep }
        var n = 0
        for (c in gone) n += db.run("UPDATE comments SET access_removed = 1 WHERE workspace_id = ? AND id = ?", workspaceId, c)
        if (n > 0) emit(StoreTable.ACCESS, workspaceId)
        changed += n
    }
    if (changed > 0) emit(StoreTable.COMMENTS, workspaceId)
    changed
}

// MARK: Writer — documents

/** Upserts documents under the row-version guard. Documents of a flagged channel are not written. */
internal fun WriteTx.writeDocs(records: List<DocRecord>): Int {
    var changed = 0
    val removed = HashMap<String, Set<String>>()
    db.prepare(
        """INSERT INTO docs(workspace_id, id, scope_id, channel_id, storage_object_id, title, summary, archived, row_version, activity_version,
                            created_at, updated_at, raw) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET scope_id=excluded.scope_id, channel_id=excluded.channel_id,
             storage_object_id=excluded.storage_object_id, title=excluded.title, summary=excluded.summary, archived=excluded.archived,
             row_version=excluded.row_version, activity_version=excluded.activity_version, updated_at=excluded.updated_at, raw=excluded.raw,
             access_removed=0
           ${newerGuard("docs")}""",
    ).use { s ->
        for (r in records) {
            if (r.channelId in removed.getOrPut(r.workspaceId) { db.removedChannelIds(r.workspaceId) }) continue
            changed += s.run(r.workspaceId, r.id, r.scopeId, r.channelId, r.storageObjectId, r.title, r.summary, r.archived, r.rowVersion,
                r.activityVersion, r.createdAt, r.updatedAt, canonical(r.raw))
        }
    }
    return changed
}

/** Upserts documents. With `completeChannel`, documents of that channel Tower no longer lists are flagged. */
suspend fun StoreWriter.upsertDocs(workspaceId: String, records: List<DocRecord>, completeChannel: String? = null): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId }
    var changed = writeDocs(rows)
    if (completeChannel != null) changed += flagMissing("docs", workspaceId, "channel_id", completeChannel, rows.mapTo(HashSet()) { it.id })
    if (changed > 0) emit(StoreTable.DOCS, workspaceId)
    changed
}

/**
 * Stores a verified body together with the document row it was read with, and indexes its text (first 20,000
 * characters) for search. An older body never replaces a newer one.
 */
suspend fun StoreWriter.saveDocBody(body: DocBodyRecord, doc: DocRecord?, searchText: String) = write {
    if (doc != null) writeDocs(listOf(doc))
    db.run(
        """INSERT INTO doc_bodies(workspace_id, doc_id, storage_object_id, sha256, content_type, doc_row_version, bytes, fetched_at)
           VALUES(?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, doc_id) DO UPDATE SET storage_object_id=excluded.storage_object_id, sha256=excluded.sha256,
             content_type=excluded.content_type, doc_row_version=excluded.doc_row_version, bytes=excluded.bytes, fetched_at=excluded.fetched_at
           WHERE excluded.doc_row_version >= doc_bodies.doc_row_version""",
        body.workspaceId, body.docId, body.storageObjectId, body.sha256, body.contentType, body.docRowVersion, body.bytes, body.fetchedAt,
    )
    db.run("UPDATE docs SET body_text = ? WHERE workspace_id = ? AND id = ? AND storage_object_id IS ?",
        searchText.take(20_000), body.workspaceId, body.docId, body.storageObjectId)
    emit(StoreTable.DOC_BODIES, body.workspaceId)
    emit(StoreTable.DOCS, body.workspaceId)
}

// MARK: Writer — document drafts

suspend fun StoreWriter.saveDocDraft(draft: DocDraft) = write {
    db.run(
        """INSERT INTO doc_drafts(key, connection_id, workspace_id, doc_id, title, body, content_type, base_row_version, base_sha256,
                                  base_storage_object_id, state, last_error, recovery_id, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(key) DO UPDATE SET title=excluded.title, body=excluded.body, content_type=excluded.content_type,
             base_row_version=excluded.base_row_version, base_sha256=excluded.base_sha256, base_storage_object_id=excluded.base_storage_object_id,
             state=excluded.state, last_error=excluded.last_error, recovery_id=excluded.recovery_id, updated_at=excluded.updated_at""",
        draft.key, draft.connectionId, draft.workspaceId, draft.docId, draft.title, draft.body, draft.contentType, draft.baseRowVersion,
        draft.baseSha256, draft.baseStorageObjectId, draft.state.raw, draft.lastError, draft.recoveryId, StoreTime.now(),
    )
    emit(StoreTable.DOC_DRAFTS, draft.workspaceId)
}

suspend fun StoreWriter.deleteDocDraft(key: String, workspaceId: String) = write {
    db.run("DELETE FROM doc_drafts WHERE key = ?", key)
    emit(StoreTable.DOC_DRAFTS, workspaceId)
}

/** The local persistence [studio.otherstuff.ambulando.core.docs.DocumentSaver] needs, over this writer. */
fun StoreWriter.docDraftStore(reader: StoreReader? = null): DocDraftStore {
    val writer = this
    return object : DocDraftStore {
        override suspend fun saveDocDraft(draft: DocDraft) = writer.saveDocDraft(draft)
        override suspend fun deleteDocDraft(key: String, workspaceId: String) = writer.deleteDocDraft(key, workspaceId)
        override suspend fun currentDraft(key: String): DocDraft? = reader?.docDraft(key)
        override suspend fun replaceIfUnchanged(expected: DocDraft, next: DocDraft): Boolean = writer.replaceDocDraftIfUnchanged(expected, next)
        override suspend fun insertIfAbsent(draft: DocDraft): Boolean = writer.insertDocDraftIfAbsent(draft)
        override suspend fun finishSave(submitted: DocDraft, saved: SavedDocBody) =
            writer.finishDocDraftSave(submitted, saved.docRowVersion, saved.sha256, saved.storageObjectId)
        override suspend fun cachedBody(workspaceId: String, docId: String): DocBodyRecord? = reader?.docBody(workspaceId, docId)
        override suspend fun saveHead(head: studio.otherstuff.ambulando.core.docs.DocHead, searchText: String) {
            writer.saveDocBody(head.body, head.doc, searchText)
        }
        override suspend fun receipt(key: String): String? = reader?.state(key)
        override suspend fun setReceipt(key: String, value: String?) = writer.setState(key, value)
        override suspend fun saveDocBody(body: SavedDocBody, doc: SavedDocument, searchText: String) {
            val record = runCatching { DocRecord.parse(doc.raw) }.getOrNull()?.takeIf { it.workspaceId == body.workspaceId && it.id == body.docId }
            writer.saveDocBody(
                DocBodyRecord(body.workspaceId, body.docId, body.storageObjectId, body.sha256, body.contentType, body.docRowVersion.toLong(),
                    body.bytes, body.fetchedAt),
                record, searchText,
            )
        }
    }
}

// MARK: Writer — files and folders

/** Upserts files. With `completeChannel`, files of that channel Tower no longer lists are flagged. */
suspend fun StoreWriter.upsertFiles(workspaceId: String, records: List<FileRecord>, completeChannel: String? = null): Int = write {
    val blocked = db.removedChannelIds(workspaceId)
    val rows = records.filter { it.workspaceId == workspaceId && it.channelId !in blocked }
    var changed = 0
    db.prepare(
        """INSERT INTO files(workspace_id, id, scope_id, channel_id, folder_id, storage_object_id, display_name, description, content_type,
                             size_bytes, sha256, archived, row_version, created_at, updated_at, raw) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET scope_id=excluded.scope_id, channel_id=excluded.channel_id, folder_id=excluded.folder_id,
             storage_object_id=excluded.storage_object_id, display_name=excluded.display_name, description=excluded.description,
             content_type=excluded.content_type, size_bytes=excluded.size_bytes, sha256=excluded.sha256, archived=excluded.archived,
             row_version=excluded.row_version, updated_at=excluded.updated_at, raw=excluded.raw, access_removed=0
           ${newerGuard("files")}""",
    ).use { s ->
        for (r in rows) changed += s.run(workspaceId, r.id, r.scopeId, r.channelId, r.folderId, r.storageObjectId, r.displayName, r.description,
            r.contentType, r.sizeBytes, r.sha256, r.archived, r.rowVersion, r.createdAt, r.updatedAt, canonical(r.raw))
    }
    if (completeChannel != null && completeChannel !in blocked) {
        changed += flagMissing("files", workspaceId, "channel_id", completeChannel, rows.mapTo(HashSet()) { it.id })
    }
    if (changed > 0) emit(StoreTable.FILES, workspaceId)
    changed
}

/** Upserts one channel's folders. With `complete`, folders Tower no longer lists are flagged. */
suspend fun StoreWriter.upsertFolders(workspaceId: String, channelId: String, records: List<FolderRecord>, complete: Boolean): Int = write {
    if (channelId in db.removedChannelIds(workspaceId)) return@write 0
    val rows = records.filter { it.workspaceId == workspaceId && it.channelId == channelId }
    var changed = 0
    db.prepare(
        """INSERT INTO folders(workspace_id, id, channel_id, parent_folder_id, title, deleted, row_version, updated_at, raw) VALUES(?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET channel_id=excluded.channel_id, parent_folder_id=excluded.parent_folder_id, title=excluded.title,
             deleted=excluded.deleted, row_version=excluded.row_version, updated_at=excluded.updated_at, raw=excluded.raw, access_removed=0
           ${newerGuard("folders")}""",
    ).use { s ->
        for (r in rows) changed += s.run(workspaceId, r.id, channelId, r.parentFolderId, r.title, r.deleted, r.rowVersion, r.updatedAt, canonical(r.raw))
    }
    if (complete) changed += flagMissing("folders", workspaceId, "channel_id", channelId, rows.mapTo(HashSet()) { it.id })
    if (changed > 0) emit(StoreTable.FOLDERS, workspaceId)
    changed
}

// MARK: Writer — view states

/**
 * Stores Tower's view states. A newer local read position (marked on this device, not yet confirmed) is kept. With
 * `complete`, view states Tower no longer returns are removed (inbox bookkeeping, not record content).
 */
suspend fun StoreWriter.upsertViewStates(workspaceId: String, records: List<ViewStateRecord>, complete: Boolean = false): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId }
    var changed = 0
    db.prepare(
        """INSERT INTO view_states(workspace_id, resource_type, resource_id, scope_id, channel_id, activity_version, viewed_activity_version,
                                   attention_activity_version, row_version, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, resource_type, resource_id) DO UPDATE SET scope_id=excluded.scope_id, channel_id=excluded.channel_id,
             activity_version=MAX(view_states.activity_version, excluded.activity_version),
             viewed_activity_version=MAX(view_states.viewed_activity_version, excluded.viewed_activity_version),
             attention_activity_version=excluded.attention_activity_version, row_version=excluded.row_version, updated_at=excluded.updated_at
           WHERE excluded.row_version <> view_states.row_version OR excluded.activity_version <> view_states.activity_version
             OR excluded.viewed_activity_version > view_states.viewed_activity_version
             OR excluded.attention_activity_version IS NOT view_states.attention_activity_version""",
    ).use { s ->
        for (r in rows) changed += s.run(workspaceId, r.resourceType.wire, r.resourceId, r.scopeId, r.channelId, r.activityVersion,
            r.viewedActivityVersion, r.attentionActivityVersion, r.rowVersion, r.updatedAt)
    }
    if (complete) {
        val keep = rows.mapTo(HashSet()) { it.id }
        val gone = db.query("SELECT resource_type, resource_id FROM view_states WHERE workspace_id = ?", workspaceId) { it.text(0)!! to it.text(1)!! }
            .filter { (t, r) -> "$t:$r" !in keep }
        db.prepare("DELETE FROM view_states WHERE workspace_id = ? AND resource_type = ? AND resource_id = ?").use { d ->
            for ((t, r) in gone) changed += d.run(workspaceId, t, r)
        }
    }
    if (changed > 0) emit(StoreTable.VIEW_STATES, workspaceId)
    changed
}

/** A resource read on this device: its type, id and the activity version seen. */
data class ViewedResource(val type: ViewStateRecord.ResourceType, val id: String, val activityVersion: Long)

/** Marks resources read on this device immediately (the durable write tells Tower). */
suspend fun StoreWriter.markViewedLocally(workspaceId: String, resources: List<ViewedResource>) = write {
    db.prepare(
        "UPDATE view_states SET viewed_activity_version = MAX(viewed_activity_version, ?) WHERE workspace_id = ? AND resource_type = ? AND resource_id = ?",
    ).use { s -> for (r in resources) s.run(r.activityVersion, workspaceId, r.type.wire, r.id) }
    emit(StoreTable.VIEW_STATES, workspaceId)
}

// MARK: Writer — reactions

/** Replaces the reactions of the given targets (each fetch returns a target's complete list). */
suspend fun StoreWriter.replaceReactions(workspaceId: String, targets: List<String>, records: List<ReactionRecord>) = write {
    db.prepare("DELETE FROM reactions WHERE workspace_id = ? AND target_id = ?").use { d -> for (t in targets) d.run(workspaceId, t) }
    val set = targets.toHashSet()
    writeReactions(records.filter { it.workspaceId == workspaceId && it.targetId in set })
    emit(StoreTable.REACTIONS, workspaceId)
}

internal fun WriteTx.writeReactions(records: List<ReactionRecord>) {
    db.prepare(
        """INSERT OR REPLACE INTO reactions(workspace_id, id, target_type, target_id, emoji, reactor_npub, deleted, row_version, updated_at)
           VALUES(?,?,?,?,?,?,0,?,?)""",
    ).use { s ->
        for (r in records) if (!r.deleted) s.run(r.workspaceId, r.id, r.targetType, r.targetId, r.emoji, r.reactorNpub, r.rowVersion, r.updatedAt)
    }
}

suspend fun StoreWriter.upsertReaction(record: ReactionRecord) = write {
    writeReactions(listOf(record))
    emit(StoreTable.REACTIONS, record.workspaceId)
}

suspend fun StoreWriter.deleteReaction(workspaceId: String, id: String) = write {
    db.run("DELETE FROM reactions WHERE workspace_id = ? AND id = ?", workspaceId, id)
    emit(StoreTable.REACTIONS, workspaceId)
}

// MARK: Writer — list sync state

/** Records whether a list (tasks/docs/files of a channel, the inbox …) was last read completely. No change notification. */
suspend fun StoreWriter.saveListSync(workspaceId: String, key: String, complete: Boolean) = write {
    db.run(
        """INSERT INTO list_sync(workspace_id, key, complete, synced_at) VALUES(?,?,?,?)
           ON CONFLICT(workspace_id, key) DO UPDATE SET complete = excluded.complete, synced_at = excluded.synced_at""",
        workspaceId, key, complete, StoreTime.now(),
    )
    Unit
}

// MARK: Reader

internal const val TASK_COLUMNS = "id, scope_id, channel_id, thread_id, title, description, state, priority, assigned_to_npub, created_by_npub, " +
    "row_version, activity_version, created_at, updated_at, raw, access_removed"

internal fun task(r: SqlRow, w: String) = TaskRecord(
    workspaceId = w, id = r.text(0)!!, scopeId = r.text(1), channelId = r.text(2)!!, threadId = r.text(3), title = r.text(4)!!,
    description = r.text(5), state = r.text(6)!!, priority = r.text(7)!!, assignedToNpub = r.text(8), createdByNpub = r.text(9),
    rowVersion = r.long(10), activityVersion = r.long(11), createdAt = r.text(12)!!, updatedAt = r.text(13)!!, raw = parseRaw(r.text(14)),
    accessRemoved = r.bool(15),
)

/** Tasks of a workspace (optionally one scope or channel), newest activity first, bounded. Flagged rows included. */
suspend fun StoreReader.tasks(
    workspaceId: String, scopeId: String? = null, channelId: String? = null, includeArchived: Boolean = false, limit: Int = 500,
): List<TaskRecord> = read {
    query(
        """SELECT $TASK_COLUMNS FROM tasks WHERE workspace_id = ? AND (? IS NULL OR scope_id = ?) AND (? IS NULL OR channel_id = ?)
             AND (? = 1 OR state NOT IN ('archive', 'archived')) ORDER BY updated_at DESC, id LIMIT ?""",
        workspaceId, scopeId, scopeId, channelId, channelId, includeArchived, limit,
    ) { task(it, workspaceId) }
}

/** Tasks inside a work context (Home, a scope, or a channel). */
suspend fun StoreReader.tasks(workspaceId: String, context: WorkContext, includeArchived: Boolean = false, limit: Int = 500): List<TaskRecord> = read {
    val f = ContextQueries.contextFilter(context, workspaceId)
    query(
        """SELECT $TASK_COLUMNS FROM tasks WHERE workspace_id = ? AND ${f.sql}
             AND (? = 1 OR state NOT IN ('archive', 'archived')) ORDER BY updated_at DESC, id LIMIT ?""",
        workspaceId, *f.bindings.toTypedArray(), includeArchived, limit,
    ) { task(it, workspaceId) }
}

internal fun SqlConnection.taskRow(workspaceId: String, id: String): TaskRecord? =
    first("SELECT $TASK_COLUMNS FROM tasks WHERE workspace_id = ? AND id = ?", workspaceId, id) { task(it, workspaceId) }

suspend fun StoreReader.task(workspaceId: String, id: String): TaskRecord? = read { taskRow(workspaceId, id) }

suspend fun StoreReader.comments(workspaceId: String, parent: CommentRecord.Parent, parentId: String, limit: Int = 500): List<CommentRecord> = read {
    query(
        """SELECT id, parent_comment_id, body, sender_npub, status, deleted, row_version, created_at, updated_at, client_record_id, raw, access_removed
           FROM comments WHERE workspace_id = ? AND parent = ? AND parent_id = ? AND deleted = 0 ORDER BY created_at, id LIMIT ?""",
        workspaceId, parent.wire, parentId, limit,
    ) {
        CommentRecord(
            workspaceId = workspaceId, id = it.text(0)!!, parent = parent, parentId = parentId, parentCommentId = it.text(1), body = it.text(2) ?: "",
            senderNpub = it.text(3), status = it.text(4), deleted = it.bool(5), rowVersion = it.long(6), createdAt = it.text(7)!!,
            updatedAt = it.text(8) ?: it.text(7)!!, clientRecordId = it.text(9), raw = parseRaw(it.text(10)), accessRemoved = it.bool(11),
        )
    }
}

private const val DOC_COLUMNS = "id, scope_id, channel_id, storage_object_id, title, summary, archived, row_version, activity_version, " +
    "created_at, updated_at, raw, access_removed"

private fun doc(r: SqlRow, w: String) = DocRecord(
    workspaceId = w, id = r.text(0)!!, scopeId = r.text(1), channelId = r.text(2)!!, storageObjectId = r.text(3), title = r.text(4)!!,
    summary = r.text(5), archived = r.bool(6), rowVersion = r.long(7), activityVersion = r.long(8), createdAt = r.text(9)!!,
    updatedAt = r.text(10)!!, raw = parseRaw(r.text(11)), accessRemoved = r.bool(12),
)

suspend fun StoreReader.docs(workspaceId: String, scopeId: String? = null, limit: Int = 500): List<DocRecord> = read {
    query(
        "SELECT $DOC_COLUMNS FROM docs WHERE workspace_id = ? AND (? IS NULL OR scope_id = ?) AND archived = 0 ORDER BY updated_at DESC, id LIMIT ?",
        workspaceId, scopeId, scopeId, limit,
    ) { doc(it, workspaceId) }
}

suspend fun StoreReader.docs(workspaceId: String, context: WorkContext, limit: Int = 500): List<DocRecord> = read {
    val f = ContextQueries.contextFilter(context, workspaceId)
    query(
        "SELECT $DOC_COLUMNS FROM docs WHERE workspace_id = ? AND ${f.sql} AND archived = 0 ORDER BY updated_at DESC, id LIMIT ?",
        workspaceId, *f.bindings.toTypedArray(), limit,
    ) { doc(it, workspaceId) }
}

internal fun SqlConnection.docRow(workspaceId: String, id: String): DocRecord? =
    first("SELECT $DOC_COLUMNS FROM docs WHERE workspace_id = ? AND id = ?", workspaceId, id) { doc(it, workspaceId) }

suspend fun StoreReader.doc(workspaceId: String, id: String): DocRecord? = read { docRow(workspaceId, id) }

suspend fun StoreReader.docBody(workspaceId: String, docId: String): DocBodyRecord? = read {
    first(
        "SELECT storage_object_id, sha256, content_type, doc_row_version, bytes, fetched_at FROM doc_bodies WHERE workspace_id = ? AND doc_id = ?",
        workspaceId, docId,
    ) { DocBodyRecord(workspaceId, docId, it.text(0)!!, it.text(1)!!, it.text(2)!!, it.long(3), it.blob(4), it.text(5)!!) }
}

suspend fun StoreReader.docDraft(key: String): DocDraft? = read {
    first(
        """SELECT connection_id, workspace_id, doc_id, title, body, content_type, base_row_version, base_sha256, base_storage_object_id, state,
                  last_error, recovery_id FROM doc_drafts WHERE key = ?""",
        key,
    ) {
        DocDraft(
            it.text(0)!!, it.text(1)!!, it.text(2)!!, it.text(3)!!, it.blob(4), it.text(5)!!, it.int(6), it.text(7)!!, it.text(8)!!,
            DocDraft.State.entries.firstOrNull { s -> s.raw == it.text(9) } ?: DocDraft.State.EDITING, it.text(10), it.text(11),
        )
    }
}

suspend fun StoreReader.docDraftIds(connectionId: String, workspaceId: String): Set<String> = read {
    query("SELECT doc_id FROM doc_drafts WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId) { it.text(0)!! }.toSet()
}

private const val FILE_COLUMNS = "id, scope_id, channel_id, folder_id, storage_object_id, display_name, description, content_type, size_bytes, " +
    "sha256, archived, row_version, created_at, updated_at, raw, access_removed"

private fun file(r: SqlRow, w: String) = FileRecord(
    workspaceId = w, id = r.text(0)!!, scopeId = r.text(1), channelId = r.text(2)!!, folderId = r.text(3), storageObjectId = r.text(4),
    displayName = r.text(5)!!, description = r.text(6), contentType = r.text(7), sizeBytes = if (r.isNull(8)) null else r.long(8),
    sha256 = r.text(9), archived = r.bool(10), rowVersion = r.long(11), createdAt = r.text(12)!!, updatedAt = r.text(13)!!,
    raw = parseRaw(r.text(14)), accessRemoved = r.bool(15),
)

suspend fun StoreReader.files(workspaceId: String, channelId: String? = null, limit: Int = 500): List<FileRecord> = read {
    query(
        "SELECT $FILE_COLUMNS FROM files WHERE workspace_id = ? AND (? IS NULL OR channel_id = ?) AND archived = 0 ORDER BY display_name COLLATE NOCASE, id LIMIT ?",
        workspaceId, channelId, channelId, limit,
    ) { file(it, workspaceId) }
}

suspend fun StoreReader.files(workspaceId: String, context: WorkContext, limit: Int = 500): List<FileRecord> = read {
    val f = ContextQueries.contextFilter(context, workspaceId)
    query(
        "SELECT $FILE_COLUMNS FROM files WHERE workspace_id = ? AND ${f.sql} AND archived = 0 ORDER BY display_name COLLATE NOCASE, id LIMIT ?",
        workspaceId, *f.bindings.toTypedArray(), limit,
    ) { file(it, workspaceId) }
}

internal fun SqlConnection.fileRow(workspaceId: String, id: String): FileRecord? =
    first("SELECT $FILE_COLUMNS FROM files WHERE workspace_id = ? AND id = ?", workspaceId, id) { file(it, workspaceId) }

suspend fun StoreReader.file(workspaceId: String, id: String): FileRecord? = read { fileRow(workspaceId, id) }

suspend fun StoreReader.folders(workspaceId: String, channelId: String): List<FolderRecord> = read {
    query(
        """SELECT id, parent_folder_id, title, deleted, row_version, updated_at, raw, access_removed FROM folders
           WHERE workspace_id = ? AND channel_id = ? AND deleted = 0 ORDER BY title COLLATE NOCASE, id""",
        workspaceId, channelId,
    ) {
        FolderRecord(workspaceId, it.text(0)!!, channelId, it.text(1), it.text(2)!!, it.bool(3), it.long(4), it.text(5)!!, parseRaw(it.text(6)), it.bool(7))
    }
}

suspend fun StoreReader.viewStates(workspaceId: String): List<ViewStateRecord> = read { viewStateRows(workspaceId) }

private fun SqlConnection.viewStateRows(workspaceId: String): List<ViewStateRecord> = query(
    """SELECT resource_type, resource_id, scope_id, channel_id, activity_version, viewed_activity_version, attention_activity_version, row_version, updated_at
       FROM view_states WHERE workspace_id = ? ORDER BY updated_at DESC""",
    workspaceId,
) {
    val type = ViewStateRecord.ResourceType.from(it.text(0)) ?: return@query null
    ViewStateRecord(workspaceId, type, it.text(1)!!, it.text(2), it.text(3), it.long(4), it.long(5), if (it.isNull(6)) null else it.long(6),
        it.long(7), it.text(8)!!)
}.filterNotNull()

suspend fun StoreReader.viewState(workspaceId: String, type: ViewStateRecord.ResourceType, id: String): ViewStateRecord? =
    viewStates(workspaceId).firstOrNull { it.resourceType == type && it.resourceId == id }

/** Channels with unread or attention-needing activity (any resource in them), for the sidebar. */
suspend fun StoreReader.unreadChannelIds(workspaceId: String): Set<String> = read {
    query(
        """SELECT DISTINCT channel_id FROM view_states WHERE workspace_id = ? AND channel_id IS NOT NULL
             AND (activity_version > viewed_activity_version OR (attention_activity_version IS NOT NULL AND attention_activity_version > viewed_activity_version))""",
        workspaceId,
    ) { it.text(0)!! }.toSet()
}

suspend fun StoreReader.reactions(workspaceId: String, targetIds: List<String>): List<ReactionRecord> = read {
    // Bounded IN lists keep the statement small for long conversations.
    targetIds.bounded().flatMap { chunk ->
        query(
            """SELECT id, target_type, target_id, emoji, reactor_npub, row_version, updated_at FROM reactions
               WHERE workspace_id = ? AND deleted = 0 AND target_id IN (${marks(chunk.size)}) ORDER BY updated_at, id""",
            workspaceId, *chunk.toTypedArray(),
        ) { ReactionRecord(workspaceId, it.text(0)!!, it.text(1)!!, it.text(2)!!, it.text(3)!!, it.text(4), false, it.long(5), it.text(6)!!) }
    }
}

suspend fun StoreReader.listSyncComplete(workspaceId: String, key: String): Boolean? = read {
    first("SELECT complete FROM list_sync WHERE workspace_id = ? AND key = ?", workspaceId, key) { it.bool(0) }
}

// MARK: Search

/**
 * Searches this workspace's local records only (never the network), most relevant first, bounded. Each word of `text`
 * becomes a quoted FTS5 prefix term, so user input cannot inject query syntax. Flagged records are found and labelled.
 */
suspend fun StoreReader.search(workspaceId: String, text: String, limit: Int = 60): List<SearchHit> = read {
    val trimmed = text.trim()
    if (trimmed.codePointCount(0, trimmed.length) < 2) return@read emptyList()
    val query = SearchIndex.ftsQuery(trimmed) ?: return@read emptyList()
    val hits = query(
        "SELECT kind, record_id FROM search_index WHERE search_index MATCH ? AND workspace_id = ? ORDER BY rank LIMIT ?",
        query, workspaceId, limit,
    ) { it.text(0)!! to it.text(1)!! }
    hits.mapNotNull { (kind, id) ->
        when (kind) {
            "task" -> taskRow(workspaceId, id)?.let { t ->
                SearchHit(SearchHit.Kind.TASK, t.id, t.title, t.description?.take(140) ?: (t.taskState?.label ?: t.state), t.channelId, null, t.accessRemoved)
            }
            "doc" -> docRow(workspaceId, id)?.let { d ->
                SearchHit(SearchHit.Kind.DOC, d.id, d.title, (d.summary ?: "").take(140), d.channelId, null, d.accessRemoved)
            }
            "file" -> fileRow(workspaceId, id)?.let { f ->
                SearchHit(SearchHit.Kind.FILE, f.id, f.displayName, f.contentType ?: "", f.channelId, null, f.accessRemoved)
            }
            else -> messageRow(workspaceId, id)?.takeIf { !it.deleted }?.let { m ->
                SearchHit(SearchHit.Kind.MESSAGE, m.id, m.senderLabel ?: "Message", MentionCodec.plain(m.body).take(160), m.channelId, m.threadId,
                    m.accessRemoved)
            }
        }
    }
}

// MARK: Deck reads

/** Newest `limit` conversations with their latest stored message, optionally limited to `channelIds`. */
suspend fun StoreReader.deckThreads(workspaceId: String, channelIds: List<String>?, limit: Int = 300): List<DeckActivity.ThreadActivity> = read {
    if (channelIds != null && channelIds.isEmpty()) return@read emptyList()
    val filter = channelIds?.let { "AND m.channel_id IN (${marks(it.size)})" } ?: ""
    // Newest conversations first (one grouped scan), then the titles and bodies for only those rows.
    query(
        """WITH latest AS (
             SELECT COALESCE(m.thread_id, m.id) AS tid, MIN(m.channel_id) AS channel, MAX(m.created_at) AS at, COUNT(*) AS n,
                    MAX(m.thread_id IS NOT NULL) AS threaded
             FROM messages m
             WHERE m.workspace_id = ?1 AND m.deleted = 0 $filter
             GROUP BY tid ORDER BY at DESC, tid LIMIT ?${2 + (channelIds?.size ?: 0)})
           SELECT latest.tid, latest.channel, latest.at, latest.n, latest.threaded,
             (SELECT t.title FROM threads t WHERE t.workspace_id = ?1 AND t.id = latest.tid AND t.deleted = 0),
             (SELECT r.body FROM messages r WHERE r.workspace_id = ?1 AND r.thread_id = latest.tid AND r.thread_source_id = r.id AND r.deleted = 0 LIMIT 1),
             CASE WHEN latest.threaded THEN
               (SELECT l.body FROM messages l WHERE l.workspace_id = ?1 AND l.thread_id = latest.tid AND l.deleted = 0 ORDER BY l.created_at DESC, l.id DESC LIMIT 1)
             ELSE (SELECT l.body FROM messages l WHERE l.workspace_id = ?1 AND l.id = latest.tid) END
           FROM latest
           WHERE NOT EXISTS (SELECT 1 FROM threads x WHERE x.workspace_id = ?1 AND x.id = latest.tid AND (x.deleted = 1 OR x.archived = 1))
           ORDER BY latest.at DESC, latest.tid""",
        workspaceId, *(channelIds ?: emptyList()).toTypedArray(), limit,
    ) {
        DeckActivity.ThreadActivity(
            threadId = it.text(0)!!, channelId = it.text(1)!!, isThread = it.bool(4), title = it.text(5), rootBody = it.text(6),
            latestBody = it.text(7) ?: "", latestAt = it.text(2) ?: "", messageCount = it.int(3),
        )
    }
}

/** Comment counts and newest times per task (comments by anyone but `viewerNpub`) or per document (open comments). */
suspend fun StoreReader.deckCommentActivity(
    workspaceId: String, parent: CommentRecord.Parent, viewerNpub: String?,
): Map<String, DeckActivity.CommentActivity> = read {
    val condition = if (parent == CommentRecord.Parent.TASK) "AND (sender_npub IS NULL OR sender_npub <> ?)" else "AND COALESCE(status, 'open') = 'open'"
    val binds = mutableListOf<Any?>(workspaceId, parent.wire)
    if (parent == CommentRecord.Parent.TASK) binds.add(viewerNpub ?: "")
    query(
        "SELECT parent_id, COUNT(*), MAX(updated_at) FROM comments WHERE workspace_id = ? AND parent = ? AND deleted = 0 $condition GROUP BY parent_id",
        *binds.toTypedArray(),
    ) { it.text(0)!! to DeckActivity.CommentActivity(it.int(1), it.text(2)) }.toMap()
}

/** Every stored task of a channel including archived ones (the task detail's parent/child projection reads these). */
suspend fun StoreReader.channelTasks(workspaceId: String, channelId: String, limit: Int = 2000): List<TaskRecord> =
    tasks(workspaceId, channelId = channelId, includeArchived = true, limit = limit)

/** Pages whose saved text links to one document, and how many saved pages could be checked. */
data class DocBacklinks(val docs: List<DocRecord>, val checked: Int)

/**
 * "Linked from": pages saved on this device whose body links to [docId], in any channel or scope this device can read.
 * Pages whose access was removed are not listed and the page itself is excluded. [DocBacklinks.checked] says how many saved
 * pages were checked, so an empty list is not mistaken for proof that nothing links here.
 */
suspend fun StoreReader.docBacklinks(workspaceId: String, docId: String): DocBacklinks = read {
    val needle = docId.lowercase().toByteArray()
    val saved = longValue(
        "SELECT COUNT(*) FROM doc_bodies b JOIN docs d ON d.workspace_id = b.workspace_id AND d.id = b.doc_id WHERE b.workspace_id = ? AND d.access_removed = 0",
        workspaceId,
    )?.toInt() ?: 0
    // The stored id is matched as text first (cheap), then each candidate is parsed so code and other lookalikes do not count.
    val candidates = query(
        """SELECT b.doc_id, b.content_type, b.bytes FROM doc_bodies b JOIN docs d ON d.workspace_id = b.workspace_id AND d.id = b.doc_id
           WHERE b.workspace_id = ? AND b.doc_id <> ? AND d.access_removed = 0 AND instr(lower(CAST(b.bytes AS TEXT)), ?) > 0
           ORDER BY d.title COLLATE NOCASE, d.id""",
        workspaceId, docId, String(needle),
    ) { Triple(it.text(0)!!, it.text(1)!!, it.blob(2)) }
    val linked = candidates.filter { (_, type, bytes) ->
        runCatching { studio.otherstuff.ambulando.core.docs.WikiLinks.links(studio.otherstuff.ambulando.core.docs.DocumentBody(bytes, type), docId) }.getOrDefault(false)
    }.mapNotNull { docRow(workspaceId, it.first) }
    DocBacklinks(linked, saved)
}

/**
 * Moves a draft's base version with the document (a move changes the row version, not the text). It is a compare-and-set
 * on the draft as it is now, so an edit made while the move was in flight is never overwritten. False when the draft is
 * gone or no longer based on [from].
 */
suspend fun StoreWriter.rebaseDocDraft(key: String, workspaceId: String, from: Int, to: Int): Boolean = write {
    val changed = db.run("UPDATE doc_drafts SET base_row_version = ? WHERE key = ? AND base_row_version = ?", to, key, from) > 0
    if (changed) emit(StoreTable.DOC_DRAFTS, workspaceId)
    changed
}

// MARK: Writer — document drafts, compare-and-set
// A draft is claimed, replaced or retired only while it is still exactly what the caller read, in one statement, so a later
// edit made while a caller awaited the network is never overwritten by an older snapshot.

/** Replaces a draft only if it still matches [expected] (body, title, content type, full base identity and state). False: it changed; nothing written. */
suspend fun StoreWriter.replaceDocDraftIfUnchanged(expected: DocDraft, next: DocDraft): Boolean = write {
    val changed = db.run(
        """UPDATE doc_drafts SET title = ?, body = ?, content_type = ?, base_row_version = ?, base_sha256 = ?, base_storage_object_id = ?, state = ?,
             last_error = ?, recovery_id = ?, updated_at = ?
           WHERE key = ? AND body = ? AND base_row_version = ? AND base_sha256 = ? AND state = ? AND title = ? AND content_type = ? AND base_storage_object_id = ?""",
        next.title, next.body, next.contentType, next.baseRowVersion, next.baseSha256, next.baseStorageObjectId, next.state.raw, next.lastError,
        next.recoveryId, StoreTime.now(),
        expected.key, expected.body, expected.baseRowVersion, expected.baseSha256, expected.state.raw, expected.title, expected.contentType,
        expected.baseStorageObjectId,
    ) > 0
    if (changed) emit(StoreTable.DOC_DRAFTS, next.workspaceId)
    changed
}

/** Stores a draft only when none exists yet (a clean document's first draft). False: another draft appeared first. */
suspend fun StoreWriter.insertDocDraftIfAbsent(draft: DocDraft): Boolean = write {
    val changed = db.run(
        """INSERT INTO doc_drafts(key, connection_id, workspace_id, doc_id, title, body, content_type, base_row_version, base_sha256,
                                  base_storage_object_id, state, last_error, recovery_id, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(key) DO NOTHING""",
        draft.key, draft.connectionId, draft.workspaceId, draft.docId, draft.title, draft.body, draft.contentType, draft.baseRowVersion,
        draft.baseSha256, draft.baseStorageObjectId, draft.state.raw, draft.lastError, draft.recoveryId, StoreTime.now(),
    ) > 0
    if (changed) emit(StoreTable.DOC_DRAFTS, draft.workspaceId)
    changed
}

/** Removes a draft only if it is still exactly [expected] (the one the person deliberately replaced). */
suspend fun StoreWriter.deleteDocDraftIfUnchanged(expected: DocDraft): Boolean = write {
    val changed = db.run(
        """DELETE FROM doc_drafts WHERE key = ? AND body = ? AND title = ? AND content_type = ?
             AND base_row_version = ? AND base_sha256 = ? AND base_storage_object_id = ? AND state = ?""",
        expected.key, expected.body, expected.title, expected.contentType, expected.baseRowVersion, expected.baseSha256,
        expected.baseStorageObjectId, expected.state.raw,
    ) > 0
    if (changed) emit(StoreTable.DOC_DRAFTS, expected.workspaceId)
    changed
}

/**
 * Completes only the submitted snapshot. Typing persisted during the request keeps its body and title and advances to the
 * accepted base, so its next save cannot conflict with this device's own successful save.
 */
suspend fun StoreWriter.finishDocDraftSave(submitted: DocDraft, savedRowVersion: Int, savedSha256: String, savedStorageObjectId: String): Unit = write {
    db.run(
        """DELETE FROM doc_drafts WHERE key = ? AND body = ? AND title = ? AND content_type = ?
             AND base_row_version = ? AND base_sha256 = ? AND base_storage_object_id = ? AND state = ?""",
        submitted.key, submitted.body, submitted.title, submitted.contentType, submitted.baseRowVersion, submitted.baseSha256,
        submitted.baseStorageObjectId, submitted.state.raw,
    )
    db.run(
        """UPDATE doc_drafts SET base_row_version = ?, base_sha256 = ?, base_storage_object_id = ?, updated_at = ?
           WHERE key = ? AND base_row_version = ? AND base_sha256 = ? AND base_storage_object_id = ?""",
        savedRowVersion, savedSha256, savedStorageObjectId, StoreTime.now(),
        submitted.key, submitted.baseRowVersion, submitted.baseSha256, submitted.baseStorageObjectId,
    )
    emit(StoreTable.DOC_DRAFTS, submitted.workspaceId)
}
