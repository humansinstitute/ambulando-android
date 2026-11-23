package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.SampleWorkspaceReport

class WorkspaceStorageTest {
    private val frozen=GenericJson.decode(javaClass.getResource("/napplets/workspace-storage-v1.json")!!.readBytes())
    private val workspace=WorkspaceReport.text(frozen,"workspace_id")
    private val base=SampleWorkspaceReport.value(workspace,"30d")
    private val storage=JsonObject(frozen+("as_of" to base.getValue("as_of")))
    private fun report(s: JsonObject=storage)=JsonObject(base+("storage" to s))
    private fun change(key: String,value: JsonElement)=JsonObject(storage+(key to value))
    private fun metric(m: JsonObject): JsonObject = change("groups",buildJsonArray { add(buildJsonObject { put("id","future_group"); put("metrics",buildJsonArray { add(m) }) }) })
    private val validMetric=GenericJson.decode("""{"id":"future_metric","value":12,"unit":"bytes","method":"future-method","status":"estimated"}""")
    @Test fun frozenFixtureAndFutureMetricsRemainGenericAndUnavailableRemainsNull() {
        val golden=GenericJson.decode(javaClass.getResource("/napplets/workspace-report-storage-v1.json")!!.readBytes())
        val value=golden.getValue("value").jsonObject
        assertEquals(value,WorkspaceStorage.model(value,workspace,"all"))
        assertTrue(GenericNapplet.parse("<script id=\"wingman-napplet-manifest\">${golden.getValue("manifest")}</script>",golden.getValue("metadata").jsonObject)!!.storage)
        assertEquals("e03c008e2264a481b3967cea2a1f9d4016453494c4067af3fe0c1086d0a51326",studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(javaClass.getResource("/napplets/workspace-storage-v1.json")!!.readBytes()))
        assertEquals(report(),WorkspaceStorage.model(report(),workspace,"30d"))
        val future=report(metric(validMetric)); assertEquals(future,WorkspaceStorage.model(future,workspace,"30d"))
        assertTrue(runCatching { WorkspaceReport.model(report(),workspace,"30d") }.isFailure)
        assertEquals(base,WorkspaceReport.model(base,workspace,"30d"))
        assertTrue(runCatching { WorkspaceStorage.model(base,workspace,"30d") }.isFailure)
    }
    @Test fun malformedBoundsIdentityAndStatusFailClosed() {
        val invalid=mutableListOf<JsonObject>()
        invalid+=change("workspace_id",JsonPrimitive("00000000-0000-4000-8000-000000000002"))
        invalid+=change("as_of",JsonPrimitive("2026-01-02T00:00:00.000Z"))
        invalid+=change("activity_range_applies",JsonPrimitive(true))
        invalid+=change("provider_measured_at",JsonPrimitive("2099-01-02T00:00:00.000Z"))
        invalid+=change("last_object_completed_at",JsonPrimitive("bad"))
        invalid+=change("groups",JsonArray(List(9) { storage.getValue("groups").jsonArray.first() }))
        invalid+=change("groups",JsonArray(List(2) { storage.getValue("groups").jsonArray.first() }))
        invalid+=change("caveats",JsonArray(List(17) {JsonPrimitive("caveat")}))
        invalid+=change("caveats",JsonArray(listOf(JsonPrimitive("x".repeat(1025)))))
        invalid+=change("caveats",JsonArray(listOf(JsonPrimitive("line\nbreak"))))
        for((k,v) in listOf("value" to JsonNull,"value" to JsonPrimitive(-1),"value" to JsonPrimitive(0.1),"value" to JsonPrimitive(-0.0),"value" to JsonPrimitive(9007199254740992L),"value" to JsonPrimitive("12"),"id" to JsonPrimitive("x".repeat(65)),"id" to JsonPrimitive("../url"),"unit" to JsonPrimitive("url"),"status" to JsonPrimitive("unknown"),"status" to JsonPrimitive("unavailable"),"method" to JsonPrimitive("<script>"))) invalid+=metric(JsonObject(validMetric+(k to v)))
        val duplicate=buildJsonObject { put("id","group"); put("metrics",JsonArray(listOf(validMetric,validMetric))) }
        invalid+=change("groups",JsonArray(listOf(duplicate)))
        invalid+=change("groups",JsonArray(listOf(JsonObject(duplicate+("metrics" to JsonArray(List(33) { validMetric }))))))
        invalid+=JsonObject(storage+("url" to JsonPrimitive("https://example.invalid")))
        invalid.forEachIndexed { index,s -> assertTrue("case $index",runCatching {WorkspaceStorage.model(report(s),workspace,"30d")}.isFailure) }
        val unavailable=JsonObject(validMetric+mapOf("status" to JsonPrimitive("unavailable"),"value" to JsonNull))
        WorkspaceStorage.model(report(metric(unavailable)),workspace,"30d")
        val identity=buildJsonObject { put("workspace_id",workspace); put("service","one") }
        val response=JsonObject(report()+("identity" to identity))
        assertEquals(report(),WorkspaceStorage.response(response,identity,workspace,"30d"))
        assertTrue(runCatching {WorkspaceStorage.response(response,JsonObject(identity+("service" to JsonPrimitive("two"))),workspace,"30d")}.isFailure)
    }
}
