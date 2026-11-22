package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.app.web.FileNappletWebHost
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*

internal fun nappletManifest(html: String): JsonObject? = runCatching {
    // Only canonical inert JSON declaration tags are recognized. Anything ambiguous stays bridge-free.
    val tags = Regex("<script\\s+([^>]+)>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE).findAll(html)
        .filter { it.groupValues[1].contains("wingman-napplet-manifest") }.toList()
    require(tags.size == 1)
    val tag = tags.single()
    val attrs = Regex("([a-zA-Z-]+)\\s*=\\s*[\"']([^\"']*)[\"']").findAll(tag.groupValues[1]).toList()
    require(Regex("([a-zA-Z-]+)\\s*=\\s*[\"']([^\"']*)[\"']").replace(tag.groupValues[1], "").isBlank())
    require(attrs.size == 2 && attrs.map { it.groupValues[1].lowercase() }.toSet() == setOf("id", "type"))
    require(attrs.associate { it.groupValues[1].lowercase() to it.groupValues[2] } == mapOf("id" to "wingman-napplet-manifest", "type" to "application/json"))
    Json.parseToJsonElement(tag.groupValues[2]).jsonObject
}.getOrNull()

/** Mounted only for a direct current File open; never for source/history or a canvas thumbnail. */
@Composable internal fun FileNappletScreen(model: AppModel, file: FileRecord, html: String, manifest: JsonObject, dataId: String, codeVersion: String) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    OwnEditorNavigation()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val exit = LocalRouteExit.current
    var trusted by remember(model.activeBoundaryId, file.id, codeVersion, dataId) { mutableStateOf(false) }
    var page by remember { mutableStateOf<FileNappletWebHost?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var deferredResult by remember { mutableStateOf<JsonObject?>(null) }
    var lastWritable by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    val gate = remember { Mutex() }
    val transport = remember(model.activeBoundaryId, file.id, codeVersion, dataId) {
        FileNappletTransport(model.fileNappletAPI(c), w.id, w.appNpub, c.signerNpub, file.id, codeVersion, manifest, dataId)
    }
    fun stop() { operationJob?.cancel(); transport.clear(); deferredResult = null; page?.destroy(); page = null; failure = "This napplet is unavailable. Close and reopen it to check access." }
    fun close() { if (!busy) { if (dirty) discard = true else exit?.invoke() } }
    BackHandler(enabled = LocalEditorActive.current) { close() }
    suspend fun authorize() = withContext(Dispatchers.Main.immediate) {
        gate.withLock {
            checking = true
            try {
                val writable = transport.authorize()
                if (lastWritable == true && !writable) stop() else lastWritable = writable
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { stop() }
            finally { checking = false }
        }
    }
    DisposableEffect(lifecycle, transport) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foreground = true
                if (trusted) scope.launch {
                    authorize()
                    if (failure == null && foreground) {
                        page?.view?.onResume()
                        deferredResult?.let { page?.deliver(it) }
                        deferredResult = null
                    }
                }
            }
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false; checking = true
                // Keep memory drafts and let an already requested save resolve against its original base.
                page?.view?.onPause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); transport.clear(); deferredResult = null; page?.destroy(); page = null }
    }
    DisposableEffect(page) {
        val host = page
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && host != null && failure == null) scope.launch { authorize() }
        }
        host?.view?.viewTreeObserver?.addOnWindowFocusChangeListener(listener)
        onDispose { host?.view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener) }
    }
    LaunchedEffect(trusted, transport, model.activeBoundaryId) {
        if (trusted) model.store.changes.stream().collect { changes ->
            if (changes.any { (it.workspaceId == null || it.workspaceId == w.id) && it.table in setOf(
                studio.otherstuff.ambulando.core.store.StoreTable.FILES, studio.otherstuff.ambulando.core.store.StoreTable.CHANNELS,
                studio.otherstuff.ambulando.core.store.StoreTable.ACCESS, studio.otherstuff.ambulando.core.store.StoreTable.WORKSPACES,
                studio.otherstuff.ambulando.core.store.StoreTable.CONNECTIONS,
            ) }) authorize()
        }
    }
    LaunchedEffect(trusted, transport) {
        if (!trusted) return@LaunchedEffect
        authorize()
        if (failure != null) return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) { FileNappletWebHost.create(context, html, onMessage = { request ->
            if (foreground && !busy && failure == null) {
                val operation = request.string("operation")
                if (operation == "dirty") dirty = request.bool("dirty") == true
                else {
                    busy = true
                    operationJob = scope.launch {
                        gate.withLock {
                            val host = page
                            try {
                                val value = if (operation == "read") transport.read() else transport.save(request.string("baseVersionId")!!, request.getValue("value").jsonObject)
                                ensureActive()
                                if (host != null && page === host) {
                                    val result = buildJsonObject {
                                        put("type", "wingman-napplet-result"); put("version", 1); put("session", host.session); put("requestId", request.getValue("requestId"))
                                        put("status", "ready"); put("baseVersionId", value.version); put("value", value.value); put("canWrite", value.canWrite); put("privacy", "self-channel")
                                    }
                                    if (foreground && !checking) host.deliver(result) else deferredResult = result
                                    if (operation == "save") dirty = false
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: FileNappletTransport.Conflict) {
                                if (host != null && page === host) {
                                    val result = buildJsonObject {
                                        put("type", "wingman-napplet-result"); put("version", 1); put("session", host.session); put("requestId", request.getValue("requestId")); put("status", "error"); put("code", "conflict"); put("message", "A newer save exists. Reload explicitly before saving.")
                                    }
                                    if (foreground && !checking) host.deliver(result) else deferredResult = result
                                }
                            } catch (_: Exception) { stop() }
                            finally { busy = false }
                        }
                    }
                }
            }
        }, onStopped = { stop() }).fold(onSuccess = { page = it }, onFailure = { android.util.Log.w("FileNappletHost", "Isolated view creation failed", it); failure = "This WebView cannot safely host the napplet." }) }
        while (isActive && failure == null) { delay(30000); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) authorize() }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !busy, onClick = { close() }, modifier = Modifier.testTag("napplet.close")) { Text("Close") }
            RecordPin(model, PinRecord.ResourceType.FILE, file.id)
            Text(file.displayName, Modifier.weight(1f).padding(12.dp))
            Text(if (busy) "Saving…" else if (dirty) "Unsaved" else "NStack", Modifier.padding(12.dp))
        }
        if (!trusted) {
            Text("Run this napplet? Its reviewed code can read and replace the linked daily tracker File in your self-conversation. NStack administrators may also have access. Only run code you trust.", Modifier.padding(16.dp))
            TextButton(onClick = { trusted = true }, modifier = Modifier.testTag("napplet.trust")) { Text("Trust and open") }
        } else if (failure != null) Text(failure!!, Modifier.padding(16.dp))
        else {
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Keep DOM and memory drafts during periodic checks, but stop interaction until reauthorized.
            page?.let { host -> AndroidView(factory = { host.view }, update = { it.visibility = if (checking || !foreground) android.view.View.INVISIBLE else android.view.View.VISIBLE }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("napplet.host")) }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unsaved changes?") }, text = { Text("These changes have not been saved to NStack.") }, confirmButton = { TextButton(onClick = { page?.destroy(); page = null; exit?.invoke() }) { Text("Discard and close") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

/** Pin every signed request to the connection identity, including an identity change during signing. */
internal fun AppModel.fileNappletAPI(connection: studio.otherstuff.ambulando.core.store.ConnectionRecord): TowerAPI {
    val bound = studio.otherstuff.ambulando.core.nostr.EventSigner { template ->
        identity.requireSigner(connection.signerNpub)
        identity.sign(template).also {
            check(studio.otherstuff.ambulando.core.nostr.NostrKeys.npub(it.pubkey) == connection.signerNpub)
        }
    }
    return TowerAPI(TowerClient(TowerOrigin(connection.origin), workspaceTransport, bound), transfers)
}
