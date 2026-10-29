package studio.otherstuff.ambulando.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.notifications.NotificationEmptyReason
import studio.otherstuff.ambulando.core.notifications.NotificationFilter
import studio.otherstuff.ambulando.core.notifications.NotificationInboxLogic
import studio.otherstuff.ambulando.core.store.NotificationRow
import studio.otherstuff.ambulando.core.transport.NotificationView
import java.time.Duration
import java.time.Instant

/** Everything the notification inbox shows. The app model builds it from the store; nothing here reads the network. */
@Immutable
data class NotificationsUiState(
    val rows: List<NotificationRow>,
    val filter: NotificationFilter,
    /** Categories present in the inbox, offered as filters. */
    val categories: List<String>,
    val unread: Int,
    /** Why the list is empty (null when there are rows). */
    val empty: NotificationEmptyReason?,
    val syncing: Boolean,
    val error: String?,
    /** Feedback about an action that did not apply as tapped; dismissible. */
    val message: String?,
    /** Signed writes need the identity unlocked; while locked the inbox is read-only. */
    val canChange: Boolean,
    val now: Instant = Instant.now(),
)

interface NotificationsActions {
    fun open(row: NotificationRow)
    fun setFilter(filter: NotificationFilter)
    fun setRead(row: NotificationRow, read: Boolean)
    fun dismiss(row: NotificationRow)
    fun setArchived(row: NotificationRow, archived: Boolean)
    /** Marks everything shown (that this device has seen) read; newer arrivals stay unread. */
    fun markAllRead()
    fun refresh()
    fun dismissMessage()
    fun close()
}

@Composable
fun NotificationsScreen(state: NotificationsUiState, actions: NotificationsActions) {
    val amb = Amb.colors
    Column(Modifier.fillMaxSize().background(amb.canvas).statusBarsPadding().navigationBarsPadding().testTag("notifications.screen")) {
        Row(Modifier.fillMaxWidth().background(amb.background).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val subtitle = when (state.empty) {
                    NotificationEmptyReason.BackendNotSupported -> "Not available on this Tower"
                    NotificationEmptyReason.AccessLost -> "No access"
                    NotificationEmptyReason.Loading -> "Loading…"
                    else -> if (state.unread == 0) "All caught up" else "${state.unread} unread"
                }
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground,
                    modifier = Modifier.testTag("notifications.unread"))
            }
            IconButton(onClick = actions::refresh, modifier = Modifier.testTag("notifications.refresh")) { Icon(Icons.Outlined.Refresh, "Refresh notifications") }
            TextButton(onClick = actions::close, modifier = Modifier.testTag("notifications.done")) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
        FilterBar(state, actions)
        state.message?.let { message ->
            Row(Modifier.fillMaxWidth().background(amb.infoBackground).padding(horizontal = 12.dp, vertical = 6.dp).testTag("notifications.message"), verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = MaterialTheme.typography.labelMedium, color = amb.info, modifier = Modifier.weight(1f))
                TextButton(onClick = actions::dismissMessage) { Text("OK") }
            }
        }
        state.error?.let {
            Text("Showing saved notifications. Update failed: $it", Modifier.fillMaxWidth().background(amb.warningBackground).padding(8.dp).testTag("notifications.error"),
                style = MaterialTheme.typography.labelSmall, color = amb.warning)
        }
        if (!state.canChange) Text("Locked: you can read saved notifications. Unlock to mark, archive or dismiss them.",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("notifications.locked"), style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
        if (state.rows.isEmpty()) EmptyState(state.empty)
        else LazyColumn(Modifier.weight(1f).testTag("notifications.list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.rows, key = { it.id }) { row -> NotificationCard(row, state, actions) }
        }
    }
}

@Composable
private fun FilterBar(state: NotificationsUiState, actions: NotificationsActions) {
    val views = listOf(NotificationView.INBOX to "Inbox", NotificationView.UNREAD to "Unread", NotificationView.ARCHIVED to "Archived")
    Column(Modifier.fillMaxWidth().background(Amb.colors.background)) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("notifications.views")) {
            items(views) { (view, label) ->
                FilterChip(selected = state.filter.view == view, onClick = { actions.setFilter(state.filter.copy(view = view)) }, label = { Text(label) }, modifier = Modifier.testTag("notifications.view.${view.wire}"))
            }
            if (state.categories.isNotEmpty()) items(state.categories) { category ->
                FilterChip(selected = state.filter.category == category, onClick = { actions.setFilter(state.filter.copy(category = if (state.filter.category == category) null else category)) },
                    label = { Text(NotificationInboxLogic.categoryLabel(category)) }, modifier = Modifier.testTag("notifications.category.$category"))
            }
        }
        if (state.unread > 0 && state.filter.view != NotificationView.ARCHIVED) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions::markAllRead, enabled = state.canChange, modifier = Modifier.testTag("notifications.markAll")) { Text("Mark all read") }
        }
        HorizontalDivider(color = Amb.colors.border)
    }
}

@Composable
private fun EmptyState(reason: NotificationEmptyReason?) {
    val amb = Amb.colors
    val (title, detail) = when (reason) {
        NotificationEmptyReason.BackendNotSupported -> "Notifications aren't available on this Tower" to
            "This Tower does not have the notification inbox yet. Nothing is wrong on your device; it will appear after the Tower is updated."
        NotificationEmptyReason.AccessLost -> "No access to this workspace's notifications" to
            "Your access changed, so saved notifications for this workspace were removed from this device."
        NotificationEmptyReason.Loading -> "Loading notifications…" to ""
        is NotificationEmptyReason.InboxStarted -> "Nothing here yet" to
            (reason.since?.let { "Your inbox started ${startedLabel(it)}. Earlier activity is not imported." } ?: "Earlier activity is not imported.")
        NotificationEmptyReason.FilteredOut -> "Nothing matches this filter" to "Choose Inbox to see everything."
        null -> "" to ""
    }
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("notifications.empty"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
    }
}

@Composable
private fun NotificationCard(row: NotificationRow, state: NotificationsUiState, actions: NotificationsActions) {
    val amb = Amb.colors
    val item = row.item
    var menu by remember { mutableStateOf(false) }
    val unread = row.unread
    val label = (if (unread) "Unread. " else "") + item.title + ". " + item.body
    Row(
        Modifier.fillMaxWidth().background(amb.card, RoundedCornerShape(Amb.radiusPanel)).heightIn(min = 56.dp)
            .clickable(enabled = item.accessible) { actions.open(row) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            .semantics { contentDescription = label }.testTag("notification.${row.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp, end = 8.dp).background(if (unread) amb.primary else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.CircleShape)
            .then(Modifier.heightIn(min = 8.dp)).padding(4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (item.accessible) androidx.compose.ui.graphics.Color.Unspecified else amb.mutedForeground)
            if (item.body.isNotEmpty()) Text(item.body, style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                add(item.sourceLabel ?: "Notification")
                add(NotificationInboxLogic.categoryLabel(item.category))
                if (item.eventCount > 1) add("${item.eventCount} updates")
                add(relativeTime(item.occurredAt, state.now))
                if (item.status == "resolved") add("Resolved")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.pending) Text("Saving…", style = MaterialTheme.typography.labelSmall, color = amb.info, modifier = Modifier.testTag("notification.${row.id}.pending"))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("notification.${row.id}.menu")) { Icon(Icons.Outlined.MoreVert, "Notification actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (item.accessible) DropdownMenuItem(
                    text = { Text(if (row.read) "Mark unread" else "Mark read") }, enabled = state.canChange,
                    onClick = { menu = false; actions.setRead(row, !row.read) }, modifier = Modifier.testTag("notification.${row.id}.toggleRead"),
                )
                DropdownMenuItem(
                    text = { Text(if (row.archived) "Move to inbox" else "Archive") }, enabled = state.canChange,
                    onClick = { menu = false; actions.setArchived(row, !row.archived) }, modifier = Modifier.testTag("notification.${row.id}.archive"),
                )
                DropdownMenuItem(text = { Text("Dismiss") }, enabled = state.canChange, onClick = { menu = false; actions.dismiss(row) }, modifier = Modifier.testTag("notification.${row.id}.dismiss"))
            }
        }
    }
}

/** "5 min ago", "3 h ago", "2 d ago", else the date. Unparseable input is shown as sent. */
internal fun relativeTime(iso: String, now: Instant): String {
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    val d = Duration.between(then, now)
    return when {
        d.isNegative || d.seconds < 60 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        d.toDays() < 7 -> "${d.toDays()} d ago"
        else -> iso.take(10)
    }
}

internal fun startedLabel(iso: String): String = runCatching { Instant.parse(iso).toString().take(10) }.getOrDefault(iso.take(10)).let { "on $it" }
