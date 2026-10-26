package studio.otherstuff.ambulando.core.chat

import studio.otherstuff.ambulando.core.store.isAccessRemoved
import studio.otherstuff.ambulando.core.store.FlaggedTable

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.MessageKey
import studio.otherstuff.ambulando.core.store.channel
import studio.otherstuff.ambulando.core.store.channelSync
import studio.otherstuff.ambulando.core.store.isChannelAccessRemoved
import studio.otherstuff.ambulando.core.store.reconcileMessages
import studio.otherstuff.ambulando.core.store.replaceMembers
import studio.otherstuff.ambulando.core.store.saveChannelSync
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.store.upsertMessages
import studio.otherstuff.ambulando.core.store.upsertThreads
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.sync.checkCancelled
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerTime
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

// Chat catch-up, scheduled through `SyncEngine` like every other network read.
//
// Channel history uses `GET …/channels/:c/messages`. It is ordered oldest first with an opaque forward cursor and has no
// "newest first" mode. So each channel keeps the cursor that fetched its last page. The next catch-up re-reads from
// there (≤ 200 rows) to the end and reconciles that range, so recent edits and deletions are picked up without re-reading
// the whole channel. A forced refresh starts from the beginning and reconciles the whole channel. Live deltas (event
// cursor, SSE) are the live-update work and are not used here.
//
// Android: a message Tower no longer lists inside the reconciled range is flagged `access_removed` by the store, not
// deleted (real deletions arrive as tombstones). A channel flagged as no longer visible is not read at all.

/** Schedules a channel catch-up (`force`: pull to refresh, re-read and reconcile the whole channel). */
suspend fun WorkspaceSync.catchUpChannel(
    connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.messagesKey(workspace.id, channelId), priority, force) {
        identity.requireReader(connection.signerNpub)
        if (store.reader.isChannelAccessRemoved(workspace.id, channelId)) return@enqueueRead
        runChannelCatchUp(connection, workspace, channelId, fromStart = force)
    }
}

/** One catch-up pass (also used by live events for a folded burst). Throws on failure; progress per page is kept. */
suspend fun WorkspaceSync.runChannelCatchUp(connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String, fromStart: Boolean) {
    val api = api(connection)
    val stored = if (fromStart) null else store.reader.channelSync(workspace.id, channelId)
    val startAfter = stored?.let { s -> s.resumeAfterId?.let { id -> s.resumeAfterCreatedAt?.let { MessageKey(it, id) } } }
    var cursor: String? = if (startAfter == null) null else stored.resumeCursor
    val reconcileAfter = if (cursor == null) null else startAfter
    var pageCursor = cursor
    var pageAfter = if (cursor == null) null else startAfter
    val keep = HashSet<String>()
    var last: MessageKey? = null
    val seen = HashSet<String>()
    val started = TowerTime.now()
    while (true) {
        val page = api.messagesPage(workspace.id, workspace.appNpub, channelId, cursor = cursor)
        checkCancelled()
        store.writer.upsertMessages(page.messages)
        for (m in page.messages) keep.add(m.id)
        page.messages.lastOrNull()?.let { last = MessageKey(it.createdAt, it.id) }
        val next = page.nextCursor ?: break
        if (!seen.add(next)) throw TowerError.Malformed("messages cursor")
        pageCursor = next
        pageAfter = last
        cursor = next
        // Progress survives interruption: a later run resumes here instead of restarting the channel.
        store.writer.saveChannelSync(workspace.id, channelId, resumeCursor = next, resumeAfter = last, complete = false)
    }
    val through = last ?: MessageKey(started, "")
    store.writer.reconcileMessages(workspace.id, channelId, threadId = null, after = reconcileAfter, through = through, keep = keep)
    store.writer.saveChannelSync(workspace.id, channelId, resumeCursor = pageCursor, resumeAfter = pageAfter, complete = true)
    // Thread titles and archive state (one page; never used to remove anything).
    val threads = api.threads(workspace.id, workspace.appNpub, channelId)
    checkCancelled()
    store.writer.upsertThreads(workspace.id, threads.items)
}

/** Opening a thread fetches that thread's messages directly (user priority), independent of channel catch-up. */
suspend fun WorkspaceSync.refreshThread(
    connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String, threadId: String,
    priority: SyncPriority = SyncPriority.USER, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.threadKey(workspace.id, threadId), priority, force) {
        if (workspace.accessRemoved || store.reader.isAccessRemoved(workspace.id, FlaggedTable.THREADS, threadId)) return@enqueueRead
        identity.requireReader(connection.signerNpub)
        if (store.reader.isChannelAccessRemoved(workspace.id, channelId)) return@enqueueRead
        val api = api(connection)
        var cursor: String? = null
        val keep = HashSet<String>()
        var last: MessageKey? = null
        val seen = HashSet<String>()
        val started = TowerTime.now()
        do {
            val page = api.messagesPage(workspace.id, workspace.appNpub, channelId, threadId = threadId, cursor = cursor)
            checkCancelled()
            store.writer.upsertMessages(page.messages)
            for (m in page.messages) keep.add(m.id)
            page.messages.lastOrNull()?.let { last = MessageKey(it.createdAt, it.id) }
            cursor = page.nextCursor
            cursor?.let { if (!seen.add(it)) throw TowerError.Malformed("messages cursor") }
        } while (cursor != null)
        store.writer.reconcileMessages(workspace.id, channelId, threadId = threadId, after = null, through = last ?: MessageKey(started, ""), keep = keep)
        val thread = api.thread(workspace.id, workspace.appNpub, threadId)
        if (thread.workspaceId == workspace.id && thread.channelId == channelId) store.writer.upsertThreads(workspace.id, listOf(thread))
    }
}

/** Member directory (mention picker, sender names) and personal scope order. */
suspend fun WorkspaceSync.refreshDirectory(
    connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority = SyncPriority.BACKGROUND, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.directoryKey(workspace.id), priority, force) {
        identity.requireReader(connection.signerNpub)
        val api = api(connection)
        val members = api.members(workspace.id, workspace.appNpub)
        checkCancelled()
        store.writer.replaceMembers(workspace.id, members)
        val order = api.scopeOrder(workspace.id, workspace.appNpub)
        checkCancelled()
        val encoded = Json.encodeToString(ListSerializer(String.serializer()), order)
        if (store.reader.state(WorkspaceSync.scopeOrderKey(workspace.id)) != encoded) store.writer.setState(WorkspaceSync.scopeOrderKey(workspace.id), encoded)
    }
}

/** Whether a channel is stored locally for this workspace (the channel list is the access authority on the phone). */
internal suspend fun WorkspaceSync.knowsChannel(workspaceId: String, channelId: String): Boolean = store.reader.channel(workspaceId, channelId) != null
