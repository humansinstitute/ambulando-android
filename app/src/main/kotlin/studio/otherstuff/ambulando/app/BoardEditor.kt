package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.work.EmptyNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.app.web.EditorWebHost
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.sync.SyncPriority
import java.io.File
import java.util.UUID

@Composable internal fun BoardEditor(model: AppModel, board: BoardReference, title: String, done: () -> Unit) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(board.fileId) { mutableStateOf(BoardState()) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<EditorWebHost?>(null) }
    val session = remember { UUID.randomUUID().toString() }
    val transport = remember(board.fileId) {
        val base = TowerBoardTransport(model.identity, model.workspaceTransport, c, w, board.fileId, title)
        object : BoardController.Transport {
            override suspend fun versions() = model.engine.perform("board-versions:${board.fileId}", SyncPriority.USER) { base.versions() }
            override suspend fun read(versionId: String, sha256: String?) = model.engine.perform("board-read:${board.fileId}", SyncPriority.USER) { base.read(versionId, sha256) }
            override suspend fun publish(baseVersionId: String, bytes: ByteArray, summary: String) = model.engine.perform("board-publish:${board.fileId}", SyncPriority.USER) { base.publish(baseVersionId, bytes, summary) }
            override suspend fun canSign() = base.canSign()
            override suspend fun rename(name: String) = model.engine.perform("board-rename:${board.fileId}", SyncPriority.USER) { base.rename(name) }
        }
    }
    val latest = remember { arrayOfNulls<BoardState>(1) }
    fun stateNow(): BoardState? = latest[0]
    val controller = remember(board.fileId) { BoardController(board.fileId, title, c.signerNpub, transport, model.boardDrafts(), { value -> scope.launch { state = value } }) }
    fun close() { scope.launch { controller.suspendBoard(); controller.detach(session); done() } }
    BackHandler { close() }
    DisposableEffect(controller) {
        EditorWebHost.create(context, "index.html", setOf("board"), 32 * 1024 * 1024) { _, raw ->
            // The page's own Save button (sol `action`) saves only while the controller's state still allows it.
            scope.launch { if (isSaveAction(raw)) { if (stateNow()?.saveAction?.enabled == true) controller.save() } else controller.receive(session, raw) }
        }
            .onSuccess { host -> page = host; host.load() }.onFailure { error = it.message }
        onDispose { page?.destroy(); controller.close() }
    }
    LaunchedEffect(controller, page) {
        val host = page ?: return@LaunchedEffect
        controller.attach(session) { json -> scope.launch { host.call("window.ambulandoBoard.deliver", json) } }
        controller.sync(board, title)
    }
    SideEffect { latest[0] = state }
    var noting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // The page draws its own Save button from the controller's state (sol `action-ui`).
    LaunchedEffect(page, state.saveAction) {
        val host = page ?: return@LaunchedEffect
        val action = state.saveAction
        val json = kotlinx.serialization.json.buildJsonObject {
            put("type", "action-ui")
            if (action == null) put("action", kotlinx.serialization.json.JsonNull) else put("action", kotlinx.serialization.json.buildJsonObject {
                put("action", "save"); put("label", action.label); put("title", if (action.enabled) "Save a new board version" else "No unsaved changes"); put("disabled", !action.enabled)
            })
        }.toString()
        host.call("window.ambulandoBoard.deliver", json)
    }
    LaunchedEffect(controller, state.status) { if (state.status == BoardState.Status.READY && state.versions.isEmpty() && !state.versionsLoading) controller.loadVersions() }
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    Column(Modifier.fillMaxSize().background(amb.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (model.environment.isSample) Text("Sample data on this device. Not connected to a Tower.", fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.caption, color = amb.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = ::close, modifier = Modifier.testTag("board.back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(state.title.ifBlank { title }, Modifier.weight(1f).testTag("board.title"), fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BoardStatusPill(state)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("board.menu")) { Icon(Icons.Outlined.Settings, "Board options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.saveAction?.let { action ->
                        DropdownMenuItem(text = { Text("Save with a note…") }, leadingIcon = { Icon(Icons.Outlined.Save, null) }, enabled = action.enabled,
                            modifier = Modifier.testTag("board.saveNote"), onClick = { menu = false; noting = true })
                    }
                    Text("Versions", fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.caption, color = amb.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    if (state.versions.isEmpty()) DropdownMenuItem(text = { Text(if (state.versionsLoading) "Loading versions…" else "Show versions") }, onClick = { scope.launch { controller.loadVersions() } })
                    state.versions.take(30).forEach { version ->
                        val here = version.id == (state.viewingVersionId.ifBlank { state.baseVersionId })
                        DropdownMenuItem(text = { Text(if (version.summary.isBlank()) "v${version.number}" else "v${version.number} · ${version.summary}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(if (here) Icons.Outlined.Check else Icons.Outlined.History, null) },
                            modifier = Modifier.testTag("board.version"), onClick = { menu = false; scope.launch { controller.viewVersion(version) } })
                    }
                    if (!state.dirty && state.viewingVersionId.isBlank()) DropdownMenuItem(text = { Text("Reload latest") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        modifier = Modifier.testTag("board.reload"), onClick = { menu = false; scope.launch { controller.loadLatest() } })
                    if (state.canWrite) DropdownMenuItem(text = { Text("Rename board…") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        modifier = Modifier.testTag("board.rename"), onClick = { menu = false; renaming = true })
                }
            }
        }
        BoardNotices(state, scope, controller, onDiscard = { discarding = true })
        error?.let { Notice(NoticeTone.WARNING, it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        page?.failure?.let { Notice(NoticeTone.ERROR, it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            page?.let { host -> AndroidView({ host.webView }, Modifier.fillMaxSize()) }
            when (state.status) {
                BoardState.Status.LOADING -> Column(Modifier.fillMaxSize().background(amb.background), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp)); Text("Loading board…", Modifier.padding(top = 12.dp), color = amb.mutedForeground)
                }
                BoardState.Status.DENIED -> Box(Modifier.fillMaxSize().background(amb.background)) { EmptyNote("Board unavailable", Icons.Outlined.Lock, state.error.ifBlank { "Ask your workspace owner for access." }) }
                BoardState.Status.ERROR -> Box(Modifier.fillMaxSize().background(amb.background)) { EmptyNote("Board cannot be shown", Icons.Outlined.WarningAmber, state.error.ifBlank { "Try again later." }) }
                BoardState.Status.READY -> Unit
            }
        }
    }
    if (noting) NoteDialog("Save board", "What changed (optional)", "", "Save", { noting = false }) { text -> noting = false; scope.launch { controller.save(text.trim()) } }
    if (renaming) NoteDialog("Rename board", "Board name", state.title.ifBlank { title }, "Rename", { renaming = false }, required = true) { text -> renaming = false; scope.launch { controller.rename(text) } }
    if (discarding) AlertDialog(onDismissRequest = { discarding = false }, title = { Text("Discard your unsaved board changes and load the latest version?") },
        confirmButton = { TextButton(onClick = { discarding = false; scope.launch { controller.loadLatest() } }, modifier = Modifier.testTag("board.discard.confirm")) { Text("Discard and load latest", color = amb.destructive) } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text("Cancel") } })
}

/** The compact status in the title bar: muted, or warm while there are unsaved changes (iOS `BoardStatusPill`). */
@Composable private fun BoardStatusPill(state: BoardState) {
    val text = state.headerStatus
    if (text.isBlank()) return
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    val warm = state.dirty || state.save == BoardState.Save.STALE || state.newerAvailable
    Text(text, Modifier.padding(end = 4.dp).background(if (warm) amb.warningBackground else amb.muted, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 10.dp, vertical = 4.dp).testTag("board.status"),
        fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.caption2, fontWeight = FontWeight.SemiBold, color = if (warm) amb.warning else amb.mutedForeground, maxLines = 1)
}

/** What the person needs to decide: a version being viewed, a newer version saved by someone else, or a message (iOS `BoardNotices`). */
@Composable private fun BoardNotices(state: BoardState, scope: kotlinx.coroutines.CoroutineScope, controller: BoardController, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.viewingVersionId.isNotBlank()) Row(Modifier.fillMaxWidth().testTag("board.viewing"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Viewing version ${state.viewingVersionNumber} (read-only)", Modifier.weight(1f), fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.footnote)
            if (state.canWrite) TextButton(onClick = { scope.launch { controller.restoreViewedVersion() } }, modifier = Modifier.testTag("board.restore")) { Text("Restore") }
            TextButton(onClick = { scope.launch { controller.returnToLatest() } }, modifier = Modifier.testTag("board.latest")) { Text("Back to latest") }
        } else if (state.save == BoardState.Save.STALE || state.newerAvailable) Row(Modifier.fillMaxWidth().testTag("board.stale"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Version ${state.latestVersionNumber} was saved by someone else.", Modifier.weight(1f), fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.footnote)
            TextButton(onClick = { scope.launch { controller.mergeLatest() } }, modifier = Modifier.testTag("board.merge")) { Text("Merge my changes") }
            TextButton(onClick = onDiscard, modifier = Modifier.testTag("board.discard")) { Text("Discard mine", color = studio.otherstuff.ambulando.app.ui.theme.Amb.colors.destructive) }
        }
        val message = state.error.ifBlank { state.notice }
        if (message.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Notice(if (state.error.isNotBlank()) NoticeTone.WARNING else NoticeTone.INFO, message, Modifier.weight(1f).testTag("board.message"))
            IconButton(onClick = { scope.launch { controller.clearMessages() } }, modifier = Modifier.testTag("board.dismiss")) { Icon(Icons.Outlined.Close, "Dismiss") }
        }
    }
}

@Composable private fun NoteDialog(title: String, hint: String, initial: String, confirm: String, onCancel: () -> Unit, required: Boolean = false, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, singleLine = true, modifier = Modifier.testTag("board.note.field")) },
        confirmButton = { TextButton(enabled = !required || text.isNotBlank(), onClick = { onDone(text) }, modifier = Modifier.testTag("board.note.confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

private fun isSaveAction(raw: String): Boolean = runCatching {
    val o = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject ?: return false
    (o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "action" && (o["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "save"
}.getOrDefault(false)
