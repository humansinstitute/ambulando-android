package studio.otherstuff.ambulando.core.support

import studio.otherstuff.ambulando.core.transport.NotificationItem

/** Synthetic `org.wingman.notification.item` v1 rows. Every id is a placeholder UUID. */
object NotificationFixtures {
    const val RECIPIENT = "00000000-0000-4000-8000-0000000000aa"
    const val THREAD = "00000000-0000-4000-8000-000000000d01"

    fun id(n: Int): String = "00000000-0000-4000-8000-%012d".format(900 + n)

    fun map(
        n: Int, seq: Long, version: Long = 1, workspace: String = Fixtures.WORKSPACE_ID, category: String = "mention", read: Boolean = false,
        dismissed: Boolean = false, archived: Boolean = false, status: String = "active", access: String = "ok", occurredAt: String = "2026-10-26T10:%02d:00Z".format(n % 60),
        title: String = "Morgan mentioned you", kind: String = "thread", activity: Long? = 10,
    ): Map<String, Any?> = linkedMapOf(
        "schema" to "org.wingman.notification.item", "version" to 1, "id" to id(n), "workspace_id" to workspace,
        "recipient_actor_id" to RECIPIENT, "change_seq" to seq, "row_version" to version,
        "source" to mapOf("app_id" to "flightdeck.core", "label" to "Flight Deck", "kind" to "tower_rule"),
        "category" to category, "reasons" to listOf(category), "severity" to "normal", "status" to status, "origin" to "live",
        "group" to mapOf("key" to "thread:$THREAD", "event_count" to 1),
        "actor" to mapOf("actor_id" to RECIPIENT, "display_name" to "Morgan", "kind" to "agent"),
        "subject" to mapOf("kind" to kind, "thread_id" to THREAD, "channel_id" to Fixtures.CHANNEL_1, "activity_version" to activity),
        "presentation" to mapOf("title" to title, "body" to "Notifications in Ambulando", "icon" to category),
        "action" to mapOf("type" to "open", "target" to mapOf("kind" to kind, "channel_id" to Fixtures.CHANNEL_1, "thread_id" to THREAD)),
        "occurred_at" to occurredAt, "updated_at" to occurredAt,
        "state" to mapOf("read" to read, "read_at" to null, "read_reason" to if (read) "user" else null, "dismissed" to dismissed, "archived" to archived),
        "access" to access,
    )

    fun item(
        n: Int, seq: Long, version: Long = 1, category: String = "mention", read: Boolean = false, dismissed: Boolean = false,
        archived: Boolean = false, status: String = "active", access: String = "ok", workspace: String = Fixtures.WORKSPACE_ID,
    ): NotificationItem = NotificationItem.parse(
        Fixtures.obj(map(n, seq, version, workspace, category, read, dismissed, archived, status, access)),
    )

    fun tombstone(n: Int, seq: Long, reason: String = "access_lost"): Map<String, Any?> =
        mapOf(
            "schema" to "org.wingman.notification.tombstone", "version" to 1, "id" to id(n), "workspace_id" to Fixtures.WORKSPACE_ID,
            "change_seq" to seq, "change_type" to "remove", "removal_reason" to reason,
        )
}
