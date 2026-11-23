package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.core.surfaces.*

/** Native discovery from the existing workspace/application namespace, alongside legacy File bookmarks. */
@Composable internal fun NappletRuntimeApps(model: AppModel) {
    val c=model.connection ?: return; val w=model.workspace ?: return
    val services=LocalAppServices.current; val boundary=model.activeBoundaryId; val scope=rememberCoroutineScope()
    val authority=runCatching {NappletRuntimeContext.create(c,w)}.getOrNull()
    if(authority==null) { Text("Workspace apps require a verified Tower and workspace service identity. Reconnect to verify compatibility."); return }
    val authorityKey=GenericJson.canonical(authority)
    val sync=remember(boundary,authorityKey,services.unlocked) { NappletRuntimeSync(model.sync,c,w) {
        model.activeBoundaryId==boundary && model.connection?.id==c.id && model.workspace?.id==w.id && services.unlocked && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked && model.identityState?.npub==c.signerNpub && model.workspace?.accessRemoved==false && runCatching {NappletRuntimeContext.create(requireNotNull(model.connection),requireNotNull(model.workspace))==authority}.getOrDefault(false)
    } }
    val state by sync.state.collectAsState()
    var selected by remember(boundary,authorityKey,services.unlocked) { mutableStateOf<String?>(null) }
    var adding by remember(boundary,authorityKey,services.unlocked) { mutableStateOf(false) }; var link by remember(boundary,authorityKey,services.unlocked) { mutableStateOf("") }
    var publisher by remember(boundary,authorityKey,services.unlocked) { mutableStateOf(c.signerNpub) }
    var busy by remember(boundary,authorityKey,services.unlocked) { mutableStateOf(false) }; var error by remember(boundary,authorityKey,services.unlocked) { mutableStateOf<String?>(null) }
    DisposableEffect(sync) { onDispose { sync.clear() } }
    LaunchedEffect(sync,services.unlocked,w.accessRemoved) {
        if(!services.unlocked || w.accessRemoved) { sync.clear(); selected=null; return@LaunchedEffect }
        while(isActive) {
            try { sync.refresh(); error=null } catch(e: CancellationException) {throw e} catch(_: Exception) {error="Workspace apps are unavailable. Legacy File apps remain below."}
            delay(30000)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        state.installations.forEach { i -> TextButton(onClick={ selected=i.id },enabled=state.available && services.unlocked && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked && model.identityState?.npub==c.signerNpub,modifier=Modifier.testTag("runtime.app.${i.id}")) {
            Text(NappletRuntimeProtocol.text(i.manifest,"icon")+" "+NappletRuntimeProtocol.text(i.manifest,"title"))
        } }
        Row {
            TextButton(enabled=services.unlocked && !busy,onClick={adding=true},modifier=Modifier.testTag("runtime.add")) {Text("Install workspace app")}
            TextButton(enabled=!busy,onClick={scope.launch {try {sync.refresh(); error=null} catch(e: CancellationException){throw e} catch(_: Exception){error="Workspace apps could not be refreshed."}}}) {Text("Refresh apps")}
        }
        error?.let {Text(it,Modifier.padding(8.dp))}
    }
    if(adding) AlertDialog(onDismissRequest={if(!busy) adding=false},title={Text("Install workspace app")},text={Column {
        Text("Use an existing v3 HTML File in this workspace. Installation needs app-management authority. Reinstall reconnects retained data using its current server revision. The publisher must have current publication authority.")
        OutlinedTextField(value=link,onValueChange={link=it},label={Text("File link or UUID")},modifier=Modifier.testTag("runtime.file"))
        OutlinedTextField(value=publisher,onValueChange={publisher=it},label={Text("Authorized publisher npub")})
        error?.let {Text(it)}
    }},confirmButton={TextButton(enabled=!busy,onClick={busy=true; scope.launch {
        try {val id=genericFileId(link,c.origin,w.id); val i=sync.installFile(id,publisher.trim()); sync.refresh(); adding=false; selected=i.id; error=null}
        catch(e: CancellationException){throw e} catch(_: Exception){error="Installation could not be verified or authorized. Existing Files and data are unchanged."} finally {busy=false}
    }}) {Text("Install and review")}},dismissButton={TextButton(enabled=!busy,onClick={adding=false}){Text("Cancel")}})
    selected?.let { id -> Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Surface(Modifier.fillMaxSize()) { key(boundary,authorityKey,id) { NappletRuntimeHost(model,sync,id) {selected=null; scope.launch {try {sync.refresh()} catch(e: CancellationException){throw e} catch(_: Exception){}}} } }
    } }
}
