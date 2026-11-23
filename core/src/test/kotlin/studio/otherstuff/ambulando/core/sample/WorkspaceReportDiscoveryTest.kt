package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

class WorkspaceReportDiscoveryTest {
    @Test fun emptySetupExistingMetadataAdoptionAndMultipleInstances() = runBlocking {
        val h = SampleHarness()
        assertTrue(h.api.workspaceReportInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB).isEmpty())
        val first = h.api.createWorkspaceReport(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Tracker</h1>")
        repeat(3) { assertEquals(listOf(first.code.id), h.api.workspaceReportInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB).map { it.id }) }
        assertTrue(h.api.workspaceReportInstances(SampleIds.LAB, h.app, TestKeys.NPUB).isEmpty())
        assertTrue(h.api.workspaceReportInstances(SampleIds.STUDIO, h.app, SampleSigners.otherNpub).isEmpty())
        val second = h.api.createWorkspaceReport(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Second</h1>")
        assertEquals(setOf(first.code.id, second.code.id), h.api.workspaceReportInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB).map { it.id }.toSet())
        assertEquals(1, h.api.fileVersions(SampleIds.STUDIO,h.app,first.code.id).versions.size)
    }

    @Test fun failuresAtEveryTraversalStageRemainFailuresAndNeverWrite() = runBlocking {
        val h = SampleHarness()
        h.api.createWorkspaceReport(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Report</h1>")
        for ((collection, stage) in listOf("scopes" to ReportStage.SCOPES, "channels" to ReportStage.CHANNELS, "files" to ReportStage.FILES)) {
            for (mode in listOf("denied", "partial", "malformed", "full_page")) {
                var writes = 0
                val log = DiagnosticsLog()
                val transport = object : HttpTransport {
                    override suspend fun send(request: HttpRequest): HttpResponse {
                        if (request.method != "GET") writes++
                        if (URI(request.url).path.endsWith("/$collection")) {
                            if (mode == "denied") return HttpResponse(403, "{}".toByteArray())
                            if (mode == "malformed") return HttpResponse(200, "{".toByteArray())
                            val response = h.tower.send(request)
                            val value = Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                            val rows = value.getValue(collection).jsonArray
                            val changed = if (mode == "partial") JsonObject(value + ("partial" to JsonPrimitive(true)))
                                else JsonObject(value + (collection to JsonArray(List(200) { rows.first() })))
                            return HttpResponse(200, changed.toString().toByteArray())
                        }
                        return h.tower.send(request)
                    }
                }
                val api = TowerAPI(TowerClient(h.tower.origin, transport, SampleSigners.primary))
                assertTrue("$collection $mode", runCatching {
                    api.workspaceReportInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB, ReportDiagnostics(log))
                }.isFailure)
                assertEquals(0, writes)
                assertTrue(log.export("test", 1, 36).contains("report=${stage.name}"))
            }
        }
    }

    @Test fun incompleteDeniedOrMalformedDiscoveryCannotEnableSetup() = runBlocking {
        val h = SampleHarness()
        h.api.createWorkspaceReport(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Tracker</h1>")
        for (mode in listOf("denied", "partial", "has_more", "wrong_workspace", "full_page", "duplicate")) {
            val transport = object : HttpTransport {
                override suspend fun send(request: HttpRequest): HttpResponse {
                    if (request.method == "GET" && URI(request.url).path.endsWith("/files")) {
                        if (mode == "denied") return HttpResponse(403, "{}".toByteArray())
                        val response = h.tower.send(request)
                        val value = Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                        val rows = value.getValue("files").jsonArray
                        val changed = when (mode) {
                            "partial" -> JsonObject(value + ("partial" to JsonPrimitive(true)))
                            "has_more" -> JsonObject(value + ("has_more" to JsonPrimitive(true)))
                            "duplicate" -> JsonObject(value + ("files" to JsonArray(listOf(rows.first(),rows.first()))))
                            "full_page" -> JsonObject(value + ("files" to JsonArray(List(200) { rows.first() })))
                            else -> JsonObject(value + ("files" to JsonArray(rows.map { JsonObject(it.jsonObject + ("workspace_id" to JsonPrimitive(SampleIds.LAB))) })))
                        }
                        return HttpResponse(200, changed.toString().toByteArray())
                    }
                    return h.tower.send(request)
                }
            }
            val api = TowerAPI(TowerClient(h.tower.origin, transport, SampleSigners.primary))
            assertTrue(mode, runCatching { api.workspaceReportInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB) }.isFailure)
        }
    }
}
