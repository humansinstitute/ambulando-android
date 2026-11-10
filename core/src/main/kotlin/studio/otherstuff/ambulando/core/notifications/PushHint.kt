package studio.otherstuff.ambulando.core.notifications

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.transport.isUUID
import java.net.URI
import java.time.Instant
import studio.otherstuff.ambulando.core.transport.NotificationItem

/** Untrusted routing hints, never a URL to fetch or a record to open directly. */
data class PushHint(val origin: String, val towerNpub: String?, val workspaceId: String, val notificationId: String, val changeSeq: Long) {
    fun matches(connection: ConnectionRecord, workspaceId: String): Boolean =
        this.workspaceId == workspaceId && origin == connection.origin &&
            (towerNpub == null || towerNpub == connection.towerNpub)

    companion object {
        fun parse(bytes: ByteArray): PushHint? = runCatching {
            require(bytes.size in 1..3993)
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            val fd = root["fd"]?.jsonObject ?: root
            fun string(key: String) = (fd[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require((fd["v"] as? JsonPrimitive)?.intOrNull == 1)
            val origin = string("origin") ?: error("origin")
            val uri = URI(origin)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty() in listOf("", "/"))
            val workspace = string("workspace_id") ?: error("workspace")
            val item = string("notification_id") ?: error("item")
            require(isUUID(workspace) && isUUID(item))
            val seq = (fd["change_seq"] as? JsonPrimitive)?.longOrNull ?: error("seq")
            require(seq > 0)
            PushHint(origin.trimEnd('/'), string("tower_service_npub"), workspace.lowercase(), item.lowercase(), seq)
        }.getOrNull()
    }
}

/** A cached row is insufficient: call only with the freshly fetched item for this hint. */
object PushValidation {
    fun presentable(hint: PushHint, item: NotificationItem?, now: Instant = Instant.now()): Boolean =
        item != null && item.id == hint.notificationId && item.workspaceId == hint.workspaceId &&
            item.changeSeq >= hint.changeSeq && item.unread &&
            (item.expiresAt == null || runCatching { Instant.parse(item.expiresAt).isAfter(now) }.getOrDefault(false))
}
