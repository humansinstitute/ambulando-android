package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.support.NotificationFixtures.map
import studio.otherstuff.ambulando.core.transport.*
import java.net.SocketTimeoutException

class NotificationSyncTest {
    private val w = Fixtures.WORKSPACE_ID
    private val workspace = WorkspaceRecord(w, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))

    private class Harness(val store: LocalStore, val engine: SyncEngine, val sync: WorkspaceSync, val connection: ConnectionRecord, val transport: StubTransport, val writes: WriteSender) {
        suspend fun close() { engine.cancelAll(); engine.close(); store.close() }
    }

    private suspend fun harness(handler: (HttpRequest) -> Pair<Int, Any?>): Harness {
        val identity = makeIdentity()
        val store = LocalStore(tempDir())
        val engine = SyncEngine(retryDelay = { 1 })
        val transport = StubTransport(handler)
        val connection = store.writer.saveConnection(ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null))
        return Harness(store, engine, WorkspaceSync(store, engine, identity, transport), connection, transport, WriteSender(store, engine, identity, transport, tempDir()))
    }

    private fun page(items: List<Map<String, Any?>>, head: Long, next: String? = null) = mapOf("items" to items, "next_cursor" to next, "head_seq" to head)
    private fun changes(list: List<Map<String, Any?>>, through: Long, head: Long = through, more: Boolean = false) =
        mapOf("changes" to list, "through_seq" to through, "head_seq" to head, "has_more" to more)

    private suspend fun idle(h: Harness) = withTimeout(5000) { h.engine.waitUntilIdle() }

    @Test fun coldStartReplaysFromTheSnapshotHeadNotFromTheLastPagesHead() = runBlocking {
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/notifications/items") && queryOf(r.url)["cursor"] == null -> 200 to page(listOf(map(1, 5), map(2, 6)), head = 10, next = "p2")
                pathOf(r.url).endsWith("/notifications/items") -> 200 to page(listOf(map(3, 12)), head = 14)      // later page saw a newer head
                pathOf(r.url).endsWith("/notifications/changes") -> 200 to changes(listOf(map(3, 12), map(1, 13, version = 2, read = true)), through = 14)
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            val changeRequest = h.transport.recorded.first { pathOf(it.url).endsWith("/changes") }
            assertEquals("replay starts at the first page's head so nothing between 10 and 14 is skipped", "10", queryOf(changeRequest.url)["after_seq"])
            val rows = h.store.reader.notificationRows(h.connection.id, w)
            assertEquals(3, rows.size)
            assertTrue(rows.first { it.id == NotificationFixtures.id(1) }.read)
            assertEquals(14, h.store.reader.notificationSync(h.connection.id, w)!!.localSeq)
            assertTrue(h.transport.recorded.all { it.header("x-flightdeck-pg-app-npub") == TestKeys.NPUB })
        } finally { h.close() }
    }

    @Test fun steadyStatePagesChangesAndAdvancesOnlyToDeliveredThroughSeq() = runBlocking {
        var served = 0
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to page(listOf(map(1, 5)), head = 5)
                pathOf(r.url).endsWith("/changes") -> {
                    served += 1
                    when (queryOf(r.url)["after_seq"]) {
                        "5" -> 200 to changes(listOf(map(2, 7)), through = 7, head = 20, more = true)
                        "7" -> 200 to changes(listOf(map(3, 9)), through = 9, head = 20)
                        else -> 400 to mapOf("error" to "unexpected")
                    }
                }
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertEquals(2, served)
            assertEquals("local_seq is the last delivered through_seq, not the head of any response", 9, h.store.reader.notificationSync(h.connection.id, w)!!.localSeq)
            assertEquals(3, h.store.reader.notificationRows(h.connection.id, w).size)
        } finally { h.close() }
    }

    @Test fun resetRequiredRestartsFromASnapshotAndKeepsPendingIntent() = runBlocking {
        var changesCalls = 0
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to page(listOf(map(1, 40)), head = 40)
                pathOf(r.url).endsWith("/changes") -> { changesCalls += 1; if (changesCalls == 1) 410 to mapOf("error" to "reset_required") else 200 to changes(emptyList(), through = 40) }
                else -> 404 to null
            }
        }
        try {
            h.store.writer.replaceNotificationInbox(h.connection.id, w, listOf(NotificationFixtures.item(7, 3)), 3)
            h.store.writer.setNotificationOverlay(h.connection.id, w, NotificationFixtures.id(7), NotificationPatch(read = true))
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            val sync = h.store.reader.notificationSync(h.connection.id, w)!!
            assertEquals(40, sync.localSeq)
            assertEquals(listOf(NotificationFixtures.id(1)), h.store.reader.notificationRows(h.connection.id, w).map { it.id })
        } finally { h.close() }
    }

    @Test fun missingRoutesMeanUnsupportedAndForbiddenMeansAccessLostWithContentPurged() = runBlocking {
        var status = 404
        val h = harness {
            status to mapOf("error" to "nope")
        }
        try {
            h.store.writer.replaceNotificationInbox(h.connection.id, w, listOf(NotificationFixtures.item(1, 5)), 5)
            h.store.writer.resetNotificationCursor(h.connection.id, w)
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertEquals(NotificationSyncState.Phase.UNSUPPORTED, h.store.reader.notificationSync(h.connection.id, w)!!.state)
            assertTrue(h.store.reader.notificationRows(h.connection.id, w).isEmpty())
            status = 403
            h.sync.refreshNotifications(h.connection, workspace, force = true); idle(h)
            assertEquals(NotificationSyncState.Phase.ACCESS_LOST, h.store.reader.notificationSync(h.connection.id, w)!!.state)
        } finally { h.close() }
    }

    @Test fun networkFailureKeepsWhatIsStoredAndRecoversOnTheNextRun() = runBlocking {
        var offline = false
        val h = harness { r ->
            if (offline) throw SocketTimeoutException()
            when {
                pathOf(r.url).endsWith("/items") -> 200 to page(listOf(map(1, 5)), head = 5)
                pathOf(r.url).endsWith("/changes") -> 200 to changes(emptyList(), through = 5)
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            offline = true
            h.sync.refreshNotifications(h.connection, workspace, force = true); idle(h)
            assertEquals("offline: the inbox is still readable", 1, h.store.reader.notificationRows(h.connection.id, w).size)
            assertEquals(NotificationSyncState.Phase.READY, h.store.reader.notificationSync(h.connection.id, w)!!.state)
        } finally { h.close() }
    }

    @Test fun itemsFromAnotherWorkspaceAreRefusedAndNothingIsStored() = runBlocking {
        val h = harness { r ->
            if (pathOf(r.url).endsWith("/items")) 200 to page(listOf(map(1, 5, workspace = Fixtures.WORKSPACE_ID_2)), head = 5) else 404 to null
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertTrue(h.store.reader.notificationRows(h.connection.id, w).isEmpty())
            assertTrue(h.store.reader.notificationRows(h.connection.id, Fixtures.WORKSPACE_ID_2).isEmpty())
        } finally { h.close() }
    }

    @Test fun streamHintSchedulesTheNotificationJobAndCarriesNoContent() {
        val plan = EventPlan.make(listOf(TowerEvent(1, "1", w, "notification_item", NotificationFixtures.id(1), "created")), w)
        assertEquals(setOf<EventPlan.Refresh>(EventPlan.Refresh.Notifications), plan.refreshes)
    }

    // MARK: Writes

    private fun seedUnread(h: Harness, version: Long = 2) = runBlocking {
        h.store.writer.replaceNotificationInbox(h.connection.id, w, listOf(NotificationFixtures.item(1, 5, version = version)), 5)
    }

    /** What the screen drew: read from the stored row at the moment the test stands in for the user looking at it. */
    private fun seen(h: Harness) = runBlocking { h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!.item.let { ObservedNotification(it.rowVersion, it.changeSeq) } }

    private fun patchBodies(h: Harness) = h.transport.recorded.filter { it.method == "PATCH" }.map { parseJsonObjectOrNull(it.body!!)!! }

    @Test fun markReadSendsTheObservedRowVersionAndAMutationIdAndAdoptsTheAcknowledgement() = runBlocking {
        val h = harness { r ->
            if (r.method == "PATCH") 200 to mapOf("item" to map(1, 8, version = 3, read = true)) else 404 to null
        }
        try {
            seedUnread(h)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = true), seen(h)); idle(h)
            val body = patchBodies(h).single()
            assertEquals(2L, body["expected_row_version"].let { flexibleLong(it) })
            assertTrue(isUUID(body.string("mutation_id")))
            assertEquals(true, body.obj("patch")!!.bool("read"))
            assertEquals("the write is also tied to the change the user saw", 5L, flexibleLong(body["observed_change_seq"]))
            assertEquals(NotificationFixtures.id(1), pathOf(h.transport.recorded.single { it.method == "PATCH" }.url).substringAfterLast('/'))
            val row = h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!
            assertFalse(row.pending); assertTrue(row.read); assertEquals(3, row.item.rowVersion)
            assertTrue(h.store.reader.writes(h.connection.id, TestKeys.NPUB).isEmpty())
        } finally { h.close() }
    }

    @Test fun conflictAdoptsTheFoldedItemAndDoesNotAcknowledgeNewActivityByRetrying() = runBlocking {
        val h = harness { r ->
            if (r.method == "PATCH") 409 to mapOf("error" to "Item changed", "code" to "state_conflict", "item" to map(1, 9, version = 4, read = false)) else 404 to null
        }
        try {
            seedUnread(h)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = true), seen(h)); idle(h)
            assertEquals("the stale tap is sent once and never re-applied", 1, patchBodies(h).size)
            val row = h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!
            assertTrue("new activity is unread again", row.unread)
            assertFalse(row.pending)
            assertEquals(4, row.item.rowVersion)
            assertTrue(h.store.reader.writes(h.connection.id, TestKeys.NPUB).isEmpty())
        } finally { h.close() }
    }

    @Test fun lostResponseIsRetriedWithTheSameMutationIdAndTheOriginalAcknowledgementIsAdopted() = runBlocking {
        var calls = 0
        val h = harness { r ->
            if (r.method != "PATCH") return@harness 404 to null
            calls += 1
            if (calls == 1) throw SocketTimeoutException()
            200 to mapOf("item" to map(1, 8, version = 3, read = true))     // identical retry: Tower returns the original acknowledgement
        }
        try {
            seedUnread(h)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = true), seen(h))
            withTimeout(5000) { while (calls < 2) delay(10) }
            idle(h)
            val ids = patchBodies(h).map { it.string("mutation_id") }
            assertEquals(2, ids.size); assertEquals("a retry must reuse the mutation id", ids[0], ids[1])
            val row = h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!
            assertFalse(row.pending); assertTrue(row.read)
            assertTrue(h.store.reader.writes(h.connection.id, TestKeys.NPUB).isEmpty())
        } finally { h.close() }
    }

    @Test fun mutationIdReusedIsARejectionNotASuccessAndTellsTheUser() = runBlocking {
        val h = harness { r -> if (r.method == "PATCH") 409 to mapOf("error" to "Mutation id already used", "code" to "mutation_id_reused") else 404 to null }
        try {
            seedUnread(h)
            val notices = CompletableDeferred<NotificationNotice>()
            val collector = launch(Dispatchers.Default) { notices.complete(h.writes.notificationNotices.first()) }
            yield(); delay(50)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = true), seen(h)); idle(h)
            val notice = withTimeout(3000) { notices.await() }
            collector.cancel()
            assertEquals(NotificationNotice.Kind.REJECTED, notice.kind)
            val row = h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!
            assertFalse("the overlay must not pretend it was read", row.read)
            assertFalse(row.pending)
            assertTrue(h.store.reader.writes(h.connection.id, TestKeys.NPUB).isEmpty())
        } finally { h.close() }
    }

    @Test fun rejectedWriteDropsTheOverlayInsteadOfLeavingAGhostState() = runBlocking {
        val h = harness { r -> if (r.method == "PATCH") 403 to mapOf("error" to "forbidden") else 404 to null }
        try {
            seedUnread(h)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(dismissed = true), seen(h)); idle(h)
            val row = h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!
            assertFalse(row.pending); assertFalse(row.dismissed)
            assertTrue(h.store.reader.writes(h.connection.id, TestKeys.NPUB).isEmpty())
        } finally { h.close() }
    }

    @Test fun aTapOnTheSnapshotAfterAnAcknowledgementCarriesThatSnapshotsVersion() = runBlocking {
        var n = 0
        val h = harness { r ->
            if (r.method != "PATCH") return@harness 404 to null
            n += 1
            200 to mapOf("item" to map(1, 6L + n, version = 2L + n, read = n == 1))
        }
        try {
            seedUnread(h)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = true), seen(h)); idle(h)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = false), seen(h)); idle(h)
            val bodies = patchBodies(h)
            assertEquals(listOf(2L, 3L), bodies.map { flexibleLong(it["expected_row_version"]) })
            assertEquals(listOf(5L, 7L), bodies.map { flexibleLong(it["observed_change_seq"]) })
            assertNotEquals(bodies[0].string("mutation_id"), bodies[1].string("mutation_id"))
        } finally { h.close() }
    }

    @Test fun aSecondTapOnTheSameSnapshotWhileTheFirstIsRetryingLearnsItsBaseFromTheFirstsAcknowledgement() = runBlocking {
        var calls = 0
        val h = harness { r ->
            if (r.method != "PATCH") return@harness 404 to null
            calls += 1
            if (calls == 1) throw SocketTimeoutException()
            200 to mapOf("item" to map(1, 5L + calls, version = 1L + calls, read = calls == 2))
        }
        try {
            seedUnread(h)
            val snapshot = seen(h)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = true), snapshot)
            withTimeout(5000) { while (calls < 1) delay(10) }
            delay(100)
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = false), snapshot)
            val queued = h.store.reader.writes(h.connection.id, TestKeys.NPUB)
            assertEquals(2, queued.size)
            assertEquals("the second write has no base of its own yet", true, queued[1].payloadObject.bool("chained"))
            h.writes.kick(h.connection, force = true); idle(h)
            val bodies = patchBodies(h)
            assertEquals(3, bodies.size)
            assertEquals("the retry reuses the mutation id", bodies[0].string("mutation_id"), bodies[1].string("mutation_id"))
            assertEquals("the chained write is based on what the first produced", 3L, flexibleLong(bodies[2]["expected_row_version"]))
            assertTrue(h.store.reader.writes(h.connection.id, TestKeys.NPUB).isEmpty())
        } finally { h.close() }
    }

    @Test fun staleRenderedRowCannotAcknowledgeActivityThatFoldedInAfterItWasDrawn() = runBlocking {
        // A stateful Tower: version 2 until new activity folds the item to version 3 / seq 9 (unread again).
        var version = 2L; var seq = 5L; var read = false
        val h = harness { r ->
            if (r.method != "PATCH") return@harness 404 to null
            val body = parseJsonObjectOrNull(r.body!!)!!
            val expected = flexibleLong(body["expected_row_version"])
            val observed = flexibleLong(body["observed_change_seq"])
            if (expected != version || (observed != null && observed < seq)) 409 to mapOf("error" to "Item changed", "code" to "state_conflict", "item" to map(1, seq, version, read = read))
            else { read = true; version += 1; seq += 1; 200 to mapOf("item" to map(1, seq, version, read = true)) }
        }
        try {
            seedUnread(h)
            val drawn = seen(h)                              // the user is looking at v2 / seq 5
            version = 3; seq = 9                             // activity folds in on Tower
            h.store.writer.applyNotificationChanges(h.connection.id, w, listOf(NotificationChange.Upsert(NotificationFixtures.item(1, 9, version = 3))), 9)
            // The local copy has already caught up (v3) but the tap comes from the stale drawn row (v2).
            h.writes.submitNotificationState(h.connection, workspace, NotificationFixtures.id(1), NotificationPatch(read = true), drawn); idle(h)
            val body = patchBodies(h).single()
            assertEquals("sent the drawn version, not the newer local one", 2L, flexibleLong(body["expected_row_version"]))
            assertEquals(5L, flexibleLong(body["observed_change_seq"]))
            assertFalse("Tower did not apply it", read)
            val row = h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!
            assertTrue("the folded activity is still unread", row.unread)
            assertFalse(row.pending)
        } finally { h.close() }
    }

    @Test fun inboxLargerThanOneJobsBudgetResumesFromTheStoredCursorAndNeverCompletesEarly() = runBlocking {
        val pagesTotal = WorkspaceSync.NOTIFICATION_PAGE_BUDGET + 4
        var changesAfter: String? = null
        val itemCalls = ArrayList<String?>()
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> {
                    val cursor = queryOf(r.url)["cursor"]
                    itemCalls += cursor
                    val index = cursor?.removePrefix("p")?.toInt() ?: 0
                    val items = listOf(map(index + 1, 100L + index))
                    200 to page(items, head = 50, next = if (index + 1 < pagesTotal) "p${index + 1}" else null) + mapOf("inbox_started_at" to "2026-10-26T09:00:00Z")
                }
                pathOf(r.url).endsWith("/changes") -> { changesAfter = queryOf(r.url)["after_seq"]; 200 to changes(emptyList(), through = 50) }
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertEquals("every page was read exactly once: the continuation resumed, it did not restart at page one", pagesTotal, itemCalls.size)
            assertEquals(itemCalls.toSet().size, itemCalls.size)
            assertEquals(pagesTotal, h.store.reader.notificationRows(h.connection.id, w).size)
            assertEquals("replay starts at the first page's head", "50", changesAfter)
            val sync = h.store.reader.notificationSync(h.connection.id, w)!!
            assertEquals(NotificationSyncState.Phase.READY, sync.state); assertEquals(50, sync.localSeq)
            assertNull(h.store.reader.notificationSnapshotProgress(h.connection.id, w))
        } finally { h.close() }
    }

    @Test fun truncatedSnapshotIsNotReplayedAndASurvivingCursorResumesAfterTheJobIsCancelled() = runBlocking {
        val h = harness { r ->
            if (pathOf(r.url).endsWith("/items")) {
                val cursor = queryOf(r.url)["cursor"]
                if (cursor == null) 200 to page(listOf(map(1, 100)), head = 50, next = "p1")
                else throw SocketTimeoutException()          // the connection drops mid-snapshot
            } else 404 to null
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            val sync = h.store.reader.notificationSync(h.connection.id, w)!!
            assertNotEquals("a truncated listing is never declared complete", NotificationSyncState.Phase.READY, sync.state)
            assertEquals(0, sync.localSeq)
            val progress = h.store.reader.notificationSnapshotProgress(h.connection.id, w)!!
            assertEquals("p1", progress.cursor); assertEquals(50L, progress.seq)
            assertTrue("no replay was attempted", h.transport.recorded.none { it.url.contains("/changes") })
            // The same cursor is used on resume instead of starting over.
            h.transport.setHandler { r ->
                if (pathOf(r.url).endsWith("/items")) { assertEquals("p1", queryOf(r.url)["cursor"]); 200 to page(listOf(map(2, 101)), head = 60) }
                else 200 to changes(emptyList(), through = 50)
            }
            h.sync.refreshNotifications(h.connection, workspace, force = true); idle(h)
            assertEquals(NotificationSyncState.Phase.READY, h.store.reader.notificationSync(h.connection.id, w)!!.state)
            assertEquals(2, h.store.reader.notificationRows(h.connection.id, w).size)
        } finally { h.close() }
    }

    @Test fun rowsNotListedByACompletedSnapshotAreRemovedButNotBeforeItCompletes() = runBlocking {
        val h = harness { r ->
            if (pathOf(r.url).endsWith("/items")) {
                if (queryOf(r.url)["cursor"] == null) 200 to page(listOf(map(1, 100)), head = 50, next = "p1") else throw SocketTimeoutException()
            } else 404 to null
        }
        try {
            h.store.writer.replaceNotificationInbox(h.connection.id, w, listOf(NotificationFixtures.item(9, 3)), 3)
            h.store.writer.resetNotificationCursor(h.connection.id, w)
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            val ids = h.store.reader.notificationRows(h.connection.id, w).map { it.id }.toSet()
            assertTrue("the held row stays visible until the new snapshot completes", NotificationFixtures.id(9) in ids)
        } finally { h.close() }
    }

    @Test fun bulkMarkReadIsBoundedByTheObservedSequenceAndCoversOnlyThoseRows() = runBlocking {
        val h = harness { r -> if (r.method == "POST") 200 to mapOf("affected" to 2, "through_seq" to 6) else 404 to null }
        try {
            h.store.writer.replaceNotificationInbox(h.connection.id, w, listOf(NotificationFixtures.item(1, 5), NotificationFixtures.item(2, 6), NotificationFixtures.item(3, 12)), 12)
            h.writes.submitNotificationBulk(h.connection, workspace, NotificationBulkOp.MARK_READ, upToSeq = 6); idle(h)
            val post = h.transport.recorded.single { it.method == "POST" }
            assertTrue(pathOf(post.url).endsWith("/notifications/bulk"))
            val body = parseJsonObjectOrNull(post.body!!)!!
            assertEquals("mark_read", body.string("op"))
            assertEquals(6L, flexibleLong(body.obj("selector")!!["up_to_seq"]))
            assertEquals(1, h.store.reader.notificationCounts(h.connection.id, w).unread)
            val row = h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!
            assertTrue("accepted: the overlay became the stored flags", row.read && !row.pending)
        } finally { h.close() }
    }

    @Test fun removedItemTombstoneFromChangesIsContentFree() = runBlocking {
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to page(listOf(map(1, 5)), head = 5) + mapOf("inbox_started_at" to "2026-10-26T09:00:00Z")
                pathOf(r.url).endsWith("/changes") -> 200 to changes(listOf(NotificationFixtures.tombstone(1, 6)), through = 6)
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertTrue(h.store.reader.notificationRows(h.connection.id, w).isEmpty())
            assertEquals("2026-10-26T09:00:00Z", h.store.reader.notificationSync(h.connection.id, w)!!.inboxStartedAt)
        } finally { h.close() }
    }

    @Test fun aQueuedChangeForAWorkspaceTheUserLostIsDroppedNotLeftAsAFailedRowWithAGhostOverlay() = runBlocking {
        val h = harness { 404 to null }
        try {
            seedUnread(h)
            val flagged = workspace.copy()
            h.store.writer.replaceWorkspaces(h.connection.id, listOf(WorkspaceRecord(w, TestKeys.NPUB, "Synthetic", null, null, null, null, JsonObject(emptyMap()))))
            h.writes.submitNotificationState(h.connection, flagged, NotificationFixtures.id(1), NotificationPatch(dismissed = true), seen(h))
            // Access to the workspace goes away while the write is still waiting.
            h.store.writer.markWorkspaceAccessRemoved(h.connection.id, w)
            h.writes.kick(h.connection, force = true); idle(h)
            assertTrue(h.store.reader.writes(h.connection.id, TestKeys.NPUB).isEmpty())
            assertFalse(h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(1))!!.pending)
        } finally { h.close() }
    }

    @Test fun archiveLoadsOnDemandInResumableJobsAndKeepsRowsTheUserIsChanging() = runBlocking {
        val pagesTotal = WorkspaceSync.NOTIFICATION_PAGE_BUDGET + 3
        val calls = ArrayList<String?>()
        val h = harness { r ->
            if (pathOf(r.url).endsWith("/items") && queryOf(r.url)["view"] == "archived") {
                val cursor = queryOf(r.url)["cursor"]; calls += cursor
                val index = cursor?.removePrefix("a")?.toInt() ?: 0
                200 to page(listOf(map(index + 1, 100L + index, archived = true)), head = 50, next = if (index + 1 < pagesTotal) "a${index + 1}" else null)
            } else 404 to null
        }
        try {
            // Two held archived rows: one the listing will not contain, one the user is un-archiving right now.
            h.store.writer.replaceNotificationInbox(h.connection.id, w, listOf(NotificationFixtures.item(40, 3), NotificationFixtures.item(41, 3)), 3)
            h.store.writer.applyNotificationChanges(h.connection.id, w, listOf(NotificationChange.Upsert(NotificationFixtures.item(40, 4, 2, archived = true)), NotificationChange.Upsert(NotificationFixtures.item(41, 5, 2, archived = true))), 5)
            h.store.writer.setNotificationOverlay(h.connection.id, w, NotificationFixtures.id(41), NotificationPatch(archived = false))
            h.sync.loadArchivedNotifications(h.connection, workspace); idle(h)
            assertEquals(pagesTotal, calls.size); assertEquals(calls.toSet().size, calls.size)
            val archived = h.store.reader.notificationRows(h.connection.id, w, NotificationView.ARCHIVED).map { it.id }.toSet()
            assertFalse("an archived row the complete listing omitted is dropped", NotificationFixtures.id(40) in archived)
            assertEquals(pagesTotal, archived.size)
            assertTrue(h.store.reader.notificationSync(h.connection.id, w)!!.archivedLoaded)
            assertTrue("the row with a pending change is not removed by the archive listing", h.store.reader.notificationRow(h.connection.id, w, NotificationFixtures.id(41)) != null)
        } finally { h.close() }
    }

    // MARK: wire v1.1

    private fun rev(base: Map<String, Any?>, r: Long) = base + mapOf("rules_revision" to r)

    @Test fun everyReadAndBulkCarriesThisAppsClientIdAndChangesSendTheSnapshotsRulesRevision() = runBlocking {
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to rev(page(listOf(map(1, 5)), head = 5), 11)
                pathOf(r.url).endsWith("/changes") -> 200 to rev(changes(emptyList(), through = 5), 11)
                pathOf(r.url).endsWith("/counts") -> 200 to mapOf("unread_total" to 1, "head_seq" to 5, "rules_revision" to 11)
                r.method == "POST" -> 200 to mapOf("affected" to 1, "through_seq" to 5)
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertEquals(11L, h.store.reader.notificationSync(h.connection.id, w)!!.rulesRevision)
            h.sync.refreshNotifications(h.connection, workspace, force = true); idle(h)
            h.sync.reconcileNotificationCounts(h.connection, workspace)
            h.writes.submitNotificationBulk(h.connection, workspace, NotificationBulkOp.MARK_READ, upToSeq = 5); idle(h)
            val reads = h.transport.recorded.filter { it.method == "GET" }
            assertTrue(reads.isNotEmpty() && reads.all { queryOf(it.url)["client_app_id"] == "ambulando" })
            val replay = h.transport.recorded.last { pathOf(it.url).endsWith("/changes") }
            assertEquals("11", queryOf(replay.url)["rules_revision"])
            val selector = parseJsonObjectOrNull(h.transport.recorded.single { it.method == "POST" }.body!!)!!.obj("selector")!!
            assertEquals("ambulando", selector.string("client_app_id"))
        } finally { h.close() }
    }

    @Test fun aRulesChangeOnAnotherDeviceResetsToAFreshSnapshotUnderTheNewRevision() = runBlocking {
        var revision = 11L
        var visible = listOf(map(1, 5), map(2, 6))
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to rev(page(visible, head = 8), revision)
                pathOf(r.url).endsWith("/changes") ->
                    if (queryOf(r.url)["rules_revision"] != revision.toString()) 410 to mapOf("code" to "reset_required", "reason" to "rules_changed", "rules_revision" to revision)
                    else 200 to rev(changes(emptyList(), through = 8), revision)
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertEquals(2, h.store.reader.notificationRows(h.connection.id, w).size)
            // The person turned this app's "mention" off elsewhere: Tower now lists one item and has revision 12.
            revision = 12; visible = listOf(map(2, 6))
            h.sync.refreshNotifications(h.connection, workspace, force = true); idle(h)
            assertEquals(listOf(NotificationFixtures.id(2)), h.store.reader.notificationRows(h.connection.id, w).map { it.id })
            val sync = h.store.reader.notificationSync(h.connection.id, w)!!
            assertEquals(12L, sync.rulesRevision); assertEquals(8L, sync.localSeq)
            assertEquals("12", queryOf(h.transport.recorded.last { pathOf(it.url).endsWith("/changes") }.url)["rules_revision"])
        } finally { h.close() }
    }

    @Test fun aPageComputedUnderOtherRulesWithoutA410IsNotAppliedAndTriggersTheSameReset() = runBlocking {
        var changesCalls = 0
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to rev(page(listOf(map(1, 5)), head = 5), if (changesCalls == 0) 3 else 4)
                pathOf(r.url).endsWith("/changes") -> { changesCalls += 1; 200 to if (changesCalls == 1) rev(changes(listOf(map(9, 7)), through = 7), 4) else rev(changes(emptyList(), through = 5), 4) }
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertEquals(4L, h.store.reader.notificationSync(h.connection.id, w)!!.rulesRevision)
            assertEquals(2, changesCalls)
            assertEquals("the mismatching page was never applied", listOf(NotificationFixtures.id(1)), h.store.reader.notificationRows(h.connection.id, w).map { it.id })
            assertEquals(5L, h.store.reader.notificationSync(h.connection.id, w)!!.localSeq)
        } finally { h.close() }
    }

    @Test fun aPageThatFilteredEverythingAdvancesToTheHeadAndDoesNotLoop() = runBlocking {
        var changesCalls = 0
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to rev(page(listOf(map(1, 5)), head = 5), 1)
                pathOf(r.url).endsWith("/changes") -> { changesCalls += 1; 200 to rev(changes(emptyList(), through = 9, head = 9), 1) }
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            assertEquals(1, changesCalls)
            assertEquals(9L, h.store.reader.notificationSync(h.connection.id, w)!!.localSeq)
            // A page that says there is more but moves nowhere is refused rather than looped on.
            val loop = harness { r -> if (pathOf(r.url).endsWith("/changes")) 200 to changes(emptyList(), through = 5, head = 9, more = true) else if (pathOf(r.url).endsWith("/items")) 200 to page(listOf(map(1, 5)), head = 5) else 404 to null }
            try {
                loop.sync.refreshNotifications(loop.connection, workspace); idle(loop)
                assertTrue(loop.transport.recorded.count { pathOf(it.url).endsWith("/changes") } <= 4)
            } finally { loop.close() }
        } finally { h.close() }
    }

    @Test fun countsUnderOtherRulesMeanTheLocalCopyIsStaleAndItStartsOver() = runBlocking {
        var revision = 2L
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/items") -> 200 to rev(page(listOf(map(1, 5)), head = 5), revision)
                pathOf(r.url).endsWith("/changes") -> 200 to rev(changes(emptyList(), through = 5), revision)
                pathOf(r.url).endsWith("/counts") -> 200 to mapOf("unread_total" to 1, "head_seq" to 5, "rules_revision" to revision)
                else -> 404 to null
            }
        }
        try {
            h.sync.refreshNotifications(h.connection, workspace); idle(h)
            revision = 3
            assertTrue(h.sync.reconcileNotificationCounts(h.connection, workspace)); idle(h)
            assertEquals(3L, h.store.reader.notificationSync(h.connection.id, w)!!.rulesRevision)
        } finally { h.close() }
    }

    // MARK: preferences (app-specific key, per-entry expected_row_version)

    private val mention = NotificationCategoryInfo("flightdeck.core", "Flight Deck", "mention", "Mentions", "normal", true, true, false)
    private val cats = mapOf("sources" to listOf(mapOf("app_id" to "flightdeck.core", "label" to "Flight Deck", "categories" to listOf(mapOf("category" to "mention", "label" to "Mentions", "default_inbox" to "on")))))

    private fun ruleJson(client: String, mode: String, version: Long, category: String = "mention") =
        mapOf("source_app_id" to "flightdeck.core", "category" to category, "client_app_id" to client, "channel" to "inbox", "mode" to mode, "row_version" to version)

    @Test fun togglingACategoryWritesTheAppSpecificKeyWithTheExactKeysRowVersion() = runBlocking {
        var current = listOf(ruleJson("*", "on", 4), ruleJson("ambulando", "default", 3))
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/categories") -> 200 to cats
                pathOf(r.url).endsWith("/rules") && r.method == "GET" -> 200 to mapOf("rules" to current, "rules_revision" to 9)
                pathOf(r.url).endsWith("/rules") -> { current = listOf(ruleJson("*", "on", 4), ruleJson("ambulando", "off", 4)); 200 to mapOf("rules" to current, "rules_revision" to 10) }
                else -> 404 to null
            }
        }
        try {
            val base = h.sync.loadNotificationPreferences(h.connection, workspace)
            assertEquals(9L, base.rulesRevision)
            val saved = h.sync.setNotificationInbox(h.connection, workspace, mention, on = false, base = base) as NotificationPreferenceResult.Saved
            val put = parseJsonObjectOrNull(h.transport.recorded.single { it.method == "PUT" }.body!!)!!
            val rule = put.objects("rules")!!.single()
            assertEquals("ambulando", rule.string("client_app_id")); assertEquals("off", rule.string("mode")); assertEquals("inbox", rule.string("channel"))
            assertEquals("the default row's version is the base for CAS", 3L, flexibleLong(rule["expected_row_version"]))
            assertFalse(saved.preferences.inboxOn(mention)); assertEquals(10L, saved.preferences.rulesRevision)
        } finally { h.close() }
    }

    @Test fun anAbsentKeyIsWrittenWithExpectedRowVersionZero() = runBlocking {
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/categories") -> 200 to cats
                pathOf(r.url).endsWith("/rules") && r.method == "GET" -> 200 to mapOf("rules" to listOf(ruleJson("*", "off", 6)), "rules_revision" to 2)
                pathOf(r.url).endsWith("/rules") -> 200 to mapOf("rules" to listOf(ruleJson("*", "off", 6), ruleJson("ambulando", "on", 1)), "rules_revision" to 3)
                else -> 404 to null
            }
        }
        try {
            val base = h.sync.loadNotificationPreferences(h.connection, workspace)
            assertFalse("a wildcard off rule hides it", base.inboxOn(mention))
            val saved = h.sync.setNotificationInbox(h.connection, workspace, mention, on = true, base = base) as NotificationPreferenceResult.Saved
            val rule = parseJsonObjectOrNull(h.transport.recorded.single { it.method == "PUT" }.body!!)!!.objects("rules")!!.single()
            assertEquals("the wildcard row is not this key", 0L, flexibleLong(rule["expected_row_version"]))
            assertEquals("ambulando", rule.string("client_app_id"))
            assertTrue("this app's own rule beats the wildcard", saved.preferences.inboxOn(mention))
        } finally { h.close() }
    }

    @Test fun aRulesConflictAdoptsEveryCurrentRuleAndRevisionAndTheRetryUsesANewMutationId() = runBlocking {
        var puts = 0
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/categories") -> 200 to cats
                pathOf(r.url).endsWith("/rules") && r.method == "GET" -> 200 to mapOf("rules" to emptyList<Any?>(), "rules_revision" to 2)
                pathOf(r.url).endsWith("/rules") -> {
                    puts += 1
                    if (puts == 1) 409 to mapOf("code" to "rules_conflict", "conflicts" to listOf(mapOf("expected_row_version" to 0)), "rules" to listOf(ruleJson("ambulando", "off", 5), ruleJson("*", "on", 2)), "rules_revision" to 7)
                    else 200 to mapOf("rules" to listOf(ruleJson("ambulando", "on", 6), ruleJson("*", "on", 2)), "rules_revision" to 8)
                }
                else -> 404 to null
            }
        }
        try {
            val base = h.sync.loadNotificationPreferences(h.connection, workspace)
            val conflict = h.sync.setNotificationInbox(h.connection, workspace, mention, on = true, base = base) as NotificationPreferenceResult.Conflict
            assertEquals(7L, conflict.preferences.rulesRevision); assertEquals(2, conflict.preferences.rules.size)
            assertFalse("the person sees the other device's choice", conflict.preferences.inboxOn(mention))
            h.sync.setNotificationInbox(h.connection, workspace, mention, on = true, base = conflict.preferences) as NotificationPreferenceResult.Saved
            val puts2 = h.transport.recorded.filter { it.method == "PUT" }.map { parseJsonObjectOrNull(it.body!!)!! }
            assertNotEquals(puts2[0].string("mutation_id"), puts2[1].string("mutation_id"))
            assertEquals(5L, flexibleLong(puts2[1].objects("rules")!!.single()["expected_row_version"]))
        } finally { h.close() }
    }

    @Test fun mutationIdReusedOnARulesWriteIsARejectionAndPreferencesAreReread() = runBlocking {
        val h = harness { r ->
            when {
                pathOf(r.url).endsWith("/categories") -> 200 to cats
                pathOf(r.url).endsWith("/rules") && r.method == "GET" -> 200 to mapOf("rules" to emptyList<Any?>(), "rules_revision" to 2)
                pathOf(r.url).endsWith("/rules") -> 409 to mapOf("code" to "mutation_id_reused")
                else -> 404 to null
            }
        }
        try {
            val base = h.sync.loadNotificationPreferences(h.connection, workspace)
            assertTrue(h.sync.setNotificationInbox(h.connection, workspace, mention, on = false, base = base) is NotificationPreferenceResult.Rejected)
        } finally { h.close() }
    }

    @Test fun inboxResolutionPrefersTheMoreSpecificKeyThenThisAppThenOffAndIgnoresDefaultRows() {
        val sourceWide = NotificationRule("flightdeck.core", "*", "ambulando", "inbox", "off", null, 1)
        val exact = NotificationRule("flightdeck.core", "mention", "*", "inbox", "on", null, 1)
        assertTrue("(source, category) beats (source, *) even for this app", NotificationPreferences(listOf(mention), listOf(sourceWide, exact)).inboxOn(mention))
        val sameLevel = listOf(NotificationRule("flightdeck.core", "mention", "*", "inbox", "off", null, 1), NotificationRule("flightdeck.core", "mention", "ambulando", "inbox", "on", null, 1))
        assertTrue(NotificationPreferences(listOf(mention), sameLevel).inboxOn(mention))
        val other = NotificationRule("flightdeck.core", "mention", "otherapp", "inbox", "off", null, 1)
        assertTrue("another app's rule does not apply", NotificationPreferences(listOf(mention), listOf(other)).inboxOn(mention))
        val inherit = NotificationRule("flightdeck.core", "mention", "ambulando", "inbox", "default", null, 3)
        assertTrue("a default row inherits the registry default", NotificationPreferences(listOf(mention), listOf(inherit)).inboxOn(mention))
        assertEquals(3L, NotificationPreferences(listOf(mention), listOf(inherit)).ownRule(mention)?.rowVersion)
    }
}
