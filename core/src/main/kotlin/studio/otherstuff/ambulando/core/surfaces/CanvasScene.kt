package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.json.JsonFields.flexibleInt
import studio.otherstuff.ambulando.core.json.JsonFields.lowerUuid
import studio.otherstuff.ambulando.core.json.JsonFields.string
import java.util.Base64
import kotlin.coroutines.coroutineContext

// Parsed, display-ready forms of a stored canvas snapshot and of an Excalidraw board scene. Both are pure values
// built from stored bytes on a background dispatcher, never on the main thread, and never written back: rendering a
// board does not mutate it.

/** Naplet or element bounds in canvas world coordinates, exactly as saved (Tower's canvas layout normalisation). */
data class CanvasLayout(val x: Double, val y: Double, val width: Double, val height: Double, val z: Int) {
    companion object {
        internal fun from(value: JsonElement?, minWidth: Double = 8.0, minHeight: Double = 8.0): CanvasLayout {
            val o = value as? JsonObject
            fun n(key: String, fallback: Double) = JsonFields.finite(o?.get(key)) ?: fallback
            return CanvasLayout(n("x", 0.0), n("y", 0.0), maxOf(minWidth, n("width", 360.0)), maxOf(minHeight, n("height", 280.0)),
                JsonFields.finite(o?.get("z"))?.toInt() ?: 1)
        }
    }
}

/** Reference to an Excalidraw board's current version, as projected by Tower for a ready naplet. */
data class BoardReference(
    val fileId: String,
    val versionId: String,
    val versionNumber: Int,
    val sha256: String,
    val sizeBytes: Int,
    val updatedAt: String?,
    /** Tower's `file.write` decision for this viewer on the board's channel (the editor is read-only without it). */
    val canWrite: Boolean = false,
)

data class CanvasNaplet(
    val id: String,
    val layout: CanvasLayout,
    val title: String,
    val sourceType: String,
    val content: Content,
    val rowVersion: Int,
    /**
     * The saved layout object as Tower sent it, including keys this build does not draw (such as `task_tree`). A move
     * or resize patches it, so those keys are kept, as the web merges `{ ...naplet.layout, ...change }`.
     */
    val savedLayout: JsonObject,
) {
    sealed interface Content {
        /** A document: its title and a short plain-text excerpt of the body Tower included. */
        data class Document(val docId: String, val excerpt: String) : Content
        data class TaskList(val channelId: String, val tasks: List<TaskSummary>) : Content
        /** A task tree for a channel: its scope and whether this viewer may create tasks there (Tower's `task.create`). */
        data class TaskTree(val channelId: String, val channelTitle: String?, val scopeId: String?, val canCreate: Boolean) : Content
        data class ChatThread(val threadId: String, val messages: List<MessageSummary>) : Content
        /** An HTML artefact: web content, opened in a labelled view. */
        data class Html(val fileId: String, val versionId: String?) : Content
        data class Excalidraw(val board: BoardReference) : Content
        data class Canvas(val canvasId: String) : Content
        /** A source type this build does not know; shown, not dropped. */
        data class Unknown(val type: String) : Content
        /** Tower did not authorize the source for this viewer (or it no longer exists); only the frame is shown. */
        data object Denied : Content
    }

    data class TaskSummary(val id: String, val title: String, val state: String)

    data class MessageSummary(val id: String, val author: String, val body: String, val createdAt: String)

    val isReady: Boolean get() = content != Content.Denied
}

/** Native Canvas drawing elements: text, rectangle, diamond, ellipse, arrow, line, draw, frame. */
data class CanvasElement(
    val id: String,
    val kind: Kind?,
    val kindName: String,
    val layout: CanvasLayout,
    val text: String?,
    val fontSize: Double,
    /** Arrow/line endpoints and optional quadratic control point, relative to the layout origin. */
    val start: List<Double>,
    val end: List<Double>,
    val curve: List<Double>?,
    val lineStyle: String,
    val startArrowhead: String,
    val endArrowhead: String,
    /** Freehand points relative to the layout origin. */
    val points: List<List<Double>>,
    /** A frame used only to group items (`data.role == "group"`) has no visible outline. */
    val isGroup: Boolean,
    /** Tower's version of the element, checked by every edit (`expected_row_version`). */
    val rowVersion: Int = 0,
    /** The saved layout and data objects as Tower sent them: an edit patches them, keeping keys this build does not draw. */
    val savedLayout: JsonObject = JsonObject(emptyMap()),
    val savedData: JsonObject = JsonObject(emptyMap()),
) {
    enum class Kind(val wire: String) {
        TEXT("text"), RECTANGLE("rectangle"), DIAMOND("diamond"), ELLIPSE("ellipse"), ARROW("arrow"), LINE("line"), DRAW("draw"), FRAME("frame");

        companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } }
    }
}

/**
 * Plain text of a stored document body (Markdown, or a stored document envelope / content model). Seam: the
 * document module's full body decoder replaces [DocumentText.markdown] once it is ported; this reader covers the
 * envelope, legacy wrappers, Markdown models and the text of ProseMirror states.
 */
object DocumentText {
    private const val ENVELOPE_FORMAT = "document_content_v1"

    private fun isModel(value: JsonElement?): Boolean {
        val o = value as? JsonObject ?: return false
        return string(o["content"]) != null || string((o["editor_state"] as? JsonObject)?.get("type")) == "doc"
    }

    fun markdown(body: String): String {
        val parsed = JsonFields.parse(body)
        val model: JsonObject? = when {
            parsed is JsonObject && string(parsed["format"]) == ENVELOPE_FORMAT && isModel(parsed["content_model"]) -> parsed["content_model"] as JsonObject
            parsed is JsonObject && parsed["format"] == null && isModel(parsed["content_model"]) -> parsed["content_model"] as JsonObject
            isModel(parsed) -> parsed as JsonObject
            else -> null
        }
        if (model == null) return body
        val state = model["editor_state"] as? JsonObject
        if (state != null && string(state["type"]) == "doc") return proseMirrorText(state)
        return string(model["content"]) ?: ""
    }

    /** Text of a ProseMirror document, one line per block. */
    private fun proseMirrorText(node: JsonObject): String {
        val lines = ArrayList<String>()
        fun inline(n: JsonObject, out: StringBuilder) {
            string(n["text"])?.let(out::append)
            if (string(n["type"]) == "hard_break" || string(n["type"]) == "hardBreak") out.append('\n')
            (n["content"] as? JsonArray)?.forEach { (it as? JsonObject)?.let { child -> inline(child, out) } }
        }
        fun block(n: JsonObject) {
            val children = (n["content"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
            val hasBlocks = children.any { string(it["type"]) != "text" && string(it["text"]) == null && (it["content"] as? JsonArray) != null }
            if (string(n["type"]) == "doc" || (hasBlocks && children.none { string(it["type"]) == "text" })) {
                children.forEach(::block)
            } else {
                val out = StringBuilder()
                inline(n, out)
                lines.add(out.toString())
            }
        }
        block(node)
        return lines.joinToString("\n")
    }
}

data class CanvasScene(
    val naplets: List<CanvasNaplet> = emptyList(),
    val elements: List<CanvasElement> = emptyList(),
    /** Elements of kinds this build does not draw (listed on the board). */
    val unsupportedElements: Map<String, Int> = emptyMap(),
) {
    data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    /** World-space bounds of everything on the board. */
    val bounds: Bounds?
        get() {
            val layouts = naplets.map { it.layout } + elements.map { it.layout }
            val first = layouts.firstOrNull() ?: return null
            return layouts.drop(1).fold(Bounds(first.x, first.y, first.x + first.width, first.y + first.height)) { r, l ->
                Bounds(minOf(r.minX, l.x), minOf(r.minY, l.y), maxOf(r.maxX, l.x + l.width), maxOf(r.maxY, l.y + l.height))
            }
        }

    /** Every board reference on the canvas (for fetching their scenes). */
    val boards: List<BoardReference> get() = naplets.mapNotNull { (it.content as? CanvasNaplet.Content.Excalidraw)?.board }

    sealed class ParseException(message: String) : Exception(message) {
        class Invalid : ParseException("This canvas could not be read.") {
            override fun equals(other: Any?) = other is Invalid
            override fun hashCode() = 1
        }
        class TooLarge(val what: String) : ParseException("This canvas is too large to show on this device ($what).") {
            override fun equals(other: Any?) = other is TooLarge && other.what == what
            override fun hashCode() = what.hashCode()
        }
    }

    companion object {
        /**
         * Client-side bounds for one stored snapshot. Tower sets no explicit limit; these keep parsing and drawing
         * bounded, and a snapshot beyond them is refused with a visible error instead of being shown partly.
         */
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_NAPLETS = 2_000
        const val MAX_ELEMENTS = 20_000

        /** Parses on the current coroutine and stops with `CancellationException` when it is cancelled. */
        suspend fun parseCancellable(bytes: ByteArray, documentText: (String) -> String = DocumentText::markdown): CanvasScene {
            val context = coroutineContext
            return parse(bytes, documentText) { context.ensureActive() }
        }

        /**
         * Parses on the caller's (background) thread. `checkCancelled` is called periodically and throws to stop
         * (for example `CancellationException` from `ensureActive`).
         */
        fun parse(bytes: ByteArray, documentText: (String) -> String = DocumentText::markdown, checkCancelled: () -> Unit = {}): CanvasScene {
            if (bytes.size > MAX_BYTES) throw ParseException.TooLarge("more than 32 MB")
            val o = JsonFields.parseObject(bytes) ?: throw ParseException.Invalid()
            val napletRows = o["naplets"] as? JsonArray ?: throw ParseException.Invalid()
            val elementRows = o["elements"] as? JsonArray ?: throw ParseException.Invalid()
            if (napletRows.any { it !is JsonObject } || elementRows.any { it !is JsonObject }) throw ParseException.Invalid()
            if (napletRows.size > MAX_NAPLETS) throw ParseException.TooLarge("more than $MAX_NAPLETS items")
            if (elementRows.size > MAX_ELEMENTS) throw ParseException.TooLarge("more than $MAX_ELEMENTS drawing elements")
            val naplets = ArrayList<CanvasNaplet>()
            napletRows.forEachIndexed { index, row ->
                if (index % 128 == 0) checkCancelled()
                naplet(row as JsonObject, documentText)?.let(naplets::add)
            }
            val elements = ArrayList<CanvasElement>()
            val unsupported = LinkedHashMap<String, Int>()
            elementRows.forEachIndexed { index, row ->
                if (index % 512 == 0) checkCancelled()
                val e = element(row as JsonObject) ?: return@forEachIndexed
                if (e.kind == null) unsupported[e.kindName] = (unsupported[e.kindName] ?: 0) + 1 else elements.add(e)
            }
            // Tower orders by z then creation; a stable sort keeps that order for equal z.
            return CanvasScene(naplets.sortedBy { it.layout.z }, elements.sortedBy { it.layout.z }, unsupported)
        }

        internal fun naplet(row: JsonObject, documentText: (String) -> String = DocumentText::markdown): CanvasNaplet? {
            val id = lowerUuid(row["id"]) ?: return null
            val layout = CanvasLayout.from(row["layout"], 220.0, 160.0)
            val version = flexibleInt(row["row_version"]) ?: 0
            val saved = row["layout"] as? JsonObject ?: JsonObject(emptyMap())
            val source = row["source"] as? JsonObject
            val type = string(source?.get("type"))
            if (string(row["access"]) != "ready" || source == null || type == null) {
                return CanvasNaplet(id, layout, "Not available to you", "", CanvasNaplet.Content.Denied, version, saved)
            }
            val sourceId = lowerUuid(source["id"])
            val content = row["content"] as? JsonObject ?: JsonObject(emptyMap())
            val title = string(row["title"])?.takeIf { it.isNotEmpty() } ?: "Untitled"
            val parsed: CanvasNaplet.Content = when (type) {
                "document" -> CanvasNaplet.Content.Document(sourceId ?: return null, excerpt(string(content["body"]) ?: "", documentText = documentText))
                "task_list" -> {
                    sourceId ?: return null
                    val tasks = (content["tasks"] as? JsonArray ?: JsonArray(emptyList())).take(200).mapNotNull { t ->
                        val o = t as? JsonObject ?: return@mapNotNull null
                        val tid = lowerUuid(o["id"]) ?: return@mapNotNull null
                        CanvasNaplet.TaskSummary(tid, string(o["title"]) ?: "Untitled task", string(o["state"]) ?: "new")
                    }
                    CanvasNaplet.Content.TaskList(sourceId, tasks)
                }
                "task_tree" -> {
                    val channel = lowerUuid(content["channel_id"]) ?: sourceId ?: return null
                    CanvasNaplet.Content.TaskTree(channel, string(content["channel_title"]), lowerUuid(content["scope_id"]), JsonFields.isTrue(content["can_create"]))
                }
                "chat_thread" -> {
                    sourceId ?: return null
                    val messages = (content["messages"] as? JsonArray ?: JsonArray(emptyList())).takeLast(50).mapNotNull { m ->
                        val o = m as? JsonObject ?: return@mapNotNull null
                        val mid = lowerUuid(o["id"]) ?: return@mapNotNull null
                        CanvasNaplet.MessageSummary(mid, string(o["author"]) ?: "", excerpt(string(o["body"]) ?: "", 400, documentText),
                            string(o["created_at"]) ?: "")
                    }
                    CanvasNaplet.Content.ChatThread(sourceId, messages)
                }
                "html" -> CanvasNaplet.Content.Html(sourceId ?: return null, lowerUuid(source["version_id"]))
                "excalidraw" -> {
                    val file = lowerUuid(content["file_id"]) ?: sourceId
                    val versionId = lowerUuid(content["current_version_id"])
                    val sha = string(content["sha256"])?.lowercase()?.takeIf { it.length == 64 }
                    if (file == null || versionId == null || sha == null) {
                        return CanvasNaplet(id, layout, title, type, CanvasNaplet.Content.Unknown("excalidraw (incomplete metadata)"), version, saved)
                    }
                    CanvasNaplet.Content.Excalidraw(BoardReference(file, versionId, flexibleInt(content["current_version_number"]) ?: 0, sha,
                        flexibleInt(content["size_bytes"]) ?: 0, string(content["updated_at"]), JsonFields.isTrue(content["can_write"])))
                }
                "canvas" -> CanvasNaplet.Content.Canvas(sourceId ?: return null)
                else -> CanvasNaplet.Content.Unknown(type)
            }
            return CanvasNaplet(id, layout, title, type, parsed, version, saved)
        }

        internal fun element(row: JsonObject): CanvasElement? {
            val id = string(row["id"])?.takeIf { it.isNotEmpty() } ?: return null
            val kindName = string(row["kind"]) ?: ""
            val kind = CanvasElement.Kind.of(kindName)
            val layout = CanvasLayout.from(row["layout"])
            val d = row["data"] as? JsonObject ?: JsonObject(emptyMap())
            fun pair(value: JsonElement?): List<Double>? {
                val a = value as? JsonArray ?: return null
                if (a.size != 2) return null
                val x = JsonFields.finite(a[0]) ?: return null
                val y = JsonFields.finite(a[1]) ?: return null
                return listOf(x, y)
            }
            val size = JsonFields.finite(d["fontSize"]) ?: 16.0
            val points = (d["points"] as? JsonArray ?: JsonArray(emptyList())).take(5000).mapNotNull(::pair)
            return CanvasElement(id, kind, kindName, layout, string(d["text"]), if (size in 12.0..144.0) size else 16.0,
                pair(d["start"]) ?: listOf(0.0, layout.height), pair(d["end"]) ?: listOf(layout.width, 0.0), pair(d["curve"]),
                string(d["lineStyle"]) ?: "solid", string(d["startArrowhead"]) ?: "none",
                string(d["endArrowhead"]) ?: if (kind == CanvasElement.Kind.ARROW) "triangle" else "none",
                points, kind == CanvasElement.Kind.FRAME && string(d["role"]) == "group",
                JsonFields.flexibleInt(row["row_version"]) ?: 0, row["layout"] as? JsonObject ?: JsonObject(emptyMap()), d)
        }

        private val newline = Regex("[\n\r\u000B\u000C\u0085  ]")

        /** Short plain text from a body that may be Markdown or a stored document envelope. */
        internal fun excerpt(body: String, limit: Int = 600, documentText: (String) -> String = DocumentText::markdown): String {
            // Stored envelopes and content models are recognised first; anything else is Markdown text.
            val text = documentText(JsonFields.prefix(body, 64 * 1024))
            val collapsed = text.split(newline).map { it.trim { c -> c.isWhitespace() } }.filter { it.isNotEmpty() }.joinToString("\n")
            return if (JsonFields.length(collapsed) > limit) JsonFields.prefix(collapsed, limit) + "…" else collapsed
        }
    }
}

// MARK: Excalidraw

/**
 * The supported native subset of a standard `.excalidraw` scene (the web client's canonical board form).
 *
 * Drawn: rectangle, ellipse, diamond, line, arrow (with arrowheads), freedraw, text (including text bound to a
 * container), embedded raster images (PNG, JPEG, GIF, WebP, AVIF data URLs) and frames (outline and name).
 * Approximated, and stated on the board: hand-drawn roughness (clean strokes), hachure/cross-hatch fills (a light tint
 * of the fill colour), fonts (system fonts) and frame clipping (not applied).
 * Not drawn and listed with counts: embeddable, iframe, magicframe, SVG images, images whose file is missing, and any
 * other type. Deleted elements (`isDeleted`) are skipped as Excalidraw does.
 */
data class ExcalidrawScene(
    val elements: List<Element> = emptyList(),
    /** Element types that are not drawn, with counts (shown on the board, never silently dropped). */
    val unsupported: Map<String, Int> = emptyMap(),
    val deletedCount: Int = 0,
    val images: Map<String, Image> = emptyMap(),
    val viewBackgroundColor: String? = null,
    /** Approximations used for this particular scene. */
    val approximations: List<String> = emptyList(),
    val minX: Double = 0.0,
    val minY: Double = 0.0,
    val maxX: Double = 0.0,
    val maxY: Double = 0.0,
) {
    enum class Kind(val wire: String) {
        RECTANGLE("rectangle"), ELLIPSE("ellipse"), DIAMOND("diamond"), LINE("line"), ARROW("arrow"), FREEDRAW("freedraw"),
        TEXT("text"), IMAGE("image"), FRAME("frame");

        companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } }
    }

    data class Element(
        val id: String,
        val kind: Kind,
        val x: Double, val y: Double, val width: Double, val height: Double,
        /** Rotation in radians around the element centre. */
        val angle: Double,
        val strokeColor: String,
        val backgroundColor: String,
        val fillStyle: String,
        val strokeWidth: Double,
        val strokeStyle: String,
        /** 0…1. */
        val opacity: Double,
        val rounded: Boolean,
        /** Line/arrow/freedraw points relative to (x, y). */
        val points: List<List<Double>>,
        val startArrowhead: String?,
        val endArrowhead: String?,
        val text: String?,
        val fontSize: Double,
        val textAlign: String,
        val verticalAlign: String,
        /** The container a text element is bound to (drawn inside it). */
        val containerId: String?,
        val fileId: String?,
        val name: String?,
    )

    class Image(val mimeType: String, val data: ByteArray) {
        override fun equals(other: Any?) = other is Image && other.mimeType == mimeType && other.data.contentEquals(data)
        override fun hashCode() = mimeType.hashCode() * 31 + data.contentHashCode()
    }

    sealed class ParseException(message: String) : Exception(message) {
        class Invalid(val detail: String) : ParseException(detail) {
            override fun equals(other: Any?) = other is Invalid && other.detail == detail
            override fun hashCode() = detail.hashCode()
        }
        class TooLarge : ParseException("This board is too large to show.") {
            override fun equals(other: Any?) = other is TooLarge
            override fun hashCode() = 2
        }
    }

    /** One readable line per unsupported type, for the board's notice. */
    val unsupportedSummary: List<String> get() = unsupported.entries.sortedBy { it.key }.map { "${it.value} × ${it.key}" }

    companion object {
        /** The web's board limits: scene bytes, elements, embedded images and bytes per image. */
        const val MAX_BYTES = 24 * 1024 * 1024
        const val MAX_ELEMENTS = 20_000
        const val MAX_IMAGES = 200
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
        internal val rasterTypes: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif")

        suspend fun parseCancellable(bytes: ByteArray): ExcalidrawScene {
            val context = coroutineContext
            return parse(bytes) { context.ensureActive() }
        }

        /**
         * Parses on the caller's thread (never the main thread). `checkCancelled` is called periodically and throws to
         * stop, for example when the board is closed while a large scene is still being read.
         */
        fun parse(bytes: ByteArray, checkCancelled: () -> Unit = {}): ExcalidrawScene {
            if (bytes.size > MAX_BYTES) throw ParseException.TooLarge()
            var o = JsonFields.parseObject(bytes) ?: throw ParseException.Invalid("Board is not valid JSON")
            val wrapped = o["scene"] as? JsonObject
            if (wrapped != null && wrapped["elements"] is JsonArray) o = wrapped
            val type = string(o["type"])
            if (type != null && type != "excalidraw") throw ParseException.Invalid("Board scene type must be \"excalidraw\"")
            val rows = o["elements"] as? JsonArray ?: throw ParseException.Invalid("Board scene must contain an elements array")
            if (rows.size > MAX_ELEMENTS) throw ParseException.TooLarge()
            val files = o["files"] as? JsonObject ?: JsonObject(emptyMap())
            val elements = ArrayList<Element>()
            val unsupported = LinkedHashMap<String, Int>()
            val images = LinkedHashMap<String, Image>()
            val notes = HashSet<String>()
            var deleted = 0
            var minX = 0.0; var minY = 0.0; var maxX = 0.0; var maxY = 0.0
            var first = true
            fun count(key: String) { unsupported[key] = (unsupported[key] ?: 0) + 1 }
            rows.forEachIndexed { index, value ->
                if (index % 256 == 0) checkCancelled()
                val e = value as? JsonObject
                val id = string(e?.get("id"))
                val typeName = string(e?.get("type"))
                if (e == null || id.isNullOrEmpty() || typeName == null) { count("malformed"); return@forEachIndexed }
                if (JsonFields.isTrue(e["isDeleted"])) { deleted += 1; return@forEachIndexed }
                val kind = Kind.of(typeName) ?: run { count(typeName); return@forEachIndexed }
                fun n(k: String, f: Double = 0.0) = JsonFields.finite(e[k]) ?: f
                var fileId: String? = null
                if (kind == Kind.IMAGE) {
                    val fid = string(e["fileId"])
                    val file = fid?.let { files[it] as? JsonObject }
                    if (fid == null || file == null) { count("image (file missing)"); return@forEachIndexed }
                    val mime = (string(file["mimeType"]) ?: "").lowercase()
                    if (mime !in rasterTypes) { count("image (${mime.ifEmpty { "unknown type" }})"); return@forEachIndexed }
                    if (images[fid] == null) {
                        if (images.size >= MAX_IMAGES) { count("image (over $MAX_IMAGES images)"); return@forEachIndexed }
                        val url = string(file["dataURL"]) ?: ""
                        val comma = url.indexOf(',')
                        val head = if (comma >= 0) url.substring(0, comma).lowercase() else ""
                        val data = if (comma >= 0 && head.startsWith("data:$mime") && head.endsWith(";base64")) {
                            runCatching { Base64.getDecoder().decode(url.substring(comma + 1)) }.getOrNull()
                        } else null
                        if (data == null) { count("image (unreadable data)"); return@forEachIndexed }
                        if (data.size > MAX_IMAGE_BYTES) { count("image (larger than 6 MB)"); return@forEachIndexed }
                        images[fid] = Image(mime, data)
                    }
                    fileId = fid
                }
                val points = (e["points"] as? JsonArray ?: JsonArray(emptyList())).take(20_000).mapNotNull { p ->
                    val a = p as? JsonArray ?: return@mapNotNull null
                    if (a.size < 2) return@mapNotNull null
                    val x = JsonFields.finite(a[0]) ?: return@mapNotNull null
                    val y = JsonFields.finite(a[1]) ?: return@mapNotNull null
                    listOf(x, y)
                }
                val roughness = n("roughness", 1.0)
                val fill = string(e["fillStyle"]) ?: "solid"
                val background = string(e["backgroundColor"]) ?: "transparent"
                if (roughness > 0 && kind != Kind.TEXT && kind != Kind.IMAGE) notes.add("Hand-drawn strokes are drawn as clean lines.")
                if (fill in setOf("hachure", "cross-hatch", "zigzag") && background != "transparent") notes.add("Hatched fills are shown as a light tint.")
                if (kind == Kind.TEXT) notes.add("Text uses system fonts.")
                if (kind == Kind.FRAME) notes.add("Frames are outlined; their contents are not clipped.")
                val element = Element(id, kind, n("x"), n("y"), n("width"), n("height"), n("angle"),
                    string(e["strokeColor"]) ?: "#1e1e1e", background, fill, maxOf(0.5, n("strokeWidth", 2.0)),
                    string(e["strokeStyle"]) ?: "solid", minOf(1.0, maxOf(0.0, n("opacity", 100.0) / 100)),
                    rounded = !JsonFields.isNull(e["roundness"]), points = points,
                    startArrowhead = string(e["startArrowhead"]), endArrowhead = string(e["endArrowhead"]),
                    text = string(e["text"]), fontSize = maxOf(4.0, n("fontSize", 20.0)), textAlign = string(e["textAlign"]) ?: "left",
                    verticalAlign = string(e["verticalAlign"]) ?: "top", containerId = string(e["containerId"])?.takeIf { it.isNotEmpty() },
                    fileId = fileId, name = string(e["name"]))
                elements.add(element)
                // Bounds include line points, which may extend beyond width/height.
                val xs = mutableListOf(element.x, element.x + element.width)
                val ys = mutableListOf(element.y, element.y + element.height)
                for (p in points) { xs.add(element.x + p[0]); ys.add(element.y + p[1]) }
                if (first) {
                    minX = xs.min(); minY = ys.min(); maxX = xs.max(); maxY = ys.max(); first = false
                } else {
                    minX = minOf(minX, xs.min()); minY = minOf(minY, ys.min()); maxX = maxOf(maxX, xs.max()); maxY = maxOf(maxY, ys.max())
                }
            }
            return ExcalidrawScene(elements, unsupported, deleted, images, string((o["appState"] as? JsonObject)?.get("viewBackgroundColor")),
                notes.sorted(), minX, minY, maxX, maxY)
        }
    }
}
