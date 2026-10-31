package studio.otherstuff.ambulando.core.workspaces

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WorkspaceStorePool
import studio.otherstuff.ambulando.core.store.outbox
import studio.otherstuff.ambulando.core.store.waitFor
import studio.otherstuff.ambulando.core.store.workspaces
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.transport.EventChunks
import studio.otherstuff.ambulando.core.transport.EventStreamTransport
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpResponseHead
import studio.otherstuff.ambulando.core.transport.HttpTransport
import java.io.File
import java.util.Collections

/**
 * Controlled interleavings of a runtime's lifecycle against its validation: a request is held in flight at a chosen point, the
 * competing lifecycle step runs, and only then is the request released. Synthetic Tower; the invariant under test is that
 * nothing durable is sent unless the boundary is validated in the current generation.
 */
class RuntimeRaceTests {
    private class Hold(val matches: (HttpRequest) -> Boolean) {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class World(val identity: IdentityService, val tower: SampleTower) {
        val posts = Collections.synchronizedList(ArrayList<String>())
        val listings = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var hold: Hold? = null
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = request.url.substringBefore('?')
                if (request.method == "POST" && path.endsWith("/messages")) posts.add(path)
                if (request.method == "GET" && path.endsWith("/api/v4/flightdeck-pg/workspaces")) listings.incrementAndGet()
                hold?.takeIf { it.matches(request) }?.let { h -> h.arrived.complete(Unit); h.release.await() }
                return tower.send(request)
            }
        }
        val events = object : EventStreamTransport {
            override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> = tower.events.open(request)
        }
        val engine = SyncEngine(maxConcurrent = 4, maxPerGroup = 3)
        val catalog = LocalStore(tempDir())
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val runtimes = WorkspaceRuntimes(catalog, pool) { hook ->
            RuntimeEnvironment(engine, identity, http, events, null, tempDir(), tempDir(), tempDir(), null, Dispatchers.Unconfined, hook)
        }
        val catalogSync = WorkspaceSync(catalog, engine, identity, http)

        suspend fun connect(): ConnectionRecord {
            val c = catalogSync.connect(tower.origin)
            runtimes.discover(c, SyncPriority.USER, true)
            withTimeout(15_000) { engine.waitUntilIdle() }
            return c
        }
        suspend fun studio(c: ConnectionRecord) = catalog.reader.workspaces(c.id).first { it.id == SampleIds.STUDIO }
        fun target(c: ConnectionRecord) = ChatTarget(c.id, SampleIds.STUDIO, SampleIds.GENERAL)
        suspend fun holdNext(matches: (HttpRequest) -> Boolean): Hold = Hold(matches).also { hold = it }
        fun releaseHeld() { hold?.release?.complete(Unit); hold = null }
    }

    private fun <T> test(block: suspend World.() -> T): T = runBlocking {
        val tower = SampleTower(tempDir(), originUrl = "https://one.sample.invalid").also { it.prepare(TestKeys.NPUB) }
        val w = World(makeIdentity(), tower)
        try { withTimeout(60_000) { w.block() } } finally { w.releaseHeld(); runCatching { w.runtimes.close() }; w.engine.close() }
    }

    private val isListing: (HttpRequest) -> Boolean = { it.method == "GET" && it.url.substringBefore('?').endsWith("/api/v4/flightdeck-pg/workspaces") }

    @Test fun aRuntimeThatWasClosedByDisconnectCannotBeStartedAgainByALateEnable() = test {
        val c = connect()
        val r = runtimes.enable(c, studio(c))
        waitFor(20_000) { r.canSend }
        r.close()
        assertFalse(r.activity.isOpen); assertFalse(r.canSend)
        // The late enable of a listing that was already on its way holds the runtime object and calls start() on it.
        r.start(foreground = true, force = true)
        r.updateWorkspace(r.workspace.copy(accessRemoved = true)); r.updateWorkspace(r.workspace.copy(accessRemoved = false))
        r.start(foreground = true, force = true)
        r.awaitStarted()
        assertFalse("a closed runtime's read gate must stay shut", r.activity.isOpen)
        assertFalse(r.canSend); assertFalse(r.enabled); assertFalse(r.validated)
        r.outbox.send("after disconnect", emptyList(), target(c), c, r.workspace)
        r.resumeWrites(); r.kickSenders()
        engine.waitUntilIdle()
        assertTrue(posts.isEmpty())
    }

    @Test fun pauseWhileTheAccessListingIsInFlightKeepsWritesClosedAndDropsTheAnswer() = test {
        val c = connect()
        val h = holdNext(isListing)
        val r = runtimes.enable(c, studio(c))
        withTimeout(10_000) { h.arrived.await() }
        r.outbox.send("queued while validating", emptyList(), target(c), c, r.workspace)
        runtimes.disable(r.boundary.id)
        releaseHeld()
        r.awaitStarted(); engine.waitUntilIdle()
        assertFalse(r.validated); assertFalse(r.canSend); assertFalse(r.activity.isOpen)
        r.resumeWrites(); r.kickSenders(); engine.waitUntilIdle()
        assertTrue(posts.isEmpty())
        assertEquals(1, r.store.reader.outbox(c.id, c.signerNpub).size)
    }

    @Test fun pausesLandingAtArbitraryPointsOfAValidationNeverLeaveAnOpenWriteGate() = test {
        val c = connect()
        val r = runtimes.enable(c, studio(c))
        val random = java.util.Random(17)
        repeat(40) {
            runtimes.enable(c, studio(c), force = true)
            kotlinx.coroutines.delay(random.nextInt(25).toLong())
            runtimes.disable(r.boundary.id)
            // Whatever the validation was doing when the pause landed, its late result must not open anything.
            r.awaitStarted(); kotlinx.coroutines.delay(random.nextInt(10).toLong())
            assertFalse("write gate open after pause (round $it)", r.canSend)
            assertFalse("read gate open after pause (round $it)", r.activity.isOpen)
            assertFalse(r.validated)
        }
        runtimes.enable(c, studio(c), force = true)
        waitFor(20_000) { r.canSend }
    }

    @Test fun accessLostWhileValidationIsInFlightNeverOpensWritesEvenIfTheStaleAnswerSaysYes() = test {
        val c = connect()
        val h = holdNext(isListing)
        val r = runtimes.enable(c, studio(c))
        withTimeout(10_000) { h.arrived.await() }
        // The catalog learns of the loss while this runtime's own listing (taken before the loss) is still in flight.
        r.updateWorkspace(r.workspace.copy(accessRemoved = true))
        releaseHeld()
        r.awaitStarted(); engine.waitUntilIdle()
        assertTrue(r.workspace.accessRemoved)
        assertFalse(r.validated); assertFalse(r.canSend); assertFalse("reads closed too", r.activity.isOpen)
        r.outbox.send("after loss", emptyList(), target(c), c, r.workspace)
        r.resumeWrites(); r.kickSenders(); engine.waitUntilIdle()
        assertTrue(posts.isEmpty())
    }

    @Test fun afterAccessIsLostReadsStreamAndDirectWritesAreRefusedAndNoQueuedJobCanBypass() = test {
        val c = connect()
        val r = runtimes.enable(c, studio(c))
        waitFor(20_000) { r.canSend }
        r.setForeground(true)
        tower.revokedWorkspaces = setOf(SampleIds.STUDIO)
        runtimes.discover(c, SyncPriority.USER, true); engine.waitUntilIdle()
        val url = "https://one.sample.invalid/api/v4/flightdeck-pg/workspaces/${SampleIds.STUDIO}/scopes"
        assertTrue(runCatching { r.readTransport.send(HttpRequest("GET", url)) }.exceptionOrNull() is studio.otherstuff.ambulando.core.sync.WorkspacePaused)
        assertTrue(runCatching { r.directTransport.send(HttpRequest("POST", "$url/x")) }.exceptionOrNull() is studio.otherstuff.ambulando.core.sync.WritesNotReady)
        // Work queued through the scheduler after the loss is never run (the lease is gone), and a forced kick sends nothing.
        var ran = false
        r.engine.enqueue("late", SyncPriority.USER) { ran = true }
        r.setForeground(true)
        r.outbox.send("after loss", emptyList(), target(c), c, r.workspace)
        r.kickSenders(); r.writes.kick(c); engine.waitUntilIdle()
        assertFalse(ran); assertTrue(posts.isEmpty())
        assertEquals(1, r.store.reader.outbox(c.id, c.signerNpub).size)
    }

    @Test fun regainedAccessIsRevalidatedWithAFreshListingBeforeAnythingIsSent() = test {
        val c = connect()
        val r = runtimes.enable(c, studio(c))
        waitFor(20_000) { r.canSend }
        tower.revokedWorkspaces = setOf(SampleIds.STUDIO)
        runtimes.discover(c, SyncPriority.USER, true); engine.waitUntilIdle()
        assertTrue(r.workspace.accessRemoved); assertFalse(r.canSend); assertFalse(r.validated); assertFalse(r.activity.isOpen)
        r.outbox.send("pending across the loss", emptyList(), target(c), c, r.workspace)
        tower.revokedWorkspaces = emptySet()
        runtimes.discover(c, SyncPriority.USER, true); engine.waitUntilIdle()
        assertFalse(r.workspace.accessRemoved)
        assertFalse("listing alone does not reopen writes", r.canSend)
        r.resumeWrites(); r.kickSenders(); engine.waitUntilIdle()
        assertTrue(posts.isEmpty())
        val before = listings.get()
        val h = holdNext(isListing)
        runtimes.enable(c, studio(c))
        withTimeout(10_000) { h.arrived.await() }
        assertTrue("a new access listing was requested", listings.get() > before)
        assertFalse("still closed while the fresh listing is outstanding", r.canSend)
        releaseHeld()
        waitFor(20_000) { r.canSend }
        waitFor(20_000) { posts.size == 1 }
    }
}
