package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*

class WorkspaceReportQueryTest {
    @Test fun syncOwnerUsesOneFixedSignedReadAndRejectsIdentityUnavailableAndWrongRange(): Unit=runBlocking {
        val w=SampleIds.STUDIO
        val expected=buildJsonObject { put("workspace_id",w); put("app_npub",SampleIds.APP); put("tower_service_npub","service"); put("workspace_service_npub","authority"); put("workspace_owner_npub","owner") }
        val workspace=WorkspaceRecord(w,SampleIds.APP,"Synthetic",null,null,null,"owner",buildJsonObject { put("identity",expected) })
        val connection=ConnectionRecord.create(TowerOrigin("https://sample.invalid"),TestKeys.NPUB,null)
        var status=200
        var response=SampleWorkspaceReport.value(w,"30d",expected)
        val requests=mutableListOf<HttpRequest>()
        val wire=object: HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse { requests.add(request); return HttpResponse(status,response.toString().toByteArray()) }
        }
        val store=LocalStore(tempDir()); val engine=SyncEngine()
        val sync=WorkspaceSync(store,engine,makeIdentity(),wire)
        try {
            assertEquals(42,sync.readWorkspaceReport(connection,workspace,"30d").getValue("activity").jsonObject.getValue("messages").jsonPrimitive.int)
            assertEquals(1,requests.size)
            val request=requests.single()
            assertEquals("GET",request.method)
            val uri=java.net.URI(request.url)
            assertEquals(TowerAPI.base(w)+"/workspace-report",uri.path)
            assertEquals(setOf("range=30d","top_n=10"),uri.rawQuery.split('&').toSet())
            assertNotNull(request.header("Authorization")); assertEquals(SampleIds.APP,request.header("x-flightdeck-pg-app-npub"))
            assertTrue(runCatching { sync.readWorkspaceReport(connection,workspace,"custom") }.isFailure)
            assertEquals(1,requests.size)
            response=JsonObject(response+("identity" to JsonObject(expected+("app_npub" to JsonPrimitive("wrong")))))
            assertTrue(runCatching { sync.readWorkspaceReport(connection,workspace,"30d") }.isFailure)
            status=404
            assertTrue(runCatching { sync.readWorkspaceReport(connection,workspace,"30d") }.isFailure)
            assertTrue(requests.all { it.method=="GET" && java.net.URI(it.url).path.endsWith("/workspace-report") })
        } finally { engine.cancelAll(); engine.close(); store.close() }
    }
}
