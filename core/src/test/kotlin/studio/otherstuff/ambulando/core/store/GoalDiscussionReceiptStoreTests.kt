package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.otherstuff.ambulando.core.chat.threads
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.io.IOException
import java.net.URI
import java.util.UUID

/** Durable local receipts and synthetic persisted-server recovery; not live service or device evidence. */
class GoalDiscussionReceiptStoreTests {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = "11111111-1111-4111-8111-111111111111"
    private val goal = "22222222-2222-4222-8222-222222222222"
    private val other = "33333333-3333-4333-8333-333333333333"
    private val request = "44444444-4444-4444-8444-444444444444"
    private val workspace = SampleIds.STUDIO
    private val channel = SampleIds.GENERAL
    private fun receipt() = GoalDiscussionReceipt(workspace, goal, channel, request)

    @Test fun pendingReceiptSurvivesStoreReopenAndReadUntilExplicitDurableClear() = runBlocking {
        val directory = temporary.newFolder("receipt-store")
        val receipt = receipt()
        val key = GoalDiscussionReceipt.key(connection, workspace, goal)
        assertEquals("goal.discussion.pending.$connection.$workspace.$goal", key)
        LocalStore(directory).use { it.writer.setState(key, receipt.encode()) }
        LocalStore(directory).use { store ->
            val original = store.reader.state(key)!!
            assertEquals(receipt, GoalDiscussionReceipt.parse(original, workspace, goal))
            assertEquals("reading is not acknowledgement or permission to retry", original, store.reader.state(key))
            assertNull(store.reader.state(GoalDiscussionReceipt.key(other, workspace, goal)))
            assertNull(store.reader.state(GoalDiscussionReceipt.key(connection, SampleIds.LAB, goal)))
            assertNull(store.reader.state(GoalDiscussionReceipt.key(connection, workspace, other)))
        }
        LocalStore(directory).use { store ->
            assertEquals(receipt, GoalDiscussionReceipt.parse(store.reader.state(key)!!, workspace, goal))
            store.writer.setState(key, null)
        }
        LocalStore(directory).use { assertNull("only explicit clear retires a receipt", it.reader.state(key)) }
    }

    @Test fun mismatchedOrMalformedReceiptsAreRefusedWithoutErasingTheStoredEvidence(): Unit = runBlocking {
        val directory = temporary.newFolder("receipt-invalid")
        val key = GoalDiscussionReceipt.key(connection, workspace, goal)
        LocalStore(directory).use { store ->
            val original = receipt().encode()
            store.writer.setState(key, original)
            assertThrows(IllegalArgumentException::class.java) { GoalDiscussionReceipt.parse(original, SampleIds.LAB, goal) }
            assertThrows(IllegalArgumentException::class.java) { GoalDiscussionReceipt.parse(original, workspace, other) }
            val root = Json.parseToJsonElement(original).jsonObject
            val malformed = listOf(
                "{", "[]", "null", "\"pending\"",
                JsonObject(root + ("version" to JsonPrimitive(2))).toString(),
                JsonObject(root + ("version" to JsonPrimitive("1"))).toString(),
                JsonObject(root + ("format" to JsonPrimitive("another.receipt"))).toString(),
                JsonObject(root - "request_id").toString(),
                JsonObject(root + ("channel_id" to JsonPrimitive(7))).toString(),
                JsonObject(root + ("goal_file_id" to JsonNull)).toString(),
                JsonObject(root + ("request_id" to JsonPrimitive("bad-request"))).toString(),
                JsonObject(root + ("request_id" to JsonPrimitive("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))).toString(),
                JsonObject(root + ("future_field" to JsonPrimitive("unsupported"))).toString(),
                " ".repeat(4097),
            )
            malformed.forEach { raw ->
                assertThrows("refuse malformed receipt: ${raw.take(100)}", Exception::class.java) { GoalDiscussionReceipt.parse(raw, workspace, goal) }
            }
            assertEquals(original, store.reader.state(key))
        }
        assertThrows(IllegalArgumentException::class.java) { GoalDiscussionReceipt(workspace, goal, channel, "invalid") }
        assertThrows(IllegalArgumentException::class.java) { GoalDiscussionReceipt.key("invalid", workspace, goal) }
    }

    @Test fun committedDiscussionWithLostReplyIsFoundAfterBothServerAndClientStoresReopenWithoutSecondCreate() = runBlocking {
        val serverDirectory = temporary.newFolder("synthetic-server")
        val clientDirectory = temporary.newFolder("client-store")
        val server = SampleHarness(SampleTower(serverDirectory))
        val space = server.api.createGoalsSpace(workspace, server.app, TestKeys.NPUB, null, emptyList())
        val savedGoal = server.api.createGoal(workspace, server.app, space.id, Goal.create("Recover purposeful work", "Keep the original discussion"))
        val receipt = GoalDiscussionReceipt(workspace, savedGoal.id, channel, UUID.randomUUID().toString())
        val key = GoalDiscussionReceipt.key(connection, workspace, savedGoal.id)
        var creates = 0
        val lostReply = TowerAPI(TowerClient(server.tower.origin, object : HttpTransport {
            override suspend fun send(sent: HttpRequest): HttpResponse {
                val reply = server.tower.send(sent)
                if (sent.method == "POST" && URI(sent.url).path.endsWith("/threads")) {
                    creates++
                    assertEquals(201, reply.status)
                    throw IOException("Synthetic response lost after server commit")
                }
                return reply
            }
        }, SampleSigners.primary))
        LocalStore(clientDirectory).use { store ->
            val failure = runCatching {
                lostReply.createGoalDiscussion(workspace, server.app, savedGoal.id, channel, SampleIds.SCOPE_AMBULANDO, null, receipt.requestId) {
                    store.writer.setState(key, receipt.encode())
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(receipt, GoalDiscussionReceipt.parse(store.reader.state(key)!!, workspace, savedGoal.id))
        }
        // Models an independent NStack service retaining its data while the client loses its process-local state.
        val reopenedServer = SampleHarness(SampleTower(serverDirectory))
        var recoveredId: String? = null
        LocalStore(clientDirectory).use { store ->
            val pending = GoalDiscussionReceipt.parse(store.reader.state(key)!!, workspace, savedGoal.id)
            val recovered = reopenedServer.api.findGoalDiscussion(pending.workspaceId, reopenedServer.app, pending.channelId, pending.goalFileId, pending.requestId)!!
            recoveredId = recovered.id
            assertEquals(savedGoal.id, GoalAssociation.read(recovered.raw.obj("metadata")!!))
            assertEquals(receipt.encode(), store.reader.state(key))
            store.writer.upsertThreads(workspace, listOf(recovered))
            store.writer.setState(key, null)
        }
        LocalStore(clientDirectory).use { store ->
            assertNull(store.reader.state(key))
            assertEquals(recoveredId, store.reader.thread(workspace, recoveredId!!)!!.id)
        }
        assertEquals(1, creates)
        assertEquals(1, reopenedServer.api.threads(workspace, reopenedServer.app, channel).items.count {
            it.raw.obj("metadata")?.string("ambulando_discussion_request_id") == receipt.requestId
        })
    }
}
