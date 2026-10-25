package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.transport.TowerError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.Ids
import studio.otherstuff.ambulando.core.TestKeys
import studio.otherstuff.ambulando.core.bytes
import studio.otherstuff.ambulando.core.j
import studio.otherstuff.ambulando.core.obj
import java.net.URI
import java.util.Base64

/** Context Tree, Canvas snapshot and Excalidraw parsing, agent/Toolbox/pin records, from synthetic Tower-shaped JSON. */
class SurfaceParsingTests {
    private val scope = Ids.scopeA

    // MARK: Context Tree

    @Test fun contextReferenceRefusesForeignRowsAndMalformedArtifacts() {
        val good = ContextReferenceRecord.parse(obj("id" to Ids.uuid(10), "workspace_id" to Ids.workspace, "scope_id" to scope,
            "component_id" to Ids.uuid(11), "target_type" to "artifact", "sort_order" to 0, "row_version" to 1,
            "target" to mapOf("origin" to "https://a.example/path", "project" to "p", "artifact" to "a", "page" to "index.html", "version_policy" to "latest"),
            "resolution" to mapOf("status" to "external")))
        assertNull("an origin with a path is not a canonical artifact target", good.artifact)
        assertEquals(ContextReferenceRecord.Resolution.EXTERNAL, good.resolution)
        val canonical = ContextReferenceRecord.parse(obj("id" to Ids.uuid(12), "workspace_id" to Ids.workspace, "scope_id" to scope,
            "component_id" to Ids.uuid(11), "target_type" to "artifact",
            "target" to mapOf("origin" to "https://A.example:8443", "project" to "p", "artifact" to "a", "page" to "index.html", "version_policy" to "latest")))
        assertEquals(ArtifactTarget("https://a.example:8443", "p", "a"), canonical.artifact)
        assertEquals("https://a.example:8443/artifacts/p/a/", canonical.artifact!!.latestURL.toString())
        val unknown = ContextReferenceRecord.parse(obj("id" to Ids.uuid(13), "workspace_id" to Ids.workspace, "scope_id" to scope,
            "component_id" to Ids.uuid(11), "target_type" to "task", "target" to mapOf("record_id" to Ids.uuid(14).uppercase()), "sort_order" to 0, "row_version" to 1))
        assertEquals("a missing resolution never implies access", ContextReferenceRecord.Resolution.UNAVAILABLE, unknown.resolution)
        assertEquals(Ids.uuid(14), unknown.targetRecordId)
        assertNull(unknown.resolvedTitle)
        assertThrows(TowerError.Malformed::class.java) {
            ContextComponentRecord.parse(obj("id" to "not-a-uuid", "workspace_id" to Ids.workspace, "scope_id" to scope))
        }
        val component = ContextComponentRecord.parse(obj("id" to Ids.uuid(15), "workspace_id" to Ids.workspace, "scope_id" to scope,
            "title" to "  ", "sort_order" to "3", "row_version" to 2, "created_at" to "2026-10-24T00:00:00.000Z"))
        assertEquals("Untitled", component.title)
        assertEquals(3, component.sortOrder)
        assertEquals("2026-10-24T00:00:00.000Z", component.updatedAt)
    }

    // MARK: Canvas

    private val doc = Ids.uuid(20)
    private val denied = Ids.uuid(21)
    private val board = Ids.uuid(22)
    private val boardVersion = Ids.uuid(23)

    private fun naplet(id: String, type: String, sourceId: String, title: String, layout: Map<String, Any>, content: Map<String, Any?>,
                       access: String = "ready") = mapOf("id" to id, "row_version" to 1, "title" to title, "access" to access, "layout" to layout,
        "source" to mapOf("type" to type, "id" to sourceId), "content" to content)

    private fun snapshot(): ByteArray {
        val sha = "ab".repeat(32)
        val naplets = listOf(
            naplet(Ids.uuid(30), "document", doc, "Release notes", mapOf("x" to 40, "y" to 60, "width" to 360, "height" to 280, "z" to 2),
                mapOf("title" to "Release notes", "body" to "# Build 7\n\nContext tree and canvas.", "updated_at" to "2026-10-24T09:00:00.000Z")),
            naplet(Ids.uuid(31), "task_list", Ids.channel1, "Tasks", mapOf("x" to 440, "y" to 60, "width" to 300, "height" to 240, "z" to 1),
                mapOf("tasks" to listOf(mapOf("id" to Ids.uuid(40), "title" to "Ship it", "state" to "ready")))),
            // Tower withholds the source of a denied naplet: only its frame is sent.
            mapOf("id" to Ids.uuid(32), "row_version" to 1, "access" to "denied", "layout" to mapOf("x" to -300, "y" to 400, "width" to 240, "height" to 180, "z" to 3)),
            naplet(Ids.uuid(33), "excalidraw", board, "Architecture sketch", mapOf("x" to 800, "y" to -200, "width" to 600, "height" to 420, "z" to 4),
                mapOf("file_id" to board, "current_version_id" to boardVersion, "current_version_number" to 3, "sha256" to sha, "size_bytes" to 100, "can_write" to true)),
            naplet(Ids.uuid(34), "task_tree", Ids.channel1, "Tree", mapOf("x" to 0, "y" to 0, "width" to 400, "height" to 300, "z" to 5,
                "task_tree" to mapOf("root_task_id" to Ids.uuid(40), "collapsed" to emptyList<String>())),
                mapOf("channel_id" to Ids.channel1, "channel_title" to "general", "scope_id" to scope, "can_create" to true)),
            naplet(Ids.uuid(35), "sticky_note", Ids.uuid(41), "Future", mapOf("z" to 6), emptyMap()),
        )
        val elements = listOf(
            mapOf("id" to "e1", "kind" to "arrow", "layout" to mapOf("x" to 400, "y" to 200, "width" to 40, "height" to 20, "z" to 5), "data" to mapOf("endArrowhead" to "arrow")),
            mapOf("id" to "e2", "kind" to "text", "layout" to mapOf("x" to 40, "y" to 0, "width" to 200, "height" to 40, "z" to 6), "data" to mapOf("text" to "Launch", "fontSize" to 24)),
            mapOf("id" to "e3", "kind" to "sticker", "layout" to mapOf("x" to 0, "y" to 0, "width" to 40, "height" to 40, "z" to 7), "data" to emptyMap<String, Any>()),
        )
        return bytes("naplets" to naplets, "elements" to elements)
    }

    @Test fun canvasSnapshotKeepsSavedLayoutAndDeniedFrames() {
        val scene = CanvasScene.parse(snapshot())
        assertEquals("drawn in saved z order", listOf(1, 2, 3, 4, 5, 6), scene.naplets.map { it.layout.z })
        val docNaplet = scene.naplets.first { it.title == "Release notes" }
        assertEquals("exact saved position and size", CanvasLayout(40.0, 60.0, 360.0, 280.0, 2), docNaplet.layout)
        assertEquals(CanvasNaplet.Content.Document(doc, "# Build 7\nContext tree and canvas."), docNaplet.content)
        val deniedNaplet = scene.naplets.first { it.layout.z == 3 }
        assertEquals(CanvasNaplet.Content.Denied, deniedNaplet.content)
        assertFalse(deniedNaplet.isReady)
        assertEquals("a locked naplet does not say what it points at", "", deniedNaplet.sourceType)
        assertEquals("Not available to you", deniedNaplet.title)
        assertEquals("a denied naplet keeps its frame", CanvasLayout(-300.0, 400.0, 240.0, 180.0, 3), deniedNaplet.layout)
        assertEquals(listOf(CanvasElement.Kind.ARROW, CanvasElement.Kind.TEXT), scene.elements.map { it.kind })
        assertEquals("unknown element kinds are counted, not dropped silently", mapOf("sticker" to 1), scene.unsupportedElements)
        assertEquals("arrow", scene.elements[0].endArrowhead)
        assertEquals("default arrow start (0, height)", listOf(0.0, 20.0), scene.elements[0].start)
        assertEquals(24.0, scene.elements[1].fontSize, 0.0)
        val boardRef = scene.boards.single()
        assertEquals(BoardReference(board, boardVersion, 3, "ab".repeat(32), 100, null, canWrite = true), boardRef)
        val tree = scene.naplets.first { it.sourceType == "task_tree" }
        assertEquals(CanvasNaplet.Content.TaskTree(Ids.channel1, "general", scope, true), tree.content)
        assertTrue("layout keys this build does not draw are kept", tree.savedLayout.containsKey("task_tree"))
        assertEquals(CanvasNaplet.Content.Unknown("sticky_note"), scene.naplets.first { it.layout.z == 6 }.content)
        assertEquals("minimum naplet size", CanvasLayout(0.0, 0.0, 360.0, 280.0, 6), scene.naplets.first { it.layout.z == 6 }.layout)
        val bounds = scene.bounds!!
        assertEquals(-300.0, bounds.minX, 0.0)
        assertEquals(1400.0, bounds.maxX, 0.0)
    }

    @Test fun canvasSnapshotBoundsAndCancellation() = runBlocking {
        assertEquals(CanvasScene.ParseException.Invalid(), runCatching { CanvasScene.parse("not json".toByteArray()) }.exceptionOrNull())
        assertEquals(CanvasScene.ParseException.TooLarge("more than 32 MB"),
            runCatching { CanvasScene.parse(ByteArray(CanvasScene.MAX_BYTES + 1)) }.exceptionOrNull())
        val many = (0..CanvasScene.MAX_NAPLETS).map { mapOf("id" to Ids.uuid(it, "0009"), "access" to "denied", "layout" to emptyMap<String, Any>(), "row_version" to 1) }
        val error = runCatching { CanvasScene.parse(bytes("naplets" to many, "elements" to emptyList<Any>())) }.exceptionOrNull()
        assertEquals("This canvas is too large to show on this device (more than 2000 items).", error?.message)

        val payload = bytes("naplets" to (0 until 1500).map { mapOf("id" to Ids.uuid(it, "0009"), "access" to "denied", "layout" to emptyMap<String, Any>(), "row_version" to 1) },
            "elements" to emptyList<Any>())
        val captured = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        CoroutineScope(Dispatchers.Default).launch {
            coroutineContext.cancel()
            captured.set(runCatching { CanvasScene.parseCancellable(payload) }.exceptionOrNull())
        }.join()
        assertTrue(captured.get() is CancellationException)
        // The plain entry point stops through its callback.
        var calls = 0
        val stopped = runCatching { CanvasScene.parse(payload) { if (++calls > 2) throw CancellationException("closed") } }.exceptionOrNull()
        assertTrue(stopped is CancellationException)
    }

    @Test fun excerptsReadStoredEnvelopesAndMarkdown() {
        val envelope = """{"format":"document_content_v1","content_model":{"content":"# Plan\n\n  first line  \n"}}"""
        assertEquals("# Plan\nfirst line", CanvasScene.excerpt(envelope))
        val proseMirror = """{"content_model":{"editor_state":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Hello"}]},{"type":"paragraph","content":[{"type":"text","text":"World"}]}]}}}"""
        assertEquals("Hello\nWorld", CanvasScene.excerpt(proseMirror))
        assertEquals("a".repeat(10) + "…", CanvasScene.excerpt("a".repeat(20), limit = 10))
    }

    // MARK: Excalidraw

    private val png: String = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private fun element(id: String, type: String, x: Int, y: Int, w: Int, h: Int, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("id" to id, "type" to type, "x" to x, "y" to y, "width" to w, "height" to h, "angle" to 0, "strokeColor" to "#1e1e1e",
            "backgroundColor" to "transparent", "fillStyle" to "solid", "strokeWidth" to 2, "strokeStyle" to "solid", "roughness" to 1, "opacity" to 100,
            "groupIds" to emptyList<String>(), "frameId" to null, "roundness" to null, "seed" to 1, "version" to 3, "versionNonce" to 7, "isDeleted" to false,
            "boundElements" to null, "updated" to 1, "link" to null, "locked" to false) + extra

    private fun boardFixture(): ByteArray = bytes(
        "type" to "excalidraw", "version" to 2, "source" to "wingman-ambulando",
        "elements" to listOf(
            element("rect", "rectangle", 0, 0, 200, 100, mapOf("backgroundColor" to "#a5d8ff", "fillStyle" to "hachure", "roundness" to mapOf("type" to 3))),
            element("ell", "ellipse", 240, 0, 120, 80),
            element("dia", "diamond", 400, 0, 120, 80),
            element("arr", "arrow", 200, 50, 120, 40, mapOf("points" to listOf(listOf(0, 0), listOf(120, 40)), "startArrowhead" to null, "endArrowhead" to "arrow")),
            element("lin", "line", 0, 150, 100, 0, mapOf("points" to listOf(listOf(0, 0), listOf(100, 0)))),
            element("pen", "freedraw", 0, 200, 30, 30, mapOf("points" to listOf(listOf(0, 0), listOf(10, 20), listOf(30, 30)), "pressures" to emptyList<Any>())),
            element("txt", "text", 20, 30, 160, 25, mapOf("text" to "Tower owns records", "fontSize" to 20, "fontFamily" to 1, "textAlign" to "center",
                "verticalAlign" to "middle", "containerId" to "rect", "originalText" to "Tower owns records")),
            element("img", "image", 300, 200, 64, 64, mapOf("fileId" to "png1", "status" to "saved", "scale" to listOf(1, 1))),
            element("svg", "image", 380, 200, 64, 64, mapOf("fileId" to "svg1")),
            element("frm", "frame", -10, -10, 540, 300, mapOf("name" to "Ownership")),
            element("emb", "embeddable", 0, 300, 200, 120, mapOf("link" to "https://example.com")),
            element("ifr", "iframe", 0, 300, 200, 120),
            element("mag", "magicframe", 0, 300, 200, 120),
            element("old", "rectangle", 9000, 9000, 10, 10, mapOf("isDeleted" to true)),
        ),
        "appState" to mapOf("viewBackgroundColor" to "#fdf8f0"),
        "files" to mapOf(
            "png1" to mapOf("id" to "png1", "mimeType" to "image/png", "dataURL" to "data:image/png;base64,$png", "created" to 1),
            "svg1" to mapOf("id" to "svg1", "mimeType" to "image/svg+xml", "dataURL" to "data:image/svg+xml;base64,PHN2Zy8+", "created" to 1),
        ))

    @Test fun excalidrawSubsetIsDrawnAndEverythingElseIsListed() {
        val scene = ExcalidrawScene.parse(boardFixture())
        assertEquals(listOf("rectangle", "ellipse", "diamond", "arrow", "line", "freedraw", "text", "image", "frame"), scene.elements.map { it.kind.wire })
        assertEquals(mapOf("embeddable" to 1, "iframe" to 1, "image (image/svg+xml)" to 1, "magicframe" to 1), scene.unsupported)
        assertEquals(listOf("1 × embeddable", "1 × iframe", "1 × image (image/svg+xml)", "1 × magicframe"), scene.unsupportedSummary)
        assertEquals(1, scene.deletedCount)
        assertEquals(1, scene.images.size)
        assertEquals("image/png", scene.images.values.first().mimeType)
        val arrow = scene.elements.first { it.kind == ExcalidrawScene.Kind.ARROW }
        assertEquals(listOf(listOf(0.0, 0.0), listOf(120.0, 40.0)), arrow.points)
        assertEquals("arrow", arrow.endArrowhead)
        assertNull(arrow.startArrowhead)
        assertEquals("text bound to its container", "rect", scene.elements.first { it.kind == ExcalidrawScene.Kind.TEXT }.containerId)
        assertTrue(scene.elements.first { it.id == "rect" }.rounded)
        assertEquals("the frame reaches furthest left", -10.0, scene.minX, 0.0)
        assertEquals(530.0, scene.maxX, 0.0)
        assertTrue(scene.approximations.contains("Hand-drawn strokes are drawn as clean lines."))
        assertTrue(scene.approximations.contains("Hatched fills are shown as a light tint."))
        assertEquals("#fdf8f0", scene.viewBackgroundColor)
        assertTrue(runCatching { ExcalidrawScene.parse("""{"type":"tldraw","elements":[]}""".toByteArray()) }.exceptionOrNull() is ExcalidrawScene.ParseException.Invalid)
        assertTrue(runCatching { ExcalidrawScene.parse("not json".toByteArray()) }.exceptionOrNull() is ExcalidrawScene.ParseException.Invalid)
        // Artifact-style wrapper is accepted.
        val wrapped = ExcalidrawScene.parse(bytes("scene" to mapOf("elements" to listOf(mapOf("id" to "r", "type" to "rectangle", "x" to 1, "y" to 2, "width" to 3, "height" to 4, "version" to 1)))))
        assertEquals(1, wrapped.elements.size)
    }

    @Test fun excalidrawLimitsAndCancellation() = runBlocking {
        assertEquals(ExcalidrawScene.ParseException.TooLarge(), runCatching { ExcalidrawScene.parse(ByteArray(ExcalidrawScene.MAX_BYTES + 1)) }.exceptionOrNull())
        val many = (0..ExcalidrawScene.MAX_ELEMENTS).map { mapOf("id" to "e$it", "type" to "rectangle", "version" to 1) }
        assertEquals(ExcalidrawScene.ParseException.TooLarge(), runCatching { ExcalidrawScene.parse(bytes("elements" to many)) }.exceptionOrNull())
        val big = Base64.getEncoder().encodeToString(ByteArray(ExcalidrawScene.MAX_IMAGE_BYTES + 1))
        val scene = ExcalidrawScene.parse(bytes(
            "elements" to listOf(mapOf("id" to "i", "type" to "image", "fileId" to "f", "x" to 0, "y" to 0, "width" to 1, "height" to 1, "version" to 1)),
            "files" to mapOf("f" to mapOf("id" to "f", "mimeType" to "image/png", "dataURL" to "data:image/png;base64,$big"))))
        assertTrue(scene.elements.isEmpty())
        assertEquals(mapOf("image (larger than 6 MB)" to 1), scene.unsupported)
        // At most 200 distinct images.
        val images = (0..ExcalidrawScene.MAX_IMAGES).map { mapOf("id" to "i$it", "type" to "image", "fileId" to "f$it", "version" to 1) }
        val files = (0..ExcalidrawScene.MAX_IMAGES).associate { "f$it" to mapOf("mimeType" to "image/png", "dataURL" to "data:image/png;base64,$png") }
        val capped = ExcalidrawScene.parse(bytes("elements" to images, "files" to files))
        assertEquals(ExcalidrawScene.MAX_IMAGES, capped.images.size)
        assertEquals(mapOf("image (over 200 images)" to 1), capped.unsupported)
        // Closing a board while a large scene is parsed stops the work.
        val payload = bytes("elements" to (0 until 5000).map { mapOf("id" to "e$it", "type" to "rectangle", "version" to 1) })
        val captured = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        CoroutineScope(Dispatchers.Default).launch {
            coroutineContext.cancel()
            captured.set(runCatching { ExcalidrawScene.parseCancellable(payload) }.exceptionOrNull())
        }.join()
        assertTrue(captured.get() is CancellationException)
    }

    // MARK: Agents, Toolbox, pins

    @Test fun agentAndActivityRecordsFollowTheWebsAcceptance() {
        val agentNpub = TestKeys.npub
        val agent = WorkspaceAgentRecord.parse(obj("id" to Ids.uuid(50), "workspace_id" to Ids.workspace, "agent_npub" to agentNpub,
            "display_name" to "Helper", "capabilities" to listOf("chat", 3), "archived_at" to null, "row_version" to "4"))
        assertEquals("Helper", agent.displayName)
        assertEquals(listOf("chat"), agent.capabilities)
        assertTrue(agent.isVisible)
        assertFalse(agent.archived)
        assertEquals(4, agent.rowVersion)
        assertThrows(TowerError.Malformed::class.java) {
            WorkspaceAgentRecord.parse(obj("id" to Ids.uuid(51), "workspace_id" to Ids.workspace, "agent_npub" to "not-an-npub"))
        }
        fun activity(state: String, visibility: String = "user_visible", terminal: String? = null) = obj("id" to Ids.uuid(52), "workspace_id" to Ids.workspace,
            "channel_id" to Ids.channel1, "thread_id" to Ids.uuid(53), "activity_id" to "a1", "visibility" to visibility, "sequence" to 2,
            "state" to state, "label" to "Reviewing", "queue_position" to 0, "terminal_at" to terminal, "created_at" to "2026-10-24T00:00:00.000Z")
        val working = AgentActivityRecord.parse(activity("Working"))
        assertEquals("working", working.state)
        assertFalse(working.isTerminal)
        assertNull("queue position 0 is not a position", working.queuePosition)
        assertEquals("2026-10-24T00:00:00.000Z", working.updatedAt)
        assertTrue(AgentActivityRecord.parse(activity("completed")).isTerminal)
        assertTrue(AgentActivityRecord.parse(activity("working", terminal = "2026-10-24T01:00:00.000Z")).isTerminal)
        assertThrows("internal activity is never shown", TowerError.Malformed::class.java) { AgentActivityRecord.parse(activity("working", "internal")) }
    }

    @Test fun toolboxOnlyOpensSafeHttpsOriginsAndLogosFollowTheWebOrder() {
        val objectId = Ids.uuid(60).uppercase()
        val wapp = PersonalWappRecord.parse(obj("id" to Ids.uuid(61), "workspace_id" to Ids.workspace, "title" to "Timesheets",
            "launch_url" to "https://apps.example.com/time/", "icon_url" to "storage://$objectId", "sort_order" to 1, "status" to "Active", "row_version" to 1,
            "metadata" to mapOf("manifest_icon_url" to "https://apps.example.com/icon-192.png", "icon_url" to "http://apps.example.com/plain.png",
                "favicon_url" to "https://apps.example.com/icon-192.png")))
        assertEquals("active", wapp.status)
        assertEquals("https://apps.example.com/time/", wapp.safeLaunchURL.toString())
        assertEquals("storage icon, then the manifest icon once, then the launch origin's favicon; plain HTTP is skipped",
            listOf(LauncherLogoSource.Storage(objectId.lowercase()), LauncherLogoSource.Web(URI("https://apps.example.com/icon-192.png")),
                LauncherLogoSource.Web(URI("https://apps.example.com/favicon.ico"))), wapp.logoCandidates)
        for (refused in listOf("storage://not-a-uuid", "http://apps.example.com/a.png", "https://192.168.1.2/a.png", "javascript:alert(1)",
            "https://user:pw@apps.example.com/a.png", " ")) {
            assertNull(refused, LauncherLogoSource.parse(refused))
        }
        val bare = PersonalWappRecord.parse(obj("id" to Ids.uuid(62), "workspace_id" to Ids.workspace, "title" to "Legacy",
            "launch_url" to "http://legacy.example.com/", "sort_order" to 2, "status" to "active"))
        assertNull("only HTTPS is opened", bare.safeLaunchURL)
        assertEquals("no safe source: the launcher shows initials", emptyList<LauncherLogoSource>(), bare.logoCandidates)
    }

    @Test fun canvasRecordsClampTheViewportAndPinsSortLikeTower() {
        val canvas = CanvasRecord.parse(obj("id" to Ids.uuid(70), "title" to "Board", "viewport" to mapOf("x" to -120, "y" to "40", "scale" to 9),
            "can_manage" to true, "visibility" to mapOf("mode" to "restricted")), Ids.workspace)
        assertEquals(CanvasViewport(-120.0, 40.0, 3.0), canvas.viewport)
        assertEquals("restricted", canvas.visibilityMode)
        assertTrue(canvas.canManage)
        fun pin(n: Int, position: Int?, pinnedAt: String?) = PinRecord.parse(obj("id" to Ids.uuid(n), "workspace_id" to Ids.workspace,
            "resource_type" to "task", "resource_id" to Ids.uuid(n + 100), "pinned" to true, "order_position" to position, "pinned_at" to pinnedAt))
        val pins = listOf(pin(80, 2, "2026-10-01"), pin(81, null, "2026-10-02"), pin(82, 1, "2026-10-03"), pin(83, null, "2026-10-05"))
        assertEquals(listOf(Ids.uuid(83), Ids.uuid(81), Ids.uuid(82), Ids.uuid(80)), pins.sortedWith(PinRecord.displayOrder).map { it.id })
        val legacy = PinRecord.parse(obj("id" to Ids.uuid(84), "workspace_id" to Ids.workspace, "thread_id" to Ids.uuid(85), "pinned" to false))
        assertEquals(PinRecord.ResourceType.THREAD, legacy.resourceType)
        assertFalse("an unpinned row is a tombstone", legacy.pinned)
        assertTrue(j(1) is kotlinx.serialization.json.JsonPrimitive)
    }
}
