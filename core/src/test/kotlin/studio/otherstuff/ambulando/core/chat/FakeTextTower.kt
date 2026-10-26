package studio.otherstuff.ambulando.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.toJsonElement
import java.util.Base64
import java.util.UUID

/** In-memory stand-in for the storage, channel Files and file-versions routes text attachment edits use. */
class FakeTextTower(val workspace: String = Fixtures.WORKSPACE_ID, val channel: String = Fixtures.CHANNEL_1) : HttpTransport {
    class Obj(val id: String, val sha: String, val size: Long, var bytes: ByteArray? = null, var completed: Boolean = false)
    class Version(val id: String, val number: Int, val objectId: String, val base: String?)
    class FileRow(val id: String, var name: String, val lineage: Map<String, String>, val versions: MutableList<Version> = mutableListOf(), var archived: Boolean = false)

    val objects = LinkedHashMap<String, Obj>()
    val files = LinkedHashMap<String, FileRow>()
    val requests = ArrayList<HttpRequest>()
    /** Messages Tower holds, with `metadata.attachments` as sent. */
    val messageAttachments = HashMap<String, List<JsonObject>>()
    var dropReplyOnNext: ((HttpRequest) -> Boolean)? = null
    var offline = false
    var denyWrites = false
    var transientCreates = 0
    var transientRenames = 0

    fun count(method: String, suffix: String) = requests.count { it.method == method && it.url.substringBefore('?').endsWith(suffix) }

    fun putObject(bytes: ByteArray): String {
        val id = UUID.randomUUID().toString()
        objects[id] = Obj(id, Sha256Hex.of(bytes), bytes.size.toLong(), bytes, true)
        return id
    }

    private fun json(status: Int, body: Any?) = HttpResponse(status, toJsonElement(body).toString().toByteArray())
    private fun error(status: Int, code: String, extra: Map<String, Any?> = emptyMap()) = json(status, mapOf("error" to code, "code" to code, "status" to status) + extra)

    private fun versionJson(f: FileRow, v: Version) = mapOf(
        "id" to v.id, "workspace_id" to workspace, "file_id" to f.id, "version_number" to v.number, "storage_object_id" to v.objectId,
        "size_bytes" to objects[v.objectId]?.size, "content_type" to "text/plain; charset=utf-8", "sha256_hex" to objects[v.objectId]?.sha,
        "base_version_id" to v.base, "operation" to "replace", "created_at" to "2026-10-26T00:00:0${v.number}.000Z",
    )

    private fun fileJson(f: FileRow) = mapOf(
        "id" to f.id, "workspace_id" to workspace, "channel_id" to channel, "display_name" to f.name,
        "storage_object_id" to f.versions.firstOrNull()?.objectId, "current_version_id" to f.versions.lastOrNull()?.id,
        "metadata" to mapOf("text_attachment_lineage" to f.lineage), "row_version" to f.versions.size, "created_at" to "2026-10-26T00:00:00.000Z",
        "record_state" to if (f.archived) "archived" else "active",
        "current_version" to f.versions.lastOrNull()?.let { versionJson(f, it) },
    )

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        if (offline) throw java.net.ConnectException("offline")
        val response = route(request)
        if (dropReplyOnNext?.invoke(request) == true) { dropReplyOnNext = null; throw java.net.SocketTimeoutException("lost response") }
        return response
    }

    private fun route(request: HttpRequest): HttpResponse {
        val path = request.url.substringAfter("//").substringAfter('/').substringBefore('?').let { "/$it" }
        val method = request.method
        val body: JsonObject? = request.body?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(String(it)).jsonObject }.getOrNull() }
        val base = "/api/v4/flightdeck-pg/workspaces/$workspace"
        when {
            method == "POST" && path == "$base/storage/prepare" -> {
                val id = UUID.randomUUID().toString()
                objects[id] = Obj(id, body!!["sha256_hex"]!!.jsonPrimitive.content, body["size_bytes"]!!.jsonPrimitive.content.toLong())
                return json(200, mapOf("object_id" to id, "upload_url" to "${Fixtures.ORIGIN}/api/v4/storage/$id", "is_public" to false))
            }
            path.startsWith("/api/v4/storage/") -> {
                val rest = path.removePrefix("/api/v4/storage/").split('/')
                val o = objects[rest[0]] ?: return error(404, "not_found")
                return when {
                    method == "PUT" -> { o.bytes = Base64.getDecoder().decode(body!!["base64_data"]!!.jsonPrimitive.content); json(200, mapOf("ok" to true)) }
                    method == "POST" && rest.getOrNull(1) == "complete" -> {
                        if (o.bytes == null) error(409, "upload_missing") else { o.completed = true; json(200, mapOf("completed_at" to "2026-10-26T00:00:00.000Z")) }
                    }
                    method == "GET" && rest.getOrNull(1) == "content" -> HttpResponse(200, o.bytes ?: ByteArray(0))
                    method == "GET" -> json(200, mapOf("id" to o.id, "completed_at" to if (o.completed) "2026-10-26T00:00:00.000Z" else null,
                        "sha256_hex" to o.sha, "size_bytes" to o.size, "is_public" to false))
                    else -> error(400, "bad")
                }
            }
            method == "GET" && path == "$base/channels/$channel/files" -> {
                val query = java.net.URI(request.url).query.orEmpty().split('&').mapNotNull {
                    val parts = it.split('=', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
                val rows = files.values.filter { f ->
                    f.archived == (query["archived"] == "true") &&
                        (query["origin_message_id"] == null || f.lineage["origin_message_id"] == query["origin_message_id"]) &&
                        (query["origin_storage_object_id"] == null || f.lineage["origin_storage_object_id"] == query["origin_storage_object_id"])
                }.take(query["limit"]?.toIntOrNull() ?: 50)
                return json(200, mapOf("files" to rows.map(::fileJson), "next_cursor" to null))
            }
            method == "POST" && path == "$base/channels/$channel/files" -> {
                if (denyWrites) return error(403, "forbidden")
                if (transientCreates > 0) { transientCreates--; return error(503, "unavailable") }
                val objectId = body!!["storage_object_id"]!!.jsonPrimitive.content
                val lineage = body["metadata"]!!.jsonObject["text_attachment_lineage"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                val existing = files.values.firstOrNull { it.lineage["origin_message_id"] == lineage["origin_message_id"] && it.lineage["origin_storage_object_id"] == lineage["origin_storage_object_id"] }
                if (existing != null) {
                    val payload = mapOf("file" to fileJson(existing), "current_version" to existing.versions.lastOrNull()?.let { versionJson(existing, it) }, "lineage" to lineage)
                    return if (existing.versions.any { it.objectId == objectId }) json(200, payload + ("idempotent_replay" to true)) else error(409, "lineage_file_exists", payload)
                }
                val f = FileRow(UUID.randomUUID().toString(), body["display_name"]!!.jsonPrimitive.content, lineage)
                f.versions.add(Version(UUID.randomUUID().toString(), 1, objectId, null))
                files[f.id] = f
                return json(201, mapOf("file" to fileJson(f).minus("current_version")))
            }
        }
        val fileMatch = Regex("""$base/files/([0-9a-f-]+)""").matchEntire(path)
        if (method == "PATCH" && fileMatch != null) {
            val f = files[fileMatch.groupValues[1]] ?: return error(404, "file_not_found")
            if (denyWrites) return error(403, "forbidden")
            if (transientRenames > 0) { transientRenames--; return error(503, "unavailable") }
            if (body?.get("row_version")?.jsonPrimitive?.content?.toLongOrNull() != f.versions.size.toLong())
                return error(409, "stale_row_version", mapOf("file" to fileJson(f)))
            f.name = body["display_name"]!!.jsonPrimitive.content
            return json(200, mapOf("file" to fileJson(f)))
        }
        val m = Regex("""$base/files/([0-9a-f-]+)/versions(?:/([0-9a-f-]+)/object)?""").matchEntire(path)
        if (m != null) {
            val f = files[m.groupValues[1]] ?: return error(404, "file_not_found")
            val versionId = m.groupValues[2]
            if (versionId.isNotEmpty()) {
                val v = f.versions.firstOrNull { it.id == versionId } ?: return error(404, "file_version_object_not_found")
                return HttpResponse(200, objects[v.objectId]!!.bytes!!)
            }
            if (method == "GET") return json(200, mapOf("file_id" to f.id, "current_version_id" to f.versions.lastOrNull()?.id, "versions" to f.versions.map { versionJson(f, it) }, "next_cursor" to null))
            if (method == "POST") {
                if (denyWrites) return error(403, "forbidden")
                val baseId = body!!["base_version_id"]!!.jsonPrimitive.content
                val objectId = body["storage_object_id"]!!.jsonPrimitive.content
                if (f.versions.last().id != baseId) return error(409, "stale_base_version", mapOf("file" to fileJson(f), "current_version" to versionJson(f, f.versions.last())))
                if (f.versions.any { it.objectId == objectId }) return error(409, "file_storage_object_conflict")
                val v = Version(UUID.randomUUID().toString(), f.versions.size + 1, objectId, baseId)
                f.versions.add(v)
                return json(201, mapOf("file" to fileJson(f), "version" to versionJson(f, v)))
            }
        }
        return error(404, "no_route:$method $path")
    }
}
