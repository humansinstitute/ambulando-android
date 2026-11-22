package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI

class FileNappletUpdateTest {
    private val original = javaClass.getResource("/napplets/daily-tracker-original.html")!!.readText()
    private val manifest = Json.parseToJsonElement("""{"version":1,"app_id":"daily-tracker","bindings":{"tracker":{"format":"daily-tracker-v1","operations":["read","save"],"privacy":"self"}}}""").jsonObject
    private val replacement get() = original.replace("</head>", "<!-- reviewed update -->\n</head>")

    @Test fun exactOriginalOnly() {
        assertTrue(FileNappletUpdate.recognized(original))
        assertFalse(FileNappletUpdate.recognized(original + " "))
        assertFalse(FileNappletUpdate.recognized(replacement))
    }
    @Test fun updatePreservesBoundDataAndMetadata() = exercise("success")
    @Test fun customizedCodeCannotBeReplaced() = exercise("custom")
    @Test fun staleVersionBeforeUploadDoesNotWrite() = exercise("stale")
    @Test fun competingVersionAtPublicationUsesOriginalCAS() = exercise("race")
    @Test fun revokedCodeWriteDoesNotUpload() = exercise("denied")
    @Test fun uncertainAcknowledgementDoesNotRetryOrClaimSuccess() = exercise("uncertain")
    @Test fun changedBindingAfterUploadDoesNotPublish() = exercise("binding")
    @Test fun foreignDataChannelDoesNotUpload() = exercise("foreign")

    @Test fun boundarySwitchAfterUploadDoesNotPublish() = exercise("boundary")
    @Test fun dataAccessChangeAfterUploadDoesNotPublish() = exercise("access")
    @Test fun newerVersionAfterAcknowledgementDoesNotClaimSuccess() = exercise("readback")

    private fun exercise(mode: String) = runBlocking {
        val h = SampleHarness()
        val code = h.api.createDailyTracker(SampleIds.STUDIO, h.app, TestKeys.NPUB, if (mode == "custom") original + " " else original).code
        val dataId = FileNapplet.binding(code.raw.getValue("metadata").jsonObject, manifest)
        val base = h.api.fileVersions(SampleIds.STUDIO, h.app, code.id).currentVersionId!!
        val savedDataBase = h.api.fileVersions(SampleIds.STUDIO, h.app, dataId).currentVersionId!!
        val savedData = """{"format":"daily-tracker-v1","metrics":[{"id":"10000000-0000-4000-8000-000000000001","label":"Synthetic yes/no","type":"boolean","unit":"","archived":false},{"id":"10000000-0000-4000-8000-000000000002","label":"Synthetic number","type":"number","unit":"","archived":true}],"days":{"2024-12-31":{"10000000-0000-4000-8000-000000000001":false,"10000000-0000-4000-8000-000000000002":0},"2025-01-01":{}}}"""
        val dataObject = h.api.store(SampleIds.STUDIO, h.app, savedData.toByteArray(), "application/json", "synthetic.json", null) { }
        assertTrue(h.api.addFileVersion(SampleIds.STUDIO, h.app, dataId, savedDataBase, dataObject, java.util.UUID.randomUUID().toString()) is VersionOutcome.Saved)
        val dataBefore = h.api.fileVersions(SampleIds.STUDIO, h.app, dataId)
        val dataVersion = dataBefore.versions.last()
        val dataBytes = h.api.fileVersionBytes(SampleIds.STUDIO, h.app, dataId, dataVersion)
        var prepares = 0; var publishes = 0; var afterUpload = false
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                if (request.method == "GET" && path.matches(Regex("${h.base()}/channels/[a-f0-9-]+"))) {
                    val id = path.substringAfterLast('/')
                    val channel = h.api.scopes(SampleIds.STUDIO, h.app).flatMap { h.api.channels(SampleIds.STUDIO, it.id, h.app) }.first { it.id == id }
                    val raw = if (mode == "foreign") JsonObject(channel.raw + ("participant_npubs" to JsonArray(listOf(JsonPrimitive(SampleSigners.otherNpub))))) else channel.raw
                    return HttpResponse(200, buildJsonObject { put("channel", raw) }.toString().toByteArray())
                }
                if (request.method == "GET" && path.endsWith("/files/${code.id}")) {
                    val reply = h.tower.send(request)
                    val body = Json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)).jsonObject
                    val raw = body.getValue("file").jsonObject
                    val changed = when {
                        mode == "denied" -> JsonObject(raw + ("can_write" to JsonPrimitive(false)))
                        mode == "binding" && afterUpload -> JsonObject(raw + ("metadata" to JsonObject(emptyMap())))
                        else -> raw
                    }
                    return HttpResponse(200, JsonObject(body + ("file" to changed)).toString().toByteArray())
                }
                if (mode == "access" && afterUpload && request.method == "GET" && path.endsWith("/files/$dataId")) {
                    val reply = h.tower.send(request)
                    val body = Json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)).jsonObject
                    val raw = body.getValue("file").jsonObject
                    return HttpResponse(200, JsonObject(body + ("file" to JsonObject(raw + ("can_write" to JsonPrimitive(false))))).toString().toByteArray())
                }
                if (request.method == "POST" && path.endsWith("/storage/prepare")) { prepares++; afterUpload = true }
                if (request.method == "POST" && path.endsWith("/files/${code.id}/versions")) {
                    publishes++
                    if (mode == "race") h.tower.work.externalFileVersion(code.id, code.storageObjectId!!)
                    val reply = h.tower.send(request)
                    if (mode == "readback") h.tower.work.externalFileVersion(code.id, code.storageObjectId!!)
                    if (mode == "uncertain") return HttpResponse(503, "{}".toByteArray())
                    return reply
                }
                return h.tower.send(request)
            }
        }
        val api = TowerAPI(TowerClient(h.tower.origin, transport, SampleSigners.primary))
        val update = FileNappletUpdate(api, SampleIds.STUDIO, h.app, TestKeys.NPUB, code.id, base, code.raw.getValue("metadata").jsonObject, manifest, dataId, true) { mode != "boundary" || !afterUpload }
        if (mode == "stale") h.tower.work.externalFileVersion(code.id, code.storageObjectId!!)
        val result = runCatching { update.update(replacement) }
        if (mode == "success") {
            assertTrue(result.isSuccess)
            val versions = h.api.fileVersions(SampleIds.STUDIO, h.app, code.id)
            assertEquals(result.getOrThrow(), versions.currentVersionId)
            assertEquals(replacement, h.api.fileVersionBytes(SampleIds.STUDIO, h.app, code.id, versions.versions.last()).toString(Charsets.UTF_8))
            assertEquals(code.raw["metadata"], h.api.artefactFile(SampleIds.STUDIO, h.app, code.id).raw["metadata"])
        } else assertTrue("$mode must fail closed", result.isFailure)
        val early = mode in setOf("custom", "stale", "denied", "foreign")
        assertEquals(if (early) 0 else 1, prepares)
        assertEquals(if (early || mode in setOf("binding", "boundary", "access")) 0 else 1, publishes)
        if (mode in setOf("race", "uncertain", "binding", "boundary", "access", "readback", "success")) {
            assertTrue(runCatching { update.update(replacement) }.isFailure)
            assertEquals(1, prepares)
        }
        assertEquals(dataBefore, h.api.fileVersions(SampleIds.STUDIO, h.app, dataId))
        assertArrayEquals(dataBytes, h.api.fileVersionBytes(SampleIds.STUDIO, h.app, dataId, dataVersion))
    }
}
