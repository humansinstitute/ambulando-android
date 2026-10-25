package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.TowerJson
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.TowerTime
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.sortedJson
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** One Tower connection: an origin and the identity (signer) that uses it. Every actor-private row hangs off its id. */
data class ConnectionRecord(
    val id: String,
    val origin: String,
    val signerNpub: String,
    val towerNpub: String?,
    val createdAt: String,
) {
    companion object {
        fun create(origin: TowerOrigin, signerNpub: String, towerNpub: String?, id: String = UUID.randomUUID().toString().lowercase()) =
            ConnectionRecord(id, origin.url, signerNpub, towerNpub, StoreTime.now())
    }
}

/** `app_state` keys the store itself writes (the workspace-list key is `WorkspaceSelection.listedKey`). */
object StoreStateKeys {
    /** Personal scope ordering within a workspace. */
    fun scopeOrder(workspaceId: String): String = "scope-order:$workspaceId"
    /** Per-connection live event cursor of one workspace. */
    fun eventCursor(connectionId: String, workspaceId: String): String = "events.cursor:$connectionId:$workspaceId"
}

internal object StoreTime {
    private val seconds: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /** Second precision, as the iOS store writes `synced_at` / `updated_at` bookkeeping. */
    fun now(): String = seconds.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))

    /** Millisecond precision (draft timestamps). */
    fun precise(): String = TowerTime.now()

    fun parse(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/** Canonical stored form of Tower JSON: keys sorted, so identical payloads compare equal in SQL. */
internal fun canonical(raw: JsonObject): String = sortedJson(raw).toString()

internal fun parseRaw(text: String?): JsonObject =
    text?.let { runCatching { TowerJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

/**
 * Guard shared by every record upsert: never let an older (row_version, updated_at) overwrite a newer row, and skip
 * identical payloads so re-syncing unchanged data produces no change notifications. On a flagged table a row marked
 * `access_removed` is always rewritten (which clears the flag: Tower showed it again).
 */
internal fun newerGuard(table: String, flagged: Boolean = table in Schema.flaggedTables): String {
    val changed = if (flagged) "(excluded.raw IS NOT $table.raw OR $table.access_removed <> 0)" else "excluded.raw IS NOT $table.raw"
    return "WHERE (excluded.row_version > $table.row_version OR (excluded.row_version = $table.row_version AND " +
        "excluded.updated_at >= $table.updated_at)) AND $changed"
}

/** The state of one writer step: its connection and the change keys it reports after commit. */
class WriteTx internal constructor(val db: SqlConnection) {
    internal val keys = LinkedHashSet<ChangeKey>()
    fun emit(table: StoreTable, workspaceId: String? = null) { keys.add(ChangeKey(table, workspaceId)) }
    fun emit(keys: Collection<ChangeKey>) { this.keys.addAll(keys) }
}

/**
 * The only writer: one serialized connection on a single-threaded dispatcher, so writes never interleave. Every public
 * method is one short IMMEDIATE transaction (callers keep batches ≤ 500 rows so a reader is never starved; WAL readers
 * are never blocked by the writer at all). Change keys are recorded on the coalescer right after the commit.
 *
 * The API is split by area as extension functions (chat, work, writes, surfaces, profiles, live), as in the iOS store.
 */
class StoreWriter internal constructor(path: String, val changes: ChangeCoalescer) : AutoCloseable {
    internal val db: SqlConnection = SqlConnection.open(path)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    init { Schema.migrate(db) }

    /** Runs `body` as one transaction on the writer connection, then reports the keys it emitted. */
    internal suspend fun <T> write(body: WriteTx.() -> T): T = withContext(dispatcher) {
        val tx = WriteTx(db)
        val result = db.transaction { tx.body() }
        changes.record(tx.keys)
        result
    }

    suspend fun schemaVersion(): Int = withContext(dispatcher) { db.userVersion }

    /** Notifies observers that data outside SQLite changed (for example a finished download in the file cache). */
    suspend fun touch(keys: Set<ChangeKey>) = withContext(dispatcher) { changes.record(keys) }

    override fun close() = db.close()
}

/**
 * Read-only connection on its own single-threaded dispatcher. Each read runs in one DEFERRED transaction, so it sees one
 * consistent WAL snapshot while the writer commits.
 */
class StoreReader internal constructor(path: String) : AutoCloseable {
    internal val db: SqlConnection = SqlConnection.open(path, readOnly = true)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    internal suspend fun <T> read(body: SqlConnection.() -> T): T = withContext(dispatcher) { db.snapshot { db.body() } }

    override fun close() = db.close()
}

/**
 * Opens (and migrates) the on-device database in `directory` (the app passes its no-backup files directory, see
 * [ProductionStorage]). Writer, reader and coalescer are thread-safe, so the store is freely shareable.
 */
class LocalStore(directory: File, coalesceIntervalMillis: Long = 100) : AutoCloseable {
    val path: String
    val changes: ChangeCoalescer
    val writer: StoreWriter
    val reader: StoreReader

    init {
        directory.mkdirs()
        path = File(directory, ProductionStorage.DATABASE_FILE_NAME).path
        changes = ChangeCoalescer(coalesceIntervalMillis)
        writer = StoreWriter(path, changes)
        reader = StoreReader(path)
    }

    override fun close() {
        reader.close()
        writer.close()
        changes.close()
    }
}

// MARK: Shared row mapping

internal object Rows {
    const val MESSAGE_COLUMNS =
        "id, channel_id, thread_id, thread_source_id, body, sender_npub, sender_label, client_request_id, created_at, updated_at, row_version, deleted, raw, access_removed"

    fun message(r: SqlRow, workspaceId: String, o: Int = 0) = MessageRecord(
        workspaceId = workspaceId, id = r.text(o)!!, channelId = r.text(o + 1)!!, threadId = r.text(o + 2), threadSourceId = r.text(o + 3),
        body = r.text(o + 4) ?: "", senderNpub = r.text(o + 5), senderLabel = r.text(o + 6), clientRequestId = r.text(o + 7),
        createdAt = r.text(o + 8)!!, updatedAt = r.text(o + 9) ?: r.text(o + 8)!!, rowVersion = r.long(o + 10), deleted = r.bool(o + 11),
        raw = parseRaw(r.text(o + 12)), accessRemoved = r.bool(o + 13),
    )

    const val SCOPE_COLUMNS = "id, name, kind, description, default_channel_id, row_version, updated_at, raw, access_removed"

    fun scope(r: SqlRow, workspaceId: String) = ScopeRecord(
        workspaceId = workspaceId, id = r.text(0)!!, name = r.text(1)!!, kind = r.text(2), description = r.text(3), defaultChannelId = r.text(4),
        rowVersion = r.long(5), updatedAt = r.text(6)!!, raw = parseRaw(r.text(7)), accessRemoved = r.bool(8),
    )

    const val CHANNEL_COLUMNS = "id, scope_id, name, kind, position, row_version, updated_at, raw, access_removed"

    fun channel(r: SqlRow, workspaceId: String) = ChannelRecord(
        workspaceId = workspaceId, id = r.text(0)!!, scopeId = r.text(1)!!, name = r.text(2)!!, kind = r.text(3),
        position = if (r.isNull(4)) null else r.double(4), rowVersion = r.long(5), updatedAt = r.text(6)!!, raw = parseRaw(r.text(7)),
        accessRemoved = r.bool(8),
    )
}

/** Splits `items` into chunks of at most `size` for bounded `IN (...)` lists. */
internal fun <T> List<T>.bounded(size: Int = 200): List<List<T>> = chunked(size)

internal fun marks(count: Int): String = List(count) { "?" }.joinToString(",")
