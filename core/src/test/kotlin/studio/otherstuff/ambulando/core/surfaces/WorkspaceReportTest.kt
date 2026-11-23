package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import java.nio.file.Files

class WorkspaceReportTest {
    private val w=SampleIds.STUDIO
    private fun value()=SampleWorkspaceReport.value(w,"30d")
    private val manifest=Json.parseToJsonElement("""{"version":1,"app_id":"workspace-report","bindings":{"report":{"format":"workspace-report-v1","operations":["read"],"privacy":"viewer"}}}""").jsonObject
    private fun metadata(workspace: String=w)=buildJsonObject {
        put("html_artifact",true); put("artefact",ArtefactType.HTML.metadata().getValue("artefact"))
        put("napplet",buildJsonObject { put("version",1); put("app_id","workspace-report"); put("bindings",buildJsonObject { put("report",buildJsonObject { put("workspace_id",workspace) }) }) })
    }
    @Test fun exactActivityRemainderCurrentInventoryNineStatesAndEveryWindow() {
        WorkspaceReport.ranges.forEach { r -> assertEquals(SampleWorkspaceReport.value(w,r),WorkspaceReport.model(SampleWorkspaceReport.value(w,r),w,r)) }
        assertEquals(42,value().getValue("activity").jsonObject.getValue("messages").jsonPrimitive.int)
        assertEquals(12,value().getValue("inventory").jsonObject.getValue("tasks").jsonObject.getValue("total").jsonPrimitive.int)
    }
    @Test fun zeroIsValidButIdentityMismatchAndUnavailableAreNotZero() {
        val unsorted=Json.parseToJsonElement(value().toString().replace("\"messages\":42","\"messages\":0").replace("\"conversations\":9","\"conversations\":0").replace("\"messages\":20","\"messages\":0").replace("\"messages\":2","\"messages\":0").replace("\"conversations\":5","\"conversations\":0").replace("\"other_messages\":4","\"other_messages\":0").replace("\"other_conversations\":4","\"other_conversations\":0")).jsonObject
        val z=JsonObject(unsorted+("channels" to JsonArray(unsorted.getValue("channels").jsonArray.sortedBy { it.jsonObject.getValue("channel_id").jsonPrimitive.content })))
        assertEquals(z,WorkspaceReport.model(z,w,"30d"))
        val i=buildJsonObject { put("workspace_id",w); put("app_npub","app"); put("tower_service_npub","service"); put("workspace_service_npub","authority"); put("workspace_owner_npub","owner") }
        val response=JsonObject(z+("identity" to i))
        assertEquals(z,WorkspaceReport.response(response,i,w,"30d"))
        for(key in i.keys) assertTrue(runCatching { WorkspaceReport.response(response,JsonObject(i+(key to JsonPrimitive("wrong"))),w,"30d") }.isFailure)
        assertTrue(runCatching { WorkspaceReport.model(JsonObject(emptyMap()),w,"30d") }.isFailure)
    }
    @Test fun malformedIncompleteUnknownAndWrongContextFailClosed() {
        val raw=value().toString()
        listOf(raw.replace("\"complete\":true","\"complete\":false"),raw.replace("\"UTC\"","\"local\""),
            raw.replace("\"total\":12","\"total\":11"),raw.replace("\"archive\":1","\"unknown\":1"),
            raw.replace("\"other_messages\":4","\"other_messages\":11"),raw.replace("\"tasks_channels\":1","\"tasks_channels\":13"),
            raw.replace("\"messages\":20","\"messages\":9007199254740992"),raw.replace("\"messages\":20","\"messages\":\"30\""),
            raw.replace("\"messages\":20","\"messages\":-1"),raw.replace("2026-10-23","2026-10-24"),
            raw.replace("General (synthetic)","x".repeat(301)),raw.replace("\"returned_channels\":10","\"returned_channels\":9")
        ).forEach { assertTrue(it,runCatching { WorkspaceReport.model(Json.parseToJsonElement(it).jsonObject,w,"30d") }.isFailure) }
        assertTrue(runCatching { WorkspaceReport.model(value(),SampleIds.LAB,"30d") }.isFailure)
        assertTrue(runCatching { WorkspaceReport.model(value(),w,"7d") }.isFailure)
    }
    @Test fun readonlyEnvelopeRejectsWritesUrlsReplaysHandledByHostAndUnknownKeys() {
        val request="""{"type":"wingman-napplet-data","version":1,"session":"nonce","requestId":"r_1","operation":"read","binding":"report","range":"30d"}"""
        assertEquals("read",WorkspaceReport.text(WorkspaceReport.request(request,"nonce"),"operation"))
        listOf(request.replace("read","save"),request.replace("30d","1d"),request.replace("report","tracker"),request.replace("nonce","wrong"),request.dropLast(1)+",\"url\":\"https://example.com\"}").forEach {
            assertTrue(runCatching { WorkspaceReport.request(it,"nonce") }.isFailure)
        }
    }
    @Test fun consentIsDurableCodeAndContextBoundAndTrackerParserStaysNarrow() {
        fun receipt(workspace: String=w,viewer: String="viewer",html: String="verified")=NappletApproval.report("https://example.com","connection","service","android",workspace,"app",viewer,SampleIds.GENERAL,html,metadata(workspace),manifest)
        val dir=Files.createTempDirectory("report-consent").toFile()
        try {
            val store=NappletApprovalStore(dir); val r=receipt()
            assertFalse(store.approved(r)); assertTrue(store.remember(r)); assertTrue(NappletApprovalStore(dir).approved(receipt()))
            for(other in listOf(receipt(viewer="other"),receipt(workspace=SampleIds.LAB),receipt(html="changed"))) assertFalse(store.approved(other))
            assertTrue(store.forget(r)); assertFalse(store.approved(r))
            assertTrue(runCatching { FileNapplet.binding(metadata(),manifest) }.isFailure)
            assertEquals(w,WorkspaceReport.binding(metadata(),manifest))
            assertTrue(runCatching { WorkspaceReport.binding(metadata(),Json.parseToJsonElement(manifest.toString().replace("[\"read\"]","[\"read\",\"save\"]")).jsonObject) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
}
