package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*

class ArtefactChatContextTest {
    private val target = ChatTarget(Fixtures.newId(), Fixtures.WORKSPACE_ID, Fixtures.CHANNEL_1, Fixtures.newId())
    private val ref = ArtefactChatReference("file", Fixtures.newId(), Fixtures.newId(), true, "a".repeat(64))
    @Test fun schemaMatchesMacAndRejectsForeignUnknownMissingAndDuplicateState() {
        val raw = ArtefactChatContext.encode(target, false, ref.recordID, listOf(ref))
        val o = ArtefactChatContext.validate(raw, target)
        assertEquals(JsonPrimitive(false), o["workspaceVisible"])
        assertEquals(JsonPrimitive("file_version_id"), o["open"]!!.jsonArray[0].jsonObject["revisionKind"])
        val invalid = listOf(raw.replace(target.workspaceId, Fixtures.newId()), raw.replace("\"digest\"", "\"unknown\""), raw.replace("a".repeat(64), "bad"), raw.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        invalid.forEach { assertTrue(runCatching { ArtefactChatContext.validate(it, target) }.isFailure) }
        assertTrue(runCatching { ArtefactChatContext.encode(target, true, ref.recordID, listOf(ref, ref)) }.isFailure)
        assertTrue(runCatching { ArtefactChatContext.encode(target, true, ref.recordID, listOf(ref.copy(kind = "doc", revision = "01"))) }.isFailure)
    }
    @Test fun docContextHashesTheFullStoredEnvelopeAndChecksRouteOwnership() = runBlocking {
        val doc = Fixtures.newId()
        val bytes = "{\"future_node\":{\"unknown\":true},\"text\":\"Hello\"}"
        var foreign = false
        val transport = StubTransport { 200 to mapOf("versions" to listOf(mapOf(
            "workspace_id" to if (foreign) Fixtures.newId() else target.workspaceId, "doc_id" to doc,
            "row_version" to 18, "title" to "Document", "content" to mapOf("raw" to bytes, "content_type" to "application/json")
        )), "next_cursor" to null) }
        val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity()))
        val version = api.documentVersions(target.workspaceId, Fixtures.APP_NPUB, doc).single()
        val reference = version.reference(doc, true)
        assertEquals("18", reference.revision)
        assertEquals("doc_versions.row_version", reference.revisionKind)
        assertEquals(Sha256Hex.of(bytes.toByteArray()), reference.digest)
        foreign = true
        assertTrue(runCatching { api.documentVersions(target.workspaceId, Fixtures.APP_NPUB, doc) }.isFailure)
    }
    @Test fun outboxPersistsSnapshotBeforeSignatureAndFreezesCleanBody() = runBlocking {
        val dir = tempDir()
        val store = StoreFixtures.store(dir)
        val c = StoreFixtures.connect(store)
        store.writer.replaceWorkspaces(c.id, listOf(StoreFixtures.workspace(target.workspaceId, "Fixture")))
        val t = target.copy(connectionId = c.id)
        val raw = ArtefactChatContext.encode(t, false, ref.recordID, listOf(ref))
        store.writer.saveDraft(t, Draft("Discuss exactly this", emptyList()))
        val item = store.writer.enqueueSend(t, c.origin, c.signerNpub, Fixtures.APP_NPUB, "Discuss exactly this", emptyList(), artefactContext = raw)
        store.close()
        StoreFixtures.store(dir).use { reopened ->
            val restored = reopened.reader.outboxItem(item.id)!!
            assertEquals(raw, restored.artefactContext)
            assertEquals("Discuss exactly this", restored.body)
            val signer = makeIdentity()
            val bytes = OutboxRequest.build(restored, signer)
            val request = parseJsonObject(bytes, "request")
            assertEquals(JsonPrimitive("Discuss exactly this"), request["body"])
            assertEquals(Json.parseToJsonElement(raw), request["metadata"]!!.jsonObject[ArtefactChatContext.KEY])
            reopened.writer.freezeOutbox(item.id, bytes)
            assertArrayEquals(bytes, reopened.reader.outboxItem(item.id)!!.request)
            assertEquals(Sha256Hex.of(bytes), Sha256Hex.of(reopened.reader.outboxItem(item.id)!!.request!!))
            assertNull(reopened.reader.draft(t))
        }
    }
}
