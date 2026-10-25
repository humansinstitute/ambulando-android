package studio.otherstuff.ambulando.core.sample

import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.ReactionEmoji
import studio.otherstuff.ambulando.core.transport.TaskPriority
import studio.otherstuff.ambulando.core.transport.TaskState
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.toJsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Base64

/**
 * In-process stand-in for Tower's task, document, file, storage, view-state, lease and reaction routes.
 *
 * It exists for deterministic tests and for the clearly labelled sample-data mode of debug builds. It is **not** Tower
 * and is never evidence of live behaviour. It follows the server rules the client depends on (Tower
 * `routes/flightdeck-pg.ts` and `routes/storage.ts`):
 * - every signed route needs a valid NIP-98 event over the exact URL, method and body bytes ([SampleAuth]);
 * - task `PATCH` checks `row_version` only for assignment; task create replays by `client_request_id` (200
 *   `replayed: true`, or 409 `idempotency_conflict` for a different request under the same id);
 * - document body saves need a valid lease and a matching base (`row_version` + body sha256), otherwise the submission
 *   becomes a recovery (409 `document_recovery_created`);
 * - storage prepare returns a presigned upload URL on another host, which must be called **without** credentials;
 *   completion checks size and sha256.
 * Faults can be injected per route for retry and conflict tests.
 */
class SampleWorkTower(
    workspaceId: String,
    val towerOrigin: String,
    val objectHost: String = SampleHosts.OBJECTS,
    file: File? = null,
    private val auth: SampleAuth = SampleAuth(),
) {
    private val lock = Any()
    val workspaceId: String = workspaceId.lowercase()
    private val state = SampleState(file, COLLECTIONS)
    private var actor = ""
    private val faults = ArrayList<Pair<String, SampleFault>>()
    private val leaks = ArrayList<String>()
    private val log = ArrayList<String>()
    private var externalDocSave = false
    private val changes = ArrayList<SampleChange>()

    /** Committed writes, reported after each request (the composite tower turns them into events). */
    @Volatile var onChange: ((SampleChange) -> Unit)? = null

    companion object {
        val COLLECTIONS = listOf(
            "tasks", "task_comments", "docs", "doc_comments", "objects", "files", "folders", "view_states", "reactions", "leases", "requests",
            "recoveries",
        )

        /** Whether a route under `/workspaces/:w/` belongs to this stand-in. */
        fun owns(p: List<String>): Boolean {
            val first = p.firstOrNull() ?: return false
            return when (first) {
                "tasks", "docs", "files", "edit-leases", "resource-view-states", "reactions", "storage" -> true
                "channels" -> p.size == 3 && p[2] in setOf("tasks", "docs", "files", "file-folders")
                else -> false
            }
        }

        /** Task tree relationships kept from the request (`metadata.parent_task_id`, `metadata.predecessor_task_ids`). */
        internal fun taskMetadata(o: Map<String, Any?>, assignee: String?): Row {
            val metadata: Row = linkedMapOf("assigned_to_npub" to assignee)
            val m = o["metadata"].asMap()
            if (m != null) {
                m["parent_task_id"].asString()?.let { metadata["parent_task_id"] = it.lowercase() }
                m["predecessor_task_ids"].asStrings()?.let { list -> metadata["predecessor_task_ids"] = list.map { it.lowercase() } }
            }
            return metadata
        }
    }

    /** The actor Tower would resolve from the NIP-98 signer (the sample identity's npub). */
    var actorNpub: String
        get() = synchronized(lock) { actor }
        set(value) = synchronized(lock) { actor = value }

    fun inject(fault: SampleFault, matching: String) = synchronized(lock) { faults.add(matching to fault); Unit }

    val requestLog: List<String> get() = synchronized(lock) { log.toList() }

    /** Requests that reached the presigned host carrying an Authorization header or cookie (must stay empty). */
    val credentialLeaks: List<String> get() = synchronized(lock) { leaks.toList() }

    // MARK: State helpers (lock held)

    private fun now(): String {
        val clock = state.clock + 1
        state.clock = clock
        return String.format("2026-10-24T10:%02d:%02d.%03dZ", (clock / 60_000) % 60, (clock / 1000) % 60, clock % 1000)
    }

    private fun emit(change: SampleChange) { changes.add(change) }

    private fun flushChanges() {
        val pending = synchronized(lock) { changes.toList().also { changes.clear() } }
        val sink = onChange ?: return
        pending.forEach(sink)
    }

    private fun <T> mutate(block: () -> T): T {
        val result = synchronized(lock) { block().also { state.persist() } }
        flushChanges()
        return result
    }

    private fun json(row: Map<String, Any?>?): JsonObject? = row?.let { toJsonElement(it) as JsonObject }

    // MARK: Seeding (sample data and tests)

    fun seeded(): Boolean = synchronized(lock) { state.data["seeded"] == true }

    fun markSeeded() = mutate { state.data["seeded"] = true }

    fun seedTask(
        id: String = newUUID(), scope: String, channel: String, title: String, description: String? = null, state: String = "new",
        priority: String = "sand", assignee: String? = null, creator: String? = null,
    ): String = mutate {
        val stamp = now()
        this.state.put(
            "tasks", id,
            linkedMapOf(
                "id" to id, "workspace_id" to workspaceId, "scope_id" to scope, "channel_id" to channel, "thread_id" to null, "title" to title,
                "description" to description, "state" to state, "priority" to priority, "metadata" to mapOf("assigned_to_npub" to assignee),
                "assigned_to_npub" to assignee, "row_version" to 1L, "activity_version" to 1L, "created_by_actor_npub" to creator,
                "sender_npub" to creator, "assignments" to emptyList<Any>(), "created_at" to stamp, "updated_at" to stamp,
            ),
        )
        id
    }

    /** Someone else edits a task (bumps row and activity versions). */
    fun externalTaskEdit(id: String, fields: Map<String, Any?>) = mutate {
        val row = state.row("tasks", id) ?: return@mutate
        row.putAll(fields)
        row["row_version"] = ((row["row_version"].asInt() ?: 1) + 1).toLong()
        row["activity_version"] = ((row["activity_version"].asInt() ?: 1) + 1).toLong()
        row["updated_at"] = now()
        state.put("tasks", id, row)
        emitTask(row, "updated")
    }

    fun task(id: String): JsonObject? = synchronized(lock) { json(state.row("tasks", id)) }

    fun seedTaskComment(task: String, body: String, sender: String): String = mutate {
        val id = newUUID()
        val stamp = now()
        val t = state.row("tasks", task) ?: LinkedHashMap()
        state.put(
            "task_comments", id,
            linkedMapOf(
                "id" to id, "workspace_id" to workspaceId, "scope_id" to t["scope_id"], "channel_id" to t["channel_id"], "task_id" to task,
                "thread_id" to null, "body" to body, "metadata" to emptyMap<String, Any>(), "row_version" to 1L, "sender_npub" to sender,
                "created_by_actor_npub" to sender, "created_at" to stamp, "updated_at" to stamp,
            ),
        )
        id
    }

    /** Stores a completed object as if uploaded earlier (lock held). */
    private fun storeObject(bytes: ByteArray, contentType: String, name: String): String {
        val id = newUUID()
        state.put(
            "objects", id,
            linkedMapOf(
                "id" to id, "content_type" to contentType, "file_name" to name, "size_bytes" to bytes.size.toString(), "sha256_hex" to Sha256Hex.of(bytes),
                "is_public" to false, "completed_at" to now(), "bytes" to Base64.getEncoder().encodeToString(bytes), "owner_npub" to actor,
            ),
        )
        return id
    }

    /** The uploaded bytes of a completed storage object (other sample routes that take a `storage_object_id`). */
    fun completedObjectBytes(id: String): ByteArray? = synchronized(lock) {
        val row = state.row("objects", id) ?: return null
        if (row["completed_at"] !is String) return null
        row["bytes"].asString()?.let { Base64.getDecoder().decode(it) }
    }

    /** A stored object on its own (an image referenced from a document or message as `storage://<id>`). */
    fun seedObject(bytes: ByteArray, contentType: String, name: String): String = mutate { storeObject(bytes, contentType, name) }

    fun seedDoc(id: String = newUUID(), scope: String, channel: String, title: String, body: ByteArray, contentType: String): String = mutate {
        val objectId = storeObject(body, contentType, "$title.document.json")
        val stamp = now()
        state.put(
            "docs", id,
            linkedMapOf(
                "id" to id, "workspace_id" to workspaceId, "scope_id" to scope, "channel_id" to channel, "storage_object_id" to objectId, "title" to title,
                "summary" to DocumentBody(body, contentType).markdown, "metadata" to mapOf("mentions" to emptyList<Any>()), "row_version" to 1L,
                "activity_version" to 1L, "created_at" to stamp, "updated_at" to stamp, "archived_at" to null, "record_state" to "active",
            ),
        )
        id
    }

    /** Someone else saves a new body (moves the head; a stale native save then becomes a recovery). */
    fun externalDocSave(id: String, body: ByteArray, contentType: String) = mutate {
        val row = state.row("docs", id) ?: return@mutate
        row["storage_object_id"] = storeObject(body, contentType, "external.json")
        row["row_version"] = ((row["row_version"].asInt() ?: 1) + 1).toLong()
        row["updated_at"] = now()
        state.put("docs", id, row)
        emitDoc(row, "updated")
    }

    fun doc(id: String): JsonObject? = synchronized(lock) { json(state.row("docs", id)) }

    fun docBody(id: String): ByteArray? = synchronized(lock) {
        val objectId = state.row("docs", id)?.get("storage_object_id").asString() ?: return null
        state.row("objects", objectId)?.get("bytes").asString()?.let { Base64.getDecoder().decode(it) }
    }

    val recoveryCount: Int get() = synchronized(lock) { state.count("recoveries") }

    /** Sample-data switch: the next document body save finds that someone else saved first (a recovery copy). */
    var externalSaveBeforeNextDocBodySave: Boolean
        get() = synchronized(lock) { externalDocSave }
        set(value) = synchronized(lock) { externalDocSave = value }

    /** Another editor holds the document's lease. */
    fun holdLease(doc: String, by: String) = mutate {
        state.put("leases", "held-$doc", linkedMapOf("id" to newUUID(), "entity_id" to doc, "holder" to by, "token" to "other", "expires" to Long.MAX_VALUE))
    }

    fun releaseHeldLease(doc: String) = mutate { state.put("leases", "held-$doc", null) }

    fun seedFile(channel: String, scope: String, name: String, bytes: ByteArray, contentType: String, folder: String? = null): String = mutate {
        val objectId = storeObject(bytes, contentType, name)
        val id = newUUID()
        state.put("files", id, fileRow(id, channel, scope, objectId, name, folder, now()))
        id
    }

    private fun fileRow(id: String, channel: String, scope: String?, objectId: String, name: String, folder: String?, stamp: String): Row {
        val meta = state.row("objects", objectId) ?: LinkedHashMap()
        return linkedMapOf(
            "id" to id, "workspace_id" to workspaceId, "scope_id" to scope, "channel_id" to channel, "folder_id" to folder, "storage_object_id" to objectId,
            "current_version_id" to null, "display_name" to name, "description" to null, "metadata" to emptyMap<String, Any>(), "row_version" to 1L,
            "created_at" to stamp, "updated_at" to stamp, "archived_at" to null, "record_state" to "active", "deleted_at" to null,
            "object" to mapOf(
                "object_id" to objectId,
                "storage_object" to mapOf(
                    "content_type" to (meta["content_type"] ?: "application/octet-stream"), "size_bytes" to (meta["size_bytes"] ?: "0"),
                    "sha256_hex" to meta["sha256_hex"], "file_name" to (meta["file_name"] ?: name),
                ),
            ),
            "current_version" to null,
        )
    }

    fun seedFolder(channel: String, scope: String, title: String): String = mutate {
        val id = newUUID()
        state.put(
            "folders", id,
            linkedMapOf(
                "id" to id, "workspace_id" to workspaceId, "scope_id" to scope, "channel_id" to channel, "parent_folder_id" to null, "title" to title,
                "metadata" to emptyMap<String, Any>(), "row_version" to 1L, "updated_at" to now(), "deleted_at" to null,
            ),
        )
        id
    }

    val fileCount: Int get() = synchronized(lock) { state.count("files") }
    fun files(): List<JsonObject> = synchronized(lock) { state.rows("files").mapNotNull { json(it) } }

    fun seedViewState(type: String, resource: String, scope: String?, channel: String?, activity: Int, viewed: Int, attention: Int? = null) = mutate {
        val row: Row = linkedMapOf(
            "workspace_id" to workspaceId, "viewer_actor_id" to "sample", "resource_type" to type, "resource_id" to resource, "scope_id" to scope,
            "channel_id" to channel, "activity_version" to activity.toLong(), "viewed_activity_version" to viewed.toLong(), "unread" to (activity > viewed),
            "row_version" to 1L, "created_at" to now(), "updated_at" to now(),
        )
        if (attention != null) {
            row["attention_policy_version"] = 1L
            row["attention_activity_version"] = attention.toLong()
            row["attention_unread"] = attention > viewed
        }
        state.put("view_states", "$type:$resource", row)
    }

    fun viewState(type: String, resource: String): JsonObject? = synchronized(lock) { json(state.row("view_states", "$type:$resource")) }

    fun reactionRows(): List<JsonObject> = synchronized(lock) { state.rows("reactions").mapNotNull { json(it) } }
    fun taskComments(task: String): List<JsonObject> = synchronized(lock) { state.rows("task_comments").filter { it["task_id"] == task }.mapNotNull { json(it) } }
    fun docComments(doc: String): List<JsonObject> = synchronized(lock) { state.rows("doc_comments").filter { it["doc_id"] == doc }.mapNotNull { json(it) } }

    /** Tower's API-fallback upload path (`upload_url` on the Tower origin) instead of a presigned host. */
    fun useFallbackUploads(on: Boolean) = mutate { state.data["fallback_uploads"] = on }

    // MARK: Routing

    /**
     * Handles a request for these routes, or returns null for routes it does not own (chat, discovery). Throws
     * [SampleOfflineException] / [SampleConnectionLostException] for injected network faults.
     */
    fun handle(request: HttpRequest): Reply? {
        val url = SampleURL.of(request) ?: return null
        if (url.host == objectHost) return presignedPut(request, url)
        val path = url.path
        val base = "/api/v4/flightdeck-pg/workspaces/$workspaceId"
        val isStorage = path.startsWith("/api/v4/storage/")
        if (!isStorage && !path.startsWith("$base/")) return null
        val parts = (if (isStorage) path.removePrefix("/api/v4/storage/") else path.removePrefix("$base/")).split('/').filter { it.isNotEmpty() }
        val method = request.method.uppercase()
        if (!isStorage && !owns(parts)) return null
        when (val result = auth.verify(request)) {
            is SampleAuth.Result.Invalid -> return SampleAuth.unauthorized(result.reason)
            is SampleAuth.Result.Valid -> Unit
        }
        val fault = synchronized(lock) {
            log.add("$method $path")
            val index = faults.indexOfFirst { path.endsWith(it.first) || path.contains(it.first) }
            if (index >= 0) faults.removeAt(index).second else null
        }
        when (fault) {
            SampleFault.Offline -> throw SampleOfflineException()
            is SampleFault.Status -> return errorReply(fault.status, fault.code)
            else -> Unit
        }
        val o = SampleJson.objectOf(request.body)
        val result = mutate { if (isStorage) storage(parts, method, o) else route(parts, method, o, url) }
        if (fault == SampleFault.DropResponseAfterCommit) throw SampleConnectionLostException()
        return result
    }

    private fun presignedPut(request: HttpRequest, url: SampleURL): Reply {
        synchronized(lock) {
            if (request.header("Authorization") != null || request.header("Cookie") != null) leaks.add(request.url)
            log.add("PUT presigned ${url.path}")
        }
        val id = url.url.pathSegments.lastOrNull()
        if (request.method.uppercase() != "PUT" || id == null) return 400 to mapOf("error" to "bad request")
        val fault = synchronized(lock) {
            val index = faults.indexOfFirst { it.first == "presigned" }
            if (index >= 0) faults.removeAt(index).second else null
        }
        when (fault) {
            SampleFault.Offline -> throw SampleOfflineException()
            is SampleFault.Status -> return fault.status to ByteArray(0)
            else -> Unit
        }
        return mutate {
            val row = state.row("objects", id)
            if (row == null || row["completed_at"] != null) return@mutate 403 to ByteArray(0)
            row["bytes"] = Base64.getEncoder().encodeToString(request.body ?: ByteArray(0))
            state.put("objects", id, row)
            200 to ByteArray(0)
        }
    }

    private fun sortedByCreated(rows: List<Row>): List<Row> = rows.sortedBy { it["created_at"] as? String ?: "" }

    private fun route(p: List<String>, method: String, o: Row, url: SampleURL): Reply {
        val n = p.size
        val head = p[0]
        return when {
            method == "GET" && n == 3 && head == "channels" && p[2] == "tasks" -> {
                val rows = state.rows("tasks").filter { it["channel_id"] == p[1] }.sortedByDescending { it["updated_at"] as? String ?: "" }
                indexPage(rows, "tasks", url, mapOf("channel_id" to p[1]))
            }
            method == "POST" && n == 3 && head == "channels" && p[2] == "tasks" -> createTask(p[1], o)
            method == "GET" && n == 2 && head == "tasks" -> state.row("tasks", p[1])?.let { 200 to mapOf("task" to it) } ?: errorReply(404, "task_not_found")
            method == "GET" && n == 3 && head == "tasks" && p[2] == "relationships" -> {
                // Tower `GET /tasks/:t/relationships`: the task and those naming it as parent or predecessor (one page).
                val task = state.row("tasks", p[1]) ?: return errorReply(404, "task_unavailable")
                val related = state.rows("tasks").filter { row ->
                    val m = row["metadata"].asMap() ?: emptyMap()
                    m["parent_task_id"] == p[1] || (m["predecessor_task_ids"].asStrings() ?: emptyList()).contains(p[1])
                }.sortedBy { it["id"] as String }
                200 to linkedMapOf(
                    "workspace_id" to workspaceId, "task" to task, "tasks" to related, "unavailable" to false, "has_more" to false, "next_cursor" to null,
                )
            }
            method == "PATCH" && n == 2 && head == "tasks" -> patchTask(p[1], o)
            method == "POST" && n == 3 && head == "tasks" && p[2] == "state" -> {
                val row = state.row("tasks", p[1]) ?: return errorReply(404, "task_not_found")
                val s = o["state"].asString() ?: ""
                if (TaskState.from(s) == null) return validationError("state is not a valid task state")
                row["state"] = s
                bump(row)
                state.put("tasks", p[1], row)
                emitTask(row, "updated")
                200 to mapOf("task" to row)
            }
            method == "GET" && n == 3 && head == "tasks" && p[2] == "comments" -> {
                if (state.row("tasks", p[1]) == null) return errorReply(404, "task_not_found")
                indexPage(sortedByCreated(state.rows("task_comments").filter { it["task_id"] == p[1] }), "comments", url, mapOf("task_id" to p[1]))
            }
            method == "POST" && n == 3 && head == "tasks" && p[2] == "comments" -> {
                val t = state.row("tasks", p[1]) ?: return errorReply(404, "task_not_found")
                val text = (o["body"].asString() ?: "").trimWhitespace()
                if (text.isEmpty()) return validationError("body must be a non-empty string")
                val id = newUUID()
                val stamp = now()
                val row: Row = linkedMapOf(
                    "id" to id, "workspace_id" to workspaceId, "scope_id" to t["scope_id"], "channel_id" to t["channel_id"], "task_id" to p[1],
                    "thread_id" to null, "body" to text, "metadata" to (o["metadata"] ?: emptyMap<String, Any>()), "row_version" to 1L,
                    "created_by_actor_npub" to actor, "sender_npub" to actor, "created_at" to stamp, "updated_at" to stamp,
                )
                state.put("task_comments", id, row)
                t["activity_version"] = ((t["activity_version"].asInt() ?: 1) + 1).toLong()
                state.put("tasks", p[1], t)
                emit(SampleChange("task_comment", id, "created", t["channel_id"].asString(), t["scope_id"].asString(), 1, mapOf("task_id" to p[1])))
                201 to mapOf("comment" to row)
            }

            method == "GET" && n == 3 && head == "channels" && p[2] == "docs" -> {
                val rows = state.rows("docs").filter { it["channel_id"] == p[1] && it["archived_at"] == null }
                200 to linkedMapOf("docs" to rows.map { docOut(it) }, "channel_id" to p[1], "next_cursor" to null)
            }
            method == "POST" && n == 3 && head == "channels" && p[2] == "docs" -> {
                val objectId = o["storage_object_id"].asString()
                val obj = objectId?.let { state.row("objects", it) }
                if (objectId == null || obj == null || obj["completed_at"] !is String) return errorReply(400, "validation_error")
                if (state.rows("docs").any { it["storage_object_id"] == objectId }) return errorReply(409, "doc_storage_object_conflict")
                val id = newUUID()
                val stamp = now()
                val row: Row = linkedMapOf(
                    "id" to id, "workspace_id" to workspaceId, "scope_id" to "", "channel_id" to p[1], "storage_object_id" to objectId,
                    "title" to (o["title"] ?: "Untitled"), "summary" to o["summary"], "metadata" to mapOf("mentions" to emptyList<Any>()),
                    "row_version" to 1L, "activity_version" to 1L, "created_at" to stamp, "updated_at" to stamp, "archived_at" to null,
                    "record_state" to "active",
                )
                state.put("docs", id, row)
                emitDoc(row, "created")
                201 to mapOf("doc" to docOut(row))
            }
            method == "GET" && n == 2 && head == "docs" -> state.row("docs", p[1])?.let { 200 to mapOf("doc" to docOut(it)) } ?: errorReply(404, "doc_not_found")
            method == "GET" && n == 3 && head == "docs" && p[2] == "body" -> {
                val d = state.row("docs", p[1])
                val objectId = d?.get("storage_object_id").asString()
                val obj = objectId?.let { state.row("objects", it) }
                val b64 = obj?.get("bytes").asString()
                if (d == null || obj == null || b64 == null) return errorReply(404, "doc_body_not_found")
                200 to linkedMapOf(
                    "doc" to docOut(d),
                    "body" to linkedMapOf(
                        "object_id" to objectId, "content_type" to (obj["content_type"] ?: ""), "size_bytes" to Base64.getDecoder().decode(b64).size,
                        "sha256_hex" to obj["sha256_hex"], "encoding" to "base64", "base64_data" to b64,
                    ),
                )
            }
            method == "PATCH" && n == 2 && head == "docs" -> patchDoc(p[1], o)
            method == "GET" && n == 3 && head == "docs" && p[2] == "comments" -> {
                if (state.row("docs", p[1]) == null) return errorReply(404, "doc_not_found")
                indexPage(sortedByCreated(state.rows("doc_comments").filter { it["doc_id"] == p[1] }), "comments", url, mapOf("doc_id" to p[1]))
            }
            method == "POST" && n == 3 && head == "docs" && p[2] == "comments" -> {
                val d = state.row("docs", p[1]) ?: return errorReply(404, "doc_not_found")
                val text = (o["body"].asString() ?: "").trimWhitespace()
                if (text.isEmpty()) return errorReply(400, "validation_error")
                val id = newUUID()
                val stamp = now()
                val row: Row = linkedMapOf(
                    "id" to id, "workspace_id" to workspaceId, "scope_id" to d["scope_id"], "channel_id" to d["channel_id"], "doc_id" to p[1],
                    "parent_comment_id" to o["parent_comment_id"], "body" to text, "metadata" to (o["metadata"] ?: emptyMap<String, Any>()),
                    "record_state" to "active", "deleted_at" to null, "row_version" to 1L, "created_by_actor_npub" to actor, "sender_npub" to actor,
                    "created_at" to stamp, "updated_at" to stamp,
                )
                state.put("doc_comments", id, row)
                emit(SampleChange("doc_comment", id, "created", d["channel_id"].asString(), d["scope_id"].asString(), 1, mapOf("doc_id" to p[1])))
                201 to mapOf("comment" to row)
            }
            method == "PATCH" && n == 4 && head == "docs" && p[2] == "comments" -> {
                val c = state.row("doc_comments", p[3]) ?: return errorReply(404, "doc_comment_not_found")
                if (o["row_version"].asInt() == null || o["row_version"].asInt() != c["row_version"].asInt()) return errorReply(409, "stale_row_version")
                val meta = LinkedHashMap(c["metadata"].asMap() ?: emptyMap())
                meta["comment_status"] = o["comment_status"] ?: "open"
                c["metadata"] = meta
                c["row_version"] = ((c["row_version"].asInt() ?: 1) + 1).toLong()
                c["updated_at"] = now()
                state.put("doc_comments", p[3], c)
                emit(SampleChange("doc_comment", p[3], "updated", c["channel_id"].asString(), c["scope_id"].asString(), c["row_version"] as Long, mapOf("doc_id" to p[1])))
                200 to mapOf("comment" to c)
            }

            method == "POST" && n == 2 && head == "edit-leases" && p[1] == "acquire" -> {
                val entity = o["entity_id"].asString() ?: ""
                state.row("leases", "held-$entity")?.let { held ->
                    return errorReply(409, "edit_lease_held", mapOf("details" to mapOf("holder_actor_npub" to (held["holder"] ?: ""), "expires_at" to "later")))
                }
                val id = newUUID()
                val token = newUUID()
                state.put("leases", id, linkedMapOf("id" to id, "entity_id" to entity, "holder" to actor, "token" to token))
                201 to mapOf(
                    "lease" to linkedMapOf(
                        "id" to id, "entity_type" to "document", "entity_id" to entity, "field_path" to null, "lease_token" to token,
                        "holder_actor_npub" to actor, "expires_at" to "2026-10-24T11:00:00.000Z",
                    ),
                )
            }
            method == "POST" && n == 3 && head == "edit-leases" && p[2] == "renew" -> {
                val lease = state.row("leases", p[1])
                if (lease == null || lease["token"] != o["lease_token"].asString()) return errorReply(409, "lease_invalid")
                200 to mapOf(
                    "lease" to linkedMapOf(
                        "id" to p[1], "entity_type" to "document", "entity_id" to (lease["entity_id"] ?: ""), "holder_actor_npub" to actor,
                        "expires_at" to "2026-10-24T11:05:00.000Z",
                    ),
                )
            }
            method == "POST" && n == 3 && head == "edit-leases" && p[2] == "release" -> {
                val token = state.row("leases", p[1])?.get("token").asString()
                val released = token != null && token == o["lease_token"].asString()
                if (released) state.put("leases", p[1], null)
                200 to mapOf("released" to released)
            }

            method == "GET" && n == 3 && head == "channels" && p[2] == "files" ->
                200 to linkedMapOf("files" to state.rows("files").filter { it["channel_id"] == p[1] && it["deleted_at"] == null }, "next_cursor" to null)
            method == "GET" && n == 3 && head == "channels" && p[2] == "file-folders" ->
                200 to linkedMapOf("folders" to state.rows("folders").filter { it["channel_id"] == p[1] }, "next_cursor" to null)
            method == "POST" && n == 3 && head == "channels" && p[2] == "files" -> {
                val objectId = o["storage_object_id"].asString()
                val obj = objectId?.let { state.row("objects", it) }
                if (objectId == null || obj == null || obj["completed_at"] !is String) return errorReply(400, "validation_error")
                if (state.rows("files").any { it["storage_object_id"] == objectId }) return errorReply(409, "file_storage_object_conflict")
                val id = newUUID()
                val row = fileRow(id, p[1], null, objectId, o["display_name"].asString() ?: "file", o["folder_id"].asString(), now())
                state.put("files", id, row)
                emit(SampleChange("file", id, "created", p[1], null, 1))
                201 to mapOf("file" to row)
            }
            method == "GET" && n == 3 && head == "files" && p[2] == "object" -> {
                val f = state.row("files", p[1])
                val objectId = f?.get("storage_object_id").asString()
                val obj = objectId?.let { state.row("objects", it) }
                val b64 = obj?.get("bytes").asString()
                if (f == null || obj == null || b64 == null) return errorReply(404, "file_object_not_found")
                200 to linkedMapOf(
                    "file" to f,
                    "object" to linkedMapOf(
                        "object_id" to objectId, "content_type" to (obj["content_type"] ?: ""), "file_name" to (obj["file_name"] ?: ""),
                        "size_bytes" to Base64.getDecoder().decode(b64).size, "sha256_hex" to obj["sha256_hex"], "encoding" to "base64", "base64_data" to b64,
                    ),
                )
            }

            method == "GET" && n == 1 && head == "resource-view-states" ->
                indexPage(state.rows("view_states").sortedBy { it["resource_id"] as? String ?: "" }, "states", url, mapOf("baseline_created" to false))
            method == "PUT" && n == 3 && head == "resource-view-states" -> {
                val key = "${p[1]}:${p[2]}"
                val row = state.row("view_states", key) ?: return errorReply(404, "resource_not_found")
                val requested = o["viewed_activity_version"].asInt() ?: -1
                if (requested < 0) return errorReply(400, "validation_error")
                val activity = row["activity_version"].asInt() ?: 0
                if (requested > activity) return errorReply(409, "activity_version_ahead")
                val viewed = maxOf(requested, row["viewed_activity_version"].asInt() ?: 0)
                row["viewed_activity_version"] = viewed.toLong()
                row["unread"] = activity > viewed
                row["attention_activity_version"].asInt()?.let { row["attention_unread"] = it > viewed }
                row["row_version"] = ((row["row_version"].asInt() ?: 1) + 1).toLong()
                state.put("view_states", key, row)
                200 to linkedMapOf("state" to row, "changed" to true)
            }
            method == "POST" && n == 2 && head == "resource-view-states" && p[1] == "mark-viewed" -> {
                val states = ArrayList<Row>()
                for (r in o["resources"].asList() ?: emptyList()) {
                    val m = r.asMap() ?: continue
                    val key = "${m["resource_type"].asString() ?: ""}:${m["resource_id"].asString() ?: ""}"
                    val row = state.row("view_states", key) ?: return errorReply(404, "resource_not_found")
                    row["viewed_activity_version"] = row["activity_version"]
                    row["unread"] = false
                    row["attention_unread"] = false
                    row["row_version"] = ((row["row_version"].asInt() ?: 1) + 1).toLong()
                    state.put("view_states", key, row)
                    states.add(row)
                }
                200 to linkedMapOf("states" to states, "changed_count" to states.size)
            }

            method == "GET" && n == 1 && head == "reactions" -> {
                val target = url.query("target_id") ?: ""
                200 to linkedMapOf("reactions" to state.rows("reactions").filter { it["target_id"] == target }, "next_cursor" to null)
            }
            method == "POST" && n == 1 && head == "reactions" -> {
                val emoji = o["emoji"].asString() ?: ""
                if (ReactionEmoji.from(emoji) == null) return errorReply(400, "validation_error")
                val id = newUUID()
                val row: Row = linkedMapOf(
                    "id" to id, "workspace_id" to workspaceId, "target_type" to (o["target_type"] ?: "message"), "target_id" to (o["target_id"] ?: ""),
                    "emoji" to emoji, "reactor_npub" to actor, "record_state" to "active", "row_version" to 1L, "updated_at" to now(),
                )
                state.put("reactions", id, row)
                emit(SampleChange("reaction", id, "created", rowVersion = 1, payload = mapOf("target_id" to (row["target_id"] as String))))
                201 to mapOf("reaction" to row)
            }
            method == "DELETE" && n == 2 && head == "reactions" -> {
                val row = state.row("reactions", p[1]) ?: return errorReply(404, "reaction_not_found")
                state.put("reactions", p[1], null)
                emit(SampleChange("reaction", p[1], "deleted", payload = mapOf("target_id" to (row["target_id"].asString() ?: ""))))
                200 to mapOf("reaction" to mapOf("id" to p[1], "record_state" to "deleted"))
            }

            method == "POST" && n == 2 && head == "storage" && p[1] == "prepare" -> {
                val type = o["content_type"].asString()
                if (type.isNullOrEmpty()) return errorReply(400, "validation_error")
                if (o["is_public"] == true) return errorReply(400, "validation_error")
                val id = newUUID()
                state.put(
                    "objects", id,
                    linkedMapOf(
                        "id" to id, "content_type" to type, "file_name" to o["file_name"], "size_bytes" to null, "sha256_hex" to null, "is_public" to false,
                        "completed_at" to null, "owner_npub" to actor,
                    ),
                )
                val presigned = if (state.data["fallback_uploads"] == true) "$towerOrigin/api/v4/storage/$id"
                else "https://$objectHost/bucket/$id?X-Amz-Signature=sample"
                201 to linkedMapOf("object_id" to id, "is_public" to false, "upload_url" to presigned, "content_type" to type)
            }
            else -> errorReply(404, "not_found")
        }
    }

    private fun storage(p: List<String>, method: String, o: Row): Reply {
        val id = p.firstOrNull()
        val row = id?.let { state.row("objects", it) } ?: return 404 to mapOf("error" to "storage object not found")
        return when {
            method == "GET" && p.size == 1 -> {
                row.remove("bytes")
                row["object_id"] = id
                200 to row
            }
            method == "PUT" && p.size == 1 -> {
                val b64 = o["base64_data"].asString()
                if (b64 == null || runCatching { Base64.getDecoder().decode(b64) }.isFailure) return 400 to mapOf("error" to "base64_data required")
                row["bytes"] = b64
                state.put("objects", id, row)
                200 to mapOf("object_id" to id)
            }
            method == "POST" && p.size == 2 && p[1] == "complete" -> {
                val bytes = row["bytes"].asString()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                    ?: return 409 to mapOf("error" to "storage upload missing")
                if (Sha256Hex.of(bytes) != o["sha256_hex"].asString() || o["size_bytes"].asInt() != bytes.size) {
                    return 409 to mapOf("error" to "storage SHA-256 mismatch")
                }
                row["sha256_hex"] = Sha256Hex.of(bytes)
                row["size_bytes"] = bytes.size.toString()
                row["completed_at"] = now()
                state.put("objects", id, row)
                200 to linkedMapOf("object_id" to id, "completed_at" to row["completed_at"], "size_bytes" to row["size_bytes"])
            }
            method == "GET" && p.size == 2 && p[1] == "content" -> {
                val bytes = row["bytes"].asString()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                if (bytes == null || row["completed_at"] !is String) return 409 to mapOf("error" to "incomplete")
                200 to bytes
            }
            else -> 404 to mapOf("error" to "not found")
        }
    }

    private fun docOut(row: Row): Row {
        val out = LinkedHashMap(row)
        out["body"] = linkedMapOf("object_id" to row["storage_object_id"], "route" to "/api/v4/flightdeck-pg/workspaces/$workspaceId/docs/${row["id"]}/body")
        return out
    }

    private fun bump(row: Row) {
        row["row_version"] = ((row["row_version"].asInt() ?: 1) + 1).toLong()
        row["activity_version"] = ((row["activity_version"].asInt() ?: 1) + 1).toLong()
        row["updated_at"] = now()
    }

    private fun emitTask(row: Row, operation: String) = emit(
        SampleChange("task", row["id"] as String, operation, row["channel_id"].asString(), row["scope_id"].asString(), row["row_version"].asInt()?.toLong()),
    )

    private fun emitDoc(row: Row, operation: String) = emit(
        SampleChange("doc", row["id"] as String, operation, row["channel_id"].asString(), row["scope_id"].asString()?.takeIf { it.isNotEmpty() },
            row["row_version"].asInt()?.toLong()),
    )

    private fun createTask(channel: String, o: Row): Reply {
        val title = (o["title"].asString() ?: "").trimWhitespace()
        if (title.isEmpty()) return validationError("title must be a non-empty string")
        val requestId = o["client_request_id"].asString()
        val hashed = LinkedHashMap(o).apply { remove("client_request_id") }
        val hash = Sha256Hex.of(jsonBody(toJsonElement(hashed) as JsonObject))
        if (requestId != null) {
            state.row("requests", requestId)?.let { prior ->
                val task = prior["task_id"].asString()?.let { state.row("tasks", it) }
                if (prior["hash"] != hash || task == null) return errorReply(409, "idempotency_conflict")
                return 200 to linkedMapOf("task" to task, "replayed" to true)
            }
        }
        val id = newUUID()
        val stamp = now()
        val assignee = o["assigned_to_npub"].asString()
        val row: Row = linkedMapOf(
            "id" to id, "workspace_id" to workspaceId, "scope_id" to null, "channel_id" to channel, "thread_id" to null, "title" to title,
            "description" to o["description"], "state" to (o["state"] ?: "new"), "priority" to (o["priority"] ?: "sand"),
            "metadata" to taskMetadata(o, assignee), "assigned_to_npub" to assignee, "row_version" to 1L, "activity_version" to 1L,
            "created_by_actor_npub" to actor, "sender_npub" to actor, "assignments" to emptyList<Any>(), "created_at" to stamp, "updated_at" to stamp,
        )
        state.put("tasks", id, row)
        if (requestId != null) state.put("requests", requestId, linkedMapOf("hash" to hash, "task_id" to id))
        emitTask(row, "created")
        return 201 to mapOf("task" to row)
    }

    private fun patchTask(id: String, o: Row): Reply {
        val row = state.row("tasks", id) ?: return errorReply(404, "task_not_found")
        // Tower checks row_version only when the assignee (or relationships) change.
        if (o.containsKey("assigned_to_npub")) {
            if (o["row_version"].asInt() == null || o["row_version"].asInt() != row["row_version"].asInt()) return errorReply(409, "stale_row_version")
            val npub = o["assigned_to_npub"].asString()?.takeIf { it.isNotEmpty() }
            row["assigned_to_npub"] = npub
            row["metadata"] = linkedMapOf("assigned_to_npub" to npub)
        }
        o["title"].asString()?.let { t ->
            if (t.trim().isEmpty()) return errorReply(400, "validation_error")
            row["title"] = t.trimWhitespace()
        }
        if (o.containsKey("description")) row["description"] = o["description"]?.let { it as? String ?: "" }
        o["priority"].asString()?.let { pr ->
            if (TaskPriority.from(pr) == null) return errorReply(400, "validation_error")
            row["priority"] = pr
        }
        bump(row)
        state.put("tasks", id, row)
        emitTask(row, "updated")
        return 200 to mapOf("task" to row)
    }

    private fun patchDoc(id: String, o: Row): Reply {
        val row = state.row("docs", id) ?: return errorReply(404, "doc_not_found")
        val objectId = o["storage_object_id"].asString()
        if (objectId == null) {
            o["title"].asString()?.let { row["title"] = it }
            bump(row)
            state.put("docs", id, row)
            emitDoc(row, "updated")
            return 200 to mapOf("doc" to docOut(row))
        }
        val submitted = state.row("objects", objectId)
        if (submitted == null || submitted["completed_at"] !is String) return errorReply(400, "validation_error")
        if (externalDocSave) {
            externalDocSave = false
            val other = DocumentBody.newDocument("Someone else's edit")
            row["storage_object_id"] = storeObject(other.bytes, other.contentType, "external.json")
            row["row_version"] = ((row["row_version"].asInt() ?: 1) + 1).toLong()
            state.put("docs", id, row)
        }
        val head = state.row("objects", row["storage_object_id"].asString() ?: "") ?: LinkedHashMap()
        val stale = o["row_version"].asInt() == null || o["row_version"].asInt() != row["row_version"].asInt() ||
            o["base_body_sha256_hex"].asString() != head["sha256_hex"].asString()
        if (stale) {
            val recovery = newUUID()
            state.put("recoveries", recovery, linkedMapOf("id" to recovery, "doc_id" to id, "storage_object_id" to objectId))
            return errorReply(409, "document_recovery_created", mapOf("recovery" to mapOf("id" to recovery), "canonical_advanced" to false))
        }
        val token = o["lease_token"].asString()
        if (state.rows("leases").none { it["entity_id"] == id && it["token"] == token && it["holder"] == actor }) return errorReply(409, "lease_required")
        row["storage_object_id"] = objectId
        o["title"].asString()?.let { row["title"] = it }
        o["summary"].asString()?.let { row["summary"] = it }
        bump(row)
        state.put("docs", id, row)
        emitDoc(row, "updated")
        return 200 to linkedMapOf(
            "doc" to docOut(row),
            "canonical_version" to linkedMapOf("row_version" to row["row_version"], "storage_object_id" to objectId, "body_sha256_hex" to submitted["sha256_hex"]),
        )
    }
}
