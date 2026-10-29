package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Notification inbox routes: `/api/v4/flightdeck-pg/workspaces/{w}/notifications/...` (NIP-98, membership required).
// Routes follow the shared design; the Tower contract (`notifications-v1`) is authoritative when they differ.

/** The inbox views a list can ask for. */
enum class NotificationView(val wire: String) { INBOX("inbox"), UNREAD("unread"), ARCHIVED("archived"), ALL("all") }

/** The flags a state write can set. A null field is left alone. */
data class NotificationPatch(val read: Boolean? = null, val dismissed: Boolean? = null, val archived: Boolean? = null) {
    val isEmpty: Boolean get() = read == null && dismissed == null && archived == null

    fun toJson(): JsonObject = jsonObjectOf(*listOfNotNull(
        read?.let { "read" to it }, dismissed?.let { "dismissed" to it }, archived?.let { "archived" to it },
    ).toTypedArray())
}

/** Bulk operations Tower understands; each applies only up to the observed sequence boundary. */
enum class NotificationBulkOp(val wire: String) {
    MARK_READ("mark_read"), MARK_UNREAD("mark_unread"), DISMISS("dismiss"), ARCHIVE("archive"), UNARCHIVE("unarchive");

    companion object { fun from(wire: String?): NotificationBulkOp? = entries.firstOrNull { it.wire == wire } }
}

private fun TowerAPI.notificationsBase(workspaceId: String): String = TowerAPI.base(workspaceId) + "/notifications"

/**
 * This app's `client_app_id` (wire v1.1). It is a presentation filter, never access authority: Tower returns the items
 * visible to this app (audience and the caller's inbox rules for it), and every count and bulk uses the same predicate.
 */
const val NOTIFICATION_CLIENT_APP_ID = "ambulando"

/**
 * One newest-first page. The first page's `head_seq` is read in the same snapshot as the page, so it is the safe start
 * for replaying `changes` (cold start, see `NotificationSync`).
 */
suspend fun TowerAPI.notificationItems(
    workspaceId: String, appNpub: String, view: NotificationView = NotificationView.INBOX, cursor: String? = null,
    limit: Int = 50, category: String? = null, sourceApp: String? = null,
): NotificationItemsPage {
    val query = mutableListOf("view" to view.wire, "limit" to limit.coerceIn(1, 100).toString(), "client_app_id" to NOTIFICATION_CLIENT_APP_ID)
    cursor?.let { query.add("cursor" to it) }
    category?.let { query.add("category" to it) }
    sourceApp?.let { query.add("source_app_id" to it) }
    val o = obj(client.request(path = notificationsBase(workspaceId) + "/items", query = query, appNpub = appNpub), "notification items")
    val rows = o.objects("items") ?: throw TowerError.Malformed("notification items")
    // An item for another workspace is refused outright: nothing may be filed under a workspace it does not belong to.
    val items = rows.map { NotificationItem.parse(it) }
    if (items.any { it.workspaceId != workspaceId }) throw TowerError.Malformed("notification items")
    return NotificationItemsPage(
        items, o.string("next_cursor")?.takeIf { it.isNotEmpty() }, flexibleLong(o["head_seq"]) ?: 0,
        o.obj("counts")?.let { flexibleInt(it["unread_total"]) }, o.string("inbox_started_at"), flexibleLong(o["rules_revision"]),
    )
}

/**
 * Ascending delta after [afterSeq], filtered for this app. [rulesRevision] is the value current when the snapshot was taken:
 * if the person's rules changed since, Tower answers 410 `reset_required` (`reason: rules_changed`), which surfaces as
 * [TowerError.Http] (status 410; every reason `rules_changed`, `tombstone_floor`, `ahead` means start over) for the caller.
 */
suspend fun TowerAPI.notificationChanges(
    workspaceId: String, appNpub: String, afterSeq: Long, limit: Int = 200, rulesRevision: Long? = null,
): NotificationChangesPage {
    val query = mutableListOf("after_seq" to afterSeq.toString(), "limit" to limit.coerceIn(1, 200).toString(), "client_app_id" to NOTIFICATION_CLIENT_APP_ID)
    rulesRevision?.let { query.add("rules_revision" to it.toString()) }
    val o = obj(client.request(path = notificationsBase(workspaceId) + "/changes", query = query, appNpub = appNpub), "notification changes")
    val rows = o.objects("changes") ?: throw TowerError.Malformed("notification changes")
    val changes = rows.map { NotificationChange.parse(it) }
    for (c in changes) {
        if (c is NotificationChange.Upsert && c.item.workspaceId != workspaceId) throw TowerError.Malformed("notification changes")
    }
    val through = flexibleLong(o["through_seq"]) ?: throw TowerError.Malformed("notification changes")
    val hasMore = o.bool("has_more") ?: false
    // A page that claims more but did not advance would loop forever. A last page may legitimately advance past its rows
    // (everything was filtered for this app): `through_seq` is then the snapshot head, and `has_more` is false.
    if (hasMore && through <= afterSeq) throw TowerError.Malformed("notification changes")
    return NotificationChangesPage(changes, through, flexibleLong(o["head_seq"]) ?: through, hasMore, flexibleLong(o["rules_revision"]))
}

suspend fun TowerAPI.notificationCounts(workspaceId: String, appNpub: String, installationId: String? = null): NotificationCounts {
    val query = listOfNotNull(installationId?.let { "installation_id" to TowerAPI.uuid(it) }, "client_app_id" to NOTIFICATION_CLIENT_APP_ID)
    return NotificationCounts.parse(obj(client.request(path = notificationsBase(workspaceId) + "/counts", query = query, appNpub = appNpub), "notification counts"))
}

/** Tower's machine code is `code`; `error` is the message. Older replies put the code in `error`, so both are checked. */
private fun errorCodes(body: ByteArray): Set<String> =
    parseJsonObjectOrNull(body)?.let { listOfNotNull(it.string("code"), it.string("error")).toSet() } ?: emptySet()

/** Compare-and-set write of one item's flags. The exact bytes are signed; a retry reuses the same [mutationId]. */
suspend fun TowerAPI.patchNotification(
    workspaceId: String, appNpub: String, itemId: String, mutationId: String, expectedRowVersion: Long, patch: NotificationPatch,
    observedChangeSeq: Long? = null,
): NotificationWriteResult {
    val fields = linkedMapOf<String, Any?>("mutation_id" to mutationId, "expected_row_version" to expectedRowVersion, "patch" to patch.toJson())
    // The sequence the user actually saw: Tower refuses (409) if the item moved on since.
    observedChangeSeq?.let { fields["observed_change_seq"] = it }
    val body = jsonBody(JsonObject(fields.mapValues { toJsonElement(it.value) }))
    val path = notificationsBase(workspaceId) + "/items/" + TowerAPI.uuid(itemId)
    val reply = client.exchange("PATCH", path, appNpub, body)
    return interpretWrite(reply) { o ->
        val item = o.obj("item")?.let { NotificationItem.parse(it) } ?: throw TowerError.Malformed("notification item")
        if (item.workspaceId != workspaceId || item.id != itemId.lowercase()) throw TowerError.Malformed("notification item")
        NotificationWriteResult.Applied(item)
    }
}

/**
 * Bulk write bounded by an observed sequence: only items with `change_seq <= upToSeq` change, so anything that arrived
 * after the user looked stays as it was.
 */
suspend fun TowerAPI.bulkNotifications(
    workspaceId: String, appNpub: String, mutationId: String, op: NotificationBulkOp, upToSeq: Long?, ids: List<String>? = null,
    view: NotificationView? = null, category: String? = null,
): NotificationWriteResult {
    val selector = if (ids != null) {
        jsonObjectOf("ids" to JsonArray(ids.take(200).map { JsonPrimitive(TowerAPI.uuid(it)) }), "client_app_id" to NOTIFICATION_CLIENT_APP_ID)
    } else {
        requireNotNull(upToSeq) { "A bulk selector needs ids or an observed sequence." }
        jsonObjectOf("up_to_seq" to upToSeq, "view" to view?.wire, "category" to category, "client_app_id" to NOTIFICATION_CLIENT_APP_ID).let { s -> JsonObject(s.filterValues { it !is kotlinx.serialization.json.JsonNull }) }
    }
    val body = jsonBody(jsonObjectOf("mutation_id" to mutationId, "op" to op.wire, "selector" to selector))
    val reply = client.exchange("POST", notificationsBase(workspaceId) + "/bulk", appNpub, body)
    return interpretWrite(reply) { o ->
        NotificationWriteResult.BulkApplied(flexibleInt(o["affected"]) ?: 0, flexibleLong(o["through_seq"]))
    }
}

private inline fun interpretWrite(reply: Exchange, applied: (JsonObject) -> NotificationWriteResult): NotificationWriteResult {
    val codes = errorCodes(reply.body)
    val code = parseJsonObjectOrNull(reply.body)?.let { it.string("code") ?: it.string("error") }
    return when {
        reply.status in 200..299 -> applied(parseJsonObject(reply.body, "notification write"))
        reply.status == 409 && "mutation_id_reused" in codes -> NotificationWriteResult.MutationReused
        reply.status == 409 -> NotificationWriteResult.StateConflict(
            parseJsonObjectOrNull(reply.body)?.obj("item")?.let { runCatching { NotificationItem.parse(it) }.getOrNull() },
        )
        else -> NotificationWriteResult.Failed(reply.status, code)
    }
}

/** One item by id: Tower's current item (`access` recomputed, so it may be `unavailable`), or a content-free removal. */
suspend fun TowerAPI.notificationItem(workspaceId: String, appNpub: String, itemId: String): NotificationChange {
    val path = notificationsBase(workspaceId) + "/items/" + TowerAPI.uuid(itemId)
    val o = obj(client.request(path = path, appNpub = appNpub), "notification item")
    val change = NotificationChange.parse(o.obj("item") ?: throw TowerError.Malformed("notification item"))
    if (change is NotificationChange.Upsert && (change.item.workspaceId != workspaceId || change.item.id != itemId.lowercase())) throw TowerError.Malformed("notification item")
    return change
}

/** The category registry visible to the caller (core Flight Deck categories plus active workspace sources). */
suspend fun TowerAPI.notificationCategories(workspaceId: String, appNpub: String): List<NotificationCategoryInfo> =
    parseNotificationCategories(obj(client.request(path = notificationsBase(workspaceId) + "/categories", appNpub = appNpub), "notification categories"))

/** The caller's rules with their `row_version`s and the `rules_revision` they were read at (the base for compare-and-set). */
suspend fun TowerAPI.notificationRules(workspaceId: String, appNpub: String): NotificationRuleSet =
    parseNotificationRuleSet(obj(client.request(path = notificationsBase(workspaceId) + "/rules", appNpub = appNpub), "notification rules"))

/**
 * Rules write, all-or-nothing, per-key compare-and-set (no last-writer-wins). Every entry carries the `row_version` of its
 * exact key from the last read (0 when that key was absent). Replay-safe through [mutationId]; after a conflict the caller
 * adopts Tower's current rules and sends a NEW mutation id.
 */
suspend fun TowerAPI.putNotificationRules(workspaceId: String, appNpub: String, mutationId: String, rules: List<NotificationRuleWrite>): NotificationRulesResult {
    require(rules.size in 1..50) { "A rules update carries 1 to 50 rules." }
    val list = rules.map {
        jsonObjectOf(
            "source_app_id" to it.sourceAppId, "category" to it.category, "client_app_id" to it.clientAppId, "channel" to it.channel,
            "mode" to it.mode, "preview" to it.preview, "expected_row_version" to it.expectedRowVersion,
        ).let { o -> JsonObject(o.filterValues { v -> v !is kotlinx.serialization.json.JsonNull }) }
    }
    val body = jsonBody(jsonObjectOf("mutation_id" to mutationId, "rules" to JsonArray(list)))
    val reply = client.exchange("PUT", notificationsBase(workspaceId) + "/rules", appNpub, body)
    val codes = errorCodes(reply.body)
    return when {
        reply.status in 200..299 -> NotificationRulesResult.Saved(parseNotificationRuleSet(parseJsonObject(reply.body, "notification rules")))
        reply.status == 409 && "mutation_id_reused" in codes -> NotificationRulesResult.MutationReused
        reply.status == 409 && "rules_conflict" in codes -> {
            val o = parseJsonObject(reply.body, "notification rules")
            NotificationRulesResult.Conflict(parseNotificationRuleSet(o), o.objects("conflicts")?.size ?: 0)
        }
        else -> NotificationRulesResult.Failed(reply.status, parseJsonObjectOrNull(reply.body)?.let { it.string("code") ?: it.string("error") })
    }
}
