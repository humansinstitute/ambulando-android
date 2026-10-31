package studio.otherstuff.ambulando.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.sync.SyncPriority
import java.util.UUID

@Composable internal fun NativeTaskTree(model: AppModel, channelId: String, saved: TaskTreeView? = null, saveView: ((TaskTreeView) -> Unit)? = null) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = LocalAppServices.current
    val writes = remember { TaskTreeWrites(model.store, model.identity, model.workspaceTransport) }
    val tasks by remember(w.id, channelId) { model.store.liveQuery({ it.workspaceId == w.id }) {
        model.store.reader.tasks(w.id).filter { it.channelId == channelId }.map { TaskTreeNode.from(it.id, it.title, it.state, it.channelId, it.createdAt, it.raw) }
    } }.collectAsState(emptyList())
    var root by remember(channelId) { mutableStateOf<String?>(saved?.rootTaskId) }
    var collapsed by remember(channelId) { mutableStateOf(saved?.collapsed ?: emptyList<String>()) }
    var upstream by remember(channelId) { mutableStateOf(saved?.upstream ?: emptyList<String>()) }
    var create by remember { mutableStateOf<Pair<TaskTreeNode, Boolean>?>(null) }
    var title by remember { mutableStateOf("") }
    val allowed = !w.accessRemoved && model.channels.firstOrNull { it.id == channelId }?.accessRemoved != true
    val selected = root ?: tasks.firstOrNull { it.parentId == null }?.id ?: tasks.firstOrNull()?.id
    LaunchedEffect(selected, upstream) {
        selected?.takeIf { allowed }?.let { model.engine.perform("task-tree:${w.id}:$it", SyncPriority.VISIBLE) { writes.load(c, w, it, upstream) } }
    }
    val graph = remember(tasks, selected, collapsed, upstream) { selected?.let { TaskTreeGraph.project(tasks, it, collapsed, upstream) } ?: TaskTreeGraph() }
    if (saveView != null && allowed) TextButton(onClick = { saveView(TaskTreeView(saved?.instanceId ?: channelId, selected, collapsed, upstream, saved?.scale ?: 1.0)) }) { Text("Save tree view") }
    Row(Modifier.horizontalScroll(rememberScrollState())) { tasks.filter { it.parentId == null }.forEach { task -> TextButton(onClick = { root = task.id }) { Text(task.title) } } }
    val columns = ((graph.positions.values.maxOfOrNull { it.x } ?: 0) + 1).coerceAtLeast(1)
    val rows = ((graph.positions.values.maxOfOrNull { it.y } ?: 0) + 1).coerceAtLeast(1)
    Box(Modifier.fillMaxWidth().height(420.dp).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
        Box(Modifier.width((columns * 260).dp).height((rows * 190).dp)) {
            Canvas(Modifier.fillMaxSize()) { graph.edges.forEach { edge ->
                val from = graph.positions[edge.from]; val to = graph.positions[edge.to]
                if (from != null && to != null) drawLine(Color.Gray, Offset((from.x * 260 + 110).dp.toPx(), (from.y * 190 + 70).dp.toPx()), Offset((to.x * 260 + 110).dp.toPx(), (to.y * 190 + 70).dp.toPx()), 2.dp.toPx())
            } }
            graph.nodes.forEach { node -> graph.positions[node.id]?.let { cell ->
                Card(Modifier.offset((cell.x * 260).dp, (cell.y * 190).dp).width(240.dp)) { Column {
                    TextButton(onClick = { services.open(WorkRoute.Task(w.id, node.id)) }) { Text("${node.title} · ${node.state}") }
                    Row {
                        TextButton(onClick = { collapsed = if (node.id in collapsed) collapsed - node.id else collapsed + node.id }) { Text(if (node.id in collapsed) "Expand" else "Collapse") }
                        TextButton(enabled = allowed, onClick = { create = node to true }) { Text("Child") }
                        TextButton(enabled = allowed, onClick = { create = node to false }) { Text("Next") }
                    }
                    node.predecessors.filter { p -> p !in graph.positions }.forEach { predecessor -> TextButton(onClick = { upstream = upstream + predecessor }) { Text("Show predecessor") } }
                } }
            } }
        }
    }
    if (graph.bounded) Text("Tree limited to 100 tasks")
    create?.let { (node, child) -> AlertDialog(onDismissRequest = { create = null }, title = { Text(if (child) "New child task" else "New successor") }, text = { OutlinedTextField(title, { title = it }) }, confirmButton = {
        TextButton(enabled = title.isNotBlank(), onClick = { val text = title; model.act {
            model.engine.perform("tree-create:${node.id}", SyncPriority.USER) { writes.create(c, w, channelId, model.channels.firstOrNull { it.id == channelId }?.scopeId, text, if (child) node.id else null, if (child) null else node, UUID.randomUUID().toString()) }
        }; create = null; title = "" }) { Text("Create") }
    }, dismissButton = { TextButton(onClick = { create = null }) { Text("Cancel") } }) }
}
