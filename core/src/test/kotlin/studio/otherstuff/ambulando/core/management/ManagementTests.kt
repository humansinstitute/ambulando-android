package studio.otherstuff.ambulando.core.management

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.StubTransport
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.pathOf
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.strings
import java.io.IOException

/**
 * Stand-in for Tower's scope/channel management routes with the server rules the client relies on: permission
 * refusals (403), duplicate names (409), 1-based reorder, archive, and the personal scope order's row version.
 */
private class FakeManagementTower {
    val scopes = mutableListOf(
        Fixtures.scope(Fixtures.SCOPE_A, name = "Alpha").apply { put("can_manage", true) },
        Fixtures.scope(Fixtures.SCOPE_B, name = "Beta").apply { put("can_manage", false) },
    )
    val channels = mutableListOf(
        Fixtures.channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, name = "general", position = 1.0),
        Fixtures.channel(Fixtures.CHANNEL_2, Fixtures.SCOPE_A, name = "design", position = 2.0),
    )
    var orderIds = listOf<String>()
    var orderVersion = 3L
    val denied = mutableSetOf(Fixtures.SCOPE_B)
    var concurrentOrderEdit = false
    var offline = false
    val bodies = mutableListOf<Triple<String, String, JsonObject>>()

    fun handle(request: HttpRequest): Pair<Int, Any?> {
        if (offline) throw IOException("The network is unreachable.")
        if (request.header("Authorization")?.startsWith("Nostr ") != true) return 401 to mapOf("code" to "unauthorized")
        val path = pathOf(request.url).removePrefix("/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}")
        val body = request.body?.let { parseJsonObject(it, "body") } ?: JsonObject(emptyMap())
        bodies.add(Triple(request.method, path, body))
        val parts = path.split('/').filter { it.isNotEmpty() }
        return when {
            request.method == "GET" && parts == listOf("me") ->
                200 to mapOf("membership" to mapOf("role" to "member"), "permissions" to listOf("channel.create", "channel.manage"), "actor" to mapOf("actor_id" to "a1"))
            request.method == "POST" && parts.size == 3 && parts[0] == "scopes" -> {
                if (parts[1] in denied) return 403 to mapOf("code" to "forbidden")
                val name = body.string("name")
                if (channels.any { it["scope_id"] == parts[1] && it["name"] == name }) return 409 to mapOf("code" to "duplicate_channel")
                val row = Fixtures.channel(Fixtures.newId(), parts[1], name = name!!, position = channels.size + 1.0)
                row["metadata"] = body.obj("metadata") ?: emptyMap<String, Any>()
                row["kind"] = body.string("kind") ?: "channel"
                channels.add(row)
                201 to mapOf("channel" to row)
            }
            request.method == "POST" && parts == listOf("scopes") -> {
                val row = Fixtures.scope(Fixtures.newId(), name = body.string("name")!!).apply { put("can_manage", true) }
                scopes.add(row)
                201 to mapOf("scope" to row)
            }
            parts.size == 2 && parts[0] == "scopes" && request.method in setOf("PATCH", "DELETE") -> {
                if (parts[1] in denied) return 403 to mapOf("code" to "forbidden")
                val index = scopes.indexOfFirst { it["id"] == parts[1] }
                if (index < 0) return 404 to mapOf("code" to "scope_not_found")
                if (request.method == "DELETE") return 200 to mapOf("scope" to scopes.removeAt(index))
                scopes[index]["name"] = body.string("name")
                scopes[index]["description"] = body["description"]
                200 to mapOf("scope" to scopes[index])
            }
            parts.size >= 2 && parts[0] == "channels" -> {
                val index = channels.indexOfFirst { it["id"] == parts[1] }
                if (index < 0) return 404 to mapOf("code" to "channel_not_found")
                if (parts[1] in denied || channels[index]["scope_id"] in denied) return 403 to mapOf("code" to "forbidden")
                when (request.method) {
                    "DELETE" -> 200 to mapOf("channel" to channels.removeAt(index))
                    "POST" -> 200 to mapOf("channel" to channels[index], "changed" to true)
                    else -> {
                        body.string("name")?.let { channels[index]["name"] = it }
                        body.obj("metadata")?.let { channels[index]["metadata"] = it }
                        200 to mapOf("channel" to channels[index])
                    }
                }
            }
            request.method == "GET" && parts == listOf("me", "scope-order") ->
                200 to mapOf("scope_order" to mapOf("scope_ids" to orderIds, "row_version" to orderVersion))
            request.method == "PUT" && parts == listOf("me", "scope-order") -> {
                if (concurrentOrderEdit) {
                    concurrentOrderEdit = false
                    orderVersion += 1
                    orderIds = listOf(Fixtures.SCOPE_B)
                }
                if (flexibleLong(body["expected_row_version"]) != orderVersion) return 409 to mapOf("code" to "scope_order_conflict")
                orderIds = body.strings("scope_ids") ?: emptyList()
                orderVersion += 1
                200 to mapOf("scope_order" to mapOf("scope_ids" to orderIds, "row_version" to orderVersion))
            }
            else -> 404 to mapOf("code" to "not_found")
        }
    }
}

class ManagementTests {
    private val workspace = WorkspaceRecord.parse(Fixtures.obj(Fixtures.workspace(Fixtures.WORKSPACE_ID, "Studio")))

    private suspend fun api(tower: FakeManagementTower, unlocked: Boolean = true): ScopeChannelApi {
        val identity = makeIdentity(unlocked)
        return ScopeChannelApi(identity, TestKeys.NPUB, TowerClient(TowerOrigin(Fixtures.ORIGIN), StubTransport { tower.handle(it) }, identity), workspace)
    }

    private suspend fun expect(expected: ManagementError, block: suspend () -> Unit): ManagementError {
        try {
            block()
        } catch (e: ManagementError) {
            assertEquals(expected, e)
            return e
        }
        fail("expected $expected")
        error("unreachable")
    }

    @Test fun createChannelSendsTheWebBody() = runBlocking {
        val tower = FakeManagementTower()
        val channel = api(tower).createChannel(Fixtures.SCOPE_A, "  ops  ", "Runbooks", "Be brief")
        assertEquals("ops", channel.name)
        assertEquals(Fixtures.SCOPE_A, channel.scopeId)
        val (method, path, body) = tower.bodies.last()
        assertEquals("POST", method)
        assertEquals("/scopes/${Fixtures.SCOPE_A}/channels", path)
        assertEquals("channel", body.string("kind"))
        assertEquals("Runbooks", body.string("description"))
        val chat = body.obj("metadata")!!.obj("agent_chat")!!
        assertEquals("Be brief", chat.string("context_prompt"))
        assertEquals("mention_then_continue", chat.string("activation"))
        assertEquals(true, chat.bool("enabled"))
        assertEquals("Be brief", channel.contextPrompt)
    }

    @Test fun refusalsAreClassified() = runBlocking {
        val tower = FakeManagementTower()
        val api = api(tower)
        val forbidden = expect(ManagementError.Forbidden("You don't have permission to create channels in this scope.")) {
            api.createChannel(Fixtures.SCOPE_B, "x", null, null)
        }
        assertFalse(forbidden.retryable)
        expect(ManagementError.Duplicate("A channel with this name already exists in this scope.")) { api.createChannel(Fixtures.SCOPE_A, "general", null, null) }
        expect(ManagementError.Invalid("Channel name is required.")) { api.createChannel(Fixtures.SCOPE_A, "   ", null, null) }
        expect(ManagementError.Invalid("Position must be 1 or more.")) {
            api.moveChannel(ChannelRecord.parse(Fixtures.obj(tower.channels[0])), 0)
        }
        assertEquals(2, tower.channels.size)
        tower.offline = true
        try {
            api.renameChannel(Fixtures.CHANNEL_1, "main")
            fail("expected offline")
        } catch (e: ManagementError.Offline) {
            assertTrue(e.retryable)
            assertTrue(e.message!!.startsWith("Couldn't reach Tower, nothing was changed."))
        }
        assertEquals("general", tower.channels[0]["name"])
    }

    @Test fun lockedIdentityIsReportedBeforeAnyRequest() = runBlocking {
        val tower = FakeManagementTower()
        expect(ManagementError.Locked) { api(tower, unlocked = false).renameChannel(Fixtures.CHANNEL_1, "main") }
        assertTrue(tower.bodies.isEmpty())
    }

    @Test fun renamePromptMoveAndArchive() = runBlocking {
        val tower = FakeManagementTower()
        tower.channels[0]["metadata"] = mapOf("basePrompt" to "old", "keep" to "me")
        val api = api(tower)
        val renamed = api.renameChannel(Fixtures.CHANNEL_1, "main")
        assertEquals("main", renamed.name)
        assertEquals("the legacy prompt is read", "old", renamed.contextPrompt)
        val prompted = api.saveContextPrompt(renamed, " new prompt ")
        val sent = tower.bodies.last().third.obj("metadata")!!
        assertNull("the legacy key is replaced by agent_chat", sent["basePrompt"])
        assertEquals("other metadata is kept", "me", sent.string("keep"))
        assertEquals("new prompt", prompted.contextPrompt)
        api.moveChannel(prompted, 2)
        assertEquals(JsonPrimitive(2), tower.bodies.last { it.second.endsWith("/reorder") }.third["position"])
        api.archiveChannel(ChannelRecord.parse(Fixtures.obj(tower.channels[1])))
        assertEquals(listOf(Fixtures.CHANNEL_1), tower.channels.map { it["id"] })
        assertEquals("DELETE", tower.bodies.last().first)
    }

    @Test fun directMessageUsesTheWebNaming() = runBlocking {
        val tower = FakeManagementTower()
        val other = TestKeys.syntheticNpub(0x44)
        api(tower).createDirectMessage(Fixtures.SCOPE_A, other)
        val body = tower.bodies.last().third
        val participants = listOf(TestKeys.NPUB, other).sorted()
        assertEquals("DM: $other", body.string("name"))
        assertEquals("dm:" + participants.joinToString("|"), body.string("description"))
        assertEquals("dm", body.string("kind"))
        assertEquals(participants, body.strings("participant_npubs"))
        expect(ManagementError.Invalid("Choose a member to message.")) { api(tower).createDirectMessage(Fixtures.SCOPE_A, "npub1bad") }
        Unit
    }

    @Test fun scopeOrderSendsTheCurrentVersionAndReportsAConflictWithoutReplaying() = runBlocking {
        val tower = FakeManagementTower()
        val api = api(tower)
        assertEquals(listOf(Fixtures.SCOPE_B, Fixtures.SCOPE_A), api.saveScopeOrder(listOf(Fixtures.SCOPE_B, Fixtures.SCOPE_A, "nope")))
        val put = tower.bodies.last { it.first == "PUT" }.third
        assertEquals(3L, flexibleLong(put["expected_row_version"]))
        assertNotNull(put.string("mutation_id"))
        assertEquals(listOf(Fixtures.SCOPE_B, Fixtures.SCOPE_A), put.strings("scope_ids"))

        tower.concurrentOrderEdit = true
        var stored: List<String>? = null
        expect(ManagementError.Conflict("Your scope order changed in another session. Try your move again.")) {
            api.saveScopeOrder(listOf(Fixtures.SCOPE_A, Fixtures.SCOPE_B)) { stored = it }
        }
        assertEquals("the rejected order is not sent again", 2, tower.bodies.count { it.first == "PUT" })
        assertEquals("the other session's order is handed back for storing", listOf(Fixtures.SCOPE_B), stored)
    }

    @Test fun scopeCreateEditArchiveAndCanManage() = runBlocking {
        val tower = FakeManagementTower()
        val api = api(tower)
        val created = api.createScope("Gamma", "New work")
        assertEquals("project", tower.bodies.last().third.string("kind"))
        assertEquals(true, created.canManage)
        api.updateScope(created.id, "Gamma 2", null)
        assertEquals("a null description is sent as JSON null", JsonNull, tower.bodies.last().third["description"])
        expect(ManagementError.Forbidden("You need Manage access on this scope.")) { api.updateScope(Fixtures.SCOPE_B, "Nope", null) }
        expect(ManagementError.NotFound("This item is no longer available. Refresh and try again.")) { api.archiveScope(Fixtures.newId()) }
        api.archiveScope(created.id)
        assertFalse(tower.scopes.any { it["id"] == created.id })
    }

    @Test fun classificationCoversValidationAndUnexpectedReplies() {
        val validation = Fixtures.obj(mapOf("code" to "validation_error", "details" to mapOf("fields" to listOf(mapOf("message" to "name too long"), mapOf("message" to "bad kind")))))
        assertEquals(ManagementError.Invalid("name too long bad kind"), ManagementError.classify(422, validation, ManagementOperation.CREATE_SCOPE))
        assertEquals(ManagementError.Invalid("Tower refused the request (HTTP 400)."), ManagementError.classify(400, JsonObject(emptyMap()), ManagementOperation.CREATE_SCOPE))
        assertEquals(ManagementError.NotFound("This scope is no longer available."), ManagementError.classify(404, JsonObject(emptyMap()), ManagementOperation.CREATE_CHANNEL))
        assertEquals(ManagementError.Duplicate("A scope with this name already exists in this workspace."), ManagementError.classify(409, Fixtures.obj(mapOf("error" to "duplicate_scope")), ManagementOperation.CREATE_SCOPE))
        assertEquals(ManagementError.Conflict("This changed elsewhere. Refresh and try again."), ManagementError.classify(409, JsonObject(emptyMap()), ManagementOperation.MANAGE_CHANNEL))
        val unexpected = ManagementError.classify(502, Fixtures.obj(mapOf("code" to "bad_gateway")), ManagementOperation.MANAGE_SCOPE)
        assertEquals(ManagementError.Unexpected("Tower returned HTTP 502 (bad_gateway). Nothing was changed."), unexpected)
        assertTrue(unexpected.retryable)
    }

    @Test fun membershipAndVisibilityRulesFollowTheWeb() = runBlocking {
        val tower = FakeManagementTower()
        val membership = api(tower).membership()
        assertEquals(setOf("channel.create", "channel.manage"), membership.permissions)
        assertFalse(membership.isAdmin)
        val alpha = ScopeRecord.parse(Fixtures.obj(tower.scopes[0]))
        val beta = ScopeRecord.parse(Fixtures.obj(tower.scopes[1]))
        assertTrue(ManagementPermissions.canCreateChannel(alpha, membership))
        assertFalse("can_manage false wins, as on the web", ManagementPermissions.canCreateChannel(beta, membership))
        assertTrue(ManagementPermissions.canManage(alpha, membership))
        assertFalse(ManagementPermissions.canManage(beta, membership))
        assertFalse(ManagementPermissions.canCreateScope(membership))
        assertTrue(ManagementPermissions.canCreateScope(WorkspaceMembership(role = "admin")))
        // As on the iPhone, `workspace.manage` makes someone an administrator for which controls are offered (Tower still decides).
        val manager = WorkspaceMembership(role = "member", permissions = setOf("workspace.manage"))
        assertTrue(ManagementPermissions.canCreateScope(manager)); assertTrue(ManagementPermissions.canCreateChannel(beta, manager))
        val dm = ChannelRecord(Fixtures.WORKSPACE_ID, Fixtures.newId(), Fixtures.SCOPE_A, "dm", kind = "dm", updatedAt = "")
        assertFalse("DMs are never managed here", ManagementPermissions.canManage(dm, WorkspaceMembership(role = "owner"), isDirectMessageChannel = true))
        val system = ChannelRecord(Fixtures.WORKSPACE_ID, Fixtures.newId(), Fixtures.SCOPE_A, "sys", kind = "system", updatedAt = "")
        assertFalse(ManagementPermissions.canManage(system, WorkspaceMembership(role = "owner"), isDirectMessageChannel = false))
        val dmScope = ScopeRecord(Fixtures.WORKSPACE_ID, Fixtures.newId(), "Direct", kind = "dm", updatedAt = "")
        assertTrue(ManagementPermissions.canCreateChannel(dmScope, null))
        assertFalse(ManagementPermissions.isPersonallyOrdered(dmScope))
        assertNull("rows from other routes carry no can_manage", dmScope.canManage)
    }

    @Test fun agentChatConfigReplacesLegacyKeys() {
        val metadata = Fixtures.obj(mapOf("contextPrompt" to "legacy", "agent_chat" to mapOf("custom" to 1), "other" to "x"))
        assertEquals("legacy", AgentChatConfig.contextPrompt(metadata))
        val written = AgentChatConfig.write(metadata, "  fresh ")
        assertNull(written["contextPrompt"])
        assertEquals("x", written.string("other"))
        assertEquals(JsonPrimitive(1), written.obj("agent_chat")!!["custom"])
        assertEquals("fresh", AgentChatConfig.contextPrompt(written))
    }
}
