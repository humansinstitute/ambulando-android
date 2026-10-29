package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.canvas.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.rich.*
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.docs.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.refreshDoc
import studio.otherstuff.ambulando.core.surfaces.refreshCanvas
import studio.otherstuff.ambulando.core.surfaces.*
import kotlin.math.roundToInt

/** Viewport-culls native naplets and draws canvas elements without a full-scene bitmap. */
@Composable internal fun NativeCanvas(model: AppModel, canvasId: String, scene: CanvasScene) {
    val density = LocalDensity.current.density
    var zoom by remember(canvasId) { mutableFloatStateOf(model.canvasViewports[canvasId]?.scale?.toFloat() ?: 0.7f) }
    var x by remember(canvasId) { mutableFloatStateOf(model.canvasViewports[canvasId]?.x?.toFloat() ?: 20f) }
    var y by remember(canvasId) { mutableFloatStateOf(model.canvasViewports[canvasId]?.y?.toFloat() ?: 20f) }
    var initialized by remember(canvasId) { mutableStateOf(model.canvasViewports.containsKey(canvasId)) }
    val w = model.workspace ?: return
    val removed by remember(canvasId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.isAccessRemoved(w.id, FlaggedTable.CANVASES, canvasId) } }.collectAsState(false)
    var fitting by remember(canvasId) { mutableStateOf(false) }
    var editing by remember(canvasId) { mutableStateOf(false) }
    val canvas by remember(canvasId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.canvas(w.id, canvasId) } }.collectAsState(null)
    val edits = rememberCanvasEdits(model, canvasId)
    val arranging = editing && !removed && !w.accessRemoved && canvas?.canManage == true && edits != null
    // The frame an item is being dragged or resized to, drawn as an outline until it is released and saved.
    var ghost by remember(canvasId) { mutableStateOf<CanvasLayout?>(null) }
    val drawing = remember(canvasId) { CanvasDrawingState() }
    LaunchedEffect(arranging) { if (!arranging) drawing.reset() }
    Column(Modifier.fillMaxSize()) {
        if (removed) Text("No longer available to you · cached canvas", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("canvas.viewport").pointerInput(canvasId) {
            detectTransformGestures { centroid, pan, factor, _ ->
                val next = (zoom * factor).coerceIn(0.2f, 3f)
                val ratio = next / zoom
                x = centroid.x - (centroid.x - x) * ratio + pan.x
                y = centroid.y - (centroid.y - y) * ratio + pan.y
                zoom = next
                model.canvasViewports[canvasId] = CanvasViewport(x.toDouble(), y.toDouble(), zoom.toDouble())
            }
        }) {
            val width = maxWidth.value * density; val height = maxHeight.value * density
            LaunchedEffect(fitting, scene.bounds) {
                val bounds = scene.bounds
                if (bounds != null && (fitting || !initialized)) {
                    zoom = minOf(width / ((bounds.maxX - bounds.minX).toFloat() * density + 40), height / ((bounds.maxY - bounds.minY).toFloat() * density + 40)).coerceIn(0.2f, 1f)
                    x = 20 - bounds.minX.toFloat() * zoom * density; y = 20 - bounds.minY.toFloat() * zoom * density
                }
                initialized = true
                fitting = false
            }
            CanvasElementsLayer(scene.elements, ViewTransform(zoom * density, x, y), Amb.colors.foreground, Amb.colors.mutedForeground)
            scene.naplets.sortedBy { it.layout.z }.forEach { naplet ->
                val left = naplet.layout.x.toFloat() * zoom * density + x
                val top = naplet.layout.y.toFloat() * zoom * density + y
                if (left < width && top < height && left + naplet.layout.width * zoom * density > 0 && top + naplet.layout.height * zoom * density > 0) {
                    Card(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .width(naplet.layout.width.dp).height(naplet.layout.height.dp)
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, transformOrigin = TransformOrigin(0f, 0f)).testTag("canvas.item.${naplet.id}")) {
                        if (arranging && edits != null) NapletEditChrome(model, canvasId, naplet, scene, edits, onGhost = { ghost = it }) {
                            CanvasNapletContent(model, canvasId, naplet, !removed && !w.accessRemoved)
                        } else {
                            Text(naplet.title, style = MaterialTheme.typography.titleSmall)
                            CanvasNapletContent(model, canvasId, naplet, !removed && !w.accessRemoved)
                        }
                    }
                }
            }
            if (arranging && edits != null) CanvasDrawingLayer(scene, edits, drawing, CanvasView(zoom * density, x, y), density) { ghost = it }
            ghost?.let { g ->
                Box(Modifier.offset { IntOffset((g.x.toFloat() * zoom * density + x).roundToInt(), (g.y.toFloat() * zoom * density + y).roundToInt()) }
                    .size((g.width.toFloat() * zoom).dp, (g.height.toFloat() * zoom).dp)
                    .border(2.dp, Amb.colors.primary, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).testTag("canvas.ghost"))
            }
        }
        if (editing && !removed && edits != null) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp).testTag("canvas.management")) {
            if (arranging) CanvasDrawingTools(drawing, scene, edits)
            CanvasManagement(model, canvasId, scene, edits)
        }
        if (edits != null) CanvasTextDialog(drawing, edits)
        CanvasBottomBar(canvas?.title ?: "Canvas", editing, onExit = LocalRouteExit.current, onFit = { fitting = true }, onToggleEdit = { editing = !editing })
    }
}

/**
 * A full-screen canvas's controls, at the bottom within thumb reach where the global section bar would be (the web's
 * mobile canvas): exit, the canvas title, fit and edit. Kept above the navigation bar and the keyboard.
 */
@Composable internal fun CanvasBottomBar(title: String, editing: Boolean, onExit: (() -> Unit)?, onFit: (() -> Unit)?, onToggleEdit: (() -> Unit)?) {
    val amb = Amb.colors
    Column(Modifier.fillMaxWidth().background(amb.card).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).testTag("canvas.bar")) {
        HorizontalDivider(thickness = 0.5.dp, color = amb.border)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onExit != null) IconButton(onClick = onExit, modifier = Modifier.testTag("canvas.exit")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close canvas") }
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (onFit != null) IconButton(onClick = onFit, modifier = Modifier.testTag("canvas.fit")) { Icon(Icons.Outlined.FitScreen, "Fit canvas to screen") }
            if (onToggleEdit != null) IconButton(onClick = onToggleEdit, modifier = Modifier.testTag("canvas.edit")) {
                Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, if (editing) "Done editing" else "Edit canvas", tint = if (editing) amb.primary else amb.foreground)
            }
        }
    }
}

@Composable private fun CanvasNapletContent(model: AppModel, canvasId: String, naplet: CanvasNaplet, allowRead: Boolean) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    if (!allowRead) {
        when (val cached = naplet.content) {
            is CanvasNaplet.Content.Document -> Text(cached.excerpt)
            is CanvasNaplet.Content.ChatThread -> cached.messages.forEach { Text(it.body) }
            is CanvasNaplet.Content.TaskList -> cached.tasks.forEach { Text(it.title) }
            is CanvasNaplet.Content.Excalidraw -> CanvasBoardPreview(model, cached.board)
            else -> Text("Cached source is read-only")
        }
        return
    }
    when (val content = naplet.content) {
        is CanvasNaplet.Content.Document -> {
            val record by remember(content.docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.doc(w.id, content.docId) } }.collectAsState(null)
            val body by remember(content.docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.docBody(w.id, content.docId) } }.collectAsState(null)
            LaunchedEffect(content.docId) { model.sync.refreshDoc(model.connection!!, w, content.docId) }
            TextButton(onClick = { services.open(WorkRoute.Doc(w.id, content.docId)) }) { Text("Open document") }
            Column(Modifier.verticalScroll(rememberScrollState())) { body?.let { RichDocument(model, it, record?.accessRemoved == false) } ?: Text(content.excerpt) }
        }
        is CanvasNaplet.Content.ChatThread -> ConnectedChat(model, content.threadId)
        is CanvasNaplet.Content.TaskTree -> NativeTaskTree(model, content.channelId, TaskTreeView.fromLayout(naplet.savedLayout, naplet.id)) { view ->
            model.act { model.engine.perform("tree-view:${naplet.id}", studio.otherstuff.ambulando.core.sync.SyncPriority.USER) {
                StoreCanvasEditBackend(model.store, model.identity, model.transport, model.connection!!, w, {}, { model.sync.refreshCanvas(model.connection!!, w, it, force = true) }).editor().saveTaskTreeView(canvasId, naplet, view)
            } }
        }
        is CanvasNaplet.Content.TaskList -> Column(Modifier.verticalScroll(rememberScrollState())) { content.tasks.forEach { task -> TextButton(onClick = { services.open(WorkRoute.Task(w.id, task.id)) }) { Text(task.title) } } }
        is CanvasNaplet.Content.Excalidraw -> {
            TextButton(onClick = { model.editorBoard = content.board to naplet.title }) { Text(if (content.board.canWrite) "Edit board" else "Open board") }
            CanvasBoardPreview(model, content.board)
        }
        is CanvasNaplet.Content.Html -> {
            TextButton(onClick = { services.open(WorkRoute.HtmlArtefact(w.id, canvasId, naplet.id, naplet.title)) }) { Text("Open HTML artefact") }
            ConnectedHtml(model, WorkRoute.HtmlArtefact(w.id, canvasId, naplet.id, naplet.title))
        }
        is CanvasNaplet.Content.Canvas -> TextButton(onClick = { services.open(WorkRoute.Canvas(w.id, content.canvasId)) }) { Text("Open canvas") }
        is CanvasNaplet.Content.Unknown -> Text("Unsupported source: ${content.type}")
        CanvasNaplet.Content.Denied -> Text("No longer available to you")
    }
}

@Composable private fun CanvasBoardPreview(model: AppModel, reference: BoardReference) {
    val w = model.workspace ?: return
    val prepared by remember(w.id, reference) { model.store.liveQuery({ it.workspaceId == w.id }) {
        val row = model.store.reader.boardScene(w.id, reference.fileId)
        PreparedBoard.prepare(row?.bytes, row?.versionId, reference.versionId)
    } }.collectAsState(PreparedBoard())
    ExcalidrawCanvas(prepared, Modifier.fillMaxSize())
}
