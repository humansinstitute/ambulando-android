package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.transport.NotificationChange
import studio.otherstuff.ambulando.core.transport.NotificationItem
import studio.otherstuff.ambulando.core.transport.NotificationPatch
import studio.otherstuff.ambulando.core.transport.NotificationView

// The local copy of Tower's per-recipient notification inbox (see Schema migration 3).
//
// Rules this file enforces:
// - Only the `inbox` partition is hydrated by default. A change for an item this device does not hold, and that is
//   outside the hydrated partitions, is ignored but still advances `local_seq`.
// - A change or snapshot row never overwrites a newer one (`change_seq`, then `row_version`).
// - The optimistic overlay (`pending_*`) is separate from server flags. It is dropped when Tower reports a newer
//   `row_version` than the one the user acted on, so activity folded into an item after the user looked at it shows as
//   unread again instead of staying hidden behind an old tap.
// - Content is purged when access is lost; rows hold no more than Tower sent (already redacted for unavailable items).

/** Backend availability and replay position of one workspace's inbox on this connection. */
data class NotificationSyncState(
    val state: Phase, val localSeq: Long, val headSeq: Long?, val archivedLoaded: Boolean, val syncedAt: String?, val lastError: String?,
    /** When Tower began this recipient's inbox. Nothing before it is imported, so the screen says so. */
    val inboxStartedAt: String? = null,
    /** The rules revision (wire v1.1) the stored snapshot was taken under; sent with `changes`. Null when Tower sent none. */
    val rulesRevision: Long? = null,
) {
    enum class Phase(val wire: String) {
        /** Nothing fetched yet: a cold start (snapshot, then replay) is needed. */
        UNSYNCED("unsynced"),
        READY("ready"),
        /** Tower has no notification routes (older Tower): the inbox shows an honest unavailable state. */
        UNSUPPORTED("unsupported"),
        /** Tower refused the workspace (403/404): local notification text was purged. */
        ACCESS_LOST("access_lost");

        companion object { fun from(wire: String?): Phase = entries.firstOrNull { it.wire == wire } ?: UNSYNCED }
    }
}

/** An item as the screen shows it: Tower's item plus the effective flags (server state under the pending overlay). */
data class NotificationRow(
    val item: NotificationItem,
    val read: Boolean,
    val dismissed: Boolean,
    val archived: Boolean,
    val partition: String,
    /** A state write for this item is waiting to reach Tower. */
    val pending: Boolean,
    val pendingBaseVersion: Long?,
) {
    val unread: Boolean get() = item.status == "active" && item.accessible && !read && !dismissed && !archived
    val id: String get() = item.id
}

data class NotificationCountsLocal(val unread: Int, val byCategory: Map<String, Int>)

private const val ROW_COLUMNS = """raw, is_read, dismissed, archived, partition, pending_read, pending_dismissed, pending_archived, pending_base_version"""

private fun rowOf(r: SqlRow): NotificationRow? {
    val item = runCatching { NotificationItem.parse(parseRaw(r.text(0))) }.getOrNull() ?: return null
    val pendingBase = if (r.isNull(8)) null else r.long(8)
    val pending = !r.isNull(5) || !r.isNull(6) || !r.isNull(7)
    return NotificationRow(
        item, read = if (r.isNull(5)) r.bool(1) else r.bool(5), dismissed = if (r.isNull(6)) r.bool(2) else r.bool(6),
        archived = if (r.isNull(7)) r.bool(3) else r.bool(7), partition = r.text(4) ?: "inbox", pending = pending, pendingBaseVersion = pendingBase,
    )
}

// MARK: Writer

private const val UPSERT_SQL = """INSERT INTO notification_items(connection_id, workspace_id, id, change_seq, row_version, partition, category, source_app,
    severity, status, title, body, occurred_at, is_read, dismissed, archived, accessible, raw) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
  ON CONFLICT(connection_id, workspace_id, id) DO UPDATE SET change_seq=excluded.change_seq, row_version=excluded.row_version,
    partition=excluded.partition, category=excluded.category, source_app=excluded.source_app, severity=excluded.severity, status=excluded.status,
    title=excluded.title, body=excluded.body, occurred_at=excluded.occurred_at, is_read=excluded.is_read, dismissed=excluded.dismissed,
    archived=excluded.archived, accessible=excluded.accessible, raw=excluded.raw,
    pending_read = CASE WHEN notification_items.pending_base_version IS NULL OR excluded.row_version > notification_items.pending_base_version
                        THEN NULL ELSE notification_items.pending_read END,
    pending_dismissed = CASE WHEN notification_items.pending_base_version IS NULL OR excluded.row_version > notification_items.pending_base_version
                        THEN NULL ELSE notification_items.pending_dismissed END,
    pending_archived = CASE WHEN notification_items.pending_base_version IS NULL OR excluded.row_version > notification_items.pending_base_version
                        THEN NULL ELSE notification_items.pending_archived END,
    pending_base_version = CASE WHEN notification_items.pending_base_version IS NULL OR excluded.row_version > notification_items.pending_base_version
                        THEN NULL ELSE notification_items.pending_base_version END
  WHERE excluded.change_seq > notification_items.change_seq
     OR (excluded.change_seq = notification_items.change_seq AND excluded.row_version >= notification_items.row_version)"""

private fun WriteTx.upsertItem(connectionId: String, w: String, item: NotificationItem, partition: String, authoritative: Boolean = false): Int {
    val s = item.state
    return db.run(
        if (authoritative) UPSERT_SQL.substringBefore("  WHERE excluded.change_seq") else UPSERT_SQL, connectionId, w, item.id, item.changeSeq, item.rowVersion, partition, item.category, item.sourceApp, item.severity, item.status,
        item.title, item.body, item.occurredAt, s.read, s.dismissed, s.archived, item.accessible, canonical(item.raw),
    )
}

private fun WriteTx.setSync(connectionId: String, w: String, state: NotificationSyncState.Phase, localSeq: Long?, headSeq: Long?, error: String?) {
    db.run(
        """INSERT INTO notification_sync(connection_id, workspace_id, state, local_seq, head_seq, synced_at, last_error) VALUES(?,?,?,?,?,?,?)
           ON CONFLICT(connection_id, workspace_id) DO UPDATE SET state=excluded.state,
             local_seq = CASE WHEN ? THEN excluded.local_seq ELSE notification_sync.local_seq END,
             head_seq = COALESCE(excluded.head_seq, notification_sync.head_seq), synced_at = excluded.synced_at, last_error = excluded.last_error""",
        connectionId, w, state.wire, localSeq ?: 0L, headSeq, StoreTime.now(), error, localSeq != null,
    )
}

/** Where a paged snapshot stands: its generation, the snapshot sequence `S` (first page's `head_seq`) and the cursor to continue from. */
data class NotificationSnapshotProgress(val gen: Long, val seq: Long?, val cursor: String?, val rulesRevision: Long? = null)

private fun WriteTx.ensureSyncRow(connectionId: String, w: String) {
    db.run("INSERT OR IGNORE INTO notification_sync(connection_id, workspace_id, state, local_seq) VALUES(?,?,?,0)", connectionId, w, NotificationSyncState.Phase.UNSYNCED.wire)
}

/** Progress of the inbox snapshot that is mid-way (cursor stored), or null when none is. A resumed job continues from it. */
suspend fun StoreReader.notificationSnapshotProgress(connectionId: String, workspaceId: String): NotificationSnapshotProgress? = read {
    first("SELECT snapshot_gen, snapshot_seq, snapshot_cursor, snapshot_rules_revision FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId) {
        if (it.isNull(2)) null else NotificationSnapshotProgress(it.long(0), if (it.isNull(1)) null else it.long(1), it.text(2), if (it.isNull(3)) null else it.long(3))
    }
}

suspend fun StoreReader.notificationArchiveProgress(connectionId: String, workspaceId: String): NotificationSnapshotProgress? = read {
    first("SELECT archive_gen, archive_cursor FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId) {
        if (it.isNull(1)) null else NotificationSnapshotProgress(it.long(0), null, it.text(1))
    }
}

/** Starts a fresh inbox snapshot generation (any earlier partial progress is abandoned; held rows stay until completion). */
suspend fun StoreWriter.beginNotificationSnapshot(connectionId: String, workspaceId: String): Long = write {
    ensureSyncRow(connectionId, workspaceId)
    db.run("UPDATE notification_sync SET snapshot_gen = snapshot_gen + 1, snapshot_seq = NULL, snapshot_cursor = NULL, snapshot_rules_revision = NULL WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    db.longValue("SELECT snapshot_gen FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId) ?: 0L
}

/**
 * Stores one page of an inbox snapshot and the continuation, atomically. [seq] is the first page's `head_seq` and is
 * fixed for the generation. A page for a generation that is no longer current (a newer cold start began) is ignored.
 * Returns whether the page was applied. The replay cursor (`local_seq`) is NOT touched here: it moves only on completion.
 */
suspend fun StoreWriter.saveNotificationSnapshotPage(
    connectionId: String, workspaceId: String, gen: Long, items: List<NotificationItem>, nextCursor: String?, seq: Long, inboxStartedAt: String?,
    rulesRevision: Long? = null,
): Boolean = write {
    val current = db.longValue("SELECT snapshot_gen FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    if (current != gen) return@write false
    for (item in items) {
        if (item.workspaceId != workspaceId) continue
        // A snapshot is Tower's state at that sequence: it replaces what was held (rows from before a Tower restore included).
        upsertItem(connectionId, workspaceId, item, "inbox", authoritative = true)
        db.run("UPDATE notification_items SET snapshot_gen = ? WHERE connection_id = ? AND workspace_id = ? AND id = ?", gen, connectionId, workspaceId, item.id)
    }
    // [rulesRevision] is the first page's, fixed for the generation (like [seq]); later pages leave it alone.
    db.run("UPDATE notification_sync SET snapshot_seq = ?, snapshot_cursor = ?, inbox_started_at = COALESCE(?, inbox_started_at), snapshot_rules_revision = COALESCE(?, snapshot_rules_revision) WHERE connection_id = ? AND workspace_id = ?",
        seq, nextCursor, inboxStartedAt, rulesRevision, connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
    true
}

/**
 * The last page of generation [gen] was stored: inbox rows this generation did not list are removed (dismissed, withdrawn
 * or no longer visible), and only now does the replay cursor become the snapshot sequence. Never called after a truncated listing.
 */
suspend fun StoreWriter.completeNotificationSnapshot(connectionId: String, workspaceId: String, gen: Long, seq: Long): Boolean = write {
    val current = db.longValue("SELECT snapshot_gen FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    if (current != gen) return@write false
    db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND partition = 'inbox' AND snapshot_gen <> ?", connectionId, workspaceId, gen)
    setSync(connectionId, workspaceId, NotificationSyncState.Phase.READY, seq, seq, null)
    db.run("UPDATE notification_sync SET rules_revision = snapshot_rules_revision, snapshot_rules_revision = NULL, snapshot_cursor = NULL, snapshot_seq = NULL WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
    true
}

/**
 * Whole-snapshot convenience (a single page): begins, stores and completes in order. Used where a complete listing is
 * already in hand.
 */
suspend fun StoreWriter.replaceNotificationInbox(
    connectionId: String, workspaceId: String, items: List<NotificationItem>, snapshotSeq: Long, inboxStartedAt: String? = null, rulesRevision: Long? = null,
) {
    val gen = beginNotificationSnapshot(connectionId, workspaceId)
    saveNotificationSnapshotPage(connectionId, workspaceId, gen, items, null, snapshotSeq, inboxStartedAt, rulesRevision)
    completeNotificationSnapshot(connectionId, workspaceId, gen, snapshotSeq)
}

/**
 * Applies one replay page and advances `local_seq` to [throughSeq] in the same transaction. Idempotent: a change at or
 * below the stored item's `change_seq` is ignored. Items that leave the inbox (dismissed) are dropped; archived items are
 * kept only when the archive was hydrated on this device; removals (withdrawn, access lost, retention) delete the row.
 */
suspend fun StoreWriter.applyNotificationChanges(connectionId: String, workspaceId: String, changes: List<NotificationChange>, throughSeq: Long) = write {
    val archivedLoaded = db.first("SELECT archived_loaded FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId) { it.bool(0) } ?: false
    for (change in changes) {
        when (change) {
            is NotificationChange.Removed ->
                db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND id = ? AND change_seq < ?", connectionId, workspaceId, change.id, change.changeSeq)
            is NotificationChange.Upsert -> {
                val item = change.item
                if (item.workspaceId != workspaceId) continue
                val known = db.first("SELECT change_seq FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND id = ?", connectionId, workspaceId, item.id) { it.long(0) }
                val state = item.state
                when {
                    state.dismissed ->
                        if (known != null && item.changeSeq > known) db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND id = ?", connectionId, workspaceId, item.id)
                    state.archived ->
                        if (known != null || archivedLoaded) {
                            upsertItem(connectionId, workspaceId, item, "archived")
                            // Archived while an archive listing may be in flight: current for that generation, so completion keeps it.
                            db.run("UPDATE notification_items SET snapshot_gen = -COALESCE((SELECT archive_gen FROM notification_sync WHERE connection_id = ? AND workspace_id = ?), 0) WHERE connection_id = ? AND workspace_id = ? AND id = ? AND change_seq = ?",
                                connectionId, workspaceId, connectionId, workspaceId, item.id, item.changeSeq)
                        }
                    else -> upsertItem(connectionId, workspaceId, item, "inbox")
                }
            }
        }
    }
    db.run("UPDATE notification_sync SET local_seq = MAX(local_seq, ?), head_seq = MAX(COALESCE(head_seq, 0), ?), synced_at = ?, last_error = NULL, state = ? WHERE connection_id = ? AND workspace_id = ?",
        throughSeq, throughSeq, StoreTime.now(), NotificationSyncState.Phase.READY.wire, connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
}

suspend fun StoreWriter.beginNotificationArchive(connectionId: String, workspaceId: String): Long = write {
    ensureSyncRow(connectionId, workspaceId)
    db.run("UPDATE notification_sync SET archive_gen = archive_gen + 1, archive_cursor = NULL WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    db.longValue("SELECT archive_gen FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId) ?: 0L
}

/** One page of the archive, loaded on demand (a separate partition; `changes` keeps it current afterwards). */
suspend fun StoreWriter.saveArchivedNotificationPage(
    connectionId: String, workspaceId: String, gen: Long, items: List<NotificationItem>, nextCursor: String?,
): Boolean = write {
    val current = db.longValue("SELECT archive_gen FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    if (current != gen) return@write false
    for (item in items) if (item.workspaceId == workspaceId && item.state.archived && !item.state.dismissed) {
        upsertItem(connectionId, workspaceId, item, "archived", authoritative = true)
        // Archive generations are stored negated so they never equal an inbox generation.
        db.run("UPDATE notification_items SET snapshot_gen = ? WHERE connection_id = ? AND workspace_id = ? AND id = ?", -gen, connectionId, workspaceId, item.id)
    }
    db.run("UPDATE notification_sync SET archive_cursor = ? WHERE connection_id = ? AND workspace_id = ?", nextCursor, connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
    true
}

/** The archive listing finished: archived rows it did not list are dropped (except where the user's own pending change decides), then it counts as loaded. */
suspend fun StoreWriter.completeNotificationArchive(connectionId: String, workspaceId: String, gen: Long): Boolean = write {
    val current = db.longValue("SELECT archive_gen FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    if (current != gen) return@write false
    db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND partition = 'archived' AND snapshot_gen <> ? AND pending_archived IS NULL", connectionId, workspaceId, -gen)
    db.run("UPDATE notification_sync SET archived_loaded = 1, archive_cursor = NULL WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
    true
}

/** Whole-archive convenience (a single page). */
suspend fun StoreWriter.saveArchivedNotifications(connectionId: String, workspaceId: String, items: List<NotificationItem>) {
    val gen = beginNotificationArchive(connectionId, workspaceId)
    saveArchivedNotificationPage(connectionId, workspaceId, gen, items, null)
    completeNotificationArchive(connectionId, workspaceId, gen)
}

/** Tower acknowledged a write (or sent the current item with a conflict): the server row replaces the overlay. */
suspend fun StoreWriter.acknowledgeNotificationItem(connectionId: String, workspaceId: String, item: NotificationItem) = write {
    adoptNotificationItem(connectionId, workspaceId, item)
}

internal fun WriteTx.adoptNotificationItem(connectionId: String, workspaceId: String, item: NotificationItem) {
    if (item.workspaceId != workspaceId) return
    val partition = when {
        item.state.dismissed -> null
        item.state.archived -> "archived"
        else -> "inbox"
    }
    if (partition == null) db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND id = ? AND change_seq <= ?", connectionId, workspaceId, item.id, item.changeSeq)
    else {
        upsertItem(connectionId, workspaceId, item, partition)
        clearOverlayIfCurrent(connectionId, workspaceId, item.id, item.rowVersion)
    }
    emit(StoreTable.NOTIFICATIONS, workspaceId)
}

/** Tower accepted the user's change: the overlay becomes the stored flags (the replacing row follows with the next change). */
internal fun WriteTx.commitNotificationOverlayTx(connectionId: String, w: String, id: String) {
    db.run(
        """UPDATE notification_items SET is_read = COALESCE(pending_read, is_read), dismissed = COALESCE(pending_dismissed, dismissed),
             archived = COALESCE(pending_archived, archived),
             pending_read = NULL, pending_dismissed = NULL, pending_archived = NULL, pending_base_version = NULL
           WHERE connection_id = ? AND workspace_id = ? AND id = ?""", connectionId, w, id,
    )
    emit(StoreTable.NOTIFICATIONS, w)
}

internal fun WriteTx.clearNotificationOverlayTx(connectionId: String, w: String, id: String) {
    db.run(
        """UPDATE notification_items SET pending_read = NULL, pending_dismissed = NULL, pending_archived = NULL, pending_base_version = NULL
           WHERE connection_id = ? AND workspace_id = ? AND id = ?""", connectionId, w, id,
    )
    emit(StoreTable.NOTIFICATIONS, w)
}

private fun WriteTx.clearOverlayIfCurrent(connectionId: String, w: String, id: String, rowVersion: Long) {
    db.run(
        """UPDATE notification_items SET pending_read = NULL, pending_dismissed = NULL, pending_archived = NULL, pending_base_version = NULL
           WHERE connection_id = ? AND workspace_id = ? AND id = ? AND row_version >= ?""", connectionId, w, id, rowVersion,
    )
}

/**
 * The user acted: the overlay shows the intent immediately. The item's current `row_version` becomes the base the write is
 * checked against by Tower (the version the user was looking at); a second action on the same item keeps the first base.
 */
suspend fun StoreWriter.setNotificationOverlay(connectionId: String, workspaceId: String, id: String, patch: NotificationPatch) = write {
    db.run(
        """UPDATE notification_items SET
             pending_read = COALESCE(?, pending_read), pending_dismissed = COALESCE(?, pending_dismissed),
             pending_archived = COALESCE(?, pending_archived),
             pending_base_version = COALESCE(pending_base_version, row_version)
           WHERE connection_id = ? AND workspace_id = ? AND id = ?""",
        patch.read, patch.dismissed, patch.archived, connectionId, workspaceId, id,
    )
    emit(StoreTable.NOTIFICATIONS, workspaceId)
}

/** A write ended without Tower accepting it (rejected, discarded): the item shows its server state again. */
suspend fun StoreWriter.clearNotificationOverlay(connectionId: String, workspaceId: String, id: String) = write {
    clearNotificationOverlayTx(connectionId, workspaceId, id)
}

suspend fun StoreWriter.markNotificationBackend(connectionId: String, workspaceId: String, phase: NotificationSyncState.Phase, error: String? = null) = write {
    when (phase) {
        NotificationSyncState.Phase.ACCESS_LOST, NotificationSyncState.Phase.UNSUPPORTED ->
            // Nothing Tower sent for this workspace's inbox is kept once access is gone or the feature is not there.
            db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
        else -> Unit
    }
    setSync(connectionId, workspaceId, phase, if (phase == NotificationSyncState.Phase.UNSYNCED) 0L else null, null, error)
    db.run("UPDATE notification_sync SET snapshot_cursor = NULL, snapshot_seq = NULL, snapshot_rules_revision = NULL, rules_revision = NULL, archive_cursor = NULL WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
}

suspend fun StoreWriter.recordNotificationSyncError(connectionId: String, workspaceId: String, error: String) = write {
    db.run("UPDATE notification_sync SET last_error = ? WHERE connection_id = ? AND workspace_id = ?", error, connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
}

/** A cursor Tower no longer accepts (410): forget the replay position so the next run is a cold start. */
suspend fun StoreWriter.resetNotificationCursor(connectionId: String, workspaceId: String) = write {
    // The archive partition is only valid for the cursor it was loaded under: forget it with the cursor, reload on demand.
    db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND partition = 'archived'", connectionId, workspaceId)
    db.run("UPDATE notification_sync SET state = ?, local_seq = 0, archived_loaded = 0, snapshot_cursor = NULL, snapshot_seq = NULL, snapshot_rules_revision = NULL, rules_revision = NULL, archive_cursor = NULL WHERE connection_id = ? AND workspace_id = ?", NotificationSyncState.Phase.UNSYNCED.wire, connectionId, workspaceId)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
}

// MARK: Reader

suspend fun StoreReader.notificationSync(connectionId: String, workspaceId: String): NotificationSyncState? = read {
    first("SELECT state, local_seq, head_seq, archived_loaded, synced_at, last_error, inbox_started_at, rules_revision FROM notification_sync WHERE connection_id = ? AND workspace_id = ?", connectionId, workspaceId) {
        NotificationSyncState(NotificationSyncState.Phase.from(it.text(0)), it.long(1), if (it.isNull(2)) null else it.long(2), it.bool(3), it.text(4), it.text(5), it.text(6), if (it.isNull(7)) null else it.long(7))
    }
}

/** Inbox rows for a view, newest first. Effective flags decide membership, so a pending dismiss hides the row at once. */
suspend fun StoreReader.notificationRows(
    connectionId: String, workspaceId: String, view: NotificationView = NotificationView.INBOX, category: String? = null, limit: Int = 500,
): List<NotificationRow> = read {
    val rows = query(
        "SELECT $ROW_COLUMNS FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND (? IS NULL OR category = ?) ORDER BY occurred_at DESC, id DESC",
        connectionId, workspaceId, category, category, row = ::rowOf,
    ).filterNotNull()
    rows.filter {
        when (view) {
            NotificationView.INBOX -> !it.dismissed && !it.archived
            NotificationView.UNREAD -> it.unread
            NotificationView.ARCHIVED -> it.archived && !it.dismissed
            NotificationView.ALL -> !it.dismissed
        }
    }.take(limit)
}

suspend fun StoreReader.notificationRow(connectionId: String, workspaceId: String, id: String): NotificationRow? = read {
    first("SELECT $ROW_COLUMNS FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND id = ?", connectionId, workspaceId, id, row = ::rowOf)
}

/** Unread count and per-category split from the local store (Tower's `counts` wins on reconcile; this works offline). */
suspend fun StoreReader.notificationCounts(connectionId: String, workspaceId: String): NotificationCountsLocal {
    val unread = notificationRows(connectionId, workspaceId, NotificationView.UNREAD, limit = Int.MAX_VALUE)
    return NotificationCountsLocal(unread.size, unread.groupingBy { it.item.category }.eachCount())
}

/** Highest `change_seq` among the rows shown: the boundary a "mark all read" is allowed to touch. */
suspend fun StoreReader.notificationMaxSeq(connectionId: String, workspaceId: String): Long = read {
    longValue("SELECT MAX(change_seq) FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND partition = 'inbox'", connectionId, workspaceId) ?: 0L
}

/** Applies the effective-flag overlay of a whole boundary at once (bulk "mark all read up to here"). Returns the ids touched. */
suspend fun StoreWriter.setNotificationOverlayUpTo(
    connectionId: String, workspaceId: String, upToSeq: Long, patch: NotificationPatch, category: String? = null, onlyUnread: Boolean = true,
): List<String> = write {
    val ids = db.query(
        "SELECT id, is_read, dismissed, archived, pending_read, pending_dismissed, pending_archived FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND partition = 'inbox' AND change_seq <= ? AND (? IS NULL OR category = ?) AND status = 'active'",
        connectionId, workspaceId, upToSeq, category, category,
    ) { r ->
        val read = if (r.isNull(4)) r.bool(1) else r.bool(4)
        val dismissed = if (r.isNull(5)) r.bool(2) else r.bool(5)
        val archived = if (r.isNull(6)) r.bool(3) else r.bool(6)
        if (dismissed || archived || (onlyUnread && read)) null else r.text(0)!!
    }.filterNotNull()
    for (id in ids) db.run(
        """UPDATE notification_items SET pending_read = COALESCE(?, pending_read), pending_dismissed = COALESCE(?, pending_dismissed),
             pending_archived = COALESCE(?, pending_archived), pending_base_version = COALESCE(pending_base_version, row_version)
           WHERE connection_id = ? AND workspace_id = ? AND id = ?""",
        patch.read, patch.dismissed, patch.archived, connectionId, workspaceId, id,
    )
    emit(StoreTable.NOTIFICATIONS, workspaceId)
    ids
}

/** A single-item refresh answered "removed" (content-free): the row goes. */
suspend fun StoreWriter.removeNotificationRow(connectionId: String, workspaceId: String, id: String, changeSeq: Long) = write {
    db.run("DELETE FROM notification_items WHERE connection_id = ? AND workspace_id = ? AND id = ? AND change_seq <= ?", connectionId, workspaceId, id, changeSeq)
    emit(StoreTable.NOTIFICATIONS, workspaceId)
}
