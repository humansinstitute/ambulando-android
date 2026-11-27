package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

/** Each operation performs signed ACL checks again; cached workspace rows grant no authority. */
class FileNappletTransport(
    private val api: TowerAPI, private val workspace: String, private val app: String,
    private val viewer: String, private val codeId: String, private val codeVersion: String,
    private val manifest: JsonObject, private val dataId: String,
) {
    data class Snapshot(val version: String, val value: JsonObject, val canWrite: Boolean, val objectId: String)
    class Conflict : Exception("A newer version exists. Reload explicitly before saving.")
    private var delivered: Snapshot? = null
    private var uncertain = false
    /** Displayed local base, never write authority; save still checks current code, ACL and version. */
    fun restore(snapshot: Snapshot) { FileNapplet.model(snapshot.value); delivered=snapshot; uncertain=false }
    /** Release private in-memory snapshots when the host loses authority or is disposed. */
    fun clear() { delivered = null; uncertain = true }

    suspend fun authorize(): Boolean {
        val code = api.artefactFile(workspace, app, codeId)
        require(FileNapplet.binding(code.raw.getValue("metadata").jsonObject, manifest) == dataId)
        require(code.raw.string("current_version_id") == codeVersion)
        val file = api.artefactFile(workspace, app, dataId)
        require(file.contentType?.substringBefore(';') == "application/json")
        val bytes = api.client.request(path = TowerAPI.base(workspace) + "/channels/" + TowerAPI.uuid(file.channelId), appNpub = app)
        val channel = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("channel").jsonObject
        require(channel.string("workspace_id") == workspace && channel.string("id") == file.channelId && channel.string("kind") == "dm")
        require(channel["participant_npubs"] == JsonArray(listOf(JsonPrimitive(viewer))))
        return file.raw.bool("can_write") == true
    }
    private suspend fun snapshot(): Snapshot {
        val writable = authorize()
        val reply = Json.parseToJsonElement(api.client.request(
            path = TowerAPI.base(workspace) + "/files/" + TowerAPI.uuid(dataId) + "/versions",
            query = listOf("limit" to "1"), appNpub = app,
        ).toString(Charsets.UTF_8)).jsonObject
        val v = FileVersionRecord.parse(reply.getValue("versions").jsonArray.single().jsonObject)
        require(v.fileId == dataId && v.id == reply.string("current_version_id"))
        require(v.contentType?.substringBefore(';') == "application/json" && v.sizeBytes in 0..FileNapplet.MAX_BYTES.toLong())
        require(v.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
        val storage = api.storageObject(v.storageObjectId) ?: error("Missing private storage")
        require(storage.bool("is_public") == false)
        val bytes = api.fileVersionBytes(workspace, app, dataId, v)
        require(bytes.size.toLong() == v.sizeBytes && bytes.size <= FileNapplet.MAX_BYTES && Sha256Hex.of(bytes) == v.sha256)
        val value = FileNapplet.model(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        currentCoroutineContext().ensureActive()
        return Snapshot(v.id, value, writable, v.storageObjectId)
    }
    suspend fun read(): Snapshot = snapshot().also { delivered = it; uncertain = false }
    suspend fun save(base: String, value: JsonObject): Snapshot {
        val old = delivered ?: error("Read first")
        if (uncertain || base != old.version) throw Conflict()
        FileNapplet.model(value, old.value)
        val fresh = snapshot()
        if (fresh.version != base) throw Conflict()
        require(fresh.canWrite)
        val objectId = api.store(workspace, app, value.toString().toByteArray(), "application/json", "napplet.json", null) { }
        currentCoroutineContext().ensureActive()
        // Recheck after the upload, before committing its reference.
        require(authorize())
        uncertain = true
        when (api.addFileVersion(workspace, app, dataId, base, objectId, UUID.randomUUID().toString())) {
            is VersionOutcome.Conflict -> throw Conflict()
            is VersionOutcome.Denied -> error("Access unavailable")
            else -> Unit // Also resolve a lost reply through an exact current readback.
        }
        val saved = snapshot()
        if (saved.version == base || saved.value != value || saved.objectId != objectId) throw Conflict()
        delivered = saved; uncertain = false
        return saved
    }
}

/** Explicit user action only. Creates empty NStack Files, never imports local/personal records. */
data class CreatedDailyTracker(val code: FileRecord, val data: FileRecord, val channel: ChannelRecord, val scope: ScopeRecord)

suspend fun TowerAPI.createDailyTracker(workspace: String, app: String, viewer: String, html: String): CreatedDailyTracker {
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
    val empty = """{"format":"daily-tracker-v1","metrics":[],"days":{}}"""
    val data = create("Daily tracker data.json", empty.toByteArray(), "application/json", JsonObject(emptyMap()))
    val code = create("Daily tracker.html", html.toByteArray(), "text/html", buildJsonObject {
        put("html_artifact", true); put("artefact", ArtefactType.HTML.metadata().getValue("artefact"))
        put("napplet", buildJsonObject { put("version", 1); put("app_id", "daily-tracker"); put("bindings", buildJsonObject { put("tracker", buildJsonObject { put("file_id", data.id) }) }) })
    })
    return CreatedDailyTracker(code, data, channel, dmScope)
}
