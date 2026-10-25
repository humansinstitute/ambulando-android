package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.Ids
import studio.otherstuff.ambulando.core.bytes
import studio.otherstuff.ambulando.core.j
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.obj

/**
 * Canvas create/rename and naplet add/move/resize/remove against a small fake that follows Tower's checks:
 * visibility (404), `can_manage` (403), source authorization (400) and `expected_row_version` (409).
 */
class CanvasEditingTests {
    private val w = Ids.workspace

    private class FakeTower(val workspaceId: String) : CanvasEditBackend {
        data class Canvas(val id: String, var title: String, var version: Int, val canManage: Boolean = true, var hidden: Boolean = false)
        data class Naplet(val id: String, val canvas: String, val type: String, val source: String, var title: String, var layout: JsonObject, var version: Int)

        val canvases = LinkedHashMap<String, Canvas>()
        val naplets = LinkedHashMap<String, Naplet>()
        val denied = HashSet<String>()
        val titles = HashMap<String, String>()
        val writes = ArrayList<String>()
        var offline = false
        var locked = false
        var nextId = 500

        /** What this device has stored (canvas list and snapshots), refreshed only through the backend calls. */
        val storedList = LinkedHashMap<String, CanvasRecord>()
        val storedSnapshots = HashMap<String, ByteArray>()
        var refreshes = 0

        fun newId() = Ids.uuid(nextId++, "0005")

        private fun canvasJson(c: Canvas) = obj("id" to c.id, "title" to c.title, "row_version" to c.version, "can_manage" to c.canManage)

        fun snapshot(canvasId: String): ByteArray = bytes("naplets" to naplets.values.filter { it.canvas == canvasId }.map { n ->
            if (n.source in denied) mapOf("id" to n.id, "access" to "denied", "layout" to n.layout, "row_version" to n.version)
            else mapOf("id" to n.id, "access" to "ready", "title" to n.title, "layout" to n.layout, "row_version" to n.version,
                "source" to mapOf("type" to n.type, "id" to n.source), "content" to mapOf("body" to "text"))
        }, "elements" to emptyList<Any>())

        override suspend fun requireSigner() { if (locked) error("locked") }

        override suspend fun exchange(method: String, path: String, body: ByteArray): Pair<Int, ByteArray> {
            if (offline) throw java.io.IOException("The network connection was lost.")
            writes.add("$method $path")
            val o = JsonFields.parseObject(body)!!
            val parts = path.removePrefix("/api/v4/flightdeck-pg/workspaces/$workspaceId/").split('/')
            fun answer(status: Int, vararg pairs: Pair<String, Any?>) = status to bytes(*pairs)
            if (parts == listOf("canvases") && method == "POST") {
                val c = Canvas(newId(), JsonFields.string(o["title"])!!, 1)
                canvases[c.id] = c
                return answer(201, "canvas" to canvasJson(c))
            }
            val canvas = canvases[parts[1]]
            if (canvas == null || canvas.hidden) return answer(404, "code" to "canvas_unavailable")
            if (!canvas.canManage) return answer(403, "code" to "forbidden")
            if (parts.size == 2 && method == "PATCH") {
                if (JsonFields.flexibleInt(o["expected_row_version"]) != canvas.version) return answer(409, "code" to "stale_row_version")
                canvas.title = JsonFields.string(o["title"])!!; canvas.version += 1
                return answer(200, "canvas" to canvasJson(canvas))
            }
            if (parts.size == 3 && method == "POST") {
                val source = o["source"]!!.jsonObject
                val sourceId = JsonFields.string(source["id"])!!
                if (sourceId in denied) return answer(400, "code" to "canvas_source_unavailable")
                val n = Naplet(newId(), canvas.id, JsonFields.string(source["type"])!!, sourceId, JsonFields.string(o["title"]) ?: titles[sourceId] ?: "Untitled",
                    o["layout"]!!.jsonObject, 1)
                naplets[n.id] = n
                return answer(201, "naplet" to mapOf("id" to n.id))
            }
            val naplet = naplets[parts[3]] ?: return answer(404, "code" to "naplet_unavailable")
            if (JsonFields.flexibleInt(o["expected_row_version"]) != naplet.version) return answer(409, "code" to "stale_row_version")
            return when (method) {
                "PATCH" -> { naplet.layout = o["layout"]!!.jsonObject; naplet.version += 1; answer(200, "naplet" to mapOf("id" to naplet.id)) }
                "DELETE" -> { naplets.remove(naplet.id); answer(200) }
                else -> answer(405)
            }
        }

        override suspend fun reloadList() {
            storedList.clear()
            canvases.values.filter { !it.hidden }.forEach { storedList[it.id] = CanvasRecord.parse(canvasJson(it), workspaceId) }
        }
        override suspend fun reloadSnapshot(canvasId: String) { storedSnapshots[canvasId] = snapshot(canvasId) }
        override suspend fun hasStoredSnapshot(canvasId: String) = storedSnapshots.containsKey(canvasId)
        override suspend fun removeStoredCanvas(canvasId: String) { storedList.remove(canvasId); storedSnapshots.remove(canvasId) }
        override suspend fun refreshCanvases() { refreshes += 1 }
        override suspend fun refreshCanvas(canvasId: String) { refreshes += 1 }

        fun seedCanvas(title: String, canManage: Boolean = true): String {
            val c = Canvas(newId(), title, 1, canManage)
            canvases[c.id] = c
            return c.id
        }

        fun seedNaplet(canvas: String, type: String, source: String, title: String, layout: Map<String, Any?>): String {
            val n = Naplet(newId(), canvas, type, source, title, j(layout).jsonObject, 1)
            naplets[n.id] = n
            return n.id
        }
    }

    private fun scene(tower: FakeTower, canvas: String) = CanvasScene.parse(tower.storedSnapshots[canvas]!!)

    private suspend inline fun <reified E : Throwable> refused(block: () -> Unit): E {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ${E::class.simpleName}, got $error", error is E)
        return error as E
    }

    @Test fun createAndRenameCanvasWithVersionCheck() = runBlocking {
        val tower = FakeTower(w)
        val editor = CanvasEditor(w, tower)
        val created = editor.createCanvas("  Launch plan  ")
        assertEquals("Launch plan", created.title)
        assertEquals("the list is stored from Tower's answer", listOf("Launch plan"), tower.storedList.values.map { it.title })
        assertTrue(tower.writes.any { it.startsWith("POST ") && it.endsWith("/canvases") })

        editor.renameCanvas(created, "Launch")
        val renamed = tower.storedList[created.id]!!
        assertEquals("Launch", renamed.title)
        assertEquals(2, renamed.rowVersion)

        // Renaming again from the version read before the first rename is refused, never replayed.
        assertEquals(CanvasEditError.Conflict, refused<CanvasEditError> { editor.renameCanvas(created, "Stale") })
        assertEquals("Launch", tower.storedList[created.id]!!.title)
        assertEquals(CanvasEditError.Invalid("A title is required."), refused<CanvasEditError> { editor.createCanvas("   ") })
        assertEquals(CanvasEditError.Invalid("Titles can be at most 200 characters."), refused<CanvasEditError> { editor.createCanvas("x".repeat(201)) })
    }

    @Test fun addMoveResizeAndRemoveNapletKeepsSavedLayoutKeys() = runBlocking {
        val tower = FakeTower(w)
        val editor = CanvasEditor(w, tower)
        val canvas = tower.seedCanvas("Board")
        val doc = Ids.uuid(1, "0006")
        tower.titles[doc] = "Release notes"
        editor.addNaplet(canvas, CanvasEditor.SourceType.DOCUMENT, doc, layout = CanvasLayout(80.4, 90.0, 100.0, 5000.0, 2))
        var naplet = scene(tower, canvas).naplets.single()
        assertEquals("Release notes", naplet.title)
        assertEquals("clamped to Tower's bounds", CanvasLayout(80.0, 90.0, 220.0, 1000.0, 2), naplet.layout)

        // A layout key this build does not draw (a task tree's view) survives a move.
        val tree = tower.seedNaplet(canvas, "task_tree", Ids.channel1, "Tree", mapOf("x" to 0, "y" to 0, "width" to 400, "height" to 300, "z" to 3,
            "task_tree" to mapOf("root_task_id" to null, "collapsed" to emptyList<String>(), "upstream" to emptyList<String>())))
        tower.reloadSnapshot(canvas)
        val treeNaplet = scene(tower, canvas).naplets.first { it.id == tree }
        editor.updateLayout(canvas, treeNaplet, CanvasLayout(500.0, 40.0, 400.0, 300.0, 3))
        val movedTree = scene(tower, canvas).naplets.first { it.id == tree }
        assertEquals(500.0, movedTree.layout.x, 0.0)
        assertNotNull("keys outside x/y/width/height/z are sent back unchanged", movedTree.savedLayout["task_tree"])

        // Saving a tree view patches only `task_tree`.
        editor.saveTaskTreeView(canvas, movedTree, TaskTreeView("inst", rootTaskId = Ids.uuid(9, "0007")))
        val viewed = scene(tower, canvas).naplets.first { it.id == tree }
        assertEquals(Ids.uuid(9, "0007"), TaskTreeView.fromLayout(viewed.savedLayout, viewed.id).rootTaskId)
        assertEquals(500.0, viewed.layout.x, 0.0)

        naplet = scene(tower, canvas).naplets.first { it.id == naplet.id }
        editor.updateLayout(canvas, naplet, CanvasLayout(10.0, 20.0, 640.0, 480.0, 2))
        naplet = scene(tower, canvas).naplets.first { it.id == naplet.id }
        assertEquals(CanvasLayout(10.0, 20.0, 640.0, 480.0, 2), naplet.layout)
        assertEquals(2, naplet.rowVersion)

        editor.removeNaplet(canvas, naplet)
        assertEquals(listOf(tree), scene(tower, canvas).naplets.map { it.id })
        assertFalse("no drawing-element write", tower.writes.any { it.contains("/elements") })
    }

    @Test fun staleNapletVersionIsRefusedAndTheBoardShowsTheOtherChange() = runBlocking {
        val tower = FakeTower(w)
        val editor = CanvasEditor(w, tower)
        val canvas = tower.seedCanvas("Board")
        val id = tower.seedNaplet(canvas, "document", Ids.uuid(2, "0006"), "Notes", mapOf("x" to 0, "y" to 0, "width" to 360, "height" to 280, "z" to 1))
        tower.reloadSnapshot(canvas)
        val stale = scene(tower, canvas).naplets.single()

        // Another device moves it first.
        tower.naplets[id]!!.apply { layout = j(mapOf("x" to 900, "y" to 900, "width" to 360, "height" to 280, "z" to 1)).jsonObject; version = 2 }
        assertEquals(CanvasEditError.Conflict, refused<CanvasEditError> { editor.updateLayout(canvas, stale, CanvasLayout(5.0, 5.0, 360.0, 280.0, 1)) })
        val current = scene(tower, canvas).naplets.single()
        assertEquals("the board was read again and shows the other device's move", 900.0, current.layout.x, 0.0)
        assertEquals(2, current.rowVersion)
        assertEquals(CanvasEditError.Conflict, refused<CanvasEditError> { editor.removeNaplet(canvas, stale) })
        assertEquals(1, scene(tower, canvas).naplets.size)
    }

    @Test fun permissionAccessLossDeniedSourcesAndLockedItems() = runBlocking {
        val tower = FakeTower(w)
        val editor = CanvasEditor(w, tower)
        val readOnly = tower.seedCanvas("Read only", canManage = false)
        val canvas = tower.seedCanvas("Board")
        val secret = Ids.uuid(3, "0006"); val hidden = Ids.uuid(4, "0006")
        val locked = tower.seedNaplet(canvas, "document", secret, "Secret", mapOf("x" to 0, "y" to 0, "width" to 360, "height" to 280, "z" to 1))
        tower.denied += setOf(secret, hidden)
        tower.reloadList(); tower.reloadSnapshot(canvas)

        // Read-only canvas: Tower refuses (the screen hides the controls, Tower still decides).
        assertEquals(CanvasEditError.Forbidden, refused<CanvasEditError> {
            editor.addNaplet(readOnly, CanvasEditor.SourceType.CANVAS, canvas, layout = CanvasLayout(0.0, 0.0, 360.0, 280.0, 1))
        })
        // A source this viewer cannot read is refused and nothing is added.
        assertEquals(CanvasEditError.SourceUnavailable, refused<CanvasEditError> {
            editor.addNaplet(canvas, CanvasEditor.SourceType.DOCUMENT, hidden, layout = CanvasLayout(0.0, 0.0, 360.0, 280.0, 2))
        })
        // A locked item keeps its frame: it cannot be moved, but it can be removed.
        val frame = scene(tower, canvas).naplets.first { it.id == locked }
        assertEquals(CanvasNaplet.Content.Denied, frame.content)
        assertEquals(CanvasEditError.Locked, refused<CanvasEditError> { editor.updateLayout(canvas, frame, CanvasLayout(1.0, 1.0, 360.0, 280.0, 1)) })
        assertFalse("a locked move never reaches Tower", tower.writes.any { it.startsWith("PATCH ") })
        editor.removeNaplet(canvas, frame)
        assertTrue(scene(tower, canvas).naplets.isEmpty())

        // The canvas disappears for this viewer: the write is refused and the stored copy removed.
        tower.canvases[canvas]!!.hidden = true
        assertEquals(CanvasEditError.CanvasUnavailable, refused<CanvasEditError> {
            editor.addNaplet(canvas, CanvasEditor.SourceType.CANVAS, readOnly, layout = CanvasLayout(0.0, 0.0, 360.0, 280.0, 1))
        })
        assertNull(tower.storedSnapshots[canvas])
        assertNull(tower.storedList[canvas])

        // An identity that cannot sign changes nothing.
        tower.locked = true
        assertEquals(CanvasEditError.Locked, refused<CanvasEditError> { editor.createCanvas("New") })
    }

    @Test fun offlineWriteChangesNothing() = runBlocking {
        val tower = FakeTower(w)
        val editor = CanvasEditor(w, tower)
        val canvas = tower.seedCanvas("Board")
        tower.reloadSnapshot(canvas)
        tower.offline = true
        val error = refused<CanvasEditError> {
            editor.addNaplet(canvas, CanvasEditor.SourceType.CANVAS, canvas, layout = CanvasLayout(0.0, 0.0, 360.0, 280.0, 1))
        }
        assertTrue(error is CanvasEditError.Offline)
        assertTrue(error.retryable)
        assertTrue(scene(tower, canvas).naplets.isEmpty())
    }

    @Test fun refusalsAreClassifiedForTheScreen() {
        assertEquals(CanvasEditError.Invalid("Title is too long."),
            CanvasEditError.classify(422, obj("code" to "invalid", "details" to mapOf("fields" to listOf(mapOf("message" to "Title is too long."))))))
        assertEquals(CanvasEditError.Invalid("Tower refused the change (HTTP 400)."), CanvasEditError.classify(400, JsonObject(emptyMap())))
        assertEquals(CanvasEditError.Unexpected("Tower returned HTTP 500 (boom). Nothing was changed."), CanvasEditError.classify(500, obj("code" to "boom")))
        assertEquals(CanvasEditError.Conflict, CanvasEditError.classify(404, obj("code" to "naplet_unavailable")))
        assertFalse(CanvasEditError.Conflict.retryable)
        val layout = CanvasEditor.layoutObject(CanvasEditor.clamped(CanvasLayout(-10.5, 2.5, 5000.0, 10.0, 0)), obj("task_tree" to mapOf("x" to 1)))
        assertEquals(obj("task_tree" to mapOf("x" to 1), "x" to -11, "y" to 3, "width" to 1200, "height" to 160, "z" to 1), layout)
        assertEquals("1", layout["z"]!!.jsonPrimitive.content)
    }

    @Test fun sourceChoicesAreSortedAsAPersonWouldSortThem() {
        val choices = CanvasSourceChoices.build(
            canvases = listOf(CanvasSourceChoices.Named("c1", "Board 10"), CanvasSourceChoices.Named("self", "This board")),
            docs = listOf(CanvasSourceChoices.Named("d1", ""), CanvasSourceChoices.Named("d2", "board 2")),
            channels = listOf(CanvasSourceChoices.Named("ch", "general")),
            threads = listOf(CanvasSourceChoices.Named("t1", "Chat")),
            files = listOf(CanvasSourceChoices.FileRow("f1", "Sketch.EXCALIDRAW", null), CanvasSourceChoices.FileRow("f2", "page.html", "text/html"),
                CanvasSourceChoices.FileRow("f3", "notes.pdf", "application/pdf")),
            excludingCanvas = "self")
        assertEquals(listOf("board 2", "Board 10", "Chat", "general task tree", "general tasks", "page.html", "Sketch", "Untitled document"),
            choices.map { it.title })
        assertEquals("excalidraw:f1", choices.first { it.title == "Sketch" }.id)
    }
}
