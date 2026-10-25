package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

/**
 * One Tower outbox event (`serializeFlightDeckPgEvent`), as delivered by `GET /workspaces/:w/events` and the
 * `flightdeck_pg.event` frames of `GET /workspaces/:w/events/stream`. Only the fields the client acts on are kept.
 */
data class TowerEvent(
    val rowVersion: Long,
    val cursor: String,
    val workspaceId: String,
    val entityType: String,
    val entityId: String?,
    val operation: String,
    val scopeId: String? = null,
    val channelId: String? = null,
    /** Parent ids some events carry in `payload` (thread of a message, task of a comment, target of a reaction). */
    val threadId: String? = null,
    val parentId: String? = null,
    /** The changed record's `row_version` after the change, when Tower reports it. */
    val entityRowVersion: Long? = null,
    /** Canvas of a naplet/element event, and component of a Context Tree reference event (`payload`). */
    val canvasId: String? = null,
    val componentId: String? = null,
) {
    companion object {
        private val PARENT_KEYS = listOf("task_id", "doc_id", "target_id", "message_id", "file_id")

        /** Parses one serialized event. Ids are lower-cased UUIDs; anything else is dropped so it can never reach a path. */
        fun parse(o: JsonObject): TowerEvent? {
            val row = flexibleLong(o["row_version"]) ?: return null
            val cursor = o.string("cursor")?.takeIf { it.isNotEmpty() } ?: return null
            val workspace = lowerUUID(o.string("workspace_id")) ?: return null
            val type = o.string("entity_type") ?: return null
            val operation = o.string("operation") ?: return null
            val payload = o.obj("payload") ?: JsonObject(emptyMap())
            val parent = PARENT_KEYS.firstNotNullOfOrNull { lowerUUID(payload.string(it)) }
            return TowerEvent(
                rowVersion = row, cursor = cursor, workspaceId = workspace, entityType = type, entityId = lowerUUID(o.string("entity_id")),
                operation = operation, scopeId = lowerUUID(o.string("scope_id")) ?: lowerUUID(payload.string("scope_id")),
                channelId = lowerUUID(o.string("channel_id")) ?: lowerUUID(payload.string("channel_id")),
                threadId = lowerUUID(payload.string("thread_id")), parentId = parent, entityRowVersion = flexibleLong(o["entity_row_version"]),
                canvasId = lowerUUID(payload.string("canvas_id")), componentId = lowerUUID(payload.string("component_id")),
            )
        }
    }
}

/** One Server-Sent Events frame. */
data class SSEFrame(val event: String, val data: String, val id: String? = null)

/**
 * Incremental `text/event-stream` parser (WHATWG rules: LF, CR or CRLF line ends, `:` comments, multi-line `data`).
 * Bytes are fed in arbitrary chunks; a frame is emitted only at its terminating blank line. Not thread-safe: one
 * parser per connection, used by one reader.
 */
class SSEParser {
    private val buffer = ByteArrayOutputStream()
    private var event = ""
    private val data = ArrayList<String>()
    private var id: String? = null
    private var sawCR = false

    /** Comment lines (`: heartbeat`) seen since the last [takeComments]; they prove the connection is alive. */
    var comments = 0
        private set

    class LineTooLong : Exception("An event-stream line exceeded ${MAX_LINE} bytes.")

    fun feed(chunk: ByteArray): List<SSEFrame> {
        val frames = ArrayList<SSEFrame>()
        for (byte in chunk) {
            if (sawCR) {
                sawCR = false
                if (byte == LF) continue
            }
            if (byte == LF || byte == CR) {
                if (byte == CR) sawCR = true
                line(buffer.toString(Charsets.UTF_8))?.let { frames.add(it) }
                buffer.reset()
            } else {
                buffer.write(byte.toInt())
                if (buffer.size() > MAX_LINE) throw LineTooLong()
            }
        }
        return frames
    }

    fun takeComments(): Int = comments.also { comments = 0 }

    private fun line(text: String): SSEFrame? {
        if (text.isEmpty()) {
            val frame = if (data.isEmpty()) null else SSEFrame(event.ifEmpty { "message" }, data.joinToString("\n"), id)
            event = ""
            data.clear()
            id = null
            return frame
        }
        if (text.startsWith(":")) {
            comments += 1
            return null
        }
        val colon = text.indexOf(':')
        val field: String
        val value: String
        if (colon >= 0) {
            field = text.substring(0, colon)
            val rest = text.substring(colon + 1)
            value = if (rest.startsWith(" ")) rest.substring(1) else rest
        } else {
            field = text
            value = ""
        }
        when (field) {
            "event" -> event = value
            "data" -> data.add(value)
            "id" -> if (!value.contains('\u0000')) id = value
        }
        return null
    }

    companion object {
        /** Upper bound on one unterminated line, so a hostile stream cannot grow memory without limit. */
        const val MAX_LINE = 4 * 1024 * 1024
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
    }
}
