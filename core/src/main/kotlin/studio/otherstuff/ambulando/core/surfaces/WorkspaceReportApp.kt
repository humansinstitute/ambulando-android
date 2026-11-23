package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import studio.otherstuff.ambulando.core.transport.*

suspend fun TowerAPI.workspaceReportInstances(workspace: String, app: String, viewer: String): List<FileRecord> {
    val self = JsonArray(listOf(JsonPrimitive(viewer)))
    val conversations = scopes(workspace, app).onEach { require(it.workspaceId == workspace) }.filter { it.kind == "dm" && it.raw.bool("is_archived")!=true && it.raw.string("record_state")!="deleted" }.flatMap { scope ->
        channels(workspace, scope.id, app).onEach { require(it.workspaceId == workspace && it.scopeId == scope.id) }
    }.filter { it.raw.bool("is_archived")!=true && it.raw.string("record_state")!="deleted" && it.kind == "dm" && it.raw["participant_npubs"] == self }
    val found = conversations.flatMap { channel ->
        client.list(TowerAPI.base(workspace) + "/channels/${TowerAPI.uuid(channel.id)}/files", app, "files")
            .map(FileRecord::parse).onEach { require(it.workspaceId == workspace && it.channelId == channel.id) }
            .filter { !it.archived && it.raw.string("record_state") != "deleted" && it.raw.obj("metadata")?.obj("napplet")?.string("app_id") == WorkspaceReport.ID }
    }
    require(found.map { it.id }.distinct().size==found.size)
    return found.sortedWith(compareBy({ it.createdAt }, { it.id }))
}

data class CreatedWorkspaceReport(val code: FileRecord, val channel: ChannelRecord, val scope: ScopeRecord)

suspend fun TowerAPI.createWorkspaceReport(workspace: String, app: String, viewer: String, html: String): CreatedWorkspaceReport {
    val base = TowerAPI.base(workspace)
    suspend fun post(path: String, body: JsonObject): JsonObject = Json.parseToJsonElement(
        client.request(method = "POST", path = path, appNpub = app, jsonBody = jsonBody(body)).toString(Charsets.UTF_8),
    ).jsonObject
    val dmScope = scopes(workspace, app).firstOrNull { it.kind == "dm" }
        ?: ScopeRecord.parse(post("$base/scopes", jsonObjectOf("name" to "DMs", "description" to "Direct message conversations", "kind" to "dm")).getValue("scope").jsonObject)
    fun self(channel: ChannelRecord) = channel.workspaceId == workspace && channel.kind == "dm" &&
        channel.raw["participant_npubs"] == JsonArray(listOf(JsonPrimitive(viewer)))
    val channel = channels(workspace, dmScope.id, app).firstOrNull(::self) ?: run {
        val request = studio.otherstuff.ambulando.core.management.ScopeChannelRequests.createDirectMessage(workspace, dmScope.id, viewer, viewer)
        ChannelRecord.parse(post(request.path, request.body!!).getValue("channel").jsonObject)
    }
    require(self(channel))
    suspend fun create(name: String, bytes: ByteArray, type: String, metadata: JsonObject): FileRecord {
        currentCoroutineContext().ensureActive()
        val objectId = store(workspace, app, bytes, type, name, null) { }
        currentCoroutineContext().ensureActive()
        val file = FileRecord.parse(post("$base/channels/${TowerAPI.uuid(channel.id)}/files", buildJsonObject {
            put("storage_object_id", objectId); put("display_name", name); put("metadata", metadata)
        }).getValue("file").jsonObject)
        require(file.workspaceId == workspace && file.channelId == channel.id)
        return file
    }
    val code = create("Workspace Report.html", html.toByteArray(), "text/html", buildJsonObject {
        put("html_artifact", true); put("artefact", ArtefactType.HTML.metadata().getValue("artefact"))
        put("napplet", buildJsonObject { put("version", 1); put("app_id", WorkspaceReport.ID); put("bindings", buildJsonObject { put("report", buildJsonObject { put("workspace_id", workspace) }) }) })
    })
    return CreatedWorkspaceReport(code, channel, dmScope)
}
