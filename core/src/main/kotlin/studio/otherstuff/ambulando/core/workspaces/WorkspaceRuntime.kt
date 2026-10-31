package studio.otherstuff.ambulando.core.workspaces

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.chat.ComposerUploadTransfer
import studio.otherstuff.ambulando.core.chat.ComposerUploads
import studio.otherstuff.ambulando.core.chat.OutboxSender
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.docs.DocumentSaveRunner
import studio.otherstuff.ambulando.core.profiles.ProfileSource
import studio.otherstuff.ambulando.core.profiles.ProfileSync
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.markWorkspaceAccessRemoved
import studio.otherstuff.ambulando.core.store.resetInterruptedSends
import studio.otherstuff.ambulando.core.store.resetInterruptedWrites
import studio.otherstuff.ambulando.core.store.replaceWorkspaces
import studio.otherstuff.ambulando.core.sync.DiscoveryHook
import studio.otherstuff.ambulando.core.sync.LiveStream
import studio.otherstuff.ambulando.core.sync.ScopedSyncScheduler
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceActivity
import studio.otherstuff.ambulando.core.sync.WorkspaceEventStreamTransport
import studio.otherstuff.ambulando.core.sync.WorkspaceHttpTransport
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.sync.WriteSender
import studio.otherstuff.ambulando.core.sync.catchUpEventsAndWait
import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpResponseHead
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.ObjectTransfers
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import java.io.File
import java.net.URI

/** A request that is not for this runtime's exact origin, workspace and application was refused before it was sent. */
class BoundaryViolation(detail: String) : Exception("Refused a request outside this workspace's boundary ($detail).")

/**
 * Last line of defence for "never send one workspace's request with another connection or signer": every Tower API request
 * a runtime makes must go to the boundary's own origin, name the boundary's own workspace in its path and carry the
 * boundary's own application header. Presigned object-store URLs (other hosts) are handled by the transfer policy.
 */
class BoundaryGuardTransport(private val base: HttpTransport, private val boundary: WorkspaceBoundary) : HttpTransport {
    private val origin = TowerOrigin(boundary.origin)

    override suspend fun send(request: HttpRequest): HttpResponse {
        check(request.url, request.header(TowerClient.APP_NPUB_HEADER), request.method)
        return base.send(request)
    }

    internal fun check(url: String, appHeader: String?, method: String = "GET") {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw BoundaryViolation("unreadable URL")
        val path = uri.rawPath ?: ""
        if (!path.startsWith("/api/v4/")) return
        if (uri.host?.lowercase() != origin.host || (uri.port.takeIf { it >= 0 && it != 443 }) != origin.port) throw BoundaryViolation("origin")
        // Revalidating access is the one read of the whole list; it names no other boundary and writes nothing.
        if (path == PREFIX && method != "GET") throw BoundaryViolation("workspace list")
        if (path.startsWith("$PREFIX/")) {
            val workspace = path.removePrefix("$PREFIX/").substringBefore('/')
            if (!workspace.equals(boundary.workspaceId, ignoreCase = true)) throw BoundaryViolation("workspace")
        }
        if (appHeader != null && appHeader != boundary.appNpub) throw BoundaryViolation("application")
    }

    private companion object { const val PREFIX = "/api/v4/flightdeck-pg/workspaces" }
}

class BoundaryGuardEventTransport(private val base: EventStreamTransport, private val boundary: WorkspaceBoundary) : EventStreamTransport {
    private val guard = BoundaryGuardTransport(object : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse = throw UnsupportedOperationException()
    }, boundary)

    override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> {
        guard.check(request.url, request.header(TowerClient.APP_NPUB_HEADER))
        return base.open(request)
    }
}

/** Transfer keys (storage object ids) are qualified by boundary, so equal ids on two Towers never share a transfer record. */
class BoundaryTransfers(private val base: ObjectTransfers, private val boundary: WorkspaceBoundary) : ObjectTransfers {
    override suspend fun upload(key: String, url: String, bytes: ByteArray, contentType: String, towerHost: String?) =
        base.upload("${boundary.id}:$key", url, bytes, contentType, towerHost)
}

/** What a runtime needs from the host app; one value shared by every runtime. */
class RuntimeEnvironment(
    val engine: SyncEngine,
    val identity: IdentityService,
    val transport: HttpTransport,
    val events: EventStreamTransport,
    val transfers: ObjectTransfers?,
    /** Staged chat attachments, staged write uploads and composer uploads (file names are unique ids; shared directories). */
    val attachmentsStaging: File,
    val uploadsStaging: File,
    val composerStaging: File,
    val profileSource: ProfileSource?,
    val mainDispatcher: CoroutineDispatcher,
    val discovery: DiscoveryHook,
    /** Called by a runtime's write queue when Tower accepted a "create and link" document (see [WriteSender.afterWikiCreate]). */
    val afterWikiCreate: (suspend (WorkspaceRuntime, ConnectionRecord, studio.otherstuff.ambulando.core.store.WriteItem, studio.otherstuff.ambulando.core.transport.DocRecord) -> Unit)? = null,
)

/**
 * One exact account/workspace/application boundary with its independently owned sync lifecycle: its store partition,
 * cursor and live feed, foreground stream, scoped scheduler, access state, outbox, write sender, document saver and
 * composer uploads. Selecting another workspace in the UI changes which runtime the screens read; it never stops the others.
 *
 * Two gates: [activity] covers reads and the stream, [writeActivity] covers everything that sends. Resume opens reads first,
 * revalidates access and catches up the saved cursor, and opens the write gate only afterwards, so queued work is never
 * sent before the boundary has been shown to still be this account's.
 */
class WorkspaceRuntime(
    val boundary: WorkspaceBoundary,
    val connection: ConnectionRecord,
    workspace: WorkspaceRecord,
    val store: LocalStore,
    private val env: RuntimeEnvironment,
) {
    @Volatile var workspace: WorkspaceRecord = workspace
        private set

    val activity = WorkspaceActivity(enabled = false)
    val writeActivity = WorkspaceActivity(enabled = false)
    val engine = ScopedSyncScheduler(env.engine, boundary.id, boundary.connectionNamespace, activity)

    /** Reads and everything signed for this boundary only; closed while the workspace is paused. */
    val readTransport: HttpTransport = WorkspaceHttpTransport(BoundaryGuardTransport(env.transport, boundary), activity)
    /** Queue senders: also closed until access is revalidated; a closed gate cancels (the queued row waits). */
    private val writeTransport: HttpTransport = WorkspaceHttpTransport(readTransport, writeActivity)
    /** Direct writes from screens (canvas, board, scopes, task tree): same gates, but a closed one is a visible failure. */
    val directTransport: HttpTransport = WorkspaceHttpTransport(readTransport, writeActivity) { studio.otherstuff.ambulando.core.sync.WritesNotReady() }
    private val transfers: ObjectTransfers? = env.transfers?.let { BoundaryTransfers(it, boundary) }

    val sync = WorkspaceSync(store, engine, env.identity, readTransport, transfers = transfers, discoveryHook = env.discovery)
    val stream = LiveStream(sync, WorkspaceEventStreamTransport(BoundaryGuardEventTransport(env.events, boundary), activity))
    val outbox = OutboxSender(store, engine, env.identity, writeTransport, env.attachmentsStaging, transfers, boundary = boundary)
    val writes = WriteSender(store, engine, env.identity, writeTransport, env.uploadsStaging, transfers, boundary = boundary)
    val documents = DocumentSaveRunner(store, engine, env.identity, writeTransport, transfers)
    init {
        env.afterWikiCreate?.let { hook -> writes.afterWikiCreate = { c, item, created -> hook(this, c, item, created) } }
    }
    val profileSync: ProfileSync? = env.profileSource?.let { ProfileSync(store, engine, it) }
    private val uploadTransfer = ComposerUploadTransfer(engine, env.identity, writeTransport, transfers)
    val uploads = ComposerUploads(store, env.composerStaging, env.mainDispatcher) { job -> uploadTransfer.upload(job, connection) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    /** Makes "close both gates and forget validation" and "validated, so open the write gate" one step each: neither can interleave. */
    private val gate = Any()
    private var starting: Job? = null
    @Volatile var validated = false
        private set
    @Volatile var enabled = false
        private set
    /** Set first by [close]; a runtime that was released for good (Disconnect, teardown) can never be started or opened again. */
    @Volatile private var closed = false

    /** True once this runtime's access was confirmed after the latest resume (write gate open). */
    val canSend: Boolean get() = writeActivity.isOpen

    /**
     * Resume (or first start): open the read gate, revalidate access and catch the saved event cursor up, then open the
     * write gate and kick the senders. Returns immediately; [onValidated] runs when the write gate opens.
     */
    suspend fun start(foreground: Boolean, force: Boolean = false, onValidated: suspend () -> Unit = {}) = lifecycle.withLock {
        // A late enable can still hold this object after Disconnect closed it; it must not reopen any gate.
        if (closed) return@withLock
        // Repeated catalog changes must not restart a validation that is already under way.
        if (!force && enabled && activity.isOpen && starting?.isActive == true) return@withLock
        enabled = true
        if (workspace.accessRemoved) { activity.pause(); writeActivity.pause(); return@withLock }
        if (!activity.isOpen) synchronized(gate) { if (!closed) activity.resume() }
        val lease = activity.currentGeneration
        starting?.cancel()
        starting = scope.launch {
            try {
                env.identity.requireReader(connection.signerNpub)
                if (!validated) {
                    // A cached membership is not enough: the exact workspace and application must be listed now, and the saved
                    // cursor caught up, before anything durable is sent.
                    val listed = engine.perform("resume-access", SyncPriority.VISIBLE) { sync.api(connection).workspaces() }
                    activity.check(lease)
                    if (listed.none { it.id == workspace.id && it.appNpub == workspace.appNpub }) {
                        updateWorkspace(workspace.copy(accessRemoved = true))
                        env.discovery.discover(connection, SyncPriority.BACKGROUND, true)
                        return@launch
                    }
                    sync.refreshWorkspace(connection, workspace, SyncPriority.VISIBLE)
                    val caught = sync.catchUpEventsAndWait(connection, workspace, priority = SyncPriority.VISIBLE)
                    if (!activity.isCurrent(lease)) return@launch
                    if (caught.accessLost) { closeAccess(); return@launch }
                    if (!caught.complete) return@launch
                    // A pause that landed after the checks above must not be undone by a stale "validated".
                    synchronized(gate) { if (!activity.isCurrent(lease)) return@launch; validated = true }
                }
                openWrites(lease)
                onValidated()
                if (foreground) stream.start(connection, workspace)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // Offline or locked: cached navigation stays usable; queued sends wait for validation, which a later
                // resume, refresh or network return repeats.
            }
        }
    }

    /** Waits for the latest [start] to finish (background windows and tests). */
    suspend fun awaitStarted() { starting?.join() }

    /**
     * Opens the write gate once this boundary is validated and the identity that owns it is unlocked, then resumes the durable
     * queues. Called after validation and again on unlock (reads can be validated with the read-only credential while locked).
     */
    suspend fun resumeWrites() {
        if (closed || !validated || !activity.isOpen || workspace.accessRemoved) return
        openWrites(activity.currentGeneration)
    }

    private suspend fun openWrites(lease: Long) {
        val state = env.identity.state()
        if (state !is IdentityState.Unlocked || state.npub != connection.signerNpub) return
        if (closed || !activity.isCurrent(lease) || !validated) return
        if (!writeActivity.isOpen) {
            store.writer.resetInterruptedSends(); store.writer.resetInterruptedWrites()
            // Re-checked together with the pause: a pause since the check above keeps the write gate closed.
            synchronized(gate) { if (!closed && activity.isCurrent(lease) && validated) writeActivity.resume() }
        }
        kickSenders()
    }

    suspend fun kickSenders() {
        if (!writeActivity.isOpen) return
        outbox.kick(connection)
        writes.kick(connection)
    }

    /** Opens or closes the stream with the app's foreground state (never while paused, locked or access is gone). */
    suspend fun setForeground(foreground: Boolean) {
        if (!foreground) { stream.stop(); return }
        if (activity.isOpen && validated && !workspace.accessRemoved) stream.start(connection, workspace)
    }

    /**
     * The catalog's authoritative answer for this boundary. Loss closes the write gate at once and every later read, stream
     * and lease; records already received stay readable and labelled (Android's documented non-destructive behaviour), and
     * pending local work stays queued for recovery. Regained access reopens through [start].
     */
    suspend fun updateWorkspace(record: WorkspaceRecord) {
        val lost = record.accessRemoved && !workspace.accessRemoved
        val regained = !record.accessRemoved && workspace.accessRemoved
        workspace = record
        if (closed) return
        if (lost) closeAccess()
        // Listed again: the partition's workspace row is unflagged, but nothing is sent until a resume revalidates it.
        if (regained) withContext(NonCancellable) { runCatching { store.writer.replaceWorkspaces(connection.id, listOf(record)) } }
    }

    /** Closes both gates synchronously (no coroutine hop), so nothing already on its way can outrun the pause. */
    fun closeGates() = synchronized(gate) { writeActivity.pause(); activity.pause(); validated = false }

    private suspend fun closeAccess() {
        synchronized(gate) { writeActivity.pause(); validated = false }
        engine.pause()
        stream.stop()
        documents.forgetAll()
        withContext(NonCancellable) { runCatching { store.writer.markWorkspaceAccessRemoved(connection.id, workspace.id) } }
    }

    /**
     * Pause: both gates close synchronously before any coroutine hop, then queued and running work, retries, reads, the
     * stream and document leases of this boundary stop. Data, drafts and the outbox stay; other workspaces continue.
     */
    suspend fun pause() = lifecycle.withLock {
        closeGates()
        enabled = false
        starting?.cancel(); starting = null
        engine.pause()
        stream.stop()
        documents.forgetAll()
    }

    /** Releases everything for good (Disconnect or process teardown). Does not delete data; the pool retires files. */
    suspend fun close() {
        synchronized(gate) { closed = true; writeActivity.pause(); activity.pause(); validated = false }
        pause()
        stream.close()
        scope.coroutineContext[Job]?.cancel()
    }
}
