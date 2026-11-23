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

/** Human-managed audience and collection grants stay visibly distinct. Frames never choose owners. */
@Composable internal fun NappletRuntimeAccess(model: AppModel,sync: NappletRuntimeSync,i: NappletRuntimeInstallation,onChanged: ()->Unit,onRemoved: ()->Unit) {
    val scope=rememberCoroutineScope(); val viewer=model.connection?.signerNpub ?: return
    var collections by remember(i.id) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var audience by remember(i.raw) { mutableStateOf(i.raw.getValue("audience_npubs").jsonArray.joinToString("\n") {it.jsonPrimitive.content}) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<Boolean?>(null) }; var confirmation by remember { mutableStateOf("") }
    var importFile by remember { mutableStateOf("") }; var importCollection by remember { mutableStateOf("") }
    var compatible by remember { mutableStateOf(false) }; var importPreview by remember { mutableStateOf<NappletRuntimeSync.Import?>(null) }
    fun npubs(value: String)=value.split(Regex("[\\s,]+")).filter {it.isNotEmpty()}.distinct().also {require(it.all {npub -> npub.matches(Regex("npub1[a-z0-9]+"))})}
    fun command(action: suspend ()->Unit) { if(!busy) {busy=true; error=null; scope.launch {
        try {action()} catch(e: CancellationException){throw e} catch(_: Exception){error="The operation could not be confirmed or authorized. Refresh before retrying a change."} finally {busy=false}
    }} }
    LaunchedEffect(i.id) { try {collections=sync.collections(i)} catch(e: CancellationException){throw e} catch(_: Exception){error="Collection access is unavailable."} }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Access and data",style=MaterialTheme.typography.titleLarge)
        Text("Sharing the app only changes discovery/open. Each collection has separate owner-managed read/write grants. Personal signer and DM capabilities are unsupported.")
        Text("App audience")
        OutlinedTextField(value=audience,onValueChange={audience=it},label={Text("User or agent npubs, separated by spaces")},enabled=!busy)
        TextButton(enabled=!busy,onClick={command {sync.audience(i,npubs(audience)); onChanged()}}) {Text("Save app audience")}
        if(i.raw["pending_release"]!=null && i.raw["pending_release"]!=JsonNull) {
            Text("A publisher update requests new access. Approving activates the new declarations; personal device consent remains separate. Declining keeps the current active release.")
            Text(i.raw.getValue("pending_release").toString())
            TextButton(enabled=!busy,onClick={command {sync.approveRelease(i); onChanged()}}) {Text("Approve expanded release")}
            TextButton(enabled=!busy,onClick=onChanged) {Text("Keep current release")}
        }
        i.manifest.getValue("collections").jsonObject.forEach { (name,declaration) ->
            Text("$name (${declaration.jsonObject["scope"]}, schema ${declaration.jsonObject["schema_version"]})",style=MaterialTheme.typography.titleMedium)
            val rows=collections.filter {it["name"]==JsonPrimitive(name) && it["scope"]==declaration.jsonObject["scope"] && it["schema_version"]==declaration.jsonObject["schema_version"]}
            rows.forEach { c ->
                val cid=NappletRuntimeProtocol.text(c,"id")
                Text("Collection $cid; owner ${c["owner_npub"]}")
                TextButton(enabled=!busy,onClick={command {sync.select(i,name,cid); onChanged()}}) {Text("Use this collection")}
                if(c["owner_npub"]==JsonPrimitive(viewer)) {
                    var readers by remember(c) {mutableStateOf(c.getValue("read_npubs").jsonArray.joinToString("\n") {it.jsonPrimitive.content})}
                    var writers by remember(c) {mutableStateOf(c.getValue("write_npubs").jsonArray.joinToString("\n") {it.jsonPrimitive.content})}
                    OutlinedTextField(value=readers,onValueChange={readers=it},label={Text("Read grants")},enabled=!busy)
                    OutlinedTextField(value=writers,onValueChange={writers=it},label={Text("Write grants")},enabled=!busy)
                    TextButton(enabled=!busy,onClick={command {sync.grants(i,c,npubs(readers),npubs(writers)); onChanged()}}) {Text("Replace collection grants")}
                }
            }
        }
        Text("Import compatible File data",style=MaterialTheme.typography.titleMedium)
        Text("Explicitly import an immutable JSON File from your self conversation as one legacy-snapshot record in your own personal collection. The receiving app must support that whole-object format. Original File and all history stay untouched. Existing records are never overwritten.")
        OutlinedTextField(value=importFile,onValueChange={importFile=it;importPreview=null},label={Text("Source File UUID")},enabled=!busy)
        OutlinedTextField(value=importCollection,onValueChange={importCollection=it;importPreview=null},label={Text("Declared personal collection name")},enabled=!busy)
        TextButton(enabled=!busy,onClick={command { importPreview=sync.previewImport(importFile.trim()) }}) {Text("Review immutable source")}
        importPreview?.let { preview ->
            Text("Source ${preview.source["file_id"]}; version ${preview.source["file_version_id"]}; SHA-256 ${preview.source["sha256_hex"]}. ${preview.value.size} top-level fields.")
            Row { Checkbox(checked=compatible,onCheckedChange={compatible=it}); Text("I confirm this app supports this complete JSON object.") }
            TextButton(enabled=!busy && compatible,onClick={command {sync.importFile(i,importCollection.trim(),preview,compatible); onChanged()}},modifier=Modifier.testTag("runtime.import")) {Text("Import and preserve source")}
        }
        Text("Removal",style=MaterialTheme.typography.titleMedium)
        Text("Keep data retains collections and history for reinstall. Delete data removes this app's collection payloads and history only when you own every collection. Source Files and their histories are preserved.")
        TextButton(enabled=!busy,onClick={removal=false},modifier=Modifier.testTag("runtime.removeKeep")) {Text("Remove and keep data")}
        TextButton(enabled=!busy,onClick={removal=true},modifier=Modifier.testTag("runtime.removeDelete")) {Text("Remove and delete data")}
        error?.let {Text(it)}
        TextButton(enabled=!busy,onClick=onChanged) {Text("Back to app")}
    }
    removal?.let { delete -> AlertDialog(onDismissRequest={if(!busy) removal=null},title={Text(if(delete) "Delete app data and history?" else "Remove app and keep data?")},text={Column {
        Text(if(delete) "This deletes this app's collection records and history. Original Files remain untouched. Type ${i.id} to confirm." else "Retained records and history can reconnect on reinstall. No data is deleted.")
        if(delete) OutlinedTextField(value=confirmation,onValueChange={confirmation=it},label={Text("Stable app ID")},modifier=Modifier.testTag("runtime.deleteId"))
        error?.let {Text(it)}
    }},confirmButton={TextButton(enabled=!busy && (!delete || confirmation==i.id),onClick={command {sync.remove(i,delete); removal=null; onRemoved()}},modifier=Modifier.testTag("runtime.confirmRemove")) {Text("Confirm removal")}},dismissButton={TextButton(enabled=!busy,onClick={removal=null}) {Text("Cancel")}}) }
}
