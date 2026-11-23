package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.store.ConnectionRecord

/** Aggregate networking belongs to the existing workspace sync scheduler, never the frame. No disk count cache. */
suspend fun WorkspaceSync.readWorkspaceReport(connection: ConnectionRecord, workspace: WorkspaceRecord, range: String): JsonObject =
    readWorkspaceReportSnapshot(connection,workspace,range,false)

/** Opt-in is selected only by an approved workspace-aggregate@2 binding, never by frame query fields. */
suspend fun WorkspaceSync.readWorkspaceStorageReport(connection: ConnectionRecord, workspace: WorkspaceRecord, range: String): JsonObject =
    readWorkspaceReportSnapshot(connection,workspace,range,true)

private suspend fun WorkspaceSync.readWorkspaceReportSnapshot(connection: ConnectionRecord, workspace: WorkspaceRecord, range: String, storage: Boolean): JsonObject =
    engine.perform("workspace-report:${workspace.id}" + if(storage) ":storage:$range" else "") {
        withTimeout(45000) {
            require(range in WorkspaceReport.ranges && !workspace.accessRemoved)
            if(storage) NappletRuntimeContext.create(connection,workspace)
            identity.requireSigner(connection.signerNpub)
            val signer=studio.otherstuff.ambulando.core.nostr.EventSigner { template ->
                identity.requireSigner(connection.signerNpub)
                identity.sign(template).also { check(studio.otherstuff.ambulando.core.nostr.NostrKeys.npub(it.pubkey)==connection.signerNpub) }
            }
            val pinned=TowerAPI(TowerClient(TowerOrigin(connection.origin),transport,signer),transfers)
            val raw = pinned.client.request(path = TowerAPI.base(workspace.id) + "/workspace-report",
                query = listOf("range" to range, "top_n" to "10") + (if(storage) listOf("storage" to "workspace") else emptyList()), appNpub = workspace.appNpub)
            require(raw.size <= 70000)
            identity.requireSigner(connection.signerNpub)
            val response = if(storage) GenericJson.decode(raw) else Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
            val expected = workspace.raw.getValue("identity").jsonObject
            if(storage) WorkspaceStorage.response(response,expected,workspace.id,range) else WorkspaceReport.response(response,expected,workspace.id,range)
        }
    }

/** Current immutable code, private storage and self-channel authority are checked on every read/result/mount. */
class WorkspaceReportTransport(private val api: TowerAPI, private val workspace: String, private val app: String,
    private val viewer: String, private val code: String, private val version: String, private val html: String,
    private val metadata: JsonObject, private val manifest: JsonObject, private val current: () -> Boolean,
    private val query: suspend (String) -> JsonObject) {
    suspend fun authorize() {
        check(current())
        val file=api.artefactFile(workspace,app,code)
        require(file.workspaceId==workspace && !file.accessRemoved && !file.archived && file.raw.string("record_state")!="deleted")
        require(file.raw["metadata"]==metadata && WorkspaceReport.binding(metadata,manifest)==workspace && file.raw.string("current_version_id")==version)
        val channel=Json.parseToJsonElement(api.client.request(path=TowerAPI.base(workspace)+"/channels/"+TowerAPI.uuid(file.channelId),appNpub=app).toString(Charsets.UTF_8)).jsonObject.getValue("channel").jsonObject
        require(channel.string("workspace_id")==workspace && channel.string("id")==file.channelId && channel.string("kind")=="dm")
        require(channel["participant_npubs"]==JsonArray(listOf(JsonPrimitive(viewer))) && channel.bool("is_archived")!=true && channel.string("record_state")!="deleted")
        val versions=api.fileVersions(workspace,app,code)
        require(versions.currentVersionId==version)
        val v=versions.versions.single { it.id==version }
        require(v.fileId==code && v.contentType?.substringBefore(';')=="text/html")
        val storage=api.storageObject(v.storageObjectId) ?: error("Unavailable")
        require(storage.bool("is_public")==false)
        val bytes=api.fileVersionBytes(workspace,app,code,v)
        FileNapplet.verifiedHTML(bytes,html,requireNotNull(v.sizeBytes),requireNotNull(v.sha256))
        require(Sha256Hex.of(bytes)==Sha256Hex.of(html.toByteArray()))
        check(current())
    }
    suspend fun read(range: String): JsonObject = withTimeout(45000) {
        authorize()
        val value=query(range)
        authorize()
        WorkspaceReport.model(value,workspace,range)
    }
}
