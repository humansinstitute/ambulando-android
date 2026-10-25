package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a [LiveQuery] currently shows. */
data class LiveQueryState<T>(val value: T, val loaded: Boolean = false, val error: String? = null)

/**
 * A store-backed value for one screen (the native equivalent of Dexie `liveQuery`).
 *
 * It loads from the local database immediately, then reloads only when a coalesced change batch (≤ 10 Hz) touches a
 * matching key. Reads and the equality check run off the main thread (`Dispatchers.Default`, the store's own reader
 * dispatcher for SQL); only a value that actually changed is published to [state], so sync bursts never block typing,
 * scrolling or navigation.
 *
 * Every [start] gets a new generation. A load that finishes after its query was replaced, paused or stopped (for example
 * a slow read for the previous workspace) is discarded, including its error and `loaded` state, so stale rows can never
 * overwrite the current query. `start`, `pause` and `stop` are meant to be called from the owning (UI) thread.
 */
class LiveQuery<T>(private val initial: T, private val scope: CoroutineScope) {
    private val lock = Any()
    private val mutable = MutableStateFlow(LiveQueryState(initial))
    val state: StateFlow<LiveQueryState<T>> = mutable.asStateFlow()
    val value: T get() = mutable.value.value
    val loaded: Boolean get() = mutable.value.loaded
    val error: String? get() = mutable.value.error

    private var generation = 0L
    private var job: Job? = null
    /** Change revision of the guarded tables taken just before the read that produced `value`. */
    private var loadedRevision: Long? = null
    private var loadedGuard: Set<StoreTable> = emptySet()

    /** Called after every successful load of the current query, including an unchanged first one. */
    @Volatile var onLoad: ((T) -> Unit)? = null

    companion object {
        /**
         * Changes after which kept rows must not be shown again before a fresh read: records were removed (deleted) or
         * flagged as no longer visible to this identity.
         */
        val ACCESS_CHANGES: Set<StoreTable> = setOf(StoreTable.ACCESS)
    }

    fun start(
        store: LocalStore, keepingValue: Boolean = false, invalidatedBy: Set<StoreTable> = ACCESS_CHANGES,
        matches: (ChangeKey) -> Boolean, load: suspend () -> T,
    ) = start(store.changes, keepingValue, invalidatedBy, matches, load)

    /**
     * Starts (or replaces) the query. `keepingValue` resumes a paused query: the rows on screen stay until the fresh read
     * replaces them (no flash, no scroll jump). Use it only for the same query that was paused. The rows are kept only
     * if none of `invalidatedBy` changed since they were read (by default: nothing was deleted or lost access);
     * otherwise the query starts empty and shows only what the fresh read returns.
     */
    fun start(
        changes: ChangeCoalescer, keepingValue: Boolean = false, invalidatedBy: Set<StoreTable> = ACCESS_CHANGES,
        matches: (ChangeKey) -> Boolean, load: suspend () -> T,
    ) {
        synchronized(lock) {
            generation += 1
            val current = generation
            val revisions = changes.revisions
            val keep = keepingValue && mutable.value.loaded && loadedRevision == revisions.value(invalidatedBy) && loadedGuard == invalidatedBy
            if (!keep) mutable.value = LiveQueryState(initial)
            job?.cancel()
            // Subscribed before the first read, so a change committed during it is not missed.
            val batches = changes.subscribe()
            job = scope.launch {
                try {
                    reload(load, current, revisions, invalidatedBy)
                    for (batch in batches) if (batch.any(matches)) reload(load, current, revisions, invalidatedBy)
                } finally {
                    batches.cancel()
                }
            }
        }
    }

    /** Stops reloading but keeps the current rows (a screen that is out of sight; resume with `keepingValue`). */
    fun pause() = synchronized(lock) {
        generation += 1
        job?.cancel()
        job = null
    }

    /** Stops reloading and returns to the initial value, so rows of a workspace that is no longer shown never render. */
    fun stop() = synchronized(lock) {
        generation += 1
        job?.cancel()
        job = null
        loadedRevision = null
        if (mutable.value != LiveQueryState(initial)) mutable.value = LiveQueryState(initial)
    }

    private suspend fun reload(load: suspend () -> T, started: Long, revisions: ChangeRevisions, guard: Set<StoreTable>) {
        // Taken before the read: a removal recorded after this point may be missing from the result, so it must
        // invalidate these rows if the query is paused and resumed.
        val revision = revisions.value(guard)
        val previous = mutable.value.value
        // The read and the equality check both run off the main thread; only a changed value is published.
        val result: Result<T?> = withContext(Dispatchers.Default) {
            try {
                val next = load()
                Result.success(if (next == previous) null else next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val loadedValue: T = synchronized(lock) {
            // The query may have been restarted, paused or stopped while the read was in flight.
            if (started != generation) return
            val now = mutable.value
            result.fold(
                onSuccess = { next ->
                    loadedRevision = revision
                    loadedGuard = guard
                    val updated = LiveQueryState(next ?: now.value, loaded = true, error = null)
                    if (updated != now) mutable.value = updated
                    updated.value
                },
                onFailure = { failure ->
                    val message = failure.message ?: failure.toString()
                    val updated = now.copy(loaded = true, error = message)
                    if (updated != now) mutable.value = updated
                    return
                },
            )
        }
        onLoad?.invoke(loadedValue)
    }
}

/**
 * A cold [Flow] over the store: emits the first read, then re-reads on every coalesced batch with a matching key and
 * emits only values that differ from the last one. Reads run on `Dispatchers.Default` (SQL on the store's reader).
 */
fun <T> LocalStore.liveQuery(matches: (ChangeKey) -> Boolean, load: suspend () -> T): Flow<T> = flow {
    val batches = changes.subscribe()
    try {
        var last = load()
        emit(last)
        for (batch in batches) {
            if (!batch.any(matches)) continue
            val value = load()
            if (value != last) { last = value; emit(value) }
        }
    } finally {
        batches.cancel()
    }
}.flowOn(Dispatchers.Default)
