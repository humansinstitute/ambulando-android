package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord
import studio.otherstuff.ambulando.core.surfaces.BoardSceneRecord
import studio.otherstuff.ambulando.core.surfaces.CanvasRecord
import studio.otherstuff.ambulando.core.surfaces.CanvasScene
import studio.otherstuff.ambulando.core.surfaces.CanvasSnapshotRecord
import studio.otherstuff.ambulando.core.surfaces.CanvasSourceChoice
import studio.otherstuff.ambulando.core.surfaces.CanvasSourceChoices
import studio.otherstuff.ambulando.core.surfaces.ContextComponentRecord
import studio.otherstuff.ambulando.core.surfaces.ContextReferenceRecord
import studio.otherstuff.ambulando.core.surfaces.PersonalWappRecord
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.surfaces.WorkspaceAgentRecord
import studio.otherstuff.ambulando.core.transport.TowerError

// The Context Tree, canvases (definitions, snapshots, board scenes), agents and their activity, the Toolbox and
// personal pins (iOS SurfaceStore.swift). Every read is bounded in SQL and runs on the reader.

/** What a complete listing does with stored rows Tower no longer returns. */
internal enum class Missing { KEEP, FLAG, DELETE }

/**
 * Upserts rows of one simple table under the shared row-version guard and applies `missing` to rows of the same
 * partition that Tower no longer lists. Returns rows written plus rows flagged or removed. `connectionId` binds
 * actor-personal tables (pins, personal WApps) to the connection (origin + signer) as well.
 */
private fun WriteTx.replace(
    table: String, columns: List<String>, rows: List<List<Any?>>, ids: List<String>, workspaceId: String, connectionId: String? = null,
    partition: Pair<String, String>?, missing: Missing,
): Int {
    var changed = 0
    val flagged = table in Schema.flaggedTables
    val owner = if (connectionId != null) "connection_id, " else ""
    val ownerFilter = if (connectionId != null) " AND connection_id = ?" else ""
    val ownerValues: List<Any?> = listOfNotNull(connectionId)
    val updates = (columns.filter { it != "id" }.map { "$it=excluded.$it" } + if (flagged) listOf("access_removed=0") else emptyList()).joinToString(", ")
    db.prepare(
        """INSERT INTO $table($owner workspace_id, ${columns.joinToString(", ")}) VALUES(${marks(columns.size + 1 + ownerValues.size)})
           ON CONFLICT($owner workspace_id, id) DO UPDATE SET $updates
           ${newerGuard(table, flagged)}""",
    ).use { s -> for (row in rows) changed += s.run(*(ownerValues + workspaceId + row).toTypedArray()) }
    if (missing != Missing.KEEP) {
        val filter = partition?.let { " AND ${it.first} = ?" } ?: ""
        val onlyActive = if (missing == Missing.FLAG) " AND access_removed = 0" else ""
        val keep = ids.toHashSet()
        val gone = db.query(
            "SELECT id FROM $table WHERE workspace_id = ?$ownerFilter$filter$onlyActive",
            *(listOf<Any?>(workspaceId) + ownerValues + listOfNotNull(partition?.second)).toTypedArray(),
        ) { it.text(0)!! }.filter { it !in keep }
        val sql = if (missing == Missing.FLAG) "UPDATE $table SET access_removed = 1 WHERE workspace_id = ? AND id = ?$ownerFilter"
        else "DELETE FROM $table WHERE workspace_id = ? AND id = ?$ownerFilter"
        var n = 0
        db.prepare(sql).use { s -> for (id in gone) n += s.run(*(listOf<Any?>(workspaceId, id) + ownerValues).toTypedArray()) }
        if (n > 0 && missing == Missing.FLAG) emit(StoreTable.ACCESS, workspaceId)
        changed += n
    }
    return changed
}

/**
 * Board scenes are kept only while a stored canvas snapshot still shows the board to this viewer. (A flagged canvas
 * keeps its snapshot, so its boards stay readable; the scene is a re-downloadable cache, not a record.)
 */
private fun WriteTx.purgeUnlinkedBoards(workspaceId: String) {
    db.run("DELETE FROM board_links WHERE workspace_id = ? AND canvas_id NOT IN (SELECT canvas_id FROM canvas_snapshots WHERE workspace_id = ?)", workspaceId, workspaceId)
    db.run("DELETE FROM board_scenes WHERE workspace_id = ? AND file_id NOT IN (SELECT file_id FROM board_links WHERE workspace_id = ?)", workspaceId, workspaceId)
}

// MARK: Writer — Context Tree

/**
 * Upserts a scope's components. A complete listing flags components Tower no longer returns, with their references
 * (Android: non-destructive).
 */
suspend fun StoreWriter.upsertContextComponents(workspaceId: String, scopeId: String, records: List<ContextComponentRecord>, complete: Boolean): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId && it.scopeId == scopeId }
    var changed = replace(
        "context_components", listOf("scope_id", "id", "parent_id", "title", "sort_order", "row_version", "updated_at", "raw"),
        rows.map { listOf(it.scopeId, it.id, it.parentId, it.title, it.sortOrder, it.rowVersion, it.updatedAt, canonical(it.raw)) },
        rows.map { it.id }, workspaceId, partition = "scope_id" to scopeId, missing = if (complete) Missing.FLAG else Missing.KEEP,
    )
    if (complete) {
        changed += db.run(
            """UPDATE context_references SET access_removed = 1 WHERE workspace_id = ? AND scope_id = ? AND access_removed = 0
               AND component_id IN (SELECT id FROM context_components WHERE workspace_id = ? AND scope_id = ? AND access_removed = 1)""",
            workspaceId, scopeId, workspaceId, scopeId,
        )
    }
    if (changed > 0) emit(StoreTable.CONTEXT_TREE, workspaceId)
    changed
}

/** Replaces one component's references with a complete listing (references no longer returned are flagged). */
suspend fun StoreWriter.replaceContextReferences(
    workspaceId: String, scopeId: String, componentId: String, records: List<ContextReferenceRecord>,
): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId && it.scopeId == scopeId && it.componentId == componentId }
    val changed = replace(
        "context_references",
        listOf("scope_id", "component_id", "id", "target_type", "target_id", "status", "sort_order", "row_version", "updated_at", "raw"),
        rows.map {
            listOf(it.scopeId, it.componentId, it.id, it.targetTypeName, it.targetRecordId, it.resolution.wire, it.sortOrder, it.rowVersion,
                it.updatedAt, canonical(it.raw))
        },
        rows.map { it.id }, workspaceId, partition = "component_id" to componentId, missing = Missing.FLAG,
    )
    if (changed > 0) emit(StoreTable.CONTEXT_TREE, workspaceId)
    changed
}

/** A component Tower reports as gone or no longer visible. Android: flagged with its references, not deleted. */
suspend fun StoreWriter.removeContextComponent(workspaceId: String, id: String) = write {
    flag(workspaceId, AccessTarget.ContextComponent(id))
    emit(StoreTable.CONTEXT_TREE, workspaceId)
}

/** A whole scope's tree is unavailable to this viewer. Android: flagged, not deleted. */
suspend fun StoreWriter.removeContextTree(workspaceId: String, scopeId: String) = write {
    flag(workspaceId, AccessTarget.ContextTree(scopeId))
    emit(StoreTable.CONTEXT_TREE, workspaceId)
    emit(StoreTable.ACCESS, workspaceId)
}

// MARK: Writer — Canvas

private val CANVAS_COLUMNS = listOf("id", "title", "position", "row_version", "updated_at", "raw")
private fun canvasRow(c: CanvasRecord): List<Any?> = listOf(c.id, c.title, c.position, c.rowVersion, c.updatedAt, canonical(c.raw))

/**
 * Replaces the visible canvas list. Canvases no longer listed are flagged (Android: their snapshots and boards stay
 * readable under the flag).
 */
suspend fun StoreWriter.replaceCanvases(workspaceId: String, records: List<CanvasRecord>): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId }
    val changed = replace("canvases", CANVAS_COLUMNS, rows.map(::canvasRow), rows.map { it.id }, workspaceId, partition = null, missing = Missing.FLAG)
    if (changed > 0) emit(StoreTable.CANVASES, workspaceId)
    changed
}

/**
 * Stores a fetched snapshot with the canvas definition it came with. The boards it shows become its links; a board
 * that no stored snapshot shows any more loses its cached scene.
 */
suspend fun StoreWriter.saveCanvasSnapshot(snapshot: CanvasSnapshotRecord, canvas: CanvasRecord) {
    if (snapshot.workspaceId != canvas.workspaceId || snapshot.canvasId != canvas.id) throw TowerError.Malformed("canvas snapshot")
    val boards = CanvasScene.parse(snapshot.bytes).boards.map { it.fileId }.toSet()
    write {
        replace("canvases", CANVAS_COLUMNS, listOf(canvasRow(canvas)), listOf(canvas.id), canvas.workspaceId, partition = null, missing = Missing.KEEP)
        val stored = db.run(
            """INSERT INTO canvas_snapshots(workspace_id, canvas_id, canvas_row_version, bytes, fetched_at) VALUES(?,?,?,?,?)
               ON CONFLICT(workspace_id, canvas_id) DO UPDATE SET canvas_row_version=excluded.canvas_row_version, bytes=excluded.bytes, fetched_at=excluded.fetched_at
               WHERE excluded.bytes IS NOT canvas_snapshots.bytes OR excluded.canvas_row_version IS NOT canvas_snapshots.canvas_row_version""",
            snapshot.workspaceId, snapshot.canvasId, snapshot.canvasRowVersion, snapshot.bytes, snapshot.fetchedAt,
        ) > 0
        db.run("DELETE FROM board_links WHERE workspace_id = ? AND canvas_id = ?", snapshot.workspaceId, snapshot.canvasId)
        db.prepare("INSERT INTO board_links(workspace_id, canvas_id, file_id) VALUES(?,?,?)").use { s ->
            for (board in boards) s.run(snapshot.workspaceId, snapshot.canvasId, board)
        }
        purgeUnlinkedBoards(snapshot.workspaceId)
        if (stored) { emit(StoreTable.CANVASES, snapshot.workspaceId); emit(StoreTable.BOARDS, snapshot.workspaceId) }
    }
}

/** A canvas that is no longer visible (404 `canvas_unavailable`). Android: flagged; definition and snapshot stay readable. */
suspend fun StoreWriter.removeCanvas(workspaceId: String, id: String) = write {
    flag(workspaceId, AccessTarget.Canvas(id))
    emit(StoreTable.CANVASES, workspaceId)
    emit(StoreTable.BOARDS, workspaceId)
}

/**
 * Stores verified board bytes; only the newest fetched version per board is kept, and only while a stored canvas still
 * shows the board (a fetch that finishes after the board left every snapshot stores nothing).
 */
suspend fun StoreWriter.saveBoardScene(scene: BoardSceneRecord) = write {
    val n = db.run(
        """INSERT INTO board_scenes(workspace_id, file_id, version_id, sha256, bytes, fetched_at)
           SELECT ?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM board_links WHERE workspace_id = ? AND file_id = ?)
           ON CONFLICT(workspace_id, file_id) DO UPDATE SET version_id=excluded.version_id, sha256=excluded.sha256, bytes=excluded.bytes, fetched_at=excluded.fetched_at""",
        scene.workspaceId, scene.fileId, scene.versionId, scene.sha256, scene.bytes, scene.fetchedAt, scene.workspaceId, scene.fileId,
    )
    if (n > 0) emit(StoreTable.BOARDS, scene.workspaceId)
}

// MARK: Writer — agents

/** Replaces the installed agents (a directory, not records: agents no longer listed are removed). */
suspend fun StoreWriter.replaceWorkspaceAgents(workspaceId: String, records: List<WorkspaceAgentRecord>): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId && !it.archived }
    val changed = replace(
        "workspace_agents", listOf("id", "agent_npub", "display_name", "sort_order", "visible", "row_version", "updated_at", "raw"),
        rows.map { listOf(it.id, it.agentNpub, it.displayName, it.sortOrder, it.isVisible, it.rowVersion, it.updatedAt, canonical(it.raw)) },
        rows.map { it.id }, workspaceId, partition = null, missing = Missing.DELETE,
    )
    if (changed > 0) emit(StoreTable.AGENTS, workspaceId)
    changed
}

/**
 * Replaces a channel's stored activity with Tower's newest page for it (a rolling window, so older rows leaving the page
 * are removed). `sequence` stands in for a row version. Nothing is written for a flagged channel.
 */
suspend fun StoreWriter.replaceAgentActivities(workspaceId: String, channelId: String, records: List<AgentActivityRecord>): Int = write {
    if (channelId in db.removedChannelIds(workspaceId)) return@write 0
    val rows = records.filter { it.workspaceId == workspaceId && it.channelId == channelId }
    val changed = replace(
        "agent_activities", listOf("id", "activity_id", "scope_id", "channel_id", "thread_id", "agent_npub", "state", "row_version", "updated_at", "raw"),
        rows.map { listOf(it.id, it.activityId, it.scopeId, it.channelId, it.threadId, it.agentNpub, it.state, it.sequence, it.updatedAt, canonical(it.raw)) },
        rows.map { it.id }, workspaceId, partition = "channel_id" to channelId, missing = Missing.DELETE,
    )
    if (changed > 0) emit(StoreTable.AGENTS, workspaceId)
    changed
}

// MARK: Writer — Toolbox and pins

/** Replaces the viewer's personal WApps for one connection (actor-private; rows no longer listed are removed). */
suspend fun StoreWriter.replacePersonalWapps(connectionId: String, workspaceId: String, records: List<PersonalWappRecord>, complete: Boolean = true): Int = write {
    val rows = records.filter { it.workspaceId == workspaceId }
    val changed = replace(
        "personal_wapps", listOf("id", "title", "sort_order", "status", "row_version", "updated_at", "raw"),
        rows.map { listOf(it.id, it.title, it.sortOrder, it.status, it.rowVersion, it.updatedAt, canonical(it.raw)) },
        rows.map { it.id }, workspaceId, connectionId, partition = null, missing = if (complete) Missing.DELETE else Missing.KEEP,
    )
    if (changed > 0) emit(StoreTable.TOOLBOX, workspaceId)
    changed
}

/**
 * Upserts pin rows (pinned or tombstoned) for one connection. With `complete`, a pin Tower no longer returns is removed.
 * Pins are private to the actor: they are stored under the connection (origin + signer) that read them.
 */
suspend fun StoreWriter.upsertPins(connectionId: String, workspaceId: String, records: List<PinRecord>, complete: Boolean): Int = write {
    val changed = writePins(connectionId, workspaceId, records.filter { it.workspaceId == workspaceId }, complete)
    if (changed > 0) emit(StoreTable.PINS, workspaceId)
    changed
}

internal fun WriteTx.writePins(connectionId: String, workspaceId: String, rows: List<PinRecord>, complete: Boolean): Int = replace(
    "pins", listOf("id", "resource_type", "resource_id", "channel_id", "scope_id", "pinned", "row_version", "updated_at", "raw"),
    rows.map { listOf(it.id, it.resourceType.wire, it.resourceId, it.channelId, it.scopeId, it.pinned, it.rowVersion, it.updatedAt, canonical(it.raw)) },
    rows.map { it.id }, workspaceId, connectionId, partition = null, missing = if (complete) Missing.DELETE else Missing.KEEP,
)

// MARK: Reader

/** A pinned record with what the store knows about it, for the sidebar. */
data class PinnedItem(
    val pin: PinRecord,
    /** The record's title, or null when it is not stored on this device (no access, or not synced yet). */
    val title: String?,
    val channelId: String?,
    val scopeId: String?,
    /** The pinned record is stored but no longer visible to this identity. */
    val accessRemoved: Boolean = false,
) {
    val id: String get() = pin.id
}

/** A Context Tree node with its children, in Tower's sort order. */
data class ContextNode(
    val component: ContextComponentRecord,
    val children: List<ContextNode>,
    /** The component is no longer visible to this identity (kept readable, labelled). */
    val accessRemoved: Boolean = false,
) {
    val id: String get() = component.id
}

const val CONTEXT_COMPONENT_LIMIT = 2000

/**
 * A scope's components as a forest (roots in sort order, children nested). Orphans whose parent is not stored are shown
 * as roots rather than hidden. Flagged components are included with `accessRemoved`.
 */
suspend fun StoreReader.contextTree(workspaceId: String, scopeId: String): List<ContextNode> = read {
    val all = query(
        "SELECT raw, access_removed FROM context_components WHERE workspace_id = ? AND scope_id = ? ORDER BY sort_order, id LIMIT ?",
        workspaceId, scopeId, CONTEXT_COMPONENT_LIMIT,
    ) { r -> runCatching { ContextComponentRecord.parse(parseRaw(r.text(0))) }.getOrNull()?.let { it to r.bool(1) } }.filterNotNull()
    val removed = all.filter { it.second }.mapTo(HashSet()) { it.first.id }
    val components = all.map { it.first }
    val ids = components.mapTo(HashSet()) { it.id }
    val byParent = LinkedHashMap<String, MutableList<ContextComponentRecord>>()
    for (c in components) byParent.getOrPut(c.parentId?.takeIf { it in ids } ?: "") { ArrayList() }.add(c)
    val visited = HashSet<String>()
    fun build(c: ContextComponentRecord): ContextNode {
        visited.add(c.id)
        val kids = (byParent[c.id] ?: emptyList<ContextComponentRecord>()).filter { it.id !in visited }.map { build(it) }
        return ContextNode(c, kids, c.id in removed)
    }
    val roots = (byParent[""] ?: emptyList<ContextComponentRecord>()).map { build(it) }.toMutableList()
    // A cycle (never produced by Tower, which refuses circular parents) still surfaces every node once.
    for (c in components) if (c.id !in visited) roots.add(build(c))
    roots
}

suspend fun StoreReader.contextComponent(workspaceId: String, id: String): ContextComponentRecord? = read {
    first("SELECT raw FROM context_components WHERE workspace_id = ? AND id = ?", workspaceId, id) {
        runCatching { ContextComponentRecord.parse(parseRaw(it.text(0))) }.getOrNull()
    }
}

/** A component's references (flagged ones included; see [accessRemovedIds] with [FlaggedTable.CONTEXT_REFERENCES]). */
suspend fun StoreReader.contextReferences(workspaceId: String, componentId: String): List<ContextReferenceRecord> = read {
    query("SELECT raw FROM context_references WHERE workspace_id = ? AND component_id = ? ORDER BY sort_order, id LIMIT 500", workspaceId, componentId) {
        runCatching { ContextReferenceRecord.parse(parseRaw(it.text(0))) }.getOrNull()
    }.filterNotNull()
}

/** Stored canvases (flagged ones included; see [accessRemovedIds] with [FlaggedTable.CANVASES]). */
suspend fun StoreReader.canvases(workspaceId: String): List<CanvasRecord> = read { canvasRows(workspaceId) }

private fun SqlConnection.canvasRows(workspaceId: String): List<CanvasRecord> =
    query("SELECT raw FROM canvases WHERE workspace_id = ? ORDER BY position, id LIMIT 500", workspaceId) {
        runCatching { CanvasRecord.parse(parseRaw(it.text(0)), workspaceId) }.getOrNull()
    }.filterNotNull()

suspend fun StoreReader.canvas(workspaceId: String, id: String): CanvasRecord? = read {
    first("SELECT raw FROM canvases WHERE workspace_id = ? AND id = ?", workspaceId, id) {
        runCatching { CanvasRecord.parse(parseRaw(it.text(0)), workspaceId) }.getOrNull()
    }
}

suspend fun StoreReader.canvasSnapshot(workspaceId: String, canvasId: String): CanvasSnapshotRecord? = read {
    first("SELECT canvas_row_version, bytes, fetched_at FROM canvas_snapshots WHERE workspace_id = ? AND canvas_id = ?", workspaceId, canvasId) {
        CanvasSnapshotRecord(workspaceId, canvasId, it.long(0), it.blob(1), it.text(2) ?: "")
    }
}

/** Canvas ids with a stored snapshot that sync refreshes when canvas events arrive (flagged canvases are left out). */
suspend fun StoreReader.snapshotCanvasIds(workspaceId: String): List<String> = read {
    query(
        """SELECT s.canvas_id FROM canvas_snapshots s WHERE s.workspace_id = ?
             AND NOT EXISTS (SELECT 1 FROM canvases c WHERE c.workspace_id = s.workspace_id AND c.id = s.canvas_id AND c.access_removed = 1)
           ORDER BY s.fetched_at DESC LIMIT 20""",
        workspaceId,
    ) { it.text(0)!! }
}

suspend fun StoreReader.boardScene(workspaceId: String, fileId: String): BoardSceneRecord? = read {
    first("SELECT version_id, sha256, bytes, fetched_at FROM board_scenes WHERE workspace_id = ? AND file_id = ?", workspaceId, fileId) {
        BoardSceneRecord(workspaceId, fileId, it.text(0) ?: "", it.text(1) ?: "", it.blob(2), it.text(3) ?: "")
    }
}

internal fun SqlConnection.agentRows(workspaceId: String): List<WorkspaceAgentRecord> = query(
    "SELECT raw FROM workspace_agents WHERE workspace_id = ? AND visible = 1 ORDER BY sort_order, display_name COLLATE NOCASE, id LIMIT 200", workspaceId,
) { runCatching { WorkspaceAgentRecord.parse(parseRaw(it.text(0))) }.getOrNull() }.filterNotNull()

suspend fun StoreReader.workspaceAgents(workspaceId: String): List<WorkspaceAgentRecord> = read { agentRows(workspaceId) }

/** Newest activity across the given channels (null = every channel), bounded. */
suspend fun StoreReader.agentActivities(workspaceId: String, channelIds: List<String>?, limit: Int = 100): List<AgentActivityRecord> = read {
    if (channelIds != null && channelIds.isEmpty()) return@read emptyList()
    val filter = channelIds?.let { "AND channel_id IN (${marks(it.size)})" } ?: ""
    query(
        "SELECT raw FROM agent_activities WHERE workspace_id = ? $filter ORDER BY updated_at DESC, id LIMIT ?",
        workspaceId, *(channelIds ?: emptyList()).toTypedArray(), limit,
    ) { runCatching { AgentActivityRecord.parse(parseRaw(it.text(0))) }.getOrNull() }.filterNotNull()
}

suspend fun StoreReader.personalWapps(connectionId: String, workspaceId: String): List<PersonalWappRecord> = read {
    query(
        """SELECT raw FROM personal_wapps WHERE connection_id = ? AND workspace_id = ? AND status = 'active'
           ORDER BY sort_order, title COLLATE NOCASE, id LIMIT 200""",
        connectionId, workspaceId,
    ) { runCatching { PersonalWappRecord.parse(parseRaw(it.text(0))) }.getOrNull() }.filterNotNull()
}

suspend fun StoreReader.personalWapp(connectionId: String, workspaceId: String, id: String): PersonalWappRecord? = read {
    first("SELECT raw FROM personal_wapps WHERE connection_id = ? AND workspace_id = ? AND id = ?", connectionId, workspaceId, id) {
        runCatching { PersonalWappRecord.parse(parseRaw(it.text(0))) }.getOrNull()
    }
}

/** The stored pin row for a resource type/id (pinned or tombstoned). */
suspend fun StoreReader.pin(connectionId: String, workspaceId: String, type: PinRecord.ResourceType, resourceId: String): PinRecord? = read {
    first(
        "SELECT raw FROM pins WHERE connection_id = ? AND workspace_id = ? AND resource_type = ? AND resource_id = ? LIMIT 1",
        connectionId, workspaceId, type.wire, resourceId,
    ) { runCatching { PinRecord.parse(parseRaw(it.text(0))) }.getOrNull() }
}

/** Pinned items in Tower's order, with titles (and access flags) from the local store where the record is stored. */
suspend fun StoreReader.pinnedItems(connectionId: String, workspaceId: String): List<PinnedItem> = read {
    val pins = query("SELECT raw FROM pins WHERE connection_id = ? AND workspace_id = ? AND pinned = 1 LIMIT 500", connectionId, workspaceId) {
        runCatching { PinRecord.parse(parseRaw(it.text(0))) }.getOrNull()
    }.filterNotNull().sortedWith(PinRecord.displayOrder)
    pins.map { pin ->
        when (pin.resourceType) {
            PinRecord.ResourceType.TASK -> taskRow(workspaceId, pin.resourceId).let { t ->
                PinnedItem(pin, t?.title, t?.channelId ?: pin.channelId, t?.scopeId ?: pin.scopeId, t?.accessRemoved == true)
            }
            PinRecord.ResourceType.DOCUMENT -> docRow(workspaceId, pin.resourceId).let { d ->
                PinnedItem(pin, d?.title, d?.channelId ?: pin.channelId, d?.scopeId ?: pin.scopeId, d?.accessRemoved == true)
            }
            PinRecord.ResourceType.CHANNEL -> channelRow(workspaceId, pin.resourceId).let { c ->
                PinnedItem(pin, c?.name, c?.id, c?.scopeId ?: pin.scopeId, c?.accessRemoved == true)
            }
            PinRecord.ResourceType.THREAD -> {
                val t = threadRow(workspaceId, pin.resourceId)
                var title = t?.title
                if (title.isNullOrEmpty()) t?.sourceMessageId?.let { messageRow(workspaceId, it) }?.let { title = it.body.take(80) }
                PinnedItem(pin, if (t == null) null else title?.takeIf { it.isNotEmpty() } ?: "Conversation", t?.channelId ?: pin.channelId, pin.scopeId,
                    t?.accessRemoved == true)
            }
        }
    }
}

/**
 * Other canvases, documents, channel task lists, conversations, HTML artefacts and Excalidraw board files stored for the
 * workspace, sorted by title. Archived, deleted and flagged records are left out (they cannot be added to a canvas).
 */
suspend fun StoreReader.canvasSourceChoices(workspaceId: String, excludingCanvas: String): List<CanvasSourceChoice> = read {
    val canvases = query("SELECT id, title FROM canvases WHERE workspace_id = ? AND access_removed = 0 ORDER BY position, id LIMIT 500", workspaceId) {
        CanvasSourceChoices.Named(it.text(0)!!, it.text(1) ?: "")
    }
    val docs = query("SELECT id, title FROM docs WHERE workspace_id = ? AND archived = 0 AND access_removed = 0 ORDER BY updated_at DESC, id LIMIT 500", workspaceId) {
        CanvasSourceChoices.Named(it.text(0)!!, it.text(1) ?: "")
    }
    val channels = query("SELECT id, name FROM channels WHERE workspace_id = ? AND access_removed = 0 ORDER BY scope_id, position IS NULL, position, name COLLATE NOCASE, id", workspaceId) {
        CanvasSourceChoices.Named(it.text(0)!!, it.text(1) ?: "")
    }
    val threads = query(
        """SELECT id, COALESCE(NULLIF(title, ''), NULLIF(latest, ''), 'Conversation') FROM threads
           WHERE workspace_id = ? AND archived = 0 AND deleted = 0 AND access_removed = 0 ORDER BY updated_at DESC, id LIMIT 300""",
        workspaceId,
    ) { CanvasSourceChoices.Named(it.text(0)!!, it.text(1)!!) }
    val files = query(
        "SELECT id, display_name, content_type FROM files WHERE workspace_id = ? AND archived = 0 AND access_removed = 0 ORDER BY display_name COLLATE NOCASE, id LIMIT 500",
        workspaceId,
    ) { CanvasSourceChoices.FileRow(it.text(0)!!, it.text(1)!!, it.text(2)) }
    CanvasSourceChoices.build(canvases, docs, channels, threads, files, excludingCanvas)
}
