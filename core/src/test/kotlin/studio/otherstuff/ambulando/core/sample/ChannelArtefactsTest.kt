package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

class ChannelArtefactsTest {
    @Test fun htmlCreationEditingHistoryAndStaleSaveUseOneFileIdentity() = runBlocking {
        val h = SampleHarness()
        suspend fun objectId(text: String) = h.api.store(SampleIds.STUDIO, h.app, text.toByteArray(), "text/html", "idea.html", null) { }
        val first = objectId("<h1>One</h1>")
        val reply = h.exchange("POST", h.base() + "/channels/${SampleIds.GENERAL}/files", mapOf(
            "storage_object_id" to first, "display_name" to "Idea.html", "metadata" to ArtefactType.HTML.metadata()))
        assertEquals(201, reply.status)
        val file = FileRecord.parse(reply.json()["file"]!!.jsonObject)
        assertEquals(ArtefactType.HTML, ArtefactType.of(file))
        assertEquals(file.id, h.api.artefactFile(SampleIds.STUDIO, h.app, file.id).id)
        val versions = h.api.fileVersions(SampleIds.STUDIO, h.app, file.id)
        val base = versions.currentVersionId!!
        assertEquals("<h1>One</h1>", h.api.artefactHTML(SampleIds.STUDIO, h.app, file.id))
        val second = objectId("<h1>Two</h1>")
        assertTrue(h.api.addFileVersion(SampleIds.STUDIO, h.app, file.id, base, second, UUID.randomUUID().toString()) is VersionOutcome.Saved)
        assertEquals("<h1>One</h1>", h.api.artefactHTML(SampleIds.STUDIO, h.app, file.id, base))
        assertEquals("<h1>Two</h1>", h.api.artefactHTML(SampleIds.STUDIO, h.app, file.id))
        // The real NStack replacement updates both the head and the ordinary File object pointer.
        val current = h.api.artefactFile(SampleIds.STUDIO,h.app,file.id)
        assertEquals(second,current.storageObjectId)
        assertEquals(studio.otherstuff.ambulando.core.nostr.Sha256Hex.of("<h1>Two</h1>".toByteArray()),current.sha256)
        assertEquals("<h1>Two</h1>", h.api.fileObject(SampleIds.STUDIO,h.app,file.id).bytes.toString(Charsets.UTF_8))
        assertTrue(h.api.addFileVersion(SampleIds.STUDIO, h.app, file.id, base, objectId("<h1>Stale</h1>"), UUID.randomUUID().toString()) is VersionOutcome.Conflict)
        assertEquals(2, h.api.fileVersions(SampleIds.STUDIO, h.app, file.id).versions.size)
    }

    @Test fun filePinUsesExistingCasAndRoundTripsThroughPrivatePinFeed() = runBlocking {
        val h = SampleHarness()
        val file = h.api.channelFiles(SampleIds.STUDIO, h.app, SampleIds.GENERAL).items.first()
        val path = h.base() + "/me/pins/file/${file.id}"
        val body = mapOf("pinned" to true, "expected_row_version" to 0, "mutation_id" to UUID.randomUUID().toString())
        val saved = h.exchange("PUT", path, body)
        assertEquals(200, saved.status)
        val pin = PinRecord.parse(saved.json()["pin"]!!.jsonObject)
        assertEquals(PinRecord.ResourceType.FILE, pin.resourceType)
        assertEquals(file.id, pin.resourceId)
        assertEquals(409, h.exchange("PUT", path, body + ("mutation_id" to UUID.randomUUID().toString())).status)
    }

    @Test fun legacyAndFutureFormatsDoNotLoadArbitraryEditors() {
        fun file(name: String, mime: String?, metadata: JsonObject = JsonObject(emptyMap())) = FileRecord(
            SampleIds.STUDIO, SampleIds.BOARD_FILE, SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL, null, null, name,
            contentType = mime, sizeBytes = 0, sha256 = null, createdAt = "", updatedAt = "", raw = buildJsonObject { put("metadata", metadata) })
        assertEquals(ArtefactType.WHITEBOARD, ArtefactType.of(file("Plan.excalidraw", "application/json")))
        assertEquals(ArtefactType.HTML, ArtefactType.of(file("Idea", "text/html; charset=utf-8")))
        val unknown = buildJsonObject { put("artefact", buildJsonObject { put("schema_version", 2); put("type", "html"); put("editor_id", "https://bad.invalid/editor") }) }
        assertNull(ArtefactType.of(file("Idea.html", "text/html", unknown)))
    }
}
