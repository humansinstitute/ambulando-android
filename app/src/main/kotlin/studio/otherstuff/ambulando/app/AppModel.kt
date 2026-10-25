package studio.otherstuff.ambulando.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.sample.SampleTower
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import java.io.File

/** Retained session owner. UI observes SQLite; all requests use the bounded sync engine. */
class AppModel(val environment: AppEnvironment, private val androidContext: android.content.Context) : studio.otherstuff.ambulando.app.background.BackgroundEntryPoint {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val browser by lazy { studio.otherstuff.ambulando.app.browser.BrowserModel(androidContext) }
    val identity = environment.identityService()
    val store = LocalStore(environment.storeFile.parentFile!!)
    val engine = SyncEngine()
    private val sample = if (environment.isSample) SampleTower(File(environment.baseDir, "tower")) else null
    val transport: HttpTransport = sample ?: OkHttpTransport()
    val transfers = if (environment.isSample) null else File(environment.baseDir, "Transfers").let { directory ->
        BackgroundTransfers(directory, studio.otherstuff.ambulando.app.background.WorkTransferBackend(androidContext, directory)).also { it.activate() }
    }
    val profileSync = studio.otherstuff.ambulando.core.profiles.ProfileSync(store, engine, studio.otherstuff.ambulando.core.profiles.RelayProfileSource())
    val sync = WorkspaceSync(store, engine, identity, transport, transfers = transfers)
    private val stream = LiveStream(sync, sample?.events ?: OkHttpEventStreamTransport())
    var foreground = true; private set
    fun foreground(active: Boolean) = act {
        foreground = active
        browser.updateLocked(!active || identityState !is IdentityState.Unlocked)
        browser.tabs.forEach { if (active) it.webView.onResume() else it.webView.onPause() }
        if (active) { startStream(); refresh() } else stream.stop()
    }
    private suspend fun startStream() {
        val c = connection ?: return; val w = workspace ?: return
        if (foreground && identityState is IdentityState.Unlocked && !w.accessRemoved) stream.start(c, w)
    }
    override suspend fun backgroundRefresh(budgetMillis: Long): Boolean {
        if (foreground) return false
        val c = connection ?: return false
        return sync.backgroundRefresh(c, store.reader.syncableWorkspaces(c.id), listOf(PendingSender { outbox.kick(it) }, PendingSender { writes.kick(it) }), budgetMillis).result == BackgroundRefreshReport.Result.COMPLETED
    }
    override suspend fun finishSending(budgetMillis: Long): Boolean {
        if (identityState !is IdentityState.Unlocked) return false
        val c = connection ?: return false
        outbox.kick(c); writes.kick(c)
        return withTimeoutOrNull(budgetMillis) { engine.waitUntilIdle(); true } ?: false
    }
    val outbox = OutboxSender(store, engine, identity, transport, File(environment.baseDir, "attachments"), transfers)
    private val uploadTransfer = ComposerUploadTransfer(engine, identity, transport, transfers)
    val uploads = ComposerUploads(store, File(environment.baseDir, "composer-uploads"), Dispatchers.Main.immediate) { job ->
        val captured = store.reader.connections().firstOrNull { it.id == job.target.connectionId } ?: error("Connection removed")
        uploadTransfer.upload(job, captured)
    }
    val writes = WriteSender(store, engine, identity, transport, File(environment.baseDir, "uploads"), transfers)
    var identityState by mutableStateOf<IdentityState?>(null); private set
    var connection by mutableStateOf<ConnectionRecord?>(null); private set
    var workspaces by mutableStateOf(emptyList<WorkspaceRecord>()); private set
    var workspace by mutableStateOf<WorkspaceRecord?>(null); private set
    var scopes by mutableStateOf(emptyList<ScopeRecord>()); private set
    var channels by mutableStateOf(emptyList<ChannelRecord>()); private set
    var context by mutableStateOf(WorkContext.HOME); private set
    var section by mutableStateOf(AppSection.DECK)
    var notice by mutableStateOf<String?>(null); private set
    val canvasViewports = mutableStateMapOf<String, studio.otherstuff.ambulando.core.surfaces.CanvasViewport>()
    var editorBoard by mutableStateOf<Pair<studio.otherstuff.ambulando.core.surfaces.BoardReference, String>?>(null)
    var editorDoc by mutableStateOf<String?>(null)
    var showAgents by mutableStateOf(false)
    var showContext by mutableStateOf(false)
    var viewLocked by mutableStateOf(false)
    var appearance by mutableStateOf(studio.otherstuff.ambulando.app.ui.theme.AppearancePreference.SYSTEM)
    private var observation: Job? = null
    // Draft persistence and enqueue are ordered, so a late draft cannot resurrect already-sent text.
    private val composerCommands = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val composerWriter = viewModelScope.launch {
        for (command in composerCommands) try { command() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = e.message }
    }
    fun saveDraft(target: ChatTarget, text: String, mentions: List<Mention> = emptyList()) {
        composerCommands.trySend { store.writer.saveDraft(target, Draft(text, mentions)) }
    }
    fun send(target: ChatTarget, text: String, connection: ConnectionRecord, workspace: WorkspaceRecord, mentions: List<Mention> = emptyList(), accepted: () -> Unit) {
        composerCommands.trySend { outbox.send(text, mentions, target, connection, workspace); accepted() }
    }

    init { act {
        store.writer.resetInterruptedSends(); store.writer.resetInterruptedWrites()
        identity.resumeSession(); identityState = identity.state()
        if (sample != null) {
            if (identityState == IdentityState.None) {
                val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                try { identityState = identity.importIdentity(studio.otherstuff.ambulando.core.nostr.Bech32.encode("nsec", secret), "123456") }
                finally { secret.fill(0) }
            } else if (identityState is IdentityState.Locked) identityState = identity.unlock("123456")
            sample.prepare(identityState!!.npub!!)
        }
        restore()
        if (sample != null && connection == null) connect(sample.origin.url)
    } }

    fun clearNotice() { notice = null }
    fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = e.message ?: "Operation failed" }
    }
    suspend fun import(nsec: String, pin: String) { identityState = identity.importIdentity(nsec, pin); restore() }
    suspend fun unlock(pin: String) { identityState = identity.unlock(pin); restore(); refresh() }
    fun lock() = act { browser.updateLocked(true); browser.hide(); stream.stop(); engine.cancelAll(); identityState = identity.lock() }
    suspend fun descriptor(origin: String) = sync.checkDescriptor(TowerOrigin(origin))
    suspend fun connect(origin: String) {
        connection = sync.connect(TowerOrigin(origin)); observe(); sync.discover(connection!!)
    }
    fun removeIdentity() = act {
        browser.reset()
        stream.stop(); engine.cancelAll()
        store.reader.connections().forEach { clearConnection(it) }
        identityState = identity.remove()
        connection = null; workspace = null; workspaces = emptyList(); observation?.cancel()
    }
    fun disconnect() = act {
        browser.reset()
        stream.stop(); engine.cancelAll()
        connection?.let { clearConnection(it) }
        connection = null; workspace = null; workspaces = emptyList(); observation?.cancel()
        identity.disableBackgroundRead()
    }
    private suspend fun clearConnection(c: ConnectionRecord) {
        val workspaces = store.reader.workspaces(c.id).map { it.id }
        val stagedWrites = store.reader.writes(c.id, c.signerNpub).mapNotNull { it.string("staged") }
        val attachments = store.reader.outbox(c.id, c.signerNpub).flatMap { it.attachments }.map { it.staged }
        uploads.items.value.filter { it.target.connectionId == c.id }.forEach { uploads.remove(it.id) }
        androidx.work.WorkManager.getInstance(androidContext).cancelAllWorkByTag("ambulando.transfers")
        store.writer.removeConnection(c.id)
        withContext(Dispatchers.IO) {
            workspaces.forEach { File(File(androidContext.cacheDir, "AmbulandoFiles"), it).deleteRecursively() }
            stagedWrites.forEach { name -> File(writes.staging, name).takeIf { it.canonicalFile.parentFile == writes.staging.canonicalFile }?.delete() }
            outbox.staging?.let { dir -> attachments.forEach { name -> File(dir, name).takeIf { it.canonicalFile.parentFile == dir.canonicalFile }?.delete() } }
        }
    }
    private suspend fun restore() {
        showAgents = store.reader.state("settings.agents") == "true"
        showContext = store.reader.state("settings.context") == "true"
        appearance = runCatching { studio.otherstuff.ambulando.app.ui.theme.AppearancePreference.valueOf(store.reader.state("settings.appearance") ?: "SYSTEM") }.getOrDefault(studio.otherstuff.ambulando.app.ui.theme.AppearancePreference.SYSTEM)
        connection = store.reader.connections().firstOrNull { it.signerNpub == identityState?.npub }
        connection?.let { observe(); if (identityState is IdentityState.Unlocked) sync.discover(it) }
    }
    private fun observe() {
        observation?.cancel()
        val c = connection ?: return
        observation = viewModelScope.launch {
            store.liveQuery(matches = { true }) {
                val ws = store.reader.workspaces(c.id)
                val selected = ws.firstOrNull { it.id == workspace?.id }
                    ?: ws.firstOrNull { it.id == store.reader.state("workspace:${c.id}") } ?: ws.firstOrNull()
                Triple(ws, selected, selected?.let { studio.otherstuff.ambulando.core.context.ScopeOrdering.apply(store.reader.scopes(it.id), studio.otherstuff.ambulando.core.context.ScopeOrdering.decode(store.reader.state("scope-order:${it.id}"))) { it.id } to store.reader.channels(it.id) })
            }.collect { (ws, selected, navigation) ->
                val changed = workspace?.id != selected?.id
                workspaces = ws; workspace = selected
                scopes = navigation?.first ?: emptyList(); channels = navigation?.second ?: emptyList()
                if (changed) {
                    context = selected?.let { w ->
                        WorkContext.decode(store.reader.state("context:${c.id}:${w.id}"))
                    } ?: WorkContext.HOME
                    startStream(); refresh()
                }
            }
        }
    }
    fun selectWorkspace(w: WorkspaceRecord) = act {
        workspace = w; context = WorkContext.HOME
        store.writer.setState("workspace:${connection!!.id}", w.id)
        observe(); refresh()
    }
    fun selectContext(scopeId: String?, channelId: String?) = act {
        if (viewLocked) return@act
        context = WorkContext(scopeId, channelId)
        val c = connection ?: return@act; val w = workspace ?: return@act
        store.writer.setState("context:${c.id}:${w.id}", context.encoded())
        refresh()
    }
    fun refresh() {
        val c = connection ?: return; val w = workspace ?: return
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return
        act {
            sync.refreshWorkspace(c, w)
            context.channelId?.let { sync.catchUpChannel(c, w, it) }
            sync.refreshTasks(c, w, context.scopeId); sync.refreshDocs(c, w, context.scopeId)
            sync.refreshFiles(c, w, context.scopeId); sync.refreshInbox(c, w)
            if (!environment.isSample) profileSync.refresh(c, w.id)
            outbox.kick(c); writes.kick(c)
        }
    }
    fun markViewed(type: ViewStateRecord.ResourceType, id: String) = act {
        val c = connection ?: return@act; val w = workspace ?: return@act
        val state = store.reader.viewState(w.id, type, id) ?: return@act
        val table = when (type.wire) { "task" -> FlaggedTable.TASKS; "document" -> FlaggedTable.DOCS; else -> FlaggedTable.THREADS }
        if (!state.unread || w.accessRemoved || store.reader.isAccessRemoved(w.id, table, id)) return@act
        writes.submit(WriteIntent(WriteKind.VIEW_MARK, id, kotlinx.serialization.json.buildJsonObject {
            put("resources", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("resource_type", kotlinx.serialization.json.JsonPrimitive(type.wire)); put("resource_id", kotlinx.serialization.json.JsonPrimitive(id)); put("viewed_activity_version", kotlinx.serialization.json.JsonPrimitive(state.activityVersion))
            })))
        }), c, w)
        store.writer.upsertViewStates(w.id, listOf(state.copy(viewedActivityVersion = state.activityVersion)))
    }
    fun submit(intent: WriteIntent): Boolean {
        val c = connection ?: return false; val w = workspace ?: return false
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return false
        act { writes.submit(intent, c, w) }; return true
    }
}
