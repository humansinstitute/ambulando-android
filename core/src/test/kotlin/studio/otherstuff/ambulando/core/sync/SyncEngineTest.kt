package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SyncEngineTest {
    private class Counter {
        private val current = AtomicInteger()
        val peak = AtomicInteger()
        val total = AtomicInteger()
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        fun enter(name: String = "") { total.incrementAndGet(); peak.accumulateAndGet(current.incrementAndGet(), ::maxOf); order.add(name) }
        fun leave() { current.decrementAndGet() }
    }

    private fun <T> test(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = runBlocking { withTimeout(15_000) { block() } }

    @Test fun backoffSchedule() {
        assertEquals(0L, SyncBackoff.delayMillis(0, 0.5))
        assertEquals(2_000L, SyncBackoff.delayMillis(1, 0.5))
        assertEquals(8_000L, SyncBackoff.delayMillis(3, 0.5))
        assertEquals(300_000L, SyncBackoff.delayMillis(20, 0.5))
        assertEquals(1_600L, SyncBackoff.delayMillis(1, 0.0))
        assertEquals(2_400L, SyncBackoff.delayMillis(1, 1.0))
    }

    @Test fun concurrencyIsBounded() = test {
        val engine = SyncEngine(maxConcurrent = 3)
        val counter = Counter()
        repeat(20) { i -> engine.enqueue("job$i") { counter.enter(); delay(10); counter.leave() } }
        engine.waitUntilIdle()
        assertEquals(20, counter.total.get())
        assertTrue(counter.peak.get() <= 3)
        assertTrue("jobs do run concurrently", counter.peak.get() > 1)
    }

    @Test fun duplicateKeysCoalesceWithOneRerun() = test {
        val engine = SyncEngine(maxConcurrent = 2)
        val counter = Counter()
        val gate = CompletableDeferred<Unit>()
        engine.enqueue("scopes:w") { counter.enter(); gate.await() }
        delay(20)
        repeat(5) { engine.enqueue("scopes:w") { counter.enter() } }
        assertEquals(setOf("scopes:w"), engine.snapshot().running)
        gate.complete(Unit)
        engine.waitUntilIdle()
        assertEquals("first run plus exactly one rerun for the five mid-flight requests", 2, counter.total.get())
    }

    @Test fun visibleWorkRunsBeforeBackgroundCatchUp() = test {
        val engine = SyncEngine(maxConcurrent = 1)
        val counter = Counter()
        val gate = CompletableDeferred<Unit>()
        engine.enqueue("blocker") { gate.await() }
        delay(10)
        repeat(3) { i -> engine.enqueue("bg$i", SyncPriority.BACKGROUND) { counter.enter("bg$i"); counter.leave() } }
        engine.enqueue("visible", SyncPriority.VISIBLE) { counter.enter("visible"); counter.leave() }
        gate.complete(Unit)
        engine.waitUntilIdle()
        assertEquals(listOf("visible", "bg0", "bg1", "bg2"), counter.order.toList())
    }

    @Test fun backgroundStormNeverTakesTheReservedSlot() = test {
        val engine = SyncEngine(maxConcurrent = 3, reservedForeground = 1)
        val gate = CompletableDeferred<Unit>()
        repeat(10) { i -> engine.enqueue("bg$i", SyncPriority.BACKGROUND) { gate.await() } }
        delay(30)
        assertEquals(2, engine.snapshot().running.size)
        val sent = engine.perform("send", SyncPriority.USER) { "sent" }
        assertEquals("sent", sent)
        engine.cancelAll()
    }

    @Test fun cancelAllStopsRunningAndQueuedWork() = test {
        val engine = SyncEngine(maxConcurrent = 2, reservedForeground = 0)
        val counter = Counter()
        repeat(10) { i -> engine.enqueue("long$i") { counter.enter(); delay(30_000) } }
        delay(30)
        val start = System.nanoTime()
        engine.cancelAll()
        engine.waitUntilIdle()
        assertTrue((System.nanoTime() - start) < 1_000_000_000)
        assertEquals("queued jobs never started", 2, counter.total.get())
        val snapshot = engine.snapshot()
        assertTrue(snapshot.isIdle)
        assertTrue("cancellation is not a failure", snapshot.errors.isEmpty())
    }

    @Test fun failureBacksOffAndForceBypasses() = test {
        val engine = SyncEngine(maxConcurrent = 1, random = { 0.5 })
        val counter = Counter()
        engine.enqueue("flaky") { counter.enter(); throw IllegalStateException("boom") }
        engine.waitUntilIdle()
        assertEquals(1, engine.failureCount("flaky"))
        assertNotNull(engine.snapshot().errors["flaky"])

        // Within the 2 s backoff a normal enqueue waits…
        engine.enqueue("flaky") { counter.enter() }
        delay(100)
        assertEquals(1, counter.total.get())
        assertEquals(setOf("flaky"), engine.snapshot().scheduled)
        // …but an explicit user refresh runs now and clears the error on success.
        engine.enqueue("flaky", force = true) { counter.enter() }
        engine.waitUntilIdle()
        assertEquals(2, counter.total.get())
        assertNull(engine.snapshot().errors["flaky"])
    }

    @Test fun delayedRetryNeverPostponesImmediateWork() = test {
        val engine = SyncEngine(maxConcurrent = 1)
        val ran = CompletableDeferred<Unit>()
        engine.enqueue("outbox", delayMillis = 60_000) { ran.complete(Unit) }
        assertEquals(setOf("outbox"), engine.snapshot().scheduled)
        engine.enqueue("outbox") { ran.complete(Unit) }
        ran.await()
    }

    @Test fun performReturnsErrorsWithoutBackoffAndDroppedJobsCancelTheWaiter() = test {
        val engine = SyncEngine(maxConcurrent = 1, reservedForeground = 0)
        val failure = runCatching { engine.perform("upload") { throw IllegalArgumentException("refused") } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, engine.failureCount("upload"))

        val gate = CompletableDeferred<Unit>()
        engine.enqueue("blocker") { gate.await() }
        delay(10)
        val waiting = async { runCatching { engine.perform("queued") { "never" } } }
        delay(20)
        engine.cancel("queued")
        assertTrue(waiting.await().exceptionOrNull() is CancellationException)
        gate.complete(Unit)
        engine.waitUntilIdle()
    }

    @Test fun workQueuedAfterCancelAllUnderTheSameKeyStillRunsWhenTheCancelledPredecessorUnwindsLate() = test {
        val engine = SyncEngine(maxConcurrent = 2, reservedForeground = 0)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        // The predecessor ignores cancellation until released (a transport that does not honour it).
        engine.enqueue("resume") { started.complete(Unit); kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() } }
        started.await()
        engine.cancelAll()
        val successor = async { runCatching { engine.perform("resume") { "validated" } } }
        delay(30)
        release.complete(Unit)
        assertEquals("a job issued after the cancel is not the cancelled one", "validated", successor.await().getOrNull())
        engine.waitUntilIdle()
    }

    @Test fun statusFlowReportsRunningAndIdle() = test {
        val engine = SyncEngine(maxConcurrent = 1)
        val gate = CompletableDeferred<Unit>()
        engine.enqueue("discover") { gate.await() }
        delay(20)
        assertEquals(setOf("discover"), engine.status.value.running)
        gate.complete(Unit)
        engine.waitUntilIdle()
        delay(5)
        assertTrue(engine.status.value.isIdle)
    }
}
