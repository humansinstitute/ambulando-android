package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/**
 * Tower access a [CanvasEditor] needs, bound to one connection and workspace by the app. Seam: implemented over the
 * transport client and the local store once both are merged.
 */
interface CanvasEditBackend {
    /** Throws when no unlocked signer is available for this connection. */
    suspend fun requireSigner()
    /** One signed JSON request. Throws only when Tower could not be reached (nothing was changed). */
    suspend fun exchange(method: String, path: String, body: ByteArray): Pair<Int, ByteArray>
    /** Reads the canvas list again and stores it; throws when that read fails. */
    suspend fun reloadList()
    /** Reads one canvas snapshot again and stores it; throws when that read fails. */
    suspend fun reloadSnapshot(canvasId: String)
    suspend fun hasStoredSnapshot(canvasId: String): Boolean
    /** Removes a canvas (and its snapshot) this viewer can no longer see. */
    suspend fun removeStoredCanvas(canvasId: String)
    /** Queues a forced user-priority refresh of the canvas list (behind any read already in flight). */
    suspend fun refreshCanvases()
    /** Queues a forced user-priority refresh of one canvas (behind any read already in flight). */
    suspend fun refreshCanvas(canvasId: String)
}

/**
 * Canvas and naplet editing through the Tower routes the web canvas uses:
 *
 * - `POST /canvases` (create; title ≤ 200, workspace visibility as the web's default)
 * - `PATCH /canvases/:c` (rename; `expected_row_version`, `can_manage`)
 * - `POST /canvases/:c/naplets` (add; Tower authorizes the source for this viewer)
 * - `PATCH /canvases/:c/naplets/:n` (move/resize: the whole saved layout with `expected_row_version`)
 * - `DELETE /canvases/:c/naplets/:n` (remove; `expected_row_version`)
 *
 * Like scope and channel management these are confirmed online requests: the user sees the result or the reason it
 * failed, and nothing is queued for a later replay (Tower's create routes carry no idempotency key). After each
 * accepted write the snapshot (or list) is read again and stored, and a keyed refresh is queued behind any read
 * already in flight so an older answer cannot stay on screen. A stale version is never retried with a newer one: the
 * board is re-read and the user decides again.
 *
 * Drawing elements (`/elements`) and Excalidraw board contents are not edited here.
 */
class CanvasEditor(private val workspaceId: String, private val backend: CanvasEditBackend) {
    /** Naplet source types Tower accepts on `POST /naplets` and this build can offer. */
    enum class SourceType(val wire: String) {
        DOCUMENT("document"), TASK_LIST("task_list"), TASK_TREE("task_tree"), CHAT_THREAD("chat_thread"), HTML("html"),
        EXCALIDRAW("excalidraw"), CANVAS("canvas"),
    }

    companion object {
        /** Tower's layout bounds for naplets. */
        const val MIN_WIDTH = 220.0
        const val MIN_HEIGHT = 160.0
        const val MAX_WIDTH = 1200.0
        const val MAX_HEIGHT = 1000.0
        const val MAX_TITLE = 200

        /** Clamps a frame to what Tower will store, rounded to whole points (halves away from zero). */
        fun clamped(layout: CanvasLayout): CanvasLayout = CanvasLayout(round(layout.x), round(layout.y),
            minOf(MAX_WIDTH, maxOf(MIN_WIDTH, round(layout.width))), minOf(MAX_HEIGHT, maxOf(MIN_HEIGHT, round(layout.height))),
            minOf(100_000, maxOf(1, layout.z)))

        private fun round(v: Double): Double = if (v < 0) -(-v).roundToLong().toDouble() else v.roundToLong().toDouble()

        fun title(value: String): String {
            val name = value.trim()
            if (name.isEmpty()) throw CanvasEditError.Invalid("A title is required.")
            if (JsonFields.length(name) > MAX_TITLE) throw CanvasEditError.Invalid("Titles can be at most $MAX_TITLE characters.")
            return name
        }

        /** The saved layout with the frame replaced; every other key (such as `task_tree`) is kept. */
        fun layoutObject(layout: CanvasLayout, base: JsonObject = JsonObject(emptyMap())): JsonObject = JsonObject(base + mapOf(
            "x" to JsonPrimitive(layout.x.toLong()), "y" to JsonPrimitive(layout.y.toLong()), "width" to JsonPrimitive(layout.width.toLong()),
            "height" to JsonPrimitive(layout.height.toLong()), "z" to JsonPrimitive(layout.z)))

        /** `/api/v4/flightdeck-pg/workspaces/<w>`; the id must be a UUID. */
        fun base(workspaceId: String): String = "/api/v4/flightdeck-pg/workspaces/" + uuid(workspaceId)

        internal fun uuid(value: String): String = JsonFields.lowerUuid(value) ?: throw CanvasEditError.Invalid("That item has an invalid id.")

        internal fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    }

    private fun canvasPath(canvasId: String) = base(workspaceId) + "/canvases/" + uuid(canvasId)
    private fun napletPath(canvasId: String, napletId: String) = canvasPath(canvasId) + "/naplets/" + uuid(napletId)

    // MARK: Canvases

    /** Creates a canvas visible to the whole workspace and returns it once the list has been read again. */
    suspend fun createCanvas(title: String): CanvasRecord {
        val name = title(title)
        val body = obj("title" to JsonPrimitive(name), "visibility" to obj("mode" to JsonPrimitive("workspace"),
            "actor_ids" to JsonArray(emptyList()), "group_ids" to JsonArray(emptyList())))
        val o = send("POST", base(workspaceId) + "/canvases", body, canvasId = null)
        val row = o["canvas"] as? JsonObject ?: throw CanvasEditError.SavedButNotRefreshed("no canvas in Tower's answer")
        val created = CanvasRecord.parse(row, workspaceId)
        reloadList()
        return created
    }

    /** Renames a canvas at the version this device last read. Visibility is left as it is. */
    suspend fun renameCanvas(canvas: CanvasRecord, title: String) {
        val name = title(title)
        if (!canvas.canManage) throw CanvasEditError.Forbidden
        send("PATCH", canvasPath(canvas.id), obj("title" to JsonPrimitive(name), "expected_row_version" to JsonPrimitive(canvas.rowVersion)), canvas.id)
        reloadList()
        if (backend.hasStoredSnapshot(canvas.id)) reloadSnapshot(canvas.id)
    }

    // MARK: Naplets

    /** Adds a naplet for a source this viewer can read. Tower decides access; a refused source adds nothing. */
    suspend fun addNaplet(canvasId: String, type: SourceType, sourceId: String, title: String? = null, layout: CanvasLayout) {
        val body = linkedMapOf<String, JsonElement>("source" to obj("type" to JsonPrimitive(type.wire), "id" to JsonPrimitive(uuid(sourceId))),
            "layout" to layoutObject(clamped(layout)))
        val trimmed = title?.trim()
        if (!trimmed.isNullOrEmpty()) body["title"] = JsonPrimitive(title(trimmed))
        send("POST", canvasPath(canvasId) + "/naplets", JsonObject(body), canvasId)
        reloadSnapshot(canvasId)
    }

    /**
     * Moves and/or resizes a naplet. The saved layout is patched, so keys this build does not draw are kept. Locked
     * naplets keep their frame (as on the web, only their removal is offered).
     */
    suspend fun updateLayout(canvasId: String, naplet: CanvasNaplet, to: CanvasLayout) {
        if (!naplet.isReady) throw CanvasEditError.Locked
        patchLayout(canvasId, naplet, layoutObject(clamped(to), naplet.savedLayout))
    }

    /** Patches a naplet's whole saved layout object (move/resize, or view state such as `task_tree`). */
    internal suspend fun patchLayout(canvasId: String, naplet: CanvasNaplet, layout: JsonObject) {
        if (!naplet.isReady) throw CanvasEditError.Locked
        send("PATCH", napletPath(canvasId, naplet.id), obj("layout" to layout, "expected_row_version" to JsonPrimitive(naplet.rowVersion)), canvasId)
        reloadSnapshot(canvasId)
    }

    /** Adds a naplet of any Tower source type with a prepared layout object. */
    internal suspend fun addNaplet(canvasId: String, sourceType: String, sourceId: String, title: String, layoutObject: JsonObject) {
        val body = obj("source" to obj("type" to JsonPrimitive(sourceType), "id" to JsonPrimitive(uuid(sourceId))), "layout" to layoutObject,
            "title" to JsonPrimitive(title(title)))
        send("POST", canvasPath(canvasId) + "/naplets", body, canvasId)
        reloadSnapshot(canvasId)
    }

    /** Removes a naplet from the board. Its source (document, tasks, file…) is not touched. */
    suspend fun removeNaplet(canvasId: String, naplet: CanvasNaplet) {
        send("DELETE", napletPath(canvasId, naplet.id), obj("expected_row_version" to JsonPrimitive(naplet.rowVersion)), canvasId)
        reloadSnapshot(canvasId)
    }

    /** Saves a tree naplet's view (root, collapsed branches, upstream context) into its layout, keeping every other key. */
    suspend fun saveTaskTreeView(canvasId: String, naplet: CanvasNaplet, view: TaskTreeView) {
        patchLayout(canvasId, naplet, JsonObject(naplet.savedLayout + ("task_tree" to view.toJson())))
    }

    /** Places a task tree for `channelId` (Tower authorizes `task.read` on it). */
    suspend fun addTaskTree(canvasId: String, channelId: String, layout: CanvasLayout) {
        val o = JsonObject(layoutObject(clamped(layout)) + ("task_tree" to TaskTreeView(UUID.randomUUID().toString().lowercase()).toJson()))
        addNaplet(canvasId, "task_tree", channelId, "Task Tree", o)
    }

    // MARK: Requests

    /**
     * One signed write. A refusal is classified for the screen; a stale version or lost access also brings the stored
     * board up to date so the user sees what changed.
     */
    private suspend fun send(method: String, path: String, body: JsonObject, canvasId: String?): JsonObject {
        try { backend.requireSigner() } catch (e: CancellationException) { throw e } catch (e: Exception) { throw CanvasEditError.Locked }
        val result = try {
            backend.exchange(method, path, JsonFields.encodeBytes(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CanvasEditError.Offline(e.message ?: e.toString())
        }
        val o = JsonFields.parseObject(result.second) ?: JsonObject(emptyMap())
        if (result.first !in 200..299) {
            val error = CanvasEditError.classify(result.first, o)
            if (canvasId != null) {
                when (error) {
                    CanvasEditError.Conflict -> runCatching { backend.reloadSnapshot(canvasId) }
                    CanvasEditError.CanvasUnavailable -> runCatching { backend.removeStoredCanvas(canvasId) }
                    CanvasEditError.Forbidden -> backend.refreshCanvases()
                    else -> Unit
                }
            }
            throw error
        }
        return o
    }

    private suspend fun reloadList() {
        try {
            backend.reloadList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            backend.refreshCanvases()
            throw CanvasEditError.SavedButNotRefreshed(e.message ?: e.toString())
        }
        backend.refreshCanvases()
    }

    private suspend fun reloadSnapshot(canvasId: String) {
        try {
            backend.reloadSnapshot(canvasId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            backend.refreshCanvas(canvasId)
            throw CanvasEditError.SavedButNotRefreshed(e.message ?: e.toString())
        }
        // Queued behind any read that started before this write, so its older answer is replaced.
        backend.refreshCanvas(canvasId)
    }
}

sealed class CanvasEditError(message: String) : Exception(message) {
    data object Locked : CanvasEditError("Unlock to edit canvases.")
    data class Offline(val detail: String) : CanvasEditError("Couldn't reach Tower, nothing was changed. $detail")
    data object Forbidden : CanvasEditError("You can view this canvas but not change it.")
    /** Someone else changed this canvas or item first (`409 stale_row_version`); the board has been read again. */
    data object Conflict : CanvasEditError("This canvas changed elsewhere, so your change was not saved. The board now shows the latest version; try again.")
    /** The canvas is no longer visible to this viewer (`404 canvas_unavailable`); its stored copy was removed. */
    data object CanvasUnavailable : CanvasEditError("This canvas is no longer available to you.")
    /** Tower would not let this viewer place the chosen source (`400 canvas_source_unavailable`). */
    data object SourceUnavailable : CanvasEditError("That item can't be added: it is no longer available to you.")
    data class Invalid(val detail: String) : CanvasEditError(detail)
    data class Unexpected(val detail: String) : CanvasEditError(detail)
    /** Tower saved the change, but reading it back failed; it shows after the next refresh. */
    data class SavedButNotRefreshed(val detail: String) :
        CanvasEditError("Saved on Tower, but the board couldn't be refreshed yet ($detail). It will update on the next refresh.")

    val retryable: Boolean get() = this is Offline || this is Unexpected || this is SavedButNotRefreshed

    companion object {
        fun classify(status: Int, o: JsonObject): CanvasEditError {
            val code = JsonFields.string(o["code"]) ?: ""
            return when {
                status == 403 -> Forbidden
                status == 404 && code == "canvas_unavailable" -> CanvasUnavailable
                status == 404 || status == 409 -> Conflict
                status == 400 && code == "canvas_source_unavailable" -> SourceUnavailable
                status == 400 || status == 422 -> {
                    val rows = (o["fields"] as? JsonArray) ?: ((o["details"] as? JsonObject)?.get("fields") as? JsonArray) ?: JsonArray(emptyList())
                    val fields = rows.mapNotNull { JsonFields.string((it as? JsonObject)?.get("message")) }
                    Invalid(if (fields.isEmpty()) "Tower refused the change (${code.ifEmpty { "HTTP $status" }})." else fields.joinToString(" "))
                }
                else -> Unexpected("Tower returned HTTP $status${if (code.isEmpty()) "" else " ($code)"}. Nothing was changed.")
            }
        }
    }
}

/**
 * Something this viewer has on this device that can be placed on a canvas. Tower still authorizes the source when it
 * is added.
 */
data class CanvasSourceChoice(val type: CanvasEditor.SourceType, val sourceId: String, val title: String) {
    val id: String get() = "${type.wire}:$sourceId"
}

/**
 * Builds the "add to canvas" list from stored records (seam: the store reader supplies these rows). Archived and
 * deleted records are expected to be left out by the caller's query.
 */
object CanvasSourceChoices {
    data class Named(val id: String, val title: String)
    data class FileRow(val id: String, val displayName: String, val contentType: String?)

    /**
     * Other canvases, documents, channel task lists and trees, conversations (newest 300), HTML artefacts and
     * Excalidraw board files, sorted by title as a person would sort them.
     */
    fun build(canvases: List<Named>, docs: List<Named>, channels: List<Named>, threads: List<Named>, files: List<FileRow>,
              excludingCanvas: String): List<CanvasSourceChoice> {
        val out = ArrayList<CanvasSourceChoice>()
        canvases.filter { it.id != excludingCanvas }.forEach { out.add(CanvasSourceChoice(CanvasEditor.SourceType.CANVAS, it.id, it.title)) }
        docs.forEach { out.add(CanvasSourceChoice(CanvasEditor.SourceType.DOCUMENT, it.id, it.title.ifEmpty { "Untitled document" })) }
        channels.forEach { out.add(CanvasSourceChoice(CanvasEditor.SourceType.TASK_LIST, it.id, "${it.title.ifEmpty { "Channel" }} tasks")) }
        channels.forEach { out.add(CanvasSourceChoice(CanvasEditor.SourceType.TASK_TREE, it.id, "${it.title.ifEmpty { "Channel" }} task tree")) }
        threads.take(300).forEach { out.add(CanvasSourceChoice(CanvasEditor.SourceType.CHAT_THREAD, it.id, JsonFields.prefix(it.title.ifEmpty { "Conversation" }, 120))) }
        for (file in files) {
            val type = (file.contentType ?: "").lowercase()
            val name = file.displayName.lowercase()
            if (type == BoardScene.CONTENT_TYPE || name.endsWith(".excalidraw")) {
                val title = if (name.endsWith(".excalidraw")) file.displayName.dropLast(".excalidraw".length) else file.displayName
                out.add(CanvasSourceChoice(CanvasEditor.SourceType.EXCALIDRAW, file.id, title))
            } else if (type.contains("html") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml")) {
                out.add(CanvasSourceChoice(CanvasEditor.SourceType.HTML, file.id, file.displayName))
            }
        }
        return out.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
    }
}

/** Finder-style ordering: case-insensitive, with runs of digits compared by value ("Plan 2" before "Plan 10"). */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i; val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0'); val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
