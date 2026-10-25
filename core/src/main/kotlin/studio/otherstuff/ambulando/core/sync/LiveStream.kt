package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.eventCursor
import studio.otherstuff.ambulando.core.store.saveEventCursor
import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.SSEParser
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerEvent
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.streamRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground live updates for one workspace over Tower's SSE route.
 *
 * Each connection attempt: (1) a bounded event catch-up from the stored cursor through the `events:<w>` job, (2) the
 * stream opened at the resulting cursor with a fresh NIP-98 header, (3) received events queued on the workspace's
 * [LiveFeed] and applied in ≤ [batchWindowMillis] batches by the same job, which saves the cursor only after a batch is
 * stored. A dropped or silent connection (no data for [idleTimeoutMillis]) reconnects with backoff (2 s → 60 s) and
 * resumes from the stored cursor. The receive buffer is bounded by the transport ([OkHttpEventStreamTransport]: 2 MB);
 * an overflow fails the connection, which reconnects from the stored cursor, so nothing is skipped. The app runs the
 * stream only while it is in the foreground and stops it on lock, workspace switch or leaving the foreground.
 *
 * Access revocation (`workspace_access_revoked`, 403/404) stops the stream and reruns discovery, which flags the
 * workspace if it is no longer listed.
 */
class LiveStream(
    private val sync: WorkspaceSync,
    private val transport: EventStreamTransport,
    private val idleTimeoutMillis: Long = 60_000,
    private val batchWindowMillis: Long = 250,
    private val random: () -> Double = { Math.random() },
) {
    private enum class Outcome { ENDED, ACCESS_LOST, RECONNECT }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var job: Job? = null
    @Volatile private var generation = 0L
    private var current: Pair<String, String>? = null

    val isRunning: Boolean get() = job?.isActive == true
    val workspaceId: String? get() = current?.second

    /** Starts (or keeps) the stream for this workspace; any other workspace's stream is stopped first. */
    suspend fun start(connection: ConnectionRecord, workspace: WorkspaceRecord) {
        mutex.withLock {
            if (current == (connection.id to workspace.id) && job?.isActive == true) return
            stopLocked()
            val gen = ++generation
            current = connection.id to workspace.id
            job = scope.launch { run(connection, workspace, gen) }
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val running = job ?: return
        running.cancel()
        job = null
        generation++
        current?.second?.let { w ->
            val feed = sync.live.feed(w)
            feed.reset()
            feed.update { it.copy(stream = LiveStatus.Stream.STOPPED) }
        }
        current = null
    }

    /** Stops the stream permanently (process teardown in tests). */
    fun close() = scope.coroutineContext[Job]?.cancel()

    private suspend fun run(connection: ConnectionRecord, workspace: WorkspaceRecord, gen: Long) {
        val feed = sync.live.feed(workspace.id)
        var failures = 0
        while (currentCoroutineContext().isActive && gen == generation) {
            try {
                sync.identity.requireReader(connection.signerNpub)
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                feed.update { it.copy(stream = LiveStatus.Stream.LOCKED) }
                return   // restarted on unlock
            }
            feed.update { it.copy(stream = if (failures == 0) LiveStatus.Stream.CONNECTING else LiveStatus.Stream.RECONNECTING) }
            try {
                val (outcome, didConnect) = connectOnce(connection, workspace, feed)
                if (didConnect) failures = 0
                if (outcome == Outcome.ACCESS_LOST) {
                    feed.update { it.copy(stream = LiveStatus.Stream.ACCESS_LOST) }
                    sync.discover(connection, SyncPriority.BACKGROUND, force = true)
                    return
                }
            } catch (e: Throwable) {
                // Only a stopped stream stops; any other failure (including a cancelled job) reconnects.
                if (!currentCoroutineContext().isActive || gen != generation) return
                feed.update { it.copy(lastError = e.message ?: e::class.simpleName) }
            }
            if (!currentCoroutineContext().isActive || gen != generation) return
            failures += 1
            feed.update { it.copy(stream = LiveStatus.Stream.RECONNECTING) }
            delay(minOf(SyncBackoff.delayMillis(failures, random()), MAX_BACKOFF_MILLIS))
        }
    }

    private suspend fun connectOnce(connection: ConnectionRecord, workspace: WorkspaceRecord, feed: LiveFeed): Pair<Outcome, Boolean> {
        // 1. Bounded catch-up (seeds the cursor on first use, handles gaps), so the stream starts near the head.
        val catchUp = sync.catchUpEventsAndWait(connection, workspace, priority = SyncPriority.VISIBLE)
        if (catchUp.accessLost) return Outcome.ACCESS_LOST to false
        val cursor = sync.store.reader.eventCursor(connection.id, workspace.id) ?: return Outcome.RECONNECT to false
        feed.takeReconnect()
        // 2. Open the stream with a fresh signature.
        val request = sync.api(connection).streamRequest(workspace.id, workspace.appNpub, cursor)
        val (head, chunks) = transport.open(request)
        try {
            when (head.status) {
                200 -> Unit
                403, 404 -> return Outcome.ACCESS_LOST to false
                400 -> {
                    // Cursor refused: drop it so the next catch-up restarts at the head.
                    sync.store.writer.saveEventCursor(connection.id, workspace.id, null)
                    throw TowerError.Http(400, "cursor", "event stream")
                }
                else -> throw TowerError.Http(head.status, null, "event stream")
            }
            // 3. Read frames, with an idle watchdog (Tower sends a heartbeat every 25 s).
            val activity = Activity()
            val outcome = coroutineScope {
                val result = CompletableDeferred<Outcome>()
                val watchdog = launch {
                    while (true) {
                        delay(maxOf(1, idleTimeoutMillis / 4))
                        if (activity.idle(idleTimeoutMillis)) { result.complete(Outcome.RECONNECT); return@launch }
                    }
                }
                val reader = launch {
                    try {
                        result.complete(readFrames(connection, workspace, feed, chunks, cursor, activity))
                    } catch (e: CancellationException) {
                        result.completeExceptionally(e)
                    } catch (e: Throwable) {
                        result.completeExceptionally(e)
                    }
                }
                try {
                    result.await()
                } finally {
                    watchdog.cancel()
                    reader.cancel()
                }
            }
            return outcome to activity.connected.get()
        } finally {
            chunks.cancel()   // the connection never outlives this attempt
        }
    }

    private suspend fun readFrames(
        connection: ConnectionRecord, workspace: WorkspaceRecord, feed: LiveFeed, chunks: EventChunks, cursor: String, activity: Activity,
    ): Outcome {
        val parser = SSEParser()
        var previous = cursor
        while (true) {
            val chunk = chunks.next() ?: return Outcome.RECONNECT   // server closed the stream
            checkCancelled()
            activity.touch()
            for (frame in parser.feed(chunk)) {
                when (frame.event) {
                    "connected" -> {
                        activity.connected.set(true)
                        feed.update { it.copy(stream = LiveStatus.Stream.CONNECTED, lastError = null) }
                    }
                    "flightdeck_pg.event" -> {
                        val event = parseJsonObjectOrNull(frame.data.toByteArray(Charsets.UTF_8))?.let { TowerEvent.parse(it) } ?: continue
                        if (event.workspaceId != workspace.id) return Outcome.RECONNECT
                        feed.push(event, previous)
                        previous = event.cursor
                        sync.scheduleEvents(connection, workspace, SyncPriority.VISIBLE, delayMillis = batchWindowMillis)
                    }
                    "flightdeck_pg.error" -> return if (frame.data.contains("workspace_access_revoked")) Outcome.ACCESS_LOST else Outcome.RECONNECT
                }
            }
            parser.takeComments()
            if (feed.takeReconnect()) return Outcome.RECONNECT
        }
    }

    private class Activity {
        private val last = AtomicLong(System.nanoTime())
        val connected = AtomicBoolean(false)
        fun touch() = last.set(System.nanoTime())
        fun idle(limitMillis: Long): Boolean = (System.nanoTime() - last.get()) / 1_000_000 > limitMillis
    }

    companion object {
        /** Reconnect backoff ceiling (2 s → 60 s). */
        const val MAX_BACKOFF_MILLIS = 60_000L
    }
}
