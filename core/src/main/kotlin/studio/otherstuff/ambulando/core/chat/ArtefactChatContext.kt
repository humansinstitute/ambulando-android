package studio.otherstuff.ambulando.core.chat

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.transport.isUUID

/** Descriptive, untrusted app state. Routing and read authority always come from ChatTarget and existing ACLs. */
data class ArtefactChatReference(val kind: String, val recordID: String, val revision: String, val historical: Boolean, val digest: String) {
    val revisionKind get() = if (kind == "doc") "doc_versions.row_version" else "file_version_id"
    fun json() = buildJsonObject {
        put("kind", kind); put("recordID", recordID); put("revision", revision); put("revisionKind", revisionKind)
        put("historical", historical); put("digest", digest)
    }
}
object ArtefactChatContext {
    const val KEY = "ambulando_artefact_context"
    const val FAILURE = "An artefact revision is unavailable. Resolve its save or access error, then retry Send. Your draft is retained."
    fun encode(target: ChatTarget, visible: Boolean, selected: String, refs: List<ArtefactChatReference>): String = buildJsonObject {
        put("schemaVersion", 1); put("workspaceID", target.workspaceId); put("channelID", target.channelId); put("threadID", target.threadId)
        put("workspaceVisible", visible); put("visibleRecordID", selected); put("open", JsonArray(refs.map { it.json() }))
    }.toString().also { validate(it, target) }
    fun validate(raw: String, target: ChatTarget): JsonObject {
        require(raw.toByteArray().size <= 16384) { FAILURE }
        val o = Json.parseToJsonElement(raw).jsonObject
        require(o.keys == setOf("schemaVersion", "workspaceID", "channelID", "threadID", "workspaceVisible", "visibleRecordID", "open")) { FAILURE }
        fun text(key: String) = o[key]?.jsonPrimitive?.takeIf { it.isString }?.content
        require(o["schemaVersion"] == JsonPrimitive(1) && text("workspaceID") == target.workspaceId && text("channelID") == target.channelId && text("threadID") == target.threadId) { FAILURE }
        require(listOf(target.workspaceId, target.channelId, text("visibleRecordID")).all { it != null && isUUID(it) }) { FAILURE }
        // A first message has no server thread yet. Bind null explicitly; never accept a missing or foreign thread.
        require(if (target.threadId == null) o["threadID"] == JsonNull else isUUID(target.threadId)) { FAILURE }
        require(o["workspaceVisible"] in listOf(JsonPrimitive(true), JsonPrimitive(false))) { FAILURE }
        val refs = o.getValue("open").jsonArray
        require(refs.size in 1..32) { FAILURE }
        val ids = refs.map { value ->
            val r = value.jsonObject
            require(r.keys == setOf("kind", "recordID", "revision", "revisionKind", "historical", "digest")) { FAILURE }
            fun s(k: String) = r[k]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error(FAILURE)
            val id = s("recordID"); val rev = s("revision")
            require(isUUID(id) && s("digest").matches(Regex("[0-9a-f]{64}")) && r["historical"] in listOf(JsonPrimitive(true), JsonPrimitive(false))) { FAILURE }
            require(when (s("kind")) {
                "file" -> s("revisionKind") == "file_version_id" && isUUID(rev)
                "doc" -> s("revisionKind") == "doc_versions.row_version" && rev.toLongOrNull()?.let { it in 1..9007199254740991L && it.toString() == rev } == true
                else -> false
            }) { FAILURE }
            id.lowercase()
        }
        require(ids.distinct().size == ids.size && text("visibleRecordID")?.lowercase() in ids) { FAILURE }
        return o
    }
}
