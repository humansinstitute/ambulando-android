package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.UUID

/** The primary Artefacts + owns every type; rich types still use the normal staged File write queue. */
@Composable internal fun CreateArtefact(model: AppModel, trigger: @Composable (open: () -> Unit, enabled: Boolean) -> Unit) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val services = LocalAppServices.current
    val boundary = listOf(c.id, w.id, model.context.scopeId, model.context.channelId)
    var showing by remember(boundary) { mutableStateOf(false) }
    // null is an ordinary Document, which retains its existing DOC_CREATE storage and editor.
    var type by remember(boundary) { mutableStateOf<ArtefactType?>(null) }
    var template by remember(boundary) { mutableStateOf("blank") }
    var style by remember(boundary) { mutableStateOf("default") }
    var title by remember(boundary) { mutableStateOf("") }
    var channel by remember(boundary) { mutableStateOf(model.context.channelId) }
    var submitting by remember(boundary) { mutableStateOf(false) }
    var error by remember(boundary) { mutableStateOf<String?>(null) }
    val destinations = model.channels.filter {
        it.workspaceId == w.id && !it.accessRemoved &&
            (model.context.scopeId == null || it.scopeId == model.context.scopeId) &&
            (model.context.channelId == null || it.id == model.context.channelId)
    }
    val enabled = services.unlocked && !w.accessRemoved && destinations.isNotEmpty()
    val canCreate = enabled && !submitting && title.isNotBlank() && destinations.any { it.id == channel }
    trigger({ if (enabled) { error = null; showing = true } }, enabled)
    if (showing) AlertDialog(
        onDismissRequest = { if (!submitting) showing = false },
        title = { Text("New artefact") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type")
                // Vertical choices stay legible at phone widths and large font sizes.
                (listOf<ArtefactType?>(null) + ArtefactType.entries).forEach { option ->
                    val wire = option?.wire ?: "document"
                    val label = option?.label ?: "Document"
                    FilterChip(type == option, { type = option; template = ArtefactTemplates.starters.first { it.type == (option?.wire ?: "document") }.id }, enabled = !submitting,
                        label = { Text(label) }, modifier = Modifier.fillMaxWidth().testTag("artefacts.type.$wire"))
                }
                Text("Starter")
                ArtefactTemplates.starters.filter { it.type == (type?.wire ?: "document") }.forEach { starter ->
                    FilterChip(template == starter.id, { template = starter.id }, enabled = !submitting,
                        label = { Text(starter.label) }, modifier = Modifier.testTag("artefacts.template.${starter.id}"))
                }
                if (type != null) {
                    Text("Style")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArtefactTemplates.styles.forEach { (id, label) ->
                            FilterChip(style == id, { style = id }, enabled = !submitting, label = { Text(label) }, modifier = Modifier.testTag("artefacts.style.$id"))
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, enabled = !submitting, singleLine = true,
                    label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("artefacts.title"))
                Text("Channel")
                destinations.forEach { row ->
                    TextButton(onClick = { channel = row.id }, enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().testTag("artefacts.destination.${row.id}")) {
                        Text((if (channel == row.id) "✓ " else "") + row.name)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("artefacts.createError")) }
            }
        },
        confirmButton = {
            TextButton(enabled = canCreate, modifier = Modifier.testTag("artefacts.create"), onClick = {
                if (submitting || !canCreate) return@TextButton
                val destination = channel ?: return@TextButton
                val kind = type; val name = title.trim(); val starter = template; val chosenStyle = style
                val writes = model.writes // Keep staging and queue ownership on the workspace where + was pressed.
                // Disable synchronously: a second tap must not enqueue a second creation.
                submitting = true; error = null
                model.act {
                    val staged = "artefact-${UUID.randomUUID()}"
                    var queued = false
                    try {
                        withContext(Dispatchers.IO) {
                            writes.staging.mkdirs()
                            File(writes.staging, staged).writeText(ArtefactTemplates.source(kind?.wire ?: "document", starter, chosenStyle, name))
                        }
                        writes.submit(WriteIntent(if (kind == null) WriteKind.DOC_CREATE else WriteKind.FILE_UPLOAD, null, buildJsonObject {
                            put("channel_id", destination); put("staged", staged)
                            if (kind == null) {
                                put("title", name); put("file_name", "document.md"); put("content_type", "text/markdown")
                            } else {
                                put("display_name", "$name.${kind.extension}"); put("file_name", "artefact.${kind.extension}")
                                put("content_type", kind.mime); put("metadata", buildJsonObject { put("artefact", ArtefactTemplates.descriptor(kind.wire, starter, chosenStyle)) })
                            }
                        }), c, w)
                        queued = true
                        // This means queued, never an unconfirmed created-success message.
                        showing = false; title = ""
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not queue this artefact. Your title is kept; try again." }
                    finally {
                        if (!queued) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { File(writes.staging, staged).delete() }
                        submitting = false
                    }
                }
            }) { Text(if (submitting) "Queuing…" else "Create") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = { showing = false }, modifier = Modifier.testTag("artefacts.cancel")) { Text("Cancel") } },
    )
}

/** Direct File route, shared by Docs, chat mentions and pins; never needs a canvas. */
@Composable internal fun DirectArtefact(model: AppModel, fileId: String) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    var file by remember(c.id, w.id, fileId) { mutableStateOf<FileRecord?>(null) }
    var error by remember(fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(c.id, w.id, fileId) {
        try { file = model.towerAPI(c).artefactFile(w.id, w.appNpub, fileId) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "This artefact is unavailable." }
    }
    val exit = studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit.current
    val current = file
    if (current == null) { Text(error ?: "Loading artefact…", Modifier.padding(16.dp)); return }
    when (ArtefactType.of(current)) {
        ArtefactType.WHITEBOARD -> {
            var reference by remember(current) { mutableStateOf<BoardReference?>(null) }
            LaunchedEffect(current) {
                try {
                    val versions = TowerBoardTransport(model.identity, model.workspaceTransport, c, w, fileId, current.displayName).versions()
                    val version = versions.second.firstOrNull { it.id == versions.first } ?: error("No saved board version")
                    reference = BoardReference(fileId, version.id, version.number, version.sha256, 0, current.updatedAt, current.raw.bool("can_write") == true)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
            }
            reference?.let { board -> BoardEditor(model, board, current.displayName, provenance = { ArtefactProvenance(current) }) { exit?.invoke() } } ?: Text(error ?: "Loading whiteboard…")
        }
        ArtefactType.SYSTEM_FLOW -> SystemFlowFile(model, current)
        ArtefactType.HTML -> HtmlFileArtefact(model, current)
        null -> NativeFiles(model, fileId, direct = false)
    }
}


@Composable internal fun HtmlFileArtefact(model: AppModel, file: FileRecord, appMode: Boolean = false) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    var reload by remember(file.id) { mutableStateOf(0) }
    var html by remember(file.id) { mutableStateOf<String?>(null) }
    var verifiedFile by remember(file.id) { mutableStateOf(file) }
    var generic by remember(file.id) { mutableStateOf<GenericNapplet.Binding?>(null) }
    var versions by remember(file.id) { mutableStateOf<FileVersionList?>(null) }
    var selected by remember(file.id) { mutableStateOf<String?>(null) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var source by remember(file.id) { mutableStateOf<String?>(null) }
    var base by remember(file.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val draftFile = remember(c.id, w.id, file.id) { model.htmlDraftFile(file.id) }
    LaunchedEffect(source, base) {
        val draft = source; val version = base
        if (draft != null && version != null) withContext(Dispatchers.IO) {
            draftFile.parentFile?.mkdirs()
            val temporary = File(draftFile.parentFile, draftFile.name + ".pending")
            temporary.writeText(buildJsonObject { put("base_version_id", version); put("source", draft) }.toString())
            check(temporary.renameTo(draftFile)) { "Could not preserve the HTML draft" }
        }
    }
    LaunchedEffect(file.id, selected, reload) {
        try {
            html = null; generic = null; error = null
            model.sync.genericNappletRead(file.id) {
                val api = model.fileNappletAPI(c)
                verifiedFile = api.artefactFile(w.id,w.appNpub,file.id)
                versions = if(selected==null && verifiedFile.raw.obj("metadata")?.obj("napplet")?.get("version")==JsonPrimitive(2)) api.genericNappletHead(w.id,w.appNpub,file.id) else api.fileVersions(w.id, w.appNpub, file.id)
                val rendered = api.artefactHTML(w.id, w.appNpub, file.id, selected ?: versions?.currentVersionId)
                generic = GenericNapplet.parse(rendered, verifiedFile.raw.obj("metadata") ?: JsonObject(emptyMap()))
                val manifest = nappletManifest(rendered)
                val bound = manifest?.let { runCatching { FileNapplet.binding(file.raw.getValue("metadata").jsonObject, it) }.getOrNull() }
                val legacyReport=manifest?.let { runCatching { WorkspaceReport.binding(file.raw.getValue("metadata").jsonObject,it) }.getOrNull() }
                require(generic!=null || bound!=null || legacyReport!=null || !GenericNapplet.claimsRuntime(rendered,verifiedFile.raw.obj("metadata") ?: JsonObject(emptyMap()))) { "This napplet declaration is invalid or unsupported." }
                html = if (selected == null && (generic != null || bound != null || manifest?.let { runCatching { WorkspaceReport.binding(file.raw.getValue("metadata").jsonObject, it) }.getOrNull() } != null)) {
                    val version = versions!!.versions.first { it.id == versions!!.currentVersionId }
                    require(version.contentType?.substringBefore(';') == "text/html" && version.sizeBytes in 1..FileNapplet.MAX_BYTES.toLong() && version.sha256 != null)
                    val bytes = api.fileVersionBytes(w.id, w.appNpub, file.id, version)
                    FileNapplet.verifiedHTML(bytes, rendered, requireNotNull(version.sizeBytes), requireNotNull(version.sha256))
                } else rendered
                if(generic!=null) model.store.writer.upsertFiles(w.id,listOf(verifiedFile))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message; html = null }
    }
    if(error != null && (verifiedFile.raw.obj("metadata")?.obj("napplet")?.get("version") == JsonPrimitive(2) || generic != null)) {
        Text("This napplet is invalid or unavailable. Close and reopen to verify it.", Modifier.padding(16.dp)); return
    }
    if(selected==null && source==null && html!=null && generic!=null && versions?.currentVersionId!=null) {
        key(model.activeBoundaryId,file.id,versions!!.currentVersionId,generic) {
            GenericNappletHost(model,verifiedFile,html!!,generic!!,versions!!.currentVersionId!!) { reload++ }
        }
        return
    }
    val declaration = html?.let(::nappletManifest)
    val binding = declaration?.let { m -> runCatching { FileNapplet.binding(file.raw.getValue("metadata").jsonObject, m) }.getOrNull() }
    val currentVersion = versions?.currentVersionId
    val reportBinding=declaration?.let { runCatching { WorkspaceReport.binding(file.raw.getValue("metadata").jsonObject,it) }.getOrNull() }
    if(selected==null && source==null && reportBinding==w.id && declaration!=null && currentVersion!=null) {
        key(model.activeBoundaryId,file.id,currentVersion,reportBinding) { WorkspaceReportHost(model,file,html!!,declaration,currentVersion) }
        return
    }
    if (selected == null && source == null && declaration != null && binding != null && currentVersion != null) {
        key(model.activeBoundaryId, file.id, currentVersion, binding) {
            FileNappletScreen(model, file, html!!, declaration, binding, currentVersion) { reload++ }
        }
        return
    }
    if (appMode) {
        val reportApp=file.raw.obj("metadata")?.obj("napplet")?.string("app_id")==WorkspaceReport.ID
        Text(error ?: if (html == null) { if(reportApp) "Opening Workspace Report…" else "Opening Daily Metrics…" }
            else if(reportApp) "This report is invalid or unavailable. No replacement was created."
            else "This tracker is invalid or unavailable. No replacement was created.", Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
        ArtefactProvenance(file)
        Row(Modifier.fillMaxWidth()) {
            RecordPin(model, PinRecord.ResourceType.FILE, file.id)
            var menu by remember { mutableStateOf(false) }
            Box { TextButton(onClick = { menu = true }, modifier = Modifier.testTag("artefacts.history")) { Text("Versions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Latest") }, onClick = { selected = null; menu = false })
                    versions?.versions?.asReversed()?.forEach { v -> DropdownMenuItem(text = { Text("v${v.versionNumber}") }, onClick = { selected = v.id; menu = false }) }
                }
            }
            TextButton(enabled = file.raw.bool("can_write") == true && !saving, onClick = {
                model.act {
                    try {
                        val restored = withContext(Dispatchers.IO) { if (draftFile.isFile) runCatching { Json.parseToJsonElement(draftFile.readText()).jsonObject }.getOrNull() else null }
                        if (restored != null) {
                            base = restored["base_version_id"]?.jsonPrimitive?.content
                            source = restored["source"]?.jsonPrimitive?.content
                            error = "Restored your unsaved source. Saving still checks the version it started from."
                            return@act
                        }
                        val list = model.towerAPI(c).fileVersions(w.id, w.appNpub, file.id)
                        val version = list.versions.first { it.id == (selected ?: list.currentVersionId) }
                        require(version.contentType?.substringBefore(';') == "text/html") { "This HTML manifest must be edited by its authoring tool." }
                        source = model.towerAPI(c).fileVersionBytes(w.id, w.appNpub, file.id, version).toString(Charsets.UTF_8); base = list.currentVersionId
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                }
            }, modifier = Modifier.testTag("artefacts.editSource")) { Text(if (selected == null) "Edit source" else "Restore source") }
        }
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        html?.let { key(file.id, selected, it) { studio.otherstuff.ambulando.app.web.SandboxedHtmlScreen(it, file.displayName) } }
        if (html == null && error == null) Text("Loading HTML…")
    }
    source?.let { text -> AlertDialog(onDismissRequest = { if (!saving) source = null }, title = { Text("Edit HTML source") }, text = {
        Column { OutlinedTextField(text, { source = it }, modifier = Modifier.heightIn(max = 400.dp).testTag("artefacts.source")); error?.let { Text(it) } }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        val draft = source ?: return@TextButton; val expected = base ?: return@TextButton
        saving = true
        model.act {
            try {
                val api = model.towerAPI(c)
                val objectId = api.store(w.id, w.appNpub, draft.toByteArray(), "text/html", "artefact.html", null) { }
                when (val result = api.addFileVersion(w.id, w.appNpub, file.id, expected, objectId, UUID.randomUUID().toString())) {
                    is VersionOutcome.Saved -> { withContext(Dispatchers.IO) { draftFile.delete() }; source = null; selected = null; versions = api.fileVersions(w.id, w.appNpub, file.id); html = api.artefactHTML(w.id, w.appNpub, file.id) }
                    is VersionOutcome.Conflict -> error = "A newer version was saved elsewhere. Your source is kept here; copy it before reloading."
                    is VersionOutcome.Denied -> error = "Permission denied. Your source is kept here."
                    is VersionOutcome.Retryable -> error = "Save failed. Your source is kept here; retry when connected."
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { saving = false }
        }
    }, modifier = Modifier.testTag("artefacts.saveSource")) { Text(if (saving) "Saving…" else "Save version") } }, dismissButton = { TextButton(enabled = !saving, onClick = { source = null }) { Text("Close (keep draft)") } }) }
}
