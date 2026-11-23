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
import studio.otherstuff.ambulando.app.web.GenericNappletWebHost
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*

@Composable internal fun GenericNappletHost(model: AppModel,file: FileRecord,html: String,binding: GenericNapplet.Binding,version: String,onUpdate: ()->Unit) {
    val c=model.connection ?: return; val w=model.workspace ?: return
    OwnEditorNavigation(); val exit=LocalRouteExit.current
    val services=studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    val context=LocalContext.current; val lifecycle=LocalLifecycleOwner.current.lifecycle
    val boundary=model.activeBoundaryId; val scope=rememberCoroutineScope(); val gate=remember { Mutex() }
    val approvals=remember { GenericApprovalStore(java.io.File(model.environment.baseDir,"napplet-approvals")) }
    var receipt by remember { mutableStateOf<GenericNapplet.Receipt?>(null) }
    var trusted by remember { mutableStateOf(false) }; var ready by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var frameEpoch by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<GenericNappletWebHost?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }; var notice by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }; var busy by remember { mutableStateOf(false) }
    var writable by remember { mutableStateOf<Boolean?>(null) }; var dirty by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }; var job by remember { mutableStateOf<Job?>(null) }
    fun current()=model.activeBoundaryId==boundary && model.connection?.id==c.id && model.workspace?.id==w.id && model.workspace?.accessRemoved==false && services.unlocked
    val transport=remember(boundary,file.id,version) { GenericNappletTransport(model.fileNappletAPI(c),w.id,w.appNpub,c.signerNpub,file.id,version,html,file.raw.getValue("metadata").jsonObject,binding,::current) { model.sync.readWorkspaceReport(c,w,it) } }
    fun clear() { job?.cancel(); job=null; page?.destroy(); page=null; transport.clear(); busy=false; dirty=false }
    fun stop() { clear(); trusted=false; failure="This napplet is unavailable. Reopen it to verify current access." }
    fun makeReceipt(write: Boolean)=GenericNapplet.receipt(buildJsonObject {
        put("baseUrl",c.origin); put("tower",c.towerNpub ?: ""); put("service",w.raw.obj("identity")?.string("workspace_service_npub") ?: "")
        put("appNpub",w.appNpub); put("workspaceId",w.id); put("viewer",c.signerNpub)
    },file.id,studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(html.toByteArray()),binding,write)
    suspend fun authorize(expectedDataVersion: String?=null): Boolean {
        checking=true
        return try {
            val write=model.sync.genericNappletRead(file.id) { transport.authorize(expectedDataVersion) }
            check(current()); if(writable!=null && writable!=write) { stop(); false } else { writable=write; true }
        } catch(_: TimeoutCancellationException) { stop(); false }
        catch(e: CancellationException) { throw e }
        catch(e: GenericNappletTransport.Conflict) { throw e }
        catch(_: Exception) { stop(); false } finally { checking=false }
    }
    fun action(a: String) {
        clear()
        when(a) {
            "close" -> exit?.invoke()
            "update" -> onUpdate()
            "forget" -> { val r=receipt; notice=if(r!=null && approvals.forget(r)) "Approval forgotten. Saved data is unchanged." else "Could not forget approval. Retry before relying on revocation."; trusted=false }
        }
    }
    fun requestAction(a: String) { if(!busy) { if(dirty) confirm=a else action(a) } }
    BackHandler(enabled=LocalEditorActive.current) { requestAction("close") }
    LaunchedEffect(transport) { gate.withLock { if(authorize()) { receipt=makeReceipt(writable==true); trusted=approvals.approved(receipt!!); ready=true } } }
    DisposableEffect(lifecycle,transport) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP) { foreground=false; clear(); frameEpoch++ }
            if(event==Lifecycle.Event.ON_RESUME) foreground=true
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); clear() }
    }
    DisposableEffect(page) {
        val host=page; val listener=android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if(focused) scope.launch { gate.withLock { authorize() } } }
        host?.view?.viewTreeObserver?.addOnWindowFocusChangeListener(listener)
        onDispose { host?.view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener) }
    }
    LaunchedEffect(services.unlocked,w.accessRemoved,model.activeBoundaryId) { if(!current()) stop() }
    LaunchedEffect(transport,trusted) {
        if(trusted) model.store.changes.stream().collect { changes -> if(changes.any { (it.workspaceId==null || it.workspaceId==w.id) && it.table in setOf(
            studio.otherstuff.ambulando.core.store.StoreTable.FILES,studio.otherstuff.ambulando.core.store.StoreTable.CHANNELS,studio.otherstuff.ambulando.core.store.StoreTable.ACCESS,studio.otherstuff.ambulando.core.store.StoreTable.WORKSPACES,studio.otherstuff.ambulando.core.store.StoreTable.CONNECTIONS) }) gate.withLock { authorize() } }
    }
    LaunchedEffect(transport,trusted,foreground,frameEpoch) {
        if(!trusted || !foreground || failure!=null) return@LaunchedEffect
        gate.withLock { if(!authorize()) return@LaunchedEffect }
        withContext(Dispatchers.Main.immediate) {
            GenericNappletWebHost.create(context,html,binding,accept={ request ->
                val op=GenericNapplet.text(request,"operation")
                val requestSession=GenericNapplet.text(request,"session")
                if(busy || !trusted || !foreground || !current() || failure!=null ||
                    (page!=null && page!!.session!=requestSession) || (checking && op!="read")) false
                else if(op=="dirty") { dirty=request.getValue("dirty").jsonPrimitive.boolean; true }
                else {
                    // A first read may arrive during focus/store authorization. Reserve exactly one
                    // operation/ID and await that same gate; never enqueue a write during checking.
                    busy=true
                    job=scope.launch {
                        var host: GenericNappletWebHost?=null
                        try {
                            val result=withTimeout(45000) {
                                yield() // Let construction finish before checking the mounted page nonce.
                                gate.withLock {
                                    host=page?.takeIf { it.session==requestSession }
                                    check(host!=null && trusted && foreground && current() && failure==null)
                                    require(authorize())
                                    val snapshot=if(binding.capability=="private-json-file") {
                                        if(op=="save") { require(writable==true); transport.save(GenericNapplet.text(request,"baseVersionId"),request.getValue("value").jsonObject) }
                                        else model.sync.genericNappletRead(file.id) { transport.read() }
                                    } else null
                                    val value=snapshot?.value ?: transport.aggregate(GenericNapplet.text(request,"range"))
                                    require(authorize(snapshot?.version)); if(snapshot!=null) require(snapshot.canWrite==writable)
                                    ensureActive(); check(page===host && foreground && current() && trusted)
                                    buildJsonObject {
                                        put("type","wingman-napplet-result"); put("version",2); put("session",requestSession); put("requestId",request.getValue("requestId")); put("status","ready")
                                        snapshot?.let { put("baseVersionId",it.version) }; put("value",value); put("canWrite",snapshot?.canWrite ?: false); put("privacy",if(snapshot!=null) "self-channel" else "viewer-channels")
                                    }
                                }
                            }
                            ensureActive(); if(page===host && foreground && current() && trusted) host?.deliver(result)
                            if(op=="save") dirty=false
                        } catch(_: TimeoutCancellationException) {
                            val target=page?.takeIf { it.session==requestSession }
                            if(target!=null && foreground && current() && trusted && failure==null)
                                target.deliver(GenericNapplet.error(requestSession,GenericNapplet.text(request,"requestId"),"unavailable"))
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) {
                            gate.withLock {
                                if(page===host && host!=null && trusted && foreground && current() && authorize())
                                    host?.deliver(GenericNapplet.error(requestSession,GenericNapplet.text(request,"requestId"),if(e is GenericNappletTransport.Conflict) "conflict" else "unavailable"))
                            }
                        } finally {
                            if(job===currentCoroutineContext()[Job]) { busy=false; job=null }
                        }
                    }; true
                }
            },onStopped={ stop() }).fold(onSuccess={ page=it },onFailure={ stop() })
        }
        while(isActive && failure==null) { delay(30000); gate.withLock { authorize() } }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(enabled=!busy,onClick={ requestAction("close") },modifier=Modifier.testTag("generic.close")) { Text("Close") }
            Text(binding.icon+" "+binding.title,Modifier.weight(1f).padding(12.dp))
            RecordPin(model,PinRecord.ResourceType.FILE,file.id)
        }
        Row {
            TextButton(enabled=!busy,onClick={ requestAction("update") },modifier=Modifier.testTag("generic.update")) { Text("Check for update") }
            if(trusted) TextButton(enabled=!busy,onClick={ requestAction("forget") },modifier=Modifier.testTag("generic.forget")) { Text("Forget approval") }
            if(dirty) Text("Unsaved",Modifier.padding(12.dp))
        }
        notice?.let { Text(it,Modifier.padding(12.dp)) }
        when {
            failure!=null -> Text(failure!!,Modifier.padding(16.dp))
            !ready -> LinearProgressIndicator(Modifier.fillMaxWidth())
            !trusted -> {
                Text("Run ${binding.title}? Bound resource: ${binding.resource}. This verified code can "+(if(binding.capability=="workspace-aggregate") "read aggregate activity in accessible workspace channels." else if(writable==true) "read and replace its bound private JSON File." else "read its bound private JSON File.")+" Only run code you trust. Approval is remembered on this device for these code bytes and access.",Modifier.padding(16.dp))
                TextButton(enabled=!checking && !busy,onClick={ scope.launch { gate.withLock { if(authorize()) { val r=makeReceipt(writable==true); receipt=r; if(!approvals.remember(r)) notice="Approval applies to this session only."; trusted=true } } } },modifier=Modifier.testTag("generic.trust")) { Text("Trust and open") }
            }
            else -> page?.let { host -> key(host.session) {
                AndroidView(factory={host.view},update={ it.visibility=if(checking || !foreground) android.view.View.INVISIBLE else android.view.View.VISIBLE },modifier=Modifier.fillMaxWidth().weight(1f).testTag("generic.host"))
            } }
        }
    }
    confirm?.let { a -> AlertDialog(onDismissRequest={confirm=null},title={Text("Discard unsaved changes?")},text={Text("Saved data and history remain in NStack.")},confirmButton={TextButton(onClick={confirm=null; action(a)}) {Text("Discard and continue")}},dismissButton={TextButton(onClick={confirm=null}) {Text("Keep editing")}}) }
}
