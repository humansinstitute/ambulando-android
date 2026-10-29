package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*

class PinOrderWriteTest {
    @Test fun ambiguousRetryKeepsMutationAndOnlySendsContractFields() = runBlocking {
        val store = LocalStore(tempDir())
        val engine = SyncEngine()
        val identity = makeIdentity()
        val c = store.writer.saveConnection(ConnectionRecord.create(TowerOrigin(Fixtures.ORIGIN), TestKeys.NPUB, null))
        val mutation = Fixtures.newId()
        val transport = StubTransport { request ->
            if (pathOf(request.url).endsWith("/me/pin-order")) {
                val body = parseJsonObject(request.body!!, "move")
                assertEquals(setOf("resource_type", "resource_id", "expected_row_version", "before", "mutation_id"), body.keys)
                assertEquals(mutation, body.string("mutation_id"))
                200 to mapOf("pins" to emptyList<Any>())
            } else 200 to mapOf("protocol_version" to 1, "changes" to emptyList<Any>(), "has_more" to false)
        }
        try {
            store.writer.enqueueWrite(c, Fixtures.WORKSPACE_ID, TestKeys.NPUB, WriteIntent(WriteKind.PIN_ORDER, null, buildJsonObject {
                put("resource_type", "thread"); put("resource_id", Fixtures.newId()); put("expected_row_version", 4)
                put("before", JsonNull); put("mutation_id", mutation)
                // The durable queue adds this flag when the first acknowledgement is lost.
                put("ambiguous", true)
            }))
            WriteSender(store, engine, identity, transport, tempDir()).drain(c)
            assertTrue(store.reader.workspaceWrites(c.id, Fixtures.WORKSPACE_ID).isEmpty())
            assertEquals(1, transport.recorded.count { it.method == "POST" })
        } finally { engine.close(); store.close() }
    }
}
