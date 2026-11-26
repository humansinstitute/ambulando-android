package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord

/** Home's Latest: the most recently active conversations the viewer can still open, one card each, newest first. */
object LatestChats {
    const val LIMIT = 5

    /** Channels of [workspaceId] whose channel and scope are still accessible; none once the workspace itself is not. */
    fun channelIds(workspaceId: String, workspaceRemoved: Boolean, channels: List<ChannelRecord>, scopes: List<ScopeRecord>): Set<String> {
        if (workspaceRemoved) return emptySet()
        val open = scopes.filter { it.workspaceId == workspaceId && !it.accessRemoved }.map { it.id }.toSet()
        return channels.filter { it.workspaceId == workspaceId && !it.accessRemoved && it.scopeId in open }.map { it.id }.toSet()
    }

    fun pick(rows: List<ConversationRow>, limit: Int = LIMIT): List<ConversationRow> =
        rows.sortedWith(compareByDescending<ConversationRow> { it.latestAt }.thenBy { it.id })
            .distinctBy { it.threadId ?: "channel:${it.channelId}:${it.id}" }
            .take(limit)

    /** The cards for Home, read from the same conversation index as the Chat list (deleted and removed records excluded). */
    suspend fun load(reader: StoreReader, workspaceId: String, channelIds: Set<String>, viewer: String): List<ConversationRow> =
        pick(reader.conversations(workspaceId, channelIds, viewer))
}
