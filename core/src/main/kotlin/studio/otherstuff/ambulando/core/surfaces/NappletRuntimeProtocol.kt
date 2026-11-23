package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*

/** Frozen common cartridge protocol v3. File v1/v2 are intentionally independent. */
object NappletRuntimeProtocol {
    val name = Regex("[a-z][a-z0-9_-]{0,63}")
    val recordId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
    val operations = setOf("list", "get", "create", "update", "delete")
    fun text(o: JsonObject, k: String) = GenericNapplet.text(o,k)
    fun integer(o: JsonObject, k: String, minimum: Long = 1): Long {
        val p=o[k] as? JsonPrimitive ?: error("Missing integer")
        val d=p.takeUnless { it.isString }?.doubleOrNull ?: error("Invalid integer")
        require(d.isFinite() && d >= minimum && d <= 9007199254740991.0 && d % 1.0 == 0.0)
        return d.toLong()
    }
    private fun display(s: String, max: Int) {
        require(GenericJson.scalar(s) && s.codePointCount(0,s.length) in 1..max && s.codePoints().noneMatch { it<32 || it in 127..159 })
    }
    private fun strings(o: JsonObject,k: String,nonempty: Boolean=false): List<String> {
        val a=o.getValue(k).jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Invalid text array") }
        require(a.size<=64 && a.distinct().size==a.size && (!nonempty || a.isNotEmpty())); return a
    }
    fun manifest(m: JsonObject): JsonObject {
        GenericJson.validate(m)
        GenericNapplet.exact(m,"version","napplet_id","bundle_version","title","icon","runtime","collections","capabilities")
        require(GenericNapplet.version(m,"version",3) && name.matches(text(m,"napplet_id")))
        display(text(m,"bundle_version"),64); display(text(m,"title"),80); display(text(m,"icon"),8)
        require(text(m,"icon").none { it in "<>:/\\" })
        val r=m.getValue("runtime").jsonObject; GenericNapplet.exact(r,"protocol","min_host")
        require(GenericNapplet.version(r,"protocol",3)); integer(r,"min_host")
        val collections=m.getValue("collections").jsonObject; val caps=m.getValue("capabilities").jsonObject
        require(collections.size<=32 && caps.size<=32)
        collections.forEach { (key,value) ->
            require(name.matches(key)); val c=value.jsonObject; GenericNapplet.exact(c,"scope","schema_version","operations")
            require(text(c,"scope") in setOf("personal","team")); integer(c,"schema_version")
            require(strings(c,"operations",true).all { it in operations })
        }
        caps.forEach { (key,value) ->
            require(name.matches(key)); val c=value.jsonObject; GenericNapplet.exact(c,"version","resources","operations")
            val v=integer(c,"version"); strings(c,"resources").forEach { display(it,2048) }
            val ops=strings(c,"operations",true); require(ops.all { name.matches(it) })
            if(key=="nostr-dm" && v==1L) require(ops.all { it in setOf("read","decrypt","send","persist","agent") })
        }
        return GenericJson.decode(GenericJson.canonical(m))
    }
    private val declarations=Regex("<script\\b[^>]*[\\t\\n\\f\\r ]id\\s*=\\s*[\"']wingman-napplet-manifest[\"'][^>]*>([\\s\\S]*?)</script\\s*>",RegexOption.IGNORE_CASE)
    fun parse(html: String): JsonObject = manifest(GenericJson.decode(declarations.findAll(html).toList().single().groupValues[1]))
    fun support(m: JsonObject): String? = when {
        integer(manifest(m).getValue("runtime").jsonObject,"min_host")>3 -> "This napplet requires a newer host runtime."
        m.getValue("capabilities").jsonObject.isNotEmpty() -> "This host does not support the requested personal capabilities. Nostr DM read, decrypt, send, plaintext persistence and agent access are separate permissions; none is available through this runtime yet."
        else -> null
    }
    /** Code bytes and presentation are deliberately absent from access comparison. */
    fun permits(approved: JsonObject, requested: JsonObject): Boolean {
        for(kind in listOf("collections","capabilities")) {
            val old=approved.getValue(kind).jsonObject
            for((key,value) in requested.getValue(kind).jsonObject) {
                val a=old[key] as? JsonObject ?: return false; val b=value.jsonObject
                val fixed=if(kind=="collections") listOf("scope","schema_version") else listOf("version")
                if(fixed.any { a[it]!=b[it] }) return false
                for(list in if(kind=="collections") listOf("operations") else listOf("operations","resources"))
                    if(!a.getValue(list).jsonArray.containsAll(b.getValue(list).jsonArray)) return false
            }
        }
        return true
    }
    fun request(raw: String, session: String, m: JsonObject): JsonObject {
        require(session.matches(Regex("[a-f0-9]{32}")))
        val d=GenericJson.envelope(raw); val common=arrayOf("type","version","session","requestId","operation")
        require(text(d,"type")=="wingman-napplet-data" && GenericNapplet.version(d,"version",3) && text(d,"session")==session && text(d,"requestId").matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val op=text(d,"operation")
        if(op=="dirty") { GenericNapplet.exact(d,*common,"dirty"); require(d["dirty"]==JsonPrimitive(true) || d["dirty"]==JsonPrimitive(false)); return d }
        val c=m.getValue("collections").jsonObject.getValue(text(d,"collection")).jsonObject
        require(JsonPrimitive(op) in c.getValue("operations").jsonArray)
        when(op) {
            "list" -> { GenericNapplet.exact(d,*common,"collection","limit","cursor"); require(integer(d,"limit")<=100); require(d["cursor"]==JsonNull || text(d,"cursor").length in 1..2048) }
            "get" -> { GenericNapplet.exact(d,*common,"collection","record_id"); require(recordId.matches(text(d,"record_id"))) }
            "create","update","delete" -> {
                val keys=common+arrayOf("collection","record_id","base_revision")+(if(op=="delete") emptyArray() else arrayOf("value"))
                GenericNapplet.exact(d,*keys); require(recordId.matches(text(d,"record_id")))
                val revision=integer(d,"base_revision",if(op=="create") 0 else 1); require(op!="create" || revision==0L)
                if(op!="delete") GenericJson.validate(d.getValue("value").jsonObject)
            }
            else -> error("Unknown operation")
        }
        return d
    }
    fun result(session: String,id: String,result: JsonObject): JsonObject = buildJsonObject {
        put("type","wingman-napplet-result"); put("version",3); put("session",session); put("requestId",id); put("status","ready"); put("result",result)
    }
    fun error(session: String,id: String,code: String): JsonObject = buildJsonObject {
        require(code in setOf("conflict","unavailable","unsupported"))
        put("type","wingman-napplet-result"); put("version",3); put("session",session); put("requestId",id); put("status","error"); put("code",code)
        put("message",when(code) { "conflict" -> "A newer revision exists."; "unsupported" -> "This host does not support the requested capability."; else -> "Operation could not be confirmed. Reopen and check before retrying a change." })
    }
}
