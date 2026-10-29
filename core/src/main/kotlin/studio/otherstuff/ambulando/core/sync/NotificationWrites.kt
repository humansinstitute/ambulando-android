package studio.otherstuff.ambulando.core.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.WriteAcknowledgement
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.WriteState
import studio.otherstuff.ambulando.core.store.clearNotificationOverlay
import studio.otherstuff.ambulando.core.store.discardWrite
import studio.otherstuff.ambulando.core.store.finishWrite
import studio.otherstuff.ambulando.core.store.notificationRow
import studio.otherstuff.ambulando.core.store.setNotificationOverlay
import studio.otherstuff.ambulando.core.store.setNotificationOverlayUpTo
import studio.otherstuff.ambulando.core.store.updateWritePayload
import studio.otherstuff.ambulando.core.store.writeItem
import studio.otherstuff.ambulando.core.store.workspaceWrites
import studio.otherstuff.ambulando.core.transport.NotificationBulkOp
import studio.otherstuff.ambulando.core.transport.NotificationPatch
import studio.otherstuff.ambulando.core.transport.NotificationWriteResult
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.bulkNotifications
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.newRequestId
import studio.otherstuff.ambulando.core.transport.patchNotification
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string

// Notification state writes ride the durable write queue (offline, retry with backoff, relaunch, per-item ordering).
//
// A write carries the `mutation_id` Tower replays and the `expected_row_version` the user was looking at, so activity
// that folded into an item after they looked can never be acknowledged by an old tap: Tower answers `state_conflict`,
// the sender adopts Tower's current item (unread again if new activity arrived) and does NOT re-apply the intent.
// A lost response is retried with the same `mutation_id`; "already acknowledged" is success.

/** What happened to a notification action that did not simply apply. */
data class NotificationNotice(val connectionId: String, val workspaceId: String, val itemId: String?, val kind: Kind) {
    enum class Kind {
        /** The item had changed since the user looked (new activity, another device): Tower's current item is shown, the tap was not applied. */
        CHANGED_ELSEWHERE,
        /** Tower refused the request as a different use of an id it had already seen; the item is reloaded. */
        REJECTED,
    }
}

/** The item version the user was actually looking at when they acted: taken from the rendered row, never from a later lookup. */
data class ObservedNotification(val rowVersion: Long, val changeSeq: Long)

/**
 * Records the user's intent: optimistic overlay first (the screen changes now), then the queued write.
 *
 * [observed] is the rendered row's `row_version` and `change_seq`. They are sent as `expected_row_version` and
 * `observed_change_seq`, so if activity folded into the item after the row was drawn (even if the local copy has already
 * caught up) Tower answers `state_conflict` and the older tap is never applied to activity the user has not seen.
 * Only a second tap on the *same* rendered snapshot while the first is still unsent may learn its base from the first
 * write's acknowledgement (see [performNotificationState]); a tap on a newer snapshot carries that snapshot's version.
 */
suspend fun WriteSender.submitNotificationState(
    connection: ConnectionRecord, workspace: WorkspaceRecord, itemId: String, patch: NotificationPatch, observed: ObservedNotification,
) {
    if (patch.isEmpty) return
    if (store.reader.notificationRow(connection.id, workspace.id, itemId) == null) return
    val open = store.reader.workspaceWrites(connection.id, workspace.id, itemId).filter { it.kind == WriteKind.NOTIFICATION_STATE }
    val last = open.lastOrNull()
    val sameSnapshot = last != null && flexibleLong(last.payloadObject["snapshot_row_version"]) == observed.rowVersion
    // A not-yet-sent, never-attempted write on the same rendered snapshot absorbs the new intent (latest tap wins per flag).
    // Anything already attempted keeps its payload: its mutation_id may be known to Tower and must not change meaning.
    val absorbing = last?.takeIf { sameSnapshot && it.state == WriteState.QUEUED && it.attempts == 0 && it.payloadObject.bool("ambiguous") != true }
    store.writer.setNotificationOverlay(connection.id, workspace.id, itemId, patch)
    if (absorbing != null) {
        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(absorbing.payloadObject.obj("patch") ?: JsonObject(emptyMap()))
        merged.putAll(patch.toJson())
        store.writer.updateWritePayload(absorbing.id, mapOf("patch" to JsonObject(merged)))
        kick(connection)
        return
    }
    val chained = last != null && sameSnapshot
    val payload = jsonObjectOf(
        "item_id" to itemId, "mutation_id" to newRequestId(), "patch" to patch.toJson(),
        "snapshot_row_version" to observed.rowVersion,
        // A write behind another one on the same snapshot learns its base from that one's acknowledgement, never from a guess.
        "expected_row_version" to if (chained) null else observed.rowVersion,
        "observed_change_seq" to if (chained) null else observed.changeSeq,
        "chained" to chained,
    )
    submit(WriteIntent(WriteKind.NOTIFICATION_STATE, itemId, payload), connection, workspace)
}

/**
 * "Mark all read" and similar: applies only to what the screen showed ([upToSeq]); anything newer stays as it is.
 * The overlay covers exactly the matching rows.
 */
suspend fun WriteSender.submitNotificationBulk(
    connection: ConnectionRecord, workspace: WorkspaceRecord, op: NotificationBulkOp, upToSeq: Long, category: String? = null,
) {
    val patch = when (op) {
        NotificationBulkOp.MARK_READ -> NotificationPatch(read = true)
        NotificationBulkOp.MARK_UNREAD -> NotificationPatch(read = false)
        NotificationBulkOp.DISMISS -> NotificationPatch(dismissed = true)
        NotificationBulkOp.ARCHIVE -> NotificationPatch(archived = true)
        NotificationBulkOp.UNARCHIVE -> NotificationPatch(archived = false)
    }
    val touched = store.writer.setNotificationOverlayUpTo(connection.id, workspace.id, upToSeq, patch, category, onlyUnread = op == NotificationBulkOp.MARK_READ)
    if (touched.isEmpty()) return
    val payload = jsonObjectOf("mutation_id" to newRequestId(), "op" to op.wire, "up_to_seq" to upToSeq, "category" to category, "item_ids" to touched)
    submit(WriteIntent(WriteKind.NOTIFICATION_BULK, null, payload), connection, workspace)
}

internal suspend fun WriteSender.performNotificationState(item: WriteItem, api: TowerAPI, attempts: Int): WriteOutcome {
    // Re-read: an earlier write of this item may have filled in the base version since the list was loaded.
    val p = (store.reader.writeItem(item.id) ?: item).payloadObject
    val itemId = item.targetId
    val mutation = p.string("mutation_id")
    val patchJson = p.obj("patch")
    val expected = flexibleLong(p["expected_row_version"])
    if (itemId == null || mutation == null || patchJson == null) return WriteOutcome.Permanent(null, null, "Missing notification change.")
    if (expected == null) return WriteOutcome.Permanent(null, "base_unknown", "This change depended on an earlier one that did not finish; it was dropped.")
    val patch = NotificationPatch(patchJson.bool("read"), patchJson.bool("dismissed"), patchJson.bool("archived"))
    val observed = flexibleLong(p["observed_change_seq"])
    return when (val r = api.patchNotification(item.workspaceId, item.appNpub, itemId, mutation, expected, patch, observed)) {
        is NotificationWriteResult.Applied -> {
            // The next write of this item is based on the version this one produced.
            for (next in store.reader.workspaceWrites(item.connectionId, item.workspaceId, itemId)) {
                if (next.id != item.id && next.kind == WriteKind.NOTIFICATION_STATE && next.payloadObject.bool("chained") == true &&
                    next.payloadObject["expected_row_version"].let { it == null || it is kotlinx.serialization.json.JsonNull }
                ) store.writer.updateWritePayload(
                    next.id, mapOf("expected_row_version" to JsonPrimitive(r.item.rowVersion), "observed_change_seq" to JsonPrimitive(r.item.changeSeq)),
                )
            }
            WriteOutcome.Done(WriteAcknowledgement(notifications = listOf(r.item)))
        }
        NotificationWriteResult.MutationReused -> {
            // Tower saw this id with a different request: a rejection, not a replayed success. Show the item as Tower has it.
            notificationNoticeFlow.tryEmit(NotificationNotice(item.connectionId, item.workspaceId, itemId, NotificationNotice.Kind.REJECTED))
            WriteOutcome.Done(WriteAcknowledgement(notificationOverlayClears = listOf(itemId)))
        }
        is NotificationWriteResult.StateConflict -> {
            // The item moved under the user's view. Later intents were based on the old view: drop them, adopt Tower's item.
            for (next in store.reader.workspaceWrites(item.connectionId, item.workspaceId, itemId)) {
                if (next.id != item.id && next.kind == WriteKind.NOTIFICATION_STATE) store.writer.discardWrite(next.id)
            }
            notificationNoticeFlow.tryEmit(NotificationNotice(item.connectionId, item.workspaceId, itemId, NotificationNotice.Kind.CHANGED_ELSEWHERE))
            WriteOutcome.Done(
                if (r.current != null) WriteAcknowledgement(notifications = listOf(r.current)) else WriteAcknowledgement(notificationOverlayClears = listOf(itemId)),
            )
        }
        is NotificationWriteResult.BulkApplied -> WriteOutcome.Done(WriteAcknowledgement())
        is NotificationWriteResult.Failed -> WriteSender.classify(
            r.status, jsonBody(JsonObject(mapOf("code" to JsonPrimitive(r.code ?: "")))), attempts, "change this notification",
        )
    }
}

internal suspend fun WriteSender.performNotificationBulk(item: WriteItem, api: TowerAPI, attempts: Int): WriteOutcome {
    val p = item.payloadObject
    val mutation = p.string("mutation_id")
    val op = NotificationBulkOp.from(p.string("op"))
    val upTo = flexibleLong(p["up_to_seq"])
    if (mutation == null || op == null || upTo == null) return WriteOutcome.Permanent(null, null, "Missing notification change.")
    return when (val r = api.bulkNotifications(item.workspaceId, item.appNpub, mutation, op, upTo, category = p.string("category"))) {
        is NotificationWriteResult.BulkApplied -> WriteOutcome.Done(WriteAcknowledgement(notificationOverlayCommits = bulkItemIds(item)))
        NotificationWriteResult.MutationReused -> {
            notificationNoticeFlow.tryEmit(NotificationNotice(item.connectionId, item.workspaceId, null, NotificationNotice.Kind.REJECTED))
            WriteOutcome.Done(WriteAcknowledgement(notificationOverlayClears = bulkItemIds(item)))
        }
        is NotificationWriteResult.Failed -> WriteSender.classify(
            r.status, jsonBody(JsonObject(mapOf("code" to JsonPrimitive(r.code ?: "")))), attempts, "change these notifications",
        )
        else -> WriteOutcome.Permanent(null, null, "Tower answered a bulk change unexpectedly.")
    }
}

/** After a notification write ended for good without Tower accepting it, the overlay must not linger. */
internal suspend fun WriteSender.dropNotificationWrite(item: WriteItem) {
    for (id in listOfNotNull(item.targetId) + bulkItemIds(item)) store.writer.clearNotificationOverlay(item.connectionId, item.workspaceId, id)
    store.writer.finishWrite(item.id)
}

private fun bulkItemIds(item: WriteItem): List<String> =
    (item.payloadObject["item_ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
