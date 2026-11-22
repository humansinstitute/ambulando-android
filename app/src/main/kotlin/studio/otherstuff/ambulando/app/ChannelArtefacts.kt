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

@Composable internal fun CreateRichArtefact(model: AppModel) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    var showing by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(ArtefactType.WHITEBOARD) }
    var title by remember { mutableStateOf("") }
    var channel by remember(model.context) { mutableStateOf(model.context.channelId) }
    val services = LocalAppServices.current
    TextButton(onClick = { showing = true }, enabled = services.unlocked && !w.accessRemoved, modifier = Modifier.testTag("artefacts.new")) { Text("New artefact") }
    if (showing) AlertDialog(onDismissRequest = { showing = false }, title = { Text("New artefact") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.testTag("artefacts.title"))
            Row { ArtefactType.entries.forEach { option -> FilterChip(type == option, { type = option }, label = { Text(option.label) }) } }
            model.channels.filter { !it.accessRemoved && (model.context.scopeId == null || it.scopeId == model.context.scopeId) }.forEach { row ->
                TextButton(onClick = { channel = row.id }) { Text((if (channel == row.id) "✓ " else "") + row.name) }
            }
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && model.channels.any { it.id == channel && !it.accessRemoved }, modifier = Modifier.testTag("artefacts.create"), onClick = {
            val destination = channel ?: return@TextButton
            val kind = type; val name = title.trim()
            model.act {
                val staged = "artefact-${UUID.randomUUID()}"
                withContext(Dispatchers.IO) { model.writes.staging.mkdirs(); File(model.writes.staging, staged).writeText(kind.initialContent()) }
                model.writes.submit(WriteIntent(WriteKind.FILE_UPLOAD, null, buildJsonObject {
                    put("channel_id", destination); put("display_name", "$name.${kind.extension}"); put("file_name", "artefact.${kind.extension}")
                    put("content_type", kind.mime); put("staged", staged); put("metadata", kind.metadata())
                }), c, w)
            }
            showing = false
        }) { Text("Create") }
    }, dismissButton = { TextButton(onClick = { showing = false }) { Text("Cancel") } })
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
            reference?.let { board -> BoardEditor(model, board, current.displayName) { exit?.invoke() } } ?: Text(error ?: "Loading whiteboard…")
        }
        ArtefactType.HTML -> HtmlFileArtefact(model, current)
        null -> NativeFiles(model, fileId, direct = false)
    }
}


@Composable internal fun HtmlFileArtefact(model: AppModel, file: FileRecord, appMode: Boolean = false) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    var reload by remember(file.id) { mutableStateOf(0) }
    var html by remember(file.id) { mutableStateOf<String?>(null) }
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
            html = null; error = null
            versions = model.towerAPI(c).fileVersions(w.id, w.appNpub, file.id)
            val api = model.towerAPI(c)
            val rendered = api.artefactHTML(w.id, w.appNpub, file.id, selected ?: versions?.currentVersionId)
            val manifest = nappletManifest(rendered)
            val bound = manifest?.let { runCatching { FileNapplet.binding(file.raw.getValue("metadata").jsonObject, it) }.getOrNull() }
            html = if (selected == null && bound != null) {
                val version = versions!!.versions.first { it.id == versions!!.currentVersionId }
                require(version.contentType?.substringBefore(';') == "text/html" && version.sizeBytes in 1..FileNapplet.MAX_BYTES.toLong() && version.sha256 != null)
                val bytes = api.fileVersionBytes(w.id, w.appNpub, file.id, version)
                FileNapplet.verifiedHTML(bytes, rendered, requireNotNull(version.sizeBytes), requireNotNull(version.sha256))
            } else rendered
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    val declaration = html?.let(::nappletManifest)
    val binding = declaration?.let { m -> runCatching { FileNapplet.binding(file.raw.getValue("metadata").jsonObject, m) }.getOrNull() }
    val currentVersion = versions?.currentVersionId
    if (selected == null && source == null && declaration != null && binding != null && currentVersion != null) {
        key(model.activeBoundaryId, file.id, currentVersion, binding) {
            FileNappletScreen(model, file, html!!, declaration, binding, currentVersion) { reload++ }
        }
        return
    }
    if (appMode) {
        Text(error ?: if (html == null) "Opening Daily Metrics…" else "This tracker is invalid or unavailable. No replacement was created.", Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
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
