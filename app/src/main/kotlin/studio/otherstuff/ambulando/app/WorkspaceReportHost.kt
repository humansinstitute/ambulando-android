package studio.otherstuff.ambulando.app

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
import studio.otherstuff.ambulando.app.web.WorkspaceReportWebHost
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*

/** Report-only execution owner. Background/context/access changes destroy the frame and its aggregates. */
@Composable internal fun WorkspaceReportHost(model: AppModel, file: FileRecord, html: String, manifest: JsonObject, version: String) {
    val c=model.connection ?: return; val w=model.workspace ?: return
    studio.otherstuff.ambulando.app.ui.shell.OwnEditorNavigation()
    val exit=studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit.current
    val context=LocalContext.current; val lifecycle=LocalLifecycleOwner.current.lifecycle
    val services=studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    val boundary=model.activeBoundaryId
    val scope=rememberCoroutineScope()
    val gate=remember { Mutex() }
    val approvals=remember { NappletApprovalStore(java.io.File(model.environment.baseDir,"napplet-approvals")) }
    var trusted by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var page by remember { mutableStateOf<WorkspaceReportWebHost?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    fun current()=model.activeBoundaryId==boundary && model.connection?.id==c.id && model.workspace?.id==w.id && model.workspace?.accessRemoved==false && services.unlocked
    val transport=remember(boundary,file.id,version) {
        WorkspaceReportTransport(model.fileNappletAPI(c),w.id,w.appNpub,c.signerNpub,file.id,version,html,
            file.raw.getValue("metadata").jsonObject,manifest,::current) { range -> model.sync.readWorkspaceReport(c,w,range) }
    }
    val receipt=remember(boundary,file.id,version) { NappletApproval.report(c.origin,c.id,c.towerNpub,context.packageName,w.id,w.appNpub,c.signerNpub,file.id,html,file.raw.getValue("metadata").jsonObject,manifest) }
    fun clear() { operation?.cancel(); page?.destroy(); page=null; busy=false }
    fun stop() { approvals.forget(receipt); clear(); trusted=false; ready=false; failure="This report is unavailable. Close and reopen it to verify access." }
    suspend fun authorize(): Boolean {
        checking=true
        return try { withTimeout(45000) { transport.authorize() }; true }
            catch(_: TimeoutCancellationException) { stop(); false }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { android.util.Log.w("WorkspaceReportHost","Code authority check failed",e); stop(); false }
            finally { checking=false }
    }
    LaunchedEffect(transport) {
        if(authorize()) { trusted=withContext(Dispatchers.IO) { approvals.approved(receipt) }; ready=true }
    }
    DisposableEffect(lifecycle,transport) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP) { foreground=false; clear() }
            if(event==Lifecycle.Event.ON_RESUME) foreground=true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); clear() }
    }
    LaunchedEffect(services.unlocked,w.accessRemoved,boundary) { if(!current()) stop() }
    LaunchedEffect(transport,trusted) {
        if(trusted) model.store.changes.stream().collect { changes ->
            if(changes.any { (it.workspaceId==null || it.workspaceId==w.id) && it.table in setOf(
                studio.otherstuff.ambulando.core.store.StoreTable.FILES,studio.otherstuff.ambulando.core.store.StoreTable.CHANNELS,
                studio.otherstuff.ambulando.core.store.StoreTable.ACCESS,studio.otherstuff.ambulando.core.store.StoreTable.WORKSPACES,
                studio.otherstuff.ambulando.core.store.StoreTable.CONNECTIONS) }) gate.withLock { if(!authorize()) return@withLock }
        }
    }
    LaunchedEffect(transport,trusted,foreground) {
        if(!trusted || !foreground || failure!=null) return@LaunchedEffect
        gate.withLock { if(!authorize()) return@LaunchedEffect }
        withContext(Dispatchers.Main.immediate) { WorkspaceReportWebHost.create(context,html,onMessage={ request ->
            if(!busy && !checking && foreground && current() && failure==null) {
                busy=true
                operation=scope.launch {
                    gate.withLock {
                        val host=page
                        try {
                            val value=transport.read(WorkspaceReport.text(request,"range"))
                            ensureActive(); check(current())
                            if(foreground && page===host) host?.deliver(buildJsonObject {
                                put("type","wingman-napplet-result"); put("version",1); put("session",host.session)
                                put("requestId",request.getValue("requestId")); put("status","ready"); put("value",value)
                                put("canWrite",false); put("privacy","viewer-channels")
                            })
                        } catch(e: CancellationException) { throw e }
                        catch(_: Exception) {
                            // A missing API/offline response is unavailable, never a zero report. Recheck code authority first.
                            if(authorize() && foreground && page===host) host?.deliver(buildJsonObject {
                                put("type","wingman-napplet-result"); put("version",1); put("session",host.session)
                                put("requestId",request.getValue("requestId")); put("status","error"); put("code","unavailable")
                                put("message","The workspace report is unavailable. Refresh when access is available.")
                            })
                        } finally { busy=false }
                    }
                }
            }
        },onStopped={ android.util.Log.w("WorkspaceReportHost","Sandbox stopped"); stop() }).fold(onSuccess={ page=it },onFailure={ android.util.Log.w("WorkspaceReportHost","Isolated view creation failed",it); stop() }) }
        while(isActive && failure==null) { delay(30000); gate.withLock { authorize() } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={ clear(); exit?.invoke() },modifier=Modifier.testTag("report.close")) { Text("Close") }
            Text("Workspace Report",Modifier.weight(1f).padding(12.dp))
            if(trusted) TextButton(onClick={ clear(); trusted=false; notice=if(approvals.forget(receipt)) "Approval forgotten on this device." else "Could not forget approval. Retry before relying on revocation." },modifier=Modifier.testTag("report.forget")) { Text("Forget approval") }
        }
        notice?.let { Text(it,Modifier.padding(12.dp)) }
        when {
            failure!=null -> Text(failure!!,Modifier.padding(16.dp).testTag("report.error"))
            !ready -> LinearProgressIndicator(Modifier.fillMaxWidth())
            !trusted -> {
                Text("Run Workspace Report? This code can read aggregate activity and current inventory for channels you can access in this workspace. It cannot write through this report capability. Approval is remembered on this device for these exact code bytes and this workspace.",Modifier.padding(16.dp))
                TextButton(enabled=!busy,onClick={ busy=true; scope.launch { gate.withLock {
                    try { if(authorize()) { if(!withContext(Dispatchers.IO) { approvals.remember(receipt) }) notice="Approval applies only to this session; it could not be remembered."; trusted=true } }
                    finally { busy=false }
                } } },modifier=Modifier.testTag("report.trust")) { Text("Trust and open") }
            }
            else -> page?.let { host -> AndroidView(factory={host.view},update={ it.visibility=if(checking || !foreground) android.view.View.INVISIBLE else android.view.View.VISIBLE },modifier=Modifier.fillMaxWidth().weight(1f).testTag("report.host")) }
        }
    }
}
