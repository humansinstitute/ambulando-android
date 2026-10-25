package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.StubTransport
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.pathOf
import studio.otherstuff.ambulando.core.support.queryOf
import studio.otherstuff.ambulando.core.transport.MessageAttachment
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin

class ChatAPITests {
    private suspend fun api(transport: StubTransport) = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity()))

    @Test fun messagePagesAreScopedToTheirChannelAndKeepRawRows() = runBlocking {
        val thread = Fixtures.newId()
        val id = Fixtures.newId()
        val transport = StubTransport { 200 to mapOf("messages" to listOf(Fixtures.message(id, body = "hi", thread = thread)), "next_cursor" to "") }
        val api = api(transport)
        val page = api.messagesPage(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.CHANNEL_1, threadId = thread, cursor = "c1")
        assertNull("an empty cursor ends the pages", page.nextCursor)
        val message = page.messages.single()
        assertEquals("hi", message.body)
        assertFalse(message.isThreadRoot)
        assertTrue("unknown fields survive", message.raw.containsKey("future_field"))
        val request = transport.recorded.single()
        assertEquals("/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}/channels/${Fixtures.CHANNEL_1}/messages", pathOf(request.url))
        assertEquals(mapOf("limit" to "200", "thread_id" to thread, "cursor" to "c1"), queryOf(request.url))
        assertEquals(Fixtures.APP_NPUB, request.header("x-flightdeck-pg-app-npub"))

        transport.setHandler { 200 to mapOf("messages" to listOf(Fixtures.message(Fixtures.newId(), channel = Fixtures.CHANNEL_2, body = "x"))) }
        try {
            api.messagesPage(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.CHANNEL_1, cursor = null)
            fail("a foreign channel's page was accepted")
        } catch (e: TowerError) {
            assertEquals(TowerError.Malformed("messages"), e)
        }
    }

    @Test fun threadsReportAFullPageAsPossiblyIncompleteAndDropForeignRows() = runBlocking {
        val row = { channel: String -> mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "channel_id" to channel, "record_state" to "archived") }
        val transport = StubTransport { 200 to mapOf("threads" to listOf(row(Fixtures.CHANNEL_1), row(Fixtures.CHANNEL_2))) }
        val result = api(transport).threads(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.CHANNEL_1)
        assertTrue(result.complete)
        assertEquals(1, result.items.size)
        assertTrue(result.items[0].archived)
        assertEquals(mapOf("limit" to "200", "include_archived" to "true"), queryOf(transport.recorded.single().url))
        transport.setHandler { 200 to mapOf("threads" to (0 until 200).map { row(Fixtures.CHANNEL_1) }) }
        assertFalse(api(transport).threads(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.CHANNEL_1).complete)
    }

    @Test fun membersSkipInvalidRowsAndScopeOrderKeepsOnlyUUIDs() = runBlocking {
        val agent = TestKeys.syntheticNpub(0x22)
        val transport = StubTransport { request ->
            if (pathOf(request.url).endsWith("/members")) {
                200 to mapOf("members" to listOf(Fixtures.member(TestKeys.NPUB, "Member A"), Fixtures.member(agent, "", kind = "agent"), Fixtures.member("npub1bad", "X")))
            } else {
                200 to mapOf("scope_order" to mapOf("scope_ids" to listOf(Fixtures.SCOPE_B.uppercase(), "nope", Fixtures.SCOPE_A), "row_version" to 3))
            }
        }
        val api = api(transport)
        val members = api.members(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB)
        assertEquals(listOf("Member A", agent.take(10) + "…" + agent.takeLast(4)), members.map { it.label })
        assertTrue(members[1].isAgent)
        assertEquals(listOf(Fixtures.SCOPE_B, Fixtures.SCOPE_A), api.scopeOrder(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB))
    }

    @Test fun attachmentsAreReadFromEitherPlace() {
        val good = Fixtures.newId()
        val raw = Fixtures.obj(
            mapOf("metadata" to mapOf("attachments" to listOf(mapOf("storage_object_id" to good.uppercase(), "size_bytes" to "12", "mime_type" to "image/png"), mapOf("storage_object_id" to "bad")))),
        )
        val attachments = MessageAttachment.from(raw)
        assertEquals(listOf(MessageAttachment("file", good, "Attachment", "image/png", 12)), attachments)
        assertEquals(listOf("kind", "storage_object_id", "filename", "mime_type", "size_bytes"), attachments[0].requestObject.keys.toList())
    }
}
