package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
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
        val base = TowerBoardTransport(model.identity, model.transport, c, w, board.fileId, title)
        object : BoardController.Transport {
            override suspend fun versions() = model.engine.perform("board-versions:${board.fileId}", SyncPriority.USER) { base.versions() }
            override suspend fun read(versionId: String, sha256: String?) = model.engine.perform("board-read:${board.fileId}", SyncPriority.USER) { base.read(versionId, sha256) }
            override suspend fun publish(baseVersionId: String, bytes: ByteArray, summary: String) = model.engine.perform("board-publish:${board.fileId}", SyncPriority.USER) { base.publish(baseVersionId, bytes, summary) }
            override suspend fun canSign() = base.canSign()
            override suspend fun rename(name: String) = model.engine.perform("board-rename:${board.fileId}", SyncPriority.USER) { base.rename(name) }
        }
    }
    val controller = remember(board.fileId) { BoardController(board.fileId, title, c.signerNpub, transport, BoardDraftStore(File(model.environment.baseDir, "board-drafts")), { value -> scope.launch { state = value } }) }
    fun close() { scope.launch { controller.suspendBoard(); controller.detach(session); done() } }
    BackHandler { close() }
    DisposableEffect(controller) {
        EditorWebHost.create(context, "index.html", setOf("board"), 32 * 1024 * 1024) { _, raw -> scope.launch { controller.receive(session, raw) } }
            .onSuccess { host -> page = host; host.load() }.onFailure { error = it.message }
        onDispose { page?.destroy(); controller.close() }
    }
    LaunchedEffect(controller, page) {
        val host = page ?: return@LaunchedEffect
        controller.attach(session) { json -> scope.launch { host.call("window.ambulandoBoard.deliver", json) } }
        controller.sync(board, title)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (model.environment.isSample) Text("Sample data", style = MaterialTheme.typography.labelSmall)
        Row {
            TextButton(onClick = ::close) { Text("Back") }
            TextButton(enabled = state.canWrite && state.dirty, onClick = { scope.launch { controller.save() } }) { Text("Save") }
            if (state.newerAvailable) TextButton(onClick = { scope.launch { controller.mergeLatest() } }) { Text("Merge latest") }
            TextButton(onClick = { scope.launch { controller.loadVersions() } }) { Text("Versions") }
        }
        Text(state.title.ifBlank { title })
        if (state.error.isNotBlank()) Text(state.error)
        if (state.notice.isNotBlank()) Text(state.notice)
        error?.let { Text(it) }
        state.versions.forEach { version -> TextButton(onClick = { scope.launch { controller.viewVersion(version) } }) { Text("Version ${version.number}") } }
        if (state.viewingVersionId.isNotBlank()) Row {
            TextButton(onClick = { scope.launch { controller.returnToLatest() } }) { Text("Return to latest") }
            TextButton(enabled = state.canWrite, onClick = { scope.launch { controller.restoreViewedVersion() } }) { Text("Restore this version") }
        }
        page?.failure?.let { Text(it) }
        page?.let { host -> AndroidView({ host.webView }, Modifier.weight(1f).fillMaxWidth()) }
    }
}
