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
@Composable internal fun FileNappletScreen(model: AppModel, file: FileRecord, html: String, manifest: JsonObject, dataId: String, codeVersion: String, onUpdated: () -> Unit) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    OwnEditorNavigation()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val exit = LocalRouteExit.current
    val approvals = remember(model.environment.baseDir) { NappletApprovalStore(java.io.File(model.environment.baseDir, "napplet-approvals")) }
    var approval by remember { mutableStateOf<NappletApproval?>(null) }
    var trusted by remember { mutableStateOf(false) }
    var consentReady by remember { mutableStateOf(false) }
    var remembering by remember { mutableStateOf(false) }
    var approvalNotice by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    var updateConfirmation by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf(false) }
    val replacement = remember { context.assets.open("napplets/daily-tracker-v1.html").bufferedReader().use { it.readText() } }
    val boundary = model.activeBoundaryId
    fun updater(writable: Boolean) = FileNappletUpdate(model.fileNappletAPI(c), w.id, w.appNpub,
        c.signerNpub, file.id, codeVersion, file.raw.getValue("metadata").jsonObject, manifest, dataId, writable) { model.activeBoundaryId == boundary && model.connection?.id == c.id && model.workspace?.id == w.id }
    var frameEpoch by remember { mutableIntStateOf(0) }
    var newerData by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<FileNappletWebHost?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    val visible by rememberUpdatedState(LocalEditorActive.current)
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var deferredResult by remember { mutableStateOf<JsonObject?>(null) }
    var lastWritable by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    val gate = remember { Mutex() }
    val local=remember(boundary,file.id,codeVersion) { LocalNappletContent(model.store,w,localContentOwner(c,w), { model.activeBoundaryId==boundary && model.workspace?.accessRemoved==false && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked }) }
    val authorityPart="$codeVersion:authority"
    val dataPart="$codeVersion:data:$dataId"
    val transport = remember(model.activeBoundaryId, file.id, codeVersion, dataId) {
        FileNappletTransport(model.fileNappletAPI(c), w.id, w.appNpub, c.signerNpub, file.id, codeVersion, manifest, dataId)
    }
    fun stop() { operationJob?.cancel(); transport.clear(); deferredResult = null; page?.destroy(); page = null; failure = "This napplet is unavailable. Close and reopen it to check access." }
    fun forget() {
        if (busy) return
        val receipt = approval ?: return
        val removed = approvals.forget(receipt)
        operationJob?.cancel(); transport.clear(); deferredResult = null; page?.destroy(); page = null
        trusted = false; dirty = false; failure = null; lastWritable = null; forgetting = false
        approvalNotice = if (removed) "Approval forgotten on this device. Your saved data is unchanged." else "Could not remove the saved approval. Close the app and retry before relying on revocation."
    }
    fun close() { if (!busy && !updating) { if (dirty) discard = true else exit?.invoke() } }
    BackHandler(enabled = LocalEditorActive.current) { close() }
    suspend fun authorize() = withContext(Dispatchers.Main.immediate) {
        gate.withLock {
            if (updating) return@withLock
            checking = true
            try {
                local.check(file.id); local.check(dataId)
                val writable = transport.authorize()
                local.put(file.id,authorityPart,buildJsonObject { put("writable",writable) })
                if (lastWritable != null && lastWritable != writable) stop() else lastWritable = writable
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(!transientNappletFailure(e) || !consentReady || !trusted) { if(model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked && model.activeBoundaryId==boundary) { local.forget(file.id,authorityPart); local.forgetCode(file.id,codeVersion) }; stop() } }
            finally { checking = false }
        }
    }
    fun receipt(writable: Boolean) = NappletApproval.verified(c.origin, c.id, c.towerNpub,
        context.packageName, w.id, w.appNpub, c.signerNpub, file.id, html,
        file.raw.getValue("metadata").jsonObject, manifest, dataId, writable)
    AppDiscoveryEffect(foreground && visible, transport) {
        if (consentReady) return@AppDiscoveryEffect
        local.get(file.id,authorityPart)?.let { cached ->
            local.check(dataId); lastWritable=cached.bool("writable")
            val saved=receipt(lastWritable==true)
            if(approvals.approved(saved)) { approval=saved; trusted=true; consentReady=true; checking=false }
        }
        if(consentReady) return@AppDiscoveryEffect
        authorize()
        if (failure == null) {
            val current = receipt(lastWritable == true)
            approval = current
            trusted = withContext(Dispatchers.IO) { approvals.approved(current) }
            consentReady = true
            if (FileNappletUpdate.recognized(html) && !FileNappletUpdate.recognized(replacement) && nappletManifest(replacement) == manifest) {
                updateAvailable = try { updater(lastWritable == true).authorize(); true }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
        }
    }
    DisposableEffect(lifecycle, transport) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foreground = true; page?.view?.onResume()
            }
            if (event == Lifecycle.Event.ON_PAUSE) {
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
            if (focused && foreground && visible && host != null && failure == null) scope.launch { authorize() }
        }
        host?.view?.viewTreeObserver?.addOnWindowFocusChangeListener(listener)
        onDispose { host?.view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener) }
    }
    AppDiscoveryEffect(trusted && foreground && visible, transport, model.activeBoundaryId) {
        model.store.changes.stream().collect { changes ->
            if (changes.any { (it.workspaceId == null || it.workspaceId == w.id) && it.table in setOf(
                studio.otherstuff.ambulando.core.store.StoreTable.FILES, studio.otherstuff.ambulando.core.store.StoreTable.CHANNELS,
                studio.otherstuff.ambulando.core.store.StoreTable.ACCESS, studio.otherstuff.ambulando.core.store.StoreTable.WORKSPACES,
                studio.otherstuff.ambulando.core.store.StoreTable.CONNECTIONS,
            ) }) authorize()
        }
    }
    AppDiscoveryEffect(trusted && foreground && visible, transport, frameEpoch) {
        if (page != null || failure != null) return@AppDiscoveryEffect
        withContext(Dispatchers.Main.immediate) { FileNappletWebHost.create(context, html, onMessage = { request ->
            if(request.string("operation")=="dirty" && foreground && visible && failure==null) dirty=request.bool("dirty")==true
            else if (foreground && visible && !busy && !updating && failure == null) {
                val operation = request.string("operation")
                if (operation == "dirty") dirty = request.bool("dirty") == true
                else {
                    busy = true
                    operationJob = scope.launch {
                        var cachedResult: JsonObject?=null
                        if(operation=="read") local.get(file.id,dataPart)?.let { cached ->
                            cachedResult=cached
                            local.check(dataId); yield()
                            page?.let { target -> if(foreground && visible) {
                                transport.restore(FileNappletTransport.Snapshot(cached.getValue("baseVersionId").jsonPrimitive.content,cached.getValue("value").jsonObject,cached.bool("canWrite")==true,""))
                                target.deliver(JsonObject(cached+mapOf("session" to JsonPrimitive(target.session),"requestId" to request.getValue("requestId"))))
                                busy=false
                            } }
                        }
                        gate.withLock {
                            val host = page
                            try {
                                // A read-only approval cannot gain save capability between periodic checks.
                                require(operation != "save" || lastWritable == true)
                                val value = if (operation == "read") transport.read() else transport.save(request.string("baseVersionId")!!, request.getValue("value").jsonObject)
                                require(value.canWrite == lastWritable)
                                ensureActive()
                                if (host != null && page === host) {
                                    val result = buildJsonObject {
                                        put("type", "wingman-napplet-result"); put("version", 1); put("session", host.session); put("requestId", request.getValue("requestId"))
                                        put("status", "ready"); put("baseVersionId", value.version); put("value", value.value); put("canWrite", value.canWrite); put("privacy", "self-channel")
                                    }
                                    local.put(file.id,dataPart,result)
                                    if(operation=="read" && cachedResult?.get("value")!=null && cachedResult?.get("value")!=result["value"]) newerData=true
                                    if(operation=="save" || cachedResult==null) {
                                        if (foreground && visible && !checking && (operation=="save" || !dirty)) host.deliver(result) else if(!dirty) deferredResult = result
                                    }
                                    if (operation == "save") dirty = false
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: FileNappletTransport.Conflict) {
                                if (host != null && page === host) {
                                    val result = buildJsonObject {
                                        put("type", "wingman-napplet-result"); put("version", 1); put("session", host.session); put("requestId", request.getValue("requestId")); put("status", "error"); put("code", "conflict"); put("message", "A newer save exists. Reload explicitly before saving.")
                                    }
                                    if (foreground && visible) host.deliver(result) else deferredResult = result
                                }
                            } catch (e: Exception) {
                                if(!transientNappletFailure(e)) { local.forget(file.id,authorityPart); stop() }
                                else if(operation!="read" || local.get(file.id,dataPart)==null) {
                                    host?.deliver(buildJsonObject {
                                        put("type","wingman-napplet-result"); put("version",1); put("session",host.session); put("requestId",request.getValue("requestId"))
                                        put("status","error"); put("code","unavailable"); put("message","Operation could not be confirmed. Your draft is kept.")
                                    })
                                }
                            }
                            finally { if(operationJob===currentCoroutineContext()[Job]) { busy = false; operationJob=null } }
                        }
                    }
                }
            }
        }, onStopped = { stop() }).fold(onSuccess = { page = it }, onFailure = { android.util.Log.w("FileNappletHost", "Isolated view creation failed", it); failure = "This WebView cannot safely host the napplet." }) }

    }
    AppDiscoveryEffect(trusted && foreground && visible && failure == null, transport) {
        authorize()
        if (failure == null && foreground && visible) {
            page?.view?.onResume()
            deferredResult?.let { page?.deliver(it) }
            deferredResult = null
        }
        while (isActive && failure == null) { delay(30000); authorize() }
    }
    LaunchedEffect(visible) { if(!visible) { checking = true; withContext(Dispatchers.Main.immediate) { page?.view?.onPause() } } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !busy && !updating, onClick = { close() }, modifier = Modifier.testTag("napplet.close")) { Text("Close") }
            RecordPin(model, PinRecord.ResourceType.FILE, file.id)
            Text(file.displayName, Modifier.weight(1f).padding(12.dp))
            Text(if (updating) "Updating…" else if (busy) "Saving…" else if (dirty) "Unsaved" else "NStack", Modifier.padding(12.dp))
        }
        if (trusted) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (updateAvailable) TextButton(enabled = !busy && !remembering && !updating && !checking && failure == null,
                onClick = { updateConfirmation = true }, modifier = Modifier.testTag("napplet.update")) { Text("Update app") }
            TextButton(enabled = !busy && !remembering && !updating, onClick = { if (dirty) forgetting = true else forget() }, modifier = Modifier.testTag("napplet.forget")) { Text("Forget approval") }
        }
        if(newerData) TextButton(enabled=!busy && !dirty,onClick={
            operationJob?.cancel();transport.clear();deferredResult=null;page?.destroy();page=null;newerData=false;frameEpoch++
        },modifier=Modifier.testTag("napplet.reloadSaved")) {Text("New saved data available · Reload")}
        approvalNotice?.let { Text(it, Modifier.padding(12.dp).testTag("napplet.approvalNotice")) }
        if (failure != null) Text(failure!!, Modifier.padding(16.dp))
        else if (!consentReady) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (!trusted) {
            Text("Run this napplet? Its reviewed code can read and replace the linked daily tracker File in your self-conversation. NStack administrators may also have access. Only run code you trust. Approval is remembered on this device for this code and access.", Modifier.padding(16.dp))
            TextButton(enabled = !remembering, onClick = {
                remembering = true
                scope.launch {
                    try {
                        authorize()
                        if (failure == null) {
                            val current = receipt(lastWritable == true)
                            approval = current
                            val saved = withContext(Dispatchers.IO) { approvals.remember(current) }
                            approvalNotice = if (saved) null else "Approval could not be remembered on this device. It applies only to this open session."
                            trusted = true
                        }
                    } finally { remembering = false }
                }
            }, modifier = Modifier.testTag("napplet.trust")) { Text("Trust and open") }
        } else {
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Keep DOM and memory drafts during periodic checks, but stop interaction until reauthorized.
            page?.let { host -> AndroidView(factory = { host.view }, update = { it.visibility = if (!foreground || !visible || updating) android.view.View.INVISIBLE else android.view.View.VISIBLE }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("napplet.host")) }
        }
    }
    if (updateConfirmation) AlertDialog(onDismissRequest = { if (!updating) updateConfirmation = false },
        title = { Text(if (dirty) "Discard changes and update app?" else "Update app?") },
        text = { Text("Install the updated built-in Daily Metrics code. Saved answers and history stay in the same NStack File. You will be asked to trust the changed code before it runs." + if (dirty) " Unsaved changes will be discarded." else "") },
        confirmButton = { TextButton(enabled = !busy && !updating, onClick = {
            updating = true; updateConfirmation = false
            scope.launch {
                gate.withLock {
                    try {
                        check(foreground && model.connection?.id == c.id && model.workspace?.id == w.id)
                        val update = updater(lastWritable == true)
                        withTimeout(45_000) {
                            update.authorize()
                            page?.destroy(); page = null; transport.clear(); deferredResult = null; dirty = false
                            update.update(replacement)
                        }
                        trusted = false; onUpdated()
                    } catch (_: TimeoutCancellationException) { stop(); failure = "The update timed out. Close and reopen to check the current app. Saved data was not changed." }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { stop(); failure = "The update could not be confirmed. Close and reopen to check the current app. Saved data was not changed." }
                    finally { updating = false }
                }
            }
        }, modifier = Modifier.testTag("napplet.confirmUpdate")) { Text("Update app") } },
        dismissButton = { TextButton(enabled = !updating, onClick = { updateConfirmation = false }) { Text("Keep current app") } })
    if (forgetting) AlertDialog(onDismissRequest = { forgetting = false }, title = { Text("Discard changes and forget approval?") }, text = { Text("Unsaved changes will be discarded. Saved tracker data will remain in NStack.") }, confirmButton = { TextButton(enabled = !busy, onClick = { forget() }) { Text("Discard and forget") } }, dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Keep editing") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unsaved changes?") }, text = { Text("These changes have not been saved to NStack.") }, confirmButton = { TextButton(onClick = { page?.destroy(); page = null; exit?.invoke() }) { Text("Discard and close") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

/** Pin every signed request to the connection identity, including an identity change during signing. */
internal fun AppModel.fileNappletAPI(
    connection: studio.otherstuff.ambulando.core.store.ConnectionRecord,
    transport: studio.otherstuff.ambulando.core.transport.HttpTransport = workspaceTransport,
    checkCurrent: (suspend () -> Unit)? = null,
): TowerAPI {
    val bound = studio.otherstuff.ambulando.core.nostr.EventSigner { template ->
        checkCurrent?.invoke()
        identity.requireSigner(connection.signerNpub)
        identity.sign(template).also {
            checkCurrent?.invoke()
            if (checkCurrent != null) identity.requireSigner(connection.signerNpub)
            check(studio.otherstuff.ambulando.core.nostr.NostrKeys.npub(it.pubkey) == connection.signerNpub)
        }
    }
    val fenced = if (checkCurrent == null) transport else object : studio.otherstuff.ambulando.core.transport.HttpTransport {
        override suspend fun send(request: studio.otherstuff.ambulando.core.transport.HttpRequest): studio.otherstuff.ambulando.core.transport.HttpResponse {
            checkCurrent()
            identity.requireSigner(connection.signerNpub)
            return transport.send(request).also { checkCurrent(); identity.requireSigner(connection.signerNpub) }
        }
    }
    return bindGoals(TowerAPI(TowerClient(TowerOrigin(connection.origin), fenced, bound), transfers), connection)
}
