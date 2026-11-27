package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.sync.WorkspaceSync
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.nostr.*

/** Online-only identity-scoped local projection, owned by the existing workspace sync service.
 * Views observe state; every command is scheduled through its SyncEngine and bound to the pinned signer.
 * No offline mutation queue and no automatic mutation retries.
 */
class NappletRuntimeSync(private val sync: WorkspaceSync,private val connection: ConnectionRecord,
    private val workspace: WorkspaceRecord,private val current: ()->Boolean) {
    val context=NappletRuntimeContext.create(connection,workspace)
    private val local=LocalRuntimeContent(sync.store,connection,workspace,current)
    suspend fun hydrate() {
        checkContext()
        val saved=local.installations()
        checkContext()
        if(projection.value.installations.isEmpty()) projection.value=projection.value.copy(installations=saved,available=saved.isNotEmpty())
    }
    suspend fun cachedOpen(id: String): Pair<NappletRuntimeInstallation,String>? { checkContext(); return local.open(id) }
    suspend fun cachedRead(i: NappletRuntimeInstallation,request: JsonObject): JsonObject? { checkContext(); return local.read(i,request) }
    suspend fun forgetLocal(id: String,expected: NappletRuntimeInstallation?=null) = local.invalidate(id,expected?.raw)
    class Compatibility: Exception("The stored collection uses a different schema or scope. A reviewed migration is required before this app can use it.")
    data class Projection(val installations: List<NappletRuntimeInstallation> = emptyList(),val records: Map<String,JsonObject> = emptyMap(),val available: Boolean=false)
    private val projection=MutableStateFlow(Projection())
    val state: StateFlow<Projection> = projection
    private val mutex=Mutex()
    @Volatile private var stopped=false
    private val stateLock=Any()
    private val selected=java.util.concurrent.ConcurrentHashMap<String,JsonObject>()
    private suspend fun checkContext() { check(!stopped && current() && !workspace.accessRemoved); sync.identity.requireSigner(connection.signerNpub) }
    private fun client(): TowerClient {
        val signer=EventSigner { template -> checkContext(); sync.identity.sign(template).also { check(NostrKeys.npub(it.pubkey)==connection.signerNpub) } }
        return TowerClient(TowerOrigin(connection.origin),sync.transport,signer)
    }
    private fun api()=NappletRuntimeAPI(client(),workspace.id,workspace.appNpub)
    private fun files()=TowerAPI(client(),sync.transfers)
    private suspend fun <T> command(block: suspend (NappletRuntimeAPI)->T): T = sync.engine.perform("napplet-runtime:${workspace.id}:${java.util.UUID.randomUUID()}") {
        withTimeout(45000) { mutex.withLock { checkContext(); block(api()).also { currentCoroutineContext().ensureActive(); checkContext() } } }
    }
    fun invalidate(id: String) {
        selected.keys.removeAll { it.startsWith(id+":") }
        projection.value=projection.value.copy(records=projection.value.records.filterKeys { !it.startsWith(id+":") })
    }
    fun clear() = synchronized(stateLock) { stopped=true; selected.clear(); projection.value=Projection() }
    private suspend fun rows(fetch: suspend (String?)->JsonObject,key: String): List<JsonObject> {
        val result=mutableListOf<JsonObject>(); val seen=mutableSetOf<String>(); val ids=mutableSetOf<String>(); var cursor: String?=null
        do {
            require(seen.size<100)
            val page=fetch(cursor); val rows=page.getValue(key).jsonArray.map { it.jsonObject }; require(rows.size<=100)
            val idKey=if(key=="installations") "napplet_id" else "id"
            rows.forEach { row -> val id=NappletRuntimeProtocol.text(row,idKey)
                require((if(key=="installations") NappletRuntimeProtocol.name.matches(id) else GenericNapplet.uuid.matches(id)) && ids.add(id))
            }
            result+=rows; require(result.size<=2000)
            require("next_cursor" in page)
            cursor=page["next_cursor"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.takeIf { it.isString }?.content
            require(page["next_cursor"]==JsonNull || cursor!=null)
            require(cursor==null || rows.isNotEmpty() && cursor==NappletRuntimeProtocol.text(rows.last(),idKey) && seen.add(cursor))
        } while(cursor!=null)
        return result
    }
    suspend fun refresh() { hydrate(); refreshOnline() }
    private suspend fun refreshOnline() = command { api ->
        try {
            val installations=rows({ api.installations(it) },"installations").map { NappletRuntimeInstallation(it).validate(workspace.id,workspace.appNpub) }
            installations.forEach { it.requireAudience(connection.signerNpub) }
            require(installations.none { it.removed } && installations.map { it.id }.distinct().size==installations.size)
            checkContext()
            val missing=local.installations().map { it.id }.filter { id -> installations.none { it.id==id } }
            missing.forEach { local.invalidate(it) }; local.saveInstallations(installations)
            synchronized(stateLock) { check(!stopped && current()); projection.value=projection.value.copy(installations=installations,available=true) }
        } catch(e: Exception) {
            if(e !is CancellationException && !transientNappletFailure(e)) {
                local.installations().forEach { local.invalidate(it.id) }; projection.value=Projection()
            }
            throw e
        }
    }
    suspend fun open(id: String): Pair<NappletRuntimeInstallation,String> = command { api ->
        val i=api.installation(id); i.requireAudience(connection.signerNpub); val html=api.bundle(i)
        require(api.installation(id).raw==i.raw); checkContext(); local.saveBundle(i,html); i to html
    }
    suspend fun authority(i: NappletRuntimeInstallation): NappletRuntimeInstallation = command { api ->
        val fresh=api.installation(i.id); fresh.requireAudience(connection.signerNpub); require(fresh.publisher==i.publisher)
        // Verify collection selection/grants even if the frame has not sent another request.
        val collections=rows({ api.collections(i.id,it) },"collections")
        for((key,previous) in selected.filterKeys { it.startsWith(i.id+":") }) {
            val name=key.substringAfter(':'); val declaration=i.manifest.getValue("collections").jsonObject.getValue(name).jsonObject
            require(collections.any { it==previous && matches(it,i.id,name,declaration) })
        }
        fresh
    }
    private fun requireCompatible(mounted: NappletRuntimeInstallation,fresh: NappletRuntimeInstallation) {
        fresh.requireAudience(connection.signerNpub)
        require(fresh.publisher==mounted.publisher && NappletRuntimeProtocol.permits(fresh.manifest,mounted.manifest))
    }
    private fun matches(row: JsonObject,napplet: String,name: String,declaration: JsonObject): Boolean = runCatching {
        require(row["workspace_id"]==JsonPrimitive(workspace.id) && row["app_npub"]==JsonPrimitive(workspace.appNpub) && row["napplet_id"]==JsonPrimitive(napplet))
        require(GenericNapplet.uuid.matches(NappletRuntimeProtocol.text(row,"id")) && NostrKeys.isValidNpub(NappletRuntimeProtocol.text(row,"owner_npub")))
        NappletRuntimeProtocol.integer(row,"revision"); NappletRuntimeProtocol.integer(row,"schema_version")
        val read=row.getValue("read_npubs").jsonArray; val write=row.getValue("write_npubs").jsonArray
        (read+write).forEach { require(it.jsonPrimitive.isString && NostrKeys.isValidNpub(it.jsonPrimitive.content)) }
        require(row["owner_npub"]==JsonPrimitive(connection.signerNpub) || JsonPrimitive(connection.signerNpub) in read+write)
        row["name"]==JsonPrimitive(name) && row["scope"]==declaration["scope"] && row["schema_version"]==declaration["schema_version"]
    }.getOrDefault(false)
    private suspend fun collection(api: NappletRuntimeAPI,i: NappletRuntimeInstallation,name: String): JsonObject {
        val declaration=i.manifest.getValue("collections").jsonObject.getValue(name).jsonObject
        val available=rows({ api.collections(i.id,it) },"collections")
        val candidates=available.filter { matches(it,i.id,name,declaration) }
        if(candidates.isEmpty() && available.any {it["name"]==JsonPrimitive(name) && it["owner_npub"]==JsonPrimitive(connection.signerNpub)}) throw Compatibility()
        val key=i.id+":"+name
        val previous=selected[key]
        val row=if(previous!=null) candidates.single { it==previous }
        else {
            val own=candidates.filter { it["owner_npub"]==JsonPrimitive(connection.signerNpub) }
            if(own.size==1 && declaration["scope"]==JsonPrimitive("personal")) own.single()
            else if(candidates.size==1) candidates.single()
            else if(candidates.isEmpty()) api.createCollection(i.id,name,declaration).also {
                require(it["owner_npub"]==JsonPrimitive(connection.signerNpub) && it["read_npubs"]==JsonArray(emptyList()) && it["write_npubs"]==JsonArray(emptyList()))
            }
            else error("More than one authorized collection matches. Select a collection in Access and data.")
        }
        require(matches(row,i.id,name,declaration)); selected[key]=row; return row
    }
    /** Explicit host selection; the caller must tear down/remount the old frame. */
    suspend fun select(i: NappletRuntimeInstallation,name: String,id: String) = command { api ->
        val declaration=i.manifest.getValue("collections").jsonObject.getValue(name).jsonObject
        val row=rows({ api.collections(i.id,it) },"collections").single { it["id"]==JsonPrimitive(id) && matches(it,i.id,name,declaration) }
        local.invalidate(i.id); selected[i.id+":"+name]=row; projection.value=projection.value.copy(records=emptyMap())
    }
    suspend fun collections(i: NappletRuntimeInstallation): List<JsonObject> = command { api -> rows({ api.collections(i.id,it) },"collections") }
    suspend fun execute(i: NappletRuntimeInstallation,request: JsonObject): JsonObject = command { api ->
        require(NappletRuntimeProtocol.support(i.manifest)==null)
        NappletRuntimeProtocol.request(request.toString(),NappletRuntimeProtocol.text(request,"session"),i.manifest)
        requireCompatible(i,api.installation(i.id))
        val name=NappletRuntimeProtocol.text(request,"collection"); val c=collection(api,i,name)
        val id=NappletRuntimeProtocol.text(c,"id"); val op=NappletRuntimeProtocol.text(request,"operation")
        val isWrite=op in setOf("create","update","delete")
        if(isWrite) require(c["owner_npub"]==JsonPrimitive(connection.signerNpub) || JsonPrimitive(connection.signerNpub) in c.getValue("write_npubs").jsonArray)
        val response=api.records(i.id,id,op,request,c.getValue("schema_version"))
        fun validateRecord(r: JsonObject) {
            require(r["collection_id"]==JsonPrimitive(id) && NappletRuntimeProtocol.recordId.matches(NappletRuntimeProtocol.text(r,"id")))
            NappletRuntimeProtocol.integer(r,"revision"); require(r["schema_version"]==c["schema_version"])
            require(r["deleted"]==JsonPrimitive(true) || r["deleted"]==JsonPrimitive(false))
            require(NostrKeys.isValidNpub(NappletRuntimeProtocol.text(r,"updated_by_npub")))
            if(r["deleted"]==JsonPrimitive(false)) { GenericJson.validate(r.getValue("data").jsonObject); require(GenericJson.canonical(r.getValue("data")).toByteArray().size<=262144) }
            else require(r["data"]==JsonNull)
        }
        val records=if(op=="list") response.getValue("records").jsonArray.map { it.jsonObject } else listOf(response.getValue("record").jsonObject)
        if(op=="list") {
            GenericNapplet.exact(response,"records","next_cursor")
            require(records.size<=NappletRuntimeProtocol.integer(request,"limit") && records.map {it["id"]}.distinct().size==records.size)
            require(response["next_cursor"]==JsonNull || NappletRuntimeProtocol.recordId.matches(NappletRuntimeProtocol.text(response,"next_cursor")))
            require(records.all {it["deleted"]==JsonPrimitive(false)})
        } else GenericNapplet.exact(response,"record")
        records.forEach(::validateRecord)
        if(op!="list") require(records.single()["id"]==request["record_id"])
        if(op in setOf("create","update","delete")) {
            val saved=records.single(); require(NappletRuntimeProtocol.integer(saved,"revision")==NappletRuntimeProtocol.integer(request,"base_revision",0)+1)
            if(op!="delete") require(GenericJson.canonical(saved.getValue("data"))==GenericJson.canonical(request.getValue("value"))) else require(saved["deleted"]==JsonPrimitive(true))
            if(JsonPrimitive("get") in i.manifest.getValue("collections").jsonObject.getValue(name).jsonObject.getValue("operations").jsonArray) {
                val readback=api.records(i.id,id,"get",request,c.getValue("schema_version")).getValue("record").jsonObject
                require(readback==saved)
            }
        }
        requireCompatible(i,api.installation(i.id))
        require(rows({ api.collections(i.id,it) },"collections").any { it==c })
        checkContext()
        synchronized(stateLock) {
            check(!stopped && current())
            projection.value=projection.value.copy(records=projection.value.records+records.associateBy { i.id+":"+id+":"+NappletRuntimeProtocol.text(it,"id") })
        }
        // Retain exact verified read pages; writes invalidate cached pages so removed/updated rows cannot reappear.
        if(isWrite) local.savedRecord(i,request,records.single())
        val result=if(op=="list") response else buildJsonObject { put("record",projection.value.records.getValue(i.id+":"+id+":"+NappletRuntimeProtocol.text(records.single(),"id"))) }
        if(!isWrite) local.saveRead(i,request,result)
        result
    }
    suspend fun remove(i: NappletRuntimeInstallation,delete: Boolean) = command { api ->
        val result=api.remove(i,delete); checkContext(); local.invalidate(i.id); selected.keys.removeAll { it.startsWith(i.id+":") }
        projection.value=projection.value.copy(installations=projection.value.installations.filterNot { it.id==i.id },records=projection.value.records.filterKeys { !it.startsWith(i.id+":") }); result
    }
    suspend fun audience(i: NappletRuntimeInstallation,npubs: List<String>) = command { api -> api.audience(i,JsonArray(npubs.map(::JsonPrimitive))) }
    suspend fun grants(i: NappletRuntimeInstallation,c: JsonObject,read: List<String>,write: List<String>) = command { api ->
        val updated=api.collectionGrant(i.id,c,JsonArray(read.map(::JsonPrimitive)),JsonArray(write.map(::JsonPrimitive)))
        val name=NappletRuntimeProtocol.text(updated,"name")
        require(matches(updated,i.id,name,i.manifest.getValue("collections").jsonObject.getValue(name).jsonObject) && updated["id"]==c["id"])
        local.invalidate(i.id)
        selected[i.id+":"+name]=updated
        projection.value=projection.value.copy(records=emptyMap()); updated
    }
    suspend fun approveRelease(i: NappletRuntimeInstallation) = command { api -> api.approveRelease(i) }
    suspend fun install(manifest: JsonObject,bundle: JsonObject,publisher: String): NappletRuntimeInstallation = command { api ->
        val id=NappletRuntimeProtocol.text(manifest,"napplet_id")
        val existing=try { api.installation(id) } catch(e: TowerError.Http) { if(e.status==404) null else throw e }
        api.install(manifest,bundle,connection.signerNpub,publisher,existing)
    }
    suspend fun installFile(fileId: String,publisher: String): NappletRuntimeInstallation = command { api ->
        val ordinary=files()
        val file=ordinary.artefactFile(workspace.id,workspace.appNpub,fileId)
        require(!file.accessRemoved && !file.archived && file.raw["deleted_at"] in listOf(null,JsonNull))
        val head=ordinary.genericNappletHead(workspace.id,workspace.appNpub,fileId); val version=head.versions.single()
        require(version.contentType?.substringBefore(';')=="text/html" && version.sizeBytes in 1..2097152L)
        val bytes=ordinary.fileVersionBytes(workspace.id,workspace.appNpub,fileId,version)
        require(bytes.size.toLong()==version.sizeBytes && Sha256Hex.of(bytes)==version.sha256)
        val manifest=NappletRuntimeProtocol.parse(GenericJson.text(bytes)); val id=NappletRuntimeProtocol.text(manifest,"napplet_id")
        val existing=try {api.installation(id)} catch(e: TowerError.Http){if(e.status==404) null else throw e}
        api.install(manifest,buildJsonObject {put("file_id",fileId);put("file_version_id",version.id);put("sha256_hex",requireNotNull(version.sha256))},connection.signerNpub,publisher,existing)
    }
    data class Import(val source: JsonObject,val value: JsonObject)
    suspend fun previewImport(fileId: String): Import = command { readImport(fileId) }
    private suspend fun readImport(fileId: String): Import {
        val ordinary=files()
        val file=ordinary.artefactFile(workspace.id,workspace.appNpub,fileId)
        require(file.id==fileId && file.workspaceId==workspace.id && !file.accessRemoved && !file.archived && file.contentType?.substringBefore(';')=="application/json" && file.raw["deleted_at"] in listOf(null,JsonNull) && file.raw["record_state"]!=JsonPrimitive("deleted"))
        val channel=Json.parseToJsonElement(GenericJson.text(ordinary.client.request(path=TowerAPI.base(workspace.id)+"/channels/"+TowerAPI.uuid(file.channelId),appNpub=workspace.appNpub))).jsonObject.getValue("channel").jsonObject
        require(channel["id"]==JsonPrimitive(file.channelId) && channel["workspace_id"]==JsonPrimitive(workspace.id))
        require(channel["kind"]==JsonPrimitive("dm") && channel["participant_npubs"]==JsonArray(listOf(JsonPrimitive(connection.signerNpub))))
        require(channel["deleted_at"] in listOf(null,JsonNull) && channel["is_archived"]!=JsonPrimitive(true))
        val versions=ordinary.genericNappletHead(workspace.id,workspace.appNpub,fileId); val v=versions.versions.single()
        require(v.contentType?.substringBefore(';')=="application/json" && ordinary.storageObject(v.storageObjectId)?.get("is_public")==JsonPrimitive(false))
        val bytes=ordinary.fileVersionBytes(workspace.id,workspace.appNpub,fileId,v)
        require(bytes.size<=262144 && bytes.size.toLong()==v.sizeBytes && Sha256Hex.of(bytes)==v.sha256)
        return Import(buildJsonObject { put("file_id",fileId); put("file_version_id",v.id); put("sha256_hex",requireNotNull(v.sha256)) },GenericJson.decode(bytes))
    }
    /** Explicit immutable whole-object import; the server revalidates source and create-only provenance. */
    suspend fun importFile(i: NappletRuntimeInstallation,name: String,preview: Import,compatible: Boolean) = command { api ->
        require(compatible && api.installation(i.id).raw==i.raw)
        val verified=readImport(NappletRuntimeProtocol.text(preview.source,"file_id"))
        require(verified.source==preview.source && GenericJson.canonical(verified.value)==GenericJson.canonical(preview.value))
        val declaration=i.manifest.getValue("collections").jsonObject.getValue(name).jsonObject
        require(declaration["scope"]==JsonPrimitive("personal"))
        val c=collection(api,i,name); require(c["owner_npub"]==JsonPrimitive(connection.signerNpub))
        val result=api.migrate(i.id,c,preview.source,preview.value)
        val updated=result.getValue("collection").jsonObject
        require(result["source_preserved"]==JsonPrimitive(true) && result["imported"]==JsonPrimitive(1) && updated["id"]==c["id"] && matches(updated,i.id,name,declaration))
        local.invalidate(i.id)
        selected[i.id+":"+name]=updated
        projection.value=projection.value.copy(records=emptyMap()); result
    }
}
