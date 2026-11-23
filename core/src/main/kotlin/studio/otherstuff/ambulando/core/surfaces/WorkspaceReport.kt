package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*
import java.time.Instant

/** Independent read-only contract; never extends the daily tracker parser. */
object WorkspaceReport {
    const val ID = "workspace-report"
    const val FORMAT = "workspace-report-v1"
    fun path(workspace: String)="/flight-deck?workspaceid=${TowerAPI.uuid(workspace)}&napplet=$ID"
    val ranges = setOf("7d", "30d", "90d", "all")
    val states = setOf("new", "ready", "in_progress", "review", "done", "archive", "backlog", "blocked", "archived")
    fun exact(o: JsonObject, vararg keys: String) = require(o.keys == keys.toSet())
    fun text(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid string")
    private fun count(o: JsonObject, k: String): Long = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.also { require(it in 0..9007199254740991L) } ?: error("Invalid count")
    private fun sum(v: List<Long>) = v.fold(0L) { a, b -> Math.addExact(a,b).also { require(it <= 9007199254740991L) } }
    fun binding(metadata: JsonObject, manifest: JsonObject): String {
        exact(manifest, "version", "app_id", "bindings")
        require(manifest["version"] == JsonPrimitive(1) && text(manifest,"app_id") == ID)
        val declarations = manifest.getValue("bindings").jsonObject; exact(declarations,"report")
        val d = declarations.getValue("report").jsonObject; exact(d,"format","operations","privacy")
        require(text(d,"format") == FORMAT && text(d,"privacy") == "viewer" && d["operations"] == JsonArray(listOf(JsonPrimitive("read"))))
        require(metadata["html_artifact"] == JsonPrimitive(true) && metadata["artefact"] == ArtefactType.HTML.metadata()["artefact"])
        val n=metadata.getValue("napplet").jsonObject; exact(n,"version","app_id","bindings")
        require(n["version"] == JsonPrimitive(1) && text(n,"app_id") == ID)
        val b=n.getValue("bindings").jsonObject; exact(b,"report")
        val r=b.getValue("report").jsonObject; exact(r,"workspace_id")
        return text(r,"workspace_id").also { require(isUUID(it)) }
    }
    fun request(raw: String, session: String): JsonObject {
        require(raw.toByteArray().size <= 1024)
        val o=Json.parseToJsonElement(raw).jsonObject
        exact(o,"type","version","session","requestId","operation","binding","range")
        require(text(o,"type") == "wingman-napplet-data" && o["version"] == JsonPrimitive(1))
        require(text(o,"session") == session && text(o,"requestId").matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(text(o,"operation") == "read" && text(o,"binding") == "report" && text(o,"range") in ranges)
        return o
    }
    fun response(response: JsonObject, identity: JsonObject, workspace: String, range: String): JsonObject {
        require(identity.string("workspace_id")==workspace && response.getValue("identity").jsonObject==identity)
        return model(JsonObject(response.filterKeys { it!="identity" }),workspace,range)
    }
    /** Validate identity in the query owner; only this whitelisted aggregate enters the frame. */
    fun model(v: JsonObject, workspace: String, range: String): JsonObject {
        require(isUUID(workspace) && v.toString().toByteArray().size <= 65536)
        exact(v,"format","workspace_id","range","from","to","as_of","timezone","complete","channel_policy","channels","activity","inventory","ranking","coverage")
        require(text(v,"format")==FORMAT && text(v,"workspace_id")==workspace && text(v,"range")==range && range in ranges)
        require(v["complete"]==JsonPrimitive(true) && text(v,"timezone")=="UTC" && text(v,"channel_policy")=="active-project-channels")
        fun date(key: String): Instant { val s=text(v,key); require(s.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))); val instant=Instant.parse(s); require(java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant)==s); return instant }
        val end=date("as_of"); require(v["to"]==v["as_of"])
        if(range=="all") require(v["from"]==JsonNull) else require(date("from")==end.minusSeconds(range.removeSuffix("d").toLong()*86400))
        val rows=v.getValue("channels").jsonArray.map { it.jsonObject }; require(rows.size<=10)
        val ids=mutableSetOf<String>()
        rows.forEach { c ->
            exact(c,"channel_id","scope_id","name","scope_name","messages","conversations")
            require(isUUID(text(c,"channel_id")) && isUUID(text(c,"scope_id")) && ids.add(text(c,"channel_id")))
            require(text(c,"name").length<=300 && text(c,"scope_name").length<=300)
            require(count(c,"conversations")<=count(c,"messages"))
        }
        rows.zipWithNext().forEach { (a,b) -> require(count(a,"messages")>count(b,"messages") || (count(a,"messages")==count(b,"messages") && text(a,"channel_id")<text(b,"channel_id"))) }
        val r=v.getValue("ranking").jsonObject; exact(r,"total_channels","returned_channels","has_more","other_messages","other_conversations")
        val total=count(r,"total_channels"); require(total<=2000 && count(r,"returned_channels")==rows.size.toLong() && rows.size<=total && rows.size.toLong()==minOf(total,10L))
        val more=rows.size<total; require(r["has_more"]==JsonPrimitive(more) && (!more || rows.isNotEmpty()))
        require(count(r,"other_conversations")<=count(r,"other_messages"))
        if(!more) require(count(r,"other_messages")==0L && count(r,"other_conversations")==0L)
        val a=v.getValue("activity").jsonObject; exact(a,"messages","conversations")
        for(k in listOf("messages","conversations")) require(count(a,k)==sum(rows.map { count(it,k) }+count(r,"other_$k")))
        val coverage=v.getValue("coverage").jsonObject; exact(coverage,"tasks_channels","documents_channels","files_channels")
        coverage.keys.forEach { require(count(coverage,it)<=total) }
        val i=v.getValue("inventory").jsonObject; exact(i,"channels","conversations","tasks","documents","files")
        require(count(i,"channels")==total); listOf("conversations","documents","files").forEach { count(i,it) }
        val t=i.getValue("tasks").jsonObject; exact(t,"total","open","completed","archived","by_state")
        val s=t.getValue("by_state").jsonObject; require(s.keys==states)
        require(count(t,"total")==sum(states.map { count(s,it) }) && count(t,"completed")==count(s,"done"))
        require(count(t,"archived")==sum(listOf(count(s,"archive"),count(s,"archived"))))
        require(count(t,"total")==sum(listOf(count(t,"open"),count(t,"completed"),count(t,"archived"))))
        return v
    }
}
