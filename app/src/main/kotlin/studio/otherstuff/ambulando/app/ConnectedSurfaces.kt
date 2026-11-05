package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.canvas.*
import studio.otherstuff.ambulando.app.ui.rich.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.docs.DocumentRender

@Composable internal fun ConnectedCanvas(model: AppModel, canvasId: String) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    val scene by remember(w.id, canvasId) { model.store.liveQuery({ it.workspaceId == w.id }) {
        model.store.reader.canvasSnapshot(w.id, canvasId)?.let { CanvasScene.parseCancellable(it.bytes) }
    } }.collectAsState(null)
    LaunchedEffect(w.id, canvasId) { model.sync.refreshCanvas(model.connection!!, w, canvasId) }
    scene?.let { NativeCanvas(model, canvasId, it) } ?: Column(Modifier.fillMaxSize()) {
        // Full screen has no top bar: the bottom controls stay available while the canvas loads.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("Canvas not cached yet") }
        CanvasBottomBar("Canvas", false, studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit.current, onFit = null, onToggleEdit = null)
    }
}

@Composable private fun CanvasTasks(model: AppModel, channelId: String) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    val tasks by remember(w.id, channelId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.tasks(w.id).filter { it.channelId == channelId } } }.collectAsState(emptyList())
    tasks.forEach { task -> TextButton(onClick = { services.open(WorkRoute.Task(w.id, task.id)) }) { Text("${task.title} · ${task.state}") } }
}
@Composable private fun NativeBoard(model: AppModel, reference: BoardReference) {
    val w = model.workspace ?: return
    val prepared by remember(w.id, reference) { model.store.liveQuery({ it.workspaceId == w.id }) {
        val row = model.store.reader.boardScene(w.id, reference.fileId)
        PreparedBoard.prepare(row?.bytes, row?.versionId, reference.versionId)
    } }.collectAsState(PreparedBoard())
    ExcalidrawCanvas(prepared, Modifier.fillMaxWidth().height(360.dp))
}

@Composable internal fun ConnectedHtml(model: AppModel, route: WorkRoute.HtmlArtefact) {
    var html by remember(route) { mutableStateOf<String?>(null) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        try { html = model.sync.napletHTML(model.connection!!, model.workspace!!, route.canvasId, route.napletId).first }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    html?.let { studio.otherstuff.ambulando.app.web.SandboxedHtmlScreen(it, route.title) } ?: Text(error ?: "Loading HTML artefact…")
}
