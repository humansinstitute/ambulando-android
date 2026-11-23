package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

/** Immutable mounted code, ordinary code ACL, self-only data and exact canonical CAS readback. */
class GenericNappletTransport(private val api: TowerAPI,private val workspace: String,private val app: String,
    private val viewer: String,private val code: String,private val version: String,private val html: String,
    private val metadata: JsonObject,val binding: GenericNapplet.Binding,private val current: () -> Boolean,
    private val query: suspend (String) -> JsonObject) {
    data class Snapshot(val version: String,val value: JsonObject,val canWrite: Boolean,val objectId: String,val bytes: ByteArray)
    class Conflict: Exception("A newer version exists.")
    private var delivered: Snapshot?=null
    private var uncertain=false
    fun clear() { delivered=null; uncertain=true }
    private suspend fun file(id: String): FileRecord = api.artefactFile(workspace,app,id).also {
        require(it.id==id && it.workspaceId==workspace && !it.accessRemoved && !it.archived && it.raw.string("record_state")!="deleted" && it.raw.string("deleted_at")==null)
    }
    private suspend fun channel(file: FileRecord,self: Boolean) {
        val c=Json.parseToJsonElement(api.client.request(path=TowerAPI.base(workspace)+"/channels/"+TowerAPI.uuid(file.channelId),appNpub=app).toString(Charsets.UTF_8)).jsonObject.getValue("channel").jsonObject
        require(c.string("id")==file.channelId && c.string("workspace_id")==workspace && c.bool("is_archived")!=true && c.string("archived_at")==null && c.string("deleted_at")==null && c.string("record_state") !in setOf("deleted","archived"))
        if(self) require(c.string("kind")=="dm" && c["participant_npubs"]==JsonArray(listOf(JsonPrimitive(viewer))))
    }
    suspend fun authorize(expectedDataVersion: String?=null): Boolean {
        check(current())
        val f=file(code); require(f.raw["metadata"]==metadata && f.raw.string("current_version_id")==version)
        require(GenericNapplet.parse(html,metadata)==binding); channel(f,false)
        val list=api.genericNappletHead(workspace,app,code); require(list.currentVersionId==version)
        val v=list.versions.single { it.id==version }; require(v.fileId==code && v.contentType?.substringBefore(';')=="text/html")
        FileNapplet.verifiedHTML(api.fileVersionBytes(workspace,app,code,v),html,requireNotNull(v.sizeBytes),requireNotNull(v.sha256))
        val writable=if(binding.capability=="private-json-file") {
            val access=GenericJson.envelope(GenericJson.text(api.client.request(path=TowerAPI.base(workspace)+"/files/"+TowerAPI.uuid(binding.resource),appNpub=app)))
            val grant=GenericNapplet.writeGrant(access)
            val data=FileRecord.parse(access.getValue("file").jsonObject)
            require(data.id==binding.resource && data.workspaceId==workspace && !data.accessRemoved && !data.archived && data.raw.string("record_state")!="deleted" && data.raw.string("deleted_at")==null)
            require(data.contentType?.substringBefore(';')=="application/json"); channel(data,true)
            if(expectedDataVersion!=null && data.raw.string("current_version_id")!=expectedDataVersion) throw Conflict()
            require(api.storageObject(requireNotNull(data.storageObjectId))?.bool("is_public")==false)
            grant && binding.saves
        } else { require(binding.resource==workspace); false }
        check(current()); return writable
    }
    private suspend fun snapshot(): Snapshot {
        val write=authorize(); val list=api.genericNappletHead(workspace,app,binding.resource)
        val v=list.versions.single { it.id==list.currentVersionId }
        require(v.fileId==binding.resource && GenericNapplet.uuid.matches(v.id) && v.contentType?.substringBefore(';')=="application/json")
        require(v.sizeBytes in 1..GenericJson.MAX_BYTES.toLong())
        val storage=api.storageObject(v.storageObjectId) ?: error("Missing storage"); require(storage.bool("is_public")==false)
        val bytes=api.fileVersionBytes(workspace,app,binding.resource,v)
        require(bytes.size.toLong()==v.sizeBytes && Sha256Hex.of(bytes)==v.sha256)
        val value=GenericJson.decode(bytes)
        require(authorize(v.id)==write); currentCoroutineContext().ensureActive()
        return Snapshot(v.id,value,write,v.storageObjectId,bytes)
    }
    suspend fun read(): Snapshot = withTimeout(45000) { require(binding.capability=="private-json-file"); snapshot().also { delivered=it; uncertain=false } }
    suspend fun aggregate(range: String): JsonObject = withTimeout(45000) {
        require(binding.capability=="workspace-aggregate" && range in WorkspaceReport.ranges)
        authorize(); val value=query(range); authorize(); WorkspaceReport.model(value,workspace,range)
    }
    suspend fun save(base: String,value: JsonObject): Snapshot = withTimeout(45000) {
        require(binding.capability=="private-json-file" && binding.saves)
        val old=delivered ?: throw Conflict(); if(uncertain || old.version!=base) throw Conflict()
        val bytes=GenericJson.canonical(GenericJson.validate(value)).toByteArray()
        val fresh=snapshot(); if(fresh.version!=base) throw Conflict(); require(fresh.canWrite)
        val objectId=api.store(workspace,app,bytes,"application/json","napplet.json",null) { }
        currentCoroutineContext().ensureActive(); require(authorize()); uncertain=true
        val outcome=api.addFileVersion(workspace,app,binding.resource,base,objectId,UUID.randomUUID().toString())
        when(outcome) {
            is VersionOutcome.Conflict -> throw Conflict()
            is VersionOutcome.Denied -> error("Unavailable")
            else -> Unit
        }
        val saved=snapshot()
        if(outcome is VersionOutcome.Saved) require(outcome.versionId==saved.version)
        require(saved.version!=base && saved.objectId==objectId && saved.bytes.contentEquals(bytes))
        delivered=saved; uncertain=false; saved
    }
}

/** Read operations are scheduled by the existing workspace sync owner. Mutations stay explicit commands. */
suspend fun <T> studio.otherstuff.ambulando.core.sync.WorkspaceSync.genericNappletRead(code: String,operation: suspend () -> T): T =
    engine.perform("files:generic-napplet:$code:${UUID.randomUUID()}") { withTimeout(45000) { operation() } }

/** Current-head execution must remain usable after the history viewer's capped 200 versions. */
suspend fun TowerAPI.genericNappletHead(workspace: String,app: String,file: String): FileVersionList {
    val reply=GenericJson.decode(client.request(path=TowerAPI.base(workspace)+"/files/"+TowerAPI.uuid(file)+"/versions",query=listOf("limit" to "1"),appNpub=app))
    val id=GenericNapplet.text(reply,"current_version_id"); require(GenericNapplet.uuid.matches(id))
    val version=FileVersionRecord.parse(reply.getValue("versions").jsonArray.single().jsonObject)
    require(version.id==id && version.fileId==file)
    return FileVersionList(file,id,listOf(version))
}
