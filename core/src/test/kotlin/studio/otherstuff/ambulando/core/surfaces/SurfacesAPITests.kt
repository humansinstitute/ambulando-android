package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.StubTransport
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.queryOf
import studio.otherstuff.ambulando.core.transport.DocumentBodyError
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin

private suspend fun expectError(expected: Exception, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        assertEquals(expected, e)
        return
    }
    fail("expected $expected")
}

class SurfacesAPITests {
    private val scope = Fixtures.SCOPE_A
    private suspend fun api(transport: StubTransport) = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity()))

    @Test fun contextReferenceRefusesForeignRowsAndMalformedArtifacts() {
        val withPath = ContextReferenceRecord.parse(
            Fixtures.obj(
                mapOf(
                    "id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "scope_id" to scope, "component_id" to Fixtures.newId(),
                    "target_type" to "artifact", "sort_order" to 0, "row_version" to 1, "resolution" to mapOf("status" to "external"),
                    "target" to mapOf("origin" to "https://a.example.com/path", "project" to "p", "artifact" to "a", "page" to "index.html", "version_policy" to "latest"),
                ),
            ),
        )
        assertNull("an origin with a path is not a canonical artifact target", withPath.artifact)
        val good = ContextReferenceRecord.parse(
            Fixtures.obj(
                mapOf(
                    "id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "scope_id" to scope, "component_id" to Fixtures.newId(),
                    "target_type" to "artifact", "resolution" to mapOf("status" to "external"),
                    "target" to mapOf("origin" to "https://A.example.com:8443/", "project" to "p", "artifact" to "a", "page" to "index.html", "version_policy" to "latest"),
                ),
            ),
        )
        assertEquals("https://a.example.com:8443/artifacts/p/a/", good.artifact?.latestURL)
        assertEquals(ContextReferenceRecord.Resolution.EXTERNAL, good.resolution)
        val unknown = ContextReferenceRecord.parse(
            Fixtures.obj(
                mapOf(
                    "id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "scope_id" to scope, "component_id" to Fixtures.newId(),
                    "target_type" to "task", "target" to mapOf("record_id" to Fixtures.newId()), "sort_order" to 0, "row_version" to 1,
                ),
            ),
        )
        assertEquals("a missing resolution never implies access", ContextReferenceRecord.Resolution.UNAVAILABLE, unknown.resolution)
        try {
            ContextComponentRecord.parse(Fixtures.obj(mapOf("id" to "not-a-uuid", "workspace_id" to Fixtures.WORKSPACE_ID, "scope_id" to scope)))
            fail()
        } catch (e: TowerError) {
            assertEquals(TowerError.Malformed("context component"), e)
        }
    }

    @Test fun contextComponentsAreScopedToTheirScope() = runBlocking {
        val row = { s: String -> mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "scope_id" to s, "title" to "  ") }
        val transport = StubTransport { 200 to mapOf("components" to listOf(row(scope)), "next_cursor" to null) }
        val api = api(transport)
        assertEquals("Untitled", api.contextComponents(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, scope).single().title)
        transport.setHandler { 200 to mapOf("components" to listOf(row(Fixtures.SCOPE_B))) }
        expectError(TowerError.Malformed("context components")) { api.contextComponents(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, scope) }
    }

    @Test fun canvasSnapshotIsBoundedAndKeptAsSent() = runBlocking {
        val canvas = Fixtures.newId()
        var naplets: List<Map<String, Any?>> = listOf(mapOf("id" to Fixtures.newId(), "access" to "denied", "layout" to mapOf("x" to 1.25)))
        val transport = StubTransport {
            200 to mapOf(
                "workspace_id" to Fixtures.WORKSPACE_ID, "complete" to true, "elements" to emptyList<Any>(), "naplets" to naplets,
                "canvas" to mapOf("id" to canvas, "title" to "Board", "row_version" to 4, "viewport" to mapOf("x" to -120, "y" to 40, "scale" to 9)),
            )
        }
        val api = api(transport)
        val (record, snapshot) = api.canvasSnapshot(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, canvas)
        assertEquals(3.0, record.viewport.scale, 0.0)
        assertEquals(4L, snapshot.canvasRowVersion)
        assertTrue("keys sorted, values as sent", String(snapshot.bytes).startsWith("{\"elements\":[],\"naplets\":[{\"access\":\"denied\""))
        naplets = (0..CanvasSnapshotBounds.MAX_NAPLETS).map { mapOf("id" to "n$it") }
        expectError(CanvasSnapshotBounds.ParseError.TooLarge("more than 2000 items")) { api.canvasSnapshot(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, canvas) }
    }

    @Test fun boardSceneBytesMustMatchTheProjectedHash() = runBlocking {
        val bytes = "{\"elements\":[]}".toByteArray()
        val transport = StubTransport { 200 to bytes }
        val api = api(transport)
        val file = Fixtures.newId()
        val version = Fixtures.newId()
        val scene = api.boardScene(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, file, version, Sha256Hex.of(bytes), bytes.size.toLong())
        assertTrue(bytes.contentEquals(scene.bytes))
        expectError(DocumentBodyError.Integrity) { api.boardScene(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, file, version, "00".repeat(32), 1) }
        expectError(BoardSceneError.TooLarge) { api.boardScene(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, file, version, "x", BOARD_MAX_BYTES + 1L) }
    }

    @Test fun pinsAreReadAcrossPagesAndRestartOnceOnAnExpiredCursor() = runBlocking {
        val pinA = Fixtures.newId()
        val pinB = Fixtures.newId()
        val pin = { id: String, version: Int, pinned: Boolean ->
            mapOf("id" to id, "workspace_id" to Fixtures.WORKSPACE_ID, "resource_type" to "thread", "resource_id" to Fixtures.newId(), "pinned" to pinned, "row_version" to version)
        }
        var expired = true
        val transport = StubTransport { request ->
            when (queryOf(request.url)["cursor"]) {
                null -> 200 to mapOf(
                    "protocol_version" to 1, "has_more" to true, "next_cursor" to "p2",
                    "changes" to listOf(mapOf("family" to "personal_pin", "id" to pinA, "row" to pin(pinA, 1, true)), mapOf("family" to "other", "id" to Fixtures.newId())),
                )
                else -> if (expired) {
                    expired = false
                    409 to mapOf("code" to "auxiliary_cursor_invalid")
                } else {
                    200 to mapOf(
                        "protocol_version" to 1, "has_more" to false,
                        "changes" to listOf(mapOf("family" to "personal_pin", "id" to pinA, "row" to pin(pinA, 2, false)), mapOf("family" to "personal_pin", "id" to pinB, "operation" to "delete")),
                    )
                }
            }
        }
        val result = api(transport).personalPins(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB)
        assertEquals(4, transport.recorded.size)
        assertEquals(listOf(pinA), result.pins.map { it.id })
        assertFalse("the newer tombstone wins", result.pins[0].pinned)
        assertEquals(listOf(pinB), result.deleted)
    }

    @Test fun agentsActivityAndToolboxAreValidated() = runBlocking {
        val transport = StubTransport { request ->
            val path = studio.otherstuff.ambulando.core.support.pathOf(request.url)
            when {
                path.endsWith("/workspace-agents") -> 200 to mapOf(
                    "workspace_agents" to listOf(
                        mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "agent_npub" to TestKeys.syntheticNpub(0x33), "display_name" to "Agent", "archived_at" to null),
                        mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "agent_npub" to "not-an-npub"),
                    ),
                )
                path.endsWith("/agent-activities") -> 200 to mapOf(
                    "agent_activities" to listOf(
                        mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "channel_id" to Fixtures.CHANNEL_1, "activity_id" to "a1", "visibility" to "user_visible", "sequence" to 2, "state" to "COMPLETED"),
                        mapOf("id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID, "channel_id" to Fixtures.CHANNEL_1, "activity_id" to "a2", "visibility" to "internal", "sequence" to 3),
                    ),
                )
                else -> 200 to mapOf("personal_wapps" to listOf(mapOf("record_id" to Fixtures.newId(), "workspace_id" to Fixtures.WORKSPACE_ID)), "next_cursor" to "more")
            }
        }
        val api = api(transport)
        val agents = api.workspaceAgents(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB)
        assertEquals(1, agents.size)
        assertFalse(agents[0].archived)
        val activities = api.agentActivities(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, Fixtures.CHANNEL_1)
        assertEquals(listOf("a1"), activities.map { it.activityId })
        assertTrue(activities[0].isTerminal)
        assertEquals(mapOf("channel_id" to Fixtures.CHANNEL_1, "history_limit" to "0", "limit" to "50"), queryOf(transport.recorded[1].url))
        val wapps = api.personalWapps(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB)
        assertEquals("WApp", wapps.items.single().title)
        assertFalse("a continuation means the list may be incomplete", wapps.complete)
    }

    @Test fun pinDisplayOrderPutsUnpositionedNewestFirst() {
        val make = { position: Int?, at: String?, id: String ->
            PinRecord(Fixtures.WORKSPACE_ID, id, PinRecord.ResourceType.THREAD, id, null, null, true, position, at, 1, "", Fixtures.obj(emptyMap()))
        }
        val sorted = listOf(make(2, null, "a"), make(null, "2026-01-01", "b"), make(1, null, "c"), make(null, "2026-02-01", "d")).sortedWith(PinRecord.displayOrder)
        assertEquals(listOf("d", "b", "c", "a"), sorted.map { it.id })
    }
}
