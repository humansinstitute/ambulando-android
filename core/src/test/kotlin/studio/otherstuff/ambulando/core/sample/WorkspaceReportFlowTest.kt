package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

class WorkspaceReportFlowTest {
    private val manifest=Json.parseToJsonElement("""{"version":1,"app_id":"workspace-report","bindings":{"report":{"format":"workspace-report-v1","operations":["read"],"privacy":"viewer"}}}""").jsonObject
    @Test fun exactPrivateCodeAuthorizationReadResultAndRevocationRaces()=runBlocking {
        val h=SampleHarness(); var deny=false; var foreign=false; var current=true; var queryCount=0; var mutate=false; var writes=0
        val wire=object: HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path=URI(request.url).path
                if(request.method!="GET") writes++
                if(deny && path.contains("/files/")) return HttpResponse(403,"{}".toByteArray())
                if(request.method=="GET" && path.matches(Regex("${h.base()}/channels/[a-f0-9-]+"))) {
                    val id=path.substringAfterLast('/')
                    val channel=h.api.scopes(SampleIds.STUDIO,h.app).flatMap { h.api.channels(SampleIds.STUDIO,it.id,h.app) }.first { it.id==id }
                    val raw=if(foreign) JsonObject(channel.raw+("participant_npubs" to JsonArray(listOf(JsonPrimitive(SampleSigners.otherNpub))))) else channel.raw
                    return HttpResponse(200,buildJsonObject { put("channel",raw) }.toString().toByteArray())
                }
                return h.tower.send(request)
            }
        }
        val api=TowerAPI(TowerClient(h.tower.origin,wire,SampleSigners.primary))
        val html="<h1>Verified report</h1>"
        val code=api.createWorkspaceReport(SampleIds.STUDIO,h.app,TestKeys.NPUB,html).code
        val version=api.fileVersions(SampleIds.STUDIO,h.app,code.id).currentVersionId!!
        fun host(source: String=html)=WorkspaceReportTransport(api,SampleIds.STUDIO,h.app,TestKeys.NPUB,code.id,version,source,code.raw.getValue("metadata").jsonObject,manifest,{current}) { range ->
            queryCount++; if(mutate) deny=true
            SampleWorkspaceReport.value(SampleIds.STUDIO,range)
        }
        writes=0
        assertEquals(42,host().read("30d").getValue("activity").jsonObject.getValue("messages").jsonPrimitive.int)
        assertEquals(1,queryCount); assertEquals(0,writes)
        assertTrue(runCatching { host("changed code bytes").authorize() }.isFailure)
        foreign=true; assertTrue(runCatching { host().read("30d") }.isFailure); foreign=false
        current=false; assertTrue(runCatching { host().read("30d") }.isFailure); current=true
        mutate=true; assertTrue("Denial between snapshot and result must suppress all aggregates",runCatching { host().read("30d") }.isFailure)
        deny=false; mutate=false
        val objectId=api.store(SampleIds.STUDIO,h.app,"changed".toByteArray(),"text/html","changed.html",null) { }
        api.addFileVersion(SampleIds.STUDIO,h.app,code.id,version,objectId,java.util.UUID.randomUUID().toString())
        assertTrue(runCatching { host().authorize() }.isFailure)
    }
}
