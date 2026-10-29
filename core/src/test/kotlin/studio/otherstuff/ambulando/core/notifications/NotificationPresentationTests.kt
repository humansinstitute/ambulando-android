package studio.otherstuff.ambulando.core.notifications

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.NotificationRow
import studio.otherstuff.ambulando.core.store.NotificationSyncState
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.NotificationFixtures
import studio.otherstuff.ambulando.core.support.NotificationFixtures.item
import studio.otherstuff.ambulando.core.transport.NotificationItem
import studio.otherstuff.ambulando.core.transport.NotificationView

class NotificationPresentationTests {
    private fun withTarget(target: Map<String, Any?>?, access: String = "ok", status: String = "active"): NotificationItem {
        val m = NotificationFixtures.map(1, 5, access = access, status = status).toMutableMap()
        m["action"] = target?.let { mapOf("type" to "open", "target" to it) }
        return NotificationItem.parse(Fixtures.obj(m))
    }

    private fun row(i: NotificationItem) = NotificationRow(i, i.state.read, i.state.dismissed, i.state.archived, "inbox", false, null)
    private fun sync(phase: NotificationSyncState.Phase, since: String? = "2026-10-26T09:00:00Z") = NotificationSyncState(phase, 0, null, false, null, null, since)

    @Test fun threadTaskDocAndChannelTargetsRouteToTheirScreens() {
        val c = Fixtures.CHANNEL_1; val t = NotificationFixtures.THREAD; val id = "00000000-0000-4000-8000-0000000000e1"
        assertEquals(NotificationDestination.Thread(c, t), NotificationRouting.destination(withTarget(mapOf("kind" to "thread", "channel_id" to c, "thread_id" to t, "message_id" to id))))
        assertEquals(NotificationDestination.Task(id), NotificationRouting.destination(withTarget(mapOf("kind" to "task", "task_id" to id))))
        assertEquals(NotificationDestination.Doc(id), NotificationRouting.destination(withTarget(mapOf("kind" to "doc", "doc_id" to id))))
        assertEquals(NotificationDestination.Channel(c), NotificationRouting.destination(withTarget(mapOf("kind" to "channel", "channel_id" to c))))
    }

    @Test fun threadWithoutThreadIdFallsBackToItsChannel() {
        assertEquals(NotificationDestination.Channel(Fixtures.CHANNEL_1), NotificationRouting.destination(withTarget(mapOf("kind" to "thread", "channel_id" to Fixtures.CHANNEL_1))))
    }

    @Test fun unknownKindsAppObjectsAndMissingIdsOpenTheGenericCard() {
        assertEquals(NotificationDestination.Detail, NotificationRouting.destination(withTarget(mapOf("kind" to "hologram", "id" to "x"))))
        assertEquals(NotificationDestination.Detail, NotificationRouting.destination(withTarget(mapOf("kind" to "app_object", "app_id" to "blinkers", "object_id" to "7c1e"))))
        assertEquals(NotificationDestination.Detail, NotificationRouting.destination(withTarget(mapOf("kind" to "task"))))
        assertEquals(NotificationDestination.Detail, NotificationRouting.destination(withTarget(null)))
    }

    @Test fun targetIdsThatAreNotUuidsNeverReachARoute() {
        val d = NotificationRouting.destination(withTarget(mapOf("kind" to "task", "task_id" to "../../etc")))
        assertEquals(NotificationDestination.Detail, d)
    }

    @Test fun inaccessibleOrRevokedItemsOpenNothing() {
        val ok = mapOf("kind" to "task", "task_id" to "00000000-0000-4000-8000-0000000000e1")
        assertEquals(NotificationDestination.Unavailable, NotificationRouting.destination(withTarget(ok, access = "unavailable")))
        assertEquals(NotificationDestination.Unavailable, NotificationRouting.destination(withTarget(ok, status = "revoked")))
    }

    @Test fun emptyReasonsAreExplicit() {
        val none = emptyList<NotificationRow>()
        val f = NotificationFilter()
        assertEquals(NotificationEmptyReason.BackendNotSupported, NotificationInboxLogic.emptyReason(sync(NotificationSyncState.Phase.UNSUPPORTED), none, 0, f, false))
        assertEquals(NotificationEmptyReason.AccessLost, NotificationInboxLogic.emptyReason(sync(NotificationSyncState.Phase.ACCESS_LOST), none, 0, f, false))
        assertEquals(NotificationEmptyReason.Loading, NotificationInboxLogic.emptyReason(null, none, 0, f, false))
        assertEquals(NotificationEmptyReason.InboxStarted("2026-10-26T09:00:00Z"), NotificationInboxLogic.emptyReason(sync(NotificationSyncState.Phase.READY), none, 0, f, false))
        assertEquals(NotificationEmptyReason.FilteredOut, NotificationInboxLogic.emptyReason(sync(NotificationSyncState.Phase.READY), none, 3, NotificationFilter(NotificationView.UNREAD), false))
        assertNull(NotificationInboxLogic.emptyReason(sync(NotificationSyncState.Phase.READY), listOf(row(item(1, 5))), 1, f, false))
    }

    @Test fun categoryFiltersFollowUrgencyOrderThenTheRest() {
        assertEquals(listOf("mention", "task_assignment", "chat_thread", "capture_failed", "zeta"), NotificationInboxLogic.filterCategories(listOf("zeta", "chat_thread", "capture_failed", "mention", "task_assignment")))
        assertEquals("Thread activity", NotificationInboxLogic.categoryLabel("chat_thread"))
        assertEquals("Capture failed", NotificationInboxLogic.categoryLabel("capture_failed"))
    }

    @Test fun badgeIsHiddenAtZeroAndCapped() {
        assertNull(NotificationInboxLogic.badge(0)); assertEquals("7", NotificationInboxLogic.badge(7)); assertEquals("99+", NotificationInboxLogic.badge(240))
    }
}
