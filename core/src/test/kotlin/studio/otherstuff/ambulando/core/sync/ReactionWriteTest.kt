package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*

class ReactionWriteTest {
    private fun send(emoji: String): String = runBlocking {
        val store = LocalStore(tempDir()); val engine = SyncEngine(); val identity = makeIdentity()
        val c = store.writer.saveConnection(ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null))
        val message = Fixtures.newId()
        val transport = StubTransport { 201 to mapOf("reaction" to mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "target_type" to "message", "target_id" to message,
            "emoji" to "thumbs_up", "reactor_npub" to TestKeys.NPUB, "row_version" to 1, "updated_at" to "2026-10-30T00:00:00Z")) }
        try {
            store.writer.enqueueWrite(c, Fixtures.WORKSPACE_ID, TestKeys.NPUB, WriteIntent(WriteKind.REACTION_ADD, message, buildJsonObject { put("target_type", "message"); put("emoji", emoji) }))
            WriteSender(store, engine, identity, transport, tempDir()).drain(c)
            parseJsonObject(transport.recorded.first { it.method == "POST" && pathOf(it.url).endsWith("/reactions") }.body!!, "reaction").string("emoji")!!
        } finally { engine.close(); store.close() }
    }

    @Test fun reactionsAreSentByTowersName() = assertEquals("heart", send("heart"))

    @Test fun reactionsQueuedByEarlierBuildsWithTheEmojiCharacterAreSentByName() {
        assertEquals("thumbs_up", send("👍"))
        assertEquals("heart", send("❤️"))
    }
}
