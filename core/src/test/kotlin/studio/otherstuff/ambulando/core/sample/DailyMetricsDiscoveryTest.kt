package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

class DailyMetricsDiscoveryTest {
    @Test fun emptySetupExistingMetadataAdoptionAndMultipleInstances() = runBlocking {
        val h = SampleHarness()
        assertTrue(h.api.dailyMetricsInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB).isEmpty())
        val first = h.api.createDailyTracker(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Tracker</h1>")
        repeat(3) { assertEquals(listOf(first.code.id), h.api.dailyMetricsInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB).map { it.id }) }
        assertTrue(h.api.dailyMetricsInstances(SampleIds.LAB, h.app, TestKeys.NPUB).isEmpty())
        assertTrue(h.api.dailyMetricsInstances(SampleIds.STUDIO, h.app, SampleSigners.otherNpub).isEmpty())
        val second = h.api.createDailyTracker(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Second</h1>")
        assertEquals(setOf(first.code.id, second.code.id), h.api.dailyMetricsInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB).map { it.id }.toSet())
        assertEquals(first.data.id, h.api.artefactFile(SampleIds.STUDIO, h.app, first.data.id).id)
    }

    @Test fun incompleteDeniedOrMalformedDiscoveryCannotEnableSetup() = runBlocking {
        val h = SampleHarness()
        h.api.createDailyTracker(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Tracker</h1>")
        for (mode in listOf("denied", "partial", "has_more", "wrong_workspace", "full_page")) {
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
                            "full_page" -> JsonObject(value + ("files" to JsonArray(List(200) { rows.first() })))
                            else -> JsonObject(value + ("files" to JsonArray(rows.map { JsonObject(it.jsonObject + ("workspace_id" to JsonPrimitive(SampleIds.LAB))) })))
                        }
                        return HttpResponse(200, changed.toString().toByteArray())
                    }
                    return h.tower.send(request)
                }
            }
            val api = TowerAPI(TowerClient(h.tower.origin, transport, SampleSigners.primary))
            assertTrue(mode, runCatching { api.dailyMetricsInstances(SampleIds.STUDIO, h.app, TestKeys.NPUB) }.isFailure)
        }
    }
}
