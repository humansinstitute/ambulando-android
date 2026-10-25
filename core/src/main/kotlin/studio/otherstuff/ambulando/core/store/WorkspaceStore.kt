package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.context.WorkspaceSelection
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

// Connections, workspaces, scopes, channels, messages and app state (iOS LocalStore.swift).

// MARK: Writer

/** Saves a connection; the same origin + signer reuses its existing row (and id). */
suspend fun StoreWriter.saveConnection(c: ConnectionRecord): ConnectionRecord = write {
    db.run(
        """INSERT INTO connections(id, origin, signer_npub, tower_npub, created_at) VALUES(?,?,?,?,?)
           ON CONFLICT(origin, signer_npub) DO UPDATE SET tower_npub = excluded.tower_npub""",
        c.id, c.origin, c.signerNpub, c.towerNpub, c.createdAt,
    )
    val saved = db.first("SELECT id, created_at FROM connections WHERE origin = ? AND signer_npub = ?", c.origin, c.signerNpub) {
        c.copy(id = it.text(0)!!, createdAt = it.text(1)!!)
    } ?: throw SqlException("connection not saved")
    emit(StoreTable.CONNECTIONS)
    saved
}

/**
 * Disconnect: removes a connection with everything private to it (cascade) and all workspace data no other connection
 * on the device still lists (flagged or not). This is a real deletion, as on iOS.
 */
suspend fun StoreWriter.removeConnection(id: String) = write {
    val ids = db.query("SELECT workspace_id FROM workspaces WHERE connection_id = ?", id) { it.text(0)!! }
    db.run("DELETE FROM connections WHERE id = ?", id)
    db.run("DELETE FROM app_state WHERE key = ?", WorkspaceSelection.listedKey(id))
    for (w in ids) if (!db.workspaceStillReferenced(w)) deleteWorkspaceRows(w)
    for (t in StoreTable.entries) emit(t)
}

internal fun SqlConnection.workspaceStillReferenced(workspaceId: String): Boolean =
    first("SELECT 1 FROM workspaces WHERE workspace_id = ? LIMIT 1", workspaceId) { true } ?: false

internal fun WriteTx.deleteWorkspaceRows(workspaceId: String) {
    for (table in Schema.workspaceTables) db.run("DELETE FROM $table WHERE workspace_id = ?", workspaceId)
}

/**
 * Replaces the complete workspace list for one connection (authoritative discovery result). A workspace Tower no longer
 * lists is flagged `access_removed` (kept readable, left out of sync), not deleted; one that is listed again is
 * unflagged.
 */
suspend fun StoreWriter.replaceWorkspaces(connectionId: String, records: List<WorkspaceRecord>) = write {
    val stamp = StoreTime.now()
    db.prepare(
        """INSERT INTO workspaces(connection_id, workspace_id, app_npub, label, slug, tower_base_url, role, owner_npub, raw, synced_at, access_removed)
           VALUES(?,?,?,?,?,?,?,?,?,?,0)
           ON CONFLICT(connection_id, workspace_id) DO UPDATE SET app_npub=excluded.app_npub, label=excluded.label, slug=excluded.slug,
             tower_base_url=excluded.tower_base_url, role=excluded.role, owner_npub=excluded.owner_npub, raw=excluded.raw,
             synced_at=excluded.synced_at, access_removed=0""",
    ).use { s ->
        for (r in records) s.run(connectionId, r.id, r.appNpub, r.label, r.slug, r.towerBaseURL, r.role, r.ownerNpub, canonical(r.raw), stamp)
    }
    // Tower's answer is authoritative even when empty: an empty list after this is "no memberships", not "not loaded".
    db.run(
        "INSERT INTO app_state(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        WorkspaceSelection.listedKey(connectionId), stamp,
    )
    val keep = records.mapTo(HashSet()) { it.id }
    val gone = db.query("SELECT workspace_id FROM workspaces WHERE connection_id = ?", connectionId) { it.text(0)!! }.filter { it !in keep }
    for (w in gone) flagWorkspace(connectionId, w)
    emit(StoreTable.WORKSPACES)
}

/** Upserts scopes. When `complete`, scopes the actor can no longer see are flagged with their channels and content. */
suspend fun StoreWriter.upsertScopes(workspaceId: String, records: List<ScopeRecord>, complete: Boolean): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId }
    val flagged = db.query("SELECT id FROM scopes WHERE workspace_id = ? AND access_removed = 1", workspaceId) { it.text(0)!! }.toHashSet()
    var changed = 0
    db.prepare(
        """INSERT INTO scopes(workspace_id, id, name, kind, description, default_channel_id, row_version, updated_at, raw)
           VALUES(?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET name=excluded.name, kind=excluded.kind, description=excluded.description,
             default_channel_id=excluded.default_channel_id, row_version=excluded.row_version, updated_at=excluded.updated_at,
             raw=excluded.raw, access_removed=0
           ${newerGuard("scopes")}""",
    ).use { s ->
        for (r in rows) changed += s.run(workspaceId, r.id, r.name, r.kind, r.description, r.defaultChannelId, r.rowVersion, r.updatedAt, canonical(r.raw))
    }
    // A scope shown again gets its Context Tree back (its channels return with their own listing).
    for (r in rows) if (r.id in flagged && db.first("SELECT 1 FROM scopes WHERE workspace_id = ? AND id = ? AND access_removed = 0", workspaceId, r.id) { true } == true) {
        db.setScopeSurfacesRestored(workspaceId, r.id)
        emit(StoreTable.CONTEXT_TREE, workspaceId)
    }
    if (changed > 0) { emit(StoreTable.SCOPES, workspaceId); emit(StoreTable.CHANNELS, workspaceId) }
    if (complete) {
        val keep = rows.mapTo(HashSet()) { it.id }
        val gone = db.query("SELECT id FROM scopes WHERE workspace_id = ? AND access_removed = 0", workspaceId) { it.text(0)!! }.filter { it !in keep }
        for (id in gone) if (flagScope(workspaceId, id) > 0) changed += 1
    }
    changed
}

private fun SqlConnection.setScopeSurfacesRestored(w: String, scopeId: String) {
    run("UPDATE context_components SET access_removed = 0 WHERE workspace_id = ? AND scope_id = ? AND access_removed = 1", w, scopeId)
    run("UPDATE context_references SET access_removed = 0 WHERE workspace_id = ? AND scope_id = ? AND access_removed = 1", w, scopeId)
}

/**
 * Upserts one scope's channels. When `complete`, channels of that scope the actor can no longer see are flagged with all
 * their content; a flagged channel listed again is unflagged together with its content.
 */
suspend fun StoreWriter.upsertChannels(workspaceId: String, scopeId: String, records: List<ChannelRecord>, complete: Boolean): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId && it.scopeId == scopeId }
    val flagged = db.removedChannelIds(workspaceId)
    var changed = 0
    db.prepare(
        """INSERT INTO channels(workspace_id, id, scope_id, name, kind, position, row_version, updated_at, raw) VALUES(?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET scope_id=excluded.scope_id, name=excluded.name, kind=excluded.kind,
             position=excluded.position, row_version=excluded.row_version, updated_at=excluded.updated_at, raw=excluded.raw, access_removed=0
           ${newerGuard("channels")}""",
    ).use { s ->
        for (r in rows) changed += s.run(workspaceId, r.id, scopeId, r.name, r.kind, r.position, r.rowVersion, r.updatedAt, canonical(r.raw))
    }
    for (r in rows) if (r.id in flagged && db.first("SELECT 1 FROM channels WHERE workspace_id = ? AND id = ? AND access_removed = 0", workspaceId, r.id) { true } == true) {
        setChannelContent(workspaceId, r.id, false)
        emit(channelContentKeys(workspaceId))
    }
    if (changed > 0) emit(StoreTable.CHANNELS, workspaceId)
    if (complete) {
        val keep = rows.mapTo(HashSet()) { it.id }
        val gone = db.query("SELECT id FROM channels WHERE workspace_id = ? AND scope_id = ? AND access_removed = 0", workspaceId, scopeId) { it.text(0)!! }
            .filter { it !in keep }
        for (id in gone) if (flagChannel(workspaceId, id) > 0) changed += 1
    }
    changed
}

/**
 * Upserts one batch of messages in a single transaction. Callers keep batches ≤ 500 rows so a reader is never blocked
 * for long. Returns the number of rows written.
 */
suspend fun StoreWriter.upsertMessages(records: List<MessageRecord>): Int = write { writeMessages(records) }

/**
 * Shared by catch-up and send acknowledgement. A server row carrying the `client_request_id` of one of this sender's
 * outbox rows retires that row in the same transaction, so a message is never shown twice. Messages of a flagged
 * channel are not written (the outbox row is still retired).
 */
internal fun WriteTx.writeMessages(records: List<MessageRecord>): Int {
    if (records.isEmpty()) return 0
    var changed = 0
    val removed = HashMap<String, Set<String>>()
    db.prepare(
        """INSERT INTO messages(workspace_id, id, channel_id, thread_id, thread_source_id, body, sender_npub, sender_label, client_request_id,
                                created_at, updated_at, row_version, deleted, raw)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(workspace_id, id) DO UPDATE SET channel_id=excluded.channel_id, thread_id=excluded.thread_id,
             thread_source_id=excluded.thread_source_id, body=excluded.body, sender_npub=excluded.sender_npub, sender_label=excluded.sender_label,
             client_request_id=excluded.client_request_id, updated_at=excluded.updated_at, row_version=excluded.row_version,
             deleted=excluded.deleted, raw=excluded.raw, access_removed=0
           ${newerGuard("messages")}""",
    ).use { s ->
        db.prepare("DELETE FROM outbox WHERE id = ? AND workspace_id = ? AND signer_npub = ?").use { retire ->
            for (r in records) {
                val blocked = removed.getOrPut(r.workspaceId) { db.removedChannelIds(r.workspaceId) }
                if (r.channelId !in blocked) {
                    val n = s.run(
                        r.workspaceId, r.id, r.channelId, r.threadId, r.threadSourceId, r.body, r.senderNpub, r.senderLabel, r.clientRequestId,
                        r.createdAt, r.updatedAt, r.rowVersion, r.deleted, canonical(r.raw),
                    )
                    if (n > 0) { changed += 1; emit(StoreTable.MESSAGES, r.workspaceId) }
                }
                val request = r.clientRequestId
                val sender = r.senderNpub
                if (request != null && sender != null && retire.run(request.lowercase(), r.workspaceId, sender) > 0) emit(StoreTable.OUTBOX, r.workspaceId)
            }
        }
    }
    return changed
}

/** Stores (or, with null, deletes) one `app_state` value. */
suspend fun StoreWriter.setState(key: String, value: String?) = write {
    if (value != null) db.run("INSERT INTO app_state(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value)
    else db.run("DELETE FROM app_state WHERE key = ?", key)
    emit(StoreTable.APP_STATE)
}

// MARK: Reader

suspend fun StoreReader.connections(): List<ConnectionRecord> = read {
    query("SELECT id, origin, signer_npub, tower_npub, created_at FROM connections ORDER BY created_at") {
        ConnectionRecord(it.text(0)!!, it.text(1)!!, it.text(2)!!, it.text(3), it.text(4)!!)
    }
}

/** Every stored workspace of a connection, flagged ones included (`accessRemoved` set): for screens. */
suspend fun StoreReader.workspaces(connectionId: String): List<WorkspaceRecord> = read { workspaceRows(connectionId, includeRemoved = true) }

/** Workspaces sync may read: still listed by Tower. */
suspend fun StoreReader.syncableWorkspaces(connectionId: String): List<WorkspaceRecord> = read { workspaceRows(connectionId, includeRemoved = false) }

private fun SqlConnection.workspaceRows(connectionId: String, includeRemoved: Boolean): List<WorkspaceRecord> = query(
    """SELECT workspace_id, app_npub, label, slug, tower_base_url, role, owner_npub, raw, access_removed FROM workspaces
       WHERE connection_id = ? ${if (includeRemoved) "" else "AND access_removed = 0"} ORDER BY label COLLATE NOCASE, workspace_id""",
    connectionId,
) {
    WorkspaceRecord(
        id = it.text(0)!!, appNpub = it.text(1)!!, label = it.text(2)!!, slug = it.text(3), towerBaseURL = it.text(4), role = it.text(5),
        ownerNpub = it.text(6), raw = parseRaw(it.text(7)), accessRemoved = it.bool(8),
    )
}

/** Scopes of a workspace (flagged ones included, with `accessRemoved`). */
suspend fun StoreReader.scopes(workspaceId: String): List<ScopeRecord> = read {
    query("SELECT ${Rows.SCOPE_COLUMNS} FROM scopes WHERE workspace_id = ? ORDER BY name COLLATE NOCASE, id", workspaceId) { Rows.scope(it, workspaceId) }
}

/** Channels of a workspace (flagged ones included, with `accessRemoved`). Catch-up uses [syncableChannels]. */
suspend fun StoreReader.channels(workspaceId: String): List<ChannelRecord> = read { channelRows(workspaceId) }

internal fun SqlConnection.channelRows(workspaceId: String): List<ChannelRecord> = query(
    "SELECT ${Rows.CHANNEL_COLUMNS} FROM channels WHERE workspace_id = ? ORDER BY scope_id, position IS NULL, position, name COLLATE NOCASE, id",
    workspaceId,
) { Rows.channel(it, workspaceId) }

internal fun SqlConnection.channelRow(workspaceId: String, id: String): ChannelRecord? =
    first("SELECT ${Rows.CHANNEL_COLUMNS} FROM channels WHERE workspace_id = ? AND id = ?", workspaceId, id) { Rows.channel(it, workspaceId) }

suspend fun StoreReader.channel(workspaceId: String, id: String): ChannelRecord? = read { channelRow(workspaceId, id) }

/** Newest `limit` messages of a channel, returned oldest first for display. */
suspend fun StoreReader.latestMessages(workspaceId: String, channelId: String, limit: Int): List<MessageRecord> = read {
    query(
        "SELECT ${Rows.MESSAGE_COLUMNS} FROM messages WHERE workspace_id = ? AND channel_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
        workspaceId, channelId, limit,
    ) { Rows.message(it, workspaceId) }.asReversed()
}

suspend fun StoreReader.messageCount(workspaceId: String): Int = read {
    longValue("SELECT COUNT(*) FROM messages WHERE workspace_id = ?", workspaceId)?.toInt() ?: 0
}

suspend fun StoreReader.state(key: String): String? = read { textValue("SELECT value FROM app_state WHERE key = ?", key) }
