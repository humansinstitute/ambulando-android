package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.net.URI

/** v2 dispatch is exclusively by versioned capability, never by app identity. */
object GenericNapplet {
    val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    data class Binding(val manifest: JsonObject,val resources: JsonObject,val name: String,val capability: String) {
        val title get()=text(manifest,"title")
        val icon get()=text(manifest,"icon")
        val resource get()=text(resources.getValue(name).jsonObject,if(capability=="private-json-file") "file_id" else "workspace_id")
        val saves get()=manifest.getValue("bindings").jsonObject.getValue(name).jsonObject.getValue("operations").jsonArray.contains(JsonPrimitive("save"))
    }
    fun version(o: JsonObject?,key: String,n: Int): Boolean = (o?.get(key) as? JsonPrimitive)?.let { !it.isString && it.doubleOrNull==n.toDouble() } == true
    fun writeGrant(payload: JsonObject): Boolean {
        val value=if("can_write" in payload) payload["can_write"] else (payload["file"] as? JsonObject)?.get("can_write")
        require(value==JsonPrimitive(true) || value==JsonPrimitive(false))
        return value==JsonPrimitive(true)
    }
    fun exact(o: JsonObject,vararg keys: String) { require(o.keys==keys.toSet()) }
    fun text(o: JsonObject,k: String): String = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid string")
    private fun display(s: String,max: Int) { require(GenericJson.scalar(s) && s.codePointCount(0,s.length) in 1..max && s.codePoints().noneMatch { it<32 || it in 127..159 }) }
    private val declarations=Regex("<script\\b[^>]*[\\t\\n\\f\\r ]id\\s*=\\s*[\"']wingman-napplet-manifest[\"'][^>]*>([\\s\\S]*?)</script\\s*>",RegexOption.IGNORE_CASE)
    fun claimsRuntime(html: String,metadata: JsonObject): Boolean = declarations.containsMatchIn(html) || version(metadata["napplet"] as? JsonObject,"version",2)
    fun parse(html: String,metadata: JsonObject): Binding? {
        val matches=declarations.findAll(html).toList()
        val m=if(matches.size==1) GenericJson.decode(matches.single().groupValues[1]) else null
        val n=metadata["napplet"] as? JsonObject
        if(!version(m,"version",2) && !version(n,"version",2)) return null
        require(matches.size==1 && m!=null && n!=null)
        exact(m,"version","app_id","title","icon","bindings"); require(version(m,"version",2))
        require(text(m,"app_id").matches(Regex("[a-z][a-z0-9-]{0,63}")))
        display(text(m,"title"),80); display(text(m,"icon"),8); require(text(m,"icon").none { it in "<>:/\\" })
        val bindings=m.getValue("bindings").jsonObject; require(bindings.size==1)
        val name=bindings.keys.single(); require(name.matches(Regex("[a-z][a-z0-9_]{0,31}")))
        val b=bindings.getValue(name).jsonObject; exact(b,"capability","version","operations")
        val cap=text(b,"capability"); require(version(b,"version",1) && cap in setOf("private-json-file","workspace-aggregate"))
        val read=JsonArray(listOf(JsonPrimitive("read"))); val write=JsonArray(listOf(JsonPrimitive("read"),JsonPrimitive("save")))
        require(b["operations"]==read || cap=="private-json-file" && b["operations"]==write)
        exact(n,"version","app_id","bindings"); require(version(n,"version",2) && n["app_id"]==m["app_id"])
        val resources=n.getValue("bindings").jsonObject; exact(resources,name)
        val resource=resources.getValue(name).jsonObject; val key=if(cap=="private-json-file") "file_id" else "workspace_id"
        exact(resource,key); require(uuid.matches(text(resource,key)))
        return Binding(m,resources,name,cap)
    }
    fun request(raw: String,session: String,b: Binding): JsonObject {
        require(session.matches(Regex("[a-f0-9]{32}")))
        val d=GenericJson.envelope(raw); val common=arrayOf("type","version","session","requestId","operation")
        require(text(d,"type")=="wingman-napplet-data" && version(d,"version",2) && text(d,"session")==session)
        require(text(d,"requestId").matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val op=text(d,"operation")
        if(op=="dirty") { exact(d,*common,"dirty"); require(d["dirty"]==JsonPrimitive(true) || d["dirty"]==JsonPrimitive(false)); return d }
        require(text(d,"binding")==b.name)
        if(b.capability=="workspace-aggregate") {
            exact(d,*common,"binding","range"); require(op=="read" && text(d,"range") in WorkspaceReport.ranges)
        } else when(op) {
            "read" -> exact(d,*common,"binding")
            "save" -> { exact(d,*common,"binding","baseVersionId","value"); require(b.saves && uuid.matches(text(d,"baseVersionId"))); GenericJson.validate(d.getValue("value").jsonObject) }
            else -> error("Unknown operation")
        }
        return d
    }
    data class Receipt(val key: String,val fingerprint: String)
    fun receipt(context: JsonObject,code: String,digest: String,b: Binding,canWrite: Boolean): Receipt {
        listOf("baseUrl","appNpub","workspaceId","viewer").forEach { require(text(context,it).isNotEmpty()) }
        require(uuid.matches(code) && digest.matches(Regex("[a-f0-9]{64}")))
        val u=URI(text(context,"baseUrl")).normalize(); require(u.scheme in setOf("http","https") && u.host!=null && u.rawUserInfo==null)
        val scheme=u.scheme.lowercase(); val port=if(u.port==-1 || scheme=="https" && u.port==443 || scheme=="http" && u.port==80) "" else ":${u.port}"
        val backend=("$scheme://${u.host.lowercase()}$port"+(u.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/")+(u.rawQuery?.let { "?$it" } ?: "")+(u.rawFragment?.let { "#$it" } ?: "")).removeSuffix("/")
        val identity=JsonArray(listOf(backend,context["tower"]?.jsonPrimitive?.contentOrNull ?: "",context["service"]?.jsonPrimitive?.contentOrNull ?: "",text(context,"appNpub"),text(context,"workspaceId"),text(context,"viewer")).map(::JsonPrimitive))
        fun hash(v: JsonElement)=Sha256Hex.of(GenericJson.canonical(v).toByteArray())
        return Receipt("napplet-approval-v2:"+hash(JsonArray(identity+JsonPrimitive(code))),hash(buildJsonObject {
            put("version",2); put("identity",identity); put("codeFileId",code); put("digest",digest); put("manifest",b.manifest); put("resources",b.resources); put("canWrite",canWrite && b.saves)
        }))
    }
    fun error(session: String,id: String,code: String): JsonObject = buildJsonObject {
        put("type","wingman-napplet-result"); put("version",2); put("session",session); put("requestId",id); put("status","error"); put("code",code)
        put("message",when(code) { "invalid" -> "Invalid request."; "conflict" -> "A newer version exists."; "unavailable" -> "Operation could not be confirmed."; else -> error("Unknown error") })
    }
}
