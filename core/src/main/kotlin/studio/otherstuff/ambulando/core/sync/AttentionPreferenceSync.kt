package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.preferences.AttentionTypes
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

/** Durable server row and one immutable mutation. Unknown fields survive edits; retries never invent a new intent. */
data class AttentionPreferenceState(val row: JsonObject? = null, val ready: Boolean = false, val pending: JsonObject? = null, val message: String? = null, val desired: String? = null) {
    val types get() = AttentionTypes.decode(desired ?: (pending?.get("value") ?: row?.get("value"))?.jsonPrimitive?.contentOrNull)
    val version get() = row?.get("row_version")?.jsonPrimitive?.longOrNull ?: 0L
    val editable get() = ready && types.supported
    fun encode() = buildJsonObject { put("row", row ?: JsonNull); put("ready", ready); put("pending", pending ?: JsonNull); put("message", message?.let(::JsonPrimitive) ?: JsonNull); put("desired", desired?.let(::JsonPrimitive) ?: JsonNull) }.toString()
    companion object {
        fun key(c: String, w: String) = "${AttentionTypes.KEY}:$c:$w"
        fun decode(text: String?): AttentionPreferenceState = runCatching {
            val o = Json.parseToJsonElement(text ?: "{}").jsonObject
            AttentionPreferenceState(o["row"] as? JsonObject, o["ready"]?.jsonPrimitive?.booleanOrNull == true, o["pending"] as? JsonObject, (o["message"] as? JsonPrimitive)?.contentOrNull, (o["desired"] as? JsonPrimitive)?.contentOrNull)
        }.getOrDefault(AttentionPreferenceState())
    }
}

suspend fun StoreReader.attentionPreference(c: String, w: String) = AttentionPreferenceState.decode(state(AttentionPreferenceState.key(c, w)))
// Serializes local edits and response materialization. Each network operation remains owned by the existing engine.
private val attentionLock = Mutex()

suspend fun WorkspaceSync.setAttentionType(c: ConnectionRecord, w: WorkspaceRecord, type: String, on: Boolean) {
    identity.requireSigner(c.signerNpub)
    attentionLock.withLock {
        val state = store.reader.attentionPreference(c.id, w.id)
        check(state.editable && !w.accessRemoved) { "Connect to load compatible settings before changing them." }
        val body = buildJsonObject {
            put("value", state.types.withEnabled(type, on).encode()); put("expected_row_version", state.version); put("mutation_id", UUID.randomUUID().toString())
        }
        store.writer.setState(AttentionPreferenceState.key(c.id, w.id), state.copy(pending = state.pending ?: body, desired = body["value"]!!.jsonPrimitive.content, message = "Saved on this device; waiting to sync.").encode())
    }
    refreshAttentionPreference(c, w, SyncPriority.USER, force = true)
}

suspend fun WorkspaceSync.refreshAttentionPreference(c: ConnectionRecord, w: WorkspaceRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false) {
    if (w.accessRemoved) return
    engine.enqueueRead("attention-types:${w.id}", priority, force) { runAttentionPreference(c, w) }
}

internal suspend fun WorkspaceSync.runAttentionPreference(c: ConnectionRecord, w: WorkspaceRecord) {
    identity.requireReader(c.signerNpub)
    val key = AttentionPreferenceState.key(c.id, w.id)
    val path = "/api/v4/flightdeck-pg/workspaces/${w.id}/me/preferences/${AttentionTypes.KEY}"
    suspend fun request(body: JsonObject? = null): JsonObject? {
        val reply = Json.parseToJsonElement(api(c).client.request(if (body == null) "GET" else "PUT", path, appNpub = w.appNpub, jsonBody = body?.toString()?.toByteArray()).decodeToString()).jsonObject
        checkCancelled()
        val identity = reply["identity"] as? JsonObject
        if (identity?.get("workspace_id")?.jsonPrimitive?.content != w.id || !reply.containsKey("preference")) throw TowerError.Malformed("private preference identity")
        val row = reply["preference"] as? JsonObject ?: if (reply["preference"] == JsonNull && body == null) return null else throw TowerError.Malformed("private preference")
        val actor = store.reader.members(w.id).firstOrNull { it.npub == c.signerNpub }?.actorId
        if (row["workspace_id"]?.jsonPrimitive?.content != w.id || row["preference_key"]?.jsonPrimitive?.content != AttentionTypes.KEY ||
            row["actor_id"]?.jsonPrimitive?.content.isNullOrEmpty() || (actor != null && row["actor_id"]?.jsonPrimitive?.content != actor) ||
            (row["row_version"]?.jsonPrimitive?.longOrNull ?: 0) < 1 || (row["value"] as? JsonPrimitive)?.isString != true) throw TowerError.Malformed("private preference row")
        return row
    }
    suspend fun materialize(row: JsonObject?, clearPending: String? = null, message: String? = null, authoritativeRead: Boolean = false) = attentionLock.withLock {
        val state = store.reader.attentionPreference(c.id, w.id)
        val newer = if (authoritativeRead && row == null) null else row?.takeIf { (it["row_version"]?.jsonPrimitive?.longOrNull ?: 0) >= state.version } ?: state.row
        val cleared = clearPending != null && state.pending?.get("mutation_id")?.jsonPrimitive?.content == clearPending
        var pending = if (cleared) null else state.pending
        var desired = state.desired
        val overtaken = cleared && row != null && (row["row_version"]?.jsonPrimitive?.longOrNull ?: 0) < state.version
        if (cleared) {
            if (message == null && !overtaken && desired != null && desired != state.pending?.get("value")?.jsonPrimitive?.content) {
                pending = buildJsonObject { put("value", desired); put("expected_row_version", newer?.get("row_version")?.jsonPrimitive?.longOrNull ?: 0); put("mutation_id", UUID.randomUUID().toString()) }
            } else desired = null
        }
        store.writer.setState(key, state.copy(row = newer, ready = true, pending = pending, desired = desired, message = message ?: if (overtaken) "These settings changed on another device. The latest saved choice is shown." else if (pending != null) "Saved on this device; waiting to sync." else null).encode())
    }
    try {
        materialize(request(), authoritativeRead = true)
        val pending = store.reader.attentionPreference(c.id, w.id).pending ?: return
        // Background credentials are GET-only; the durable intent waits for unlock.
        if (identity.state() !is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked) return
        identity.requireSigner(c.signerNpub)
        try {
            materialize(request(pending), pending["mutation_id"]!!.jsonPrimitive.content)
            if (store.reader.attentionPreference(c.id, w.id).pending != null) refreshAttentionPreference(c, w, SyncPriority.USER, force = true)
        } catch (e: TowerError.Http) {
            if (e.status !in listOf(400, 403, 404, 409)) throw e
            val row = request()
            materialize(row, pending["mutation_id"]!!.jsonPrimitive.content, "These settings changed or were refused. The saved choice is shown; choose again to apply your change.", authoritativeRead = true)
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        attentionLock.withLock {
            val state = store.reader.attentionPreference(c.id, w.id)
            store.writer.setState(key, state.copy(message = if (state.pending != null) "Saved on this device; waiting to sync." else "Could not refresh settings. The last saved choice is shown.").encode())
        }
        // Older Towers have no private preferences. Preserve cached choices without putting all content sync in error.
        if (e is TowerError.Http && e.status in listOf(403, 404, 405, 501)) return
        throw e
    }
}
