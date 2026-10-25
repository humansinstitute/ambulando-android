package studio.otherstuff.ambulando.core.context

/**
 * Reads bounded by the shared [WorkContext]. A scope context matches records whose own scope is the scope or whose
 * channel belongs to it (older records may carry only a channel); a channel context matches that channel only. Home
 * spans the workspace. Every query stays inside one workspace. The store's task, document and file readers append
 * this condition to their `workspace_id = ?` filter.
 */
object ContextQueries {
    data class Filter(val sql: String, val bindings: List<Any?>)

    /** SQL condition on a table with `scope_id` and `channel_id` columns, and its bindings. */
    fun contextFilter(context: WorkContext, workspaceId: String): Filter {
        val scopeId = context.scopeId ?: return Filter("1 = 1", emptyList())
        context.channelId?.let { return Filter("channel_id = ?", listOf(it)) }
        return Filter("(scope_id = ? OR channel_id IN (SELECT id FROM channels WHERE workspace_id = ? AND scope_id = ?))", listOf(scopeId, workspaceId, scopeId))
    }
}

/** A conversation inside a channel, for the channel's thread list: what it is about, how much was said and when. */
data class ThreadSummary(
    val id: String,
    val channelId: String,
    /** Tower's thread title (derived from the source message when not set), or null when only messages are stored. */
    val title: String?,
    /** The first stored message (the root), as shown in the channel. */
    val rootBody: String,
    val rootSender: String?,
    /** The newest message and its sender (activity cue). */
    val latestBody: String,
    val latestSender: String?,
    val replyCount: Int,
    val lastActivityAt: String,
    val archived: Boolean,
)
