package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.deck.PersonalAgentLauncher
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import java.io.File
import java.util.UUID

/** A partition could not be opened or imported. Nothing was served from the legacy catalog instead (fail closed). */
class PartitionUnavailable(val boundaryId: String, cause: Throwable) : Exception("This workspace's local data could not be opened. Your data is kept; try again.", cause)

/**
 * The legacy database is the connection / discovery / navigation catalog only. Content, drafts, the outbox and the write
 * queue live in immutable-boundary partitions: one SQLite file per normalised origin, signer, application namespace and
 * workspace. See `docs/multi-workspace-storage.md` for the ownership and recovery policy.
 */
class WorkspaceStorePool(private val catalog: LocalStore, val directory: File) {
    val legacyBackup: File = File(directory, LEGACY_BACKUP_NAME)

    private val lock = Mutex()
    private val stores = HashMap<String, Opened>()
    private val removing = HashSet<String>()
    /** Connections retired in this process: a late enable (a listing applied after Disconnect) must not reopen a partition for them. */
    private val retiredConnections = HashSet<String>()
    private var backupReady = false

    private class Opened(val store: CompletableDeferred<LocalStore>, val connectionId: String, val connectionNamespace: String)

    /** Boundaries whose partition is currently open. */
    suspend fun openBoundaryIds(): Set<String> = lock.withLock { stores.keys.toSet() }

    /**
     * The partition of [workspace] under [connection], opened and (once) filled from the frozen legacy backup. Failure to
     * open or import throws [PartitionUnavailable]; there is no fallback to the catalog's content or queues.
     */
    suspend fun store(connection: ConnectionRecord, workspace: WorkspaceRecord): LocalStore {
        val boundary = WorkspaceBoundary.of(connection, workspace)
        val pending: CompletableDeferred<LocalStore>
        val mine: Boolean
        lock.withLock {
            if (boundary.connectionNamespace in removing || connection.id in retiredConnections) throw kotlinx.coroutines.CancellationException("Connection is being removed")
            val existing = stores[boundary.id]
            if (existing != null) {
                if (existing.connectionId != connection.id) throw SqlException("Use the catalog's canonical connection for this workspace")
                pending = existing.store; mine = false
            } else {
                pending = CompletableDeferred()
                stores[boundary.id] = Opened(pending, connection.id, boundary.connectionNamespace)
                mine = true
            }
        }
        if (!mine) return pending.await()
        try {
            val store = open(connection, workspace, boundary)
            pending.complete(store)
            return store
        } catch (t: Throwable) {
            lock.withLock { if (stores[boundary.id]?.store === pending) stores.remove(boundary.id) }
            val failure = if (t is kotlinx.coroutines.CancellationException) t else PartitionUnavailable(boundary.id, t)
            pending.completeExceptionally(failure)
            throw failure
        }
    }

    private suspend fun open(connection: ConnectionRecord, workspace: WorkspaceRecord, boundary: WorkspaceBoundary): LocalStore {
        ensureBackup()
        var store = withContext(Dispatchers.IO) { LocalStore(File(directory, boundary.id)) }
        try {
            var canonical = store.writer.saveConnection(connection)
            if (canonical.id != connection.id) {
                // An alias of a live connection is refused (private state is never stranded behind another local id). A partition
                // whose connection the catalog no longer has was left behind by a Disconnect that was interrupted after the
                // catalog dropped it: a later connection to the same Tower and identity never adopts its queue; it is retired.
                if (catalog.reader.connections().any { it.id == canonical.id }) throw SqlException("Workspace partition belongs to another local connection identifier")
                store.close()
                withContext(Dispatchers.IO) { retire(boundary.id, File(File(directory, RETIRED_DIRECTORY), UUID.randomUUID().toString())) }
                store = withContext(Dispatchers.IO) { LocalStore(File(directory, boundary.id)) }
                canonical = store.writer.saveConnection(connection)
                if (canonical.id != connection.id) throw SqlException("Workspace partition belongs to another local connection identifier")
            }
            store.writer.replacePartitionWorkspace(connection.id, workspace)
            store.writer.importLegacyPartition(legacyBackup, connection, workspace, boundary)
            return store
        } catch (t: Throwable) {
            runCatching { store.close() }
            throw t
        }
    }

    private fun retire(name: String, retired: File) {
        val candidate = File(directory, name)
        if (!candidate.exists()) return
        retired.mkdirs()
        if (!candidate.renameTo(File(retired, name))) throw SqlException("Could not retire the partition of a removed connection")
    }

    /**
     * One consistent, immutable copy of the catalog, including committed WAL pages (`VACUUM INTO`), written to a temporary
     * file and moved into place without replacing an existing backup.
     */
    private suspend fun ensureBackup() {
        lock.withLock {
            if (backupReady) return
            if (!legacyBackup.exists()) {
                directory.mkdirs()
                discardAbandonedBackups()
                val temporary = File(directory, "backup-${UUID.randomUUID()}.sqlite")
                try {
                    catalog.writer.exclusive { run("VACUUM INTO ?", temporary.path) }
                    withContext(Dispatchers.IO) {
                        // An existing backup always wins (including a concurrent pool's completed snapshot).
                        if (!legacyBackup.exists()) {
                            if (!temporary.renameTo(legacyBackup) && !legacyBackup.exists()) throw SqlException("Could not publish the legacy backup")
                        }
                    }
                } finally {
                    for (suffix in listOf("", "-wal", "-shm")) File(temporary.path + suffix).delete()
                }
            }
            backupReady = true
        }
    }

    /** A copy whose process ended before it was published is a private duplicate of the catalog nobody will use: remove it. */
    private suspend fun discardAbandonedBackups() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - ABANDONED_BACKUP_MILLIS
        for (file in directory.listFiles().orEmpty()) {
            if (file.isFile && file.name.startsWith("backup-") && file.lastModified() < cutoff) file.delete()
        }
    }

    /**
     * Explicit Disconnect / account removal only (never workspace pause). Closes the open handles of this origin and signer,
     * runs [dropFromCatalog] (the catalog forgets the connection), then moves their directories under `Retired/` for local
     * recovery, so a reconnect gets a fresh partition and cannot resurrect the old account's queue. The connection is
     * forgotten before anything is moved: an interruption leaves an unreferenced partition (retired when next met), never a
     * connection whose queue could be imported again from the frozen backup. Other connections' partitions and the legacy
     * backup stay where they are.
     */
    suspend fun remove(connection: ConnectionRecord, dropFromCatalog: suspend () -> Unit = {}) {
        val namespace = WorkspaceBoundary.connectionNamespace(connection)
        val origin = TowerOrigin(connection.origin).url
        val toClose = lock.withLock {
            if (!removing.add(namespace)) throw kotlinx.coroutines.CancellationException("Already being removed")
            stores.filterValues { it.connectionNamespace == namespace }.also { picked -> picked.keys.forEach { stores.remove(it) } }.values.toList()
        }
        try {
            for (opened in toClose) {
                val store = runCatching { opened.store.await() }.getOrNull() ?: continue
                runCatching { store.close() }
            }
            dropFromCatalog()
            lock.withLock { retiredConnections.add(connection.id) }
            withContext(Dispatchers.IO) {
                val retired = File(File(directory, RETIRED_DIRECTORY), UUID.randomUUID().toString())
                for (candidate in directory.listFiles().orEmpty()) {
                    val name = candidate.name
                    if (!candidate.isDirectory || name.length != 64 || !name.all { it in '0'..'9' || it in 'a'..'f' }) continue
                    val database = File(candidate, ProductionStorage.DATABASE_FILE_NAME)
                    if (!database.exists()) continue
                    val matches = runCatching {
                        SqlConnection.open(database.path, readOnly = true).use { db ->
                            db.first("SELECT 1 FROM connections WHERE origin = ? AND signer_npub = ? LIMIT 1", origin, connection.signerNpub) { true } == true
                        }
                    }.getOrDefault(false)
                    if (!matches) continue
                    retire(name, retired)
                }
            }
        } finally {
            lock.withLock { removing.remove(namespace) }
        }
    }

    suspend fun close() {
        val all = lock.withLock { stores.values.toList().also { stores.clear() } }
        for (opened in all) runCatching { opened.store.await().close() }
    }

    companion object {
        const val LEGACY_BACKUP_NAME = "legacy-cache-v1.sqlite"
        /** Another process may still be copying; only a temporary copy this old was certainly abandoned. */
        internal const val ABANDONED_BACKUP_MILLIS = 10 * 60 * 1000L
        const val RETIRED_DIRECTORY = "Retired"
        const val MIGRATION_MARKER = "partition.migration.v1"
        const val LEGACY_STATE = "partition.legacy-cache"
        const val IMPORTED = "imported"
        const val UNATTRIBUTED = "unattributed-source-preserved"

        /** The workspace-only content tables. Imported only when exactly one connection of the backup listed the workspace. */
        internal val contentTables = listOf(
            "scopes", "channels", "messages", "threads", "members", "channel_sync",
            "tasks", "task_relationships", "task_relationship_members", "comments", "docs", "doc_bodies", "files", "folders", "view_states", "reactions",
            "list_sync", "context_components", "context_references", "canvases", "canvas_snapshots", "board_scenes", "board_links",
            "workspace_agents", "agent_activities",
        )

        /** Connection-qualified private tables. Imported only for the exact connection and workspace (and fields they carry). */
        internal val privateTables = listOf("drafts", "doc_drafts", "outbox", "writes", "personal_wapps", "pins", "notification_items", "notification_sync")

        /** Connection-wide (not workspace-qualified) private caches, imported for the exact connection. */
        internal val connectionTables = listOf("profiles")
    }
}

/** The partition holds exactly one workspace row: the one this partition serves. */
internal suspend fun StoreWriter.replacePartitionWorkspace(connectionId: String, workspace: WorkspaceRecord) = write {
    db.run(
        """INSERT INTO workspaces(connection_id, workspace_id, app_npub, label, slug, tower_base_url, role, owner_npub, raw, synced_at, access_removed)
           VALUES(?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(connection_id, workspace_id) DO UPDATE SET app_npub=excluded.app_npub, label=excluded.label, slug=excluded.slug,
             tower_base_url=excluded.tower_base_url, role=excluded.role, owner_npub=excluded.owner_npub, raw=excluded.raw,
             synced_at=excluded.synced_at, access_removed=excluded.access_removed""",
        connectionId, workspace.id, workspace.appNpub, workspace.label, workspace.slug, workspace.towerBaseURL, workspace.role,
        workspace.ownerNpub, canonical(workspace.raw), StoreTime.now(), workspace.accessRemoved,
    )
    emit(StoreTable.WORKSPACES)
}

/**
 * Fills a new partition from the frozen backup, once. Rows move only with proven ownership:
 * - workspace-only content: when the backup lists the workspace under exactly one connection and that connection's origin,
 *   signer and the workspace's application match this boundary;
 * - connection-qualified private rows: the exact connection and workspace (and any origin, signer or application field the
 *   row carries); rows without an application field also need the matching discovery row;
 * - app state: only the recognised, exactly qualified navigation and cursor keys.
 * Outbox and write rows, request blobs and ids, ambiguity flags, staged references and draft bytes are copied unchanged;
 * interrupted sends are recovered afterwards by the existing idempotent paths. Ambiguous rows stay in the backup. The
 * import and its completion marker are one transaction: an interrupted import rolls back; a completed one never repeats.
 */
internal suspend fun StoreWriter.importLegacyPartition(source: File, connection: ConnectionRecord, workspace: WorkspaceRecord, boundary: WorkspaceBoundary) {
    val done = exclusive { textValue("SELECT value FROM app_state WHERE key = ?", WorkspaceStorePool.MIGRATION_MARKER) }
    if (done != null) {
        if (done != boundary.id) throw SqlException("Workspace partition boundary mismatch")
        return
    }
    exclusive {
        run("ATTACH DATABASE ? AS legacy", source.path)
        try {
            transaction {
                val owners = longValue("SELECT COUNT(*) FROM legacy.workspaces WHERE workspace_id = ?", workspace.id) ?: 0L
                val matches = first(
                    """SELECT 1 FROM legacy.workspaces w JOIN legacy.connections c ON c.id = w.connection_id
                       WHERE w.workspace_id = ? AND w.connection_id = ? AND w.app_npub = ? AND c.origin = ? AND c.signer_npub = ?""",
                    workspace.id, connection.id, workspace.appNpub, boundary.origin, connection.signerNpub,
                ) { true } == true
                val unique = matches && owners == 1L
                val tables = WorkspaceStorePool.contentTables + WorkspaceStorePool.privateTables + WorkspaceStorePool.connectionTables
                for (table in tables) {
                    val legacyColumns = query("PRAGMA legacy.table_info($table)") { it.text(1)!! }
                    if (legacyColumns.isEmpty()) continue
                    val mainColumns = query("PRAGMA main.table_info($table)") { it.text(1)!! }.toSet()
                    val columns = legacyColumns.filter { it in mainColumns }
                    val predicate = ArrayList<String>()
                    val values = ArrayList<Any?>()
                    if ("workspace_id" in legacyColumns) { predicate.add("workspace_id = ?"); values.add(workspace.id) }
                    if ("connection_id" in legacyColumns) {
                        predicate.add("connection_id = ?"); values.add(connection.id)
                        // Outbox and writes are independently application-qualified; the other private tables need discovery provenance.
                        if ("app_npub" !in legacyColumns && !matches) continue
                    } else if (!unique) continue
                    if ("app_npub" in legacyColumns) { predicate.add("app_npub = ?"); values.add(workspace.appNpub) }
                    if ("origin" in legacyColumns) { predicate.add("origin = ?"); values.add(boundary.origin) }
                    if ("signer_npub" in legacyColumns) { predicate.add("signer_npub = ?"); values.add(connection.signerNpub) }
                    if (predicate.isEmpty()) continue
                    val names = columns.joinToString(",") { "\"$it\"" }
                    run("INSERT OR IGNORE INTO $table($names) SELECT $names FROM legacy.$table WHERE ${predicate.joinToString(" AND ")}", *values.toTypedArray())
                }
                if (matches) {
                    val qualified = listOf(
                        "nav-stacks:${connection.id}:${workspace.id}",
                        WorkContext.stateKey(connection.id, workspace.id),
                        "collapsed:${connection.id}:${workspace.id}",
                        PersonalAgentLauncher.stateKey(connection.id, workspace.id),
                        // The cursor says how much of the cached content is current, so it moves only with that content.
                    ) + (if (unique) listOf(WorkspaceSync.scopeOrderKey(workspace.id), StoreStateKeys.eventCursor(connection.id, workspace.id)) else emptyList())
                    for (key in qualified) run("INSERT OR IGNORE INTO app_state(key,value) SELECT key,value FROM legacy.app_state WHERE key = ?", key)
                    // Review journals (preserved text, merge in progress) and upload receipts belong to the draft they are keyed by
                    // (connection, workspace, document); the draft moved above, so they move with it.
                    val draftPrefix = "${connection.id}:${workspace.id}:"
                    for (base in listOf(DocumentReviewCodec.key(""), DocumentReviewCodec.corruptKey(""), DocumentReviewCodec.UPLOAD_RECEIPT_PREFIX)) {
                        val prefix = base + draftPrefix
                        run("INSERT OR IGNORE INTO app_state(key,value) SELECT key,value FROM legacy.app_state WHERE substr(key, 1, ?) = ?", prefix.length, prefix)
                    }
                }
                run("INSERT INTO app_state(key,value) VALUES(?,?)", WorkspaceStorePool.MIGRATION_MARKER, boundary.id)
                run("INSERT INTO app_state(key,value) VALUES(?,?)", WorkspaceStorePool.LEGACY_STATE, if (unique) WorkspaceStorePool.IMPORTED else WorkspaceStorePool.UNATTRIBUTED)
            }
        } finally {
            runCatching { execute("DETACH DATABASE legacy") }
        }
    }
}
