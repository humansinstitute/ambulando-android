package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.notifications.NotificationsActions
import studio.otherstuff.ambulando.app.ui.notifications.NotificationsScreen
import studio.otherstuff.ambulando.app.ui.notifications.NotificationsUiState
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.notifications.NotificationDestination
import studio.otherstuff.ambulando.core.notifications.NotificationFilter
import studio.otherstuff.ambulando.core.notifications.NotificationInboxLogic
import studio.otherstuff.ambulando.core.notifications.NotificationRouting
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.ObservedNotification
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.sync.refreshNotificationItem
import studio.otherstuff.ambulando.core.transport.NotificationBulkOp
import studio.otherstuff.ambulando.core.transport.NotificationPatch
import studio.otherstuff.ambulando.core.transport.NotificationView

/** The version of the item as this row was drawn: what a tap is checked against. */
private fun NotificationRow.observed() = ObservedNotification(item.rowVersion, item.changeSeq)

private data class InboxData(val rows: List<NotificationRow>, val categories: List<String>, val unread: Int, val sync: NotificationSyncState?, val held: Int, val maxSeq: Long)

/** Hosts the notification inbox for the selected workspace: reads the local store only; every change goes through the write queue. */
@Composable internal fun NativeNotifications(model: AppModel, done: () -> Unit) {
    val c = model.connection ?: return
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    val filterState = remember(w.id) { mutableStateOf(NotificationFilter()) }
    val filter = filterState.value
    var detail by remember { mutableStateOf<NotificationRow?>(null) }
    val data by remember(c.id, w.id, filter) {
        model.store.liveQuery({ it.table == StoreTable.NOTIFICATIONS && (it.workspaceId == null || it.workspaceId == w.id) }) {
            val r = model.store.reader
            val all = r.notificationRows(c.id, w.id, NotificationView.ALL, limit = Int.MAX_VALUE)
            InboxData(
                r.notificationRows(c.id, w.id, filter.view, filter.category), all.map { it.item.category }.distinct(),
                r.notificationCounts(c.id, w.id).unread, r.notificationSync(c.id, w.id), all.size, r.notificationMaxSeq(c.id, w.id),
            )
        }
    }.collectAsState(null)
    val engine by model.engine.status.collectAsState()
    val key = WorkspaceSync.notificationsKey(w.id)
    LaunchedEffect(c.id, w.id) { model.refreshNotifications(force = false) }
    LaunchedEffect(filter.view) { if (filter.view == NotificationView.ARCHIVED) model.loadArchivedNotifications() }
    BackHandler { done() }
    val current = data
    val unlocked = model.identityState is IdentityState.Unlocked
    val state = NotificationsUiState(
        rows = current?.rows ?: emptyList(), filter = filter,
        categories = NotificationInboxLogic.filterCategories(current?.categories ?: emptyList()), unread = current?.unread ?: 0,
        empty = NotificationInboxLogic.emptyReason(current?.sync, current?.rows ?: emptyList(), current?.held ?: 0, filter, key in engine.running || key in engine.queued || current == null),
        syncing = key in engine.running, error = engine.errors[key], message = model.notificationMessage, canChange = unlocked && !w.accessRemoved,
    )
    val actions = remember(c.id, w.id, current?.maxSeq, filter) { object : NotificationsActions {
        override fun open(row: NotificationRow) {
            when (val destination = NotificationRouting.destination(row.item)) {
                NotificationDestination.Unavailable -> {
                    model.act { model.sync.refreshNotificationItem(c, w, row.id) }
                    model.showNotificationMessage("That item is no longer available to you.")
                }
                NotificationDestination.Detail -> { detail = row; if (row.unread) model.changeNotification(row.id, NotificationPatch(read = true), row.observed()) }
                else -> {
                    if (row.unread) model.changeNotification(row.id, NotificationPatch(read = true), row.observed())
                    when (destination) {
                        is NotificationDestination.Thread -> services.open(WorkRoute.Thread(w.id, destination.channelId, destination.threadId))
                        is NotificationDestination.Channel -> services.open(WorkRoute.Channel(w.id, destination.channelId))
                        is NotificationDestination.Task -> services.open(WorkRoute.Task(w.id, destination.taskId))
                        is NotificationDestination.Doc -> services.open(WorkRoute.Doc(w.id, destination.docId))
                        else -> Unit
                    }
                    done()
                }
            }
        }
        override fun setFilter(filter: NotificationFilter) { filterState.value = filter }
        override fun setRead(row: NotificationRow, read: Boolean) { model.changeNotification(row.id, NotificationPatch(read = read), row.observed()) }
        override fun dismiss(row: NotificationRow) { model.changeNotification(row.id, NotificationPatch(dismissed = true), row.observed()) }
        override fun setArchived(row: NotificationRow, archived: Boolean) { model.changeNotification(row.id, NotificationPatch(archived = archived), row.observed()) }
        override fun markAllRead() { model.bulkNotifications(NotificationBulkOp.MARK_READ, current?.maxSeq ?: 0, filter.category) }
        override fun refresh() { model.refreshNotifications(force = true) }
        override fun dismissMessage() { model.dismissNotificationMessage() }
        override fun close() { done() }
    } }
    NotificationsScreen(state, actions)
    detail?.let { row ->
        AlertDialog(
            onDismissRequest = { detail = null }, title = { Text(row.item.title) },
            text = { Text(listOfNotNull(row.item.body.takeIf { it.isNotEmpty() }, "From ${row.item.sourceLabel ?: "an app"}. This kind of notification can't be opened in the native app yet.").joinToString("\n\n")) },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
        )
    }
}
