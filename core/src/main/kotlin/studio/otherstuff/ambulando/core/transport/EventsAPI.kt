package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Event routes. Both are ordinary NIP-98 GETs over the exact URL (including the cursor query).

const val EVENT_PAGE_LIMIT = 200

data class EventPage(
    val events: List<TowerEvent>,
    /** Cursor to resume from after this page (Tower's `next_cursor`, else the last event's cursor). */
    val nextCursor: String?,
    /** Rows Tower returned that this client could not parse; they still advance the cursor. */
    val skipped: Int,
)

fun TowerAPI.Companion.eventsPath(workspaceId: String): String = base(workspaceId) + "/events"
fun TowerAPI.Companion.streamPath(workspaceId: String): String = base(workspaceId) + "/events/stream"

/** `GET /workspaces/:w/events?cursor=&limit=` — visible events with `row_version` above the cursor, oldest first. */
suspend fun TowerAPI.events(workspaceId: String, appNpub: String, cursor: String?, limit: Int = EVENT_PAGE_LIMIT): EventPage {
    val query = mutableListOf("limit" to limit.coerceIn(1, 200).toString())
    if (cursor != null) query.add(0, "cursor" to cursor)
    val o = obj(client.request(path = TowerAPI.eventsPath(workspaceId), query = query, appNpub = appNpub), "events")
    val rows = o.objects("events") ?: throw TowerError.Malformed("events")
    val events = rows.mapNotNull { TowerEvent.parse(it) }
    if (!events.all { it.workspaceId == workspaceId }) throw TowerError.Malformed("events")
    val next = o.string("next_cursor") ?: rows.lastOrNull()?.string("cursor") ?: cursor
    return EventPage(events, next, rows.size - events.size)
}

/**
 * The workspace's current event high-water mark, from `GET /workspaces/:w/sync?limit=1` (`through_cursor`). Used to
 * start live updates without replaying the whole event history; the local lists are then caught up directly.
 */
suspend fun TowerAPI.eventHead(workspaceId: String, appNpub: String): String {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/sync", query = listOf("limit" to "1"), appNpub = appNpub), "sync")
    return o.string("through_cursor")?.takeIf { it.isNotEmpty() } ?: throw TowerError.Malformed("sync cursor")
}

/**
 * Signed request for the event stream. NIP-98 is checked when the stream opens (Tower's 300 s window), so a fresh
 * header is made for every connection attempt. The stream transport applies the 90 s idle timeout (heartbeats
 * arrive every 25 s).
 */
suspend fun TowerAPI.streamRequest(workspaceId: String, appNpub: String, cursor: String?): HttpRequest {
    val query = cursor?.let { listOf("cursor" to it) } ?: emptyList()
    return client.signed("GET", TowerAPI.streamPath(workspaceId), query, appNpub, null).withHeader("Accept", "text/event-stream")
}

/**
 * Body chunks of one event-stream connection, pulled on demand (so a bounded buffer stays bounded). [cancel] closes
 * the connection; the reader calls it when it stops reading for any reason.
 */
interface EventChunks {
    /** The next chunk, null at the end of the stream, or the failure. */
    suspend fun next(): ByteArray?
    fun cancel()

    companion object {
        /** Wraps a channel (tests and the sample-data stand-in). A closed channel ends the stream. */
        fun of(channel: ReceiveChannel<ByteArray>): EventChunks = object : EventChunks {
            override suspend fun next(): ByteArray? = channel.receiveCatching().let { result ->
                result.exceptionOrNull()?.let { throw it }
                result.getOrNull()
            }

            override fun cancel() = channel.cancel()
        }
    }
}

/** Status and headers of an opened stream. */
class HttpResponseHead(val status: Int, val headers: Map<String, String> = emptyMap())

/** Streaming seam for SSE. Production uses [OkHttpEventStreamTransport]; tests feed chunks. */
interface EventStreamTransport {
    /** Opens the request and returns the response head with the body chunks. */
    suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks>
}

/**
 * Bounded hand-off between the network reader thread and the stream reader. Bytes count against [limit] until the
 * reader takes them; if a slow reader lets more than [limit] accumulate, the buffer fails with [Overflow] instead of
 * dropping bytes, so the stream reconnects from the last durably stored cursor and nothing is silently skipped.
 */
class BoundedChunkBuffer(val limit: Int) {
    /** The consumer fell behind by more than [limit] bytes. */
    class Overflow : Exception("The event stream buffer overflowed.")

    private val lock = Any()
    private val chunks = ArrayDeque<ByteArray>()
    private var buffered = 0
    private var finished = false
    private var failure: Throwable? = null
    private var waiter: CancellableContinuation<ByteArray?>? = null

    val bufferedBytes: Int get() = synchronized(lock) { buffered }

    /** Producer side. Returns false once the buffer has failed or finished (the producer should cancel its request). */
    fun append(data: ByteArray): Boolean {
        val resume: CancellableContinuation<ByteArray?> = synchronized(lock) {
            if (finished || failure != null) return false
            val w = waiter
            if (w == null) {
                if (buffered + data.size > limit) {
                    failure = Overflow()
                    chunks.clear()
                    buffered = 0
                    return false
                }
                chunks.addLast(data)
                buffered += data.size
                return true
            }
            waiter = null
            w
        }
        resume.resume(data)
        return true
    }

    fun finish(error: Throwable? = null) {
        val action: (() -> Unit)?
        synchronized(lock) {
            if (finished) return
            finished = true
            if (error != null && failure == null) failure = error
            val w = waiter ?: return
            waiter = null
            val pending = failure
            action = when {
                chunks.isNotEmpty() -> {
                    val chunk = chunks.removeFirst()
                    buffered -= chunk.size
                    ({ w.resume(chunk) })
                }
                pending != null -> ({ w.resumeWithException(pending) })
                else -> ({ w.resume(null) })
            }
        }
        action?.invoke()
    }

    /** Consumer side: the next chunk, null at the end, or the failure (an overflow is reported as soon as it happens). */
    suspend fun next(): ByteArray? = suspendCancellableCoroutine { c ->
        val action: () -> Unit
        synchronized(lock) {
            val f = failure
            action = when {
                f != null && (chunks.isEmpty() || f is Overflow) -> ({ c.resumeWithException(f) })
                chunks.isNotEmpty() -> {
                    val chunk = chunks.removeFirst()
                    buffered -= chunk.size
                    ({ c.resume(chunk) })
                }
                finished -> ({ c.resume(null) })
                else -> {
                    waiter = c
                    c.invokeOnCancellation { synchronized(lock) { if (waiter === c) waiter = null } }
                    ({})
                }
            }
        }
        action()
    }

    internal fun cancelled() = finish(CancellationException("The event stream was cancelled."))
}
