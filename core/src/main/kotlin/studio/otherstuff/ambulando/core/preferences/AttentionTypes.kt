package studio.otherstuff.ambulando.core.preferences

import kotlinx.serialization.json.*

/** Actor/workspace presentation preference shared by Android, web, iPhone and Mac. Never a delivery/read rule. */
data class AttentionTypes(val raw: JsonObject = buildJsonObject { put("version", 1); put("disabled", JsonArray(emptyList())) }) {
    val supported: Boolean get() = (raw["version"] as? JsonPrimitive)?.let { !it.isString && it.intOrNull == 1 } == true &&
        (raw["disabled"] as? JsonArray)?.all { it is JsonPrimitive && it.isString } == true
    val disabled: Set<String> get() = if (supported) (raw["disabled"] as JsonArray).map { it.jsonPrimitive.content }.toSet() else emptySet()
    fun includes(type: String) = type !in TYPES || type !in disabled
    fun showsNotification(view: String, category: String?, targetKind: String?): Boolean =
        (view != "unread" && category !in setOf("mention", "comment_tag")) || includes(notificationType(targetKind))
    fun showsActivity(unread: Boolean, type: String) = !unread || includes(type)
    fun withEnabled(type: String, on: Boolean): AttentionTypes {
        require(supported && type in TYPES) { "Refresh these settings with a compatible app before changing them." }
        val next = if (on) disabled - type else disabled + type
        return AttentionTypes(JsonObject(raw + ("disabled" to JsonArray(next.sorted().map(::JsonPrimitive)))))
    }
    fun encode() = raw.toString()
    companion object {
        const val KEY = "ambulando.attention-types"
        val TYPES = listOf("chat", "document", "task", "file", "other")
        fun decode(value: String?): AttentionTypes = value?.let { runCatching { AttentionTypes(Json.parseToJsonElement(it).jsonObject) }.getOrNull() }
            ?: if (value == null) AttentionTypes() else AttentionTypes(JsonObject(emptyMap()))
        fun notificationType(kind: String?): String = when (kind) {
            "thread", "channel", "chat", "message", "dm" -> "chat"
            "doc", "document", "doc_comment", "document_comment" -> "document"
            "task", "task_comment" -> "task"
            "file", "file_comment" -> "file"
            else -> "other"
        }
    }
}
