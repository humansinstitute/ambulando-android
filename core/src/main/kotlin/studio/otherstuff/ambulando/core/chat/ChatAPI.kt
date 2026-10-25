package studio.otherstuff.ambulando.core.chat

import studio.otherstuff.ambulando.core.transport.ListResult
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ThreadRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.isUUID
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.strings

// Chat routes. Paths are built only from validated UUIDs.

/** One page of `GET /workspaces/:w/channels/:c/messages` (order `created_at ASC, id ASC`, opaque forward cursor). */
data class MessagePage(val messages: List<MessageRecord>, val nextCursor: String?)

/** Tower `parseLimit` caps at 200. */
const val MESSAGE_PAGE_LIMIT = 200

suspend fun TowerAPI.messagesPage(
    workspaceId: String, appNpub: String, channelId: String, threadId: String? = null, cursor: String?, limit: Int = MESSAGE_PAGE_LIMIT,
): MessagePage {
    val query = mutableListOf("limit" to limit.toString())
    if (threadId != null) query.add("thread_id" to TowerAPI.uuid(threadId))
    if (cursor != null) query.add("cursor" to cursor)
    val data = client.request(path = TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/messages", query = query, appNpub = appNpub)
    val o = parseJsonObject(data, "messages")
    val rows = o.objects("messages") ?: throw TowerError.Malformed("messages")
    val messages = rows.map { MessageRecord.parse(it) }
    // A page that does not belong to the requested channel is never materialised.
    if (!messages.all { it.workspaceId == workspaceId && it.channelId == channelId && (threadId == null || it.threadId == threadId) }) {
        throw TowerError.Malformed("messages")
    }
    val next = o.string("next_cursor")
    return MessagePage(messages, next?.takeIf { it.isNotEmpty() })
}

/**
 * Thread metadata for a channel. Tower returns one page (≤ 200) with `next_cursor: null`, so a full page is reported
 * as possibly incomplete and is never used to remove local threads.
 */
suspend fun TowerAPI.threads(workspaceId: String, appNpub: String, channelId: String): ListResult<ThreadRecord> {
    val data = client.request(
        path = TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/threads",
        query = listOf("limit" to "200", "include_archived" to "true"), appNpub = appNpub,
    )
    val rows = parseJsonObject(data, "threads").objects("threads") ?: throw TowerError.Malformed("threads")
    val threads = rows.map { ThreadRecord.parse(it) }
    return ListResult(threads.filter { it.workspaceId == workspaceId && it.channelId == channelId }, rows.size < 200)
}

suspend fun TowerAPI.thread(workspaceId: String, appNpub: String, threadId: String): ThreadRecord {
    val data = client.request(path = TowerAPI.base(workspaceId) + "/threads/" + TowerAPI.uuid(threadId), appNpub = appNpub)
    val row = parseJsonObject(data, "thread").obj("thread") ?: throw TowerError.Malformed("thread")
    return ThreadRecord.parse(row)
}

/** The workspace directory. Rows that are not valid members are skipped. */
suspend fun TowerAPI.members(workspaceId: String, appNpub: String): List<MemberRecord> {
    val data = client.request(path = TowerAPI.base(workspaceId) + "/members", appNpub = appNpub)
    val rows = parseJsonObject(data, "members").objects("members") ?: throw TowerError.Malformed("members")
    return rows.mapNotNull { runCatching { MemberRecord.parse(workspaceId, it) }.getOrNull() }
}

/** The actor's own scope order (`GET /me/scope-order`); scopes not listed keep their default order after these. */
suspend fun TowerAPI.scopeOrder(workspaceId: String, appNpub: String): List<String> {
    val data = client.request(path = TowerAPI.base(workspaceId) + "/me/scope-order", appNpub = appNpub)
    val order = parseJsonObject(data, "scope order").obj("scope_order") ?: throw TowerError.Malformed("scope order")
    return (order.strings("scope_ids") ?: emptyList()).filter { isUUID(it) }.map { it.lowercase() }
}
