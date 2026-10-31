package studio.otherstuff.ambulando.core.workspaces

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.connections
import studio.otherstuff.ambulando.core.store.writes
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WorkspaceStorePool
import studio.otherstuff.ambulando.core.store.latestMessages
import studio.otherstuff.ambulando.core.store.outbox
import studio.otherstuff.ambulando.core.store.waitFor
import studio.otherstuff.ambulando.core.store.workspaces
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.sync.catchUpEventsAndWait
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpResponseHead
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import java.io.File
import java.net.URI
import java.util.Collections

/**
 * Two synthetic Towers serving the SAME workspace ids to the same identity, driven through real stores, partitions, a shared
 * scheduler and real signing. Nothing here is evidence of live Tower behaviour.
 */
class MultiWorkspaceRuntimeTests {
    private class World(val identity: IdentityService, val a: SampleTower, val b: SampleTower) {
        val posts = Collections.synchronizedList(ArrayList<String>())
        private fun route(url: String): SampleTower = if (URI(url).host == a.origin.host) a else b
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if (request.method == "POST" && request.url.substringBefore('?').endsWith("/messages")) posts.add(URI(request.url).host)
                return route(request.url).send(request)
            }
        }
        val events = object : EventStreamTransport {
            override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> = route(request.url).events.open(request)
        }
        val engine = SyncEngine(maxConcurrent = 4, maxPerGroup = 3)
        val catalog: LocalStore = LocalStore(tempDir())
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val runtimes = WorkspaceRuntimes(catalog, pool) { hook ->
            RuntimeEnvironment(engine, identity, http, events, null, tempDir(), tempDir(), tempDir(), null, Dispatchers.Unconfined, hook)
        }
        val catalogSync = WorkspaceSync(catalog, engine, identity, http)

        suspend fun connect(tower: SampleTower): ConnectionRecord {
            val c = catalogSync.connect(tower.origin)
            runtimes.discover(c, SyncPriority.USER, true)
            withTimeout(15_000) { engine.waitUntilIdle() }
            return c
        }

        suspend fun studio(c: ConnectionRecord) = catalog.reader.workspaces(c.id).first { it.id == SampleIds.STUDIO }
    }

    private fun world(): World = runBlocking {
        val identity = makeIdentity()
        val a = SampleTower(tempDir(), originUrl = "https://one.sample.invalid").also { it.prepare(TestKeys.NPUB) }
        val b = SampleTower(tempDir(), originUrl = "https://two.sample.invalid").also { it.prepare(TestKeys.NPUB) }
        World(identity, a, b)
    }

    private fun <T> test(block: suspend World.() -> T): T = runBlocking { val w = world(); try { withTimeout(60_000) { w.block() } } finally { runCatching { w.runtimes.close() }; w.engine.close() } }

    private suspend fun World.settle() { withTimeout(20_000) { engine.waitUntilIdle() } }

    @Test fun identicalWorkspaceIdsOnTwoTowersRunSideBySideWithSeparateStoresAndWrites() = test {
        val ca = connect(a); val cb = connect(b)
        assertNotEquals(ca.id, cb.id)
        val ra = runtimes.enable(ca, studio(ca)); val rb = runtimes.enable(cb, studio(cb))
        waitFor(20_000) { ra.canSend && rb.canSend }; settle()
        assertEquals(ra.workspace.id, rb.workspace.id)
        assertNotEquals(ra.boundary.id, rb.boundary.id)
        assertNotEquals(ra.store.path, rb.store.path)
        val general = SampleIds.GENERAL
        assertTrue(ra.store.reader.latestMessages(SampleIds.STUDIO, general, 50).isNotEmpty())
        assertTrue(rb.store.reader.latestMessages(SampleIds.STUDIO, general, 50).isNotEmpty())

        // A write composed in A's runtime is signed for and sent to A's Tower only.
        ra.outbox.send("only for tower one", emptyList(), ChatTarget(ca.id, SampleIds.STUDIO, general), ca, studio(ca))
        settle()
        assertEquals(listOf("one.sample.invalid"), posts.toList())
        assertTrue(ra.store.reader.latestMessages(SampleIds.STUDIO, general, 500).any { it.body == "only for tower one" })
        assertFalse(rb.store.reader.latestMessages(SampleIds.STUDIO, general, 500).any { it.body == "only for tower one" })
        assertTrue(rb.store.reader.outbox(cb.id, cb.signerNpub).isEmpty())
    }

    @Test fun pausingOneWorkspaceLeavesTheOtherSyncingAndKeepsPausedWorkSafe() = test {
        val ca = connect(a); val cb = connect(b)
        val ra = runtimes.enable(ca, studio(ca)); val rb = runtimes.enable(cb, studio(cb))
        waitFor(20_000) { ra.canSend && rb.canSend }; settle()
        runtimes.disable(ra.boundary.id)
        assertFalse(ra.canSend); assertFalse(ra.activity.isOpen)
        // A draft/outbox write while paused is local only: it is durable and nothing is sent.
        ra.outbox.send("queued while paused", emptyList(), ChatTarget(ca.id, SampleIds.STUDIO, SampleIds.GENERAL), ca, studio(ca))
        a.postAsMember(body = "tower one news")
        b.postAsMember(body = "tower two news")
        rb.sync.catchUpEventsAndWait(cb, rb.workspace)
        waitFor(20_000) { rb.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == "tower two news" } }
        assertFalse(ra.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == "tower one news" })
        assertTrue(posts.isEmpty())
        assertEquals("queued while paused", ra.store.reader.outbox(ca.id, ca.signerNpub).single().body)
        // Re-enable: revalidated and caught up first, then the queued message is sent exactly once.
        runtimes.enable(ca, studio(ca))
        waitFor(20_000) { ra.canSend }; settle()
        waitFor(20_000) { posts.size == 1 }
        assertEquals(listOf("one.sample.invalid"), posts.toList())
        assertTrue(ra.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == "tower one news" })
        waitFor(20_000) { ra.store.reader.outbox(ca.id, ca.signerNpub).isEmpty() }
    }

    @Test fun lostAccessClosesWritesButKeepsReceivedContentAndPendingWork() = test {
        val ca = connect(a); val cb = connect(b)
        val ra = runtimes.enable(ca, studio(ca)); val rb = runtimes.enable(cb, studio(cb))
        waitFor(20_000) { ra.canSend && rb.canSend }; settle()
        a.revokedWorkspaces = setOf(SampleIds.STUDIO)
        runtimes.discover(ca, SyncPriority.USER, true); settle()
        assertTrue(ra.workspace.accessRemoved)
        assertFalse(ra.canSend)
        assertTrue("received content stays readable", ra.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 50).isNotEmpty())
        // A write attempted afterwards stays queued and is not sent with any connection.
        ra.outbox.send("after loss", emptyList(), ChatTarget(ca.id, SampleIds.STUDIO, SampleIds.GENERAL), ca, ra.workspace)
        settle()
        assertTrue(posts.isEmpty())
        assertEquals(1, ra.store.reader.outbox(ca.id, ca.signerNpub).size)
        // The other Tower is untouched and still sends.
        assertTrue(rb.canSend)
        rb.outbox.send("still fine", emptyList(), ChatTarget(cb.id, SampleIds.STUDIO, SampleIds.GENERAL), cb, studio(cb))
        settle()
        assertEquals(listOf("two.sample.invalid"), posts.toList())
        // Access returns: nothing is sent until the runtime revalidates, then the pending message goes to its own Tower.
        a.revokedWorkspaces = emptySet()
        runtimes.discover(ca, SyncPriority.USER, true); settle()
        assertFalse(ra.workspace.accessRemoved)
        assertFalse("write gate stays shut until revalidation", ra.canSend)
        runtimes.enable(ca, studio(ca))
        waitFor(20_000) { ra.canSend }; settle()
        waitFor(20_000) { posts.toList() == listOf("two.sample.invalid", "one.sample.invalid") }
    }

    @Test fun nothingIsSentUntilTheBoundaryHasBeenRevalidatedAfterAResume() = test {
        val ca = connect(a)
        a.offline = true
        val ra = runtimes.enable(ca, studio(ca))
        kotlinx.coroutines.delay(500)
        assertTrue("reads are open for cached navigation", ra.activity.isOpen)
        assertFalse("but access could not be revalidated", ra.canSend)
        ra.outbox.send("composed offline", emptyList(), ChatTarget(ca.id, SampleIds.STUDIO, SampleIds.GENERAL), ca, studio(ca))
        a.offline = false
        // Even a direct kick (not through the runtime) cannot send through the closed write gate.
        ra.outbox.kick(ca); kotlinx.coroutines.delay(500); settle()
        assertTrue(posts.isEmpty())
        assertEquals(1, ra.store.reader.outbox(ca.id, ca.signerNpub).size)
        // A resume revalidates, catches up, opens the gate and only then sends - once.
        runtimes.enable(ca, studio(ca))
        waitFor(20_000) { posts.size == 1 }; settle()
        assertEquals(listOf("one.sample.invalid"), posts.toList())
        waitFor(20_000) { ra.store.reader.outbox(ca.id, ca.signerNpub).isEmpty() }
    }

    @Test fun disconnectRetiresOnlyThatTowerAndAReconnectStartsFresh() = test {
        val ca = connect(a); val cb = connect(b)
        val ra = runtimes.enable(ca, studio(ca)); val rb = runtimes.enable(cb, studio(cb))
        waitFor(20_000) { ra.canSend && rb.canSend }; settle()
        a.offline = true
        ra.outbox.send("never to be resurrected", emptyList(), ChatTarget(ca.id, SampleIds.STUDIO, SampleIds.GENERAL), ca, studio(ca))
        runtimes.removeConnection(ca)
        a.offline = false
        assertTrue(runtimes.runtime(ra.boundary.id) == null)
        assertTrue(rb.canSend)
        val ca2 = connect(a)
        val ra2 = runtimes.enable(ca2, studio(ca2))
        waitFor(20_000) { ra2.canSend }; settle()
        assertTrue(ra2.store.reader.outbox(ca2.id, ca2.signerNpub).isEmpty())
        assertTrue(posts.isEmpty())
    }

    @Test fun aListingThatArrivesAfterDisconnectCannotReopenTheConnection() = test {
        val ca = connect(a)
        val workspace = studio(ca)
        val ra = runtimes.enable(ca, workspace)
        waitFor(20_000) { ra.canSend }; settle()
        runtimes.removeConnection(ca)
        // The catalog listing that was already being applied reaches enable() after the runtimes were closed.
        val late = runCatching { runtimes.enable(ca, workspace) }.exceptionOrNull()
        assertTrue(late is kotlinx.coroutines.CancellationException)
        assertTrue(runtimes.runtime(ra.boundary.id) == null)
        assertFalse(ra.boundary.id in pool.openBoundaryIds())
        assertTrue(catalog.reader.connections().none { it.id == ca.id })
    }

    @Test fun aScreenThatKeptAnotherWorkspaceCannotQueueWorkInThisOne() = test {
        val ca = connect(a); val cb = connect(b)
        val ra = runtimes.enable(ca, studio(ca))
        waitFor(20_000) { ra.canSend }; settle()
        // The same workspace id on the other Tower: a late callback from a screen that was showing it names B's connection.
        val refusedMessage = runCatching { ra.outbox.send("wrong place", emptyList(), ChatTarget(cb.id, SampleIds.STUDIO, SampleIds.GENERAL), cb, studio(cb)) }.exceptionOrNull()
        assertTrue(refusedMessage is BoundaryViolation)
        val reaction = WriteIntent(WriteKind.REACTION_ADD, SampleIds.GENERAL, kotlinx.serialization.json.buildJsonObject {
            put("target_type", kotlinx.serialization.json.JsonPrimitive("message")); put("emoji", kotlinx.serialization.json.JsonPrimitive("heart"))
        })
        val refusedWrite = runCatching { ra.writes.submit(reaction, cb, studio(cb)) }.exceptionOrNull()
        assertTrue(refusedWrite is BoundaryViolation)
        assertTrue(ra.store.reader.outbox(ca.id, ca.signerNpub).isEmpty() && ra.store.reader.outbox(cb.id, cb.signerNpub).isEmpty())
        assertTrue(ra.store.reader.writes(ca.id, ca.signerNpub).isEmpty() && ra.store.reader.writes(cb.id, cb.signerNpub).isEmpty())
        // Its own workspace is unaffected.
        ra.writes.submit(reaction, ca, studio(ca))
        assertEquals(1, ra.store.reader.writes(ca.id, ca.signerNpub).size)
    }

    @Test fun theBoundaryGuardRefusesAnyRequestOutsideItsOwnOriginWorkspaceAndApplication() = runBlocking {
        val boundary = WorkspaceBoundary("https://one.sample.invalid", TestKeys.NPUB, "npub1app", SampleIds.STUDIO)
        val reached = Collections.synchronizedList(ArrayList<String>())
        val guard = BoundaryGuardTransport(object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse { reached.add(request.url); return HttpResponse(200, ByteArray(0)) }
        }, boundary)
        val ok = "https://one.sample.invalid/api/v4/flightdeck-pg/workspaces/${SampleIds.STUDIO}/scopes"
        guard.send(HttpRequest("GET", ok))
        for (bad in listOf(
            "https://two.sample.invalid/api/v4/flightdeck-pg/workspaces/${SampleIds.STUDIO}/scopes",
            "https://one.sample.invalid/api/v4/flightdeck-pg/workspaces/${SampleIds.LAB}/scopes",
        )) {
            val failure = runCatching { guard.send(HttpRequest("GET", bad)) }.exceptionOrNull()
            assertTrue(bad, failure is BoundaryViolation)
        }
        guard.send(HttpRequest("GET", "https://one.sample.invalid/api/v4/flightdeck-pg/workspaces"))
        assertTrue(runCatching { guard.send(HttpRequest("POST", "https://one.sample.invalid/api/v4/flightdeck-pg/workspaces")) }.exceptionOrNull() is BoundaryViolation)
        val wrongApp = runCatching { guard.send(HttpRequest("GET", ok, mapOf("x-flightdeck-pg-app-npub" to "npub1other"))) }.exceptionOrNull()
        assertTrue(wrongApp is BoundaryViolation)
        assertEquals(listOf(ok, "https://one.sample.invalid/api/v4/flightdeck-pg/workspaces"), reached.toList())
    }
}
