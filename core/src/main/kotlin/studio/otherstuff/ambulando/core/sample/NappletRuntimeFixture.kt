package studio.otherstuff.ambulando.core.sample

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.net.URI
import java.util.UUID

/** Opt-in synthetic NStack v3 stand-in. No seeded apps, real accounts, network or release activation.
 * Tests publish arbitrary bundles after the host APK is built. SampleTower authenticates before invoking it.
 */
class NappletRuntimeFixture(val workspace: String,val app: String) {
    private val installations=linkedMapOf<String,JsonObject>()
    private val bytes=mutableMapOf<String,ByteArray>()
    private val collections=linkedMapOf<String,JsonObject>()
    private val records=linkedMapOf<String,JsonObject>()
    private val histories=linkedMapOf<String,MutableList<JsonObject>>()
    @Volatile var deny=false
    @Volatile var wrongCollectionNamespace=false
    @Volatile var jumpRevision=false
    @Volatile var loseMutationResponse=false
    @Volatile var corruptBundle=false
    private val base=TowerAPI.base(workspace)+"/apps/$app/napplets"
    @Synchronized fun publish(html: String,owner: String): NappletRuntimeInstallation {
        val m=NappletRuntimeProtocol.parse(html); val id=NappletRuntimeProtocol.text(m,"napplet_id"); val previous=installations[id]
        val content=html.toByteArray(); val bundle=buildJsonObject {put("file_id",UUID.randomUUID().toString());put("file_version_id",UUID.randomUUID().toString());put("sha256_hex",Sha256Hex.of(content))}
        bytes[NappletRuntimeProtocol.text(bundle,"file_version_id")]=content
        val pending=previous!=null && !NappletRuntimeProtocol.permits(previous.getValue("manifest").jsonObject,m)
        val row=buildJsonObject {
            previous?.forEach { (k,v)->put(k,v) }
            put("workspace_id",workspace);put("app_npub",app);put("napplet_id",id);put("owner_npub",owner);put("publisher_npub",owner)
            put("display_name",m.getValue("title"));put("revision",(previous?.get("revision")?.jsonPrimitive?.long ?: 0)+1);put("removed",false)
            put("audience_npubs",previous?.get("audience_npubs") ?: JsonArray(listOf(JsonPrimitive(owner))))
            if(pending) put("pending_release",buildJsonObject {put("bundle",bundle);put("manifest",m)})
            else {put("bundle",bundle);put("manifest",m);put("pending_release",JsonNull)}
        }
        installations[id]=row; return NappletRuntimeInstallation(row).validate(workspace,app)
    }
    @Synchronized fun registerSource(html: String,file: String,version: String): JsonObject {
        val content=html.toByteArray(); bytes[version]=content
        return buildJsonObject {put("file_id",file);put("file_version_id",version);put("sha256_hex",Sha256Hex.of(content))}
    }
    @Synchronized fun history(collection: String,id: String): List<JsonObject> = histories["$collection:$id"]?.toList() ?: emptyList()
    private fun response(status: Int=200,value: JsonObject)=HttpResponse(status,value.toString().toByteArray())
    private fun fail(status: Int)=response(status,buildJsonObject {put("code",if(status==409) "revision_conflict" else "unavailable")})
    private fun envelope(key: String,value: JsonElement)=buildJsonObject {put(key,value)}
    private fun page(key: String,rows: List<JsonObject>,query: Map<String,String>,id: String): JsonObject {
        val limit=query["limit"]?.toIntOrNull()?.coerceIn(1,100) ?: 50; val after=query["after"] ?: ""
        val candidates=rows.sortedBy {it.getValue(id).jsonPrimitive.content}.filter {it.getValue(id).jsonPrimitive.content>after}
        val result=candidates.take(limit)
        return buildJsonObject {put(key,JsonArray(result));put("next_cursor",if(candidates.size>limit) result.last().getValue(id) else JsonNull)}
    }
    @Synchronized fun handle(request: HttpRequest,actor: String): HttpResponse? {
        val uri=URI(request.url); if(uri.path!=base && !uri.path.startsWith("$base/")) return null
        if(deny) return fail(403)
        val segments=uri.path.removePrefix(base).split('/').filter {it.isNotEmpty()}
        val body=request.body?.let {Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject} ?: JsonObject(emptyMap())
        val query=uri.rawQuery?.split('&')?.associate {val p=it.split('=',limit=2);java.net.URLDecoder.decode(p[0],"UTF-8") to java.net.URLDecoder.decode(p.getOrElse(1){""},"UTF-8")} ?: emptyMap()
        fun open(i: JsonObject)=i["owner_npub"]==JsonPrimitive(actor) || i["publisher_npub"]==JsonPrimitive(actor) || JsonPrimitive(actor) in i.getValue("audience_npubs").jsonArray
        if(segments.isEmpty()) return response(value=page("installations",installations.values.filter {it["removed"]!=JsonPrimitive(true) && open(it)},query,"napplet_id"))
        val id=segments[0]
        if(segments.size==1 && request.method=="PUT" && installations[id]==null) {
            if(body["expected_revision"]!=JsonPrimitive(0) || body["requester_npub"]!=JsonPrimitive(actor) || body["publisher_npub"]!=JsonPrimitive(actor)) return fail(403)
            val bundle=body.getValue("bundle").jsonObject; val content=bytes[NappletRuntimeProtocol.text(bundle,"file_version_id")] ?: return fail(404)
            if(Sha256Hex.of(content)!=NappletRuntimeProtocol.text(bundle,"sha256_hex") || NappletRuntimeProtocol.parse(GenericJson.text(content))!=body["manifest"]) return fail(409)
            val row=buildJsonObject {put("workspace_id",workspace);put("app_npub",app);put("napplet_id",id);put("owner_npub",actor);put("publisher_npub",actor);put("display_name",body.getValue("display_name"));put("bundle",bundle);put("manifest",body.getValue("manifest"));put("revision",1);put("removed",false);put("audience_npubs",JsonArray(listOf(JsonPrimitive(actor))));put("pending_release",JsonNull)}
            installations[id]=row;return response(value=envelope("installation",row))
        }
        val i=installations[id] ?: return fail(404)
        val owner=i["owner_npub"]==JsonPrimitive(actor)
        if(!open(i)) return fail(404)
        fun cas(row: JsonObject)=body["expected_revision"]==row["revision"]
        if(segments.size==1) {
            when(request.method) {
                "GET" -> return response(value=envelope("installation",i))
                "PATCH" -> {
                    if(!owner) return fail(403);if(!cas(i)) return fail(409)
                    val updated=JsonObject(i+mapOf("audience_npubs" to body.getValue("audience_npubs"),"revision" to JsonPrimitive(i.getValue("revision").jsonPrimitive.long+1)))
                    installations[id]=updated;return response(value=envelope("installation",updated))
                }
                "DELETE" -> {
                    if(!owner) return fail(403);if(!cas(i)) return fail(409)
                    val ownCollections=collections.values.filter {it["napplet_id"]==JsonPrimitive(id)}
                    if(body["data"]==JsonPrimitive("delete")) {
                        if(body["confirm_napplet_id"]!=JsonPrimitive(id) || ownCollections.any {it["owner_npub"]!=JsonPrimitive(actor)}) return fail(403)
                        ownCollections.forEach {c->val cid=NappletRuntimeProtocol.text(c,"id");collections.remove(cid);records.keys.removeAll {it.startsWith("$cid:")};histories.keys.removeAll {it.startsWith("$cid:")}}
                    }
                    val updated=JsonObject(i+mapOf("removed" to JsonPrimitive(true),"revision" to JsonPrimitive(i.getValue("revision").jsonPrimitive.long+1)))
                    installations[id]=updated;return response(value=envelope("installation",updated))
                }
                "PUT" -> {
                    if(!owner || i["removed"]!=JsonPrimitive(true) || body["publisher_npub"]!=i["publisher_npub"] || body["requester_npub"]!=i["owner_npub"]) return fail(403)
                    if(!cas(i)) return fail(409)
                    val updated=JsonObject(i+mapOf("removed" to JsonPrimitive(false),"revision" to JsonPrimitive(i.getValue("revision").jsonPrimitive.long+1),"bundle" to body.getValue("bundle"),"manifest" to body.getValue("manifest")))
                    installations[id]=updated;return response(value=envelope("installation",updated))
                }
            }
        }
        if(i["removed"]==JsonPrimitive(true)) return fail(404)
        if(segments[1]=="bundle") {
            val content=bytes[NappletRuntimeProtocol.text(i.getValue("bundle").jsonObject,"file_version_id")] ?: return fail(404)
            return HttpResponse(200,if(corruptBundle) content+byteArrayOf(32) else content,mapOf("Content-Type" to "application/octet-stream"))
        }
        if(segments.drop(1)==listOf("releases","approve")) {
            if(!owner) return fail(403); if(!cas(i)) return fail(409)
            val pending=i["pending_release"] as? JsonObject ?: return fail(409)
            val updated=JsonObject(i+pending+mapOf("pending_release" to JsonNull,"revision" to JsonPrimitive(i.getValue("revision").jsonPrimitive.long+1)))
            installations[id]=updated; return response(value=envelope("installation",updated))
        }
        if(segments[1]!="collections") return fail(404)
        fun readable(c: JsonObject)=c["owner_npub"]==JsonPrimitive(actor) || JsonPrimitive(actor) in c.getValue("read_npubs").jsonArray+c.getValue("write_npubs").jsonArray
        if(segments.size==2) {
            if(request.method=="POST") {
                val name=NappletRuntimeProtocol.text(body,"name"); val declaration=i.getValue("manifest").jsonObject.getValue("collections").jsonObject[name] as? JsonObject ?: return fail(403)
                if(body["scope"]!=declaration["scope"] || body["schema_version"]!=declaration["schema_version"]) return fail(403)
                if(collections.values.any {it["napplet_id"]==JsonPrimitive(id) && it["name"]==body["name"] && it["owner_npub"]==JsonPrimitive(actor)}) return fail(409)
                val c=buildJsonObject {body.forEach{(k,v)->put(k,v)};put("id",UUID.randomUUID().toString());put("workspace_id",workspace);put("app_npub",app);put("napplet_id",id);put("owner_npub",actor);put("revision",1)}
                collections[NappletRuntimeProtocol.text(c,"id")]=c;return response(value=envelope("collection",c))
            }
            val rows=collections.values.filter {it["napplet_id"]==JsonPrimitive(id) && readable(it)}.map {if(wrongCollectionNamespace) JsonObject(it+("workspace_id" to JsonPrimitive(UUID.randomUUID().toString()))) else it}
            return response(value=page("collections",rows,query,"id"))
        }
        val cid=segments[2];val c=collections[cid]?.takeIf {it["napplet_id"]==JsonPrimitive(id) && readable(it)} ?: return fail(404)
        if(segments.size==3 && request.method=="PATCH") {
            if(c["owner_npub"]!=JsonPrimitive(actor)) return fail(403);if(!cas(c)) return fail(409)
            val updated=JsonObject(c+mapOf("read_npubs" to body.getValue("read_npubs"),"write_npubs" to body.getValue("write_npubs"),"revision" to JsonPrimitive(c.getValue("revision").jsonPrimitive.long+1)))
            collections[cid]=updated;return response(value=envelope("collection",updated))
        }
        if(segments.getOrNull(3)=="migrations" && request.method=="POST") {
            if(c["owner_npub"]!=JsonPrimitive(actor) || body["compatible"]!=JsonPrimitive(true)) return fail(403)
            if(!cas(c) || body["from_schema_version"]!=c["schema_version"] || body["to_schema_version"]!=c["schema_version"]) return fail(409)
            val source=body.getValue("source").jsonObject;val content=bytes[NappletRuntimeProtocol.text(source,"file_version_id")] ?: return fail(404)
            if(Sha256Hex.of(content)!=NappletRuntimeProtocol.text(source,"sha256_hex")) return fail(409)
            val inputs=body.getValue("records").jsonArray;if(inputs.size!=1) return fail(400)
            val input=inputs.single().jsonObject;val rid=NappletRuntimeProtocol.text(input,"id");val key="$cid:$rid"
            if(records.containsKey(key)) return fail(409)
            if(GenericJson.canonical(GenericJson.decode(content))!=GenericJson.canonical(input.getValue("data"))) return fail(400)
            val record=buildJsonObject {put("collection_id",cid);put("id",rid);put("revision",1);put("schema_version",c.getValue("schema_version"));put("data",input.getValue("data"));put("deleted",false);put("provenance",source);put("updated_by_npub",actor);put("updated_at",java.time.Instant.now().toString())}
            records[key]=record;histories.getOrPut(key){mutableListOf()}.add(record)
            val updated=JsonObject(c+("revision" to JsonPrimitive(c.getValue("revision").jsonPrimitive.long+1)));collections[cid]=updated
            return response(value=buildJsonObject {put("collection",updated);put("imported",1);put("source_preserved",true)})
        }
        if(segments.getOrNull(3)!="records") return fail(404)
        val declaration=i.getValue("manifest").jsonObject.getValue("collections").jsonObject.getValue(NappletRuntimeProtocol.text(c,"name")).jsonObject
        val ops=declaration.getValue("operations").jsonArray
        if(segments.size==4) {
            if(JsonPrimitive("list") !in ops) return fail(403)
            return response(value=page("records",records.filterKeys {it.startsWith("$cid:")}.values.filter {it["deleted"]==JsonPrimitive(false)},query,"id"))
        }
        val rid=segments[4];val key="$cid:$rid";val old=records[key]
        if(segments.getOrNull(5)=="history") return response(value=page("versions",histories[key] ?: emptyList(),query,"revision"))
        if(request.method=="GET") return if(JsonPrimitive("get") !in ops) fail(403) else old?.let {response(value=envelope("record",it))} ?: fail(404)
        val write=c["owner_npub"]==JsonPrimitive(actor) || JsonPrimitive(actor) in c.getValue("write_npubs").jsonArray
        if(!write) return fail(403)
        val operation=if(request.method=="DELETE") "delete" else if(old==null) "create" else "update"
        if(JsonPrimitive(operation) !in ops) return fail(403)
        if(body["expected_revision"]!=(old?.get("revision") ?: JsonPrimitive(0))) return fail(409)
        if(operation=="delete" && old==null) return fail(404)
        if(operation!="delete" && body["schema_version"]!=c["schema_version"]) return fail(409)
        val r=buildJsonObject {put("collection_id",cid);put("id",rid);put("revision",(old?.get("revision")?.jsonPrimitive?.long ?: 0)+(if(jumpRevision) 2 else 1));put("schema_version",c.getValue("schema_version"));put("data",if(operation=="delete") JsonNull else GenericJson.validate(body.getValue("data").jsonObject));put("deleted",operation=="delete");put("updated_by_npub",actor);put("updated_at",java.time.Instant.now().toString())}
        records[key]=r;histories.getOrPut(key){mutableListOf()}.add(r)
        if(loseMutationResponse) throw java.net.SocketException("Synthetic response lost after commit")
        return response(value=envelope("record",r))
    }
}
