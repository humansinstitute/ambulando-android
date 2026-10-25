package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.surfaces.agentActivities
import studio.otherstuff.ambulando.core.surfaces.boardScene
import studio.otherstuff.ambulando.core.surfaces.canvasSnapshot
import studio.otherstuff.ambulando.core.surfaces.canvases
import studio.otherstuff.ambulando.core.surfaces.contextComponents
import studio.otherstuff.ambulando.core.surfaces.contextReferences
import studio.otherstuff.ambulando.core.surfaces.napletHTML
import studio.otherstuff.ambulando.core.surfaces.personalPins
import studio.otherstuff.ambulando.core.surfaces.personalWapps
import studio.otherstuff.ambulando.core.surfaces.workspaceAgents
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.store
import studio.otherstuff.ambulando.core.transport.string

/**
 * Context Tree, Canvas, board, agent, Toolbox and pin routes of [SampleSurfaceTower] and [SampleBoardTower] over the
 * seeded sample data (the tower rules behind the iOS `SurfaceTests` and `CanvasEditingTests`).
 */
class SampleSurfaceTowerTests {
    private val w = SampleIds.STUDIO
    private val a = SampleIds.SCOPE_AMBULANDO

    @Test fun contextTreeIsPagedAndReferencesResolvePerViewer() = runBlocking {
        val h = SampleHarness()
        val components = h.api.contextComponents(w, h.app, a)
        assertEquals(4, components.size)
        val surfacesNode = components.first { it.title == "Workspace surfaces" }
        val references = h.api.contextReferences(w, h.app, a, surfacesNode.id)
        assertEquals(3, references.size)
        val raw = parseJsonObject(h.exchange("GET", h.base() + "/scopes/$a/context/components/${surfacesNode.id}/references", null).body, "refs")
            .objects("references")!!.map { it.obj("resolution")!!.string("status") }
        assertEquals(listOf("available", "unavailable", "external"), raw)
        h.tower.surfaces.denyScope(a)
        try {
            h.api.contextComponents(w, h.app, a); fail("denied")
        } catch (e: TowerError.Http) { assertEquals(404 to "context_not_found", e.status to e.code) }
        assertTrue(h.api.contextComponents(w, h.app, SampleIds.SCOPE_OPS).isEmpty())
    }

    @Test fun canvasSnapshotDeniesLockedSourcesAndProjectsTheBoard() = runBlocking {
        val h = SampleHarness()
        val canvases = h.api.canvases(w, h.app)
        assertEquals(listOf("Launch board", "Retro"), canvases.map { it.title })
        val launch = canvases[0].id
        val snapshot = parseJsonObject(h.exchange("GET", h.base() + "/canvases/$launch/snapshot", null).body, "snapshot")
        val naplets = snapshot.objects("naplets")!!
        assertEquals(7, naplets.size)
        assertEquals(1, naplets.count { it.string("access") == "denied" })
        val board = naplets.first { it.obj("source")?.string("type") == "excalidraw" }.obj("content")!!
        val version = board.string("current_version_id")!!
        val bytes = h.client.request(path = h.base() + "/files/${SampleIds.BOARD_FILE}/versions/$version/object", appNpub = h.app)
        assertEquals(board.string("sha256"), Sha256Hex.of(bytes))
        assertTrue(h.tower.surfaces.canvasWrites.isEmpty())
        val html = naplets.first { it.obj("source")?.string("type") == "html" }
        assertTrue(h.api.napletHTML(w, h.app, launch, html.string("id")!!).second.contains("Status"))
        h.tower.surfaces.hideCanvas(launch)
        val r = h.exchange("GET", h.base() + "/canvases/$launch/snapshot", null)
        assertEquals(404, r.status)
        assertEquals(1, h.api.canvases(w, h.app).size)
    }

    @Test fun canvasAndNapletEditsFollowTowerChecks() = runBlocking {
        val h = SampleHarness()
        val created = h.exchange("POST", h.base() + "/canvases", mapOf("title" to "  Plan  "))
        assertEquals(201, created.status)
        val canvas = created.json().obj("canvas")!!.string("id")!!
        assertEquals(400, h.exchange("PATCH", h.base() + "/canvases/$canvas", mapOf("title" to "x")).status)
        assertEquals(409, h.exchange("PATCH", h.base() + "/canvases/$canvas", mapOf("title" to "x", "expected_row_version" to 2)).status)
        assertEquals(200, h.exchange("PATCH", h.base() + "/canvases/$canvas", mapOf("title" to "Renamed", "expected_row_version" to 1)).status)
        // Sources: readable record, locked record, unknown type.
        val layout = mapOf("x" to 10.4, "y" to 20, "width" to 100, "height" to 5000, "z" to 3)
        val added = h.exchange("POST", h.base() + "/canvases/$canvas/naplets", mapOf("source" to mapOf("type" to "document", "id" to SampleIds.CANVAS_DOC), "layout" to layout))
        assertEquals(201, added.status)
        val naplet = added.json().obj("naplet")!!
        assertEquals("Build 7 launch notes", naplet.string("title"))
        assertEquals("220", naplet.obj("layout")!!["width"].toString())
        assertEquals("1000", naplet.obj("layout")!!["height"].toString())
        assertEquals("10", naplet.obj("layout")!!["x"].toString())
        val locked = h.exchange("POST", h.base() + "/canvases/$canvas/naplets", mapOf("source" to mapOf("type" to "document", "id" to SampleIds.LOCKED_FILE), "layout" to layout))
        assertEquals(400 to "canvas_source_unavailable", locked.status to locked.json().string("code"))
        assertEquals(400, h.exchange("POST", h.base() + "/canvases/$canvas/naplets", mapOf("source" to mapOf("type" to "video", "id" to SampleIds.CANVAS_DOC), "layout" to layout)).status)
        val id = naplet.string("id")!!
        assertEquals(409, h.exchange("PATCH", h.base() + "/canvases/$canvas/naplets/$id", mapOf("layout" to layout, "expected_row_version" to 2)).status)
        assertEquals(200, h.exchange("PATCH", h.base() + "/canvases/$canvas/naplets/$id", mapOf("layout" to layout, "expected_row_version" to 1)).status)
        assertEquals(200, h.exchange("DELETE", h.base() + "/canvases/$canvas/naplets/$id", mapOf("expected_row_version" to 2)).status)
        // Every write request is recorded, refused or not.
        assertEquals(10, h.tower.surfaces.canvasWrites.size)
        // A canvas this viewer cannot manage.
        val readOnly = h.tower.surfaces.seedCanvas(title = "Read only", position = 9, canManage = false)
        assertEquals(403, h.exchange("PATCH", h.base() + "/canvases/$readOnly", mapOf("title" to "x", "expected_row_version" to 1)).status)
    }

    @Test fun boardVersionsSaveWithStaleBaseCheck() = runBlocking {
        val h = SampleHarness()
        val path = h.base() + "/files/${SampleIds.BOARD_FILE}/versions"
        val list = h.exchange("GET", path, null).json()
        val current = list.string("current_version_id")!!
        val scene = SampleContent.sampleBoard()
        val objectId = h.api.store(w, h.app, scene, "application/vnd.excalidraw+json", "board.excalidraw", null) {}
        val stale = h.exchange("POST", path, mapOf("base_version_id" to "5a000000-0000-4000-8000-0000000eeeee", "storage_object_id" to objectId))
        assertEquals(409 to "stale_base_version", stale.status to stale.json().string("code"))
        val saved = h.exchange("POST", path, mapOf("base_version_id" to current, "storage_object_id" to objectId, "summary" to "moved a box"))
        assertEquals(201, saved.status)
        assertEquals(TestKeys.NPUB, saved.json().obj("version")!!.string("created_by_actor_npub"))
        assertEquals(2, h.tower.boards.versionCount(SampleIds.BOARD_FILE))
        // The snapshot projects the new head, and its bytes verify.
        val launch = h.api.canvases(w, h.app)[0].id
        val board = parseJsonObject(h.exchange("GET", h.base() + "/canvases/$launch/snapshot", null).body, "s").objects("naplets")!!
            .first { it.obj("source")?.string("type") == "excalidraw" }.obj("content")!!
        val record = h.api.boardScene(w, h.app, SampleIds.BOARD_FILE, board.string("current_version_id")!!, board.string("sha256")!!, scene.size.toLong())
        assertTrue(record.bytes.contentEquals(scene))
        // Someone else saves first: the next publish meets a stale base.
        h.tower.boards.externalSaveBeforeNextPublish = true
        val head = h.exchange("GET", path, null).json().string("current_version_id")!!
        assertEquals(409, h.exchange("POST", path, mapOf("base_version_id" to head, "storage_object_id" to objectId)).status)
        assertEquals(200, h.exchange("PATCH", h.base() + "/files/${SampleIds.BOARD_FILE}", mapOf("display_name" to "Renamed sketch")).status)
    }

    @Test fun agentsActivityAndToolbox() = runBlocking {
        val h = SampleHarness()
        val agents = h.api.workspaceAgents(w, h.app)
        assertEquals(listOf(SampleIds.ORBIT, SampleIds.RELAY), agents.map { it.agentNpub })
        assertEquals(2, h.api.agentActivities(w, h.app, SampleIds.GENERAL).size)
        h.tower.surfaces.denyChannel(SampleIds.GENERAL)
        try {
            h.api.agentActivities(w, h.app, SampleIds.GENERAL); fail("denied")
        } catch (e: TowerError.Http) { assertEquals(403, e.status) }
        val wapps = h.api.personalWapps(w, h.app)
        assertEquals(4, wapps.items.size)
        assertTrue(wapps.complete)
    }

    @Test fun pinsAreReadAcrossPagesAndWritesReplayOrConflict() = runBlocking {
        val h = SampleHarness()
        h.tower.surfaces.pinPageSize = 1
        val pins = h.api.personalPins(w, h.app)
        assertEquals(2, pins.pins.size)
        assertTrue(pins.pins.all { it.pinned })
        val task = SampleIds.CANVAS_TASK
        val path = h.base() + "/me/pins/task/$task"
        val body = mapOf("pinned" to true, "expected_row_version" to 0, "mutation_id" to "5a000000-0000-4000-8000-00000000b001")
        val first = h.exchange("PUT", path, body)
        assertEquals(200, first.status)
        val replay = h.exchange("PUT", path, body)
        assertEquals(first.json().obj("pin")!!.string("id"), replay.json().obj("pin")!!.string("id"))
        assertEquals(409 to "mutation_id_reused", h.exchange("PUT", path, body + ("pinned" to false)).let { it.status to it.json().string("code") })
        assertEquals(409 to "thread_pin_conflict",
            h.exchange("PUT", path, body + ("mutation_id" to "5a000000-0000-4000-8000-00000000b002")).let { it.status to it.json().string("code") })
        val forbidden = h.exchange("PUT", h.base() + "/me/pins/document/${SampleIds.LOCKED_FILE}",
            mapOf("pinned" to true, "expected_row_version" to 0, "mutation_id" to "5a000000-0000-4000-8000-00000000b003"))
        assertEquals(403 to "thread_pin_target_forbidden", forbidden.status to forbidden.json().string("code"))
        assertEquals(400, h.exchange("PUT", path, body + ("extra" to 1)).status)
        assertEquals(3, h.api.personalPins(w, h.app).pins.size)
        // Only the personal pin family is served; an expired continuation restarts one pass.
        assertEquals(400, h.exchange("GET", h.base() + "/record-sync/priority", null).status)
        h.tower.surfaces.invalidatePinCursors()
        assertEquals(3, h.api.personalPins(w, h.app).pins.size)
        // The Lab workspace has no surfaces.
        assertTrue(h.api.canvases(SampleIds.LAB, h.app).isEmpty())
    }

    @Test fun snapshotOfEverySeededCanvasParses() = runBlocking {
        val h = SampleHarness()
        for (canvas in h.api.canvases(w, h.app)) {
            val (_, snapshot) = h.api.canvasSnapshot(w, h.app, canvas.id)
            assertEquals(canvas.id, snapshot.canvasId)
        }
    }
}
