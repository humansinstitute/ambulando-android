package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI
import java.util.UUID

/** Signed synthetic File/CAS flow. This is explicitly not live NStack/native installation proof. */
class GenericNappletFlowTest {
    @Test fun unknownAppsCanonicalCASIsolationUpdateAndRevocation()=runBlocking {
        val h=SampleHarness(); var deny=false; var foreign=false; var current=true; var readOnly=false; var topFlag: JsonElement?=null
        val wire=object: HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path=URI(request.url).path
                if(deny && path.contains("/files/")) return HttpResponse(403,"{}".toByteArray())
                if(request.method=="GET" && path.matches(Regex("${h.base()}/channels/[a-f0-9-]+"))) {
                    val channel=h.api.scopes(SampleIds.STUDIO,h.app).flatMap { h.api.channels(SampleIds.STUDIO,it.id,h.app) }.first { it.id==path.substringAfterLast('/') }
                    val raw=if(foreign) JsonObject(channel.raw+("participant_npubs" to JsonArray(listOf(JsonPrimitive(SampleSigners.otherNpub))))) else channel.raw
                    return HttpResponse(200,buildJsonObject { put("channel",raw) }.toString().toByteArray())
                }
                val response=h.tower.send(request)
                if(readOnly && request.method=="GET" && path.matches(Regex("${h.base()}/files/[a-f0-9-]+")) && response.status==200) {
                    val root=Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                    return HttpResponse(200,JsonObject(root+("file" to JsonObject(root.getValue("file").jsonObject+("can_write" to JsonPrimitive(false))))).toString().toByteArray())
                }
                if(topFlag!=null && request.method=="GET" && path.matches(Regex("${h.base()}/files/[a-f0-9-]+")) && response.status==200) {
                    val root=Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                    return HttpResponse(200,JsonObject(root+("can_write" to topFlag!!)).toString().toByteArray())
                }
                return response
            }
        }
        val api=TowerAPI(TowerClient(h.tower.origin,wire,SampleSigners.primary))
        // Bootstrap a self-channel only in the test fixture; product generic installation never provisions.
        val channel=api.createDailyTracker(SampleIds.STUDIO,h.app,TestKeys.NPUB,"<h1>legacy</h1>").channel
        suspend fun file(name: String,bytes: ByteArray,mime: String,metadata: JsonObject): FileRecord {
            val id=api.store(SampleIds.STUDIO,h.app,bytes,mime,name,null) { }
            val reply=api.client.request(method="POST",path=h.base()+"/channels/${channel.id}/files",appNpub=h.app,jsonBody=jsonBody(buildJsonObject { put("storage_object_id",id); put("display_name",name); put("metadata",metadata) }))
            return FileRecord.parse(Json.parseToJsonElement(reply.toString(Charsets.UTF_8)).jsonObject.getValue("file").jsonObject)
        }
        data class App(val code: FileRecord,val html: String,val binding: GenericNapplet.Binding)
        suspend fun app(): App {
            val data=file("data.json","{}".toByteArray(),"application/json",JsonObject(emptyMap()))
            val id="app-"+UUID.randomUUID().toString(); val name="b"+UUID.randomUUID().toString().replace("-", "").take(20)
            val manifest=buildJsonObject { put("version",2); put("app_id",id); put("title",id); put("icon","🌱"); put("bindings",buildJsonObject { put(name,buildJsonObject { put("capability","private-json-file"); put("version",1); put("operations",JsonArray(listOf(JsonPrimitive("read"),JsonPrimitive("save")))) }) }) }
            val meta=buildJsonObject { put("napplet",buildJsonObject { put("version",2); put("app_id",id); put("bindings",buildJsonObject { put(name,buildJsonObject { put("file_id",data.id) }) }) }) }
            val html="<script type=\"application/json\" id=\"wingman-napplet-manifest\">$manifest</script><h1>Original</h1>"
            return App(file("code.html",html.toByteArray(),"text/html",meta),html,GenericNapplet.parse(html,meta)!!)
        }
        val a=app(); val b=app()
        suspend fun host(a: App,html: String=a.html): GenericNappletTransport {
            val version=api.fileVersions(SampleIds.STUDIO,h.app,a.code.id).currentVersionId!!
            return GenericNappletTransport(api,SampleIds.STUDIO,h.app,TestKeys.NPUB,a.code.id,version,html,a.code.raw.getValue("metadata").jsonObject,a.binding,{current}) { error("No aggregate") }
        }
        val one=host(a); val stale=host(a); val initial=one.read(); stale.read()
        val input=GenericJson.decode("{\"zero\":0,\"answered\":false,\"null\":null,\"rows\":[1e-7,1e-6]}")
        val value=GenericJson.decode(GenericJson.canonical(input))
        val saved=one.save(initial.version,value)
        assertArrayEquals(GenericJson.canonical(value).toByteArray(),saved.bytes)
        assertEquals(value,host(a).read().value); assertEquals(JsonObject(emptyMap()),host(b).read().value)
        assertTrue(runCatching { stale.save(initial.version,value) }.exceptionOrNull() is GenericNappletTransport.Conflict)
        val before=api.fileVersions(SampleIds.STUDIO,h.app,a.binding.resource)
        val codeVersion=api.fileVersions(SampleIds.STUDIO,h.app,a.code.id).currentVersionId!!
        val updated=a.html.replace("Original","Updated")
        val objectId=api.store(SampleIds.STUDIO,h.app,updated.toByteArray(),"text/html","updated.html",null) { }
        assertTrue(api.addFileVersion(SampleIds.STUDIO,h.app,a.code.id,codeVersion,objectId,UUID.randomUUID().toString()) is VersionOutcome.Saved)
        assertTrue(runCatching { one.authorize() }.isFailure)
        assertEquals(value,host(a,updated).read().value)
        assertEquals(before,api.fileVersions(SampleIds.STUDIO,h.app,a.binding.resource))
        readOnly=true
        val ro=host(b); val read=ro.read(); assertFalse(read.canWrite)
        assertTrue(runCatching { ro.save(read.version,value) }.isFailure); readOnly=false
        topFlag=JsonPrimitive(false); assertFalse(host(b).read().canWrite)
        topFlag=JsonNull; assertTrue(runCatching { host(b).read() }.isFailure); topFlag=null
        val dataFile=api.artefactFile(SampleIds.STUDIO,h.app,b.binding.resource)
        repeat(205) { h.tower.work.externalFileVersion(b.binding.resource,dataFile.storageObjectId!!) }
        assertEquals(JsonObject(emptyMap()),host(b).read().value)
        foreign=true; assertTrue(runCatching { host(b).read() }.isFailure); foreign=false
        current=false; assertTrue(runCatching { host(b).read() }.isFailure); current=true
        deny=true; assertTrue(runCatching { host(b).read() }.isFailure)
    }
}
