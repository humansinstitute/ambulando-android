package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields
import kotlin.math.abs
import kotlin.math.roundToLong

// Drawing elements on a canvas (sol `canvas/page.js` and `sync-service.js`; the iPhone client's CanvasElementEditing):
//
// - `POST   /canvases/:c/elements` `{ kind, layout, data }` (a text element needs text)
// - `PATCH  /canvases/:c/elements/:e` `{ layout?, data?, expected_row_version }`
// - `DELETE /canvases/:c/elements/:e` `{ expected_row_version }`
//
// Each is one confirmed request with Tower's version check, followed by a fresh snapshot, like the naplet edits.

/** A drawing element to create: sol's text, arrow and frame tools, the shapes it draws, and group frames. */
sealed interface NewCanvasElement {
    data class Text(val text: String) : NewCanvasElement
    /** A rectangle, ellipse or diamond. */
    data class Shape(val kind: CanvasElement.Kind) : NewCanvasElement
    /** An arrow (or a plain line) between two canvas points. */
    data class Arrow(val from: List<Double>, val to: List<Double>, val line: Boolean = false) : NewCanvasElement
    /** A visible frame (sol's frame tool). */
    data object Frame : NewCanvasElement
    /** A group frame around canvas items (sol `createGroup`): `members` are their `naplet:<id>`/`element:<id>` keys. */
    data class Group(val members: List<String>) : NewCanvasElement
}

/** Tower's element layout bounds (`normalizeFlightDeckPgCanvasElementLayout`). */
const val ELEMENT_MIN_SIZE = 8.0
const val ELEMENT_MAX_SIZE = 4000.0

/** An item's key in a group's `members` (sol `canvasItemKey`). */
fun canvasItemKey(type: String, id: String) = "$type:$id"

private fun whole(v: Double): Double = if (v < 0) -(-v).roundToLong().toDouble() else v.roundToLong().toDouble()

/** Clamps an element frame to what Tower stores, rounded to whole points. */
fun elementLayout(layout: CanvasLayout): CanvasLayout = CanvasLayout(whole(layout.x), whole(layout.y),
    whole(layout.width).coerceIn(ELEMENT_MIN_SIZE, ELEMENT_MAX_SIZE), whole(layout.height).coerceIn(ELEMENT_MIN_SIZE, ELEMENT_MAX_SIZE),
    layout.z.coerceIn(1, 100_000))

/** The frame around items, with sol's 32-point padding (`groupBounds`). */
fun groupBounds(items: List<CanvasLayout>, padding: Double = 32.0): CanvasLayout {
    val minX = items.minOf { it.x }; val minY = items.minOf { it.y }
    return CanvasLayout(minX - padding, minY - padding, items.maxOf { it.x + it.width } - minX + padding * 2,
        items.maxOf { it.y + it.height } - minY + padding * 2, maxOf(1, items.minOf { it.z } - 1))
}

/** An arrow's frame and its ends relative to the frame's corner. */
data class ArrowGeometry(val layout: CanvasLayout, val start: List<Double>, val end: List<Double>)

/** sol `arrowGeometry`: the frame spans both canvas points (at least 8 points); the ends are relative to its corner. */
fun arrowGeometry(from: List<Double>, to: List<Double>, z: Int): ArrowGeometry {
    val x = minOf(from[0], to[0]); val y = minOf(from[1], to[1])
    val box = elementLayout(CanvasLayout(x, y, maxOf(ELEMENT_MIN_SIZE, abs(to[0] - from[0])), maxOf(ELEMENT_MIN_SIZE, abs(to[1] - from[1])), z))
    return ArrowGeometry(box, listOf(whole(from[0] - box.x), whole(from[1] - box.y)), listOf(whole(to[0] - box.x), whole(to[1] - box.y)))
}

/** The canvas points of an arrow's or line's ends. */
fun CanvasElement.endPoints(): Pair<List<Double>, List<Double>> =
    listOf(layout.x + start[0], layout.y + start[1]) to listOf(layout.x + end[0], layout.y + end[1])

/**
 * Moves one end of an arrow or line to a canvas point, keeping the other end and any bend where they are (sol
 * `moveArrowEndpoint`): the new frame and the data keys for [updateElement].
 */
fun CanvasElement.movingEnd(start: Boolean, to: List<Double>): Pair<CanvasLayout, Map<String, JsonElement>> {
    val (a0, b0) = endPoints()
    val a = if (start) to else a0
    val b = if (start) b0 else to
    val bend = curve?.let { listOf(layout.x + it[0], layout.y + it[1]) }
    if (bend == null) {
        val g = arrowGeometry(a, b, layout.z)
        return g.layout to mapOf("start" to point(g.start), "end" to point(g.end))
    }
    val left = minOf(a[0], b[0], bend[0]); val top = minOf(a[1], b[1], bend[1])
    val box = elementLayout(CanvasLayout(left, top, maxOf(ELEMENT_MIN_SIZE, maxOf(a[0], b[0], bend[0]) - left),
        maxOf(ELEMENT_MIN_SIZE, maxOf(a[1], b[1], bend[1]) - top), layout.z))
    fun relative(p: List<Double>) = point(listOf(whole(p[0] - box.x), whole(p[1] - box.y)))
    return box to mapOf("start" to relative(a), "end" to relative(b), "curve" to relative(bend))
}

private fun num(v: Double): JsonPrimitive = if (v == whole(v)) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
private fun point(p: List<Double>) = JsonArray(listOf(num(p[0]), num(p[1])))
private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

/** The body sol sends to create `element` at `layout` (the arrow's frame is derived from its ends). */
fun elementBody(element: NewCanvasElement, layout: CanvasLayout): JsonObject {
    val frame = elementLayout(layout)
    return when (element) {
        is NewCanvasElement.Text -> {
            val value = element.text.trim()
            if (value.isEmpty()) throw CanvasEditError.Invalid("Text can't be empty.")
            obj("kind" to JsonPrimitive("text"), "layout" to CanvasEditor.layoutObject(frame), "data" to obj("text" to JsonPrimitive(value)))
        }
        is NewCanvasElement.Shape -> {
            if (element.kind !in setOf(CanvasElement.Kind.RECTANGLE, CanvasElement.Kind.ELLIPSE, CanvasElement.Kind.DIAMOND)) {
                throw CanvasEditError.Invalid("That shape can't be drawn here.")
            }
            obj("kind" to JsonPrimitive(element.kind.wire), "layout" to CanvasEditor.layoutObject(frame), "data" to obj())
        }
        is NewCanvasElement.Arrow -> {
            val g = arrowGeometry(element.from, element.to, frame.z)
            val data = linkedMapOf<String, JsonElement>("start" to point(g.start), "end" to point(g.end))
            if (!element.line) data["direction"] = JsonPrimitive("forward")
            obj("kind" to JsonPrimitive(if (element.line) "line" else "arrow"), "layout" to CanvasEditor.layoutObject(g.layout), "data" to JsonObject(data))
        }
        NewCanvasElement.Frame -> obj("kind" to JsonPrimitive("frame"), "layout" to CanvasEditor.layoutObject(frame), "data" to obj())
        is NewCanvasElement.Group -> {
            if (element.members.size < 2) throw CanvasEditError.Invalid("Choose at least two items to group.")
            obj("kind" to JsonPrimitive("frame"), "layout" to CanvasEditor.layoutObject(frame), "data" to obj("role" to JsonPrimitive("group"),
                "label" to JsonPrimitive("Group"), "members" to JsonArray(element.members.map { JsonPrimitive(it) })))
        }
    }
}

private fun CanvasEditor.elementPath(canvasId: String, elementId: String) = canvasPath(canvasId) + "/elements/" + CanvasEditor.uuid(elementId)

/** Creates a drawing element and returns its id; the board is read again. */
suspend fun CanvasEditor.createElement(canvasId: String, element: NewCanvasElement, layout: CanvasLayout): String? {
    val o = send("POST", canvasPath(canvasId) + "/elements", elementBody(element, layout), canvasId)
    reloadSnapshot(canvasId)
    return JsonFields.string((o["element"] as? JsonObject)?.get("id"))
}

/**
 * Moves or resizes an element and/or replaces keys of its data (text, font size, line style, arrowheads, arrow ends),
 * keeping the saved keys it does not change; a `JsonNull` value removes a key.
 */
suspend fun CanvasEditor.updateElement(canvasId: String, element: CanvasElement, layout: CanvasLayout? = null, data: Map<String, JsonElement>? = null) {
    val body = linkedMapOf<String, JsonElement>("expected_row_version" to JsonPrimitive(element.rowVersion))
    if (layout != null) body["layout"] = CanvasEditor.layoutObject(elementLayout(layout), element.savedLayout)
    if (data != null) {
        val merged = LinkedHashMap(element.savedData)
        for ((key, value) in data) if (value is JsonNull) merged.remove(key) else merged[key] = value
        if (element.kind == CanvasElement.Kind.TEXT && JsonFields.string(merged["text"]).isNullOrBlank()) throw CanvasEditError.Invalid("Text can't be empty.")
        body["data"] = JsonObject(merged)
    }
    if (body.size == 1) return
    send("PATCH", elementPath(canvasId, element.id), JsonObject(body), canvasId)
    reloadSnapshot(canvasId)
}

suspend fun CanvasEditor.deleteElement(canvasId: String, element: CanvasElement) {
    send("DELETE", elementPath(canvasId, element.id), obj("expected_row_version" to JsonPrimitive(element.rowVersion)), canvasId)
    reloadSnapshot(canvasId)
}

/** A copy of an element 24 points down and right, above everything (sol `duplicateSelected`). */
suspend fun CanvasEditor.duplicateElement(canvasId: String, element: CanvasElement, topZ: Int) {
    val l = element.layout
    val frame = elementLayout(l.copy(x = l.x + CanvasEditor.DUPLICATE_OFFSET, y = l.y + CanvasEditor.DUPLICATE_OFFSET, z = topZ + 1))
    send("POST", canvasPath(canvasId) + "/elements", obj("kind" to JsonPrimitive(element.kindName), "layout" to CanvasEditor.layoutObject(frame),
        "data" to element.savedData), canvasId)
    reloadSnapshot(canvasId)
}

/** Moves an element one step up or down the stacking order (z ± 1 within Tower's bounds). */
suspend fun CanvasEditor.reorderElement(canvasId: String, element: CanvasElement, forward: Boolean) {
    val z = (element.layout.z + if (forward) 1 else -1).coerceIn(1, 100_000)
    if (z != element.layout.z) updateElement(canvasId, element, layout = element.layout.copy(z = z))
}

/** The highest z on the canvas, so a new item goes on top (sol `highestZ`). */
val CanvasScene.highestZ: Int get() = maxOf(0, naplets.maxOfOrNull { it.layout.z } ?: 0, elements.maxOfOrNull { it.layout.z } ?: 0)
