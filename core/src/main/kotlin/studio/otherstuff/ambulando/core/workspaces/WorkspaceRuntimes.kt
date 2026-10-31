package studio.otherstuff.ambulando.core.workspaces

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WorkspaceStorePool
import studio.otherstuff.ambulando.core.store.removeConnection
import studio.otherstuff.ambulando.core.sync.DiscoveryHook
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/**
 * The runtimes of every workspace the person has enabled, across all connections. The catalog store (connections,
 * discovered workspace lists, preferences) is the only thing shared; each runtime owns a partition and its own sync
 * lifecycle, so enabling, pausing, losing access to or disconnecting one never stops another.
 *
 * Discovery is the connection catalog's job: it stores the authoritative list in the catalog and hands each runtime its own
 * boundary's answer. A runtime that is not listed any more closes its write gate and every later read.
 */
class WorkspaceRuntimes(
    val catalog: LocalStore,
    val pool: WorkspaceStorePool,
    foreground: Boolean = true,
    private val environment: (DiscoveryHook) -> RuntimeEnvironment,
) : DiscoveryHook {
    private val env = environment(this)
    private val catalogSync = WorkspaceSync(catalog, env.engine, env.identity, env.transport, transfers = env.transfers)
    private val lock = Mutex()
    private val all = LinkedHashMap<String, WorkspaceRuntime>()
    /** Connections being (or already) disconnected: no runtime may be created for them, however late a listing arrives. */
    private val disconnecting = HashSet<String>()
    private val _runtimes = MutableStateFlow<Map<String, WorkspaceRuntime>>(emptyMap())
    /** Every runtime that exists (enabled or paused), by boundary id. */
    val runtimes: StateFlow<Map<String, WorkspaceRuntime>> = _runtimes.asStateFlow()
    @Volatile var foreground = foreground
        private set

    fun runtime(boundaryId: String): WorkspaceRuntime? = _runtimes.value[boundaryId]

    /** Creates the runtime if needed (opening its partition) and starts it. Fails closed if the partition cannot open. */
    suspend fun enable(connection: ConnectionRecord, workspace: WorkspaceRecord, force: Boolean = false): WorkspaceRuntime {
        val boundary = WorkspaceBoundary.of(connection, workspace)
        val runtime = lock.withLock {
            if (connection.id in disconnecting) throw kotlinx.coroutines.CancellationException("Connection is being disconnected")
            all[boundary.id] ?: WorkspaceRuntime(boundary, connection, workspace, pool.store(connection, workspace), env).also {
                all[boundary.id] = it; _runtimes.value = all.toMap()
            }
        }
        runtime.updateWorkspace(workspace)
        runtime.start(foreground, force)
        return runtime
    }

    /** Pause: gates close, work stops, data stays. */
    suspend fun disable(boundaryId: String) { runtime(boundaryId)?.pause() }

    /** Lock or process teardown: every runtime pauses; the persisted enabled set is untouched, so unlock resumes it. */
    suspend fun pauseAll() { _runtimes.value.values.forEach { it.pause() } }

    suspend fun setForeground(value: Boolean) {
        foreground = value
        _runtimes.value.values.forEach { it.setForeground(value) }
    }

    /** The catalog's discovery job (see [WorkspaceSync.discoverListing]); runtimes of this connection receive their record. */
    override suspend fun discover(connection: ConnectionRecord, priority: SyncPriority, force: Boolean) {
        catalogSync.discoverListing(connection, priority, force) { listing -> applyListing(connection, listing) }
    }

    private suspend fun applyListing(connection: ConnectionRecord, listing: List<WorkspaceRecord>) {
        for (runtime in _runtimes.value.values) {
            if (runtime.connection.id != connection.id) continue
            val listed = listing.firstOrNull { it.id == runtime.workspace.id && it.appNpub == runtime.workspace.appNpub }
            val retry = !runtime.validated && runtime.enabled && listed != null && runtime.activity.isOpen
            runtime.updateWorkspace(listed ?: runtime.workspace.copy(accessRemoved = true))
            if (retry && listed != null) runtime.start(foreground)
        }
    }

    /**
     * Explicit Disconnect. Jobs, observers and leases of the connection's runtimes stop first; the catalog then forgets the
     * connection and only after that are its partitions retired (moved aside for recovery), so no interruption leaves a
     * connection that could be opened again over its old queue. A reconnect cannot see the old queue.
     */
    suspend fun removeConnection(connection: ConnectionRecord) {
        val namespace = WorkspaceBoundary.connectionNamespace(connection)
        val doomed = lock.withLock {
            disconnecting.add(connection.id)
            all.values.filter { it.boundary.connectionNamespace == namespace }.also { picked -> picked.forEach { all.remove(it.boundary.id) }; _runtimes.value = all.toMap() }
        }
        doomed.forEach { it.close() }
        env.engine.cancelPrefix("discover:${connection.id}")
        try {
            pool.remove(connection) { catalog.writer.removeConnection(connection.id) }
        } catch (t: Throwable) {
            // Nothing was dropped yet when the catalog write failed: the connection stays usable and Disconnect can be retried.
            if (t !is kotlinx.coroutines.CancellationException) lock.withLock { disconnecting.remove(connection.id) }
            throw t
        }
    }

    /** Returns when no enabled runtime has queued or running work (background windows). */
    suspend fun waitUntilIdle() { env.engine.waitUntilIdle() }

    suspend fun close() {
        _runtimes.value.values.forEach { it.close() }
        pool.close()
    }
}
