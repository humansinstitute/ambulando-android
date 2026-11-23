package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.store.conversationAgentActivities
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.store.isAccessRemoved
import studio.otherstuff.ambulando.core.store.FlaggedTable

import studio.otherstuff.ambulando.core.chat.knowsChannel
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.store.AccessTarget
import studio.otherstuff.ambulando.core.store.ChangeKey
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.boardScene
import studio.otherstuff.ambulando.core.store.isChannelAccessRemoved
import studio.otherstuff.ambulando.core.store.markAccessRemoved
import studio.otherstuff.ambulando.core.store.removeCanvas
import studio.otherstuff.ambulando.core.store.removeContextComponent
import studio.otherstuff.ambulando.core.store.removeContextTree
import studio.otherstuff.ambulando.core.store.replaceAgentActivities
import studio.otherstuff.ambulando.core.store.replaceCanvases
import studio.otherstuff.ambulando.core.store.replaceContextReferences
import studio.otherstuff.ambulando.core.store.replacePersonalWapps
import studio.otherstuff.ambulando.core.store.replaceWorkspaceAgents
import studio.otherstuff.ambulando.core.store.saveBoardScene
import studio.otherstuff.ambulando.core.store.saveCanvasSnapshot
import studio.otherstuff.ambulando.core.store.saveListSync
import studio.otherstuff.ambulando.core.store.upsertContextComponents
import studio.otherstuff.ambulando.core.store.upsertPins
import studio.otherstuff.ambulando.core.store.upsertThreads
import studio.otherstuff.ambulando.core.store.scopes
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.sync.checkCancelled
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

// Read paths for the Context Tree, Canvas, agents, Toolbox and personal pins. Like every other read, each is a keyed
// `SyncEngine` job (bounded concurrency, coalescing, cancellation, backoff) whose results land in SQLite; screens only
// observe the store. Parsing and verification happen inside the job. Nothing here writes to Tower: opening a canvas or a
// board never changes it.
//
// Android: a 403/404 flags the stored tree, component, canvas or channel activity `access_removed` (kept readable,
// labelled, no longer read) instead of deleting it, and a scope or channel flagged as no longer visible is not read.

/** Schedules every board of a snapshot: the first batch at [priority], the rest in the background. */
suspend fun WorkspaceSync.scheduleBoards(connection: ConnectionRecord, workspace: WorkspaceRecord, scene: CanvasScene, priority: SyncPriority) {
    scene.boards.forEachIndexed { index, board ->
        refreshBoard(connection, workspace, board, if (index < WorkspaceSync.BOARD_FETCH_LIMIT) priority else SyncPriority.BACKGROUND)
    }
}

/** Whether a stored scope is flagged as no longer visible (its surfaces are not read). */
private suspend fun WorkspaceSync.scopeRemoved(workspaceId: String, scopeId: String): Boolean =
    store.reader.scopes(workspaceId).any { it.id == scopeId && it.accessRemoved }

// MARK: Context Tree

/** A scope's components. A 404 (`context_not_found`: no access or archived) flags the stored tree. */
suspend fun WorkspaceSync.refreshContextTree(
    connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.contextTreeKey(workspace.id, scopeId), priority, force) {
        identity.requireReader(connection.signerNpub)
        if (scopeRemoved(workspace.id, scopeId)) return@enqueueRead
        val api = api(connection)
        try {
            val components = api.contextComponents(workspace.id, workspace.appNpub, scopeId)
            checkCancelled()
            store.writer.upsertContextComponents(workspace.id, scopeId, components, complete = true)
            store.writer.saveListSync(workspace.id, WorkspaceSync.contextTreeKey(workspace.id, scopeId), complete = true)
        } catch (e: TowerError.Http) {
            if (!WorkspaceSync.lost(e)) throw e
            store.writer.removeContextTree(workspace.id, scopeId)
        }
    }
}

/** One component's references, with Tower's per-viewer resolution (user priority: the node was opened). */
suspend fun WorkspaceSync.refreshContextReferences(
    connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String, componentId: String,
    priority: SyncPriority = SyncPriority.USER, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.contextReferencesKey(workspace.id, componentId), priority, force) {
        identity.requireReader(connection.signerNpub)
        if (workspace.accessRemoved || scopeRemoved(workspace.id, scopeId) || store.reader.isAccessRemoved(workspace.id, FlaggedTable.CONTEXT_COMPONENTS, componentId)) return@enqueueRead
        val api = api(connection)
        try {
            val references = api.contextReferences(workspace.id, workspace.appNpub, scopeId, componentId)
            checkCancelled()
            store.writer.replaceContextReferences(workspace.id, scopeId, componentId, references)
            store.writer.saveListSync(workspace.id, WorkspaceSync.contextReferencesKey(workspace.id, componentId), complete = true)
        } catch (e: TowerError.Http) {
            if (!WorkspaceSync.lost(e)) throw e
            store.writer.removeContextComponent(workspace.id, componentId)
        }
    }
}

// MARK: Canvas

suspend fun WorkspaceSync.refreshCanvases(connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false) {
    engine.enqueueRead(WorkspaceSync.canvasesKey(workspace.id), priority, force) {
        identity.requireReader(connection.signerNpub)
        val canvases = api(connection).canvases(workspace.id, workspace.appNpub)
        checkCancelled()
        store.writer.replaceCanvases(workspace.id, canvases)
    }
}

/** Opening a canvas: its snapshot, then the scenes of the boards on it that are not stored at their current version. */
suspend fun WorkspaceSync.refreshCanvas(
    connection: ConnectionRecord, workspace: WorkspaceRecord, canvasId: String, priority: SyncPriority = SyncPriority.USER, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.canvasKey(workspace.id, canvasId), priority, force) {
        if (workspace.accessRemoved || store.reader.isAccessRemoved(workspace.id, FlaggedTable.CANVASES, canvasId)) return@enqueueRead
        identity.requireReader(connection.signerNpub)
        val fetched = try {
            api(connection).canvasSnapshot(workspace.id, workspace.appNpub, canvasId)
        } catch (e: TowerError.Http) {
            if (!WorkspaceSync.lost(e)) throw e
            store.writer.removeCanvas(workspace.id, canvasId)
            return@enqueueRead
        }
        checkCancelled()
        val (canvas, snapshot) = fetched
        store.writer.saveCanvasSnapshot(snapshot, canvas)
        scheduleBoards(connection, workspace, CanvasScene.parseCancellable(snapshot.bytes), priority)
    }
}

/** One board version's scene bytes (skipped when that version is already stored). */
suspend fun WorkspaceSync.refreshBoard(connection: ConnectionRecord, workspace: WorkspaceRecord, board: BoardReference, priority: SyncPriority = SyncPriority.USER) {
    engine.enqueueRead(WorkspaceSync.boardKey(workspace.id, board.fileId), priority) {
        val stored = store.reader.boardScene(workspace.id, board.fileId)
        if (stored != null && stored.versionId == board.versionId && stored.sha256 == board.sha256) return@enqueueRead
        identity.requireReader(connection.signerNpub)
        val scene = api(connection).boardScene(workspace.id, workspace.appNpub, board.fileId, board.versionId, board.sha256, board.sizeBytes.toLong())
        checkCancelled()
        store.writer.saveBoardScene(scene)
    }
}

// MARK: Agents

/** Installed agents, then Tower's materialised activity for the given channels (bounded). */
suspend fun WorkspaceSync.refreshAgents(
    connection: ConnectionRecord, workspace: WorkspaceRecord, channelIds: List<String>, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.agentsKey(workspace.id), priority, force) {
        identity.requireReader(connection.signerNpub)
        val agents = api(connection).workspaceAgents(workspace.id, workspace.appNpub)
        checkCancelled()
        store.writer.replaceWorkspaceAgents(workspace.id, agents)
    }
    channelIds.forEachIndexed { index, channel ->
        refreshAgentActivity(connection, workspace, channel, if (index < WorkspaceSync.AGENT_ACTIVITY_CHANNEL_LIMIT) priority else SyncPriority.BACKGROUND, force)
    }
}

suspend fun WorkspaceSync.refreshAgentActivity(
    connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
    threadId: String? = null, activityId: String? = null, beforeSequence: Long? = null,
) {
    val key = WorkspaceSync.agentActivityKey(workspace.id, channelId) +
        if (threadId == null && activityId == null && beforeSequence == null) "" else ":" + listOf(threadId, activityId, beforeSequence).joinToString(":")
    engine.enqueueRead(key, priority, force) {
        identity.requireReader(connection.signerNpub)
        if (store.reader.isChannelAccessRemoved(workspace.id, channelId)) return@enqueueRead
        val api = api(connection)
        try {
            val retained = store.reader.conversationAgentActivities(workspace.id, channelId, threadId).associateBy { it.id }
            suspend fun recover(previous: AgentActivityRecord) {
                    var after = previous.raw.string("android_recovered_cursor") ?: previous.raw.string("commentary_cursor") ?: "0"
                    var recoveryPages = 0
                    do {
                        val recovery = api.agentActivityPage(workspace.id, workspace.appNpub, channelId, previous.threadId,
                            activityId = previous.activityId, afterCommentaryCursor = after)
                        checkCancelled()
                        val sameTurn = recovery.rows.filter { it.turnId == previous.turnId }
                        store.writer.replaceAgentActivities(workspace.id, channelId, sameTurn)
                        val next = sameTurn.singleOrNull()?.raw?.string("commentary_next_cursor")
                        if (next == null) break
                        if ((next.toLongOrNull() ?: -1) <= (after.toLongOrNull() ?: -1)) throw TowerError.Malformed("agent commentary cursor repeated")
                        after = next
                        recoveryPages++
                    } while (recoveryPages < 20)
                    if (recoveryPages >= 20) throw TowerError.Malformed("agent commentary page limit")
            }
            var cursor: String? = null
            val seen = mutableSetOf<String>()
            var pages = 0
            do {
                val page = api.agentActivityPage(workspace.id, workspace.appNpub, channelId, threadId, cursor, activityId, beforeSequence)
                checkCancelled()
                if (beforeSequence == null) for (latest in page.rows) {
                    val previous = retained[latest.id] ?: continue
                    val known = previous.raw.string("android_recovered_cursor") ?: previous.raw.string("commentary_cursor") ?: "0"
                    if (latest.turnId == previous.turnId && (latest.raw.string("commentary_cursor")?.toLongOrNull() ?: 0) > (known.toLongOrNull() ?: 0)) recover(previous)
                }
                store.writer.replaceAgentActivities(workspace.id, channelId, page.rows)
                cursor = page.nextCursor
                if (cursor != null && !seen.add(cursor)) throw TowerError.Malformed("agent activity cursor repeated")
                pages++
            } while (cursor != null && pages < 20)
            if (cursor != null) throw TowerError.Malformed("agent activity page limit")
            store.writer.saveListSync(workspace.id, WorkspaceSync.agentActivityKey(workspace.id, channelId), complete = true)
        } catch (e: TowerError.Http) {
            if (!WorkspaceSync.lost(e)) throw e
            // No channel.read any more: that channel's activity is flagged (kept readable, no longer refreshed).
            store.writer.markAccessRemoved(workspace.id, AccessTarget.AgentActivity(channelId))
        }
    }
}

// MARK: Toolbox

suspend fun WorkspaceSync.refreshToolbox(connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false) {
    engine.enqueueRead(WorkspaceSync.toolboxKey(workspace.id), priority, force) {
        identity.requireReader(connection.signerNpub)
        val page = api(connection).personalWapps(workspace.id, workspace.appNpub)
        checkCancelled()
        store.writer.replacePersonalWapps(connection.id, workspace.id, page.items, complete = page.complete)
        store.writer.saveListSync(workspace.id, WorkspaceSync.toolboxKey(workspace.id), complete = page.complete)
        store.writer.touch(setOf(ChangeKey(StoreTable.TOOLBOX, workspace.id)))
    }
}

// MARK: HTML naplets

/**
 * The HTML of an HTML naplet, read when it is opened as web content. Returns (title, html). It is not stored on the
 * phone: publisher HTML is shown live, and Tower re-authorizes the File on every read. Runs as a user-priority engine
 * job (the engine is the only network owner).
 */
suspend fun WorkspaceSync.napletHTML(connection: ConnectionRecord, workspace: WorkspaceRecord, canvasId: String, napletId: String): Pair<String, String> =
    engine.perform("naplet-html:${workspace.id}:$napletId", SyncPriority.USER) {
        identity.requireReader(connection.signerNpub)
        api(connection).napletHTML(workspace.id, workspace.appNpub, canvasId, napletId)
    }

// MARK: Conversations known by id

/**
 * A thread referenced by a pin or a canvas naplet that is not stored yet: its record (for the channel). A 403/404, or a
 * channel this phone does not know, leaves nothing stored and is reported on the job key.
 */
suspend fun WorkspaceSync.ensureThread(connection: ConnectionRecord, workspace: WorkspaceRecord, threadId: String, priority: SyncPriority = SyncPriority.USER) {
    engine.enqueueRead(WorkspaceSync.threadRecordKey(workspace.id, threadId), priority) {
        identity.requireReader(connection.signerNpub)
        val thread = api(connection).thread(workspace.id, workspace.appNpub, threadId)
        if (thread.workspaceId != workspace.id || thread.id != threadId.lowercase()) throw TowerError.Malformed("thread")
        // Only a channel this viewer can see locally is used (the channel list is the access authority on the phone).
        if (!knowsChannel(workspace.id, thread.channelId) || store.reader.isChannelAccessRemoved(workspace.id, thread.channelId)) {
            throw TowerError.Http(404, "channel_not_found", null)
        }
        checkCancelled()
        store.writer.upsertThreads(workspace.id, listOf(thread))
    }
}

// MARK: Personal pins

/**
 * The complete pin set from the record-sync feed. The store mirrors Tower; a pin or unpin still in the write queue is
 * overlaid by the screens, so a refresh never visually flips a pin the user just changed.
 */
suspend fun WorkspaceSync.refreshPins(connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false) {
    engine.enqueueRead(WorkspaceSync.pinsKey(workspace.id), priority, force) {
        identity.requireReader(connection.signerNpub)
        val result = api(connection).personalPins(workspace.id, workspace.appNpub)
        checkCancelled()
        store.writer.upsertPins(connection.id, workspace.id, result.pins, complete = true)
    }
}

