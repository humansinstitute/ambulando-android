package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.chat.threads
import studio.otherstuff.ambulando.core.transport.*

/** App-owned reference on a native thread/Doc/File. The containing record supplies workspace and access. */
object GoalAssociation {
    const val KEY = "ambulando_goal"

    fun read(metadata: JsonObject): String? {
        val value = metadata[KEY] ?: return null
        val o = value as? JsonObject ?: error("The goal association is invalid. Update or repair it before continuing.")
        require(o["version"] == JsonPrimitive(1)) { "This goal association needs a newer app." }
        val id = (o["goal_file_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(isUUID(id) && id == id?.lowercase()) { "The goal reference is invalid." }
        return id
    }

    fun metadata(goalFileId: String, base: JsonObject = JsonObject(emptyMap())): JsonObject {
        require(isUUID(goalFileId) && goalFileId == goalFileId.lowercase())
        val current = read(base)
        require(current == null || current == goalFileId) { "This record already belongs to another goal." }
        if (current == goalFileId) return base
        return JsonObject(base + (KEY to buildJsonObject { put("version", 1); put("goal_file_id", goalFileId) }))
    }
}

/** Recovery only: generic create has no idempotency key, so an uncertain start is looked up, never replayed. */
suspend fun TowerAPI.findGoalDiscussion(workspace: String, app: String, channelId: String, goalFileId: String, requestId: String): ThreadRecord? {
    require(isUUID(requestId) && isUUID(goalFileId))
    val listed = threads(workspace, app, channelId)
    val matches = listed.items.filter { thread ->
        thread.workspaceId == workspace && thread.channelId == channelId && !thread.deleted && !thread.archived &&
            thread.raw.obj("metadata")?.string("ambulando_discussion_request_id") == requestId &&
            GoalAssociation.read(thread.raw.obj("metadata")!!) == goalFileId
    }
    check(matches.size <= 1) { "Several discussions match this start. Open the channel and choose the original." }
    check(matches.isNotEmpty() || listed.complete) { "The server returned only the recent discussion window. The earlier start could not be found in this incomplete list; no second chat was created." }
    return matches.singleOrNull()
}

/** Read the primary association from authority, never from the selected screen or an explicit mention. */
suspend fun TowerAPI.threadGoalMetadata(workspace: String, app: String, threadId: String): JsonObject {
    val thread = thread(workspace, app, threadId)
    require(thread.workspaceId == workspace && thread.id == threadId && !thread.deleted && !thread.archived)
    val metadata = thread.raw.obj("metadata") ?: JsonObject(emptyMap())
    val id = GoalAssociation.read(metadata) ?: return JsonObject(emptyMap())
    // Verifies kind, content schema, digest and normal goal ACL before a new output is associated.
    try { readGoal(workspace, app, id) }
    catch (e: TowerError.Http) { if (e.status != 403) throw e }
    return GoalAssociation.metadata(id)
}

/** New native thread with its goal reference saved in the same create transaction. No visible boilerplate message. */
suspend fun TowerAPI.createGoalDiscussion(workspace: String, app: String, goalFileId: String, channelId: String,
    scopeId: String, expectedDefault: String?, requestId: String, beforeCreate: suspend () -> Unit = {}): ThreadRecord {
    require(isUUID(requestId))
    val goal = readGoal(workspace, app, goalFileId).goal
    check(goal.status != GoalStatus.ARCHIVED) { "Restore this goal before discussing it." }
    if (goal.defaultChannelId != expectedDefault) throw GoalError.Conflict("The default channel changed elsewhere. Reopen Discuss goal.")
    check(scopes(workspace, app).any { it.workspaceId == workspace && it.id == scopeId && !it.accessRemoved && it.raw.string("archived_at") == null && it.raw.string("deleted_at") == null && it.raw.string("record_state") != "deleted" }) { "This channel’s scope is unavailable." }
    val channel = channels(workspace, scopeId, app)
        .firstOrNull { it.workspaceId == workspace && it.id == channelId && it.scopeId == scopeId && !it.accessRemoved && it.raw.string("archived_at") == null && it.raw.string("deleted_at") == null && it.raw.string("record_state") != "deleted" }
        ?: error("This channel is unavailable. Choose an accessible channel explicitly.")
    val metadata = GoalAssociation.metadata(goalFileId, buildJsonObject { put("ambulando_discussion_request_id", requestId) })
    beforeCreate()
    val data = client.request(method = "POST", path = TowerAPI.base(workspace) + "/channels/${TowerAPI.uuid(channel.id)}/threads", appNpub = app,
        jsonBody = buildJsonObject { put("title", goal.name.take(120)); put("metadata", metadata) }.toString().toByteArray())
    val result = ThreadRecord.parse(parseJsonObject(data, "goal discussion").obj("thread") ?: throw TowerError.Malformed("goal discussion"))
    require(result.workspaceId == workspace && result.channelId == channelId && GoalAssociation.read(result.raw.obj("metadata") ?: JsonObject(emptyMap())) == goalFileId)
    return result
}
