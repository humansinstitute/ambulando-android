package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.*

/** The client's notification API against the labelled stand-in, which follows the `notifications-v1` contract rules. */
class SampleNotificationsTests {
    private val w = SampleIds.STUDIO

    @Test fun listCountsAndCursorDeltaAgree() = runBlocking {
        val h = SampleHarness()
        val page = h.api.notificationItems(w, h.app, NotificationView.INBOX, limit = 2)
        assertEquals(2, page.items.size); assertNotNull(page.nextCursor); assertEquals(3L, page.headSeq); assertEquals(3, page.unreadTotal)
        assertNotNull(page.inboxStartedAt)
        val rest = h.api.notificationItems(w, h.app, NotificationView.INBOX, cursor = page.nextCursor, limit = 2)
        assertEquals(1, rest.items.size); assertNull(rest.nextCursor)
        val counts = h.api.notificationCounts(w, h.app)
        assertEquals(3, counts.unreadTotal); assertEquals(3L, counts.headSeq)
        val delta = h.api.notificationChanges(w, h.app, afterSeq = 1)
        assertEquals(2, delta.changes.size); assertEquals(3L, delta.throughSeq); assertFalse(delta.hasMore)
        val empty = h.api.notificationChanges(w, h.app, afterSeq = 3)
        assertEquals(3L, empty.throughSeq); assertTrue(empty.changes.isEmpty())
    }

    @Test fun cursorAheadOfTheHeadIsResetRequired() = runBlocking {
        val h = SampleHarness()
        try { h.api.notificationChanges(w, h.app, afterSeq = 99); fail("410") } catch (e: TowerError.Http) { assertEquals(410, e.status) }
    }

    @Test fun staleRowVersionConflictsAndReturnsTheCurrentItem() = runBlocking {
        val h = SampleHarness()
        val item = h.api.notificationItems(w, h.app).items.first()
        val ok = h.api.patchNotification(w, h.app, item.id, newRequestId(), item.rowVersion, NotificationPatch(read = true), item.changeSeq)
        assertTrue(ok is NotificationWriteResult.Applied); ok as NotificationWriteResult.Applied
        assertTrue(ok.item.state.read); assertEquals(item.rowVersion + 1, ok.item.rowVersion)
        val stale = h.api.patchNotification(w, h.app, item.id, newRequestId(), item.rowVersion, NotificationPatch(read = false))
        assertTrue(stale is NotificationWriteResult.StateConflict)
        assertEquals(ok.item.rowVersion, (stale as NotificationWriteResult.StateConflict).current?.rowVersion)
    }

    @Test fun activityFoldedAfterTheUserLookedCannotBeAcknowledgedByTheOldTap() = runBlocking {
        val h = SampleHarness()
        val item = h.api.notificationItems(w, h.app).items.first()
        h.tower.notifications.fold(item.id)
        val result = h.api.patchNotification(w, h.app, item.id, newRequestId(), item.rowVersion, NotificationPatch(read = true), item.changeSeq)
        assertTrue(result is NotificationWriteResult.StateConflict)
        val current = h.api.notificationItems(w, h.app).items.first { it.id == item.id }
        assertFalse("the folded activity is still unread", current.state.read)
        assertEquals(2, current.eventCount)
    }

    @Test fun sameMutationIdReplaysAndAChangedRequestIsRejected() = runBlocking {
        val h = SampleHarness()
        val item = h.api.notificationItems(w, h.app).items.first()
        val mutation = newRequestId()
        val first = h.api.patchNotification(w, h.app, item.id, mutation, item.rowVersion, NotificationPatch(read = true)) as NotificationWriteResult.Applied
        val replay = h.api.patchNotification(w, h.app, item.id, mutation, item.rowVersion, NotificationPatch(read = true)) as NotificationWriteResult.Applied
        assertEquals(first.item.rowVersion, replay.item.rowVersion)
        val changed = h.api.patchNotification(w, h.app, item.id, mutation, item.rowVersion, NotificationPatch(dismissed = true))
        assertTrue(changed is NotificationWriteResult.MutationReused)
    }

    @Test fun bulkOnlyTouchesWhatWasObservedAndReplaysSafely() = runBlocking {
        val h = SampleHarness()
        val items = h.api.notificationItems(w, h.app).items
        val observed = h.api.notificationItems(w, h.app).headSeq
        h.tower.notifications.fold(items.first().id)       // arrives after the user looked: its change_seq is above the boundary
        val mutation = newRequestId()
        val result = h.api.bulkNotifications(w, h.app, mutation, NotificationBulkOp.MARK_READ, observed)
        assertEquals(2, (result as NotificationWriteResult.BulkApplied).affected)
        val after = h.api.notificationItems(w, h.app, NotificationView.UNREAD).items
        assertEquals(listOf(items.first().id), after.map { it.id })
        val replay = h.api.bulkNotifications(w, h.app, mutation, NotificationBulkOp.MARK_READ, observed)
        assertEquals(2, (replay as NotificationWriteResult.BulkApplied).affected)
    }

    @Test fun dismissedAndArchivedLeaveTheInboxButStayListable() = runBlocking {
        val h = SampleHarness()
        val items = h.api.notificationItems(w, h.app).items
        h.api.patchNotification(w, h.app, items[0].id, newRequestId(), items[0].rowVersion, NotificationPatch(dismissed = true))
        h.api.patchNotification(w, h.app, items[1].id, newRequestId(), items[1].rowVersion, NotificationPatch(archived = true))
        assertEquals(1, h.api.notificationItems(w, h.app).items.size)
        assertEquals(listOf(items[1].id), h.api.notificationItems(w, h.app, NotificationView.ARCHIVED).items.map { it.id })
        assertEquals(1, h.api.notificationCounts(w, h.app).unreadTotal)
    }

    @Test fun categoriesAndRulesAreServed() = runBlocking {
        val h = SampleHarness()
        assertEquals(setOf("mention", "task_assignment", "chat_thread"), h.api.notificationCategories(w, h.app).map { it.category }.toSet())
        assertTrue(h.api.notificationRules(w, h.app).rules.isEmpty())
    }

    @Test fun otherWorkspacesHaveNoInbox() = runBlocking {
        val h = SampleHarness()
        try { h.api.notificationItems(SampleIds.LAB, h.app); fail("404") } catch (e: TowerError.Http) { assertEquals(404, e.status) }
    }

    // MARK: wire v1.1 (per-app view, rules compare-and-set, rules_revision)

    private suspend fun idsFor(h: SampleHarness, client: String?, view: String = "inbox"): List<String> {
        val query = listOfNotNull("view" to view, client?.let { "client_app_id" to it })
        val o = studio.otherstuff.ambulando.core.transport.parseJsonObject(h.client.request(path = h.base() + "/notifications/items", query = query, appNpub = h.app), "items")
        return o.objects("items")!!.map { it.string("id")!! }
    }

    @Test fun anItemForAnotherAppIsHiddenFromThisAppButTheSharedRowStays() = runBlocking {
        val h = SampleHarness()
        val other = h.tower.notifications.raise("mention", "Only for another app", listOf("otherapp"))
        val shared = h.tower.notifications.raise("mention", "For everyone", listOf("*"))
        val mine = h.api.notificationItems(w, h.app).items.map { it.id }
        assertFalse(other in mine); assertTrue(shared in mine)
        assertEquals("count and list use one predicate", 4, h.api.notificationCounts(w, h.app).unreadTotal)
        assertEquals(4, h.api.notificationItems(w, h.app).unreadTotal)
        assertTrue("the other app still sees it", other in idsFor(h, "otherapp"))
        assertTrue("no client app: everything the actor may access", other in idsFor(h, null))
    }

    @Test fun anAppSpecificOffRuleHidesFromThisAppOnlyAndReadStateStaysShared() = runBlocking {
        val h = SampleHarness()
        val id = h.api.notificationItems(w, h.app).items.first { it.category == "mention" }.id
        h.tower.notifications.setRule("flightdeck.core", "mention", "ambulando", "off")
        assertFalse(id in h.api.notificationItems(w, h.app).items.map { it.id })
        assertEquals(2, h.api.notificationCounts(w, h.app).unreadTotal)
        assertTrue("another app still shows it", id in idsFor(h, "otherapp"))
        // Read it through the other app's view of the shared row, then it is read for this app too once the rule is lifted.
        val row = studio.otherstuff.ambulando.core.transport.parseJsonObject(h.client.request(path = h.base() + "/notifications/items/$id", appNpub = h.app), "item").obj("item")!!
        h.api.patchNotification(w, h.app, id, newRequestId(), flexibleLong(row["row_version"])!!, NotificationPatch(read = true))
        h.tower.notifications.setRule("flightdeck.core", "mention", "ambulando", "default")
        val back = h.api.notificationItems(w, h.app).items.first { it.id == id }
        assertTrue("one shared read state", back.state.read)
        assertEquals(2, back.rowVersion)
    }

    @Test fun anAppSpecificRuleBeatsAWildcardRuleAtTheSameLevelAndOffWinsTies() = runBlocking {
        val h = SampleHarness()
        h.tower.notifications.setRule("flightdeck.core", "mention", "*", "off")
        assertFalse(h.api.notificationItems(w, h.app).items.any { it.category == "mention" })
        h.tower.notifications.setRule("flightdeck.core", "mention", "ambulando", "on")
        assertTrue("this app's own rule wins over the wildcard", h.api.notificationItems(w, h.app).items.any { it.category == "mention" })
        h.tower.notifications.setRule("flightdeck.core", "*", "ambulando", "off")
        assertTrue("a more specific key (source, category) still wins over (source, *)", h.api.notificationItems(w, h.app).items.any { it.category == "mention" })
        assertFalse(h.api.notificationItems(w, h.app).items.any { it.category == "chat_thread" })
    }

    @Test fun changesFilterForThisAppAndAFilteredPageStillAdvancesToTheHead() = runBlocking {
        val h = SampleHarness()
        h.tower.notifications.raise("mention", "Another app only", listOf("otherapp"))
        val page = h.api.notificationChanges(w, h.app, afterSeq = 3)
        assertTrue(page.changes.isEmpty()); assertFalse(page.hasMore)
        assertEquals("through_seq reaches the head so the next call does not re-scan the filtered row", 4L, page.throughSeq)
        assertEquals(4L, h.api.notificationChanges(w, h.app, afterSeq = 4).throughSeq)
    }

    @Test fun staleRulesRevisionOnChangesIsResetRequiredAndEveryResponseCarriesTheRevision() = runBlocking {
        val h = SampleHarness()
        val revision = h.api.notificationItems(w, h.app).rulesRevision!!
        assertEquals(revision, h.api.notificationCounts(w, h.app).rulesRevision)
        assertEquals(revision, h.api.notificationRules(w, h.app).rulesRevision)
        assertEquals(revision, h.api.notificationChanges(w, h.app, afterSeq = 1, rulesRevision = revision).rulesRevision)
        h.tower.notifications.setRule("flightdeck.core", "mention", "ambulando", "off")
        try { h.api.notificationChanges(w, h.app, afterSeq = 1, rulesRevision = revision); fail("410") } catch (e: TowerError.Http) { assertEquals(410, e.status) }
        assertEquals(revision + 1, h.api.notificationChanges(w, h.app, afterSeq = 1, rulesRevision = revision + 1).rulesRevision)
    }

    private fun write(category: String, mode: String, expected: Long, client: String = "ambulando") =
        NotificationRuleWrite("flightdeck.core", category, client, "inbox", mode, null, expected)

    @Test fun rulesWriteIsPerKeyCompareAndSetAndAllOrNothing() = runBlocking {
        val h = SampleHarness()
        val first = h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("mention", "off", 0))) as NotificationRulesResult.Saved
        assertEquals(1, first.current.rules.size); assertEquals(1L, first.current.rules.single().rowVersion)
        assertEquals("ambulando", first.current.rules.single().clientAppId)
        val revision = first.current.rulesRevision!!
        // One valid entry and one stale entry: nothing is written.
        val mixed = h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("task_assignment", "off", 0), write("mention", "on", 0)))
        mixed as NotificationRulesResult.Conflict
        assertEquals(1, mixed.conflictCount)
        assertEquals("the whole current set comes back", listOf("mention"), mixed.current.rules.map { it.category })
        assertEquals(revision, mixed.current.rulesRevision)
        assertEquals("nothing was written", revision, h.api.notificationRules(w, h.app).rulesRevision)
        assertEquals("task_assignment was not applied", 1, h.api.notificationRules(w, h.app).rules.size)
        // Rebased on the current row version it succeeds, and bumps the revision once.
        val ok = h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("mention", "on", 1))) as NotificationRulesResult.Saved
        assertEquals(2L, ok.current.rules.single().rowVersion); assertEquals(revision + 1, ok.current.rulesRevision)
    }

    @Test fun anotherDevicesChangeIsAConflictNotAnOverwriteAndRetryNeedsANewMutationId() = runBlocking {
        val h = SampleHarness()
        val before = h.api.notificationRules(w, h.app)
        h.tower.notifications.setRule("flightdeck.core", "mention", "ambulando", "off")        // the other device
        val mutation = newRequestId()
        val conflict = h.api.putNotificationRules(w, h.app, mutation, listOf(write("mention", "on", 0)))
        conflict as NotificationRulesResult.Conflict
        assertEquals("off", conflict.current.rules.single().mode)
        assertEquals(before.rulesRevision!! + 1, conflict.current.rulesRevision)
        // The person decides again against the current row: a NEW mutation id with the adopted row_version.
        val retry = h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("mention", "on", conflict.current.rules.single().rowVersion))) as NotificationRulesResult.Saved
        assertEquals("on", retry.current.rules.single().mode); assertEquals(2L, retry.current.rules.single().rowVersion)
    }

    @Test fun identicalRulesReplayReturnsTheStoredResponseAndAChangedBodyUnderTheSameIdIsReused() = runBlocking {
        val h = SampleHarness()
        val mutation = newRequestId()
        val a = h.api.putNotificationRules(w, h.app, mutation, listOf(write("mention", "off", 0))) as NotificationRulesResult.Saved
        val b = h.api.putNotificationRules(w, h.app, mutation, listOf(write("mention", "off", 0))) as NotificationRulesResult.Saved
        assertEquals(a, b)
        assertEquals("a replay does not bump again", a.current.rulesRevision, h.api.notificationRules(w, h.app).rulesRevision)
        assertEquals(NotificationRulesResult.MutationReused, h.api.putNotificationRules(w, h.app, mutation, listOf(write("mention", "on", 0))))
    }

    @Test fun defaultModeKeepsADurableInheritedRowWithAnIncrementedVersion() = runBlocking {
        val h = SampleHarness()
        h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("mention", "off", 0)))
        assertFalse(h.api.notificationItems(w, h.app).items.any { it.category == "mention" })
        val undone = h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("mention", "default", 1))) as NotificationRulesResult.Saved
        val row = undone.current.rules.single()
        assertEquals("default", row.mode); assertEquals("the row is kept and its version rises", 2L, row.rowVersion)
        assertTrue("an inherited row hides nothing", h.api.notificationItems(w, h.app).items.any { it.category == "mention" })
        // A stale base of 0 (the key looked absent before) must not resurrect an old override over the reset.
        assertTrue(h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("mention", "off", 0))) is NotificationRulesResult.Conflict)
        assertTrue(h.api.putNotificationRules(w, h.app, newRequestId(), listOf(write("mention", "off", 2))) is NotificationRulesResult.Saved)
    }

    @Test fun bulkWithThisAppAsClientTouchesOnlyWhatItCanSee() = runBlocking {
        val h = SampleHarness()
        val hidden = h.tower.notifications.raise("mention", "Another app only", listOf("otherapp"))
        val head = h.api.notificationItems(w, h.app).headSeq
        val result = h.api.bulkNotifications(w, h.app, newRequestId(), NotificationBulkOp.MARK_READ, head) as NotificationWriteResult.BulkApplied
        assertEquals("the three visible items, not the hidden one", 3, result.affected)
        assertTrue("still unread for the app that can see it", hidden in idsFor(h, "otherapp", "unread"))
    }
}
