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

@Composable internal fun ConnectedToolbox(model: AppModel) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = LocalAppServices.current
    val rows by remember(w.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.personalWapps(c.id, w.id) } }.collectAsState(emptyList())
    LaunchedEffect(w.id) { model.sync.refreshToolbox(c, w) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        if (rows.isEmpty()) item { Text("No personal apps configured") }
        item { Text("WApps open in the browser and ask before using Nostr. Add, edit and place WApps in the web app.", style = MaterialTheme.typography.bodySmall) }
        items(rows, key = { it.id }) { app -> TextButton(enabled = app.safeLaunchURL != null, onClick = { app.safeLaunchURL?.let { services.openUrl(it.toString()) } }) { Column { Text(app.title); app.description?.let { Text(it) } } } }
    }
}

@Composable internal fun ConnectedAgents(model: AppModel) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    val ids = model.channels.filter { model.context.scopeId == null || it.scopeId == model.context.scopeId }.filter { model.context.channelId == null || it.id == model.context.channelId }.map { it.id }
    val rows by remember(w.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.workspaceAgents(w.id) } }.collectAsState(emptyList())
    val activity by remember(w.id, model.context) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.agentActivities(w.id, ids) } }.collectAsState(emptyList())
    LaunchedEffect(w.id, model.context) { model.sync.refreshAgents(model.connection!!, w, ids) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Text("Workspace agents", style = MaterialTheme.typography.titleMedium) }
        items(rows, key = { it.id }) { row -> Text(row.displayName); Text(row.capabilities.joinToString(", "), style = MaterialTheme.typography.bodySmall) }
        item { Text("Activity", style = MaterialTheme.typography.titleMedium) }
        items(activity, key = { it.id }) { item -> TextButton(enabled = item.threadId != null, onClick = { item.threadId?.let { services.open(WorkRoute.Thread(w.id, item.channelId, it)) } }) { Column { Text(item.state); Text(item.label ?: item.summary ?: "Agent activity") } } }
        item { Text("Pipeline runs and Autopilot sessions require a direct Autopilot connection and are not available here.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable internal fun ConnectedContext(model: AppModel) {
    val w = model.workspace ?: return
    val s = model.context.scopeId
    if (s == null) { Text("Choose a scope to view its Context Tree", Modifier.padding(16.dp)); return }
    val rows by remember(w.id, s) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.contextTree(w.id, s) } }.collectAsState(emptyList())
    LaunchedEffect(w.id, s) { model.sync.refreshContextTree(model.connection!!, w, s) }
    LazyColumn { items(rows, key = { it.id }) { ContextBranch(model, it, 0) } }
}
@Composable private fun ContextBranch(model: AppModel, node: ContextNode, depth: Int) {
    val services = LocalAppServices.current
    var expanded by remember(node.id) { mutableStateOf(false) }
    val references by remember(node.id) { model.store.liveQuery({ it.workspaceId == node.component.workspaceId }) { model.store.reader.contextReferences(node.component.workspaceId, node.id) } }.collectAsState(emptyList())
    TextButton(onClick = { expanded = !expanded; if (expanded && !node.accessRemoved) model.act { model.sync.refreshContextReferences(model.connection!!, model.workspace!!, node.component.scopeId, node.id) } }, modifier = Modifier.padding(start = (16 + depth * 16).dp, top = 8.dp)) { Text(node.component.title) }
    if (expanded) references.forEach { ref ->
        TextButton(enabled = ref.resolution != ContextReferenceRecord.Resolution.UNAVAILABLE, onClick = {
            val w = node.component.workspaceId
            when (ref.targetType) {
                ContextReferenceRecord.TargetType.TASK -> ref.targetRecordId?.let { services.open(WorkRoute.Task(w, it)) }
                ContextReferenceRecord.TargetType.DOC -> ref.targetRecordId?.let { services.open(WorkRoute.Doc(w, it)) }
                ContextReferenceRecord.TargetType.FILE -> ref.targetRecordId?.let { services.open(WorkRoute.File(w, it)) }
                ContextReferenceRecord.TargetType.ARTIFACT -> ref.artifact?.takeIf { it.isHTTPS }?.let { services.openUrl(it.latestURL) }
                null -> Unit
            }
        }) { Text(ref.resolvedTitle ?: "Unavailable reference") }
    }
    if (node.accessRemoved) Text("No longer available to you")
    node.children.forEach { ContextBranch(model, it, depth + 1) }
}

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
