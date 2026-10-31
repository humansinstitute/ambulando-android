package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `USER` is an explicit user action (send, open a thread); `VISIBLE` refreshes the screen in view; `BACKGROUND` is
 * bulk catch-up and can never occupy the reserved foreground slot.
 */
enum class SyncPriority { BACKGROUND, VISIBLE, USER }

object SyncBackoff {
    /** 2 s, 4 s, 8 s … capped at 300 s, with ±20 % jitter (`random` in 0..1). Milliseconds. */
    fun delayMillis(failures: Int, random: Double): Long {
        if (failures <= 0) return 0
        val base = min(2.0 * 2.0.pow(failures - 1), 300.0)
        return (base * (0.8 + 0.4 * random.coerceIn(0.0, 1.0)) * 1000).toLong()
    }
}

data class SyncSnapshot(
    val running: Set<String> = emptySet(),
    /** Ready to run now. */
    val queued: Set<String> = emptySet(),
    /** Waiting for a scheduled retry time; not shown as active syncing. */
    val scheduled: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val connectionFailures: Set<String> = emptySet(),
) {
    val isIdle: Boolean get() = running.isEmpty() && queued.isEmpty()
}

/**
 * What every sync collaborator needs from the scheduler. The shared [SyncEngine] implements it directly; a
 * [ScopedSyncScheduler] gives one workspace runtime its own namespace, pause gate and status view over that same engine.
 */
interface SyncScheduler {
    val status: StateFlow<SyncSnapshot>
    suspend fun snapshot(): SyncSnapshot
    suspend fun enqueue(key: String, priority: SyncPriority = SyncPriority.BACKGROUND, force: Boolean = false, delayMillis: Long? = null, work: SyncEngine.Work)
    suspend fun enqueueRead(key: String, priority: SyncPriority = SyncPriority.BACKGROUND, force: Boolean = false, delayMillis: Long? = null, work: SyncEngine.Work)
    suspend fun recovered(key: String)
    fun post(key: String, priority: SyncPriority = SyncPriority.BACKGROUND, force: Boolean = false, delayMillis: Long? = null, work: SyncEngine.Work)
    suspend fun cancel(key: String)
    suspend fun cancelPrefix(prefix: String)
    suspend fun cancelAll()
    suspend fun waitUntilIdle()
    suspend fun failureCount(key: String): Int
    suspend fun <T> perform(key: String, priority: SyncPriority = SyncPriority.USER, work: suspend () -> T): T
}

/**
 * The single owner of network refresh work (architecture: "sole network/update owner").
 *
 * - Keyed jobs coalesce: enqueueing a queued key replaces its work; enqueueing a running key schedules exactly one
 *   rerun after it finishes, so changes that land mid-fetch are not lost.
 * - At most [maxConcurrent] jobs run; user and visible-screen work runs before catch-up, and [reservedForeground]
 *   slots are never given to background jobs, so a send is not stuck behind a storm.
 * - Every job runs off the main thread and is cancellable (lock, workspace switch, background expiry).
 * - Failures back off exponentially per key; `force` (user pull-to-refresh) bypasses the wait.
 *
 * All engine state is confined to [stateDispatcher] (one thread at a time, the analogue of the Swift actor).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngine(
    maxConcurrent: Int = 3,
    reservedForeground: Int = 1,
    private val random: () -> Double = { Math.random() },
    /** Monotonic milliseconds. */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val stateDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val jobDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Retry delay injection for deterministic recovery tests. */
    private val retryDelay: (Int) -> Long = { SyncBackoff.delayMillis(it, random()) },
    private val retryEvent: (String) -> Unit = {},
    private val describe: (Throwable) -> String = { studio.otherstuff.ambulando.core.transport.ConnectionFailure.userMessage(it) ?: "Connection lost" },
    /**
     * Most jobs of one connection group that may run at once (null: unlimited). With several Towers this leaves a slot
     * for another one, in foreground and background work alike.
     */
    maxPerGroup: Int? = null,
) : SyncScheduler {
    fun interface Work { suspend fun run() }

    private class QueuedJob(
        val key: String,
        var priority: SyncPriority,
        var work: Work,
        val sequence: Long,
        /** Called when the job is removed without running (cancelled, or replaced by a newer job of its key). */
        var dropped: (() -> Unit)? = null,
        var retryRead: Boolean = false,
        var automaticRetry: Boolean = false,
        /** Workspace boundary that owns the job (equal-priority work rotates between owners). */
        val owner: String? = null,
        /** Connection (origin + signer) the job talks to (bounds occupancy per Tower). */
        val group: String? = null,
    )

    val maxConcurrent: Int = max(1, maxConcurrent)
    val reservedForeground: Int = reservedForeground.coerceIn(0, this.maxConcurrent - 1)
    val maxPerGroup: Int? = maxPerGroup?.coerceAtLeast(1)

    private val scope = CoroutineScope(SupervisorJob() + stateDispatcher)
    private val queue = ArrayList<QueuedJob>()
    private val running = HashMap<String, Job>()
    private val runningPriority = HashMap<String, SyncPriority>()
    private val runningGroup = HashMap<String, String>()
    /** Start order of each owner's most recent job: the owner served longest ago goes first among equal priorities. */
    private val ownerServed = HashMap<String, Long>()
    private var served = 0L
    private val reruns = HashMap<String, QueuedJob>()
    private val failures = HashMap<String, Int>()
    private val notBefore = HashMap<String, Long>()      // failure backoff
    private val delayedUntil = HashMap<String, Long>()   // requested delay (scheduled retry)
    private val connectionFailures = HashSet<String>()
    private val errors = HashMap<String, String>()
    private var sequence = 0L
    private var generation = 0L
    private class IdleWaiter(val prefix: String?, val done: CompletableDeferred<Unit> = CompletableDeferred())
    private val idleWaiters = ArrayList<IdleWaiter>()
    private var wakeJob: Job? = null

    private val _status = MutableStateFlow(SyncSnapshot())
    /** Current queue state; conflated, so observers see the newest snapshot. */
    override val status: StateFlow<SyncSnapshot> = _status.asStateFlow()

    private suspend fun <T> confined(block: () -> T): T = withContext(stateDispatcher) { block() }

    override suspend fun snapshot(): SyncSnapshot = confined { computeSnapshot() }

    private fun computeSnapshot(): SyncSnapshot {
        val now = nowMillis()
        val waiting = queue.map { it.key }.filter { k -> readyAt(k)?.let { it > now } ?: false }.toSet()
        return SyncSnapshot(running.keys.toSet(), queue.map { it.key }.toSet() - waiting, waiting, errors.toMap(), connectionFailures.toSet())
    }

    private fun publish() { _status.value = computeSnapshot() }

    /**
     * Adds or coalesces a keyed job. [delayMillis] schedules it for later (an automatic retry); a delayed request
     * never postpones one already pending without delay, and an immediate request cancels an earlier delay.
     */
    override suspend fun enqueue(key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, work: Work) =
        confined { enqueueLocked(key, priority, force, delayMillis, work, null) }

    /** [enqueue] for a scoped scheduler: the job is attributed to a workspace boundary [owner] and a [group] (connection). */
    suspend fun enqueueFor(owner: String?, group: String?, key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, read: Boolean, work: Work) =
        confined { enqueueLocked(key, priority, force, delayMillis, work, null, retryRead = read, owner = owner, group = group) }

    /** Only idempotent read jobs opt in. Three total attempts per key/outage, reset by success or explicit force. */
    override suspend fun enqueueRead(key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, work: Work) =
        confined { enqueueLocked(key, priority, force, delayMillis, work, null, retryRead = true) }

    /** A successful alternate refresh (for example the event path) resolves stale warnings for this key. */
    override suspend fun recovered(key: String) = confined {
        failures.remove(key); notBefore.remove(key); errors.remove(key)
        if (connectionFailures.remove(key)) retryEvent("recovered")
        queue.removeAll { it.key == key && it.automaticRetry }
        pump(); publish(); resumeIdleWaitersIfIdle()
    }

    /** Fire-and-forget [enqueue] for callers that are not suspending (posts in order on the state dispatcher). */
    override fun post(key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, work: Work) {
        scope.launch { enqueueLocked(key, priority, force, delayMillis, work, null) }
    }

    private fun enqueueLocked(key: String, priority: SyncPriority, force: Boolean, delayMillis: Long?, work: Work, dropped: (() -> Unit)?, retryRead: Boolean = false, owner: String? = null, group: String? = null) {
        sequence++
        val job = QueuedJob(key, priority, work, sequence, dropped, retryRead, owner = owner, group = group)
        if (force) { notBefore.remove(key); if (retryRead) failures.remove(key) }
        val pendingImmediately = (reruns.containsKey(key) || queue.any { it.key == key }) && !delayedUntil.containsKey(key)
        if (delayMillis != null && !force) {
            if (!pendingImmediately) {
                val at = nowMillis() + delayMillis
                delayedUntil[key] = delayedUntil[key]?.let { min(it, at) } ?: at
            }
        } else {
            delayedUntil.remove(key)
        }
        if (running.containsKey(key)) {
            reruns[key]?.let { existing -> job.priority = maxOf(existing.priority, priority); existing.dropped?.invoke() }
            reruns[key] = job
        } else {
            val existing = queue.firstOrNull { it.key == key }
            if (existing != null) {
                existing.dropped?.invoke()
                existing.retryRead = retryRead
                existing.automaticRetry = false
                existing.work = work
                existing.dropped = dropped
                existing.priority = maxOf(existing.priority, priority)
            } else {
                queue.add(job)
            }
        }
        pump()
        publish()
    }

    private fun readyAt(key: String): Long? {
        val a = notBefore[key]
        val b = delayedUntil[key]
        return when {
            a == null -> b
            b == null -> a
            else -> max(a, b)
        }
    }

    override suspend fun cancel(key: String) = confined { cancelLocked(key) }

    private fun cancelLocked(key: String) {
        failures.remove(key); notBefore.remove(key); errors.remove(key); connectionFailures.remove(key)
        queue.filter { it.key == key }.forEach { it.dropped?.invoke() }
        queue.removeAll { it.key == key }
        reruns.remove(key)?.dropped?.invoke()
        delayedUntil.remove(key)
        running[key]?.cancel()
        publish()
        resumeIdleWaitersIfIdle()
    }

    /** Cancels every job whose key starts with [prefix] (for example all profile refreshes of a connection). */
    override suspend fun cancelPrefix(prefix: String) = confined {
        val keys = (queue.map { it.key } + reruns.keys + delayedUntil.keys + running.keys).filter { it.startsWith(prefix) }.toSet()
        keys.forEach { cancelLocked(it) }
    }

    /** Cancels everything (identity locked, workspace removed, background work expiring). */
    override suspend fun cancelAll() = confined {
        generation++
        failures.clear(); notBefore.clear(); errors.clear(); connectionFailures.clear()
        queue.forEach { it.dropped?.invoke() }
        reruns.values.forEach { it.dropped?.invoke() }
        queue.clear()
        reruns.clear()
        delayedUntil.clear()
        running.values.forEach { it.cancel() }
        publish()
        resumeIdleWaitersIfIdle()
    }

    /** Returns when nothing is queued or running, or when the caller is cancelled (background expiry). */
    override suspend fun waitUntilIdle() = waitUntilIdle(null)

    /** As [waitUntilIdle], but only for jobs whose key starts with [prefix] (one workspace's namespace). */
    suspend fun waitUntilIdle(prefix: String?) {
        val waiter = confined {
            if (idleFor(prefix)) null else IdleWaiter(prefix).also { idleWaiters.add(it) }
        } ?: return
        try {
            waiter.done.await()
        } finally {
            withContext(NonCancellable + stateDispatcher) { idleWaiters.remove(waiter) }
        }
    }

    override suspend fun failureCount(key: String): Int = confined { failures[key] ?: 0 }

    /**
     * Runs one job under the engine's slots and priorities and returns its result. The job's own error is returned to
     * the caller rather than recorded (no backoff for the key). A job removed before it ran (cancel, [cancelAll]) or a
     * cancelled caller ends with [CancellationException], so a waiter is never left hanging.
     */
    override suspend fun <T> perform(key: String, priority: SyncPriority, work: suspend () -> T): T = performFor(null, null, key, priority, work)

    /** [perform] attributed to a workspace boundary [owner] and its connection [group]. */
    suspend fun <T> performFor(owner: String?, group: String?, key: String, priority: SyncPriority, work: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        confined {
            enqueueLocked(key, priority, true, null, Work {
                try {
                    result.complete(work())
                } catch (c: CancellationException) {
                    result.completeExceptionally(c)
                    throw c
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                }
            }, dropped = { result.completeExceptionally(CancellationException("Job was cancelled before it ran")) }, owner = owner, group = group)
        }
        try {
            return result.await()
        } catch (c: CancellationException) {
            if (!result.isCompleted) {
                result.cancel(c)
                withContext(NonCancellable) { cancel(key) }
            }
            throw c
        }
    }

    private fun pump() {
        val now = nowMillis()
        while (running.size < maxConcurrent) {
            val backgroundRunning = runningPriority.values.count { it == SyncPriority.BACKGROUND }
            val backgroundAllowed = backgroundRunning < maxConcurrent - reservedForeground
            val groupLimit = maxPerGroup
            val next = queue.filter { j ->
                (backgroundAllowed || j.priority > SyncPriority.BACKGROUND) && (readyAt(j.key)?.let { it <= now } ?: true) &&
                    (groupLimit == null || j.group == null || runningGroup.values.count { it == j.group } < groupLimit)
            }.maxWithOrNull(
                compareBy<QueuedJob> { it.priority }
                    // The workspace served longest ago goes first, so one busy workspace cannot starve another.
                    .thenBy { -(it.owner?.let { o -> ownerServed[o] } ?: 0L) }
                    .thenByDescending { it.sequence }
            ) ?: break
            queue.remove(next)
            start(next)
        }
        scheduleWake(now)
    }

    private fun start(job: QueuedJob) {
        val key = job.key
        val started = generation
        runningPriority[key] = job.priority
        job.group?.let { runningGroup[key] = it }
        job.owner?.let { ownerServed[it] = ++served }
        delayedUntil.remove(key)
        running[key] = scope.launch(jobDispatcher) {
            var failure: Throwable? = null
            try {
                job.work.run()
            } catch (t: Throwable) {
                failure = t
            }
            // A transport error raised because the job was cancelled is a cancellation, not a failure to back off.
            if (failure != null && !isActive) failure = CancellationException("cancelled", failure)
            val outcome = failure
            withContext(NonCancellable + stateDispatcher) { finish(job, outcome, started) }
        }
    }

    private fun finish(job: QueuedJob, error: Throwable?, started: Long) {
        val key = job.key
        val cancelled = running.remove(key)?.isCancelled == true || started != generation
        runningPriority.remove(key)
        runningGroup.remove(key)
        if (cancelled) {
            // A cancelled transport may ignore cancellation and return a late success/failure. Neither is current.
        } else if (error != null && error !is CancellationException) {
            val count = (failures[key] ?: 0) + 1
            failures[key] = count
            notBefore[key] = nowMillis() + retryDelay(count)
            if (studio.otherstuff.ambulando.core.transport.ConnectionFailure.isTransient(error)) {
                connectionFailures.add(key); errors.remove(key)
                if (job.retryRead && count < 3 && started == generation && !reruns.containsKey(key)) {
                    job.automaticRetry = true; queue.add(job); retryEvent("retry")
                } else if (job.retryRead) retryEvent("gave-up")
            } else {
                connectionFailures.remove(key); errors[key] = describe(error)
            }
        } else if (error == null) {
            failures.remove(key)
            notBefore.remove(key)
            errors.remove(key)
            if (connectionFailures.remove(key)) retryEvent("recovered")
        }
        // cancelAll clears every rerun, so one still waiting here was issued after the latest cancel: it is not the cancelled
        // job's and must run even when its predecessor unwinds late.
        reruns.remove(key)?.let { rerun -> queue.add(rerun) }
        pump()
        publish()
        resumeIdleWaitersIfIdle()
    }

    private fun scheduleWake(now: Long) {
        wakeJob?.cancel()
        wakeJob = null
        if (running.size >= maxConcurrent) return
        val next = queue.mapNotNull { readyAt(it.key) }.filter { it > now }.minOrNull() ?: return
        wakeJob = scope.launch {
            delay(next - now)
            pump()
            publish()
        }
    }

    private fun idleFor(prefix: String?): Boolean =
        if (prefix == null) queue.isEmpty() && running.isEmpty()
        else queue.none { it.key.startsWith(prefix) } && running.keys.none { it.startsWith(prefix) }

    private fun resumeIdleWaitersIfIdle() {
        val ready = idleWaiters.filter { idleFor(it.prefix) }
        if (ready.isEmpty()) return
        idleWaiters.removeAll(ready.toSet())
        ready.forEach { it.done.complete(Unit) }
    }

    /** Stops the engine permanently (process teardown in tests). */
    fun close() = scope.coroutineContext[Job]?.cancel()
}
