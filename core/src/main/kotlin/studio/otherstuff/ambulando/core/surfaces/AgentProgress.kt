package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.string
import java.time.Instant

/** Only producer-published, turn-bound commentary is retained. Never interpret arbitrary metadata as reasoning. */
data class AgentCommentary(val sequence: Long, val body: String, val createdAt: String, val raw: JsonObject)

val AgentActivityRecord.turnId: String? get() = raw.string("turn_id")?.takeIf { it.isNotBlank() }
val AgentActivityRecord.triggerMessageId: String? get() = raw.string("trigger_message_id")?.takeIf { it.isNotBlank() }
val AgentActivityRecord.historyBefore: Long? get() = flexibleLong(raw["commentary_next_before_sequence"])
val AgentActivityRecord.commentary: List<AgentCommentary> get() =
    (raw["commentary_history"] as? JsonArray).orEmpty().mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        val sequence = flexibleLong(row["sequence"]) ?: return@mapNotNull null
        val body = (row.string("body") ?: row.string("summary"))?.trim().orEmpty()
        if (turnId == null || row.string("turn_id") != turnId || row.string("activity_id") != activityId || sequence < 0 ||
            body.isEmpty() || (row.string("visibility") ?: "user_visible") != "user_visible" ||
            (row.string("state") ?: "working") != "working") return@mapNotNull null
        AgentCommentary(sequence, body, row.string("created_at").orEmpty(), row)
    }.distinctBy { it.sequence }.sortedBy { it.sequence }

private fun instant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Time is calibrated from an authenticated snapshot; retained history alone never proves a live lease. */
fun AgentActivityRecord.progressStatus(localNow: Long): String {
    if (isTerminal) return when (state) { "failed" -> "Failed"; "cancelled" -> "Cancelled"; else -> "Completed" }
    val server = instant(raw.string("android_server_time"))
    val received = flexibleLong(raw["android_received_at"])
    val now = if (server != null && received != null) server + (localNow - received).coerceAtLeast(0) else localNow
    val deadline = instant(raw.string("lease_expires_at") ?: raw.string("expires_at"))
    if (!raw.string("expired_at").isNullOrBlank() || deadline == null || now >= deadline + 15_000) return "Stopped responding"
    return when (state) {
        "queued" -> "Queued" + (queuePosition?.let { " · position $it" } ?: "")
        "accepted" -> "Message received"
        "waiting" -> "Waiting for you"
        "working" -> "Working"
        else -> label ?: state.replaceFirstChar { it.uppercase() }
    }
}

/** Terminal state and independently ordered lease updates survive replay and older history requests. */
fun mergeAgentProgress(current: AgentActivityRecord?, incoming: AgentActivityRecord): AgentActivityRecord {
    if (current == null) return incoming
    if (current.workspaceId != incoming.workspaceId || current.scopeId != incoming.scopeId || current.channelId != incoming.channelId || current.threadId != incoming.threadId ||
        current.activityId != incoming.activityId || current.turnId != incoming.turnId || current.agentNpub != incoming.agentNpub ||
        current.triggerMessageId != incoming.triggerMessageId) return current
    val base = when {
        current.isTerminal != incoming.isTerminal -> if (incoming.isTerminal) incoming else current
        incoming.sequence > current.sequence -> incoming
        else -> current
    }
    fun epoch(row: AgentActivityRecord) = flexibleLong(row.raw["lease_epoch"]) ?: 1L
    val order = compareValuesBy(incoming, current, { epoch(it) }, { flexibleLong(it.raw["lease_sequence"]) ?: -1L }, { it.sequence })
    val lease = if (base.isTerminal) base else if (order > 0) incoming else current
    val merged = (current.raw + base.raw).toMutableMap()
    listOf("lease_expires_at", "expires_at", "lease_epoch", "lease_sequence", "last_heartbeat_at", "lease_health").forEach { key ->
        lease.raw[key]?.let { merged[key] = it }
    }
    val expired = listOf(current, incoming).filter { epoch(it) == epoch(lease) }.mapNotNull { it.raw.string("expired_at")?.takeIf(String::isNotBlank) }.minOrNull()
    merged["expired_at"] = expired?.let(::JsonPrimitive) ?: JsonNull
    val knownCursor = (current.raw.string("android_recovered_cursor") ?: current.raw.string("commentary_cursor"))?.toLongOrNull() ?: 0L
    val recoveredCursor = incoming.raw.string("android_recovered_cursor")?.toLongOrNull() ?: 0L
    merged["android_recovered_cursor"] = JsonPrimitive(maxOf(knownCursor, recoveredCursor).toString())
    current.raw["created_at"]?.let { merged["created_at"] = it }
    val history = (current.commentary + incoming.commentary).distinctBy { it.sequence }.sortedBy { it.sequence }
    merged["commentary_history"] = JsonArray(history.map { it.raw })
    if ("commentary_next_before_sequence" in incoming.raw) {
        val old = current.raw["commentary_next_before_sequence"]
        val next = incoming.raw["commentary_next_before_sequence"]
        merged["commentary_next_before_sequence"] = if (old == JsonNull || next == JsonNull) JsonNull
            else listOfNotNull(current.historyBefore, incoming.historyBefore).minOrNull()?.let(::JsonPrimitive) ?: JsonNull
    }
    if ((flexibleLong(incoming.raw["android_received_at"]) ?: 0) > (flexibleLong(current.raw["android_received_at"]) ?: 0)) {
        listOf("android_server_time", "android_received_at").forEach { key -> incoming.raw[key]?.let { merged[key] = it } }
    }
    return AgentActivityRecord.parse(JsonObject(merged))
}

/** Queue never displaces the active turn, and an old working snapshot cannot outlive a newer completed turn. */
fun currentAgentProgress(rows: List<AgentActivityRecord>, now: Long): Set<String> = rows.groupBy {
    listOf(it.workspaceId, it.channelId, it.threadId ?: it.triggerMessageId, it.agentNpub)
}.values.flatMap { runs ->
    val ordered = runs.sortedWith(compareByDescending<AgentActivityRecord> { it.createdAt }.thenByDescending { it.activityId })
    val latest = ordered.firstOrNull { it.state != "queued" }
    ordered.filter { row ->
        (row == latest && (row.isTerminal || row.progressStatus(now) != "Stopped responding" || row.inStoppedWindow(now))) ||
            (row.state == "queued" && row.progressStatus(now).startsWith("Queued"))
    }.map { it.id }
}.toSet()

private fun AgentActivityRecord.inStoppedWindow(localNow: Long): Boolean {
    val server = instant(raw.string("android_server_time"))
    val received = flexibleLong(raw["android_received_at"])
    val now = if (server != null && received != null) server + (localNow - received).coerceAtLeast(0) else localNow
    val deadline = instant(raw.string("lease_expires_at") ?: raw.string("expires_at"))?.plus(15_000)
    val expired = instant(raw.string("expired_at"))
    val reference = listOfNotNull(deadline, expired).minOrNull() ?: instant(updatedAt) ?: instant(createdAt) ?: return false
    return now < reference + 600_000
}

/** A fork's inherited message never carries the parent's live activity into the child's current slot. */
fun AgentActivityRecord.belongsToTrigger(message: studio.otherstuff.ambulando.core.transport.MessageRecord, effectiveThreadId: String?): Boolean =
    triggerMessageId == message.id && workspaceId == message.workspaceId && channelId == message.channelId &&
        threadId == message.threadId && (effectiveThreadId == null || threadId == effectiveThreadId) && !message.deleted && !message.inherited
