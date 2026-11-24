package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.string

/** Shared by scheduling and warning matching, so an exact thread/account cannot hide another scope's error. */
fun agentActivitySyncPrefix(connectionId: String, workspaceId: String, channelId: String, threadId: String?): String =
    WorkspaceSync.agentActivityKey(workspaceId, channelId) + ":" +
        listOf(connectionId, threadId.orEmpty()).joinToString("") { "${it.length}:$it" }

/** One head request, then one page per background job. New heads never replace a pending history checkpoint. */
suspend fun WorkspaceSync.refreshAgentActivity(
    connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
    threadId: String? = null, activityId: String? = null, beforeSequence: Long? = null,
) {
    val base = WorkspaceSync.agentActivityKey(workspace.id, channelId)
    // Length-prefixed fields prevent collisions, including opaque activity ids that contain separators.
    val query = listOf(activityId.orEmpty(), beforeSequence?.toString().orEmpty())
        .joinToString("") { "${it.length}:$it" }
    val key = agentActivitySyncPrefix(connection.id, workspace.id, channelId, threadId) + query
    val checkpoint = "activity-traversal:$key"
    val syncKey = if (threadId == null && activityId == null && beforeSequence == null) base else key

    suspend fun allowed() {
        checkCancelled()
        identity.requireReader(connection.signerNpub)
        if (workspace.accessRemoved || store.reader.isChannelAccessRemoved(workspace.id, channelId))
            throw kotlinx.coroutines.CancellationException("Channel access removed")
    }
    suspend fun readGuard(work: suspend () -> Unit) {
        allowed()
        try { work() } catch (e: TowerError.Http) {
            if (!WorkspaceSync.lost(e)) throw e
            store.writer.markAccessRemoved(workspace.id, AccessTarget.AgentActivity(channelId))
        }
    }
    suspend fun recover(previous: AgentActivityRecord, targetCursor: Long) {
        val recoveryKey = "$key:commentary:${previous.id}:${previous.turnId}"
        suspend fun enqueueRecovery() {
            engine.enqueueRead(recoveryKey, SyncPriority.BACKGROUND, force) {
                readGuard {
                    val current = store.reader.agentActivitiesById(workspace.id, channelId, listOf(previous.id))
                        .firstOrNull { it.id == previous.id && it.turnId == previous.turnId } ?: return@readGuard
                    val after = current.raw.string("android_recovered_cursor") ?: previous.raw.string("commentary_cursor") ?: "0"
                    val target = maxOf(targetCursor, current.raw.string("commentary_cursor")?.toLongOrNull() ?: 0)
                    if ((after.toLongOrNull() ?: 0) >= target) return@readGuard
                    val page = api(connection).agentActivityPage(workspace.id, workspace.appNpub, channelId, previous.threadId,
                        activityId = previous.activityId, afterCommentaryCursor = after)
                    checkCancelled()
                    val sameTurn = page.rows.filter { it.id == previous.id && it.turnId == previous.turnId }
                    val row = sameTurn.singleOrNull() ?: throw TowerError.Malformed("agent commentary association")
                    val next = row.raw.string("commentary_next_cursor")
                    val reached = next ?: row.raw.string("commentary_cursor")
                    if (reached?.toLongOrNull() == null || reached.toLong() <= (after.toLongOrNull() ?: -1))
                        throw TowerError.Malformed("agent commentary cursor repeated")
                    store.writer.replaceAgentActivities(workspace.id, channelId, sameTurn)
                    if (next != null) enqueueRecovery()
                }
            }
        }
        enqueueRecovery()
    }
    suspend fun persist(page: AgentActivityPage, traversal: AgentActivityTraversal, head: Boolean): AgentActivityTraversal {
        val retained = if (beforeSequence == null) store.reader.agentActivitiesById(workspace.id, channelId, page.rows.map { it.id }).associateBy { it.id } else emptyMap()
        val saved = store.writer.saveAgentActivityPage(workspace.id, channelId, page.rows, checkpoint, traversal, syncKey, head, reuseRecent = !force)
        for (latest in page.rows) {
            val previous = retained[latest.id] ?: continue
            val known = previous.raw.string("android_recovered_cursor") ?: previous.raw.string("commentary_cursor") ?: "0"
            if (latest.turnId == previous.turnId && (latest.raw.string("commentary_cursor")?.toLongOrNull() ?: 0) > (known.toLongOrNull() ?: 0)) recover(previous, latest.raw.string("commentary_cursor")!!.toLong())
        }
        return saved
    }
    suspend fun enqueueHistory() {
        engine.enqueueRead("$key:history", SyncPriority.BACKGROUND, force) {
            readGuard {
                var traversal = store.reader.agentActivityTraversal(checkpoint) ?: return@readGuard
                if (!traversal.pending) return@readGuard
                val page = try {
                    api(connection).agentActivityPage(workspace.id, workspace.appNpub, channelId, threadId, traversal.next, activityId, beforeSequence)
                } catch (e: TowerError.Http) {
                    // Invalidated opaque cursors get one fresh pass, never an endless restart from page one.
                    if (e.status !in listOf(400, 409) || e.code?.contains("cursor") != true || !e.code.contains("invalid") || traversal.restarted) throw e
                    traversal = AgentActivityTraversal(restarted = true, pending = true)
                    store.writer.saveAgentActivityPage(workspace.id, channelId, emptyList(), checkpoint, traversal, syncKey)
                    enqueueHistory()
                    return@readGuard
                }
                checkCancelled()
                val next = page.nextCursor
                if (next != null && (next == traversal.next || next in traversal.seen || next == traversal.anchor || page.rows.isEmpty()))
                    throw TowerError.Malformed("agent activity cursor repeated or nonadvancing")
                // A small recent window catches short replays immediately. Brent's anchor detects longer cycles
                // in constant space, even across process recreation; checkpoint size does not grow with history.
                val steps = traversal.steps + 1
                val advanceAnchor = steps >= traversal.span
                val updated = AgentActivityTraversal(next,
                    (traversal.seen + listOfNotNull(next)).toList().takeLast(32).toSet(), traversal.restarted,
                    anchor = if (advanceAnchor) next else traversal.anchor,
                    span = if (advanceAnchor) (traversal.span * 2).coerceAtMost(Long.MAX_VALUE / 2) else traversal.span,
                    steps = if (advanceAnchor) 0 else steps)
                val saved = persist(page, updated, false)
                if (saved.pending) enqueueHistory()
            }
        }
    }
    engine.enqueueRead(key, priority, force) {
        readGuard {
            val page = api(connection).agentActivityPage(workspace.id, workspace.appNpub, channelId, threadId, activityId = activityId, beforeSequence = beforeSequence)
            checkCancelled()
            if (page.nextCursor != null && page.rows.isEmpty()) throw TowerError.Malformed("agent activity cursor nonadvancing")
            val saved = persist(page, AgentActivityTraversal(page.nextCursor, setOfNotNull(page.nextCursor)), true)
            // Admission marker for event refresh; a filtered traversal cannot claim a whole channel is complete.
            if (syncKey != base && store.reader.listSyncComplete(workspace.id, base) == null)
                store.writer.saveListSync(workspace.id, base, complete = false)
            if (saved.pending) enqueueHistory()
        }
    }
}
