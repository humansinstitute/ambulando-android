package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@Composable internal fun BoardEditor(model: AppModel, board: BoardReference, title: String, provenance: @Composable () -> Unit = {}, exportFile: studio.otherstuff.ambulando.core.transport.FileRecord? = null, done: () -> Unit) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    studio.otherstuff.ambulando.app.ui.shell.OwnEditorNavigation()
    val boundaryId = model.activeBoundaryId
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(board.fileId, boundaryId) { mutableStateOf(BoardState()) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember(board.fileId, boundaryId) { mutableStateOf<EditorWebHost?>(null) }
    var pageReady by remember(board.fileId, boundaryId) { mutableStateOf(false) }
    val session = remember(board.fileId, model.activeBoundaryId) { UUID.randomUUID().toString() }
    val transport = remember(board.fileId, boundaryId) {
        val base = TowerBoardTransport(model.identity, model.workspaceTransport, c, w, board.fileId, title)
        object : BoardController.Transport {
            override suspend fun versions() = model.engine.perform("board-versions:${board.fileId}", SyncPriority.USER) { base.versions() }
            override suspend fun read(versionId: String, sha256: String?) = model.engine.perform("board-read:${board.fileId}", SyncPriority.USER) { base.read(versionId, sha256) }
            override suspend fun publish(baseVersionId: String, bytes: ByteArray, summary: String) = model.engine.perform("board-publish:${board.fileId}", SyncPriority.USER) { base.publish(baseVersionId, bytes, summary) }
            override suspend fun canSign() = base.canSign()
            override suspend fun rename(name: String) = model.engine.perform("board-rename:${board.fileId}", SyncPriority.USER) { base.rename(name) }
        }
    }
    val latest = remember(board.fileId, boundaryId) { arrayOfNulls<BoardState>(1) }
    fun stateNow(): BoardState? = latest[0]
    val controller = remember(board.fileId, boundaryId) { BoardController(board.fileId, title, c.signerNpub, transport, model.boardDrafts(), { value -> scope.launch { state = value } }) }
    val tab = LocalThreadArtefactTab.current
    val restoringRevision = remember(controller) { tab?.pinnedRevision ?: tab?.selectedRevision }
    var restorationComplete by remember(controller) { mutableStateOf(restoringRevision == null) }
    var restorationFailure by remember(controller) { mutableStateOf(false) }
    SideEffect {
        tab?.title = state.title.ifBlank { title }
        tab?.versionLabel = if (state.viewingVersionId.isNotBlank()) "Pinned v${state.viewingVersionNumber}" else if (state.baseVersionNumber > 0) "v${state.baseVersionNumber}" else "Loading revision…"
        if (state.status == BoardState.Status.READY && restorationComplete) {
            tab?.pinnedRevision = state.viewingVersionId.takeIf { it.isNotBlank() }
            tab?.selectedRevision = state.viewingVersionId.ifBlank { state.baseVersionId }
        }
        tab?.prepare = { check(restorationComplete && !restorationFailure) { "Pinned board revision unavailable. Retry opening its exact history." }; controller.prepareChatReference() }
        tab?.validate = { ref -> val now = controller.current(); !now.dirty && ref.revision == now.viewingVersionId.ifBlank { now.baseVersionId } }
        tab?.checkpoint = { controller.checkpointChat() }
        tab?.sourceDirty = { state.dirty }
        tab?.saveSource = { controller.save().also { if (it) tab.sourceSaved() } }
    }
    DisposableEffect(tab, controller) { onDispose { tab?.prepare = null; tab?.checkpoint = null; tab?.sourceDirty = null; tab?.saveSource = null } }
    val directory = peopleDirectory(model)
    val authorNames = remember(controller) { BoardAuthorNames() }
    val restoringViewport = remember(controller) { tab?.viewport }
    var viewportRestored by remember(controller) { mutableStateOf(false) }
    var authorRevision by remember(controller) { mutableIntStateOf(0) }
    val threadState = LocalThreadArtefacts.current
    fun close() { if (threadState != null) { threadState.shown = false; return }; scope.launch { controller.suspendBoard(); controller.detach(session); done() } }
    BackHandler(enabled = studio.otherstuff.ambulando.app.ui.shell.LocalEditorActive.current && LocalThreadPaneActive.current) { close() }
    DisposableEffect(controller) {
        var current = true
        EditorWebHost.create(context, "index.html", setOf("board"), 32 * 1024 * 1024) { _, raw ->
            // The page's own Save button (sol `action`) saves only while the controller's state still allows it.
            scope.launch { if (!current || model.activeBoundaryId != boundaryId) return@launch
                val incoming = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                if ((incoming?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content == "ready") pageReady = true
                if ((incoming?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content == "viewport") {
                    if (viewportRestored) tab?.viewport = incoming["viewport"]?.toString()
                    return@launch
                }
                if (authorNames.request(raw)) { authorRevision++; return@launch }
                if (isSaveAction(raw)) { if (stateNow()?.saveAction?.enabled == true) controller.save().also { if (it) tab?.sourceSaved() } } else controller.receive(session, raw) }
        }
            .onSuccess { host -> page = host; host.load() }.onFailure { error = it.message }
        onDispose { current = false; page?.destroy(); controller.close() }
    }
    LaunchedEffect(controller, page) {
        val host = page ?: return@LaunchedEffect
        // Restore the exact local view before a new page can edit or be asked to flush.
        // Existing mounted editors still use the controller's normal history flush barrier.
        controller.sync(board, title)
        if (restoringRevision != null && (tab?.pinnedRevision != null || restoringRevision != board.versionId)) {
            controller.loadVersions()
            val version = controller.current().versions.firstOrNull { it.id == restoringRevision }
            if (version != null) {
                controller.viewVersion(version, forcePinned = true)
                restorationComplete = controller.current().viewingVersionId == restoringRevision
                restorationFailure = !restorationComplete
                if (restorationFailure) error = "Pinned revision could not be restored. Reopen its exact history before Send."
            } else { restorationFailure = true; error = "Pinned revision unavailable. Reopen its history; no latest substitution was made." }
        } else restorationComplete = true
        controller.attach(session) { json -> scope.launch {
            if (page !== host || model.activeBoundaryId != boundaryId) return@launch
            authorNames.sent(json)
            host.call("window.ambulandoBoard.deliver", json)
        } }
        // A fast page may have reported ready while source/history was loading.
        if (pageReady) controller.receive(session, "{\"type\":\"ready\"}")
    }
    LaunchedEffect(page, state.status, restorationComplete) {
        if (state.status == BoardState.Status.READY && restorationComplete && !viewportRestored) {
            kotlinx.coroutines.delay(300)
            restoringViewport?.let { page?.call("window.ambulandoBoard.deliver", kotlinx.serialization.json.buildJsonObject { put("type", "viewport"); put("viewport", kotlinx.serialization.json.Json.parseToJsonElement(it)) }.toString()) }
            viewportRestored = true
        }
    }
    LaunchedEffect(page, directory, authorRevision) {
        val host = page ?: return@LaunchedEffect
        authorNames.message(directory)?.let { host.call("window.ambulandoBoard.deliver", it) }
    }
    SideEffect { latest[0] = state }
    var noting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var exporting by remember(board.fileId, boundaryId) { mutableStateOf(false) }
    if (exporting && exportFile != null) ArtefactFileActions(model, exportFile) { exporting = false }
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
    LaunchedEffect(controller, state.status, state.baseVersionId) { if (state.status == BoardState.Status.READY && state.versions.isEmpty() && !state.versionsLoading) controller.loadVersions() }
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    Column(Modifier.fillMaxSize().background(amb.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (model.environment.isSample) Text("Sample data on this device. Not connected to a Tower.", fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.caption, color = amb.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = ::close, modifier = Modifier.testTag("board.back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(state.title.ifBlank { title }, Modifier.weight(1f).testTag("board.title"), fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.dirty || state.save == BoardState.Save.STALE || state.newerAvailable) BoardStatusPill(state)
            ThreadArtefactHeaderEntry()
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("board.menu")) { Icon(Icons.Outlined.MoreHoriz, "Board options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ThreadArtefactMenuItems { menu = false }
                    state.saveAction?.let { action ->
                        DropdownMenuItem(text = { Text("Save with a note…") }, leadingIcon = { Icon(Icons.Outlined.Save, null) }, enabled = action.enabled,
                            modifier = Modifier.testTag("board.saveNote"), onClick = { menu = false; noting = true })
                    }
                    BoardStatusPill(state)
                    provenance()
                    if (exportFile != null) DropdownMenuItem(text = { Text("Download / share File") }, onClick = { menu = false; exporting = true }, modifier = Modifier.testTag("artefacts.export"))
                    Text("Versions", fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.caption, color = amb.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    if (state.versions.isEmpty()) DropdownMenuItem(text = { Text(if (state.versionsLoading) "Loading versions…" else "Show versions") }, onClick = { scope.launch { controller.loadVersions() } })
                    state.versions.take(30).forEach { version ->
                        val here = version.id == (state.viewingVersionId.ifBlank { state.baseVersionId })
                        DropdownMenuItem(text = { Text(if (version.summary.isBlank()) "v${version.number}" else "v${version.number} · ${version.summary}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(if (here) Icons.Outlined.Check else Icons.Outlined.History, null) },
                            modifier = Modifier.testTag("board.version"), onClick = { menu = false; scope.launch { controller.viewVersion(version, forcePinned = tab != null) } })
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
            page?.let { host -> AndroidView({ host.webView }, Modifier.fillMaxSize().testTag("board.canvas")) }
            if (!restorationComplete && restoringRevision != null) Box(Modifier.fillMaxSize().background(amb.background).clickable { }) { Text("Opening exact saved revision…", Modifier.padding(16.dp)) }
            if (restorationFailure) Box(Modifier.fillMaxSize().background(amb.background)) { EmptyNote("Pinned revision unavailable", Icons.Outlined.History, "Reconnect and reopen this board. Your draft remains preserved.") }
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
    if (noting) NoteDialog("Save board", "What changed (optional)", "", "Save", { noting = false }) { text -> noting = false; scope.launch { controller.save(text.trim()).also { if (it) tab?.sourceSaved() } } }
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
