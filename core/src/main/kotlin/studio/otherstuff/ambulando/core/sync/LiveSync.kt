package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import studio.otherstuff.ambulando.core.chat.catchUpChannel
import studio.otherstuff.ambulando.core.chat.refreshDirectory
import studio.otherstuff.ambulando.core.chat.runChannelCatchUp
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.store.AccessTarget
import studio.otherstuff.ambulando.core.store.ChangeKey
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.applyTombstones
import studio.otherstuff.ambulando.core.store.canvasSnapshot
import studio.otherstuff.ambulando.core.store.channelSync
import studio.otherstuff.ambulando.core.store.doc
import studio.otherstuff.ambulando.core.store.docBody
import studio.otherstuff.ambulando.core.store.eventCursor
import studio.otherstuff.ambulando.core.store.listSyncComplete
import studio.otherstuff.ambulando.core.store.markAccessRemoved
import studio.otherstuff.ambulando.core.store.message
import studio.otherstuff.ambulando.core.store.removeCanvas
import studio.otherstuff.ambulando.core.store.removeContextComponent
import studio.otherstuff.ambulando.core.store.removeContextTree
import studio.otherstuff.ambulando.core.store.replaceAgentActivities
import studio.otherstuff.ambulando.core.store.replaceCanvases
import studio.otherstuff.ambulando.core.store.replaceContextReferences
import studio.otherstuff.ambulando.core.store.replacePersonalWapps
import studio.otherstuff.ambulando.core.store.replaceReactions
import studio.otherstuff.ambulando.core.store.replaceWorkspaceAgents
import studio.otherstuff.ambulando.core.store.saveCanvasSnapshot
import studio.otherstuff.ambulando.core.store.saveDocBody
import studio.otherstuff.ambulando.core.store.saveEventCursor
import studio.otherstuff.ambulando.core.store.saveListSync
import studio.otherstuff.ambulando.core.store.syncableChannels
import studio.otherstuff.ambulando.core.store.task
import studio.otherstuff.ambulando.core.store.upsertComments
import studio.otherstuff.ambulando.core.store.upsertContextComponents
import studio.otherstuff.ambulando.core.store.upsertDocs
import studio.otherstuff.ambulando.core.store.upsertFiles
import studio.otherstuff.ambulando.core.store.upsertFolders
import studio.otherstuff.ambulando.core.store.upsertMessages
import studio.otherstuff.ambulando.core.store.upsertPins
import studio.otherstuff.ambulando.core.store.upsertTasks
import studio.otherstuff.ambulando.core.store.upsertThreads
import studio.otherstuff.ambulando.core.store.upsertViewStates
import studio.otherstuff.ambulando.core.surfaces.CanvasScene
import studio.otherstuff.ambulando.core.surfaces.agentActivities
import studio.otherstuff.ambulando.core.surfaces.canvasSnapshot
import studio.otherstuff.ambulando.core.surfaces.canvases
import studio.otherstuff.ambulando.core.surfaces.contextComponents
import studio.otherstuff.ambulando.core.surfaces.contextReferences
import studio.otherstuff.ambulando.core.surfaces.personalPins
import studio.otherstuff.ambulando.core.surfaces.personalWapps
import studio.otherstuff.ambulando.core.surfaces.refreshAgents
import studio.otherstuff.ambulando.core.surfaces.refreshCanvases
import studio.otherstuff.ambulando.core.surfaces.refreshPins
import studio.otherstuff.ambulando.core.surfaces.refreshToolbox
import studio.otherstuff.ambulando.core.surfaces.scheduleBoards
import studio.otherstuff.ambulando.core.surfaces.workspaceAgents
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.EVENT_PAGE_LIMIT
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerEvent
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.channelDocs
import studio.otherstuff.ambulando.core.transport.channelFiles
import studio.otherstuff.ambulando.core.transport.channelFolders
import studio.otherstuff.ambulando.core.transport.channelTasks
import studio.otherstuff.ambulando.core.transport.doc
import studio.otherstuff.ambulando.core.transport.docBody
import studio.otherstuff.ambulando.core.transport.docComments
import studio.otherstuff.ambulando.core.transport.eventHead
import studio.otherstuff.ambulando.core.transport.events
import studio.otherstuff.ambulando.core.transport.message
import studio.otherstuff.ambulando.core.transport.reactions
import studio.otherstuff.ambulando.core.transport.task
import studio.otherstuff.ambulando.core.transport.taskComments
import studio.otherstuff.ambulando.core.transport.viewStates
import java.time.Instant

/** Observable diagnostics of live updates for one workspace (Settings, tests). */
data class LiveStatus(
    val stream: Stream = Stream.STOPPED,
    val eventsApplied: Int = 0,
    val batches: Int = 0,
    val gapRecoveries: Int = 0,
    val seeded: Int = 0,
    val lastEventAt: Instant? = null,
    val lastError: String? = null,
) {
    enum class Stream { STOPPED, CONNECTING, CONNECTED, RECONNECTING, LOCKED, ACCESS_LOST }
}

/** Result of one bounded event catch-up. */
data class EventCatchUp(
    val pages: Int = 0,
    val events: Int = 0,
    /** No stored cursor: started at the current head and caught lists up directly. */
    val seeded: Boolean = false,
    /**
     * More history than the page budget, or a cursor Tower no longer accepts: restarted at the head and scheduled a full
     * list catch-up instead of replaying.
     */
    val gapRecovered: Boolean = false,
    /** Tower refused the workspace (403/404): discovery was scheduled, which flags it if access is gone. */
    val accessLost: Boolean = false,
    val complete: Boolean = false,
)

/** A catch-up the caller waited for did not finish in time. */
class EventCatchUpTimeout : Exception("The event catch-up did not finish in time.")

/**
 * Pending live-update work of one workspace, shared by the foreground event stream and event polls. Both are drained by
 * the single `events:<w>` job, so one workspace's event cursor is only ever advanced by one job at a time. All state is
 * guarded by one mutex (the analogue of the Swift actor).
 */
class LiveFeed {
    /** Consecutive stream events: [from] is the cursor the first one follows, [through] the last one's cursor. */
    internal data class Batch(val from: String, val through: String, val events: List<TowerEvent>)

    private val mutex = Mutex()
    private var batch: Batch? = null
    private var pollPages = 0
    private val pollWaiters = LinkedHashSet<CompletableDeferred<EventCatchUp>>()
    /** Set when a stream batch no longer follows the stored cursor; the stream reopens from storage. */
    private var needsReconnect = false
    @Volatile var visibleChannelId: String? = null
        private set

    private val _status = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = _status.asStateFlow()

    internal suspend fun push(event: TowerEvent, after: String) = mutex.withLock {
        val current = batch
        when {
            current != null && current.through == after -> batch = current.copy(through = event.cursor, events = current.events + event)
            current == null -> batch = Batch(after, event.cursor, listOf(event))
            else -> needsReconnect = true   // the stream skipped: never splice two chains
        }
    }

    internal suspend fun requestPoll(pages: Int) = mutex.withLock { pollPages = maxOf(pollPages, pages) }

    internal suspend fun take(): Pair<Batch?, Int> = mutex.withLock {
        val result = batch to pollPages
        batch = null
        pollPages = 0
        result
    }

    /** Puts a batch that failed to apply back in front of anything received meanwhile. */
    internal suspend fun restore(failed: Batch) = mutex.withLock {
        val current = batch
        when {
            current != null && current.from == failed.through -> batch = Batch(failed.from, current.through, failed.events + current.events)
            current == null -> batch = failed
            else -> needsReconnect = true
        }
    }

    internal suspend fun restorePoll(pages: Int) = mutex.withLock { pollPages = maxOf(pollPages, pages) }

    internal suspend fun takeReconnect(): Boolean = mutex.withLock { needsReconnect.also { needsReconnect = false } }

    internal suspend fun markDiscontiguous() = mutex.withLock { needsReconnect = true; batch = null }

    /** Registers interest in the next finished poll before the job is scheduled, so a fast job cannot finish unseen. */
    internal suspend fun expectPoll(): CompletableDeferred<EventCatchUp> = mutex.withLock {
        CompletableDeferred<EventCatchUp>().also { pollWaiters.add(it) }
    }

    internal suspend fun waitForPoll(slot: CompletableDeferred<EventCatchUp>, timeoutMillis: Long): EventCatchUp {
        try {
            return withTimeoutOrNull(timeoutMillis) { slot.await() } ?: throw EventCatchUpTimeout()
        } finally {
            mutex.withLock { pollWaiters.remove(slot) }
        }
    }

    internal suspend fun finishPoll(result: Result<EventCatchUp>) {
        val waiters = mutex.withLock { pollWaiters.toList().also { pollWaiters.clear() } }
        for (w in waiters) result.fold({ w.complete(it) }, { w.completeExceptionally(it) })
    }

    /** The channel on screen; its live work runs first. */
    fun setVisibleChannel(id: String?) { visibleChannelId = id }

    internal fun update(change: (LiveStatus) -> LiveStatus) = _status.update(change)

    /** Drops queued stream events (workspace switch, lock, stream restart) and fails waiting polls. */
    internal suspend fun reset() {
        mutex.withLock {
            batch = null
            needsReconnect = false
        }
        finishPoll(Result.failure(CancellationException("Live updates were reset.")))
    }
}

/** Per-workspace [LiveFeed]s. One per [WorkspaceSync]. */
class LiveRegistry {
    private val feeds = HashMap<String, LiveFeed>()
    @Synchronized fun feed(workspaceId: String): LiveFeed = feeds.getOrPut(workspaceId) { LiveFeed() }
}

private fun describe(error: Throwable): String = error.message ?: error::class.simpleName ?: "Error"

/** Bounded event catch-up from the stored cursor (foreground resume, background refresh, pull to refresh). */
suspend fun WorkspaceSync.catchUpEvents(
    connection: ConnectionRecord, workspace: WorkspaceRecord, pages: Int = WorkspaceSync.EVENT_PAGE_BUDGET,
    priority: SyncPriority = SyncPriority.BACKGROUND, force: Boolean = false,
) {
    if (workspace.accessRemoved) return
    live.feed(workspace.id).requestPoll(maxOf(1, pages))
    scheduleEvents(connection, workspace, priority, force)
}

/** Like [catchUpEvents], then waits for that catch-up to finish (the event stream opens only afterwards). */
suspend fun WorkspaceSync.catchUpEventsAndWait(
    connection: ConnectionRecord, workspace: WorkspaceRecord, pages: Int = WorkspaceSync.EVENT_PAGE_BUDGET,
    priority: SyncPriority = SyncPriority.VISIBLE, timeoutMillis: Long = 180_000,
): EventCatchUp {
    val feed = live.feed(workspace.id)
    val slot = feed.expectPoll()
    feed.requestPoll(maxOf(1, pages))
    scheduleEvents(connection, workspace, priority, force = true)
    return feed.waitForPoll(slot, timeoutMillis)
}

internal suspend fun WorkspaceSync.scheduleEvents(
    connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority, force: Boolean = false, delayMillis: Long? = null,
) {
    engine.enqueue(WorkspaceSync.eventsKey(workspace.id), priority, force, delayMillis) { runEvents(connection, workspace) }
}

/**
 * The `events:<w>` job: applies queued stream events (only if they follow the stored cursor), then any requested poll.
 * The cursor is saved only after the batch's records are materialised.
 */
internal suspend fun WorkspaceSync.runEvents(connection: ConnectionRecord, workspace: WorkspaceRecord) {
    val feed = live.feed(workspace.id)
    val (batch, pages) = feed.take()
    try {
        identity.requireReader(connection.signerNpub)
        if (batch != null) {
            val stored = store.reader.eventCursor(connection.id, workspace.id)
            if (stored == batch.from) {
                try {
                    applyEvents(batch.events, connection, workspace)
                } catch (e: Throwable) {
                    feed.restore(batch)
                    throw e
                }
                store.writer.saveEventCursor(connection.id, workspace.id, batch.through)
                val count = batch.events.size
                feed.update { it.copy(eventsApplied = it.eventsApplied + count, batches = it.batches + 1, lastEventAt = Instant.now(), lastError = null) }
            } else {
                feed.markDiscontiguous()
            }
        }
        if (pages > 0) {
            val result = try {
                pollEvents(connection, workspace, pages)
            } catch (e: Throwable) {
                if (e !is CancellationException && currentCoroutineContext().isActive) feed.restorePoll(pages)
                throw e
            }
            feed.update {
                it.copy(
                    eventsApplied = it.eventsApplied + result.events,
                    lastEventAt = if (result.events > 0) Instant.now() else it.lastEventAt,
                    gapRecoveries = it.gapRecoveries + if (result.gapRecovered) 1 else 0,
                    seeded = it.seeded + if (result.seeded) 1 else 0,
                    stream = if (result.accessLost) LiveStatus.Stream.ACCESS_LOST else it.stream,
                    lastError = null,
                )
            }
            feed.finishPoll(Result.success(result))
        }
    } catch (e: Throwable) {
        if (pages > 0) feed.finishPoll(Result.failure(e))
        if (e !is CancellationException && currentCoroutineContext().isActive) feed.update { it.copy(lastError = describe(e)) }
        throw e
    }
}

/**
 * Reads event pages from the stored cursor, applying and saving after each page. Stops at a short page, after [pages]
 * pages (gap recovery) or on cancellation (progress so far is kept).
 */
internal suspend fun WorkspaceSync.pollEvents(connection: ConnectionRecord, workspace: WorkspaceRecord, pages: Int): EventCatchUp {
    val api = api(connection)
    var cursor = store.reader.eventCursor(connection.id, workspace.id)
    if (cursor == null) {
        reseed(connection, workspace, api)
        return EventCatchUp(seeded = true, complete = true)
    }
    var read = 0
    var events = 0
    while (read < pages) {
        checkCancelled()
        val page = try {
            api.events(workspace.id, workspace.appNpub, cursor)
        } catch (e: TowerError.Http) {
            when {
                e.status == 400 -> {
                    // Tower no longer accepts this cursor: restart at the head instead of replaying from zero.
                    reseed(connection, workspace, api)
                    return EventCatchUp(read, events, gapRecovered = true, complete = true)
                }
                WorkspaceSync.lost(e) -> {
                    discover(connection, SyncPriority.BACKGROUND, force = true)
                    return EventCatchUp(read, events, accessLost = true)
                }
                else -> throw e
            }
        }
        checkCancelled()
        read += 1
        if (page.events.isNotEmpty()) applyEvents(page.events, connection, workspace)
        events += page.events.size
        val next = page.nextCursor
        if (next != null && next != cursor) {
            store.writer.saveEventCursor(connection.id, workspace.id, next)
            cursor = next
        }
        if (page.events.size + page.skipped < EVENT_PAGE_LIMIT) return EventCatchUp(read, events, complete = true)
    }
    // Backlog larger than the budget: skip to the head and catch the lists up directly (bounded, background).
    reseed(connection, workspace, api)
    return EventCatchUp(read, events, gapRecovered = true, complete = true)
}

/** Starts from the current head and schedules direct catch-up of everything the screens show. */
internal suspend fun WorkspaceSync.reseed(connection: ConnectionRecord, workspace: WorkspaceRecord, api: TowerAPI) {
    val head = api.eventHead(workspace.id, workspace.appNpub)
    checkCancelled()
    // The head is saved before the catch-up runs, so changes made meanwhile are replayed, never skipped.
    store.writer.saveEventCursor(connection.id, workspace.id, head)
    refreshWorkspace(connection, workspace, SyncPriority.BACKGROUND)
    refreshInbox(connection, workspace, SyncPriority.BACKGROUND)
    val visible = live.feed(workspace.id).visibleChannelId
    for (channel in store.reader.syncableChannels(workspace.id)) {
        val priority = if (channel.id == visible) SyncPriority.VISIBLE else SyncPriority.BACKGROUND
        // Only channels this phone has read before; others catch up when opened.
        if (store.reader.channelSync(workspace.id, channel.id) != null) catchUpChannel(connection, workspace, channel.id, priority)
    }
    refreshTasks(connection, workspace, priority = SyncPriority.BACKGROUND)
    refreshDocs(connection, workspace, priority = SyncPriority.BACKGROUND)
    refreshFiles(connection, workspace, priority = SyncPriority.BACKGROUND)
    refreshPins(connection, workspace, SyncPriority.BACKGROUND)
    refreshCanvases(connection, workspace, SyncPriority.BACKGROUND)
    refreshToolbox(connection, workspace, SyncPriority.BACKGROUND)
    refreshAgents(connection, workspace, emptyList(), SyncPriority.BACKGROUND)
}

/**
 * Applies one ordered batch: tombstones first, then the records it names (visible channel first, bounded concurrency).
 * Throws if a record could not be read, so the caller keeps the cursor and the batch is replayed.
 */
suspend fun WorkspaceSync.applyEvents(events: List<TowerEvent>, connection: ConnectionRecord, workspace: WorkspaceRecord) {
    val plan = EventPlan.make(events, workspace.id)
    store.writer.applyTombstones(workspace.id, plan.tombstones)
    val visible = live.feed(workspace.id).visibleChannelId
    val ordered = plan.refreshes.sortedBy { liveRank(it, visible) }
    val api = api(connection)
    val slots = Semaphore(WorkspaceSync.LIVE_FETCH_CONCURRENCY)
    coroutineScope {
        for (refresh in ordered) {
            val version = plan.versions[refresh]
            slots.acquire()
            launch {
                try {
                    materialize(refresh, version, connection, workspace, api)
                } finally {
                    slots.release()
                }
            }
        }
    }
}

internal fun liveRank(refresh: EventPlan.Refresh, visible: String?): Int = when (refresh) {
    is EventPlan.Refresh.Message -> if (refresh.channelId == visible) 0 else 2
    is EventPlan.Refresh.Thread -> if (refresh.channelId == visible) 0 else 2
    is EventPlan.Refresh.ChannelMessages -> if (refresh.channelId == visible) 0 else 2
    is EventPlan.Refresh.Reactions -> if (refresh.channelId == visible) 0 else 2
    is EventPlan.Refresh.ChannelTasks -> if (refresh.channelId == visible) 1 else 3
    is EventPlan.Refresh.ChannelDocs -> if (refresh.channelId == visible) 1 else 3
    is EventPlan.Refresh.ChannelFiles -> if (refresh.channelId == visible) 1 else 3
    is EventPlan.Refresh.Task, is EventPlan.Refresh.Doc -> 2
    EventPlan.Refresh.Inbox, EventPlan.Refresh.Pins -> 3
    is EventPlan.Refresh.AgentActivity -> if (refresh.channelId == visible) 1 else 3
    is EventPlan.Refresh.Canvas, is EventPlan.Refresh.ContextReferences -> 3
    EventPlan.Refresh.Structure, EventPlan.Refresh.Directory, EventPlan.Refresh.Access, EventPlan.Refresh.Canvases,
    is EventPlan.Refresh.ContextTree, EventPlan.Refresh.Agents, EventPlan.Refresh.Toolbox -> 4
}

/** Runs [block]; a 403/404 runs [onLost] instead (Android: flag, never delete). */
private suspend inline fun onAccessLoss(onLost: () -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (e: TowerError.Http) {
        if (!WorkspaceSync.lost(e)) throw e
        onLost()
    }
}

private suspend fun WorkspaceSync.materialize(
    refresh: EventPlan.Refresh, version: Long?, connection: ConnectionRecord, workspace: WorkspaceRecord, api: TowerAPI,
) {
    val w = workspace.id
    val app = workspace.appNpub
    checkCancelled()
    when (refresh) {
        is EventPlan.Refresh.Message -> {
            if (version != null) store.reader.message(w, refresh.messageId)?.let { if (it.rowVersion >= version) return }
            val message = api.message(w, app, refresh.messageId)
            if (message != null) {
                if (message.channelId != refresh.channelId) return   // never file a record under another channel
                store.writer.upsertMessages(listOf(message))
            } else {
                // 404: deleted or no longer visible. Real deletions arrive as `deleted` events; this one is flagged.
                store.writer.markAccessRemoved(w, AccessTarget.Message(refresh.messageId))
            }
        }
        is EventPlan.Refresh.Thread -> onAccessLoss({ store.writer.markAccessRemoved(w, AccessTarget.Thread(refresh.threadId)) }) {
            val thread = api.thread(w, app, refresh.threadId)
            if (thread.workspaceId == w && thread.channelId == refresh.channelId) store.writer.upsertThreads(w, listOf(thread))
        }
        is EventPlan.Refresh.ChannelMessages -> runChannelCatchUp(connection, workspace, refresh.channelId, fromStart = false)
        is EventPlan.Refresh.Task -> {
            if (version != null) store.reader.task(w, refresh.id)?.let { if (it.rowVersion >= version) return }
            onAccessLoss({ store.writer.markAccessRemoved(w, AccessTarget.Task(refresh.id)) }) {
                val task = api.task(w, app, refresh.id)
                store.writer.upsertTasks(w, listOf(task))
                val comments = api.taskComments(w, app, refresh.id)
                store.writer.upsertComments(w, comments, complete = CommentRecord.Parent.TASK to task.id)
            }
        }
        is EventPlan.Refresh.ChannelTasks -> {
            val tasks = api.channelTasks(w, app, refresh.channelId)
            store.writer.upsertTasks(w, tasks, completeChannel = refresh.channelId)
            store.writer.saveListSync(w, WorkspaceSync.tasksKey(w, refresh.channelId), complete = true)
        }
        is EventPlan.Refresh.Doc -> {
            if (version != null) store.reader.doc(w, refresh.id)?.let { if (it.rowVersion >= version) return }
            onAccessLoss({ store.writer.markAccessRemoved(w, AccessTarget.Doc(refresh.id)) }) {
                val doc = api.doc(w, app, refresh.id)
                store.writer.upsertDocs(w, listOf(doc))
                // A body kept for offline reading stays current; others are fetched when opened.
                val cached = store.reader.docBody(w, refresh.id)
                if (cached != null && cached.docRowVersion < doc.rowVersion) {
                    val (fresh, body) = api.docBody(w, app, refresh.id)
                    store.writer.saveDocBody(body, fresh, searchText = DocumentBody(body.bytes, body.contentType).markdown)
                }
                val comments = api.docComments(w, app, refresh.id)
                store.writer.upsertComments(w, comments, complete = CommentRecord.Parent.DOC to doc.id)
            }
        }
        is EventPlan.Refresh.ChannelDocs -> {
            val page = api.channelDocs(w, app, refresh.channelId)
            store.writer.upsertDocs(w, page.items, completeChannel = if (page.complete) refresh.channelId else null)
            store.writer.saveListSync(w, WorkspaceSync.docsKey(w, refresh.channelId), complete = page.complete)
        }
        is EventPlan.Refresh.ChannelFiles -> {
            val files = api.channelFiles(w, app, refresh.channelId)
            store.writer.upsertFiles(w, files.items, completeChannel = if (files.complete) refresh.channelId else null)
            val folders = api.channelFolders(w, app, refresh.channelId)
            store.writer.upsertFolders(w, refresh.channelId, folders.items, complete = folders.complete)
            store.writer.saveListSync(w, WorkspaceSync.filesKey(w, refresh.channelId), complete = files.complete)
        }
        is EventPlan.Refresh.Reactions -> {
            val list = api.reactions(w, app, "message", refresh.messageId)
            store.writer.replaceReactions(w, listOf(refresh.messageId), list)
        }
        EventPlan.Refresh.Inbox -> store.writer.upsertViewStates(w, api.viewStates(w, app), complete = true)
        // Scopes/channels are re-listed as their own coalescing job (also run on every foreground resume).
        EventPlan.Refresh.Structure -> refreshWorkspace(connection, workspace, SyncPriority.BACKGROUND)
        EventPlan.Refresh.Directory -> refreshDirectory(connection, workspace, SyncPriority.BACKGROUND)
        EventPlan.Refresh.Access -> discover(connection, SyncPriority.BACKGROUND, force = true)
        EventPlan.Refresh.Canvases -> store.writer.replaceCanvases(w, api.canvases(w, app))
        is EventPlan.Refresh.Canvas -> {
            // Only canvases this phone has opened are kept current; others are read when opened.
            if (store.reader.canvasSnapshot(w, refresh.id) == null) return
            onAccessLoss({ store.writer.removeCanvas(w, refresh.id) }) {
                val (canvas, snapshot) = api.canvasSnapshot(w, app, refresh.id)
                store.writer.saveCanvasSnapshot(snapshot, canvas)
                scheduleBoards(connection, workspace, CanvasScene.parseCancellable(snapshot.bytes), SyncPriority.BACKGROUND)
            }
        }
        is EventPlan.Refresh.ContextTree -> {
            if (store.reader.listSyncComplete(w, WorkspaceSync.contextTreeKey(w, refresh.scopeId)) == null) return
            onAccessLoss({ store.writer.removeContextTree(w, refresh.scopeId) }) {
                store.writer.upsertContextComponents(w, refresh.scopeId, api.contextComponents(w, app, refresh.scopeId), complete = true)
            }
        }
        is EventPlan.Refresh.ContextReferences -> {
            if (store.reader.listSyncComplete(w, WorkspaceSync.contextReferencesKey(w, refresh.componentId)) == null) return
            onAccessLoss({ store.writer.removeContextComponent(w, refresh.componentId) }) {
                val references = api.contextReferences(w, app, refresh.scopeId, refresh.componentId)
                store.writer.replaceContextReferences(w, refresh.scopeId, refresh.componentId, references)
            }
        }
        EventPlan.Refresh.Pins -> store.writer.upsertPins(connection.id, w, api.personalPins(w, app).pins, complete = true)
        EventPlan.Refresh.Agents -> store.writer.replaceWorkspaceAgents(w, api.workspaceAgents(w, app))
        is EventPlan.Refresh.AgentActivity -> {
            if (store.reader.listSyncComplete(w, WorkspaceSync.agentActivityKey(w, refresh.channelId)) == null) return
            onAccessLoss({ store.writer.markAccessRemoved(w, AccessTarget.AgentActivity(refresh.channelId)) }) {
                store.writer.replaceAgentActivities(w, refresh.channelId, api.agentActivities(w, app, refresh.channelId))
            }
        }
        EventPlan.Refresh.Toolbox -> {
            val page = api.personalWapps(w, app)
            store.writer.replacePersonalWapps(connection.id, w, page.items, complete = page.complete)
            store.writer.saveListSync(w, WorkspaceSync.toolboxKey(w), complete = page.complete)
            store.writer.touch(setOf(ChangeKey(StoreTable.TOOLBOX, w)))
        }
    }
}
