package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*

/** Typed additive NStack contract. Uses existing NIP-98/FIPS TowerClient, never a frame signer. */
class NappletRuntimeAPI(private val client: TowerClient,val workspace: String,val app: String) {
    val base=TowerAPI.base(workspace)+"/apps/"+app+"/napplets"
    init { require(app.matches(Regex("npub1[a-z0-9]+"))) }
    class Conflict: Exception("A newer revision exists.")
    private fun slug(id: String)=id.also { require(NappletRuntimeProtocol.name.matches(it)) }
    private fun record(id: String)=id.also { require(NappletRuntimeProtocol.recordId.matches(it)) }
    private suspend fun call(method: String="GET",suffix: String="",body: JsonObject?=null,query: List<Pair<String,String>> = emptyList()): JsonObject {
        val response=client.exchange(method,base+suffix,app,body?.toString()?.toByteArray(),query)
        if(response.status==409) throw Conflict()
        if(response.status !in 200..299) throw TowerError.Http(response.status,null,null)
        require(response.body.size<=8388608)
        currentCoroutineContext().ensureActive()
        // Envelopes include server metadata; record payloads are separately safety validated.
        return Json.parseToJsonElement(GenericJson.text(response.body)).jsonObject
    }
    suspend fun installations(cursor: String?=null): JsonObject = call(query=listOf("limit" to "100")+listOfNotNull(cursor?.let { "after" to it }))
    suspend fun installation(id: String)=NappletRuntimeInstallation(call(suffix="/${slug(id)}").getValue("installation").jsonObject).validate(workspace,app).also { require(it.id==id) }
    suspend fun bundle(i: NappletRuntimeInstallation): String = i.verify(client.request(path=base+"/${slug(i.id)}/bundle",appNpub=app))
    suspend fun collections(id: String,cursor: String?=null): JsonObject = call(suffix="/${slug(id)}/collections",query=listOf("limit" to "100")+listOfNotNull(cursor?.let { "after" to it })).also { page ->
        val rows=page.getValue("collections").jsonArray; require(rows.size<=100)
        rows.forEach { value -> val c=value.jsonObject
            require(c["workspace_id"]==JsonPrimitive(workspace) && c["app_npub"]==JsonPrimitive(app) && c["napplet_id"]==JsonPrimitive(id))
            require(GenericNapplet.uuid.matches(NappletRuntimeProtocol.text(c,"id")) && studio.otherstuff.ambulando.core.nostr.NostrKeys.isValidNpub(NappletRuntimeProtocol.text(c,"owner_npub")))
            require(NappletRuntimeProtocol.name.matches(NappletRuntimeProtocol.text(c,"name")) && NappletRuntimeProtocol.text(c,"scope") in setOf("personal","team"))
            NappletRuntimeProtocol.integer(c,"schema_version"); NappletRuntimeProtocol.integer(c,"revision")
        }
    }
    suspend fun createCollection(id: String,name: String,declaration: JsonObject): JsonObject {
        require(NappletRuntimeProtocol.name.matches(name))
        return call("POST","/${slug(id)}/collections",buildJsonObject {
            put("name",name); put("scope",declaration.getValue("scope")); put("schema_version",declaration.getValue("schema_version"))
            put("read_npubs",JsonArray(emptyList())); put("write_npubs",JsonArray(emptyList()))
        }).getValue("collection").jsonObject
    }
    suspend fun collectionGrant(id: String,collection: JsonObject,read: JsonArray,write: JsonArray): JsonObject = call("PATCH","/${slug(id)}/collections/${TowerAPI.uuid(NappletRuntimeProtocol.text(collection,"id"))}",buildJsonObject {
        put("expected_revision",collection.getValue("revision")); put("read_npubs",read); put("write_npubs",write)
    }).getValue("collection").jsonObject
    suspend fun audience(i: NappletRuntimeInstallation,audience: JsonArray)=NappletRuntimeInstallation(call("PATCH","/${slug(i.id)}",buildJsonObject { put("expected_revision",i.revision); put("audience_npubs",audience) }).getValue("installation").jsonObject).validate(workspace,app)
    suspend fun release(i: NappletRuntimeInstallation,bundle: JsonObject,manifest: JsonObject): NappletRuntimeInstallation = NappletRuntimeInstallation(call("POST","/${slug(i.id)}/releases",buildJsonObject {
        put("expected_revision",i.revision); put("bundle",bundle); put("manifest",NappletRuntimeProtocol.manifest(manifest))
    }).getValue("installation").jsonObject).validate(workspace,app).also { require(it.id==i.id && it.publisher==i.publisher) }
    suspend fun approveRelease(i: NappletRuntimeInstallation)=NappletRuntimeInstallation(call("POST","/${slug(i.id)}/releases/approve",buildJsonObject { put("expected_revision",i.revision) }).getValue("installation").jsonObject).validate(workspace,app)
    suspend fun remove(i: NappletRuntimeInstallation,delete: Boolean): NappletRuntimeInstallation = NappletRuntimeInstallation(call("DELETE","/${slug(i.id)}",buildJsonObject {
        put("expected_revision",i.revision); put("data",if(delete) "delete" else "keep"); if(delete) put("confirm_napplet_id",i.id)
    }).getValue("installation").jsonObject).validate(workspace,app).also {require(it.id==i.id && it.publisher==i.publisher && it.removed && it.revision==i.revision+1)}
    /** Caller first reads current lifecycle metadata, including the exact retained removal revision. */
    suspend fun install(manifest: JsonObject,bundle: JsonObject,requester: String,publisher: String,current: NappletRuntimeInstallation?): NappletRuntimeInstallation {
        NappletRuntimeProtocol.manifest(manifest); val id=NappletRuntimeProtocol.text(manifest,"napplet_id")
        if(current!=null) {
            require(current.id==id && current.publisher==publisher)
            if(!current.removed) return current
            require(current.raw["owner_npub"]==JsonPrimitive(requester))
        }
        return NappletRuntimeInstallation(call("PUT","/${slug(id)}",buildJsonObject {
            put("expected_revision",current?.revision ?: 0); put("requester_npub",requester); put("publisher_npub",publisher)
            put("display_name",manifest.getValue("title")); put("bundle",bundle); put("manifest",manifest)
        }).getValue("installation").jsonObject).validate(workspace,app).also {
            require(it.id==id && !it.removed && it.publisher==publisher && it.raw["owner_npub"]==JsonPrimitive(requester) && it.manifest==manifest && it.bundle==bundle && it.revision==(current?.revision ?: 0)+1)
        }
    }
    suspend fun records(id: String,collection: String,operation: String,request: JsonObject,schema: JsonElement): JsonObject {
        val path="/${slug(id)}/collections/${TowerAPI.uuid(collection)}/records"
        if(operation=="list") return call(suffix=path,query=listOf("limit" to request.getValue("limit").jsonPrimitive.content)+listOfNotNull((request["cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { "after" to it }))
        val suffix=path+"/"+record(NappletRuntimeProtocol.text(request,"record_id"))
        val response=when(operation) {
            "get" -> call(suffix=suffix)
            "create","update" -> { val value=GenericJson.validate(request.getValue("value").jsonObject); require(GenericJson.canonical(value).toByteArray().size<=262144)
                call("PUT",suffix,buildJsonObject { put("expected_revision",request.getValue("base_revision")); put("schema_version",schema); put("data",value) }) }
            "delete" -> call("DELETE",suffix,buildJsonObject { put("expected_revision",request.getValue("base_revision")) })
            else -> error("Invalid operation")
        }
        return response
    }
    suspend fun history(id: String,collection: String,record: String,cursor: String?=null)=call(suffix="/${slug(id)}/collections/${TowerAPI.uuid(collection)}/records/${record(record)}/history",query=listOf("limit" to "100")+listOfNotNull(cursor?.let { "after" to it }))
    suspend fun migrate(id: String,collection: JsonObject,source: JsonObject,value: JsonObject): JsonObject = call("POST","/${slug(id)}/collections/${TowerAPI.uuid(NappletRuntimeProtocol.text(collection,"id"))}/migrations",buildJsonObject {
        put("expected_revision",collection.getValue("revision")); put("from_schema_version",collection.getValue("schema_version")); put("to_schema_version",collection.getValue("schema_version")); put("compatible",true); put("source",source)
        put("records",buildJsonArray { add(buildJsonObject { put("id","legacy-snapshot"); put("data",GenericJson.validate(value)) }) })
    })
}
