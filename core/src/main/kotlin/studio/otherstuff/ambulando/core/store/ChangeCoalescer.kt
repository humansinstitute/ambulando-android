package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Local tables (and pseudo-tables) a change notification can name. */
enum class StoreTable {
    CONNECTIONS, WORKSPACES, SCOPES, CHANNELS, MESSAGES, THREADS, MEMBERS, DRAFTS, OUTBOX, APP_STATE,
    TASKS, COMMENTS, DOCS, DOC_BODIES, DOC_DRAFTS, FILES, FOLDERS, VIEW_STATES, REACTIONS, WRITES,
    CONTEXT_TREE, CANVASES, BOARDS, AGENTS, TOOLBOX, PINS,

    /** Public Nostr profiles; connection-scoped, so their change key has no workspace. */
    PROFILES,

    /**
     * Not a table: emitted whenever stored records are removed (deleted on Tower) or flagged `access_removed` (no longer
     * visible to this identity). A paused screen must not show rows read before such a change without a fresh read.
     */
    ACCESS,
}

/** A change notification key. `workspaceId == null` means "not workspace-scoped". */
data class ChangeKey(val table: StoreTable, val workspaceId: String? = null)

/**
 * Merges store change keys and delivers them to observers at most once per `intervalMillis` (default 100 ms, so ≤ 10 Hz
 * per observer). A burst of thousands of writes becomes a handful of UI refreshes, bounding main-thread invalidation.
 * Thread-safe; [record] never suspends, so the writer calls it right after its commit.
 */
class ChangeCoalescer(private val intervalMillis: Long = 100) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending = LinkedHashSet<ChangeKey>()
    private var flushScheduled = false
    private val observers = LinkedHashSet<Channel<Set<ChangeKey>>>()

    /**
     * How many times each table was reported changed, readable synchronously (a screen deciding whether rows it kept are
     * still current must not wait for the next coalesced batch).
     */
    val revisions = ChangeRevisions()

    fun record(keys: Set<ChangeKey>) {
        if (keys.isEmpty()) return
        revisions.bump(keys)
        synchronized(lock) {
            pending.addAll(keys)
            if (flushScheduled) return
            flushScheduled = true
        }
        scope.launch {
            delay(intervalMillis)
            flush()
        }
    }

    private fun flush() {
        val (batch, targets) = synchronized(lock) {
            flushScheduled = false
            if (pending.isEmpty()) return
            val b: Set<ChangeKey> = pending
            pending = LinkedHashSet()
            b to observers.toList()
        }
        for (channel in targets) channel.trySend(batch)
    }

    /**
     * Subscribes immediately (no batch recorded after this call is missed) and returns the coalesced batches. Batches are
     * never dropped (a filtering observer must not miss its key); emission is already rate-limited, so the buffer stays
     * small. Cancel the channel to unsubscribe.
     */
    fun subscribe(): ReceiveChannel<Set<ChangeKey>> {
        val channel = Channel<Set<ChangeKey>>(Channel.UNLIMITED)
        synchronized(lock) { observers.add(channel) }
        channel.invokeOnClose { synchronized(lock) { observers.remove(channel) } }
        return channel
    }

    /** The coalesced batches as a cold flow; each collector subscribes when it starts collecting. */
    fun stream(): Flow<Set<ChangeKey>> = flow {
        val channel = subscribe()
        try {
            for (batch in channel) emit(batch)
        } finally {
            channel.cancel()
        }
    }

    val observerCount: Int get() = synchronized(lock) { observers.size }

    override fun close() {
        synchronized(lock) { observers.toList() }.forEach { it.close() }
        scope.cancel()
    }
}

/** Per-table change counters, bumped as soon as a change is recorded (before its batch is delivered). */
class ChangeRevisions {
    private val counts = HashMap<StoreTable, Long>()

    internal fun bump(keys: Set<ChangeKey>) {
        val tables = keys.mapTo(HashSet()) { it.table }
        synchronized(this) { for (t in tables) counts[t] = (counts[t] ?: 0L) + 1 }
    }

    /** A value that changes whenever any of `tables` is reported changed. */
    fun value(tables: Set<StoreTable>): Long = synchronized(this) { tables.sumOf { counts[it] ?: 0L } }
}
