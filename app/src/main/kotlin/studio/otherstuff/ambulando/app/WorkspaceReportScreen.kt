package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*

/** Apps owns discovery/setup; execution and every data operation keep the existing File host's authority gates. */
@Composable internal fun WorkspaceReportScreen(model: AppModel) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val store = model.store
    val boundary = model.activeBoundaryId
    val services = studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    var candidates by remember(boundary) { mutableStateOf<List<FileRecord>?>(null) }
    var opened by remember(boundary) { mutableStateOf<FileRecord?>(null) }
    var failure by remember(boundary) { mutableStateOf<String?>(null) }
    var busy by remember(boundary) { mutableStateOf(false) }
    var refresh by remember(boundary) { mutableIntStateOf(0) }
    var setupUncertain by remember(boundary) { mutableStateOf(false) }
    fun current() = model.activeBoundaryId == boundary && model.workspace?.accessRemoved == false && services.unlocked
    suspend fun open(file: FileRecord) {
        val fresh = model.fileNappletAPI(c).artefactFile(w.id, w.appNpub, file.id)
        require(!fresh.archived && fresh.raw.string("record_state") != "deleted" && !fresh.accessRemoved && ArtefactType.of(fresh) == ArtefactType.HTML)
        if (current()) opened = fresh
    }
    LaunchedEffect(boundary, refresh, services.unlocked, w.accessRemoved) {
        candidates = null; opened = null; failure = null
        if (!current()) { failure = "Unlock an accessible workspace to open Workspace Report."; return@LaunchedEffect }
        busy = true
        try {
            model.identity.requireSigner(c.signerNpub)
            val found = model.fileNappletAPI(c).workspaceReportInstances(w.id, w.appNpub, c.signerNpub)
            if (current()) { candidates = found; if (found.size == 1) open(found.single()) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure = "Could not verify your existing reports. No replacement was created. Retry when access is available." }
        finally { busy = false }
    }
    opened?.let { file ->
        key(boundary, file.id) { HtmlFileArtefact(model, file, appMode = true) }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Workspace Report", style = MaterialTheme.typography.titleLarge)
        when {
            busy -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking your private reports…") }
            failure != null -> {
                Text(failure!!, modifier = Modifier.testTag("apps.reportError"))
                if (!setupUncertain) TextButton(onClick = { refresh++ }) { Text("Retry") }
            }
            candidates?.isEmpty() == true -> {
                Text("Set up a read-only report in your private Note to self conversation. Normal NStack administration rules apply.")
                TextButton(enabled = current() && !setupUncertain, modifier = Modifier.testTag("apps.setupReport"), onClick = {
                    busy = true; failure = null
                    scope.launch {
                        try {
                            // Recheck at the explicit setup action: another device may have created a report meanwhile.
                            val api = model.fileNappletAPI(c)
                            val existing = api.workspaceReportInstances(w.id, w.appNpub, c.signerNpub)
                            if (!current()) return@launch
                            if (existing.isNotEmpty()) {
                                candidates = existing
                                if (existing.size == 1) open(existing.single())
                            } else {
                                val html = withContext(Dispatchers.IO) { context.assets.open("napplets/workspace-report-v1.html").bufferedReader().use { it.readText() } }
                                if (!current()) return@launch
                                model.identity.requireSigner(c.signerNpub)
                                setupUncertain = true
                                val created = api.createWorkspaceReport(w.id, w.appNpub, c.signerNpub, html)
                                if (current()) {
                                    store.writer.upsertScopes(w.id, listOf(created.scope), complete = false)
                                    store.writer.upsertChannels(w.id, created.scope.id, listOf(created.channel), complete = false)
                                    store.writer.upsertFiles(w.id, listOf(created.code))
                                    candidates = listOf(created.code)
                                    open(created.code)
                                    setupUncertain = false
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failure = if (setupUncertain) "Setup could not be confirmed. Check Note to self for saved Files before trying setup again." else "Could not verify your existing reports. No replacement was created." }
                        finally { busy = false }
                    }
                }) { Text("Set up Workspace Report") }
            }
            candidates != null -> {
                Text("Choose an existing report. No new report will be created.")
                candidates!!.forEachIndexed { index, file ->
                    TextButton(enabled = current(), modifier = Modifier.testTag("apps.chooseReport"), onClick = {
                        busy = true
                        scope.launch {
                            try { open(file) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failure = "This report is unavailable. No replacement was created." }
                            finally { busy = false }
                        }
                    }) { Text("${index + 1}. ${file.displayName} · ${file.id.take(8)}") }
                }
            }
        }
    }
}
