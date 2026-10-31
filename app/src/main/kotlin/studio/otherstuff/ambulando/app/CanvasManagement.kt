package studio.otherstuff.ambulando.app

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.sync.SyncPriority

/** Canvas writes for one canvas, serialized per canvas; failures reach the app's notice. */
@Stable internal class CanvasEdits(private val model: AppModel, val canvasId: String, val editor: CanvasEditor, val backend: StoreCanvasEditBackend) {
    fun run(block: suspend () -> Unit) { model.act { model.engine.perform("canvas-edit:$canvasId", SyncPriority.USER) { block() } } }
}

@Composable internal fun rememberCanvasEdits(model: AppModel, canvasId: String): CanvasEdits? {
    val w = model.workspace ?: return null; val c = model.connection ?: return null
    return remember(canvasId, w.id, c.id) {
        val backend = StoreCanvasEditBackend(model.store, model.identity, model.workspaceTransport, c, w, { model.sync.refreshCanvases(c, w, force = true) }, { model.sync.refreshCanvas(c, w, it, force = true) })
        CanvasEdits(model, canvasId, backend.editor(), backend)
    }
}

/** The canvas's edit actions while editing: add an item (or a new board), rename the canvas. */
@Composable internal fun CanvasManagement(model: AppModel, canvasId: String, scene: CanvasScene, edits: CanvasEdits) {
    val w = model.workspace ?: return
    val canvas by remember(canvasId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.canvas(w.id, canvasId) } }.collectAsState(null)
    // Read while editing (in this window, not the dialog's), so the add list is ready when it opens.
    val sources by remember(canvasId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.canvasSourceChoices(w.id, canvasId) } }.collectAsState(null)
    var rename by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    if (canvas?.canManage != true) { Text("You can view this canvas; managing it requires permission.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall); return }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { adding = true }, modifier = Modifier.testTag("canvas.add")) { Text("Add item") }
        OutlinedButton(onClick = { title = canvas?.title ?: ""; rename = true }, modifier = Modifier.testTag("canvas.rename")) { Text("Rename") }
    }
    Text("Drag an item's handle to move it and its corner to resize it; ⋮ has more.", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground)
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Rename canvas") }, text = { OutlinedTextField(title, { title = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { canvas?.let { edits.run { edits.editor.renameCanvas(it, title) } }; rename = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    if (adding) AddToCanvas(model, canvasId, scene, edits, sources) { adding = false }
}

/** What the add dialog lists: one source type, or every type. */
internal enum class AddKind(val label: String, val type: CanvasEditor.SourceType?) {
    ALL("All", null), DOCUMENT("Documents", CanvasEditor.SourceType.DOCUMENT), BOARD("Boards", CanvasEditor.SourceType.EXCALIDRAW),
    CHAT("Chats", CanvasEditor.SourceType.CHAT_THREAD), TASKS("Task lists", CanvasEditor.SourceType.TASK_LIST),
    TREE("Task trees", CanvasEditor.SourceType.TASK_TREE), HTML("HTML", CanvasEditor.SourceType.HTML), CANVAS("Canvases", CanvasEditor.SourceType.CANVAS),
}

/** Filters add-to-canvas choices by type and by a name substring (case-insensitive), as the web's source search. */
internal fun filterChoices(choices: List<CanvasSourceChoice>, kind: AddKind, query: String): List<CanvasSourceChoice> {
    val needle = query.trim().lowercase()
    return choices.filter { (kind.type == null || it.type == kind.type) && (needle.isEmpty() || it.title.lowercase().contains(needle)) }
}

internal fun CanvasEditor.SourceType.label(): String = when (this) {
    CanvasEditor.SourceType.DOCUMENT -> "Document"
    CanvasEditor.SourceType.TASK_LIST -> "Task list"
    CanvasEditor.SourceType.TASK_TREE -> "Task tree"
    CanvasEditor.SourceType.CHAT_THREAD -> "Chat"
    CanvasEditor.SourceType.HTML -> "HTML artefact"
    CanvasEditor.SourceType.EXCALIDRAW -> "Board"
    CanvasEditor.SourceType.CANVAS -> "Canvas link"
}

/**
 * The web's Add naplet dialog: choose a type, search the stored sources by name, optionally give a display title; for
 * boards, create a new board in a channel or link an existing one. New items are placed where they are visible.
 */
@Composable private fun AddToCanvas(model: AppModel, canvasId: String, scene: CanvasScene, edits: CanvasEdits, sources: List<CanvasSourceChoice>?, onDone: () -> Unit) {
    var kind by remember { mutableStateOf(AddKind.ALL) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<CanvasSourceChoice?>(null) }
    var title by remember { mutableStateOf("") }
    var createBoard by remember { mutableStateOf(false) }
    var boardChannel by remember { mutableStateOf(model.context.channelId ?: model.channels.firstOrNull { !it.accessRemoved }?.id) }
    val layout = nextLayout(scene)
    AlertDialog(onDismissRequest = onDone, modifier = Modifier.testTag("canvas.add.dialog"), title = { Text("Add to canvas") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AddKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k; chosen = null; if (k != AddKind.BOARD) createBoard = false },
                    label = { Text(k.label) }, modifier = Modifier.testTag("canvas.add.kind.${k.name.lowercase()}")) }
            }
            if (kind == AddKind.BOARD) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = createBoard, onClick = { createBoard = true; chosen = null }, label = { Text("Create board") }, modifier = Modifier.testTag("canvas.add.board.create"))
                FilterChip(selected = !createBoard, onClick = { createBoard = false }, label = { Text("Link existing") })
            }
            if (createBoard) {
                OutlinedTextField(title, { title = it }, label = { Text("Board title") }, placeholder = { Text("Untitled board") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("canvas.add.board.title"))
                Text("Channel", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.heightIn(max = 180.dp)) { items(model.channels.filter { !it.accessRemoved }, key = { it.id }) { channel ->
                    Row(Modifier.fillMaxWidth().clickable { boardChannel = channel.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = boardChannel == channel.id, onClick = { boardChannel = channel.id }); Text("#${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } }
                Text("The board is a private File in this channel. Only people who can read the channel can open it.", style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground)
            } else {
                OutlinedTextField(query, { query = it }, label = { Text("Find a source") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("canvas.add.search"))
                val list = sources?.let { filterChoices(it, kind, query) }
                when {
                    list == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    list.isEmpty() -> Column {
                        Text(if (query.isBlank()) "Nothing of this type is stored on this device yet." else "No matches.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("canvas.add.empty"))
                        TextButton(onClick = { model.refresh() }) { Text("Refresh from Tower") }
                    }
                    else -> LazyColumn(Modifier.heightIn(max = 260.dp).testTag("canvas.add.list")) { items(list, key = { it.id }) { choice ->
                        val selected = chosen?.id == choice.id
                        Row(Modifier.fillMaxWidth().clickable { chosen = choice }.then(if (selected) Modifier.border(1.dp, Amb.colors.primary, RoundedCornerShape(6.dp)) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp).testTag("canvas.add.choice.${choice.id}"), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(choice.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(choice.type.label(), style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground)
                            }
                        }
                    } }
                }
                if (chosen != null) OutlinedTextField(title, { title = it }, label = { Text("Display title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("The selected source is checked independently. People without access to it see no title or content.", style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground)
            }
        }
    }, confirmButton = {
        if (createBoard) TextButton(enabled = boardChannel != null, modifier = Modifier.testTag("canvas.add.confirm"), onClick = {
            val channel = boardChannel ?: return@TextButton
            val name = title.trim().ifEmpty { "Untitled board" }
            edits.run {
                val fileId = edits.backend.createBoard(channel, name)
                try { edits.editor.addNaplet(canvasId, CanvasEditor.SourceType.EXCALIDRAW, fileId, name, layout) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { throw IllegalStateException("The board “$name” was saved but could not be added; add it with Boards › Link existing. ${e.message ?: ""}".trim()) }
            }
            onDone()
        }) { Text("Create board") }
        else TextButton(enabled = chosen != null, modifier = Modifier.testTag("canvas.add.confirm"), onClick = {
            val choice = chosen ?: return@TextButton
            edits.run { edits.editor.addNaplet(canvasId, choice.type, choice.sourceId, title.trim().ifEmpty { null }, layout) }
            onDone()
        }) { Text("Add") }
    }, dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
}

/** Where a new item goes: to the right of everything on the canvas, on top. */
internal fun nextLayout(scene: CanvasScene): CanvasLayout {
    val right = scene.naplets.maxOfOrNull { it.layout.x + it.layout.width } ?: 0.0
    val top = scene.naplets.minOfOrNull { it.layout.y } ?: 40.0
    return CanvasLayout(if (scene.naplets.isEmpty()) 40.0 else right + 40, top, 420.0, 360.0, (scene.naplets.maxOfOrNull { it.layout.z } ?: 0) + 1)
}

/**
 * An item's edit chrome on the canvas: a handle to move it, a corner to resize it (both shown as a ghost outline until
 * released, then saved), and its options. [onGhost] reports the frame being dragged (null when released).
 */
@Composable internal fun NapletEditChrome(
    model: AppModel, canvasId: String, naplet: CanvasNaplet, scene: CanvasScene, edits: CanvasEdits, onGhost: (CanvasLayout?) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val services = LocalAppServices.current
    val w = model.workspace ?: return
    var menu by remember(naplet.id) { mutableStateOf(false) }
    var frame by remember(naplet.id) { mutableStateOf(false) }
    val amb = Amb.colors
    fun commit(to: CanvasLayout) { onGhost(null); if (to != naplet.layout) edits.run { edits.editor.updateLayout(canvasId, naplet, CanvasEditor.clamped(to)) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                var moved by remember(naplet.id) { mutableStateOf(naplet.layout) }
                Icon(Icons.Outlined.DragIndicator, "Move ${naplet.title}", tint = amb.mutedForeground, modifier = Modifier.size(32.dp).padding(4.dp)
                    .testTag("canvas.item.move.${naplet.id}")
                    .pointerInput(naplet.id, naplet.layout, naplet.isReady) {
                        if (naplet.isReady) detectDragGestures(onDragStart = { moved = naplet.layout }, onDragEnd = { commit(moved) }, onDragCancel = { onGhost(null) }) { change, drag ->
                            change.consume()
                            moved = moved.copy(x = moved.x + drag.x / density, y = moved.y + drag.y / density)
                            onGhost(moved)
                        }
                    })
                Text(naplet.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp).testTag("canvas.item.options.${naplet.id}")) { Icon(Icons.Outlined.MoreVert, "Item options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val open = openNaplet(model, canvasId, naplet, services)
                        if (open != null) DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; open() })
                        if (naplet.isReady && naplet.sourceId != null) DropdownMenuItem(text = { Text("Duplicate") }, modifier = Modifier.testTag("canvas.item.duplicate"),
                            onClick = { menu = false; edits.run { edits.editor.duplicateNaplet(canvasId, naplet, scene.highestZ) } })
                        if (naplet.isReady) {
                            DropdownMenuItem(text = { Text("Bring forward") }, onClick = { menu = false; edits.run { edits.editor.reorder(canvasId, naplet, forward = true) } })
                            DropdownMenuItem(text = { Text("Send backward") }, onClick = { menu = false; edits.run { edits.editor.reorder(canvasId, naplet, forward = false) } })
                            DropdownMenuItem(text = { Text("Position and size…") }, onClick = { menu = false; frame = true })
                        }
                        DropdownMenuItem(text = { Text("Remove from canvas") }, modifier = Modifier.testTag("canvas.item.remove"),
                            onClick = { menu = false; edits.run { edits.editor.removeNaplet(canvasId, naplet) } })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        if (naplet.isReady) {
            var sized by remember(naplet.id) { mutableStateOf(naplet.layout) }
            Icon(Icons.Outlined.OpenInFull, "Resize ${naplet.title}", tint = amb.primary, modifier = Modifier.align(Alignment.BottomEnd).size(36.dp).padding(6.dp)
                .testTag("canvas.item.resize.${naplet.id}")
                .pointerInput(naplet.id, naplet.layout) {
                    detectDragGestures(onDragStart = { sized = naplet.layout }, onDragEnd = { commit(sized) }, onDragCancel = { onGhost(null) }) { change, drag ->
                        change.consume()
                        sized = sized.copy(width = (sized.width + drag.x / density).coerceAtLeast(CanvasEditor.MIN_WIDTH),
                            height = (sized.height + drag.y / density).coerceAtLeast(CanvasEditor.MIN_HEIGHT))
                        onGhost(sized)
                    }
                })
        }
    }
    if (frame) NapletFrameDialog(naplet, onSave = { commit(it); frame = false }) { frame = false }
}

/** How an item opens full screen (null when it has no separate view). */
private fun openNaplet(model: AppModel, canvasId: String, naplet: CanvasNaplet, services: studio.otherstuff.ambulando.app.ui.nav.AppServices): (() -> Unit)? {
    val w = model.workspace ?: return null
    return when (val c = naplet.content) {
        is CanvasNaplet.Content.Document -> { { services.open(WorkRoute.Doc(w.id, c.docId)) } }
        is CanvasNaplet.Content.ChatThread -> { { services.open(WorkRoute.Thread(w.id, null, c.threadId)) } }
        is CanvasNaplet.Content.Canvas -> { { services.open(WorkRoute.Canvas(w.id, c.canvasId)) } }
        is CanvasNaplet.Content.Html -> { { services.open(WorkRoute.HtmlArtefact(w.id, canvasId, naplet.id, naplet.title)) } }
        is CanvasNaplet.Content.Excalidraw -> { { model.editorBoard = c.board to naplet.title } }
        else -> null
    }
}

/** Exact position and size, for when dragging is not precise enough. */
@Composable private fun NapletFrameDialog(naplet: CanvasNaplet, onSave: (CanvasLayout) -> Unit, onDismiss: () -> Unit) {
    var x by remember { mutableStateOf(naplet.layout.x.toLong().toString()) }; var y by remember { mutableStateOf(naplet.layout.y.toLong().toString()) }
    var width by remember { mutableStateOf(naplet.layout.width.toLong().toString()) }; var height by remember { mutableStateOf(naplet.layout.height.toLong().toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(naplet.title) }, text = { Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(x, { x = it }, label = { Text("X") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(y, { y = it }, label = { Text("Y") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(width, { width = it }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(height, { height = it }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f))
        }
    } }, confirmButton = { TextButton(onClick = {
        onSave(CanvasLayout(x.toDoubleOrNull() ?: naplet.layout.x, y.toDoubleOrNull() ?: naplet.layout.y, width.toDoubleOrNull() ?: naplet.layout.width,
            height.toDoubleOrNull() ?: naplet.layout.height, naplet.layout.z))
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
