package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalStagesTest {
    @Test fun blockedStageIsObservableAndNestedStagesHaveLocalCorrelationWithoutReplayingWork() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch(Dispatchers.Default) {
            LocalStages.trace(LocalStages.Stage.WRITE_ATTEMPT) {
                LocalStages.trace(LocalStages.Stage.UPLOAD_PREPARE) {
                    calls++; entered.complete(Unit); release.await()
                }
            }
        }
        entered.await()
        val before = LocalStages.snapshot()
        val prepare = before.last { it.stage == LocalStages.Stage.UPLOAD_PREPARE }
        assertEquals(LocalStages.Result.BEGIN, prepare.result)
        assertTrue(prepare.parent > 0)
        assertFalse(before.any { it.operation == prepare.operation && it.result == LocalStages.Result.END })
        release.complete(Unit); job.join()
        assertEquals(1, calls)
        assertTrue(LocalStages.snapshot().any { it.operation == prepare.operation && it.result == LocalStages.Result.END })
    }
    @Test fun cancellationAndFailureNeverRecordExceptionDataOrRetry() = runBlocking {
        var calls = 0
        for (error in listOf(IllegalStateException("secret URL identity file grant"), CancellationException("secret"))) {
            runCatching { LocalStages.trace(LocalStages.Stage.UPLOAD_LINK) { calls++; throw error } }
        }
        assertEquals(2, calls)
        assertEquals(LocalStages.Result.CANCELLED, LocalStages.snapshot().last().result)
        assertFalse(LocalStages.snapshot().toString().contains("secret"))
        assertFalse(ConnectionFailure.validationMessage(IllegalStateException("secret")).contains("secret"))
        val grant = SharedConnectionException(ConnectionIssue.PEER, "secret provider reply")
        runCatching { LocalStages.trace(LocalStages.Stage.PROVIDER_CHECK) { throw grant } }
        assertEquals(LocalStages.Result.PEER_REJECTED, LocalStages.snapshot().last().result)
        assertEquals(ConnectionIssue.PEER.userMessage, ConnectionFailure.validationMessage(grant))
        assertEquals(ConnectionIssue.PEER.userMessage, ConnectionFailure.userMessage(grant))
    }
    @Test fun boundedNumericSinkCannotBreakWorkAndBlockingStagesRetainParent() = runBlocking {
        val old = LocalStages.observer
        try {
            LocalStages.observer = { throw IllegalStateException("sink failure") }
            repeat(400) { LocalStages.emit(LocalStages.next(), LocalStages.Stage.DNS, LocalStages.Result.BEGIN) }
            assertEquals(256, LocalStages.snapshot().size)
            val answer = LocalStages.trace(LocalStages.Stage.LEASE_ADMISSION) { LocalStages.blocking(LocalStages.Stage.PROVIDER_CHECK) { 42 } }
            assertEquals(42, answer)
            val last = LocalStages.snapshot().takeLast(4)
            assertEquals(last.first().operation, last[1].parent)
            assertTrue(last.zipWithNext().all { (a,b) -> a.sequence < b.sequence && a.elapsedMillis <= b.elapsedMillis })
        } finally { LocalStages.observer = old }
    }
}
