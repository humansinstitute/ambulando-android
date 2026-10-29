package studio.otherstuff.ambulando.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.surfaces.*
import kotlin.math.roundToInt

/** The drawing tools of a canvas in Edit mode (sol's text, arrow and frame tools, plus the shapes it draws). */
internal enum class CanvasTool(val label: String) {
    TEXT("Text"), RECTANGLE("Rectangle"), ELLIPSE("Ellipse"), DIAMOND("Diamond"), ARROW("Arrow"), LINE("Line"), FRAME("Frame");

    /**
     * The element this tool makes from a tap at [start] (canvas points) or a drag from [start] to [end], on top of
     * everything at [z]. A tap gives sol's default sizes; a text element gets [text].
     */
    fun element(start: List<Double>, end: List<Double>?, z: Int, text: String = ""): Pair<NewCanvasElement, CanvasLayout> {
        fun box(defaultWidth: Double, defaultHeight: Double): CanvasLayout = if (end == null) CanvasLayout(start[0], start[1], defaultWidth, defaultHeight, z)
            else CanvasLayout(minOf(start[0], end[0]), minOf(start[1], end[1]), kotlin.math.abs(end[0] - start[0]), kotlin.math.abs(end[1] - start[1]), z)
        val to = end ?: listOf(start[0] + 160, start[1])
        return when (this) {
            TEXT -> NewCanvasElement.Text(text) to box(240.0, 120.0)
            RECTANGLE -> NewCanvasElement.Shape(CanvasElement.Kind.RECTANGLE) to box(160.0, 100.0)
            ELLIPSE -> NewCanvasElement.Shape(CanvasElement.Kind.ELLIPSE) to box(160.0, 100.0)
            DIAMOND -> NewCanvasElement.Shape(CanvasElement.Kind.DIAMOND) to box(160.0, 120.0)
            ARROW -> NewCanvasElement.Arrow(start, to) to CanvasLayout(0.0, 0.0, 8.0, 8.0, z)
            LINE -> NewCanvasElement.Arrow(start, to, line = true) to CanvasLayout(0.0, 0.0, 8.0, 8.0, z)
            FRAME -> NewCanvasElement.Frame to box(320.0, 240.0)
        }
    }
}

/** What the canvas's drawing layer is doing: the active tool, the selected element, or choosing items to group. */
@Stable internal class CanvasDrawingState {
    var tool by mutableStateOf<CanvasTool?>(null)
    var selected by mutableStateOf<String?>(null)
    var grouping by mutableStateOf(false)
    var groupKeys by mutableStateOf(emptySet<String>())
    /** The ends of an arrow being drawn or reshaped, in canvas points. */
    var ghostLine by mutableStateOf<Pair<List<Double>, List<Double>>?>(null)
    /** A text element waiting for its text: where it goes (and the element when editing an existing one). */
    var textAt by mutableStateOf<CanvasLayout?>(null)
    var editingText by mutableStateOf<CanvasElement?>(null)

    fun reset() { tool = null; selected = null; grouping = false; groupKeys = emptySet(); ghostLine = null }
}

/** Screen pixels ↔ canvas points for the current pan and zoom. */
internal class CanvasView(val scale: Float, val x: Float, val y: Float) {
    fun toCanvas(p: Offset) = listOf(((p.x - x) / scale).toDouble(), ((p.y - y) / scale).toDouble())
    fun toScreen(cx: Double, cy: Double) = Offset(cx.toFloat() * scale + x, cy.toFloat() * scale + y)
}

/** The drawing tools and grouping controls, shown in the edit panel. */
@Composable internal fun CanvasDrawingTools(state: CanvasDrawingState, scene: CanvasScene, edits: CanvasEdits) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CanvasTool.entries.forEach { t ->
            FilterChip(selected = state.tool == t, onClick = { state.selected = null; state.grouping = false; state.tool = if (state.tool == t) null else t },
                label = { Text(t.label) }, modifier = Modifier.testTag("canvas.tool.${t.name.lowercase()}"))
        }
        FilterChip(selected = state.grouping, onClick = { state.tool = null; state.selected = null; state.grouping = !state.grouping; state.groupKeys = emptySet() },
            label = { Text("Group…") }, modifier = Modifier.testTag("canvas.tool.group"))
    }
    val hint = when {
        state.grouping -> "Tap two or more items to group them."
        state.tool == CanvasTool.TEXT -> "Tap where the text goes."
        state.tool == CanvasTool.ARROW || state.tool == CanvasTool.LINE -> "Drag from start to end, or tap for a short one."
        state.tool != null -> "Drag to draw it, or tap for the default size."
        else -> "Tap a drawing to select it; drag it to move, its corner to resize, its ends to reshape an arrow."
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(hint, style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground, modifier = Modifier.weight(1f))
        if (state.grouping) TextButton(enabled = state.groupKeys.size >= 2, modifier = Modifier.testTag("canvas.group.confirm"), onClick = {
            val members = state.groupKeys.toList()
            val layouts = members.mapNotNull { key -> itemLayout(scene, key) }
            edits.run { edits.editor.createElement(edits.canvasId, NewCanvasElement.Group(members), groupBounds(layouts)) }
            state.grouping = false; state.groupKeys = emptySet()
        }) { Text("Group ${state.groupKeys.size}") }
    }
}

/** A canvas item's frame by its group key (`naplet:<id>` or `element:<id>`). */
internal fun itemLayout(scene: CanvasScene, key: String): CanvasLayout? {
    val (type, id) = key.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
    return if (type == "naplet") scene.naplets.firstOrNull { it.id == id }?.layout else scene.elements.firstOrNull { it.id == id }?.layout
}

/**
 * The editable drawing layer over a canvas in Edit mode: creates with the active tool, selects drawings, moves and
 * resizes them (as an outline until released), reshapes arrows by their ends, and picks items to group. Frames are
 * picked by their top edge so the items inside stay reachable.
 */
@Composable internal fun CanvasDrawingLayer(scene: CanvasScene, edits: CanvasEdits, state: CanvasDrawingState, view: CanvasView, density: Float, onGhost: (CanvasLayout?) -> Unit) {
    val amb = Amb.colors
    fun px(l: CanvasLayout) = IntOffset((l.x.toFloat() * view.scale + view.x).roundToInt(), (l.y.toFloat() * view.scale + view.y).roundToInt())
    fun sizeDp(v: Double, min: Float = 0f) = maxOf(min, v.toFloat() * view.scale / density).dp
    val tool = state.tool
    if (tool != null) {
        Box(Modifier.fillMaxSize().testTag("canvas.draw")
            .pointerInput(tool, view.scale, view.x, view.y) {
                detectTapGestures { p ->
                    val start = view.toCanvas(p)
                    if (tool == CanvasTool.TEXT) { state.textAt = CanvasLayout(start[0], start[1], 240.0, 120.0, scene.highestZ + 1); return@detectTapGestures }
                    val (element, layout) = tool.element(start, null, scene.highestZ + 1)
                    edits.run { edits.editor.createElement(edits.canvasId, element, layout) }
                    state.tool = null
                }
            }
            .pointerInput(tool, view.scale, view.x, view.y) {
                var start = listOf(0.0, 0.0); var end = start
                detectDragGestures(
                    onDragStart = { p -> start = view.toCanvas(p); end = start },
                    onDragCancel = { onGhost(null); state.ghostLine = null },
                    onDragEnd = {
                        onGhost(null); state.ghostLine = null
                        if (tool == CanvasTool.TEXT) { state.textAt = CanvasLayout(minOf(start[0], end[0]), minOf(start[1], end[1]), maxOf(80.0, kotlin.math.abs(end[0] - start[0])), maxOf(40.0, kotlin.math.abs(end[1] - start[1])), scene.highestZ + 1); return@detectDragGestures }
                        val (element, layout) = tool.element(start, end, scene.highestZ + 1)
                        edits.run { edits.editor.createElement(edits.canvasId, element, layout) }
                        state.tool = null
                    },
                ) { change, _ ->
                    change.consume()
                    end = view.toCanvas(change.position)
                    if (tool == CanvasTool.ARROW || tool == CanvasTool.LINE) state.ghostLine = start to end
                    else onGhost(CanvasLayout(minOf(start[0], end[0]), minOf(start[1], end[1]), kotlin.math.abs(end[0] - start[0]), kotlin.math.abs(end[1] - start[1]), 0))
                }
            })
    } else if (state.grouping) {
        val items = scene.naplets.map { canvasItemKey("naplet", it.id) to it.layout } + scene.elements.map { canvasItemKey("element", it.id) to it.layout }
        items.forEach { (key, l) ->
            val chosen = key in state.groupKeys
            Box(Modifier.offset { px(l) }.size(sizeDp(l.width, 24f), sizeDp(l.height, 24f))
                .border(if (chosen) 3.dp else 1.dp, if (chosen) amb.primary else amb.border, RoundedCornerShape(6.dp))
                .clickable { state.groupKeys = if (chosen) state.groupKeys - key else state.groupKeys + key }.testTag("canvas.pick.$key")) {
                if (chosen) Icon(Icons.Outlined.CheckCircle, "Chosen", tint = amb.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
    } else {
        scene.elements.sortedBy { it.layout.z }.forEach { e -> key(e.id) {
            val l = e.layout
            val line = e.kind == CanvasElement.Kind.ARROW || e.kind == CanvasElement.Kind.LINE
            val frame = e.kind == CanvasElement.Kind.FRAME
            val selected = state.selected == e.id
            var moved by remember(e.id, e.rowVersion) { mutableStateOf(l) }
            Box(Modifier.offset { px(l) }.size(sizeDp(l.width, 24f), if (frame && !selected) 28.dp else sizeDp(l.height, 24f))
                .then(if (selected) Modifier.border(2.dp, amb.primary, RoundedCornerShape(4.dp)) else Modifier)
                .pointerInput(e.id) { detectTapGestures { state.selected = if (selected) null else e.id } }
                .then(if (selected) Modifier.pointerInput(e.id, e.rowVersion, view.scale) {
                    detectDragGestures(onDragStart = { moved = l }, onDragCancel = { onGhost(null) }, onDragEnd = {
                        onGhost(null); if (moved != l) edits.run { edits.editor.updateElement(edits.canvasId, e, layout = moved) }
                    }) { change, drag -> change.consume(); moved = moved.copy(x = moved.x + drag.x / view.scale, y = moved.y + drag.y / view.scale); onGhost(moved) }
                } else Modifier)
                .testTag("canvas.element.${e.id}"))
            if (selected) {
            if (!line) {
                var sized by remember(e.id, e.rowVersion) { mutableStateOf(l) }
                val corner = view.toScreen(l.x + l.width, l.y + l.height)
                Icon(Icons.Outlined.OpenInFull, "Resize drawing", tint = amb.primary, modifier = Modifier.offset { IntOffset(corner.x.roundToInt() - 40, corner.y.roundToInt() - 40) }
                    .size(36.dp).background(amb.card, CircleShape).padding(6.dp).testTag("canvas.element.resize")
                    .pointerInput(e.id, e.rowVersion, view.scale) {
                        detectDragGestures(onDragStart = { sized = l }, onDragCancel = { onGhost(null) }, onDragEnd = {
                            onGhost(null); if (sized != l) edits.run { edits.editor.updateElement(edits.canvasId, e, layout = sized) }
                        }) { change, drag ->
                            change.consume()
                            sized = sized.copy(width = maxOf(ELEMENT_MIN_SIZE, sized.width + drag.x / view.scale), height = maxOf(ELEMENT_MIN_SIZE, sized.height + drag.y / view.scale))
                            onGhost(sized)
                        }
                    })
            } else {
                val (a, b) = e.endPoints()
                listOf(true to a, false to b).forEach { (isStart, p) ->
                    var at by remember(e.id, e.rowVersion, isStart) { mutableStateOf(p) }
                    val s = view.toScreen(p[0], p[1])
                    Box(Modifier.offset { IntOffset(s.x.roundToInt() - 36, s.y.roundToInt() - 36) }.size(28.dp).background(amb.primary.copy(alpha = 0.85f), CircleShape)
                        .testTag("canvas.element.${if (isStart) "start" else "end"}")
                        .pointerInput(e.id, e.rowVersion, view.scale, isStart) {
                            detectDragGestures(onDragStart = { at = p }, onDragCancel = { state.ghostLine = null }, onDragEnd = {
                                state.ghostLine = null
                                if (at != p) { val (layout, data) = e.movingEnd(isStart, at); edits.run { edits.editor.updateElement(edits.canvasId, e, layout = layout, data = data) } }
                            }) { change, drag ->
                                change.consume()
                                at = listOf(at[0] + drag.x / view.scale, at[1] + drag.y / view.scale)
                                state.ghostLine = if (isStart) at to b else a to at
                            }
                        })
                }
            }
            val anchor = view.toScreen(l.x + l.width, l.y)
            Box(Modifier.offset { IntOffset(anchor.x.roundToInt() - 8, anchor.y.roundToInt() - 44) }) { ElementOptions(e, scene, edits, state) }
            }
        } }
    }
    state.ghostLine?.let { (from, to) ->
        Canvas(Modifier.fillMaxSize()) { drawLine(amb.primary, view.toScreen(from[0], from[1]), view.toScreen(to[0], to[1]), strokeWidth = 4f) }
    }
}

private val fontSizes = listOf("Small" to 16, "Medium" to 24, "Large" to 36, "Title" to 56)
private val lineStyles = listOf("solid", "dotted", "dashed")
private val arrowheads = listOf("none", "arrow", "triangle", "diamond", "circle")

/** A selected drawing's options (sol's selection toolbar and window options). */
@Composable private fun ElementOptions(e: CanvasElement, scene: CanvasScene, edits: CanvasEdits, state: CanvasDrawingState) {
    var menu by remember(e.id) { mutableStateOf(false) }
    val amb = Amb.colors
    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp).background(amb.card, CircleShape).testTag("canvas.element.options")) { Icon(Icons.Outlined.MoreVert, "Drawing options") }
    fun set(key: String, value: JsonPrimitive) { menu = false; edits.run { edits.editor.updateElement(edits.canvasId, e, data = mapOf(key to value)) } }
    fun next(list: List<String>, current: String) = list[(list.indexOf(current) + 1).mod(list.size)]
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        if (e.kind == CanvasElement.Kind.TEXT) {
            DropdownMenuItem(text = { Text("Edit text…") }, modifier = Modifier.testTag("canvas.element.edittext"), onClick = { menu = false; state.editingText = e })
            fontSizes.forEach { (name, size) -> DropdownMenuItem(text = { Text(if (e.fontSize.toInt() == size) "✓ $name text" else "$name text") }, onClick = { set("fontSize", JsonPrimitive(size)) }) }
        }
        if (e.kind == CanvasElement.Kind.ARROW || e.kind == CanvasElement.Kind.LINE) {
            DropdownMenuItem(text = { Text("Line: ${e.lineStyle}") }, modifier = Modifier.testTag("canvas.element.linestyle"), onClick = { set("lineStyle", JsonPrimitive(next(lineStyles, e.lineStyle))) })
            DropdownMenuItem(text = { Text("Start: ${e.startArrowhead}") }, onClick = { set("startArrowhead", JsonPrimitive(next(arrowheads, e.startArrowhead))) })
            DropdownMenuItem(text = { Text("End: ${e.endArrowhead}") }, onClick = { set("endArrowhead", JsonPrimitive(next(arrowheads, e.endArrowhead))) })
        }
        DropdownMenuItem(text = { Text("Duplicate") }, modifier = Modifier.testTag("canvas.element.duplicate"), onClick = { menu = false; edits.run { edits.editor.duplicateElement(edits.canvasId, e, scene.highestZ) } })
        DropdownMenuItem(text = { Text("Bring forward") }, onClick = { menu = false; edits.run { edits.editor.reorderElement(edits.canvasId, e, forward = true) } })
        DropdownMenuItem(text = { Text("Send backward") }, onClick = { menu = false; edits.run { edits.editor.reorderElement(edits.canvasId, e, forward = false) } })
        DropdownMenuItem(text = { Text(if (e.isGroup) "Ungroup" else "Delete") }, modifier = Modifier.testTag("canvas.element.delete"),
            onClick = { menu = false; state.selected = null; edits.run { edits.editor.deleteElement(edits.canvasId, e) } })
    }
}

/** Asks for the text of a new text element, or edits an existing one. */
@Composable internal fun CanvasTextDialog(state: CanvasDrawingState, edits: CanvasEdits) {
    val existing = state.editingText
    val placing = state.textAt
    if (existing == null && placing == null) return
    var text by remember(existing?.id, placing) { mutableStateOf(existing?.text ?: "") }
    fun close() { state.editingText = null; state.textAt = null }
    AlertDialog(onDismissRequest = ::close, modifier = Modifier.testTag("canvas.text.dialog"), title = { Text(if (existing != null) "Edit text" else "Add text") },
        text = { OutlinedTextField(text, { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("canvas.text.field")) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), modifier = Modifier.testTag("canvas.text.save"), onClick = {
            if (existing != null) edits.run { edits.editor.updateElement(edits.canvasId, existing, data = mapOf("text" to JsonPrimitive(text.trim()))) }
            else if (placing != null) edits.run { edits.editor.createElement(edits.canvasId, NewCanvasElement.Text(text), placing) }
            state.tool = null; close()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } })
}
