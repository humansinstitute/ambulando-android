package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.support.*
import java.net.SocketTimeoutException
import java.net.SocketException
import javax.net.ssl.SSLHandshakeException

class ConnectionRecoveryTest {
    @Test fun timeoutThenLostConnectionRecoversWithoutUserActionAndSignsEachAttempt() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        val identity = makeIdentity()
        val client = TowerClient(TowerOrigin(Fixtures.ORIGIN), object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                requests.add(request)
                when (requests.size) { 1 -> throw SocketTimeoutException(); 2 -> throw SocketException("reset") }
                return HttpResponse(200, "ok".toByteArray())
            }
        }, identity)
        val engine = SyncEngine(retryDelay = { 1100 })
        try {
            engine.enqueueRead("inbox:w") { client.request(path = "/api/v4/flightdeck-pg/workspaces/w/resource-view-states") }
            withTimeout(10_000) { engine.waitUntilIdle() }
            assertEquals(3, requests.size)
            assertEquals(3, requests.map { it.header("Authorization") }.toSet().size)
            assertTrue(engine.snapshot().connectionFailures.isEmpty())
            assertTrue(engine.snapshot().errors.isEmpty())
        } finally { engine.close() }
    }

    @Test fun attemptsAreCappedAndAuthCertificatesValidationAndWritesAreNeverAutomaticallyRetried() = runBlocking {
        for (error in listOf(SocketTimeoutException(), TowerError.Http(503, null, null), TowerError.Http(401, null, null), TowerError.Http(403, null, null), SSLHandshakeException("certificate"), IllegalArgumentException("validation"))) {
            val engine = SyncEngine(retryDelay = { 1 })
            var attempts = 0
            try {
                engine.enqueueRead("read") { attempts++; throw error }
                withTimeout(2000) { engine.waitUntilIdle() }
                assertEquals(if (ConnectionFailure.isTransient(error)) 3 else 1, attempts)
                assertEquals(ConnectionFailure.isTransient(error), "read" in engine.snapshot().connectionFailures)
                engine.enqueue("write") { attempts++; throw SocketTimeoutException() }
                withTimeout(2000) { engine.waitUntilIdle() }
                assertEquals(if (ConnectionFailure.isTransient(error)) 4 else 2, attempts)
            } finally { engine.close() }
        }
    }

    @Test fun cancellationDuringBackoffDoesNotRequeueAndScheduledUnrelatedWorkDoesNotReconnect() = runBlocking {
        val retried = CompletableDeferred<Unit>()
        val engine = SyncEngine(retryDelay = { 60_000 }, retryEvent = { retried.complete(Unit) })
        var attempts = 0
        try {
            engine.enqueueRead("inbox:w") { attempts++; throw SocketTimeoutException() }
            withTimeout(2000) { retried.await() }
            assertEquals("Connection lost", connectionStatus(true, engine.snapshot()))
            engine.cancel("inbox:w"); withTimeout(2000) { engine.waitUntilIdle() }
            assertTrue(engine.snapshot().connectionFailures.isEmpty())
            assertEquals(0, engine.failureCount("inbox:w"))
            delay(30)
            assertEquals(1, attempts)
            engine.recovered("inbox:w")
            engine.enqueue("other", delayMillis = 60_000) { }
            assertEquals("Connected", connectionStatus(false, engine.snapshot()))
        } finally { engine.cancelAll(); engine.close() }
    }

    @Test fun alternateRecoveryCancelsObsoleteScheduledRetry() = runBlocking {
        val scheduled = CompletableDeferred<Unit>()
        val engine = SyncEngine(retryDelay = { 60_000 }, retryEvent = { if (it == "retry") scheduled.complete(Unit) })
        try {
            engine.enqueueRead("inbox:w") { throw SocketTimeoutException() }
            scheduled.await()
            engine.recovered("inbox:w")
            withTimeout(2000) { engine.waitUntilIdle() }
            assertTrue(engine.snapshot().scheduled.isEmpty())
            assertTrue(engine.snapshot().connectionFailures.isEmpty())
            assertEquals("Reconnecting", connectionStatus(true, SyncSnapshot(running = setOf("inbox:w"), connectionFailures = setOf("inbox:w"))))
            assertEquals("Connection lost", connectionStatus(true, SyncSnapshot(running = setOf("other"), connectionFailures = setOf("inbox:w"))))
        } finally { engine.close() }
    }

    @Test fun lateFailureAfterKeyCancellationCannotRestoreWarningOrRetry() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val engine = SyncEngine(retryDelay = { 1 })
        try {
            engine.enqueueRead("read") {
                withContext(NonCancellable) { started.complete(Unit); gate.await(); throw SocketTimeoutException() }
            }
            started.await(); engine.cancel("read"); gate.complete(Unit)
            withTimeout(2000) { engine.waitUntilIdle() }
            assertTrue(engine.snapshot().connectionFailures.isEmpty())
            assertTrue(engine.snapshot().errors.isEmpty())
            assertEquals(0, engine.failureCount("read"))
        } finally { engine.close() }
    }

    @Test fun automaticRefreshDoesNotResetExhaustedBudgetAndManualRetryDoes() = runBlocking {
        val engine = SyncEngine(retryDelay = { 1 })
        var attempts = 0
        try {
            engine.enqueueRead("read") { attempts++; throw SocketTimeoutException() }
            withTimeout(2000) { engine.waitUntilIdle() }
            assertEquals(3, attempts)
            engine.enqueueRead("read") { attempts++; throw SocketTimeoutException() }
            withTimeout(2000) { engine.waitUntilIdle() }
            assertEquals(4, attempts)
            engine.enqueueRead("read", force = true) { attempts++; if (attempts < 7) throw SocketTimeoutException() }
            withTimeout(2000) { engine.waitUntilIdle() }
            assertEquals(7, attempts)
            assertTrue(engine.snapshot().connectionFailures.isEmpty())
        } finally { engine.close() }
    }

    @Test fun cancellationOfActiveReadHasNoFailureOrRetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val engine = SyncEngine(retryDelay = { 1 })
        try {
            engine.enqueueRead("read") { started.complete(Unit); awaitCancellation() }
            started.await(); engine.cancel("read"); withTimeout(2000) { engine.waitUntilIdle() }
            assertEquals(0, engine.failureCount("read"))
            assertTrue(engine.snapshot().connectionFailures.isEmpty())
        } finally { engine.close() }
    }
}
