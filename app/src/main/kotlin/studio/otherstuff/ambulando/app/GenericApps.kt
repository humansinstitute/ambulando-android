package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.app.ui.deck.*
import studio.otherstuff.ambulando.app.ui.nav.*

/** Personal File pins are bookmarks, never permission grants or a replacement app catalogue. */
@Composable internal fun GenericApps(model: AppModel) {
    val c=model.connection ?: return; val w=model.workspace ?: return
    val services=LocalAppServices.current
    val scope=rememberCoroutineScope(); val boundary=model.activeBoundaryId
    val pinsState=remember(c.id,w.id) { PinsState(model.store,c.id,w.id,scope) }
    DisposableEffect(pinsState) { pinsState.start(); onDispose { pinsState.stop() } }
    val pinResult by pinsState.state.collectAsState()
    val pins=pinResult.value
    LaunchedEffect(c.id,w.id) { model.sync.refreshPins(c,w) }
    suspend fun setPinned(pinned: Boolean,id: String) {
        check(model.activeBoundaryId==boundary && services.unlocked && !w.accessRemoved)
        val old=model.store.reader.pin(c.id,w.id,PinRecord.ResourceType.FILE,id)
        model.writes.submit(WriteIntent(WriteKind.PIN_SET,id,buildJsonObject {
            put("resource_type","file"); put("pinned",pinned); put("expected_row_version",old?.rowVersion ?: 0)
            put("mutation_id",java.util.UUID.randomUUID().toString())
        }),c,w)
    }
    var adding by remember(boundary) { mutableStateOf(false) }; var link by remember(boundary) { mutableStateOf("") }
    var error by remember(boundary) { mutableStateOf<String?>(null) }; var busy by remember(boundary) { mutableStateOf(false) }
    var installed by remember(boundary) { mutableStateOf<List<Pair<FileRecord,GenericNapplet.Binding>>>(emptyList()) }
    val files by remember(boundary) { model.store.liveQuery({ it.workspaceId==w.id }) { model.store.reader.files(w.id) } }.collectAsState(emptyList())
    suspend fun verify(id: String): Pair<FileRecord,GenericNapplet.Binding> = model.sync.genericNappletRead(id) {
        val api=model.fileNappletAPI(c); val file=api.artefactFile(w.id,w.appNpub,id)
        require(!file.accessRemoved && !file.archived && file.raw.string("record_state")!="deleted")
        val list=api.genericNappletHead(w.id,w.appNpub,id); val version=list.versions.single { it.id==list.currentVersionId }
        require(version.contentType?.substringBefore(';')=="text/html")
        val bytes=api.fileVersionBytes(w.id,w.appNpub,id,version); val html=GenericJson.text(bytes)
        FileNapplet.verifiedHTML(bytes,html,requireNotNull(version.sizeBytes),requireNotNull(version.sha256))
        file to requireNotNull(GenericNapplet.parse(html,file.raw.obj("metadata") ?: JsonObject(emptyMap())))
    }
    val ids=(pins.visibleItems.filter { it.pin.resourceType==PinRecord.ResourceType.FILE }.map { it.pin.resourceId } + pins.pending.values.filter { it.isPending && it.payloadObject["pinned"]==JsonPrimitive(true) && it.string("resource_type")=="file" }.mapNotNull { it.targetId }).distinct()
    LaunchedEffect(boundary,ids,files.filter { it.id in ids }.map { it.id to it.raw.string("current_version_id") }) {
        val result=mutableListOf<Pair<FileRecord,GenericNapplet.Binding>>()
        for(id in ids) try { result+=verify(id) } catch(e: CancellationException) { throw e } catch(_: Exception) { }
        if(model.activeBoundaryId==boundary) installed=result
    }
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        TextButton(enabled=services.unlocked && !w.accessRemoved,onClick={adding=true},modifier=Modifier.testTag("apps.addNapplet")) { Text("Add napplet") }
        installed.forEach { (file,b) -> Row(Modifier.fillMaxWidth()) {
            TextButton(onClick={services.open(WorkRoute.File(w.id,file.id))},modifier=Modifier.weight(1f).testTag("apps.generic")) { Text(b.icon+" "+b.title) }
            TextButton(onClick={scope.launch { try { setPinned(false,file.id) } catch(e: CancellationException) { throw e } catch(_: Exception) { error="The bookmark could not be removed. Saved Files are unchanged." } }},modifier=Modifier.testTag("apps.uninstall")) { Text("Uninstall") }
        } }
        error?.let { Text(it) }
    }
    if(adding) AlertDialog(onDismissRequest={if(!busy) adding=false},title={Text("Add napplet")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
        Text("Choose an existing HTML File, or paste its File link. The File and its data stay in NStack. This list contains locally available Files and may be incomplete.")
        files.filter { ArtefactType.of(it)==ArtefactType.HTML }.take(30).forEach { f -> TextButton(onClick={link=f.id}) { Text(f.displayName) } }
        OutlinedTextField(value=link,onValueChange={link=it},label={Text("File link or UUID")},modifier=Modifier.testTag("apps.nappletLink"))
        error?.let { Text(it) }
    }},confirmButton={TextButton(enabled=!busy,onClick={busy=true; scope.launch {
        try {
            val id=genericFileId(link,c.origin,w.id)
            val (file,_)=verify(id); check(model.activeBoundaryId==boundary && services.unlocked)
            model.store.writer.upsertFiles(w.id,listOf(file)); setPinned(true,file.id)
            error=null; adding=false; services.open(WorkRoute.File(w.id,file.id))
        } catch(e: CancellationException) { throw e } catch(e: Exception) { android.util.Log.w("GenericApps","Existing File installation unavailable",e); error="This File could not be verified as a supported napplet. No File or data was created." } finally { busy=false }
    }}) {Text("Add and open")}},dismissButton={TextButton(enabled=!busy,onClick={adding=false}) {Text("Cancel")}})
}

internal fun genericFileId(input: String,origin: String,workspace: String): String {
    val s=input.trim(); if(GenericNapplet.uuid.matches(s)) return s
    val base=java.net.URI(origin); val u=base.resolve(java.net.URI(s))
    require(u.scheme==base.scheme && u.host==base.host && u.port==base.port && u.rawUserInfo==null)
    if(u.rawQuery!=null) {
        require(u.fragment==null)
        val pairs=u.rawQuery.split('&').map { part -> val p=part.split('=',limit=2); require(p.size==2); java.net.URLDecoder.decode(p[0],"UTF-8") to java.net.URLDecoder.decode(p[1],"UTF-8") }
        require(pairs.map { it.first }.distinct().size==pairs.size); val q=pairs.toMap()
        require(q["workspaceid"]==workspace && (u.path=="/flight-deck" || u.path=="/apps" || u.path=="/files"))
        val ids=listOfNotNull(q["nappletFile"],q["artefactid"],q["fileid"]); require(ids.size==1)
        return ids.single().also { require(GenericNapplet.uuid.matches(it)) }
    }
    require(u.fragment==null)
    val route=u.path.split('/').filter { it.isNotEmpty() }
    val id=route.lastOrNull() ?: error("Missing File ID")
    require(route==listOf("files",id) || route==listOf("workspaces",workspace,"files",id))
    return id.also { require(GenericNapplet.uuid.matches(it)) }
}
