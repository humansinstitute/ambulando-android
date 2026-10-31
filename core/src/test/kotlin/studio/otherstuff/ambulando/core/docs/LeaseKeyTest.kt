package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority

class LeaseKeyTest {
    @Test fun leaseCallsOfDifferentSessionsNeverShareAKeyButOneSessionKeepsItsOrder() {
        val a = leaseKey("c", "w", "d", "session-a")
        assertNotEquals(a, leaseKey("c", "w", "d", "session-b"))
        assertEquals(a, leaseKey("c", "w", "d", "session-a"))
        assertNotEquals(a, leaseKey("c", "w2", "d", "session-a"))
        assertNotEquals(a, leaseKey("c2", "w", "d", "session-a"))
    }

    /** An editor recreated by Android releases its old session while the new one acquires: neither may drop the other. */
    @Test fun anOldSessionsReleaseSurvivesTheNewSessionsAcquire() = runBlocking {
        val engine = SyncEngine()
        try {
            val renewRunning = CompletableDeferred<Unit>()
            val letRenewFinish = CompletableDeferred<Unit>()
            val order = java.util.Collections.synchronizedList(mutableListOf<String>())
            val renew = async(Dispatchers.Default) { engine.perform(leaseKey("c", "w", "d", "old"), SyncPriority.USER) { renewRunning.complete(Unit); letRenewFinish.await(); order.add("renew-old") } }
            renewRunning.await()
            val release = async(Dispatchers.Default) { engine.perform(leaseKey("c", "w", "d", "old"), SyncPriority.USER) { order.add("release-old") } }
            val acquire = async(Dispatchers.Default) { engine.perform(leaseKey("c", "w", "d", "new"), SyncPriority.USER) { order.add("acquire-new") } }
            delay(300) // both are queued behind the running renew
            letRenewFinish.complete(Unit)
            withTimeout(5_000) { renew.await(); release.await(); acquire.await() }
            assertEquals(setOf("renew-old", "release-old", "acquire-new"), order.toSet())
            assertEquals("the old session's own calls stay in order", listOf("renew-old", "release-old"), order.filter { it.endsWith("old") })
        } finally { engine.close() }
    }
}
