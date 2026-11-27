package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.isUUID

/**
 * A durable receipt for one possibly committed discussion start. It records the original routing only; reading it
 * neither retries creation nor clears the receipt. The caller resolves the request against the original channel.
 */
data class GoalDiscussionReceipt(
    val workspaceId: String,
    val goalFileId: String,
    val channelId: String,
    val requestId: String,
) {
    init {
        require(listOf(workspaceId, goalFileId, channelId, requestId).all(::canonicalId)) { "The pending discussion receipt contains an invalid identity." }
    }

    fun encode(): String = buildJsonObject {
        put("format", FORMAT); put("version", VERSION)
        put("workspace_id", workspaceId); put("goal_file_id", goalFileId)
        put("channel_id", channelId); put("request_id", requestId)
    }.toString()

    companion object {
        private const val FORMAT = "ambulando.goal_discussion_pending"
        private const val VERSION = 1
        private val FIELDS = setOf("format", "version", "workspace_id", "goal_file_id", "channel_id", "request_id")
        private fun canonicalId(value: String) = isUUID(value) && value == value.lowercase()

        /** A receipt from another workspace/goal or a future/damaged format is refused, never redirected or rewritten. */
        fun parse(raw: String, workspaceId: String, goalFileId: String): GoalDiscussionReceipt {
            require(canonicalId(workspaceId) && canonicalId(goalFileId)) { "The pending discussion target is invalid." }
            require(raw.toByteArray(Charsets.UTF_8).size <= 4096) { "The pending discussion receipt is too large." }
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("The pending discussion receipt is invalid.")
            require(root.keys == FIELDS && root["format"] == JsonPrimitive(FORMAT) && root["version"] == JsonPrimitive(VERSION)) { "The pending discussion receipt format is unavailable." }
            fun id(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("The pending discussion receipt contains an invalid identity.")
            val receipt = GoalDiscussionReceipt(id("workspace_id"), id("goal_file_id"), id("channel_id"), id("request_id"))
            require(receipt.workspaceId == workspaceId && receipt.goalFileId == goalFileId) { "The pending discussion belongs to a different goal or workspace." }
            return receipt
        }

        /** Connection binding stays in the local key, alongside the authoritative workspace and goal identity. */
        fun key(connectionId: String, workspaceId: String, goalFileId: String): String {
            require(listOf(connectionId, workspaceId, goalFileId).all(::canonicalId)) { "The pending discussion storage target is invalid." }
            return "goal.discussion.pending.$connectionId.$workspaceId.$goalFileId"
        }
    }
}
