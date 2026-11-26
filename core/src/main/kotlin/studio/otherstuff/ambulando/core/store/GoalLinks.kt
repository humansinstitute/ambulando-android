package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.goals.GoalLink
import studio.otherstuff.ambulando.core.goals.GoalLinkType
import studio.otherstuff.ambulando.core.transport.TaskState

/**
 * A goal link as this device can currently show it. The title and location come from the source record in the local
 * store, never from the goal, so a rename or move is reflected and nothing is copied. A source this identity cannot
 * read (missing, access removed, not synced yet) is [available] = false with no content; the link stays removable.
 */
data class ResolvedGoalLink(
    val link: GoalLink,
    val title: String?,
    /** "Channel · Scope" of the source record, when known. */
    val location: String?,
    /** A short state such as "In progress" or "Document". */
    val detail: String?,
    val channelId: String?,
    val available: Boolean,
)

suspend fun StoreReader.resolveGoalLinks(workspaceId: String, links: List<GoalLink>): List<ResolvedGoalLink> {
    if (links.isEmpty()) return emptyList()
    val scopeNames = scopes(workspaceId).associate { it.id to it.name }
    suspend fun place(channelId: String?, scopeId: String?): String? {
        val channel = channelId?.let { channel(workspaceId, it) }
        val scope = (scopeId ?: channel?.scopeId)?.let { scopeNames[it] }
        return listOfNotNull(channel?.name, scope).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
    return links.map { link ->
        when (link.type) {
            GoalLinkType.TASK -> task(workspaceId, link.recordId).let { t ->
                if (t == null || t.accessRemoved) unavailable(link)
                else ResolvedGoalLink(link, t.title, place(t.channelId, t.scopeId), taskState(t.state), t.channelId, true)
            }
            GoalLinkType.DOC -> doc(workspaceId, link.recordId).let { d ->
                if (d == null || d.accessRemoved || d.archived) unavailable(link)
                else ResolvedGoalLink(link, d.title, place(d.channelId, d.scopeId), "Document", d.channelId, true)
            }
            GoalLinkType.FILE -> file(workspaceId, link.recordId).let { f ->
                if (f == null || f.accessRemoved || f.archived) unavailable(link)
                else ResolvedGoalLink(link, f.displayName, place(f.channelId, f.scopeId), "File", f.channelId, true)
            }
            GoalLinkType.CHAT -> thread(workspaceId, link.recordId).let { t ->
                if (t == null || t.accessRemoved || t.deleted || t.archived) unavailable(link)
                else {
                    val title = t.title?.takeIf { it.isNotBlank() }
                        ?: t.sourceMessageId?.let { message(workspaceId, it) }?.body?.lineSequence()?.firstOrNull()?.take(80)?.takeIf { it.isNotBlank() }
                        ?: "Conversation"
                    ResolvedGoalLink(link, title, place(t.channelId, null), "Chat", t.channelId, true)
                }
            }
        }
    }
}

private fun unavailable(link: GoalLink) = ResolvedGoalLink(link, null, null, null, link.channelId, false)

private fun taskState(state: String): String =
    TaskState.entries.firstOrNull { it.wire == state }?.label ?: state.replace('_', ' ').replaceFirstChar { it.uppercase() }
