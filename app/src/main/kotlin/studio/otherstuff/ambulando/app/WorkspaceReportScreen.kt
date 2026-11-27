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
import studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntime

/** Apps owns discovery/setup; execution keeps the existing File host's authority gates. */
@Composable internal fun WorkspaceReportScreen(model: AppModel) {
    val runtime = model.active ?: return
    val readiness by runtime.readiness.collectAsState(runtime.readinessSnapshot())
    val services = studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    // An uncertain create must survive pause, relock, validation retries and generation changes.
    var setupUncertain by remember(runtime.boundary.id) { mutableStateOf(false) }
    key(runtime, services.unlocked) {
        ReportBootstrapScreen(model, runtime, readiness, services.unlocked, setupUncertain) { setupUncertain = it }
    }
}

@Composable private fun ReportBootstrapScreen(
    model: AppModel, runtime: WorkspaceRuntime, readiness: WorkspaceRuntime.Readiness,
    unlocked: Boolean, setupUncertain: Boolean, setSetupUncertain: (Boolean) -> Unit,
) {
    val c = runtime.connection; val w = runtime.workspace; val boundary = runtime.boundary.id
    val services = studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }
    var candidates by remember { mutableStateOf<List<FileRecord>?>(null) }
    var opened by remember { mutableStateOf<FileRecord?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    fun current() = alive && model.active === runtime && model.activeBoundaryId == boundary &&
        model.connection == c && (model.identityState as? studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked)?.npub == c.signerNpub &&
        model.workspace?.accessRemoved == false && !runtime.workspace.accessRemoved &&
        unlocked && services.unlocked && readiness.ready && runtime.readinessSnapshot() == readiness
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!current()) throw studio.otherstuff.ambulando.core.sync.WorkspacePaused()
        model.identity.requireSigner(c.signerNpub)
        currentCoroutineContext().ensureActive()
        if (!current()) throw studio.otherstuff.ambulando.core.sync.WorkspacePaused()
    }
    val local = remember(runtime) { LocalNappletContent(runtime.store,w,localContentOwner(c,w), { alive && model.active === runtime && services.unlocked && !w.accessRemoved }) }
    val locationKey="napplet.report.location.v1:${c.id}:${w.id}"
    LaunchedEffect(runtime) {
        val id=runtime.store.reader.state(locationKey)
        if(id!=null) local.code(id)?.let { opened=it.file }
    }
    val diagnostics = ReportDiagnostics(model.diagnostics, ::current)
    val api = model.fileNappletAPI(c, runtime.directTransport, ::checkCurrent)
    suspend fun discover(): List<FileRecord> {
        diagnostics.at(ReportStage.IDENTITY) { checkCurrent() }
        return api.workspaceReportInstances(w.id, w.appNpub, c.signerNpub, diagnostics).also { checkCurrent() }
    }
    suspend fun open(file: FileRecord) {
        val fresh = diagnostics.at(ReportStage.METADATA) {
            checkCurrent()
            api.artefactFile(w.id, w.appNpub, file.id).also { checkCurrent() }
        }
        diagnostics.at(ReportStage.METADATA_VALIDATION) {
            require(fresh.workspaceId == w.id && fresh.id == file.id && !fresh.archived &&
                fresh.raw.string("record_state") != "deleted" && !fresh.accessRemoved && ArtefactType.of(fresh) == ArtefactType.HTML)
        }
        checkCurrent()
        opened = fresh
        runtime.store.writer.setState(locationKey,fresh.id)
    }
    LaunchedEffect(refresh, readiness) {
        if (!readiness.ready || !unlocked) return@LaunchedEffect
        failure = null; busy = true
        var metadata = false
        try {
            val found = discover()
            checkCurrent(); candidates = found
            if(opened!=null && found.none { it.id==opened!!.id }) { opened=null; runtime.store.writer.setState(locationKey,null) }
            if (found.size == 1) { metadata = true; open(found.single()) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (currentCoroutineContext().isActive && current()) failure = if (metadata) "This report could not be opened. No replacement was created. Retry to check it again."
                else "Could not verify your existing reports. No replacement was created. Retry when access is available."
        } finally { if (currentCoroutineContext().isActive && current()) busy = false }
    }
    if (alive && model.active === runtime && unlocked && services.unlocked && !w.accessRemoved && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked) opened?.let { file ->
        key(boundary, file.id) { HtmlFileArtefact(model, file, appMode = true) }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Workspace Report", style = MaterialTheme.typography.titleLarge)
        when {
            !unlocked || w.accessRemoved || !services.unlocked -> Text("Unlock an accessible workspace to open Workspace Report.")
            !readiness.ready -> {
                if (readiness.issue != null) {
                    Text("This report has not been saved on this device yet. Reconnect and retry.", modifier = Modifier.testTag("apps.reportValidationError"))
                    TextButton(onClick = { scope.launch { if (alive && model.active === runtime) runtime.start(foreground = true, force = true) } }) { Text("Retry") }
                } else Text("Loading Workspace Report…", modifier = Modifier.testTag("apps.reportWaiting"))
            }
            busy -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking your private reports…") }
            failure != null -> {
                Text(failure!!, modifier = Modifier.testTag("apps.reportError"))
                // Retry is read-only even when an earlier create was uncertain.
                TextButton(onClick = { refresh++ }) { Text("Retry") }
            }
            candidates?.isEmpty() == true -> {
                if (setupUncertain) Text("Setup could not be confirmed. Check Note to self for saved Files before trying setup again.")
                else Text("Set up a read-only report in your private Note to self conversation. Normal NStack administration rules apply.")
                TextButton(enabled = current() && !setupUncertain, modifier = Modifier.testTag("apps.setupReport"), onClick = {
                    busy = true; failure = null
                    scope.launch {
                        try {
                            val existing = discover() // Complete fresh recheck on the explicit setup action.
                            checkCurrent()
                            if (existing.isNotEmpty()) {
                                candidates = existing
                                if (existing.size == 1) open(existing.single())
                            } else {
                                val html = withContext(Dispatchers.IO) { context.assets.open("napplets/workspace-report-v1.html").bufferedReader().use { it.readText() } }
                                checkCurrent()
                                setSetupUncertain(true)
                                val created = api.createWorkspaceReport(w.id, w.appNpub, c.signerNpub, html)
                                checkCurrent()
                                runtime.store.writer.upsertScopes(w.id, listOf(created.scope), complete = false)
                                checkCurrent()
                                runtime.store.writer.upsertChannels(w.id, created.scope.id, listOf(created.channel), complete = false)
                                checkCurrent()
                                runtime.store.writer.upsertFiles(w.id, listOf(created.code))
                                checkCurrent(); candidates = listOf(created.code)
                                open(created.code)
                                checkCurrent(); setSetupUncertain(false)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (currentCoroutineContext().isActive && current()) failure = "Setup could not be confirmed. No automatic retry was made. Retry only checks existing reports." }
                        finally { if (currentCoroutineContext().isActive && current()) busy = false }
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
                            catch (_: Exception) { if (currentCoroutineContext().isActive && current()) failure = "This report is unavailable. No replacement was created." }
                            finally { if (currentCoroutineContext().isActive && current()) busy = false }
                        }
                    }) { Text("${index + 1}. ${file.displayName} · ${file.id.take(8)}") }
                }
            }
        }
    }
}
