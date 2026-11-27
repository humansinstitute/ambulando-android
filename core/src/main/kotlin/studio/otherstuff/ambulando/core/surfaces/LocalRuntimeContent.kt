package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** Durable snapshots of the existing runtime projection, bound to authenticated context and connection lifecycle. */
class LocalRuntimeContent(private val store: LocalStore,private val connection: ConnectionRecord,private val workspace: WorkspaceRecord,private val current: () -> Boolean) {
    private val context=NappletRuntimeContext.create(connection,workspace)
    private val prefix="napplet.runtime.content.v1:"+Sha256Hex.of((connection.id+GenericJson.canonical(context)).toByteArray())+":"
    private suspend fun check() = store.reader.withArtefactAccess(workspace,"runtime",current) { Unit }
    private suspend fun get(part: String): JsonObject? {
        check()
        return try {
            val o=Json.parseToJsonElement(store.reader.state(prefix+part) ?: return null).jsonObject
            val value=o.getValue("value").jsonObject
            require(o["context"]==context && o.string("sha256")==Sha256Hex.of(value.toString().toByteArray()))
            check(); value
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    }
    private suspend fun put(part: String,value: JsonObject) {
        check()
        store.writer.setState(prefix+part,buildJsonObject { put("context",context);put("value",value);put("sha256",Sha256Hex.of(value.toString().toByteArray())) }.toString())
        check()
    }
    suspend fun installations(): List<NappletRuntimeInstallation> = get("projection")?.getValue("installations")?.jsonArray?.mapNotNull { row ->
        runCatching { NappletRuntimeInstallation(row.jsonObject).validate(workspace.id,workspace.appNpub).also { it.requireAudience(connection.signerNpub) } }.getOrNull()
    }.orEmpty()
    suspend fun saveInstallations(items: List<NappletRuntimeInstallation>) = put("projection",buildJsonObject { put("installations",JsonArray(items.map { it.raw })) })
    suspend fun open(id: String): Pair<NappletRuntimeInstallation,String>? {
        val known=installations().singleOrNull { it.id==id } ?: return null
        val saved=get("bundle:$id") ?: return null
        return runCatching {
            val i=NappletRuntimeInstallation(saved.getValue("installation").jsonObject).validate(workspace.id,workspace.appNpub)
            require(i.raw==known.raw); i.requireAudience(connection.signerNpub)
            val file=NappletRuntimeProtocol.text(i.bundle,"file_id")
            store.reader.withArtefactAccess(workspace,file,current) {
                i to i.verify(saved.getValue("html").jsonPrimitive.content.toByteArray())
            }
        }.onFailure {if(it is CancellationException) throw it}.getOrNull()
    }
    suspend fun saveBundle(i: NappletRuntimeInstallation,html: String) {
        i.verify(html.toByteArray())
        val items=installations().filterNot { it.id==i.id }+i
        saveInstallations(items)
        put("bundle:${i.id}",buildJsonObject { put("installation",i.raw);put("html",html) })
    }
    private fun requestKey(i: NappletRuntimeInstallation,request: JsonObject): String = "records:${i.id}:"+Sha256Hex.of(GenericJson.canonical(buildJsonObject {
        put("installation",i.raw);put("request",JsonObject(request.filterKeys { it !in setOf("session","requestId") }))
    }).toByteArray())
    suspend fun read(i: NappletRuntimeInstallation,request: JsonObject): JsonObject? {
        require(NappletRuntimeProtocol.text(request,"operation") in setOf("list","get"))
        val known=installations().singleOrNull { it.id==i.id } ?: return null
        require(known.raw==i.raw)
        return get(requestKey(i,request))?.getValue("result")?.jsonObject
    }
    suspend fun saveRead(i: NappletRuntimeInstallation,request: JsonObject,result: JsonObject) = put(requestKey(i,request),buildJsonObject { put("request",request);put("result",result) })
    suspend fun savedRecord(i: NappletRuntimeInstallation,request: JsonObject,record: JsonObject) {
        val base=prefix+"records:${i.id}:"
        val parts=store.reader.read { query("SELECT key FROM app_state WHERE substr(key,1,?) = ?",base.length,base) { it.text(0)!! } }.map { it.removePrefix(prefix) }
        for(part in parts) {
            val page=get(part) ?: continue
            val read=page.getValue("request").jsonObject
            if(read["collection"]!=request["collection"]) continue
            val op=read.string("operation")
            val old=page.getValue("result").jsonObject
            val result=when {
                op=="get" && read["record_id"]==record["id"] -> buildJsonObject { put("record",record) }
                op=="list" && read["cursor"]==JsonNull && old["next_cursor"]==JsonNull -> {
                    val records=(old.getValue("records").jsonArray.map { it.jsonObject }.filterNot { it["id"]==record["id"] }+if(record["deleted"]==JsonPrimitive(false)) listOf(record) else emptyList()).sortedBy { it.string("id") }
                    val limit=NappletRuntimeProtocol.integer(read,"limit").toInt()
                    buildJsonObject { put("records",JsonArray(records.take(limit)));put("next_cursor",if(records.size>limit) records[limit-1].getValue("id") else JsonNull) }
                }
                else -> null
            }
            if(result!=null) put(part,buildJsonObject {put("request",read);put("result",result)}) else store.writer.setState(prefix+part,null)
        }
        val getRequest=JsonObject(request.filterKeys { it !in setOf("base_revision","value") }+mapOf("operation" to JsonPrimitive("get")))
        saveRead(i,getRequest,buildJsonObject {put("record",record)})
    }
    suspend fun invalidate(id: String,expected: JsonObject?=null) {
        check()
        val cacheKey=prefix+"projection"
        val raw=store.reader.state(cacheKey) ?: return
        val envelope=Json.parseToJsonElement(raw).jsonObject
        val value=envelope.getValue("value").jsonObject
        require(envelope["context"]==context && envelope.string("sha256")==Sha256Hex.of(value.toString().toByteArray()))
        val items=value.getValue("installations").jsonArray.map { NappletRuntimeInstallation(it.jsonObject).validate(workspace.id,workspace.appNpub) }
        if(expected!=null && items.firstOrNull {it.id==id}?.raw!=expected) return
        val next=buildJsonObject {put("installations",JsonArray(items.filterNot {it.id==id}.map {it.raw}))}
        val encoded=buildJsonObject {put("context",context);put("value",next);put("sha256",Sha256Hex.of(next.toString().toByteArray()))}.toString()
        store.writer.write {
            check(current())
            // Compare the entire observed projection inside the same transaction as eviction.
            val live=db.first("SELECT value FROM app_state WHERE key = ?",cacheKey) {it.text(0)}
            if(live!=raw) return@write
            db.run("UPDATE app_state SET value = ? WHERE key = ?",encoded,cacheKey)
            db.run("DELETE FROM app_state WHERE key = ?",prefix+"bundle:$id")
            db.run("DELETE FROM app_state WHERE substr(key,1,?) = ?", (prefix+"records:$id:").length,prefix+"records:$id:")
        }
    }
}
