package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** One shared engine, several workspace runtimes: namespaces, pause gates, fair rotation and per-Tower occupancy. */
class ScopedSchedulerTests {
    private fun <T> test(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = runBlocking { withTimeout(15_000) { block() } }

    private fun scoped(engine: SyncEngine, name: String, connection: String = "tower-$name"): Pair<ScopedSyncScheduler, WorkspaceActivity> {
        val activity = WorkspaceActivity(enabled = true)
        return ScopedSyncScheduler(engine, name, connection, activity) to activity
    }

    @Test fun identicalKeysInDifferentWorkspacesNeverCoalesce() = test {
        val engine = SyncEngine(maxConcurrent = 4)
        val (a, _) = scoped(engine, "a"); val (b, _) = scoped(engine, "b")
        val gate = CompletableDeferred<Unit>()
        val ran = Collections.synchronizedList(ArrayList<String>())
        // The same workspace id (so the same job key) under two boundaries: both run, neither replaces the other.
        a.enqueue("scopes:00000000-0000-4000-8000-000000000001") { ran.add("a"); gate.await() }
        b.enqueue("scopes:00000000-0000-4000-8000-000000000001") { ran.add("b"); gate.await() }
        delay(50)
        assertEquals(setOf("a", "b"), ran.toSet())
        // Each runtime sees only its own jobs, under its own plain key.
        assertEquals(setOf("scopes:00000000-0000-4000-8000-000000000001"), a.status.value.running)
        gate.complete(Unit)
        engine.waitUntilIdle()
    }

    @Test fun pausingOneWorkspaceCancelsOnlyItsWorkAndBlocksNewWork() = test {
        val engine = SyncEngine(maxConcurrent = 4)
        val (a, activityA) = scoped(engine, "a", "same-tower"); val (b, _) = scoped(engine, "b", "same-tower")
        val aStarted = CompletableDeferred<Unit>(); val aCancelled = CompletableDeferred<Unit>(); val bDone = CompletableDeferred<Unit>()
        a.enqueue("long") { aStarted.complete(Unit); try { delay(60_000) } catch (c: CancellationException) { aCancelled.complete(Unit); throw c } }
        b.enqueue("steady") { delay(150); bDone.complete(Unit) }
        aStarted.await()
        a.pause()
        aCancelled.await()
        bDone.await()
        // While paused nothing is accepted, and a user-priority call fails instead of waiting.
        var ranAfterPause = false
        a.enqueue("late") { ranAfterPause = true }
        engine.waitUntilIdle()
        assertFalse(ranAfterPause)
        val refused = runCatching { a.perform("send") { 1 } }.exceptionOrNull()
        assertTrue(refused is CancellationException)
        // Re-enabling opens a new generation; the old one stays dead.
        activityA.resume()
        var ranAfterResume = false
        a.enqueue("again") { ranAfterResume = true }
        engine.waitUntilIdle()
        assertTrue(ranAfterResume)
    }

    @Test fun anAnswerThatArrivesAfterPauseIsRefusedEvenIfTheTransportIgnoredCancellation() = test {
        val activity = WorkspaceActivity(enabled = true)
        val release = CompletableDeferred<Unit>()
        val sent = AtomicInteger()
        val slow = object : HttpTransport { override suspend fun send(request: HttpRequest): HttpResponse { sent.incrementAndGet(); release.await(); return HttpResponse(200, "stale".toByteArray()) } }
        val guarded = WorkspaceHttpTransport(slow, activity)
        val answer = async { runCatching { guarded.send(HttpRequest("GET", "https://t.example.com/api/v4/x")) } }
        while (sent.get() == 0) delay(5)
        activity.pause(); release.complete(Unit)
        assertTrue(answer.await().exceptionOrNull() is WorkspacePaused)
        // Closed gate: the request is not even sent.
        val before = sent.get()
        assertTrue(runCatching { guarded.send(HttpRequest("GET", "https://t.example.com/api/v4/x")) }.exceptionOrNull() is WorkspacePaused)
        assertEquals(before, sent.get())
        // After resume a late answer from the previous generation is still refused.
        activity.resume()
        val late = CompletableDeferred<Unit>()
        val slow2 = WorkspaceHttpTransport(object : HttpTransport { override suspend fun send(request: HttpRequest): HttpResponse { late.await(); return HttpResponse(200, ByteArray(0)) } }, activity)
        val pending = async { runCatching { slow2.send(HttpRequest("GET", "https://t.example.com/api/v4/x")) } }
        delay(30); activity.pause(); activity.resume(); late.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is WorkspacePaused)
    }

    @Test fun equalPriorityWorkRotatesBetweenWorkspaces() = test {
        val engine = SyncEngine(maxConcurrent = 1, reservedForeground = 0)
        val (a, _) = scoped(engine, "a"); val (b, _) = scoped(engine, "b")
        val order = Collections.synchronizedList(ArrayList<String>())
        val gate = CompletableDeferred<Unit>()
        a.enqueue("block") { order.add("a-block"); gate.await() }
        delay(30)
        // Workspace A queues more jobs than B, and later: rotation still serves B between them.
        b.enqueue("b1") { order.add("b1") }
        a.enqueue("a1") { order.add("a1") }
        a.enqueue("a2") { order.add("a2") }
        b.enqueue("b2") { order.add("b2") }
        gate.complete(Unit)
        engine.waitUntilIdle()
        assertEquals(5, order.size)
        val tail = order.drop(1).map { it.take(1) }
        // FIFO inside one workspace: b1, a1, b2, a2.
        assertEquals("never two of the same workspace in a row while the other has work", tail, listOf("b", "a", "b", "a"))
    }

    @Test fun oneTowerCannotOccupyEverySlot() = test {
        val engine = SyncEngine(maxConcurrent = 3, reservedForeground = 0, maxPerGroup = 2)
        val (a1, _) = scoped(engine, "a1", "tower-a"); val (a2, _) = scoped(engine, "a2", "tower-a"); val (b, _) = scoped(engine, "b", "tower-b")
        val running = Collections.synchronizedSet(HashSet<String>())
        val gate = CompletableDeferred<Unit>()
        listOf(a1 to "x", a1 to "y", a2 to "z").forEach { (s, k) -> s.enqueue(k) { running.add("${s.namespace}:$k"); gate.await() } }
        delay(80)
        assertEquals("only two of the same Tower run", 2, running.size)
        b.enqueue("other") { running.add("b:other"); gate.await() }
        delay(80)
        assertTrue("another Tower still gets a slot", "b:other" in running)
        gate.complete(Unit)
        engine.waitUntilIdle()
        assertEquals(4, running.size)
    }

    @Test fun waitingForOneWorkspaceIgnoresAnother() = test {
        val engine = SyncEngine(maxConcurrent = 4)
        val (a, _) = scoped(engine, "a"); val (b, _) = scoped(engine, "b")
        val hold = CompletableDeferred<Unit>()
        b.enqueue("long") { hold.await() }
        a.enqueue("quick") { delay(10) }
        withTimeout(2_000) { a.waitUntilIdle() }
        assertFalse(engine.snapshot().isIdle)
        hold.complete(Unit)
        engine.waitUntilIdle()
    }
}
