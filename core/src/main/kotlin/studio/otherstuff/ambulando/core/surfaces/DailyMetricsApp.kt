package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*

/** Launcher identity contains no instance IDs or private tracker content. */
object DailyMetricsApp {
    const val ID = "daily-tracker"
    const val PLACEMENT_ID = "napplet:daily-tracker"
    const val TITLE = "Daily Metrics"
    fun path(workspace: String) = "/flight-deck?workspaceid=${TowerAPI.uuid(workspace)}&napplet=$ID"
}

/** A failed, malformed or truncated list is never evidence that setup is needed. No content is read here. */
suspend fun TowerAPI.dailyMetricsInstances(workspace: String, app: String, viewer: String): List<FileRecord> {
    val self = JsonArray(listOf(JsonPrimitive(viewer)))
    val conversations = scopes(workspace, app).onEach { require(it.workspaceId == workspace) }.filter { it.kind == "dm" }.flatMap { scope ->
        channels(workspace, scope.id, app).onEach { require(it.workspaceId == workspace && it.scopeId == scope.id) }
    }.filter { it.kind == "dm" && it.raw["participant_npubs"] == self }
    return conversations.flatMap { channel ->
        client.list(TowerAPI.base(workspace) + "/channels/${TowerAPI.uuid(channel.id)}/files", app, "files")
            .map(FileRecord::parse).onEach { require(it.workspaceId == workspace && it.channelId == channel.id) }
            .filter { !it.archived && it.raw.string("record_state") != "deleted" && it.raw.obj("metadata")?.obj("napplet")?.string("app_id") == DailyMetricsApp.ID }
    }.distinctBy { it.id }.sortedWith(compareBy({ it.createdAt }, { it.id }))
}
