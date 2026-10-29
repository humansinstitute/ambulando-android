package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import studio.otherstuff.ambulando.core.context.NavigationKeys
import studio.otherstuff.ambulando.core.context.NavigationSyncSnapshot
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.chat.catchUpChannel
import studio.otherstuff.ambulando.core.chat.refreshDirectory
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.channelSync
import studio.otherstuff.ambulando.core.store.replaceWorkspaces
import studio.otherstuff.ambulando.core.store.saveConnection
import studio.otherstuff.ambulando.core.store.upsertChannels
import studio.otherstuff.ambulando.core.store.upsertScopes
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.NStackDescriptor
import studio.otherstuff.ambulando.core.transport.ObjectTransfers
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/**
 * Something that sends locally queued changes of one connection (the chat outbox and the durable write queue implement
 * it). The read side only ever asks it to start: [backgroundRefresh] kicks every sender when the identity is unlocked in
 * memory, and the app may kick them after a foreground catch-up. Implementations schedule their own engine jobs and
 * return without waiting for them.
 */
fun interface PendingSender {
    suspend fun kick(connection: ConnectionRecord)
}

/**
 * The workspace read path: nStack descriptor → workspace discovery → scopes → channels, materialised into the local
 * store. All work is scheduled through [SyncEngine]; screens only read the store.
 *
 * The other read paths are extension functions in their area, as on iPhone: chat catch-up (`core.chat` ChatSync),
 * tasks/documents/files/inbox ([refreshTasks] … in WorkSync), Context Tree / Canvas / agents / Toolbox / pins
 * (`core.surfaces` SurfaceSync), live events ([catchUpEvents], [applyEvents], [LiveStream]) and [backgroundRefresh].
 * Every job key is in the companion object, so screens and the sidebar can match engine state without importing an
 * area.
 *
 * Every request is bound to the connection's origin and signer (`requireReader(connection.signerNpub)` inside the job)
 * and to the workspace's `app_npub`; nothing is ever redirected to the currently selected workspace.
 *
 * Android: access loss is non-destructive. Where iOS deletes a local copy on 403/404 or when a complete list no longer
 * contains it, these jobs flag it `access_removed` through the store and stop reading it; records Tower reports deleted
 * are still removed.
 */
class WorkspaceSync(
    val store: LocalStore,
    val engine: SyncEngine,
    val identity: IdentityService,
    val transport: HttpTransport,
    /** Live-update state per workspace (event stream batches, poll requests, diagnostics). */
    val live: LiveRegistry = LiveRegistry(),
    /** Out-of-process transfers for presigned object-store URLs, when the app provides them. */
    val transfers: ObjectTransfers? = null,
) {
    /** A Tower API over [origin], signed by the identity (or unsigned for the public descriptor). */
    fun api(origin: TowerOrigin, signed: Boolean = true): TowerAPI =
        TowerAPI(TowerClient(origin, transport, if (signed) identity else null), transfers)

    /** The API of a stored connection (its pinned origin). */
    fun api(connection: ConnectionRecord): TowerAPI = api(TowerOrigin(connection.origin))

    /** Reads the public descriptor for an origin the user typed. No identity is used. */
    suspend fun checkDescriptor(origin: TowerOrigin): NStackDescriptor = api(origin, signed = false).descriptor()

    /** Validates the descriptor, binds the origin to the unlocked identity, and persists the connection. */
    suspend fun connect(origin: TowerOrigin): ConnectionRecord {
        val npub = (identity.state() as? IdentityState.Unlocked)?.npub ?: throw IdentityError.Locked
        val descriptor = checkDescriptor(origin)
        return store.writer.saveConnection(ConnectionRecord.create(origin, npub, descriptor.stack?.towerNpub))
    }

    /**
     * Discovery job: the authoritative workspace list for the connection, then each workspace's scopes. A workspace no
     * longer listed is flagged (kept readable, left out of sync) by the store.
     */
    suspend fun discover(connection: ConnectionRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false) {
        engine.enqueueRead(discoverKey(connection.id), priority, force) {
            identity.requireReader(connection.signerNpub)
            val origin = TowerOrigin(connection.origin)
            val workspaces = api(origin).workspaces()
            checkCancelled()
            store.writer.replaceWorkspaces(connection.id, workspaces)
            // Keeps an enabled background credential pinned to exactly the workspaces this identity can see.
            runCatching { identity.updateBackgroundWorkspaces(origin, workspaces.map { it.id }) }
            for (workspace in workspaces) refreshWorkspace(connection, workspace, SyncPriority.BACKGROUND)
        }
    }

    /** Scopes job for one workspace; on success schedules one channel job per scope. */
    suspend fun refreshWorkspace(connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false) {
        engine.enqueueRead(scopesKey(workspace.id), priority, force) {
            identity.requireReader(connection.signerNpub)
            val api = api(connection)
            val scopes = api.scopes(workspace.id, workspace.appNpub)
            checkCancelled()
            store.writer.upsertScopes(workspace.id, scopes, complete = true)
            refreshDirectory(connection, workspace, SyncPriority.BACKGROUND)
            for (scope in scopes) {
                engine.enqueueRead(channelsKey(workspace.id, scope.id), priority) {
                    identity.requireReader(connection.signerNpub)
                    val channels = api.channels(workspace.id, scope.id, workspace.appNpub)
                    checkCancelled()
                    store.writer.upsertChannels(workspace.id, scope.id, channels, complete = true)
                    // Every channel's history is fetched in the background, not only the ones opened on this phone, so
                    // conversations are there before they are opened (bounded by the engine's background slots).
                    for (channel in channels) {
                        if (store.reader.channelSync(workspace.id, channel.id)?.complete != true) {
                            catchUpChannel(connection, workspace, channel.id, SyncPriority.BACKGROUND)
                        }
                    }
                }
            }
        }
    }

    companion object {
        // Navigation (sidebar)
        fun discoverKey(connectionId: String): String = "discover:$connectionId"
        fun scopesKey(w: String): String = "scopes:$w"
        fun channelsKey(w: String, scope: String): String = "channels:$w:$scope"
        fun directoryKey(w: String): String = "directory:$w"

        // Chat (core.chat ChatSync)
        fun messagesKey(w: String, channelId: String): String = "messages:$w:$channelId"
        fun threadKey(w: String, threadId: String): String = "thread:$w:$threadId"
        /** `app_state` key of the personal scope order (a JSON array of scope ids). */
        fun scopeOrderKey(w: String): String = "scope-order:$w"
        fun collapsedKey(connectionId: String, workspaceId: String): String = "scope-collapsed:$connectionId:$workspaceId"

        // Work (WorkSync)
        fun tasksKey(w: String, c: String): String = "tasks:$w:$c"
        fun taskKey(w: String, t: String): String = "task:$w:$t"
        fun docsKey(w: String, c: String): String = "docs:$w:$c"
        fun docKey(w: String, d: String): String = "doc:$w:$d"
        fun filesKey(w: String, c: String): String = "files:$w:$c"
        fun inboxKey(w: String): String = "inbox:$w"
        fun notificationsKey(w: String): String = "notifications:$w"
        fun reactionsKey(w: String, scope: String): String = "reactions:$w:$scope"
        fun downloadKey(w: String, f: String): String = "download:$w:$f"
        fun attachmentKey(w: String, objectId: String): String = "attachment:$w:$objectId"

        /** Most targets refreshed for one conversation's reactions (Tower lists reactions per target). */
        const val REACTION_TARGET_LIMIT = 40
        /** Item pages read by one snapshot job (50 per page); a larger inbox continues in following jobs from the stored cursor. */
        const val NOTIFICATION_PAGE_BUDGET = 10
        /** Replay pages applied in one run (200 changes each); the rest continues on the next hint or refresh. */
        const val NOTIFICATION_REPLAY_BUDGET = 25

        /** Most inbox records fetched individually because they are not in local lists yet. */
        const val INBOX_HYDRATION_LIMIT = 30

        // Surfaces (core.surfaces SurfaceSync)
        fun contextTreeKey(w: String, s: String): String = "context:$w:$s"
        fun contextReferencesKey(w: String, c: String): String = "context-refs:$w:$c"
        fun canvasesKey(w: String): String = "canvases:$w"
        fun canvasKey(w: String, c: String): String = "canvas:$w:$c"
        fun boardKey(w: String, f: String): String = "board:$w:$f"
        fun agentsKey(w: String): String = "agents:$w"
        fun agentActivityKey(w: String, c: String): String = "agent-activity:$w:$c"
        fun toolboxKey(w: String): String = "toolbox:$w"
        fun pinsKey(w: String): String = "pins:$w"
        fun threadRecordKey(w: String, t: String): String = "thread-record:$w:$t"

        /**
         * Channels whose agent activity is read first, at the caller's priority (Tower lists activity per channel). The
         * rest of the context's channels follow at background priority, so none is left unread.
         */
        const val AGENT_ACTIVITY_CHANNEL_LIMIT = 12
        /**
         * Boards of one canvas fetched first, at the caller's priority; the others follow at background priority, and any
         * board shown or opened is also requested on demand (coalescing with its queued job).
         */
        const val BOARD_FETCH_LIMIT = 6

        // Live events (LiveSync)
        fun eventsKey(w: String): String = "events:$w"
        /** Pages a single catch-up may read before treating the backlog as a gap (200 events per page). */
        const val EVENT_PAGE_BUDGET = 10
        /** Records fetched at once while applying a batch. */
        const val LIVE_FETCH_CONCURRENCY = 3

        // Background refresh
        /** Most workspaces caught up per background window (selected first). */
        const val BACKGROUND_WORKSPACE_LIMIT = 5
        /** Event pages per workspace per window. */
        const val BACKGROUND_PAGE_BUDGET = 5

        /** A 403/404: the actor can no longer see the record (Android: flag it, never delete). */
        fun lost(error: Throwable): Boolean = error is TowerError.Http && (error.status == 403 || error.status == 404)

        /** The engine snapshot as the sidebar's load-state rules read it (see `NavigationKeys`). */
        fun navigationSnapshot(snapshot: SyncSnapshot): NavigationSyncSnapshot =
            NavigationSyncSnapshot(snapshot.running, snapshot.queued, snapshot.scheduled, snapshot.errors)

        /** Whether sidebar data is being fetched now (scheduled retries waiting for their backoff do not count). */
        fun navigationRefreshing(snapshot: SyncSnapshot, connectionId: String, workspaceId: String?): Boolean =
            NavigationKeys.refreshing(navigationSnapshot(snapshot), connectionId, workspaceId)

        /** The latest failure of a sidebar job, discovery first (without it nothing below can load). */
        fun navigationError(snapshot: SyncSnapshot, connectionId: String, workspaceId: String?): String? =
            NavigationKeys.error(navigationSnapshot(snapshot), connectionId, workspaceId)
    }
}

/** Cooperative cancellation point inside a job (the analogue of `Task.checkCancellation()`). */
internal suspend fun checkCancelled() = currentCoroutineContext().ensureActive()
