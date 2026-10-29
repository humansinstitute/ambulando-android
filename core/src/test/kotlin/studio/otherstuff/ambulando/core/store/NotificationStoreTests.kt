package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.NotificationFixtures
import studio.otherstuff.ambulando.core.support.NotificationFixtures.item
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.NotificationChange
import studio.otherstuff.ambulando.core.transport.NotificationItem
import studio.otherstuff.ambulando.core.transport.NotificationPatch
import studio.otherstuff.ambulando.core.transport.NotificationView
import studio.otherstuff.ambulando.core.transport.TowerError

class NotificationStoreTests {
    private val w = Fixtures.WORKSPACE_ID

    private fun upsert(i: NotificationItem) = NotificationChange.Upsert(i)

    private suspend fun fixture(block: suspend (LocalStore, ConnectionRecord) -> Unit) {
        val store = LocalStore(studio.otherstuff.ambulando.core.support.tempDir())
        try { block(store, StoreFixtures.connect(store)) } finally { store.close() }
    }

    @Test fun snapshotSetsReplayCursorToSnapshotNotToNewestItemAndRemovesUnlistedRows() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5), item(2, 6)), snapshotSeq = 6)
            // A page fetched later saw item 3 at seq 9; the cursor must still be the snapshot (6) so seq 7-9 are replayed.
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(2, 6), item(3, 9)), snapshotSeq = 6)
            val sync = store.reader.notificationSync(c.id, w)!!
            assertEquals(6, sync.localSeq)
            assertEquals(NotificationSyncState.Phase.READY, sync.state)
            assertEquals(listOf(NotificationFixtures.id(3), NotificationFixtures.id(2)).toSet(), store.reader.notificationRows(c.id, w).map { it.id }.toSet())
        }
    }

    @Test fun replayIsIdempotentAndIgnoresStaleChanges() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5, version = 2)), 5)
            val newer = item(1, 8, version = 3, read = true)
            store.writer.applyNotificationChanges(c.id, w, listOf(upsert(newer)), 8)
            store.writer.applyNotificationChanges(c.id, w, listOf(upsert(newer), upsert(item(1, 6, version = 2))), 8)   // redelivery + stale
            val row = store.reader.notificationRow(c.id, w, NotificationFixtures.id(1))!!
            assertEquals(8, row.item.changeSeq)
            assertTrue(row.read)
            assertEquals(8, store.reader.notificationSync(c.id, w)!!.localSeq)
        }
    }

    @Test fun cursorNeverMovesBackwards() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, emptyList(), 10)
            store.writer.applyNotificationChanges(c.id, w, emptyList(), 4)
            assertEquals(10, store.reader.notificationSync(c.id, w)!!.localSeq)
        }
    }

    @Test fun dismissedLeavesTheInboxAndUnknownArchivedIsIgnoredButAdvancesTheCursor() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5), item(2, 6)), 6)
            store.writer.applyNotificationChanges(
                c.id, w, listOf(upsert(item(1, 7, 2, dismissed = true)), upsert(item(9, 8, 1, archived = true)), upsert(item(2, 9, 2, archived = true))), 9,
            )
            assertNull(store.reader.notificationRow(c.id, w, NotificationFixtures.id(1)))
            assertNull("an archived item this device never held is not hydrated", store.reader.notificationRow(c.id, w, NotificationFixtures.id(9)))
            val moved = store.reader.notificationRow(c.id, w, NotificationFixtures.id(2))!!
            assertEquals("archived", moved.partition)
            assertTrue(store.reader.notificationRows(c.id, w, NotificationView.INBOX).isEmpty())
            assertEquals(listOf(NotificationFixtures.id(2)), store.reader.notificationRows(c.id, w, NotificationView.ARCHIVED).map { it.id })
            assertEquals(9, store.reader.notificationSync(c.id, w)!!.localSeq)
        }
    }

    @Test fun contentFreeTombstoneRemovesTheRow() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5)), 5)
            val removal = NotificationChange.parse(Fixtures.obj(NotificationFixtures.tombstone(1, 9)))
            assertTrue(removal is NotificationChange.Removed)
            store.writer.applyNotificationChanges(c.id, w, listOf(removal), 9)
            assertNull(store.reader.notificationRow(c.id, w, NotificationFixtures.id(1)))
            assertEquals(0, store.reader.notificationCounts(c.id, w).unread)
        }
    }

    @Test fun overlayShowsIntentAtOnceAndIsDroppedOnlyByANewerRowVersion() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5, version = 2)), 5)
            assertEquals(1, store.reader.notificationCounts(c.id, w).unread)
            store.writer.setNotificationOverlay(c.id, w, NotificationFixtures.id(1), NotificationPatch(read = true))
            assertEquals("the tap counts immediately", 0, store.reader.notificationCounts(c.id, w).unread)
            assertTrue(store.reader.notificationRow(c.id, w, NotificationFixtures.id(1))!!.pending)
            // Same row_version (nothing new): the overlay stays.
            store.writer.applyNotificationChanges(c.id, w, listOf(upsert(item(1, 6, version = 2))), 6)
            assertEquals(0, store.reader.notificationCounts(c.id, w).unread)
            // New activity folded in (row_version moved): the old tap must not hide it.
            store.writer.applyNotificationChanges(c.id, w, listOf(upsert(item(1, 7, version = 3))), 7)
            val row = store.reader.notificationRow(c.id, w, NotificationFixtures.id(1))!!
            assertFalse(row.pending)
            assertTrue(row.unread)
        }
    }

    @Test fun pendingDismissHidesTheRowAndClearingRestoresIt() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5)), 5)
            store.writer.setNotificationOverlay(c.id, w, NotificationFixtures.id(1), NotificationPatch(dismissed = true))
            assertTrue(store.reader.notificationRows(c.id, w, NotificationView.INBOX).isEmpty())
            store.writer.clearNotificationOverlay(c.id, w, NotificationFixtures.id(1))
            assertEquals(1, store.reader.notificationRows(c.id, w, NotificationView.INBOX).size)
        }
    }

    @Test fun acknowledgementAdoptsTowersRowAndClearsTheOverlay() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5, version = 2)), 5)
            store.writer.setNotificationOverlay(c.id, w, NotificationFixtures.id(1), NotificationPatch(read = true))
            store.writer.acknowledgeNotificationItem(c.id, w, item(1, 8, version = 3, read = true))
            val row = store.reader.notificationRow(c.id, w, NotificationFixtures.id(1))!!
            assertFalse(row.pending)
            assertTrue(row.read)
            assertEquals(3, row.item.rowVersion)
        }
    }

    @Test fun bulkOverlayTouchesOnlyRowsAtOrBelowTheObservedSequence() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5), item(2, 6), item(3, 12)), 12)
            val touched = store.writer.setNotificationOverlayUpTo(c.id, w, upToSeq = 6, patch = NotificationPatch(read = true))
            assertEquals(setOf(NotificationFixtures.id(1), NotificationFixtures.id(2)), touched.toSet())
            // The item that arrived after the user looked stays unread.
            assertEquals(1, store.reader.notificationCounts(c.id, w).unread)
            assertTrue(store.reader.notificationRow(c.id, w, NotificationFixtures.id(3))!!.unread)
        }
    }

    @Test fun unavailableItemIsRedactedAtParseTimeAndCannotRoute() {
        val raw = NotificationFixtures.map(1, 5, access = "unavailable", title = "Secret plan from Morgan")
        val parsed = NotificationItem.parse(Fixtures.obj(raw))
        assertEquals(NotificationItem.UNAVAILABLE_TITLE, parsed.title)
        assertEquals("", parsed.body)
        assertNull(parsed.target)
        assertFalse(parsed.accessible)
        assertFalse("an unavailable item is never counted as unread activity", parsed.unread)
    }

    @Test fun itemFromAnotherWorkspaceOrWithoutKeysIsRefused() {
        val bad = NotificationFixtures.map(1, 5).toMutableMap().also { it.remove("row_version") }
        assertThrows(TowerError.Malformed::class.java) { NotificationItem.parse(Fixtures.obj(bad)) }
    }

    @Test fun accessLossAndUnsupportedBackendPurgeOnlyThatWorkspaceAndConnection() = runBlocking {
        fixture { store, c ->
            val other = store.writer.saveConnection(ConnectionRecord.create(studio.otherstuff.ambulando.core.transport.TowerOrigin("https://other.example.com"), TestKeys.NPUB, null))
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5)), 5)
            store.writer.replaceNotificationInbox(c.id, Fixtures.WORKSPACE_ID_2, listOf(item(2, 5, workspace = Fixtures.WORKSPACE_ID_2)), 5)
            store.writer.replaceNotificationInbox(other.id, w, listOf(item(1, 5)), 5)
            store.writer.markNotificationBackend(c.id, w, NotificationSyncState.Phase.ACCESS_LOST)
            assertTrue(store.reader.notificationRows(c.id, w).isEmpty())
            assertEquals(NotificationSyncState.Phase.ACCESS_LOST, store.reader.notificationSync(c.id, w)!!.state)
            assertEquals(1, store.reader.notificationRows(c.id, Fixtures.WORKSPACE_ID_2).size)
            assertEquals("another connection's inbox is a different identity's data", 1, store.reader.notificationRows(other.id, w).size)
        }
    }

    @Test fun disconnectingRemovesTheInbox() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5)), 5)
            store.writer.removeConnection(c.id)
            assertTrue(store.reader.notificationRows(c.id, w).isEmpty())
            assertNull(store.reader.notificationSync(c.id, w))
        }
    }

    @Test fun resetForgetsTheCursorSoTheNextRunIsAColdStart() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5)), 5)
            store.writer.resetNotificationCursor(c.id, w)
            val sync = store.reader.notificationSync(c.id, w)!!
            assertEquals(NotificationSyncState.Phase.UNSYNCED, sync.state)
            assertEquals(0, sync.localSeq)
        }
    }

    @Test fun unknownCategoryTargetKindAndSchemaDegradeToAGenericCardWithoutThrowing() {
        val raw = NotificationFixtures.map(1, 5, category = "brand_new_category", kind = "hologram").toMutableMap()
        raw["version"] = 7
        raw["future_field"] = mapOf("x" to 1)
        val parsed = NotificationItem.parse(Fixtures.obj(raw))
        assertEquals("brand_new_category", parsed.category)
        assertEquals("hologram", parsed.target?.kind)
        assertEquals(Fixtures.CHANNEL_1, parsed.target?.channelId)
    }

    @Test fun coldStartSnapshotReplacesRowsWhoseSequenceNumbersAreNoLongerComparable() = runBlocking {
        fixture { store, c ->
            // Rows held from before a Tower restore carry a higher change_seq than anything the restored Tower will report.
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 500, version = 9, read = true)), 500)
            store.writer.resetNotificationCursor(c.id, w)
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 7, version = 2, read = false)), 7)
            val row = store.reader.notificationRow(c.id, w, NotificationFixtures.id(1))!!
            assertEquals(7, row.item.changeSeq); assertTrue(row.unread)
            assertEquals(7, store.reader.notificationSync(c.id, w)!!.localSeq)
        }
    }

    @Test fun resetDropsTheArchivePartitionButKeepsTheInbox() = runBlocking {
        fixture { store, c ->
            store.writer.replaceNotificationInbox(c.id, w, listOf(item(1, 5), item(2, 6)), 6)
            store.writer.applyNotificationChanges(c.id, w, listOf(upsert(item(2, 7, 2, archived = true))), 7)
            assertEquals(1, store.reader.notificationRows(c.id, w, NotificationView.ARCHIVED).size)
            store.writer.resetNotificationCursor(c.id, w)
            assertTrue("an archive loaded under the old cursor is not trusted", store.reader.notificationRows(c.id, w, NotificationView.ARCHIVED).isEmpty())
            assertEquals(1, store.reader.notificationRows(c.id, w, NotificationView.INBOX).size)
        }
    }

    @Test fun upgradingAVersion3DatabaseKeepsItsNotificationsAndAddsTheSnapshotColumns() {
        val db = SqlConnection.memory()
        try {
            Schema.migrate(db, Schema.migrations.filter { it.version <= 3 })
            assertEquals(3, db.userVersion)
            db.run("INSERT INTO connections(id, origin, signer_npub, created_at) VALUES('c1','https://tower.example.com','npub1x','2026-10-26T00:00:00Z')")
            db.run(
                """INSERT INTO notification_items(connection_id, workspace_id, id, change_seq, row_version, category, source_app, severity, status, title, body, occurred_at, is_read, dismissed, archived, raw)
                   VALUES('c1','w1','i1',5,2,'mention','flightdeck.core','normal','active','Title','Body','2026-10-26T10:00:00Z',0,0,0,'{}')""",
            )
            db.run("INSERT INTO notification_sync(connection_id, workspace_id, state, local_seq) VALUES('c1','w1','ready',5)")
            Schema.migrate(db)
            assertEquals(Schema.VERSION, db.userVersion)
            assertEquals("Title", db.textValue("SELECT title FROM notification_items WHERE id = 'i1'"))
            assertEquals("a copy taken before per-app filtering starts over once", 0L, db.longValue("SELECT local_seq FROM notification_sync WHERE connection_id = 'c1'"))
            assertEquals("unsynced", db.textValue("SELECT state FROM notification_sync WHERE connection_id = 'c1'"))
            assertEquals(0L, db.longValue("SELECT snapshot_gen FROM notification_items WHERE id = 'i1'"))
            assertNull(db.textValue("SELECT snapshot_cursor FROM notification_sync WHERE connection_id = 'c1'"))
        } finally { db.close() }
    }

    @Test fun upgradingAVersion4DatabaseForcesOneFilteredSnapshotAndDropsTheArchivePartition() {
        val db = SqlConnection.memory()
        try {
            Schema.migrate(db, Schema.migrations.filter { it.version <= 4 })
            db.run("INSERT INTO connections(id, origin, signer_npub, created_at) VALUES('c1','https://tower.example.com','npub1x','2026-10-26T00:00:00Z')")
            for ((id, partition) in listOf("i1" to "inbox", "i2" to "archived")) db.run(
                """INSERT INTO notification_items(connection_id, workspace_id, id, change_seq, row_version, partition, category, source_app, severity, status, title, body, occurred_at, is_read, dismissed, archived, raw)
                   VALUES('c1','w1','$id',5,2,'$partition','mention','flightdeck.core','normal','active','Title','Body','2026-10-26T10:00:00Z',0,0,0,'{}')""",
            )
            db.run("INSERT INTO notification_sync(connection_id, workspace_id, state, local_seq, archived_loaded) VALUES('c1','w1','ready',5,1)")
            db.run("INSERT INTO notification_sync(connection_id, workspace_id, state, local_seq) VALUES('c1','w2','unsupported',0)")
            Schema.migrate(db)
            assertEquals(Schema.VERSION, db.userVersion)
            assertEquals("unsynced", db.textValue("SELECT state FROM notification_sync WHERE workspace_id = 'w1'"))
            assertEquals(0L, db.longValue("SELECT local_seq FROM notification_sync WHERE workspace_id = 'w1'"))
            assertEquals(0L, db.longValue("SELECT archived_loaded FROM notification_sync WHERE workspace_id = 'w1'"))
            assertNull("no revision yet: the first snapshot sets it", db.longValue("SELECT rules_revision FROM notification_sync WHERE workspace_id = 'w1'"))
            assertEquals("unsupported", db.textValue("SELECT state FROM notification_sync WHERE workspace_id = 'w2'"))
            assertEquals("the inbox row stays visible until the new snapshot completes", 1L, db.longValue("SELECT COUNT(*) FROM notification_items WHERE partition = 'inbox'"))
            assertEquals(0L, db.longValue("SELECT COUNT(*) FROM notification_items WHERE partition = 'archived'"))
        } finally { db.close() }
    }

    @Test fun theSnapshotsFirstPageRulesRevisionBecomesTheStoredRevisionOnlyOnCompletion() = runBlocking {
        fixture { store, c ->
            val gen = store.writer.beginNotificationSnapshot(c.id, w)
            store.writer.saveNotificationSnapshotPage(c.id, w, gen, listOf(item(1, 5)), "next", 5, null, rulesRevision = 7)
            assertNull("not in force until the snapshot completes", store.reader.notificationSync(c.id, w)!!.rulesRevision)
            assertEquals(7L, store.reader.notificationSnapshotProgress(c.id, w)!!.rulesRevision)
            store.writer.saveNotificationSnapshotPage(c.id, w, gen, listOf(item(2, 6)), null, 5, null, rulesRevision = null)
            store.writer.completeNotificationSnapshot(c.id, w, gen, 5)
            assertEquals(7L, store.reader.notificationSync(c.id, w)!!.rulesRevision)
            store.writer.resetNotificationCursor(c.id, w)
            assertNull("a reset forgets it with the cursor", store.reader.notificationSync(c.id, w)!!.rulesRevision)
        }
    }
}
