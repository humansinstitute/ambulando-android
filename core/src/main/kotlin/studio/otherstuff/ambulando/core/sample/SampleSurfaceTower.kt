package studio.otherstuff.ambulando.core.sample

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord
import studio.otherstuff.ambulando.core.surfaces.BoardScene
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.isUUID
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.io.File
import java.util.Base64
import kotlin.math.floor

/**
 * In-process stand-in for Tower's Context Tree, Canvas, board version object, agent, Toolbox and personal pin routes.
 *
 * Like [SampleWorkTower] it exists for deterministic tests and the labelled sample-data mode; it is **not** Tower and is
 * never evidence of live behaviour. It follows the server rules the client depends on (Tower
 * `services/flightdeck-pg-context-tree.ts`, `flightdeck-pg-canvases.ts`, `thread-pins.ts`,
 * `flightdeck-record-priority.ts` and `routes/flightdeck-pg.ts`):
 * - every route needs a valid NIP-98 event over the exact URL, method and body bytes;
 * - an unreadable scope's tree is `404 context_not_found`; references resolve per viewer (`available`, `unavailable`,
 *   `external` for artifacts);
 * - invisible canvases are `404 canvas_unavailable`; naplets whose source the viewer cannot read are projected as
 *   `access: denied` with only their layout;
 * - board version bytes are served raw;
 * - agent activity needs `channel.read` on the channel (403 otherwise);
 * - pins: `expected_row_version` must match (409 `thread_pin_conflict`), a reused `mutation_id` with a different request
 *   is 409 `mutation_id_reused` and with the same request replays the receipt, an unreadable target is 403
 *   `thread_pin_target_forbidden`; reads come only through the record-sync priority feed (`family=personal_pin`).
 * Any write to a canvas route is recorded in [canvasWrites] (rendering must never produce one).
 */
class SampleSurfaceTower(
    workspaceId: String,
    file: File? = null,
    private val auth: SampleAuth = SampleAuth(),
) {
    private val lock = Any()
    val workspaceId: String = workspaceId.lowercase()
    private val state = SampleState(file, COLLECTIONS)
    private val faults = ArrayList<Pair<String, SampleFault>>()
    private val log = ArrayList<String>()
    private val writes = ArrayList<String>()
    private val changes = ArrayList<SampleChange>()

    /**
     * Records the host stand-in owns (sample tasks, documents, threads, channels), as `title`, `channel_id`, `scope_id`.
     * Consulted after records seeded here, so pins and references resolve against the data the other routes serve.
     */
    @Volatile var recordLookup: ((String) -> Map<String, Any?>?)? = null

    /** Page size of the pin feed (Tower's is 200; tests use a small one to exercise continuation). */
    @Volatile var pinPageSize = 200

    /** Committed writes (pins, canvases, naplets), reported after each request. */
    @Volatile var onChange: ((SampleChange) -> Unit)? = null

    init {
        synchronized(lock) {
            for (key in listOf("denied_scopes", "denied_channels", "denied_records", "hidden_canvases")) {
                if (state.data[key] !is List<*>) state.setList(key, emptyList())
            }
        }
    }

    companion object {
        val COLLECTIONS = listOf(
            "components", "references", "canvases", "naplets", "elements", "board_versions", "agents", "activities", "wapps", "pins", "pin_mutations",
            "records",
        )

        fun owns(p: List<String>): Boolean {
            val first = p.firstOrNull() ?: return false
            return when (first) {
                "canvases", "workspace-agents", "agent-activities", "personal-wapps", "record-sync" -> true
                "scopes" -> p.size >= 4 && p[2] == "context"
                "me" -> p.size >= 2 && p[1] in setOf("pins", "autopilot-agents")
                "files" -> p.size == 5 && p[2] == "versions" && p[4] == "object"
                else -> false
            }
        }

        /** `normalizeFlightDeckPgCanvasLayout`: rounded, size clamped to 220…1200 × 160…1000, z to 1…100000; `task_tree` kept. */
        fun napletLayout(value: Any?): Row {
            val o = value.asMap() ?: emptyMap()
            fun n(key: String, fallback: Double): Long {
                val d = (o[key] as? Number)?.toDouble() ?: fallback
                return (if (d >= 0) floor(d + 0.5) else -floor(-d + 0.5)).toLong()
            }
            val out: Row = linkedMapOf(
                "x" to n("x", 0.0), "y" to n("y", 0.0), "width" to n("width", 360.0).coerceIn(220, 1200),
                "height" to n("height", 280.0).coerceIn(160, 1000), "z" to n("z", 1.0).coerceIn(1, 100_000),
            )
            o["task_tree"]?.let { out["task_tree"] = it }
            return out
        }

        private val NAPLET_TYPES = setOf("document", "task_list", "task_tree", "chat_thread", "html", "excalidraw", "canvas")
        private val PIN_KEYS = setOf("pinned", "expected_row_version", "mutation_id", "import_only", "pinned_at")
    }

    fun inject(fault: SampleFault, matching: String) = synchronized(lock) { faults.add(matching to fault); Unit }
    val requestLog: List<String> get() = synchronized(lock) { log.toList() }

    /** Method and path of every non-GET request to a canvas route. */
    val canvasWrites: List<String> get() = synchronized(lock) { writes.toList() }

    // MARK: State helpers (lock held)

    private fun now(): String {
        val clock = state.clock + 1
        state.clock = clock
        return String.format("2026-10-24T11:%02d:%02d.%03dZ", (clock / 60_000) % 60, (clock / 1000) % 60, clock % 1000)
    }

    private fun <T> mutate(block: () -> T): T {
        val result = synchronized(lock) { block().also { state.persist() } }
        val pending = synchronized(lock) { changes.toList().also { changes.clear() } }
        onChange?.let { sink -> pending.forEach(sink) }
        return result
    }

    private fun lv(row: Map<String, Any?>, key: String): Long = (row[key].asInt() ?: 0).toLong()

    // MARK: Seeding and access (sample data and tests)

    fun seeded(): Boolean = synchronized(lock) { state.data["seeded"] == true }
    fun markSeeded() = mutate { state.data["seeded"] = true }

    /**
     * Viewer access, as Tower's ACL would decide it: a denied scope's tree is unreadable, a denied channel blocks its
     * activity and pin targets, a denied record resolves as unavailable (references) or denied (naplets).
     */
    fun denyScope(scope: String) = mutate { state.setList("denied_scopes", state.list("denied_scopes") + scope) }
    fun denyChannel(channel: String) = mutate { state.setList("denied_channels", state.list("denied_channels") + channel) }
    fun denyRecord(record: String) = mutate { state.setList("denied_records", state.list("denied_records") + record) }
    fun allowRecord(record: String) = mutate { state.setList("denied_records", state.list("denied_records").filter { it != record }) }
    fun hideCanvas(canvas: String) = mutate { state.setList("hidden_canvases", state.list("hidden_canvases") + canvas) }

    /** A record a reference or pin may point at: its type, title and channel (stand-in for Tower's own tables). */
    fun seedRecord(id: String, type: String, title: String, channel: String, scope: String) = mutate {
        state.put("records", id, linkedMapOf("id" to id, "type" to type, "title" to title, "channel_id" to channel, "scope_id" to scope))
    }

    fun seedComponent(id: String = newUUID(), scope: String, title: String, parent: String? = null, sortOrder: Int): String = mutate {
        val stamp = now()
        state.put(
            "components", id,
            linkedMapOf(
                "id" to id, "workspace_id" to workspaceId, "scope_id" to scope, "title" to title, "parent_id" to parent, "sort_order" to sortOrder.toLong(),
                "row_version" to 1L, "created_at" to stamp, "updated_at" to stamp, "deleted_at" to null,
            ),
        )
        id
    }

    fun renameComponent(id: String, title: String) = mutate {
        val row = state.row("components", id) ?: return@mutate
        row["title"] = title
        row["row_version"] = lv(row, "row_version") + 1
        row["updated_at"] = now()
        state.put("components", id, row)
    }

    /** Deletes a component with its subtree and their references (Tower's confirmed delete). */
    fun deleteComponent(id: String) = mutate {
        val doomed = hashSetOf(id)
        val frontier = arrayListOf(id)
        while (frontier.isNotEmpty()) {
            val next = frontier.removeAt(frontier.size - 1)
            for (c in state.rows("components")) {
                val cid = c["id"] as String
                if (c["parent_id"] == next && doomed.add(cid)) frontier.add(cid)
            }
        }
        for (cid in doomed) state.put("components", cid, null)
        for (r in state.rows("references")) if (r["component_id"] in doomed) state.put("references", r["id"] as String, null)
    }

    fun seedReference(component: String, type: String, recordId: String? = null, artifact: Map<String, String>? = null, sortOrder: Int): String {
        val id = newUUID()
        mutate {
            val c = state.row("components", component) ?: LinkedHashMap()
            val target: Any = if (type == "artifact") (artifact ?: emptyMap()) else mapOf("record_id" to (recordId ?: ""))
            val stamp = now()
            state.put(
                "references", id,
                linkedMapOf(
                    "id" to id, "workspace_id" to workspaceId, "scope_id" to c["scope_id"], "component_id" to component, "target_type" to type,
                    "target" to target, "sort_order" to sortOrder.toLong(), "row_version" to 1L, "created_at" to stamp, "updated_at" to stamp,
                ),
            )
        }
        return id
    }

    fun seedCanvas(
        id: String = newUUID(), title: String, position: Int, viewport: Map<String, Double> = mapOf("x" to 0.0, "y" to 0.0, "scale" to 1.0),
        canManage: Boolean = true,
    ): String = mutate {
        val stamp = now()
        state.put(
            "canvases", id,
            linkedMapOf(
                "id" to id, "title" to title, "position" to position.toLong(),
                "visibility" to mapOf("mode" to "workspace", "actor_ids" to emptyList<String>(), "group_ids" to emptyList<String>()),
                "viewport" to viewport, "row_version" to 1L, "created_at" to stamp, "updated_at" to stamp, "can_manage" to canManage,
            ),
        )
        id
    }

    /** A naplet with its source; `content` is what Tower's resolver would return for a readable source. */
    fun seedNaplet(
        canvas: String, sourceType: String, sourceId: String, title: String, layout: Map<String, Int>, content: Map<String, Any?>,
        source: Map<String, Any?>? = null,
    ): String {
        val id = newUUID()
        mutate {
            state.put(
                "naplets", id,
                linkedMapOf(
                    "id" to id, "canvas_id" to canvas, "source_type" to sourceType, "source_id" to sourceId, "title" to title,
                    "layout" to layout.mapValues { it.value.toLong() }, "content" to content, "source" to (source ?: mapOf("type" to sourceType, "id" to sourceId)),
                    "row_version" to 1L,
                ),
            )
        }
        return id
    }

    fun moveNaplet(id: String, layout: Map<String, Int>) = mutate {
        val row = state.row("naplets", id) ?: return@mutate
        row["layout"] = layout.mapValues { it.value.toLong() }
        row["row_version"] = lv(row, "row_version") + 1
        state.put("naplets", id, row)
    }

    fun seedElement(canvas: String, kind: String, layout: Map<String, Int>, data: Map<String, Any?>): String {
        val id = newUUID()
        mutate {
            val stamp = now()
            state.put(
                "elements", id,
                linkedMapOf(
                    "id" to id, "canvas_id" to canvas, "kind" to kind, "layout" to layout.mapValues { it.value.toLong() }, "data" to data,
                    "row_version" to 1L, "created_at" to stamp, "updated_at" to stamp,
                ),
            )
        }
        return id
    }

    /** A board File's version bytes; returns the projection a ready excalidraw naplet carries. */
    fun seedBoardVersion(fileId: String, versionId: String, versionNumber: Int, bytes: ByteArray): Map<String, Any?> {
        val sha = Sha256Hex.of(bytes)
        mutate { state.put("board_versions", "$fileId/$versionId", linkedMapOf("bytes" to Base64.getEncoder().encodeToString(bytes), "sha256" to sha)) }
        return linkedMapOf(
            "file_id" to fileId, "current_version_id" to versionId, "current_version_number" to versionNumber, "content_type" to BoardScene.CONTENT_TYPE,
            "size_bytes" to bytes.size, "sha256" to sha, "updated_at" to "2026-10-24T09:00:00.000Z", "updated_by_actor_npub" to null, "can_write" to false,
        )
    }

    fun seedAgent(npub: String, name: String, sortOrder: Int, visible: Boolean = true, avatarURL: String? = null): String {
        val id = newUUID()
        mutate {
            val stamp = now()
            state.put(
                "agents", id,
                linkedMapOf(
                    "id" to id, "workspace_id" to workspaceId, "connection_id" to newUUID(), "agent_id" to name.lowercase().filter { it.isLetterOrDigit() },
                    "agent_npub" to npub, "display_name" to name, "avatar_url" to avatarURL, "capabilities" to listOf("chat"), "sort_order" to sortOrder.toLong(),
                    "is_visible" to visible, "metadata" to emptyMap<String, Any>(), "row_version" to 1L, "created_at" to stamp, "updated_at" to stamp,
                    "archived_at" to null,
                ),
            )
        }
        return id
    }

    fun seedActivity(
        channel: String, scope: String, thread: String, agent: String, state: String, label: String, summary: String? = null, sequence: Int = 1,
        visibility: String = "user_visible",
    ): String {
        val id = newUUID()
        mutate {
            val stamp = now()
            val terminal = state in AgentActivityRecord.TERMINAL_STATES
            this.state.put(
                "activities", id,
                linkedMapOf(
                    "id" to id, "workspace_id" to workspaceId, "scope_id" to scope, "channel_id" to channel, "thread_id" to thread,
                    "trigger_message_id" to newUUID(), "turn_id" to null, "session_id" to "sample-session", "activity_id" to "act-${id.take(8)}",
                    "agent_npub" to agent, "publisher_actor_id" to newUUID(), "state" to state, "label" to label, "summary" to summary, "body" to null,
                    "visibility" to visibility, "sequence" to sequence.toLong(), "expires_at" to "2026-10-24T23:00:00.000Z", "last_heartbeat_at" to stamp,
                    "lease_expires_at" to "2026-10-24T23:00:00.000Z", "lease_health" to "live", "blocked_by_turn_id" to null, "queue_position" to null,
                    "terminal_at" to (if (terminal) stamp else null), "created_at" to stamp, "updated_at" to stamp,
                ),
            )
        }
        return id
    }

    fun seedWapp(
        title: String, launchURL: String, description: String? = null, iconURL: String? = null, sortOrder: Int, owner: String, status: String = "active",
    ): String {
        val id = newUUID()
        mutate {
            val stamp = now()
            state.put(
                "wapps", id,
                linkedMapOf(
                    "id" to id, "record_id" to id, "workspace_id" to workspaceId, "owner_actor_id" to newUUID(), "owner_npub" to owner,
                    "owner_actor_npub" to owner, "scope_id" to null, "channel_id" to null, "title" to title, "description" to description,
                    "launch_url" to launchURL, "icon_url" to iconURL, "app_id" to null, "wapp_id" to null, "source_wingman_url" to null,
                    "sort_order" to sortOrder.toLong(), "status" to status, "record_state" to status, "metadata" to emptyMap<String, Any>(),
                    "signer_profile" to null, "row_version" to 1L, "created_at" to stamp, "updated_at" to stamp,
                ),
            )
        }
        return id
    }

    /** Someone (another device) pins or unpins; bumps the row version like Tower. */
    fun externalPin(type: String, resource: String, pinned: Boolean) = mutate { writePin(type, resource, pinned); Unit }

    fun pinRows(): List<JsonObject> = synchronized(lock) { state.rows("pins").map { toJsonElement(it) as JsonObject } }

    /** Expires every pin feed continuation (a new record-clock epoch or the 7-day idle limit on Tower). */
    fun invalidatePinCursors() = mutate { state.data["pin_cursors"] = LinkedHashMap<String, Any?>() }

    // MARK: Routing

    /** Handles a request for these routes, or returns null for routes it does not own. */
    fun handle(request: HttpRequest): Reply? {
        val url = SampleURL.of(request) ?: return null
        val base = "/api/v4/flightdeck-pg/workspaces/$workspaceId/"
        val path = url.path
        if (!path.startsWith(base)) return null
        val parts = path.removePrefix(base).split('/').filter { it.isNotEmpty() }
        val method = request.method.uppercase()
        if (!owns(parts)) return null
        when (val result = auth.verify(request)) {
            is SampleAuth.Result.Invalid -> return SampleAuth.unauthorized(result.reason)
            is SampleAuth.Result.Valid -> Unit
        }
        val fault = synchronized(lock) {
            log.add("$method $path")
            if (parts.first() == "canvases" && method != "GET") writes.add("$method $path")
            val index = faults.indexOfFirst { path.endsWith(it.first) || path.contains(it.first) }
            if (index >= 0) faults.removeAt(index).second else null
        }
        when (fault) {
            SampleFault.Offline -> throw SampleOfflineException()
            is SampleFault.Status -> return errorReply(fault.status, fault.code)
            else -> Unit
        }
        val o = SampleJson.objectOf(request.body)
        val result = mutate { route(parts, method, o, url) }
        if (fault == SampleFault.DropResponseAfterCommit) throw SampleConnectionLostException()
        return result
    }

    private fun known(record: String): Map<String, Any?>? = state.row("records", record) ?: recordLookup?.invoke(record)

    private fun readable(record: String): Map<String, Any?>? {
        if (record in state.list("denied_records")) return null
        val r = known(record) ?: return null
        if ((r["channel_id"].asString() ?: "") in state.list("denied_channels")) return null
        return r
    }

    private val bySortOrder = compareBy<Row>({ it["sort_order"].asInt() ?: 0 }, { it["id"] as? String ?: "" })

    private fun route(p: List<String>, method: String, o: Row, url: SampleURL): Reply {
        val capabilities = linkedMapOf("read" to true, "manage" to false, "edit" to false, "scope_manage" to false)
        val n = p.size
        val head = p[0]
        return when {
            // Context Tree
            method == "GET" && n == 4 && head == "scopes" && p[3] == "components" -> {
                if (p[1] in state.list("denied_scopes")) return errorReply(404, "context_not_found")
                val rows = state.rows("components").filter { it["scope_id"] == p[1] }.sortedWith(bySortOrder)
                indexPage(rows, "components", url, mapOf("capabilities" to capabilities), hasMore = false)
            }
            method == "GET" && n == 6 && head == "scopes" && p[3] == "components" && p[5] == "references" -> {
                if (p[1] in state.list("denied_scopes")) return errorReply(404, "context_not_found")
                if (state.row("components", p[4])?.get("scope_id") != p[1]) return errorReply(404, "context_not_found")
                val rows = state.rows("references").filter { it["component_id"] == p[4] }.sortedWith(bySortOrder).map { row ->
                    val out = LinkedHashMap(row)
                    val recordId = row["target"].asMap()?.get("record_id").asString()
                    val record = recordId?.let { readable(it) }
                    out["resolution"] = when {
                        row["target_type"] == "artifact" -> mapOf("status" to "external")
                        record != null -> linkedMapOf("status" to "available", "title" to (record["title"] ?: ""), "record_id" to recordId)
                        else -> mapOf("status" to "unavailable")
                    }
                    out
                }
                indexPage(rows, "references", url, mapOf("capabilities" to capabilities), hasMore = false)
            }
            head == "scopes" -> errorReply(403, "permission_denied")
            // Canvas
            method == "GET" && n == 1 && head == "canvases" ->
                200 to linkedMapOf("workspace_id" to workspaceId, "complete" to true, "canvases" to visibleCanvases())
            method == "GET" && n == 3 && head == "canvases" && p[2] == "snapshot" -> {
                val canvas = visibleCanvases().firstOrNull { it["id"] == p[1] } ?: return errorReply(404, "canvas_unavailable")
                val denied = state.list("denied_records")
                val naplets = state.rows("naplets").filter { it["canvas_id"] == p[1] }
                    .sortedWith(compareBy({ it["layout"].asMap()?.get("z").asInt() ?: 1 }, { it["id"] as String }))
                    .map { nap ->
                        // Tower collapses missing, unauthorized and failed sources to the same non-disclosing projection.
                        if ((nap["source_id"].asString() ?: "") in denied) {
                            linkedMapOf("id" to nap["id"], "access" to "denied", "layout" to nap["layout"], "row_version" to nap["row_version"])
                        } else {
                            linkedMapOf(
                                "id" to nap["id"], "access" to "ready", "layout" to nap["layout"], "source" to nap["source"], "title" to nap["title"],
                                "content" to nap["content"], "row_version" to nap["row_version"],
                            )
                        }
                    }
                val elements = state.rows("elements").filter { it["canvas_id"] == p[1] }.map { e -> e.apply { remove("canvas_id") } }
                200 to linkedMapOf(
                    "workspace_id" to workspaceId, "complete" to true, "canvas" to canvas, "naplets" to naplets, "elements" to elements,
                    "spatial_context" to linkedMapOf("version" to 1, "coordinate_space" to "canvas_world", "items" to emptyList<Any>()),
                )
            }
            method == "GET" && n == 5 && head == "canvases" && p[2] == "naplets" && p[4] == "html" -> {
                if (visibleCanvases().none { it["id"] == p[1] }) return errorReply(404, "canvas_unavailable")
                val nap = state.row("naplets", p[3])
                val html = nap?.get("content").asMap()?.get("html").asString()
                if (nap == null || nap["source_type"] != "html" || (nap["source_id"].asString() ?: "") in state.list("denied_records") || html == null) {
                    return errorReply(404, "canvas_source_unavailable")
                }
                200 to linkedMapOf(
                    "workspace_id" to workspaceId, "naplet_id" to p[3], "title" to (nap["title"] ?: "HTML"), "source" to (nap["source"] ?: emptyMap<String, Any>()),
                    "content" to mapOf("html" to html), "versions" to emptyList<Any>(),
                )
            }
            // Canvas and naplet writes (recorded above); drawing-element writes are refused as the native client sends none.
            head == "canvases" -> canvasWrite(p, method, o)
            method == "GET" && n == 5 && head == "files" -> {
                val v = state.row("board_versions", "${p[1]}/${p[3]}")
                val bytes = v?.get("bytes").asString()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                if (p[1] in state.list("denied_records") || bytes == null) return errorReply(404, "file_version_object_not_found")
                200 to bytes
            }
            // Agents
            method == "GET" && n == 1 && head == "workspace-agents" ->
                200 to linkedMapOf("workspace_agents" to state.rows("agents").sortedBy { it["sort_order"].asInt() ?: 0 }, "next_cursor" to null)
            method == "GET" && n == 1 && head == "agent-activities" -> {
                val channel = url.query("channel_id")
                if (channel.isNullOrEmpty()) return errorReply(400, "validation_error")
                if (channel in state.list("denied_channels")) return errorReply(403, "permission_denied")
                val rows = state.rows("activities").filter { it["channel_id"] == channel }.sortedByDescending { it["created_at"] as? String ?: "" }
                indexPage(rows, "agent_activities", url, hasMore = false)
            }
            method == "GET" && n == 2 && head == "me" && p[1] == "autopilot-agents" -> {
                val rows = state.rows("agents").sortedBy { it["sort_order"].asInt() ?: 0 }
                200 to mapOf(
                    "settings" to linkedMapOf(
                        "row_version" to 1,
                        "autopilot_agents" to rows.map { linkedMapOf("agent_npub" to (it["agent_npub"] ?: ""), "url" to "${SampleHosts.BROWSER}/agents") },
                    ),
                )
            }
            // Toolbox
            method == "GET" && n == 1 && head == "personal-wapps" ->
                200 to linkedMapOf("personal_wapps" to state.rows("wapps").sortedBy { it["sort_order"].asInt() ?: 0 }, "next_cursor" to null)
            // Pins
            method == "GET" && n == 2 && head == "record-sync" && p[1] == "priority" -> pinFeed(url)
            method == "PUT" && n == 4 && head == "me" -> putPin(p[2], p[3], o)
            else -> errorReply(404, "not_found")
        }
    }

    // MARK: Canvas writes (lock held)

    /**
     * `POST /canvases`, `PATCH /canvases/:c`, `POST /canvases/:c/naplets`, `PATCH|DELETE /canvases/:c/naplets/:n`, with
     * Tower's checks in its order: visible canvas (404 `canvas_unavailable`), `can_manage` (403), body validation (400),
     * source authorization (400 `canvas_source_unavailable`), then `expected_row_version` (409 `stale_row_version`).
     */
    private fun canvasWrite(p: List<String>, method: String, o: Row): Reply {
        fun title(value: Any?): String? = value.asString()?.trimWhitespace()
        fun expected(): Int? = o["expected_row_version"].asInt()?.takeIf { it >= 1 }
        if (method == "POST" && p.size == 1) {
            val name = title(o["title"])
            if (name.isNullOrEmpty() || name.length > 200) return errorReply(400, "validation_error")
            val id = newUUID()
            val stamp = now()
            val position = (state.rows("canvases").mapNotNull { it["position"].asInt() }.maxOrNull() ?: 0) + 1
            val row: Row = linkedMapOf(
                "id" to id, "title" to name, "position" to position.toLong(),
                "visibility" to (o["visibility"] ?: mapOf("mode" to "workspace", "actor_ids" to emptyList<String>(), "group_ids" to emptyList<String>())),
                "viewport" to linkedMapOf("x" to 0, "y" to 0, "scale" to 1), "row_version" to 1L, "created_at" to stamp, "updated_at" to stamp,
                "can_manage" to true,
            )
            state.put("canvases", id, row)
            changes.add(SampleChange("canvas", id, "created", rowVersion = 1))
            return 201 to linkedMapOf("workspace_id" to workspaceId, "canvas" to row)
        }
        val canvas = if (p.size >= 2) visibleCanvases().firstOrNull { it["id"] == p[1] } else null
        canvas ?: return errorReply(404, "canvas_unavailable")
        if (canvas["can_manage"] != true) return errorReply(403, "permission_denied")
        return when {
            method == "PATCH" && p.size == 2 -> {
                val version = expected() ?: return errorReply(400, "validation_error")
                if (o.containsKey("title")) {
                    val name = title(o["title"])
                    if (name.isNullOrEmpty() || name.length > 200) return errorReply(400, "validation_error")
                }
                if (version != canvas["row_version"].asInt()) return errorReply(409, "stale_row_version")
                title(o["title"])?.let { canvas["title"] = it }
                o["visibility"]?.let { canvas["visibility"] = it }
                canvas["row_version"] = version + 1L
                canvas["updated_at"] = now()
                state.put("canvases", p[1], canvas)
                changes.add(SampleChange("canvas", p[1], "updated", rowVersion = version + 1L))
                200 to linkedMapOf("workspace_id" to workspaceId, "canvas" to canvas)
            }
            method == "POST" && p.size == 3 && p[2] == "naplets" -> {
                val source = o["source"].asMap() ?: emptyMap()
                val type = source["type"].asString() ?: ""
                val sourceId = (source["id"].asString() ?: "").lowercase()
                if (type !in NAPLET_TYPES || !isUUID(sourceId) || (type == "canvas" && sourceId == p[1]) || o["layout"] !is Map<*, *>) {
                    return errorReply(400, "validation_error")
                }
                val label = if (type == "canvas") {
                    val other = visibleCanvases().firstOrNull { it["id"] == sourceId } ?: return errorReply(400, "canvas_source_unavailable")
                    other["title"].asString() ?: "Canvas"
                } else {
                    val record = readable(sourceId) ?: return errorReply(400, "canvas_source_unavailable")
                    record["title"].asString() ?: "Untitled"
                }
                val content: Map<String, Any?> = when (type) {
                    "document" -> mapOf("body" to "")
                    "task_list" -> mapOf("tasks" to emptyList<Any>())
                    "chat_thread" -> mapOf("messages" to emptyList<Any>())
                    else -> emptyMap()
                }
                val id = newUUID()
                val row: Row = linkedMapOf(
                    "id" to id, "canvas_id" to p[1], "source_type" to type, "source_id" to sourceId,
                    "title" to (title(o["title"])?.takeIf { it.isNotEmpty() } ?: label), "layout" to napletLayout(o["layout"]), "content" to content,
                    "source" to linkedMapOf("type" to type, "id" to sourceId), "row_version" to 1L,
                )
                state.put("naplets", id, row)
                changes.add(SampleChange("canvas_naplet", id, "created", rowVersion = 1, payload = mapOf("canvas_id" to p[1])))
                201 to linkedMapOf("workspace_id" to workspaceId, "naplet" to row)
            }
            (method == "PATCH" || method == "DELETE") && p.size == 4 && p[2] == "naplets" -> {
                val version = expected() ?: return errorReply(400, "validation_error")
                if (method == "PATCH" && o["layout"] == null && o["title"] == null) return errorReply(400, "validation_error")
                val naplet = state.row("naplets", p[3])
                if (naplet == null || naplet["canvas_id"] != p[1] || version != naplet["row_version"].asInt()) return errorReply(409, "stale_row_version")
                if (method == "DELETE") {
                    state.put("naplets", p[3], null)
                    changes.add(SampleChange("canvas_naplet", p[3], "deleted", payload = mapOf("canvas_id" to p[1])))
                    return 200 to linkedMapOf("workspace_id" to workspaceId, "deleted" to true, "id" to p[3])
                }
                o["layout"]?.let { naplet["layout"] = napletLayout(it) }
                title(o["title"])?.takeIf { it.isNotEmpty() }?.let { naplet["title"] = it }
                naplet["row_version"] = version + 1L
                state.put("naplets", p[3], naplet)
                changes.add(SampleChange("canvas_naplet", p[3], "updated", rowVersion = version + 1L, payload = mapOf("canvas_id" to p[1])))
                200 to linkedMapOf("workspace_id" to workspaceId, "naplet" to naplet)
            }
            else -> errorReply(403, "permission_denied")
        }
    }

    private fun visibleCanvases(): List<Row> {
        val hidden = state.list("hidden_canvases")
        return state.rows("canvases").filter { (it["id"] as? String) !in hidden }
            .sortedWith(compareBy({ it["position"].asInt() ?: 0 }, { it["id"] as String }))
    }

    // MARK: Pins (lock held)

    /** Newest first, each pin's current row once; continuation tokens expire with [invalidatePinCursors]. */
    private fun pinFeed(url: SampleURL): Reply {
        if (url.queryValues("family") != listOf("personal_pin")) return errorReply(400, "invalid_family")
        val rows = state.rows("pins").sortedWith(compareByDescending<Row> { it["row_version"].asInt() ?: 0 }.thenByDescending { it["id"] as String })
        @Suppress("UNCHECKED_CAST")
        val cursors = (state.data["pin_cursors"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { state.data["pin_cursors"] = it }
        var start = 0
        url.query("cursor")?.let { cursor ->
            start = cursors[cursor].asInt() ?: return errorReply(409, "auxiliary_cursor_invalid")
        }
        val slice = rows.drop(start).take(minOf(pinPageSize, url.query("limit")?.toIntOrNull() ?: 200))
        val token = newUUID()
        cursors[token] = (start + slice.size).toLong()
        val changes = slice.map { row ->
            linkedMapOf(
                "family" to "personal_pin", "id" to row["id"], "operation" to "upsert", "version" to (row["row_version"].asInt() ?: 0).toString(),
                "workspace_id" to workspaceId, "scope_id" to row["scope_id"], "channel_id" to row["channel_id"], "row" to row,
            )
        }
        return 200 to linkedMapOf(
            "protocol_version" to 1, "captured_head" to "1", "changes" to changes, "next_cursor" to token, "has_more" to (start + slice.size < rows.size),
            "coverage" to "partial", "retention_policy" to "retain_downloaded",
        )
    }

    private fun pinKey(type: String, resource: String) = "$type:$resource"

    private fun writePin(type: String, resource: String, pinned: Boolean): Row {
        val key = pinKey(type, resource)
        var row: Row = state.rows("pins").firstOrNull { it["key"] == key } ?: LinkedHashMap()
        val record = readable(resource)
        val stamp = now()
        if (row.isEmpty()) {
            row = linkedMapOf(
                "id" to newUUID(), "workspace_id" to workspaceId, "actor_id" to "sample-actor", "key" to key, "resource_type" to type,
                "resource_id" to resource, "thread_id" to (if (type == "thread") resource else null), "order_position" to null, "row_version" to 0L,
            )
        }
        val wasPinned = row["pinned"] == true
        row["pinned"] = pinned
        row["channel_id"] = record?.get("channel_id") ?: row["channel_id"]
        row["scope_id"] = record?.get("scope_id") ?: row["scope_id"]
        row["root_record_id"] = resource
        // Re-pinning keeps the original time (Tower keeps `pinned_at` while a pin stays pinned).
        if (pinned) { if (!wasPinned) row["pinned_at"] = stamp } else row["pinned_at"] = null
        row["row_version"] = lv(row, "row_version") + 1
        row["updated_at"] = stamp
        state.put("pins", row["id"] as String, row)
        changes.add(SampleChange("personal_pin", row["id"] as String, "updated", row["channel_id"].asString(), row["scope_id"].asString(), row["row_version"] as Long))
        return row
    }

    private fun putPin(type: String, resource: String, o: Row): Reply {
        val pinned = o["pinned"] as? Boolean
        val expected = o["expected_row_version"].asInt()
        val mutation = o["mutation_id"].asString()
        if (type !in setOf("thread", "task", "document", "channel") || !isUUID(resource) || pinned == null || expected == null || expected < 0 ||
            mutation == null || !isUUID(mutation) || !PIN_KEYS.containsAll(o.keys)
        ) return errorReply(400, "invalid_thread_pin")
        val hash = Sha256Hex.of("$type|$resource|$pinned|$expected")
        state.row("pin_mutations", mutation)?.let { prior ->
            if (prior["hash"] != hash) return errorReply(409, "mutation_id_reused")
            return 200 to mapOf("pin" to (prior["ack"] ?: emptyMap<String, Any>()))
        }
        val current = state.rows("pins").firstOrNull { it["key"] == pinKey(type, resource) }
        if ((current?.get("row_version").asInt() ?: 0) != expected) return errorReply(409, "thread_pin_conflict")
        if (pinned) {
            if (known(resource) == null) return errorReply(404, "thread_not_found")
            if (readable(resource) == null) return errorReply(403, "thread_pin_target_forbidden")
        }
        val row = writePin(type, resource, pinned)
        state.put("pin_mutations", mutation, linkedMapOf("hash" to hash, "ack" to row))
        row.remove("key")
        return 200 to mapOf("pin" to row)
    }
}
