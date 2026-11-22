package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

/** Signed requests against synthetic Tower storage; no real member identity or network. */
class FileNappletFlowTest {
    private val manifest = Json.parseToJsonElement("""{"version":1,"app_id":"daily-tracker","bindings":{"tracker":{"format":"daily-tracker-v1","operations":["read","save"],"privacy":"self"}}}""").jsonObject
    @Test fun createReloadSaveHistoryConflictAndRevocation() = runBlocking {
        val h = SampleHarness()
        var deny = false
        var foreign = false
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                if (deny && path.contains("/files/")) return HttpResponse(403, "{}".toByteArray())
                if (request.method == "GET" && path.matches(Regex("${h.base()}/channels/[a-f0-9-]+"))) {
                    val id = path.substringAfterLast('/')
                    val channel = h.api.scopes(SampleIds.STUDIO, h.app).flatMap { h.api.channels(SampleIds.STUDIO, it.id, h.app) }.first { it.id == id }
                    val raw = if (foreign) JsonObject(channel.raw + ("participant_npubs" to JsonArray(listOf(JsonPrimitive(SampleSigners.otherNpub))))) else channel.raw
                    return HttpResponse(200, buildJsonObject { put("channel", raw) }.toString().toByteArray())
                }
                return h.tower.send(request)
            }
        }
        val api = TowerAPI(TowerClient(h.tower.origin, transport, SampleSigners.primary))
        val code = api.createDailyTracker(SampleIds.STUDIO, h.app, TestKeys.NPUB, "<h1>Tracker</h1>").code
        val dataId = FileNapplet.binding(code.raw.getValue("metadata").jsonObject, manifest)
        val version = api.fileVersions(SampleIds.STUDIO, h.app, code.id).currentVersionId!!
        fun host() = FileNappletTransport(api, SampleIds.STUDIO, h.app, TestKeys.NPUB, code.id, version, manifest, dataId)
        // Current-only reads still work beyond the ordinary history viewer's 200-version limit.
        val data = api.artefactFile(SampleIds.STUDIO, h.app, dataId)
        repeat(205) { h.tower.work.externalFileVersion(dataId, data.storageObjectId!!) }
        val one = host(); val two = host()
        val initial = one.read(); assertTrue(initial.value.getValue("metrics").jsonArray.isEmpty())
        assertEquals(initial, two.read())
        val id = "10000000-0000-4000-8000-000000000001"
        val value = Json.parseToJsonElement("""{"format":"daily-tracker-v1","metrics":[{"id":"$id","label":"Number","type":"number","unit":"","archived":false}],"days":{"2024-12-31":{"$id":0}}}""").jsonObject
        val saved = one.save(initial.version, value)
        assertNotEquals(initial.version, saved.version)
        assertEquals(value, host().read().value)
        val conflict = runCatching { two.save(initial.version, value) }.exceptionOrNull()
        assertTrue(conflict is FileNappletTransport.Conflict)
        assertEquals(value, two.read().value)
        val edited = Json.parseToJsonElement(value.toString().replace(":0}", ":5}")).jsonObject
        assertEquals(edited, two.save(saved.version, edited).value)
        assertEquals(edited, host().read().value)
        foreign = true
        assertTrue(runCatching { one.read() }.isFailure)
        foreign = false; deny = true
        assertTrue(runCatching { one.read() }.isFailure)
        assertTrue(runCatching { one.save(saved.version, value) }.isFailure)
        one.clear(); deny = false
        assertTrue("A cleared host requires a new authorized read", runCatching { one.save(saved.version, value) }.isFailure)
    }
}
