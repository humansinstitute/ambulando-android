package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.bytes
import studio.otherstuff.ambulando.core.j
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.obj
import java.nio.file.Files
import java.util.UUID

/** Board drafts, saves and conflicts (the web board controller's semantics) against an in-memory version store. */
class BoardEditingTests {
    private val author = "npub1" + "q".repeat(58)
    private val fileId = "00000000-0000-4000-8000-0000000000b1"

    private fun element(id: String, version: Int, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("id" to id, "type" to "rectangle", "version" to version, "versionNonce" to version * 7, "x" to 10, "y" to 20, "width" to 100,
            "height" to 50, "isDeleted" to false) + extra

    private fun sceneJSON(elements: List<Map<String, Any?>>, files: Map<String, Any?> = emptyMap()): ByteArray =
        bytes("type" to "excalidraw", "version" to 2, "elements" to elements, "appState" to emptyMap<String, Any>(), "files" to files)

    /** Tower's file versions for one board: create checks the base version like `POST /files/:f/versions`. */
    private class Store(first: ByteArray) {
        val versions = ArrayList<Pair<BoardVersion, ByteArray>>().apply {
            add(BoardVersion(UUID.randomUUID().toString(), 1, sha256 = Sha256Hex.of(first)) to first)
        }
        val head: BoardVersion get() = synchronized(this) { versions.last().first }
        fun bytes(id: String): ByteArray? = synchronized(this) { versions.firstOrNull { it.first.id == id }?.second }
        fun external(data: ByteArray) = synchronized(this) {
            versions.add(BoardVersion(UUID.randomUUID().toString(), versions.size + 1, sha256 = Sha256Hex.of(data)) to data)
        }

        var renamer: (suspend (String) -> String)? = null

        fun transport() = object : BoardController.Transport {
            override suspend fun versions() = synchronized(this@Store) { versions.last().first.id to versions.map { it.first }.reversed() }
            override suspend fun read(versionId: String, sha256: String?) = bytes(versionId) ?: throw SurfaceHttpException(404, "file_version_unavailable")
            override suspend fun publish(baseVersionId: String, bytes: ByteArray, summary: String): BoardVersion = synchronized(this@Store) {
                if (versions.last().first.id != baseVersionId) throw SurfaceHttpException(409, "stale_base_version")
                val v = BoardVersion(UUID.randomUUID().toString(), versions.size + 1, summary = summary, sha256 = Sha256Hex.of(bytes))
                versions.add(v to bytes)
                v
            }
            override suspend fun canSign() = true
            override suspend fun rename(name: String): String = renamer?.invoke(name) ?: super.rename(name)
        }
    }

    private class Outbox {
        val messages = ArrayList<JsonObject>()
        val states = ArrayList<BoardState>()
        fun send(json: String) = synchronized(this) { messages.add(JsonFields.parseObject(json) ?: JsonObject(emptyMap())) }
        fun last(type: String): JsonObject? = synchronized(this) { messages.lastOrNull { JsonFields.string(it["type"]) == type } }
    }

    private fun reference(store: Store, canWrite: Boolean = true) =
        BoardReference(fileId, store.head.id, store.head.number, store.head.sha256, 10, null, canWrite)

    private fun drafts() = BoardDraftStore(Files.createTempDirectory("board-drafts").toFile())

    private fun controller(store: Store, drafts: BoardDraftStore, outbox: Outbox, transport: BoardController.Transport = store.transport()) =
        BoardController(fileId, "Plan", author, transport, drafts, { s -> synchronized(outbox) { outbox.states.add(s) } })

    /** The editor's change message: the elements it holds now (its own echo of the scene). */
    private fun change(elements: List<Any?>): String = BoardController.message(mapOf("type" to JsonPrimitive("change"),
        "scene" to j(mapOf("type" to "excalidraw", "version" to 2, "elements" to elements, "appState" to emptyMap<String, Any>(), "files" to emptyMap<String, Any>()))))

    private suspend fun answerFlush(board: BoardController, outbox: Outbox, session: String) {
        while (outbox.last("flush") == null) delay(5)
        board.receive(session, BoardController.message(mapOf("type" to JsonPrimitive("flushed"), "token" to outbox.last("flush")!!["token"]!!)))
    }

    @Test fun savePublishesAVersionKeepingUnknownFieldsAndReopens() = runBlocking {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val first = sceneJSON(listOf(element("a", 1, mapOf("customData" to mapOf("keep" to true), "futureField" to 42)),
            element("img", 1, mapOf("type" to "image", "fileId" to "f1"))),
            files = mapOf("f1" to mapOf("id" to "f1", "mimeType" to "image/png", "dataURL" to png, "created" to 1),
                "orphan" to mapOf("mimeType" to "image/png", "dataURL" to png)))
        val store = Store(first); val outbox = Outbox(); val local = drafts()
        val board = controller(store, local, outbox)
        board.attach("s1", outbox::send)
        board.sync(reference(store), "Plan")
        board.receive("s1", """{"type":"ready"}""")
        val load = outbox.last("load")!!
        assertEquals("false", load["readOnly"]!!.jsonPrimitive.content)

        // The editor draws one new shape (and echoes existing ones with their unknown fields).
        val loaded = load["scene"]!!.jsonObject["elements"]!!.jsonArray.toList()
        board.receive("s1", change(loaded + j(element("b", 1))))
        assertTrue(board.current().dirty)
        assertNotNull("draft kept on the device until saved", local.load(fileId))

        // Save waits for the editor to confirm its pending change.
        val saved = async(Dispatchers.Default) { board.save("added b") }
        answerFlush(board, outbox, "s1")
        assertTrue(saved.await())
        assertEquals(2, store.head.number)
        assertEquals("added b", store.head.summary)
        assertEquals("v2", board.current().headerStatus)
        assertNull("a saved draft is removed", local.load(fileId))

        // Reopen: the published bytes keep unknown element fields and referenced images; unreferenced ones are pruned.
        val reopened = BoardScene.parse(store.bytes(store.head.id)!!)
        val a = BoardScene.elements(reopened).first { JsonFields.string(it["id"]) == "a" }
        assertEquals("42", a["futureField"]!!.jsonPrimitive.content)
        assertEquals("true", a["customData"]!!.jsonObject["keep"]!!.jsonPrimitive.content)
        assertEquals(3, BoardScene.elements(reopened).size)
        assertEquals(listOf("f1"), reopened["files"]!!.jsonObject.keys.sorted())
        assertTrue(JsonFields.string(reopened["wingman"]!!.jsonObject["base_version_id"])!!.isNotEmpty())
    }

    @Test fun staleSaveKeepsTheDraftAndMergeRebasesOntoTheLatest() = runBlocking {
        val store = Store(sceneJSON(listOf(element("a", 1), element("c", 1))))
        val outbox = Outbox(); val local = drafts()
        val board = controller(store, local, outbox)
        board.sync(reference(store), "Plan")
        board.attach("s1", outbox::send)
        board.receive("s1", """{"type":"ready"}""")
        board.receive("s1", change(listOf(j(element("a", 2, mapOf("x" to 99))), j(element("c", 1)))))
        // Someone else saves version 2 first (changes c).
        store.external(sceneJSON(listOf(element("a", 1), element("c", 3, mapOf("x" to 5)))))

        val saved = async(Dispatchers.Default) { board.save() }
        answerFlush(board, outbox, "s1")
        assertFalse(saved.await())
        val stale = board.current()
        assertEquals(BoardState.Save.STALE, stale.save)
        assertTrue(stale.dirty)
        assertEquals(2, stale.latestVersionNumber)
        assertEquals("nothing published over the newer version", 2, store.head.number)

        board.mergeLatest()
        val merged = board.current()
        assertEquals(2, merged.baseVersionNumber)
        assertTrue(merged.dirty)
        assertEquals(0, merged.conflicts)
        val scene = outbox.last("load")!!["scene"]!!.jsonObject
        val byId = BoardScene.elements(scene).associateBy { JsonFields.string(it["id"]) }
        assertEquals("my edit kept", "99", byId["a"]!!["x"]!!.jsonPrimitive.content)
        assertEquals("their edit kept", "5", byId["c"]!!["x"]!!.jsonPrimitive.content)
    }

    @Test fun readOnlyBoardsIgnoreEditsAndCommentsAreAttributed() = runBlocking {
        val store = Store(sceneJSON(listOf(element("a", 1))))
        val outbox = Outbox()
        val readOnly = controller(store, drafts(), outbox)
        readOnly.attach("s1", outbox::send)
        readOnly.sync(reference(store, canWrite = false), "Plan")
        readOnly.receive("s1", """{"type":"ready"}""")
        assertEquals("true", outbox.last("load")!!["readOnly"]!!.jsonPrimitive.content)
        readOnly.receive("s1", change(listOf(j(element("a", 5)))))
        assertFalse(readOnly.current().dirty)
        assertNull(readOnly.current().saveAction)
        assertEquals("Read-only · v1", readOnly.current().headerStatus)

        val writable = controller(store, drafts(), outbox)
        writable.attach("s2", outbox::send)
        writable.sync(reference(store), "Plan")
        writable.receive("s2", BoardController.message(mapOf("type" to JsonPrimitive("comment-op"),
            "operation" to j(mapOf("type" to "create", "id" to "t1", "anchor" to mapOf("x" to 1, "y" to 2), "body" to "Why here?")))))
        val comments = outbox.last("comments")!!["comments"]!!.jsonArray
        val message = comments.first().jsonObject["messages"]!!.jsonArray.first().jsonObject
        assertEquals(author, message["author_npub"]!!.jsonPrimitive.content)
        assertEquals("Why here?", message["body"]!!.jsonPrimitive.content)
        assertTrue(writable.current().dirty)
    }

    /**
     * Lock without background reading clears every editor and the scene in memory; the unsaved draft stays on disk and
     * is restored when the board loads again after unlocking.
     */
    @Test fun suspendClearsEditorsAndResumeRestoresTheDraft() = runBlocking {
        val store = Store(sceneJSON(listOf(element("a", 1))))
        val outbox = Outbox(); val local = drafts()
        val board = controller(store, local, outbox)
        board.attach("s1", outbox::send)
        board.sync(reference(store), "Plan")
        board.receive("s1", change(listOf(j(element("a", 2, mapOf("x" to 42))))))
        assertTrue(board.current().dirty)

        board.suspendBoard()
        assertNotNull("the editor page is cleared", outbox.last("clear"))
        val suspended = board.current()
        assertEquals(BoardState.Status.LOADING, suspended.status)
        assertFalse(suspended.canWrite)
        board.receive("s1", change(listOf(j(element("a", 9)))))
        assertNotNull("the draft stays on disk", local.load(fileId))

        board.sync(reference(store), "Plan")
        val resumed = board.current()
        assertEquals(BoardState.Status.READY, resumed.status)
        assertTrue(resumed.dirty)
        assertEquals(BoardState.Save.UNSAVED, resumed.save)
        assertEquals("Unsaved draft restored from this device.", resumed.notice)
        board.receive("s1", """{"type":"ready"}""")
        val element = BoardScene.elements(outbox.last("load")!!["scene"]!!.jsonObject).first()
        assertEquals("the draft before the lock, not the edit sent while suspended", "42", element["x"]!!.jsonPrimitive.content)
    }

    @Test fun renameNeedsWriteAccessAndConfirmsTheName() = runBlocking {
        val store = Store(sceneJSON(listOf(element("a", 1))))
        store.renamer = { it }
        val board = controller(store, drafts(), Outbox())
        board.sync(reference(store, canWrite = false), "Plan")
        assertFalse(board.rename("New name"))
        board.sync(reference(store, canWrite = true), "Plan")
        assertFalse(board.rename("  "))
        assertTrue(board.rename("New name"))
        assertEquals("New name", board.current().title)
    }

    @Test fun deniedBoardClearsDraftsAndEditors() = runBlocking {
        val store = Store(sceneJSON(listOf(element("a", 1))))
        val outbox = Outbox(); val local = drafts()
        val transport = object : BoardController.Transport by store.transport() {
            override suspend fun read(versionId: String, sha256: String?): ByteArray = throw SurfaceHttpException(403, "forbidden")
            override suspend fun versions(): Pair<String?, List<BoardVersion>> = throw SurfaceHttpException(403, "forbidden")
        }
        val board = controller(store, local, outbox, transport)
        board.attach("s1", outbox::send)
        board.sync(reference(store), "Plan")
        val state = board.current()
        assertEquals(BoardState.Status.DENIED, state.status)
        assertEquals("No access", state.headerStatus)
        assertNotNull(outbox.last("clear"))
    }

    // MARK: Pure scene rules

    @Test fun rebaseCountsBothSidedChangesAndKeepsTheDraft() {
        val base = BoardScene.parse(sceneJSON(listOf(element("a", 1), element("b", 1))))
        val draft = BoardScene.parse(sceneJSON(listOf(element("a", 2, mapOf("x" to 1)), element("b", 1), element("new", 1))))
        val latest = BoardScene.parse(sceneJSON(listOf(element("a", 3, mapOf("x" to 2)), element("b", 2, mapOf("x" to 3)))))
        val merged = BoardScene.rebase(base, draft, latest)
        assertEquals(1, merged.conflicts)
        val byId = BoardScene.elements(merged.scene).associateBy { JsonFields.string(it["id"]) }
        assertEquals(listOf("a", "b", "new"), BoardScene.elements(merged.scene).map { JsonFields.string(it["id"]) })
        assertEquals("the draft wins a conflict", "1", byId["a"]!!["x"]!!.jsonPrimitive.content)
        assertEquals("3", byId["b"]!!["x"]!!.jsonPrimitive.content)
    }

    @Test fun restoreRestampsAndTombstonesNewerElements() {
        val target = BoardScene.parse(sceneJSON(listOf(element("a", 1))))
        val latest = BoardScene.parse(sceneJSON(listOf(element("a", 5), element("b", 2))))
        val restored = BoardScene.restore(target, latest, nowMillis = 1000, random = kotlin.random.Random(1))
        val byId = BoardScene.elements(restored).associateBy { JsonFields.string(it["id"]) }
        assertEquals("6", byId["a"]!!["version"]!!.jsonPrimitive.content)
        assertEquals("true", byId["b"]!!["isDeleted"]!!.jsonPrimitive.content)
        assertEquals("3", byId["b"]!!["version"]!!.jsonPrimitive.content)
    }

    @Test fun normalizeRefusesBrokenScenesAndPrunesDefaults() {
        assertTrue(runCatching { BoardScene.normalize(j(mapOf("elements" to listOf(element("a", 0))))) }.exceptionOrNull() is BoardScene.SceneException.Invalid)
        assertTrue(runCatching { BoardScene.normalize(j(mapOf("elements" to listOf(element("a", 1), element("a", 1))))) }.exceptionOrNull() is BoardScene.SceneException.Invalid)
        assertTrue(runCatching { BoardScene.normalize(j(mapOf("type" to "tldraw", "elements" to emptyList<Any>()))) }.exceptionOrNull() is BoardScene.SceneException.Invalid)
        val scene = BoardScene.normalize(j(mapOf("elements" to emptyList<Any>(), "appState" to mapOf("viewBackgroundColor" to "#ffffff", "gridSize" to 10, "zoom" to 2))))
        assertEquals(obj("gridSize" to 10), scene["appState"])
        val fingerprint = BoardScene.fingerprint(scene)
        val zoomed = JsonObject(scene + ("appState" to obj("gridSize" to 10, "scrollX" to 5)))
        assertEquals("view state never changes the fingerprint", fingerprint, BoardScene.fingerprint(BoardScene.normalize(zoomed)))
        assertEquals("Plan v2.excalidraw", BoardScene.fileName(" Plan: v2 "))
        assertEquals("Board.excalidraw", BoardScene.fileName("  "))
    }

    @Test fun discussionMergesMessagesAndValidatesComments() {
        val now = java.time.Instant.parse("2026-10-24T10:00:00Z")
        var counter = 0
        val base = BoardScene.applyCommentOperation(BoardScene.empty(), obj("type" to "create", "id" to "t1", "anchor" to mapOf("x" to 1, "y" to 2), "body" to "Hi"),
            author, now) { "m${counter++}" }
        val mine = BoardScene.applyCommentOperation(base, obj("type" to "reply", "thread_id" to "t1", "body" to "Mine"), author, now) { "m-mine" }
        val theirs = BoardScene.applyCommentOperation(base, obj("type" to "resolve", "thread_id" to "t1", "resolved" to true), author, now)
        val merged = BoardScene.mergeComments(base, mine, theirs)
        assertEquals(0, merged.second)
        val thread = merged.first.single()
        assertEquals(2, (thread["messages"] as JsonArray).size)
        assertEquals("true", thread["resolved"]!!.jsonPrimitive.content)
        assertEquals("2026-10-24T10:00:00.000Z", JsonFields.string((thread["messages"] as JsonArray)[0].jsonObject["created_at"]))
        assertTrue(runCatching { BoardScene.applyCommentOperation(base, obj("type" to "reply", "thread_id" to "nope", "body" to "x"), author) }
            .exceptionOrNull() is BoardScene.SceneException.Invalid)
        assertTrue("an author must be an npub", runCatching { BoardScene.applyCommentOperation(base, obj("type" to "create", "body" to "x"), "someone") }
            .exceptionOrNull() is BoardScene.SceneException.Invalid)
    }

    @Test fun versionRowsAndRequestBodies() {
        val row = obj("id" to "00000000-0000-4000-8000-0000000000C1", "version_number" to "3", "summary" to "x".repeat(400), "sha256_hex" to "AB".repeat(32))
        val version = BoardVersion.parse(row)!!
        assertEquals("00000000-0000-4000-8000-0000000000c1", version.id)
        assertEquals(3, version.number)
        assertEquals(BoardScene.MAX_SUMMARY, version.summary.length)
        assertEquals("ab".repeat(32), version.sha256)
        assertNull(BoardVersion.parse(obj("id" to "x", "version_number" to 1)))
        val (current, list) = BoardRequests.parseVersions(obj("file_id" to fileId, "current_version_id" to version.id,
            "versions" to listOf(row, obj("id" to "00000000-0000-4000-8000-0000000000c2", "version_number" to 4))), fileId)
        assertEquals(version.id, current)
        assertEquals(listOf(4, 3), list.map { it.number })
        val body = BoardRequests.createVersionBody(version.id, "obj-1", "  saved  ", clientMutationId = "m1")
        assertEquals(obj("base_version_id" to version.id, "storage_object_id" to "obj-1", "client_mutation_id" to "m1", "summary" to "saved"), body)
        assertTrue(BoardRequests.confirmRename(obj("file" to mapOf("id" to fileId, "display_name" to "Plan")), fileId, "Plan"))
        assertFalse(BoardRequests.confirmRename(obj("file" to mapOf("id" to fileId, "display_name" to "Other")), fileId, "Plan"))
        assertEquals(BoardEditError.Stale(), BoardEditError.from(SurfaceHttpException(409, "stale_base_version")))
        assertEquals(BoardEditError.Unavailable(), BoardEditError.from(SurfaceHttpException(404)))
        assertTrue(BoardEditError.from(SurfaceHttpException(500)) is BoardEditError.Failed)
    }

    @Test fun headerStatusFollowsTheWebBoardWindow() {
        val ready = BoardState(status = BoardState.Status.READY, canWrite = true, baseVersionId = "b", baseVersionNumber = 7)
        assertEquals("v7", ready.headerStatus)
        assertEquals(BoardState.SaveAction("Saved", false), ready.saveAction)
        assertEquals("Unsaved · v7", ready.copy(dirty = true).headerStatus)
        assertEquals(BoardState.SaveAction("Save", true), ready.copy(dirty = true).saveAction)
        assertEquals("Unsaved · newer v8 saved", ready.copy(dirty = true, latestVersionId = "l", latestVersionNumber = 8).headerStatus)
        assertEquals("Viewing v3 · read-only", ready.copy(viewingVersionId = "h", viewingVersionNumber = 3).headerStatus)
        assertEquals("Saving…", ready.copy(save = BoardState.Save.SAVING).headerStatus)
    }
}
