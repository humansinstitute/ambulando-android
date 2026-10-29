package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.DocumentBodyError
import studio.otherstuff.ambulando.core.transport.ListResult
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerTime
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.lowerUUID
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.string

// Tower routes behind the Context Tree, Canvas, agents, Toolbox and personal pins. All are existing typed
// `/api/v4/flightdeck-pg` routes with NIP-98 auth. Paths are built only from validated UUIDs, and every row must belong
// to the requested workspace (and scope, canvas or channel) before it is returned for materialisation.

/** Pages a cursor route (`limit ≤ 200`, opaque `next_cursor`) to its end, refusing a repeated cursor. */
private suspend fun TowerAPI.cursorPages(path: String, appNpub: String, key: String, maxPages: Int = 20): List<JsonObject> =
    pages(path, appNpub, key, maxPages = maxPages)

// MARK: Context Tree

suspend fun TowerAPI.contextComponents(workspaceId: String, appNpub: String, scopeId: String): List<ContextComponentRecord> {
    val path = TowerAPI.base(workspaceId) + "/scopes/" + TowerAPI.uuid(scopeId) + "/context/components"
    val components = cursorPages(path, appNpub, "components").map { ContextComponentRecord.parse(it) }
    if (!components.all { it.workspaceId == workspaceId && it.scopeId == scopeId.lowercase() }) throw TowerError.Malformed("context components")
    return components
}

suspend fun TowerAPI.contextReferences(workspaceId: String, appNpub: String, scopeId: String, componentId: String): List<ContextReferenceRecord> {
    val path = TowerAPI.base(workspaceId) + "/scopes/" + TowerAPI.uuid(scopeId) + "/context/components/" + TowerAPI.uuid(componentId) + "/references"
    val references = cursorPages(path, appNpub, "references").map { ContextReferenceRecord.parse(it) }
    if (!references.all { it.workspaceId == workspaceId && it.scopeId == scopeId.lowercase() && it.componentId == componentId.lowercase() }) {
        throw TowerError.Malformed("context references")
    }
    return references
}

// MARK: Canvas

/**
 * Client-side bounds for one stored canvas snapshot (the first checks of the iOS `CanvasScene.parse`). Tower sets no
 * explicit limit; these keep parsing and drawing bounded, and a snapshot beyond them is refused with a visible error
 * instead of being shown partly. The canvas scene parser may be passed to [canvasSnapshot] instead.
 */
object CanvasSnapshotBounds {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_NAPLETS = 2_000
    const val MAX_ELEMENTS = 20_000

    sealed class ParseError(message: String) : Exception(message, null, false, false) {
        data object Invalid : ParseError("This canvas could not be read.")
        data class TooLarge(val what: String) : ParseError("This canvas is too large to show on this device ($what).")
    }

    fun check(bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) throw ParseError.TooLarge("more than 32 MB")
        val o = runCatching { parseJsonObject(bytes, "canvas") }.getOrNull() ?: throw ParseError.Invalid
        val naplets = o.objects("naplets") ?: throw ParseError.Invalid
        val elements = o.objects("elements") ?: throw ParseError.Invalid
        if (naplets.size > MAX_NAPLETS) throw ParseError.TooLarge("more than $MAX_NAPLETS items")
        if (elements.size > MAX_ELEMENTS) throw ParseError.TooLarge("more than $MAX_ELEMENTS drawing elements")
    }
}

/** The canvases visible to this viewer, in the viewer's order. Tower returns the complete list in one response. */
suspend fun TowerAPI.canvases(workspaceId: String, appNpub: String): List<CanvasRecord> {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/canvases", appNpub = appNpub), "canvases")
    val rows = o.objects("canvases")
    if (lowerUUID(o.string("workspace_id")) != workspaceId || o.bool("complete") != true || rows == null) throw TowerError.Malformed("canvases")
    return rows.map { CanvasRecord.parse(it, workspaceId) }
}

/**
 * One canvas snapshot: the definition, viewer-authorized naplets (denied ones carry only their frame) and elements.
 * [validate] refuses a snapshot beyond the bounds before it is stored (default [CanvasSnapshotBounds.check]).
 */
suspend fun TowerAPI.canvasSnapshot(
    workspaceId: String, appNpub: String, canvasId: String, validate: (ByteArray) -> Unit = CanvasSnapshotBounds::check,
): Pair<CanvasRecord, CanvasSnapshotRecord> {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/canvases/" + TowerAPI.uuid(canvasId) + "/snapshot", appNpub = appNpub), "canvas snapshot")
    val row = o.obj("canvas")
    val naplets = o["naplets"] as? JsonArray
    val elements = o["elements"] as? JsonArray
    if (lowerUUID(o.string("workspace_id")) != workspaceId || o.bool("complete") != true || row == null ||
        naplets == null || elements == null || naplets.any { it !is JsonObject } || elements.any { it !is JsonObject }
    ) throw TowerError.Malformed("canvas snapshot")
    val canvas = CanvasRecord.parse(row, workspaceId)
    if (canvas.id != canvasId.lowercase()) throw TowerError.Malformed("canvas snapshot")
    val bytes = jsonBody(JsonObject(mapOf("naplets" to naplets, "elements" to elements)))
    validate(bytes)
    return canvas to CanvasSnapshotRecord(workspaceId, canvas.id, canvas.rowVersion, bytes, TowerTime.now())
}

/** The HTML of an HTML naplet (web content), as Tower resolves it for this viewer. Returns (title, html). */
suspend fun TowerAPI.napletHTML(workspaceId: String, appNpub: String, canvasId: String, napletId: String): Pair<String, String> {
    val path = TowerAPI.base(workspaceId) + "/canvases/" + TowerAPI.uuid(canvasId) + "/naplets/" + TowerAPI.uuid(napletId) + "/html"
    val o = obj(client.request(path = path, appNpub = appNpub), "HTML naplet")
    val html = o.obj("content")?.string("html")
    if (lowerUUID(o.string("workspace_id")) != workspaceId || lowerUUID(o.string("naplet_id")) != napletId.lowercase() || html == null) {
        throw TowerError.Malformed("HTML naplet")
    }
    return (o.string("title") ?: "HTML") to html
}

/** An Excalidraw board version beyond this client's limit. */
sealed class BoardSceneError(message: String) : Exception(message, null, false, false) {
    data object TooLarge : BoardSceneError("This board is larger than the 24 MB limit.")
}

const val BOARD_MAX_BYTES = 24 * 1024 * 1024

/** The exact bytes of one board version, verified against the sha256 Tower projected for the naplet. */
suspend fun TowerAPI.boardScene(
    workspaceId: String, appNpub: String, fileId: String, versionId: String, sha256: String, sizeBytes: Long,
): BoardSceneRecord {
    if (sizeBytes > BOARD_MAX_BYTES) throw BoardSceneError.TooLarge
    val path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/versions/" + TowerAPI.uuid(versionId) + "/object"
    val bytes = client.request(path = path, appNpub = appNpub)
    if (bytes.size > BOARD_MAX_BYTES) throw BoardSceneError.TooLarge
    if (Sha256Hex.of(bytes) != sha256) throw DocumentBodyError.Integrity
    return BoardSceneRecord(workspaceId, fileId, versionId, sha256, bytes, TowerTime.now())
}

// MARK: Agents

suspend fun TowerAPI.workspaceAgents(workspaceId: String, appNpub: String): List<WorkspaceAgentRecord> {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/workspace-agents", appNpub = appNpub), "workspace agents")
    val rows = o.objects("workspace_agents") ?: throw TowerError.Malformed("workspace agents")
    val agents = rows.mapNotNull { runCatching { WorkspaceAgentRecord.parse(it) }.getOrNull() }
    if (!agents.all { it.workspaceId == workspaceId }) throw TowerError.Malformed("workspace agents")
    return agents
}

/** Tower's materialised user-visible activity for one channel: the newest page (no commentary history). */
const val AGENT_ACTIVITY_PAGE = 50

suspend fun TowerAPI.agentActivities(workspaceId: String, appNpub: String, channelId: String): List<AgentActivityRecord> {
    val query = listOf("channel_id" to TowerAPI.uuid(channelId), "history_limit" to "0", "limit" to AGENT_ACTIVITY_PAGE.toString())
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/agent-activities", query = query, appNpub = appNpub), "agent activity")
    val rows = o.objects("agent_activities") ?: throw TowerError.Malformed("agent activity")
    // Rows that are not user-visible or lack an activity id are skipped, as on the web.
    val activities = rows.mapNotNull { runCatching { AgentActivityRecord.parse(it) }.getOrNull() }
    if (!activities.all { it.workspaceId == workspaceId && it.channelId == channelId.lowercase() }) throw TowerError.Malformed("agent activity")
    return activities
}

// MARK: Toolbox

/**
 * Tower caps this list at 200 and returns no continuation (`next_cursor: null`). A full page may therefore be
 * incomplete: it is reported as such and never used to remove local rows.
 */
const val PERSONAL_WAPP_LIMIT = 200

suspend fun TowerAPI.personalWapps(workspaceId: String, appNpub: String): ListResult<PersonalWappRecord> {
    val o = obj(
        client.request(path = TowerAPI.base(workspaceId) + "/personal-wapps", query = listOf("limit" to PERSONAL_WAPP_LIMIT.toString()), appNpub = appNpub),
        "personal WApps",
    )
    val rows = o.objects("personal_wapps") ?: throw TowerError.Malformed("personal WApps")
    val wapps = rows.map { PersonalWappRecord.parse(it) }
    if (!wapps.all { it.workspaceId == workspaceId }) throw TowerError.Malformed("personal WApps")
    val more = o.string("next_cursor")?.isNotEmpty() ?: false
    return ListResult(wapps, rows.size < PERSONAL_WAPP_LIMIT && !more)
}

// MARK: Personal pins

/** The viewer's pins and the ids of pins deleted since (from the record-sync feed). */
data class PersonalPins(val pins: List<PinRecord>, val deleted: List<String>)

/**
 * The viewer's pins through the record-sync priority feed (`family=personal_pin`), read until `has_more` is false. The
 * feed lists each pin's newest head once; Tower makes the family visible only to its own actor. An expired
 * continuation (`409 auxiliary_cursor_invalid`) restarts one fresh pass.
 */
suspend fun TowerAPI.personalPins(workspaceId: String, appNpub: String, maxPages: Int = 20): PersonalPins {
    val path = TowerAPI.base(workspaceId) + "/record-sync/priority"
    var pins = LinkedHashMap<String, PinRecord>()
    var deleted = LinkedHashSet<String>()
    var cursor: String? = null
    var restarted = false
    var pages = 0
    while (pages < maxPages) {
        pages += 1
        val query = mutableListOf("family" to "personal_pin", "limit" to "200")
        cursor?.let { query.add("cursor" to it) }
        val data = try {
            client.request(path = path, query = query, appNpub = appNpub)
        } catch (e: TowerError.Http) {
            if (e.status == 409 && e.code == "auxiliary_cursor_invalid" && !restarted) {
                // The continuation expired or the record clock moved to a new epoch: start one fresh pass.
                restarted = true
                cursor = null
                pins = LinkedHashMap()
                deleted = LinkedHashSet()
                continue
            }
            throw e
        }
        val o = obj(data, "personal pins")
        val version = (o["protocol_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        val changes = o.objects("changes")
        if (version != 1.0 || changes == null) throw TowerError.Malformed("personal pins")
        for (change in changes) {
            if (change.string("family") != "personal_pin") continue
            val id = lowerUUID(change.string("id")) ?: continue
            val w = change.string("workspace_id")
            if (w != null && lowerUUID(w) != workspaceId) throw TowerError.Malformed("personal pins")
            if (change.string("operation") == "delete") {
                deleted.add(id)
                pins.remove(id)
                continue
            }
            val row = change.obj("row") ?: continue
            val pin = PinRecord.parse(row)
            if (pin.workspaceId != workspaceId || pin.id != id) throw TowerError.Malformed("personal pins")
            val existing = pins[id]
            if (existing == null || existing.rowVersion < pin.rowVersion) pins[id] = pin
        }
        if (o.bool("has_more") != true) return PersonalPins(pins.values.toList(), deleted.toList())
        val next = o.string("next_cursor")
        if (next.isNullOrEmpty() || next == cursor) throw TowerError.Malformed("personal pins cursor")
        cursor = next
    }
    throw TowerError.ListLimitReached("personal pins")
}

/** Capability is advertised by the main record-sync envelope, not its priority feed. */
suspend fun TowerAPI.supportsPinOrdering(workspaceId: String, appNpub: String): Boolean {
    val page = obj(client.request(path = TowerAPI.base(workspaceId) + "/record-sync", query = listOf("protocol_version" to "1", "limit" to "1"), appNpub = appNpub), "record sync capabilities")
    return (page["personal_pin_order_version"] as? JsonPrimitive)?.content == "1"
}
