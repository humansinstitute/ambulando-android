package studio.otherstuff.ambulando.core.notifications

import studio.otherstuff.ambulando.core.store.NotificationRow
import studio.otherstuff.ambulando.core.store.NotificationSyncState
import studio.otherstuff.ambulando.core.transport.NotificationItem
import studio.otherstuff.ambulando.core.transport.NotificationView

// Pure decisions behind the notification screens (no Android, no network), so they are unit-tested on the JVM.

/** Where tapping a notification goes. The notification only names ids; the record is opened through the normal screens. */
sealed class NotificationDestination {
    data class Thread(val channelId: String?, val threadId: String) : NotificationDestination()
    data class Channel(val channelId: String) : NotificationDestination()
    data class Task(val taskId: String) : NotificationDestination()
    data class Doc(val docId: String) : NotificationDestination()
    /** A kind this version cannot open (or a record of another app): the generic detail card is shown instead. */
    data object Detail : NotificationDestination()
    /** Tower says the record can no longer be read: nothing is opened and no text is shown. */
    data object Unavailable : NotificationDestination()
}

object NotificationRouting {
    /**
     * Maps `action.target` to a local destination. Unknown kinds and targets missing their ids degrade to [Detail];
     * an inaccessible or non-active item never opens anything.
     */
    fun destination(item: NotificationItem): NotificationDestination {
        if (!item.accessible || item.status == "revoked" || item.status == "withdrawn") return NotificationDestination.Unavailable
        val t = item.target ?: return NotificationDestination.Detail
        return when (t.kind) {
            "thread" -> t.threadId?.let { NotificationDestination.Thread(t.channelId, it) } ?: t.channelId?.let { NotificationDestination.Channel(it) } ?: NotificationDestination.Detail
            "channel" -> t.channelId?.let { NotificationDestination.Channel(it) } ?: NotificationDestination.Detail
            "task" -> t.taskId?.let { NotificationDestination.Task(it) } ?: NotificationDestination.Detail
            "doc", "document" -> t.docId?.let { NotificationDestination.Doc(it) } ?: NotificationDestination.Detail
            else -> NotificationDestination.Detail
        }
    }
}

/** What the inbox screen shows when there is nothing to list, and why. Never a blank screen without a reason. */
sealed class NotificationEmptyReason {
    /** Tower has no notification routes: say so rather than showing an empty inbox. */
    data object BackendNotSupported : NotificationEmptyReason()
    /** The workspace no longer lets this identity read its inbox; everything stored was removed. */
    data object AccessLost : NotificationEmptyReason()
    /** First load has not finished yet. */
    data object Loading : NotificationEmptyReason()
    /** The inbox is empty from its start date onwards; nothing before that is imported. */
    data class InboxStarted(val since: String?) : NotificationEmptyReason()
    /** Rows exist but the chosen filter hides them. */
    data object FilteredOut : NotificationEmptyReason()
}

data class NotificationFilter(val view: NotificationView = NotificationView.INBOX, val category: String? = null) {
    val isDefault: Boolean get() = view == NotificationView.INBOX && category == null
}

object NotificationInboxLogic {
    /** Why the list is empty, or null when there are rows to show. */
    fun emptyReason(sync: NotificationSyncState?, shown: List<NotificationRow>, totalHeld: Int, filter: NotificationFilter, syncing: Boolean): NotificationEmptyReason? {
        if (shown.isNotEmpty()) return null
        return when {
            sync?.state == NotificationSyncState.Phase.UNSUPPORTED -> NotificationEmptyReason.BackendNotSupported
            sync?.state == NotificationSyncState.Phase.ACCESS_LOST -> NotificationEmptyReason.AccessLost
            sync == null || sync.state == NotificationSyncState.Phase.UNSYNCED -> if (syncing || sync == null) NotificationEmptyReason.Loading else NotificationEmptyReason.InboxStarted(sync.inboxStartedAt)
            totalHeld > 0 || !filter.isDefault -> NotificationEmptyReason.FilteredOut
            else -> NotificationEmptyReason.InboxStarted(sync.inboxStartedAt)
        }
    }

    /** Categories to offer as filters: the ones present, in the order Tower ranks them (most urgent first), then any others. */
    fun filterCategories(present: Collection<String>): List<String> {
        val ranked = listOf("security", "approval", "dm", "mention", "task_assignment", "comment_tag", "chat_thread")
        return ranked.filter { it in present } + present.filter { it !in ranked }.distinct().sorted()
    }

    /** Human label for a category when the registry has not been loaded (never taken from a notification payload). */
    fun categoryLabel(category: String): String = when (category) {
        "chat_thread" -> "Thread activity"
        "mention" -> "Mentions"
        "dm" -> "Direct messages"
        "comment_tag" -> "Comment tags"
        "task_assignment" -> "Task assignments"
        "approval" -> "Approvals"
        "security" -> "Security"
        else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Count shown on the bell: capped like Tower's counters so the badge stays short. */
    fun badge(unread: Int, cap: Int = 99): String? = when { unread <= 0 -> null; unread > cap -> "$cap+"; else -> unread.toString() }
}
