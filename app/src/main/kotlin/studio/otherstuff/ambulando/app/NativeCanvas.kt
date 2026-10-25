package studio.otherstuff.ambulando.app

import androidx.compose.foundation.gestures.detectTransformGestures
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
    Column {
        Row {
            TextButton(onClick = { fitting = true }) { Text("Fit") }
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done editing" else "Edit canvas") }
        }
        if (removed) Text("No longer available to you · cached canvas")
        if (editing && !removed) CanvasManagement(model, canvasId, scene)
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(canvasId) {
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
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, transformOrigin = TransformOrigin(0f, 0f))) {
                        Text(naplet.title, style = MaterialTheme.typography.titleSmall)
                        CanvasNapletContent(model, canvasId, naplet, !removed && !w.accessRemoved)
                    }
                }
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
