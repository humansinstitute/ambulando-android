package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpResponseHead
import studio.otherstuff.ambulando.core.transport.HttpTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A synchronous gate over one workspace's network activity. [pause] closes it before any other work (a plain atomic, so
 * no coroutine hop can race it) and starts a new generation; a job, request or materialisation that began in an earlier
 * generation is refused with a cancellation even if its transport ignored cancellation. Local draft and preference writes
 * never pass through the gate.
 */
class WorkspaceActivity(enabled: Boolean = false) {
    private val open = AtomicBoolean(enabled)
    private val generation = AtomicLong(0)

    val isOpen: Boolean get() = open.get()
    val currentGeneration: Long get() = generation.get()

    /** Closes the gate; everything started so far is stale. */
    fun pause() { open.set(false); generation.incrementAndGet() }

    /** Opens the gate for a new generation. */
    fun resume(): Long { generation.incrementAndGet(); open.set(true); return generation.get() }

    /** The generation to remember when work starts, or null while paused. */
    fun lease(): Long? = if (open.get()) generation.get() else null

    fun isCurrent(lease: Long): Boolean = open.get() && generation.get() == lease

    fun check(lease: Long) { if (!isCurrent(lease)) throw CancellationException("Workspace paused") }
}

/** The pause signal for a request that cannot be sent or whose answer is no longer wanted. */
class WorkspacePaused : CancellationException("Workspace paused")

/**
 * A write the person asked for directly (not through a durable queue) reached a workspace whose access has not been
 * confirmed yet, or has been lost. Unlike [WorkspacePaused] this is an ordinary failure, so the screen can say so.
 */
class WritesNotReady : Exception("This workspace is still confirming your access. Try again in a moment.")

/**
 * Refuses requests while the gate is closed and drops answers that arrive after a pause or re-enable. Queue senders see
 * [WorkspacePaused] (a cancellation: the queued row simply waits); direct callers can ask for [closed] to report a failure.
 */
class WorkspaceHttpTransport(
    private val base: HttpTransport, private val activity: WorkspaceActivity,
    private val closed: () -> Throwable = { WorkspacePaused() },
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val lease = activity.lease() ?: throw closed()
        val response = base.send(request)
        if (!activity.isCurrent(lease)) throw WorkspacePaused()
        return response
    }
}

class WorkspaceEventStreamTransport(private val base: EventStreamTransport, private val activity: WorkspaceActivity) : EventStreamTransport {
    override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> {
        val lease = activity.lease() ?: throw WorkspacePaused()
        val opened = base.open(request)
        if (!activity.isCurrent(lease)) throw WorkspacePaused()
        return opened
    }
}

/**
 * One workspace runtime's view of the shared [SyncEngine]: its keys live in the workspace's own namespace (so identical
 * workspace ids on two connections never coalesce), its jobs carry the boundary and connection for fair rotation and
 * per-Tower occupancy, its status contains only its own jobs, and pausing it cancels only its own work.
 */
class ScopedSyncScheduler(
    private val parent: SyncEngine,
    val namespace: String,
    private val connectionNamespace: String,
    private val activity: WorkspaceActivity,
) : SyncScheduler {
    private val prefix = "$namespace|"
    private fun scoped(key: String) = prefix + key

    override val status: StateFlow<SyncSnapshot> = MapView(parent.status) { snapshot ->
        fun keys(set: Set<String>) = set.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        SyncSnapshot(
            keys(snapshot.running), keys(snapshot.queued), keys(snapshot.scheduled),
            snapshot.errors.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) },
            keys(snapshot.connectionFailures),
        )
    }

    override suspend fun snapshot(): SyncSnapshot = status.value

    /** Wraps work so a job that started before a pause never materialises after it. */
    private fun guarded(lease: Long, work: SyncEngine.Work) = SyncEngine.Work {
        activity.check(lease)
        work.run()
        activity.check(lease)
    }

    override suspend fun enqueue(key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, work: SyncEngine.Work) {
        val lease = activity.lease() ?: return
        parent.enqueueFor(namespace, connectionNamespace, scoped(key), priority, force, delayMillis, false, guarded(lease, work))
    }

    override suspend fun enqueueRead(key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, work: SyncEngine.Work) {
        val lease = activity.lease() ?: return
        parent.enqueueFor(namespace, connectionNamespace, scoped(key), priority, force, delayMillis, true, guarded(lease, work))
    }

    override suspend fun recovered(key: String) = parent.recovered(scoped(key))

    override fun post(key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, work: SyncEngine.Work) {
        val lease = activity.lease() ?: return
        parent.post(scoped(key), priority, force, delayMillis, guarded(lease, work))
    }

    override suspend fun cancel(key: String) = parent.cancel(scoped(key))
    override suspend fun cancelPrefix(prefix: String) = parent.cancelPrefix(scoped(prefix))

    /** Cancels this workspace's work only; other workspaces (including those on the same Tower) continue. */
    override suspend fun cancelAll() = parent.cancelPrefix(prefix)

    override suspend fun waitUntilIdle() = parent.waitUntilIdle(prefix)
    override suspend fun failureCount(key: String): Int = parent.failureCount(scoped(key))

    override suspend fun <T> perform(key: String, priority: SyncPriority, work: suspend () -> T): T {
        val lease = activity.lease() ?: throw WorkspacePaused()
        return parent.performFor(namespace, connectionNamespace, scoped(key), priority) {
            activity.check(lease)
            val result = work()
            activity.check(lease)
            result
        }
    }

    /** Pauses: the gate closes first, then queued and running work of this namespace is cancelled. */
    suspend fun pause() { activity.pause(); cancelAll() }
}

/** A read-only mapped view of a [StateFlow] (the value is recomputed on read, so it is always current). */
private class MapView<T, R>(private val source: StateFlow<T>, private val transform: (T) -> R) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<R>): Nothing {
        var last: Any? = NONE
        source.collect { upstream ->
            val mapped = transform(upstream)
            if (last === NONE || last != mapped) { last = mapped; collector.emit(mapped) }
        }
    }
    private companion object { val NONE = Any() }
}
