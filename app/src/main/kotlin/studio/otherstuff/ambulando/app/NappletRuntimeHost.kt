package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.web.NappletRuntimeWebHost
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.core.surfaces.*

/** A mounted common runtime has no ambient native authority: the host owns all commands and lifecycle. */
@Composable internal fun NappletRuntimeHost(model: AppModel,sync: NappletRuntimeSync,id: String,onClose: ()->Unit) {
    val c=model.connection ?: return; val w=model.workspace ?: return
    val services=studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    val visible by rememberUpdatedState(LocalEditorActive.current)
    val boundary=model.activeBoundaryId; val context=LocalContext.current; val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val authority=runCatching {NappletRuntimeContext.create(c,w)}.getOrNull()
    if(authority==null || sync.context!=authority) { Text("This napplet requires a verified Tower and workspace service identity. Reconnect before opening it."); return }
    val authorityKey=GenericJson.canonical(authority)
    val gate=remember(authorityKey) { Mutex() }
    val approvals=remember(boundary,authorityKey) { NappletRuntimeApprovals(java.io.File(model.environment.baseDir,"napplet-runtime-approvals"),authority) }
    var installation by remember(id,boundary,authorityKey) { mutableStateOf<NappletRuntimeInstallation?>(null) }
    var html by remember(id,boundary,authorityKey) { mutableStateOf<String?>(null) }
    var trusted by remember(id,boundary,authorityKey) { mutableStateOf(false) }
    var page by remember(authorityKey) { mutableStateOf<NappletRuntimeWebHost?>(null) }
    var foreground by remember(authorityKey) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var busy by remember(authorityKey) { mutableStateOf(false) }; var dirty by remember(authorityKey) { mutableStateOf(false) }; var dirtyRevision by remember(authorityKey) { mutableLongStateOf(0) }
    var checking by remember(authorityKey) { mutableStateOf(true) }; var failure by remember(authorityKey) { mutableStateOf<String?>(null) }
    var notice by remember(authorityKey) { mutableStateOf<String?>(null) }; var confirm by remember(authorityKey) { mutableStateOf<(() -> Unit)?>(null) }
    var job by remember(authorityKey) { mutableStateOf<Job?>(null) }; var epoch by remember(authorityKey) { mutableIntStateOf(0) }
    var displayedData by remember(authorityKey) { mutableStateOf(false) }
    var newerData by remember(authorityKey) { mutableStateOf(false) }
    var access by remember(authorityKey) { mutableStateOf(false) }; var updatePending by remember(authorityKey) { mutableStateOf(false) }
    fun current()=model.activeBoundaryId==boundary && model.connection?.id==c.id && model.workspace?.id==w.id && model.workspace?.accessRemoved==false && services.unlocked && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked && model.identityState?.npub==c.signerNpub && runCatching {NappletRuntimeContext.create(requireNotNull(model.connection),requireNotNull(model.workspace))==authority}.getOrDefault(false)
    fun clear() { displayedData=false; job?.cancel(); job=null; page?.destroy(); page=null; busy=false; dirty=false }
    fun stop() { clear(); sync.invalidate(id); trusted=false; html=null; failure="This napplet is unavailable. Reopen it to verify current access." }
    fun act(action: ()->Unit) { if(!busy) { if(dirty) confirm=action else { clear(); action() } } }
    suspend fun refresh() {
        checking=true
        try {
            check(current())
            val previous=installation
            val fresh=if(previous==null) null else sync.authority(previous)
            if(previous==null || fresh!!.bundle!=previous.bundle || fresh.manifest!=previous.manifest || fresh.publisher!=previous.publisher) {
                if(previous!=null && (dirty || busy) && fresh!=null && approvals.approved(previous) && NappletRuntimeProtocol.permits(fresh.manifest,previous.manifest)) {
                    updatePending=true
                    notice="An app update is ready. Save or discard your unsaved changes before loading it."
                    return
                }
                val opened=sync.open(id); check(current())
                clear(); updatePending=false; installation=opened.first; html=opened.second; failure=null
                trusted=approvals.approved(opened.first); epoch++
                if(previous!=null) notice=if(trusted) "App updated. Saved data and history are unchanged." else "This update needs access review."
            } else {
                installation=fresh
                if(trusted && !approvals.approved(fresh)) { clear(); trusted=false }
            }
        } catch(e: CancellationException) { throw e } catch(e: Exception) {
            if(!transientNappletFailure(e) || installation==null || !trusted) {
                if(!transientNappletFailure(e) && current()) sync.forgetLocal(id,installation)
                stop()
            } else notice="Showing saved content. The app could not be refreshed."
        } finally { checking=false; if(foreground && visible && trusted && failure==null && current()) page?.resume() }
    }
    LaunchedEffect(updatePending,busy,dirty,foreground,visible) { if(updatePending && !busy && !dirty && foreground && visible) gate.withLock { refresh() } }
    BackHandler { act(onClose) }
    DisposableEffect(lifecycle,authorityKey) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_PAUSE) {
                foreground=false; checking=true; page?.pause()
                if(busy) { job?.cancel(); job=null; busy=false; notice="An operation was interrupted. Reload records before retrying a change; it may already have saved." }
            }
            if(event==Lifecycle.Event.ON_RESUME) { foreground=true; if(current()) page?.resume() }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); clear() }
    }
    DisposableEffect(page) {
        val host=page; val listener=android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if(focused && foreground && visible) scope.launch { gate.withLock { refresh() } } }
        host?.view?.viewTreeObserver?.addOnWindowFocusChangeListener(listener)
        onDispose { host?.view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener) }
    }
    LaunchedEffect(services.unlocked,w.accessRemoved,model.activeBoundaryId,authorityKey) { if(!current()) stop() }
    AppDiscoveryEffect(trusted && foreground && visible,sync,id) {
        model.store.changes.stream().collect {
            val mounted=installation ?: return@collect
            try {
                model.store.reader.withArtefactAccess(w,NappletRuntimeProtocol.text(mounted.bundle,"file_id"),::current) { Unit }
            } catch(e: CancellationException) { throw e } catch(_: Exception) { stop() }
        }
    }
    AppDiscoveryEffect(foreground && visible, sync,id,boundary,authorityKey) {
        if(installation==null) try {
            sync.cachedOpen(id)?.let { cached ->
                check(current()); installation=cached.first; html=cached.second; trusted=approvals.approved(cached.first); checking=false
            }
        } catch(e: CancellationException) {throw e} catch(_: Exception) {if(!current()) {stop();return@AppDiscoveryEffect}}
        gate.withLock { refresh() }
        while(isActive) { delay(30000); gate.withLock { refresh() } }
    }
    LaunchedEffect(visible) { if(!visible) { checking=true; page?.pause() } }
    LaunchedEffect(sync,trusted,foreground,html,epoch,access,checking,visible) {
        val i=installation; val bytes=html
        if(!visible || page!=null || !trusted || !foreground || !current() || failure!=null || i==null || bytes==null || access) return@LaunchedEffect
        if(NappletRuntimeProtocol.support(i.manifest)!=null) return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
        NappletRuntimeWebHost.create(context,bytes,i.manifest,accept={ request ->
            val op=NappletRuntimeProtocol.text(request,"operation"); val session=NappletRuntimeProtocol.text(request,"session")
            if(!trusted || !foreground || !visible || !current() || failure!=null || page?.session?.let { it!=session }==true) false
            else if(op=="dirty") { dirty=request["dirty"]==JsonPrimitive(true); dirtyRevision++; true }
            else if(busy || checking && op !in setOf("list","get") && !displayedData) false
            else {
                busy=true; val revisionAtRequest=dirtyRevision
                job=scope.launch {
                    var host: NappletRuntimeWebHost?=null
                    var cached: JsonObject?=null
                    try {
                        if(op in setOf("list","get")) {
                            cached=sync.cachedRead(i,request)
                            if(cached!=null) {
                                yield(); host=page?.takeIf { it.session==session }
                                if(host!=null && trusted && foreground && visible && current()) {
                                    host?.deliver(NappletRuntimeProtocol.result(session,NappletRuntimeProtocol.text(request,"requestId"),cached))
                                    displayedData=true; busy=false
                                }
                            }
                        }
                        val result=withTimeout(45000) {
                            yield()
                            gate.withLock {
                                host=page?.takeIf { it.session==session }; check(host!=null && trusted && foreground && visible && current())
                                val authority=sync.authority(i); require(authority.publisher==i.publisher && approvals.approved(i) && NappletRuntimeProtocol.permits(authority.manifest,i.manifest))
                                sync.execute(i,request)
                            }
                        }
                        if(cached!=null && cached!=result) newerData=true
                        ensureActive(); if(page===host && current() && foreground && visible && trusted && (!dirty || op !in setOf("list","get")) && cached==null) {displayedData=true;host?.deliver(NappletRuntimeProtocol.result(session,NappletRuntimeProtocol.text(request,"requestId"),result))}
                        if(op in setOf("create","update","delete")) { if(dirtyRevision==revisionAtRequest) dirty=false }
                    } catch(_: TimeoutCancellationException) {
                        if(page===host && current() && foreground && visible && trusted && cached==null) host?.deliver(NappletRuntimeProtocol.error(session,NappletRuntimeProtocol.text(request,"requestId"),"unavailable"))
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        if(page===host && current() && foreground && visible && trusted && cached==null) host?.deliver(NappletRuntimeProtocol.error(session,NappletRuntimeProtocol.text(request,"requestId"),if(e is NappletRuntimeAPI.Conflict) "conflict" else "unavailable"))
                        if(e is NappletRuntimeSync.Compatibility) notice=e.message
                        else if(foreground && visible && e !is NappletRuntimeAPI.Conflict) gate.withLock { refresh() }
                    } finally { if(job===currentCoroutineContext()[Job]) { busy=false; job=null } }
                }; true
            }
        },onStopped={ stop() }).fold(onSuccess={page=it},onFailure={stop()})
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row {
            TextButton(enabled=!busy,onClick={act(onClose)},modifier=Modifier.testTag("runtime.close")) { Text("Close") }
            Text(installation?.manifest?.get("title")?.jsonPrimitive?.content ?: id,Modifier.weight(1f).padding(12.dp))
        }
        Row {
            TextButton(enabled=!busy,onClick={scope.launch {gate.withLock {refresh()}}}) { Text("Check for update") }
            TextButton(enabled=!busy && installation!=null,onClick={act { access=true; epoch++ }},modifier=Modifier.testTag("runtime.access")) { Text("Access and data") }
        }
        if(trusted) TextButton(enabled=!busy,onClick={act { installation?.let { notice=if(approvals.forget(it)) "Approval forgotten. Saved data is unchanged." else "Approval could not be forgotten." }; sync.invalidate(id); trusted=false }},modifier=Modifier.testTag("runtime.forget")) { Text("Forget approval") }
        if(newerData) TextButton(enabled=!busy,onClick={act { newerData=false; epoch++ }},modifier=Modifier.testTag("runtime.reloadSaved")) {Text("New saved data available · Reload")}
        if(dirty) Text("Unsaved changes")
        if(updatePending) TextButton(enabled=!busy,onClick={act { scope.launch { gate.withLock {refresh()} } }}) { Text("Load available update") }
        notice?.let { Text(it,Modifier.padding(12.dp)) }
        val i=installation
        val unsupported=i?.let { NappletRuntimeProtocol.support(it.manifest) }
        when {
            failure!=null -> Text(failure!!,Modifier.padding(16.dp))
            i==null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            access -> NappletRuntimeAccess(model,sync,i,onChanged={ access=false; epoch++; scope.launch { gate.withLock {refresh()} } },onRemoved={approvals.forget(i); clear(); sync.invalidate(id); onClose()})
            unsupported!=null -> Text(unsupported,Modifier.padding(16.dp))
            !trusted -> {
                Text("Run ${NappletRuntimeProtocol.text(i.manifest,"title")}? Publisher: ${i.publisher}. Only run publishers you trust. This app requests:",Modifier.padding(16.dp))
                i.manifest.getValue("collections").jsonObject.forEach { (name,c) -> Text("$name: ${c.jsonObject["scope"]}, schema ${c.jsonObject["schema_version"]}, ${c.jsonObject["operations"]}",Modifier.padding(8.dp)) }
                Text("Audience access does not grant access to private data. Approval is remembered on this device for this publisher and access; ordinary code updates need no new approval.",Modifier.padding(16.dp))
                TextButton(enabled=!checking,onClick={scope.launch {gate.withLock {
                    refresh(); val fresh=installation
                    if(fresh!=null && failure==null && current() && NappletRuntimeProtocol.support(fresh.manifest)==null) {
                        if(approvals.remember(fresh)) {trusted=true; epoch++} else notice="Approval could not be saved. Retry before opening."
                    }
                }}},modifier=Modifier.testTag("runtime.trust")) { Text("Trust and open") }
            }
            else -> page?.let { host -> key(host.session) { AndroidView(factory={host.view},update={it.visibility=if(!foreground || !visible) android.view.View.INVISIBLE else android.view.View.VISIBLE},modifier=Modifier.fillMaxWidth().weight(1f).testTag("runtime.host")) } }
        }
    }
    confirm?.let { action -> AlertDialog(onDismissRequest={confirm=null},title={Text("Discard unsaved changes?")},text={Text("Saved records and history remain in NStack.")},confirmButton={TextButton(onClick={confirm=null; clear(); action()}) {Text("Discard and continue")}},dismissButton={TextButton(onClick={confirm=null}) {Text("Keep editing")}}) }
}
