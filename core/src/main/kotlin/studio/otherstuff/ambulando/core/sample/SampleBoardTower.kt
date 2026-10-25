package studio.otherstuff.ambulando.core.sample

import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.surfaces.BoardScene
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.TowerTime
import java.io.File
import java.util.Base64

/**
 * In-process stand-in for Tower's board File version routes (`routes/flightdeck-pg.ts`), for sample data and tests:
 *
 * - `GET  /files/:f/versions` (newest first, with `current_version_id`)
 * - `GET  /files/:f/versions/:v/object`
 * - `PATCH /files/:f` with `display_name` (rename; the canvas shows the new name)
 * - `POST /files/:f/versions` with `base_version_id` and a completed `storage_object_id` (409 `stale_base_version`
 *   unless the base is the current version; 403 without write access)
 *
 * It also keeps the Canvas snapshot's projection of each board current ([project]), as Tower re-projects a naplet's
 * board after a save. Only boards seeded here are owned; other `/files` routes fall through.
 */
class SampleBoardTower(
    workspaceId: String,
    file: File? = null,
    private val auth: SampleAuth = SampleAuth(),
) {
    private val lock = Any()
    val workspaceId: String = workspaceId.lowercase()
    private val state = SampleState(file, listOf("boards"))
    private val log = ArrayList<String>()
    private var externalSave = false

    /** Bytes of a completed storage object (the work stand-in's object store). */
    @Volatile var objectBytes: ((String) -> ByteArray?)? = null

    /** Committed saves and renames, reported after each request. */
    @Volatile var onChange: ((SampleChange) -> Unit)? = null

    /** Someone else saves (a new rectangle) just before this device's next save, which then meets a stale base. */
    var externalSaveBeforeNextPublish: Boolean
        get() = synchronized(lock) { externalSave }
        set(value) = synchronized(lock) { externalSave = value }

    val requestLog: List<String> get() = synchronized(lock) { log.toList() }

    private fun board(fileId: String): Row? = state.row("boards", fileId)

    private fun versions(fileId: String): List<Row> = board(fileId)?.get("versions").asList()?.mapNotNull { SampleJson.row(it) } ?: emptyList()

    private fun append(fileId: String, bytes: ByteArray, author: String, summary: String?): Row {
        val board: Row = board(fileId) ?: linkedMapOf("can_write" to true, "versions" to emptyList<Any>())
        val rows = versions(fileId).toMutableList()
        val row: Row = linkedMapOf(
            "id" to newUUID(), "file_id" to fileId, "version_number" to (rows.size + 1).toLong(), "created_at" to TowerTime.now(),
            "created_by_actor_npub" to author, "summary" to summary, "size_bytes" to bytes.size.toLong(), "sha256_hex" to Sha256Hex.of(bytes),
            "content_type" to BoardScene.CONTENT_TYPE, "bytes" to Base64.getEncoder().encodeToString(bytes),
        )
        rows.add(row)
        board["versions"] = rows
        state.put("boards", fileId, board)
        return row
    }

    private fun bytesOf(row: Map<String, Any?>?): ByteArray? = row?.get("bytes").asString()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** A board this stand-in owns, starting from [bytes] as version 1. Seeding an existing board does nothing. */
    fun seed(fileId: String, bytes: ByteArray, canWrite: Boolean, author: String) = synchronized(lock) {
        val id = fileId.lowercase()
        if (board(id) != null) return@synchronized
        append(id, bytes, author, null)
        val b = board(id)!!
        b["can_write"] = canWrite
        state.put("boards", id, b)
        state.persist()
    }

    /** Someone else saves a new version (conflict testing). */
    fun externalSave(fileId: String, bytes: ByteArray, author: String) = synchronized(lock) {
        append(fileId.lowercase(), bytes, author, "external")
        state.persist()
    }

    fun versionCount(fileId: String): Int = synchronized(lock) { versions(fileId.lowercase()).size }
    fun currentBytes(fileId: String): ByteArray? = synchronized(lock) { bytesOf(versions(fileId.lowercase()).lastOrNull()) }

    private fun publicRow(row: Map<String, Any?>): Row = LinkedHashMap(row).apply { remove("bytes") }

    /** The ready naplet projection of a board (Tower `content` for `source.type == "excalidraw"`). */
    private fun projection(fileId: String): Row? {
        val head = versions(fileId).lastOrNull() ?: return null
        return linkedMapOf(
            "file_id" to fileId, "current_version_id" to head["id"], "current_version_number" to head["version_number"],
            "content_type" to BoardScene.CONTENT_TYPE, "size_bytes" to head["size_bytes"], "sha256" to head["sha256_hex"], "updated_at" to head["created_at"],
            "updated_by_actor_npub" to head["created_by_actor_npub"], "can_write" to (board(fileId)?.get("can_write") == true),
        )
    }

    fun handle(request: HttpRequest): Reply? {
        val url = SampleURL.of(request) ?: return null
        val base = "/api/v4/flightdeck-pg/workspaces/$workspaceId/files/"
        if (!url.path.startsWith(base)) return null
        val p = url.path.removePrefix(base).split('/').filter { it.isNotEmpty() }
        val method = request.method.uppercase()
        if (!((p.size >= 2 && p[1] == "versions") || (p.size == 1 && method == "PATCH"))) return null
        val fileId = p[0].lowercase()
        if (synchronized(lock) { board(fileId) } == null) return null
        val author = when (val result = auth.verify(request)) {
            is SampleAuth.Result.Invalid -> return SampleAuth.unauthorized(result.reason)
            is SampleAuth.Result.Valid -> result.npub
        }
        val o = SampleJson.objectOf(request.body)
        var change: SampleChange? = null
        val reply: Reply = synchronized(lock) {
            log.add("$method ${url.path}")
            val canWrite = board(fileId)?.get("can_write") == true
            when {
                method == "PATCH" && p.size == 1 -> {
                    if (!canWrite) return@synchronized errorReply(403, "permission_denied")
                    val name = o["display_name"].asString()?.trimWhitespace()
                    if (name.isNullOrEmpty()) return@synchronized errorReply(400, "validation_error")
                    val b = board(fileId)!!
                    b["title"] = name
                    state.put("boards", fileId, b)
                    state.persist()
                    change = SampleChange("file", fileId, "updated")
                    200 to mapOf("file" to linkedMapOf("id" to fileId, "display_name" to name))
                }
                method == "GET" && p.size == 2 -> {
                    val rows = versions(fileId)
                    200 to linkedMapOf("file_id" to fileId, "current_version_id" to rows.lastOrNull()?.get("id"), "versions" to rows.reversed().map { publicRow(it) })
                }
                method == "GET" && p.size == 4 && p[3] == "object" -> {
                    bytesOf(versions(fileId).firstOrNull { it["id"] == p[2] })?.let { 200 to it } ?: errorReply(404, "file_version_object_not_found")
                }
                method == "POST" && p.size == 2 -> {
                    if (!canWrite) return@synchronized errorReply(403, "permission_denied")
                    if (externalSave) {
                        externalSave = false
                        val sceneBytes = bytesOf(versions(fileId).lastOrNull())
                        val scene = sceneBytes?.let { SampleJson.objectOf(it) }
                        if (scene != null && scene.isNotEmpty()) {
                            val other: Row = linkedMapOf(
                                "id" to "external-${newUUID().take(8)}", "type" to "rectangle", "version" to 1, "versionNonce" to 1, "x" to -400, "y" to -300,
                                "width" to 160, "height" to 90, "angle" to 0, "strokeColor" to "#e03131", "backgroundColor" to "transparent",
                                "fillStyle" to "solid", "strokeWidth" to 2, "roughness" to 1, "opacity" to 100, "isDeleted" to false, "seed" to 7,
                                "groupIds" to emptyList<Any>(), "boundElements" to null,
                            )
                            scene["elements"] = (scene["elements"].asList() ?: emptyList()) + other
                            append(fileId, SampleJson.encode(scene), "external", "external")
                        }
                    }
                    val current = versions(fileId).lastOrNull() ?: return@synchronized errorReply(404, "file_not_found")
                    if (o["base_version_id"].asString() != current["id"]) {
                        return@synchronized errorReply(409, "stale_base_version", mapOf("current_version" to publicRow(current)))
                    }
                    val bytes = o["storage_object_id"].asString()?.let { objectBytes?.invoke(it) }
                        ?: return@synchronized errorReply(400, "storage_object_unavailable")
                    val row = append(fileId, bytes, author, o["summary"].asString())
                    state.persist()
                    change = SampleChange("file", fileId, "updated")
                    201 to mapOf("version" to publicRow(row))
                }
                else -> errorReply(405, "method_not_allowed")
            }
        }
        change?.let { c -> onChange?.invoke(c) }
        return reply
    }

    /** Re-projects the boards this stand-in owns inside a Canvas snapshot response. */
    fun project(reply: Reply, request: HttpRequest): Reply {
        val url = SampleURL.of(request) ?: return reply
        val o = reply.second.asMap()
        if (reply.first != 200 || !url.path.endsWith("/snapshot") || o == null) return reply
        val naplets = o["naplets"].asList() ?: return reply
        val out = LinkedHashMap(o)
        out["naplets"] = synchronized(lock) {
            naplets.map { n ->
                val m = n.asMap() ?: return@map n
                val source = m["source"].asMap()
                val id = source?.get("id").asString()?.lowercase()
                val content = if (source?.get("type") == "excalidraw" && id != null) projection(id) else null
                if (content == null) n else LinkedHashMap(m).apply {
                    put("content", content)
                    board(id!!)?.get("title").asString()?.let { put("title", it) }
                }
            }
        }
        return reply.first to out
    }
}
