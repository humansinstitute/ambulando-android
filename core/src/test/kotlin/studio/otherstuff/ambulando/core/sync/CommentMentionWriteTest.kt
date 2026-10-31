package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*

class CommentMentionWriteTest {
    private val agent = Bech32.encode("npub", ByteArray(32) { 0x22 })

    private fun commentReply(kind: String, id: String, body: String) = 201 to mapOf("comment" to mapOf(
        "id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, kind to id, "body" to body, "created_at" to "2026-10-30T00:00:00Z", "row_version" to 1))

    private fun send(kind: WriteKind, target: String, body: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(); val identity = makeIdentity()
        val c = store.writer.saveConnection(ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null))
        val transport = StubTransport { commentReply(if (kind == WriteKind.DOC_COMMENT) "doc_id" else "task_id", target, body) }
        try {
            store.writer.enqueueWrite(c, Fixtures.WORKSPACE_ID, TestKeys.NPUB, WriteIntent(kind, target, buildJsonObject { put("body", body); extra() }))
            WriteSender(store, engine, identity, transport, tempDir()).drain(c)
            parseJsonObject(transport.recorded.first { it.method == "POST" && pathOf(it.url).endsWith("/comments") }.body!!, "comment")
        } finally { engine.close(); store.close() }
    }

    @Test fun documentCommentCarriesActorMentionsDerivedFromTheBody() {
        val target = Fixtures.newId()
        val body = "Hi @[Member Alpha](mention:person:${TestKeys.NPUB}), see @[Plan](mention:document:abc) and @[Agent](mention:agent:$agent) @[Member Alpha](mention:person:${TestKeys.NPUB})"
        val sent = send(WriteKind.DOC_COMMENT, target, body) { put("parent_comment_id", "00000000-0000-4000-8000-000000000001") }
        val expected = listOf(mapOf("type" to "person", "npub" to TestKeys.NPUB, "label" to "Member Alpha"), mapOf("type" to "agent", "npub" to agent, "label" to "Agent"))
        val top = sent["mentions"]!!.jsonArray.map { o -> o.jsonObject.mapValues { it.value.jsonPrimitive.content } }
        assertEquals(expected, top)
        assertEquals(top, sent["metadata"]!!.jsonObject["mentions"]!!.jsonArray.map { o -> o.jsonObject.mapValues { it.value.jsonPrimitive.content } })
        assertEquals("open", sent["metadata"]!!.jsonObject["comment_status"]!!.jsonPrimitive.content)
        assertEquals("00000000-0000-4000-8000-000000000001", sent["parent_comment_id"]!!.jsonPrimitive.content)
        assertEquals(body, sent["body"]!!.jsonPrimitive.content)
    }

    @Test fun plainNamesAndRecordReferencesCreateNoActorEntries() {
        val sent = send(WriteKind.TASK_COMMENT, Fixtures.newId(), "Thanks @Member Alpha, see @[Plan](mention:document:abc)")
        assertEquals(0, sent["mentions"]!!.jsonArray.size)
        assertEquals(0, sent["metadata"]!!.jsonObject["mentions"]!!.jsonArray.size)
    }
}
