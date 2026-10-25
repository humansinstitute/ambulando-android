package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.sync.SyncPriority

@Composable internal fun CanvasManagement(model: AppModel, canvasId: String, scene: CanvasScene) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val canvas by remember(canvasId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.canvas(w.id, canvasId) } }.collectAsState(null)
    val sources by remember(canvasId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.canvasSourceChoices(w.id, canvasId) } }.collectAsState(emptyList())
    val editor = remember(canvasId) { StoreCanvasEditBackend(model.store, model.identity, model.transport, c, w, { model.sync.refreshCanvases(c, w, force = true) }, { model.sync.refreshCanvas(c, w, it, force = true) }).editor() }
    var rename by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CanvasNaplet?>(null) }
    var x by remember { mutableStateOf("") }; var y by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("") }; var height by remember { mutableStateOf("") }
    fun run(block: suspend () -> Unit) { model.act { model.engine.perform("canvas-edit:$canvasId", SyncPriority.USER) { block() } } }
    if (canvas?.canManage != true) { Text("You can view this canvas; managing it requires permission."); return }
    Row {
        TextButton(onClick = { title = canvas?.title ?: ""; rename = true }) { Text("Rename") }
        TextButton(onClick = { adding = true }) { Text("Add item") }
    }
    Row { scene.naplets.take(10).forEach { item -> TextButton(onClick = {
        selected = item; x = item.layout.x.toString(); y = item.layout.y.toString(); width = item.layout.width.toString(); height = item.layout.height.toString()
    }) { Text(item.title.take(20)) } } }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Rename canvas") }, text = { OutlinedTextField(title, { title = it }) }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { canvas?.let { run { editor.renameCanvas(it, title) } }; rename = false }) { Text("Save") } })
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add to canvas") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) { items(sources, key = { it.id }) { choice -> TextButton(onClick = {
            run { editor.addNaplet(canvasId, choice.type, choice.sourceId, choice.title, CanvasLayout(40.0, 40.0, 420.0, 360.0, (scene.naplets.maxOfOrNull { it.layout.z } ?: 0) + 1)) }; adding = false
        }) { Text("${choice.title} · ${choice.type.wire}") } } }
    }, confirmButton = { TextButton(onClick = { adding = false }) { Text("Done") } })
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title) }, text = { Column {
        OutlinedTextField(x, { x = it }, label = { Text("X") }); OutlinedTextField(y, { y = it }, label = { Text("Y") })
        OutlinedTextField(width, { width = it }, label = { Text("Width") }); OutlinedTextField(height, { height = it }, label = { Text("Height") })
    } }, confirmButton = { TextButton(onClick = {
        val next = CanvasLayout(x.toDoubleOrNull() ?: item.layout.x, y.toDoubleOrNull() ?: item.layout.y, width.toDoubleOrNull() ?: item.layout.width, height.toDoubleOrNull() ?: item.layout.height, item.layout.z)
        run { editor.updateLayout(canvasId, item, next) }; selected = null
    }) { Text("Save position") } }, dismissButton = { TextButton(onClick = { run { editor.removeNaplet(canvasId, item) }; selected = null }) { Text("Remove item") } }) }
}
