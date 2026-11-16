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
import studio.otherstuff.ambulando.core.store.notificationSync
import studio.otherstuff.ambulando.core.store.NotificationSyncState
import studio.otherstuff.ambulando.core.store.resetNotificationCursor
import studio.otherstuff.ambulando.core.store.setState
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
import studio.otherstuff.ambulando.core.store.syncableChannels
import studio.otherstuff.ambulando.core.sync.refreshTasks
import studio.otherstuff.ambulando.core.sync.refreshDocs
import studio.otherstuff.ambulando.core.sync.refreshInbox
import studio.otherstuff.ambulando.core.sync.refreshFiles
import studio.otherstuff.ambulando.core.sync.refreshNotifications
import studio.otherstuff.ambulando.core.surfaces.refreshPins
import studio.otherstuff.ambulando.core.sync.AttentionPreferenceState
import studio.otherstuff.ambulando.core.sync.attentionPreference
import kotlinx.serialization.json.*
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
        val requests = Collections.synchronizedList(ArrayList<HttpRequest>())
        @Volatile var denyScoped = false
        @Volatile var denyScopedStatus = false
        @Volatile var pagedNotificationRecovery = false
        val notificationPages = java.util.concurrent.atomic.AtomicInteger()
        fun targeted(request: HttpRequest): Boolean = request.url.contains("/${SampleIds.STUDIO}/") &&
            (request.url.contains("/record-sync/priority?") || request.url.contains("/me/preferences/") || request.url.contains("/notifications/") || request.url.contains("/resource-view-states") || request.url.substringBefore('?').endsWith("/scopes") ||
                Regex("/channels/[^/]+/(tasks|docs|files)(\\?|$)").containsMatchIn(request.url))
        val posts = Collections.synchronizedList(ArrayList<String>())
        val listings = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var hold: Hold? = null
        @Volatile var denyListing = false
        val http = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                requests.add(request)
                if (targeted(request)) {
                    if (denyScoped) throw studio.otherstuff.ambulando.core.transport.SharedConnectionException(studio.otherstuff.ambulando.core.transport.ConnectionIssue.CONSENT)
                    if (denyScopedStatus) return HttpResponse(403, "{}".toByteArray())
                }
                if (pagedNotificationRecovery && request.url.contains("/notifications/items?")) {
                    val page = notificationPages.incrementAndGet()
                    if (page > WorkspaceSync.NOTIFICATION_PAGE_BUDGET) return HttpResponse(403, "{}".toByteArray())
                    return HttpResponse(200, """{"items":[],"head_seq":1,"next_cursor":"page-$page"}""".toByteArray())
                }
                val path = request.url.substringBefore('?')
                if (request.method == "POST" && path.endsWith("/messages")) posts.add(path)
                if (request.method == "GET" && path.endsWith("/api/v4/flightdeck-pg/workspaces")) {
                    listings.incrementAndGet()
                    if (denyListing) throw studio.otherstuff.ambulando.core.transport.SharedConnectionException(studio.otherstuff.ambulando.core.transport.ConnectionIssue.CONSENT)
                }
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

    private suspend fun World.failedScopedReads(): WorkspaceRuntime {
        val c = connect()
        val r = runtimes.enable(c, studio(c))
        r.awaitStarted(); engine.waitUntilIdle()
        assertTrue(r.validated)
        denyScoped = true
        r.sync.refreshPins(c, r.workspace, force = true)
        r.sync.refreshNotifications(c, r.workspace, force = true)
        r.sync.refreshWorkspace(c, r.workspace, force = true)
        r.sync.refreshTasks(c, r.workspace, force = true)
        r.sync.refreshDocs(c, r.workspace, force = true)
        r.sync.refreshFiles(c, r.workspace, force = true)
        r.sync.refreshInbox(c, r.workspace, force = true)
        engine.waitUntilIdle()
        assertEquals(setOf(WorkspaceSync.scopesKey(r.workspace.id), WorkspaceSync.pinsKey(r.workspace.id), "attention-types:${r.workspace.id}", WorkspaceSync.notificationsKey(r.workspace.id), WorkspaceSync.inboxKey(r.workspace.id)) +
            r.store.reader.syncableChannels(r.workspace.id).flatMap { listOf(WorkspaceSync.tasksKey(r.workspace.id, it.id), WorkspaceSync.docsKey(r.workspace.id, it.id), WorkspaceSync.filesKey(r.workspace.id, it.id)) }, r.engine.snapshot().errors.keys)
        return r
    }

    @Test fun sameEntryProofRetriesOnlyFailedStartupReadsAndWaitsForRealSuccess() = test {
        val r = failedScopedReads(); val c = r.connection; val w = r.workspace
        val initial = r.engine.snapshot().errors
        // Actual proof/access and healthy file reads cannot resolve failed pins, preferences or notifications.
        denyScoped = false
        r.sync.api(c).workspaces()
        r.sync.api(c).workspaces()
        engine.waitUntilIdle()
        assertEquals(initial, r.engine.snapshot().errors)
        r.engine.enqueueRead(WorkspaceSync.taskKey(w.id, "unrelated-detail"), SyncPriority.USER, true) { error("unrelated read") }
        r.engine.enqueue("write:${w.id}", SyncPriority.USER, true) { error("unrelated write") }
        r.engine.enqueueRead(WorkspaceSync.tasksKey(w.id, "not-a-stored-channel"), SyncPriority.USER, true) { error("unmatched channel") }
        r.engine.enqueueRead(WorkspaceSync.filesKey("other-workspace", "channel"), SyncPriority.USER, true) { error("other workspace collection") }
        r.engine.enqueueRead(WorkspaceSync.pinsKey("other-workspace"), SyncPriority.USER, true) { error("other workspace key") }
        val other = runtimes.enable(c, catalog.reader.workspaces(c.id).first { it.id != w.id })
        other.awaitStarted(); engine.waitUntilIdle()
        other.engine.enqueueRead(WorkspaceSync.pinsKey(w.id), SyncPriority.USER, true) { error("other namespace") }
        engine.enqueueRead("discover:other-connection", SyncPriority.USER, true) { error("other connection") }
        engine.waitUntilIdle()
        val retained = engine.snapshot().errors.filterKeys { key -> initial.keys.none { key == "${r.boundary.id}|$it" } }
        // A durable preference edit must stay pending: proof recovery is GET-only.
        val pending = buildJsonObject { put("mutation_id", "pending-test"); put("value", "{}") }
        r.store.writer.setState(AttentionPreferenceState.key(c.id, w.id), AttentionPreferenceState(pending = pending).encode())
        val before = requests.size
        val h = holdNext { targeted(it) }
        r.retryFailedReadsAfterProof(c, w, r.activity.currentGeneration) { true }
        h.arrived.await()
        assertEquals("held reads retain their errors", initial, r.engine.snapshot().errors.filterKeys { it in initial })
        releaseHeld(); engine.waitUntilIdle()
        assertEquals(retained, engine.snapshot().errors)
        assertEquals(pending, r.store.reader.attentionPreference(c.id, w.id).pending)
        val retried = requests.drop(before).filter { targeted(it) }
        assertTrue(retried.any { it.url.contains("family=personal_pin") })
        assertTrue(retried.any { it.url.contains("/me/preferences/") })
        assertTrue(retried.any { it.url.contains("/notifications/") })
        assertTrue(retried.all { it.method == "GET" })
        assertTrue("no healthy descendants or unrelated jobs", requests.drop(before).all {
            targeted(it) || it.url.substringBefore('?').endsWith("/file-folders")
        })
        val healthy = requests.size
        r.retryFailedReadsAfterProof(c, w, r.activity.currentGeneration) { true }
        engine.waitUntilIdle()
        assertEquals("healthy keys are not retried", healthy, requests.size)
        assertTrue(posts.isEmpty())
    }

    @Test fun collectionRecoveryClearsOnlyItsSuccessfulKeysAndSkipsHealthyChannelKeys() = test {
        val r = failedScopedReads(); val c = r.connection; val w = r.workspace
        denyScoped = false
        val channel = r.store.reader.syncableChannels(w.id).first().id
        val healthy = WorkspaceSync.docsKey(w.id, channel)
        r.sync.refreshDocs(c, w, force = true, failedJobKeys = setOf(healthy))
        engine.waitUntilIdle()
        val remaining = r.engine.snapshot().errors.keys
        assertFalse(healthy in remaining)
        val heldKey = WorkspaceSync.tasksKey(w.id, channel)
        val before = requests.size
        val h = holdNext { it.url.substringBefore('?').endsWith("/channels/$channel/tasks") }
        r.retryFailedReadsAfterProof(c, w, r.activity.currentGeneration) { true }
        h.arrived.await()
        withTimeout(15_000) {
            while (r.engine.snapshot().errors.keys != setOf(heldKey)) kotlinx.coroutines.delay(10)
        }
        assertTrue(requests.drop(before).none { it.url.substringBefore('?').endsWith("/channels/$channel/docs") })
        releaseHeld(); engine.waitUntilIdle()
        assertTrue(r.engine.snapshot().errors.isEmpty())
        assertTrue(requests.drop(before).all { it.method == "GET" })
    }

    @Test fun deniedScopedRecoveryRemainsVisibleWithoutMutationReplay() = test {
        val r = failedScopedReads(); val initial = r.engine.snapshot().errors.keys
        denyScoped = false; denyScopedStatus = true
        val before = requests.size
        r.retryFailedReadsAfterProof(r.connection, r.workspace, r.activity.currentGeneration) { true }
        engine.waitUntilIdle()
        assertEquals(initial, r.engine.snapshot().errors.keys)
        assertEquals(NotificationSyncState.Phase.ACCESS_LOST, r.store.reader.notificationSync(r.connection.id, r.workspace.id)?.state)
        assertTrue(requests.drop(before).all { it.method == "GET" })
        assertTrue(posts.isEmpty())
    }

    @Test fun staleProofContextOrIdentityOrBoundaryCannotRequestScopedRecovery() = test {
        val r = failedScopedReads(); denyScoped = false
        val lease = r.activity.currentGeneration; val before = requests.size
        r.retryFailedReadsAfterProof(r.connection, r.workspace, lease) { false }
        r.retryFailedReadsAfterProof(r.connection.copy(origin = "https://other.sample.invalid"), r.workspace, lease) { true }
        r.retryFailedReadsAfterProof(r.connection, r.workspace.copy(appNpub = "another-app"), lease) { true }
        identity.remove(); identity.importIdentity(TestKeys.otherNsec(), TestKeys.PIN)
        runCatching { r.retryFailedReadsAfterProof(r.connection, r.workspace, lease) { true } }
        engine.waitUntilIdle()
        assertEquals(before, requests.size)
        assertTrue(r.engine.snapshot().errors.size > 3)
    }

    @Test fun queuedRecoveryCannotOutliveItsCapturedProofContext() = test {
        val r = failedScopedReads(); denyScoped = false
        val blockers = (1..4).map { CompletableDeferred<Unit>() to CompletableDeferred<Unit>() }
        blockers.forEachIndexed { index, (arrived, release) ->
            engine.enqueue("blocker:$index", SyncPriority.USER, true) { arrived.complete(Unit); release.await() }
        }
        blockers.forEach { it.first.await() }
        val before = requests.size; val initial = r.engine.snapshot().errors
        var current = true
        r.retryFailedReadsAfterProof(r.connection, r.workspace, r.activity.currentGeneration) { current }
        current = false
        blockers.forEach { it.second.complete(Unit) }
        engine.waitUntilIdle()
        assertEquals(before, requests.size)
        assertEquals(initial, r.engine.snapshot().errors)
        assertTrue(posts.isEmpty())
    }

    @Test fun notificationOnlyRecoveryDoesNotRefreshHealthyAttentionOrPins() = test {
        val c = connect(); val r = runtimes.enable(c, studio(c))
        r.awaitStarted(); engine.waitUntilIdle()
        r.engine.enqueueRead(WorkspaceSync.notificationsKey(r.workspace.id), SyncPriority.USER, true) {
            throw studio.otherstuff.ambulando.core.transport.SharedConnectionException(studio.otherstuff.ambulando.core.transport.ConnectionIssue.CONSENT)
        }
        engine.waitUntilIdle(); val before = requests.size
        r.retryFailedReadsAfterProof(c, r.workspace, r.activity.currentGeneration) { true }
        engine.waitUntilIdle()
        val retry = requests.drop(before)
        assertTrue(retry.isNotEmpty())
        assertTrue(retry.all { it.url.contains("/notifications/") && it.method == "GET" })
        assertTrue(r.engine.snapshot().errors.isEmpty())
    }

    @Test fun largeInboxRecoveryContinuationKeepsReadOnlyAndDeniedFailureVisible() = test {
        val c = connect(); val r = runtimes.enable(c, studio(c))
        r.awaitStarted(); engine.waitUntilIdle()
        val w = r.workspace
        val pending = buildJsonObject { put("mutation_id", "keep-original"); put("value", "{\"version\":1,\"disabled\":[\"chat\"]}"); put("expected_row_version", 7) }
        val desired = "{\"version\":1,\"disabled\":[\"file\"]}"
        r.store.writer.setState(AttentionPreferenceState.key(c.id, w.id), AttentionPreferenceState(ready = true, pending = pending, desired = desired).encode())
        r.engine.enqueueRead(WorkspaceSync.notificationsKey(w.id), SyncPriority.USER, true) {
            throw studio.otherstuff.ambulando.core.transport.SharedConnectionException(studio.otherstuff.ambulando.core.transport.ConnectionIssue.CONSENT)
        }
        engine.waitUntilIdle()
        val before = requests.size
        pagedNotificationRecovery = true
        r.store.writer.resetNotificationCursor(c.id, w.id)
        r.retryFailedReadsAfterProof(c, w, r.activity.currentGeneration) { true }
        engine.waitUntilIdle()
        assertEquals(WorkspaceSync.NOTIFICATION_PAGE_BUDGET + 1, notificationPages.get())
        assertEquals(setOf(WorkspaceSync.notificationsKey(w.id)), r.engine.snapshot().errors.keys)
        val after = r.store.reader.attentionPreference(c.id, w.id)
        assertEquals(pending, after.pending); assertEquals(desired, after.desired)
        val retry = requests.drop(before)
        assertTrue("continuation never enters the normal attention refresh", retry.all { it.url.contains("/notifications/items?") && it.method == "GET" })
        assertTrue(posts.isEmpty())
    }

    @Test fun pausedOrRevokedRuntimeCannotBeRevivedByScopedProofRecovery() = test {
        val r = failedScopedReads(); denyScoped = false
        val lease = r.activity.currentGeneration
        r.pause(); val before = requests.size
        r.retryFailedReadsAfterProof(r.connection, r.workspace, lease) { true }
        engine.waitUntilIdle()
        assertEquals(before, requests.size); assertFalse(r.activity.isOpen); assertFalse(r.canSend)
        runtimes.enable(r.connection, r.workspace); r.awaitStarted(); engine.waitUntilIdle()
        val nextLease = r.activity.currentGeneration
        r.updateWorkspace(r.workspace.copy(accessRemoved = true))
        val revoked = requests.size
        r.retryFailedReadsAfterProof(r.connection, r.workspace.copy(accessRemoved = false), nextLease) { true }
        r.resumeWrites(); r.kickSenders(); engine.waitUntilIdle()
        assertEquals(revoked, requests.size); assertFalse(r.activity.isOpen); assertFalse(r.canSend)
        assertTrue(posts.isEmpty())
    }

    @Test fun explicitConsentRecoveryRetriesOnlyTheFailedConnectionRead() = test {
        val c = connect()
        val key = WorkspaceSync.discoverKey(c.id)
        denyListing = true
        runtimes.discover(c, SyncPriority.VISIBLE, true)
        engine.enqueueRead(WorkspaceSync.scopesKey("other-workspace"), SyncPriority.USER, true) { error("Unrelated failure") }
        engine.waitUntilIdle()
        assertTrue(engine.snapshot().errors.containsKey(key))
        val unrelated = engine.snapshot().errors.getValue(WorkspaceSync.scopesKey("other-workspace"))
        denyListing = false
        // QR proof/access and healthy scoped reads do not run the catalog discovery key: reproduce the stale warning.
        catalogSync.api(c).workspaces()
        assertTrue(engine.snapshot().errors.containsKey(key))
        val before = listings.get()
        val h = holdNext(isListing)
        runtimes.retryFailedDiscovery(c)
        h.arrived.await()
        assertTrue("warning remains until the actual read succeeds", engine.snapshot().errors.containsKey(key))
        releaseHeld()
        engine.waitUntilIdle()
        assertEquals(before + 1, listings.get())
        assertFalse(engine.snapshot().errors.containsKey(key))
        assertEquals(unrelated, engine.snapshot().errors[WorkspaceSync.scopesKey("other-workspace")])
        runtimes.retryFailedDiscovery(c)
        engine.waitUntilIdle()
        assertEquals("healthy discovery is not replayed", before + 1, listings.get())
        assertTrue(posts.isEmpty())
    }

    @Test fun failedConsentRecoveryRetryRemainsVisibleAndDoesNotClearAnotherConnection() = test {
        val c = connect()
        val other = ConnectionRecord.create(studio.otherstuff.ambulando.core.transport.TowerOrigin("https://other.sample.invalid"), c.signerNpub, null)
        val otherKey = WorkspaceSync.discoverKey(other.id)
        engine.enqueueRead(otherKey, SyncPriority.USER, true) { error("Other connection failure") }
        denyListing = true
        runtimes.discover(c, SyncPriority.USER, true)
        engine.waitUntilIdle()
        val errors = engine.snapshot().errors
        val before = listings.get()
        runtimes.retryFailedDiscovery(c)
        engine.waitUntilIdle()
        assertEquals(before + 1, listings.get())
        assertEquals(errors, engine.snapshot().errors)
        assertTrue(posts.isEmpty())
    }

    @Test fun accountSwitchCannotRecoverThePreviousAccountsDiscoveryFailure() = test {
        val c = connect()
        denyListing = true
        runtimes.discover(c, SyncPriority.USER, true)
        engine.waitUntilIdle()
        val errors = engine.snapshot().errors
        val before = listings.get()
        identity.remove()
        identity.importIdentity(TestKeys.otherNsec(), TestKeys.PIN)
        denyListing = false
        try { runtimes.retryFailedDiscovery(c); org.junit.Assert.fail("Expected signer mismatch") }
        catch (_: studio.otherstuff.ambulando.core.crypto.IdentityError.SignerMismatch) { }
        engine.waitUntilIdle()
        assertEquals(before, listings.get())
        assertEquals(errors, engine.snapshot().errors)
        assertTrue(posts.isEmpty())
    }

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
