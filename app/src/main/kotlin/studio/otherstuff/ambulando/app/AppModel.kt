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

    /** Work that must finish even though the screen that asked for it is already gone (a recreated activity, for example). */
    fun launchDetached(block: suspend () -> Unit) { viewModelScope.launch(kotlinx.coroutines.NonCancellable) { block() } }
    val browser by lazy { studio.otherstuff.ambulando.app.browser.BrowserModel(androidContext) }
    val identity = environment.identityService()
    val store = LocalStore(environment.storeFile.parentFile!!)
    val diagnostics = DiagnosticsLog(file = File(environment.baseDir, "diagnostics/connection.json"))
    val engine = SyncEngine(retryEvent = diagnostics::retry)
    private val sample = if (environment.isSample) SampleTower(File(environment.baseDir, "tower")) else null
    val transport = ObservedTransport(sample ?: OkHttpTransport(), diagnostics)
    /** The in-process synthetic Tower of sample-data mode (null otherwise); connected tests move its documents as "someone else". */
    internal val sampleTower: SampleTower? get() = sample
    val transfers = if (environment.isSample) null else File(environment.baseDir, "Transfers").let { directory ->
        BackgroundTransfers(directory, studio.otherstuff.ambulando.app.background.WorkTransferBackend(androidContext, directory)).also { it.activate() }
    }
    val profileSync = studio.otherstuff.ambulando.core.profiles.ProfileSync(store, engine, studio.otherstuff.ambulando.core.profiles.RelayProfileSource())
    val sync = WorkspaceSync(store, engine, identity, transport, transfers = transfers)
    private val stream = LiveStream(sync, sample?.events ?: OkHttpEventStreamTransport())
    private val networkReturn = NetworkReturn(androidContext, viewModelScope) {
        if (foreground && identityState is IdentityState.Unlocked) { startStream(); refresh() }
    }
    var foreground = true; private set
    fun foreground(active: Boolean) = act {
        foreground = active
        browser.updateLocked(!active || identityState !is IdentityState.Unlocked)
        browser.tabs.forEach { if (active) it.webView.onResume() else it.webView.onPause() }
        runStructureTicker(active)
        if (active) { networkReturn.start(); startStream(); refresh() } else { networkReturn.stop(); stream.stop() }
    }
    private var structureTicker: kotlinx.coroutines.Job? = null
    /**
     * While the app is in front, the sidebar's scopes and channels are re-read every minute even when the event stream
     * looks healthy, so a missed hint (a grant change, an archived channel) cannot leave the sidebar stale until restart.
     */
    private fun runStructureTicker(active: Boolean) {
        structureTicker?.cancel(); structureTicker = null
        if (!active) return
        structureTicker = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(STRUCTURE_RECONCILE_MILLIS)
                val c = connection; val w = workspace
                if (foreground && c != null && w != null && !w.accessRemoved && identityState is IdentityState.Unlocked) {
                    try { sync.refreshWorkspace(c, w, SyncPriority.BACKGROUND) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the next tick tries again */ }
                }
            }
        }
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
    /** Sent text attachments: version history, offline cache and durable local edits. */
    private val textEdits = TextEditStore(File(environment.baseDir, "text-edits"))
    private val textServices = mutableMapOf<String, TextAttachmentService>()
    fun textAttachments(c: ConnectionRecord, workspaceId: String): TextAttachmentService {
        val key = TextCache.scopeKey(c.id, c.origin, c.signerNpub, workspaceId)
        return textServices.getOrPut(key) { TextAttachmentService(textEdits, TextCache(File(File(environment.baseDir, "text-cache-v2"), key))) }
    }
    val textAttachments: TextAttachmentService get() = textAttachments(requireNotNull(connection), requireNotNull(workspace).id)
    fun towerAPI(c: ConnectionRecord) = TowerAPI(TowerClient(TowerOrigin(c.origin), transport, identity), transfers)
    val writes = WriteSender(store, engine, identity, transport, File(environment.baseDir, "uploads"), transfers)
    /** Edit leases and document saves for every open editor (and background link binding), shared so one session keeps one lease. */
    val documents = studio.otherstuff.ambulando.core.docs.DocumentSaveRunner(store, engine, identity, transport, transfers)
    /** Bumped whenever the account or workspace in front changes, so a slow wiki step cannot act in the wrong place. */
    var wikiContextEpoch = 0; private set
    init { writes.afterWikiCreate = { c, item, created -> bindCreatedWikiPage(c, item, created) } }

    /**
     * A page made by "create and link" exists on Tower: its id goes into the document that asked for it, before the creation write
     * is retired. An open editor on that document (of this account) receives the link itself and stores it as a durable draft;
     * otherwise the link goes through the draft-first save path. Anything that leaves the link unrecorded throws, which keeps the
     * write queued with the page's location; the retry finds the page already made and never makes a second one.
     */
    private suspend fun bindCreatedWikiPage(c: ConnectionRecord, item: WriteItem, created: DocRecord) {
        val epoch = wikiContextEpoch
        if (item.connectionId != c.id || item.origin != c.origin || item.signerNpub != c.signerNpub || created.workspaceId != item.workspaceId) {
            throw studio.otherstuff.ambulando.core.docs.WikiBindError.NotReady
        }
        val source = item.string(studio.otherstuff.ambulando.core.docs.WikiCreate.SOURCE_KEY) ?: return
        val title = item.string(studio.otherstuff.ambulando.core.docs.WikiCreate.TITLE_KEY) ?: return
        val workspace = store.reader.workspaces(c.id).firstOrNull { it.id == item.workspaceId && it.appNpub == item.appNpub }
            ?: throw studio.otherstuff.ambulando.core.docs.WikiBindError.NotReady
        val payload = item.payloadObject
        var changedBySomeoneElse = false
        val editor = studio.otherstuff.ambulando.core.docs.WikiEditors.editor(c.id, workspace.id, source)
        if (editor != null) {
            // The editor's own thread owns its text; the binding runs there.
            val outcome = withContext(Dispatchers.Main.immediate) {
                editor.bindWiki(title, created.id, payload.flexInt(studio.otherstuff.ambulando.core.docs.WikiCreate.OCCURRENCE_KEY),
                    payload.flexInt(studio.otherstuff.ambulando.core.docs.WikiCreate.EXPECTED_COUNT_KEY))
            }
            when (outcome) {
                studio.otherstuff.ambulando.core.docs.WikiEditorBinding.BOUND, studio.otherstuff.ambulando.core.docs.WikiEditorBinding.NOTHING_TO_BIND -> {}
                studio.otherstuff.ambulando.core.docs.WikiEditorBinding.NO_LONGER_REQUESTED -> changedBySomeoneElse = true
                studio.otherstuff.ambulando.core.docs.WikiEditorBinding.FAILED -> throw studio.otherstuff.ambulando.core.docs.WikiBindError.NotReady
            }
        } else {
            changedBySomeoneElse = studio.otherstuff.ambulando.core.docs.WikiBinder.complete(item, created, c, workspace, store,
                studio.otherstuff.ambulando.core.docs.RunnerAuthority(documents, c, workspace)) == studio.otherstuff.ambulando.core.docs.WikiBindOutcome.NoLongerRequested
        }
        // An offscreen workspace may finish its durable binding, but cannot navigate or show notices in another.
        if (epoch != wikiContextEpoch || connection?.id != c.id || workspace.id != this.workspace?.id || workspace.appNpub != this.workspace?.appNpub) return
        if (changedBySomeoneElse) {
            notice = "“$title” was created, but the text changed while it was being made, so the link was not changed automatically. Link it from the document."
            return
        }
        // The request registry and the sheet it opens belong to the main thread; the write drain runs elsewhere.
        withContext(Dispatchers.Main.immediate) {
            studio.otherstuff.ambulando.core.docs.WikiRequests.created(payload.stringOrNull(studio.otherstuff.ambulando.core.docs.WikiCreate.REQUEST_KEY), created, c.id)
        }
    }

    private fun kotlinx.serialization.json.JsonObject.flexInt(key: String): Int? = flexibleInt(this[key])
    private fun kotlinx.serialization.json.JsonObject.stringOrNull(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
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
    /** Where each conversation was left (by draft key) while the app runs, so returning to it keeps the reading position. */
    val chatPositions = HashMap<String, ChatPosition>()
    var editorBoard by mutableStateOf<Pair<studio.otherstuff.ambulando.core.surfaces.BoardReference, String>?>(null)
    var editorDoc by mutableStateOf<String?>(null)
    /** A record the document editor asked to open on leaving (kind, id), consumed by the connected screen. */
    var pendingRecordOpen by mutableStateOf<Pair<String, String>?>(null)
    var showAgents by mutableStateOf(false)
    /** Set by the Activity at each start: whether the saved open section and record stacks are put back (see [AppEnvironment.restoresNavigation]). */
    var restoreNavigation = true
    /** The person's default for existing documents: View (the default) or Edit, which starts editing when one opens. */
    var documentOpensInEdit by mutableStateOf(false)
    /** The document whose automatic Edit already ran, so leaving the editor returns to its reader instead of re-entering. */
    var autoEditedDoc: String? = null
    var showContext by mutableStateOf(false)
    var viewLocked by mutableStateOf(false)
    var appearance by mutableStateOf(studio.otherstuff.ambulando.app.ui.theme.AppearancePreference.SYSTEM)
    private var observation: Job? = null
    // Draft persistence and enqueue are ordered, so a late draft cannot resurrect already-sent text.
    private val composerCommands = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val composerWriter = viewModelScope.launch {
        for (command in composerCommands) try { command() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = ConnectionFailure.userMessage(e) }
    }
    fun saveDraft(target: ChatTarget, text: String, mentions: List<Mention> = emptyList()) {
        composerCommands.trySend { store.writer.saveDraft(target, Draft(text, mentions)) }
    }
    /** The composer's staged text attachments are read in the same ordered step, so none added just before Send is missed. */
    fun send(target: ChatTarget, text: String, connection: ConnectionRecord, workspace: WorkspaceRecord, mentions: List<Mention> = emptyList(), accepted: () -> Unit) {
        composerCommands.trySend { outbox.send(text, mentions, target, connection, workspace, store.reader.draft(target)?.attachments ?: emptyList()); accepted() }
    }
    /** Pasted text becomes a staged `.txt` attachment (ordered with draft saves and sends). */
    fun addPastedText(target: ChatTarget, text: String, added: (PendingAttachment) -> Unit) {
        composerCommands.trySend { added(outbox.addDraftText(target, text)) }
    }
    fun removeDraftAttachment(target: ChatTarget, id: String) { composerCommands.trySend { outbox.removeDraftText(target, id) } }
    fun saveDraftAttachment(target: ChatTarget, id: String, text: String, filename: String, done: (Boolean) -> Unit) {
        composerCommands.trySend {
            try { done(outbox.replaceDraftText(target, id, text, filename) != null) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { done(false) }
        }
    }
    /** Finishes a saved local edit of a sent attachment and reports its stored state. */
    fun saveSentEdit(editId: String, done: (TextEdit) -> Unit) {
        val c = connection ?: return
        viewModelScope.launch {
            try {
                val edit = textEdits.get(editId) ?: return@launch
                require(edit.connectionId == c.id && edit.backendOrigin == c.origin) { "Connection changed" }
                done(textAttachments(c, edit.workspaceId).save(towerAPI(c), editId))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                notice = ConnectionFailure.userMessage(e)
                textEdits.get(editId)?.let { done(it.copy(message = "Could not persist this save. Your previous local copy is kept; retry.")) }
            }
        }
    }
    private suspend fun resumeTextEdits(c: ConnectionRecord) {
        for (edit in textEdits.list()) if (edit.connectionId == c.id && edit.backendOrigin == c.origin && !edit.localOnly && edit.state == TextEditState.PENDING) textAttachments(c, edit.workspaceId).save(towerAPI(c), edit.id)
    }

    init { File(androidContext.cacheDir, "diagnostics/connection.txt").delete(); act {
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

    /** Feedback about a notification action that did not apply as tapped (shown in the inbox until dismissed). */
    var notificationMessage by mutableStateOf<String?>(null); private set
    fun dismissNotificationMessage() { notificationMessage = null }
    fun showNotificationMessage(text: String) { notificationMessage = text }
    init {
        viewModelScope.launch {
            writes.notificationNotices.collect { n ->
                if (n.connectionId != connection?.id) return@collect
                notificationMessage = when (n.kind) {
                    NotificationNotice.Kind.CHANGED_ELSEWHERE -> "That notification had new activity, so your change was not applied. It is unread again."
                    NotificationNotice.Kind.REJECTED -> "Tower rejected that change and the notifications were reloaded."
                }
                val c = connection ?: return@collect; val w = workspaces.firstOrNull { it.id == n.workspaceId } ?: return@collect
                if (identityState is IdentityState.Unlocked) act { sync.refreshNotifications(c, w, SyncPriority.USER, force = true) }
            }
        }
    }
    /** Reloads the inbox now (the refresh button, or opening it). */
    fun refreshNotifications(force: Boolean = true) {
        val c = connection ?: return; val w = workspace ?: return
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return
        act { sync.refreshNotifications(c, w, SyncPriority.USER, force) }
    }
    /** Queues a read / dismiss / archive change for one notification, checked against the version the user saw. Needs the identity unlocked (signed write). */
    fun changeNotification(id: String, patch: studio.otherstuff.ambulando.core.transport.NotificationPatch, observed: ObservedNotification): Boolean {
        val c = connection ?: return false; val w = workspace ?: return false
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return false
        act { writes.submitNotificationState(c, w, id, patch, observed) }; return true
    }
    /** Applies one bulk change to what the user has seen (up to the observed sequence); newer arrivals are untouched. */
    fun bulkNotifications(op: studio.otherstuff.ambulando.core.transport.NotificationBulkOp, upToSeq: Long, category: String? = null): Boolean {
        val c = connection ?: return false; val w = workspace ?: return false
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return false
        act { writes.submitNotificationBulk(c, w, op, upToSeq, category) }; return true
    }
    fun loadArchivedNotifications() {
        val c = connection ?: return; val w = workspace ?: return
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return
        act { sync.loadArchivedNotifications(c, w) }
    }

    fun clearNotice() { notice = null }
    fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = ConnectionFailure.userMessage(e) }
    }
    suspend fun import(nsec: String, pin: String) { identityState = identity.importIdentity(nsec, pin); restore() }
    suspend fun unlock(pin: String) { identityState = identity.unlock(pin); restore(); refresh() }
    fun lock() = act { browser.updateLocked(true); browser.hide(); stream.stop(); engine.cancelAll(); documents.forgetAll(); wikiContextEpoch++; identityState = identity.lock() }
    suspend fun descriptor(origin: String) = sync.checkDescriptor(TowerOrigin(origin))
    suspend fun connect(origin: String) {
        connection = sync.connect(TowerOrigin(origin)); observe(); sync.discover(connection!!)
    }
    fun removeIdentity() = act {
        browser.reset()
        stream.stop(); engine.cancelAll(); documents.forgetAll(); wikiContextEpoch++
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
        val attachments = (store.reader.outbox(c.id, c.signerNpub).flatMap { it.attachments } + store.reader.draftAttachments(c.id)).map { it.staged }
        uploads.items.value.filter { it.target.connectionId == c.id }.forEach { uploads.remove(it.id) }
        androidx.work.WorkManager.getInstance(androidContext).cancelAllWorkByTag("ambulando.transfers")
        store.writer.removeConnection(c.id)
        textEdits.removeConnection(c.id)
        workspaces.forEach { id ->
            val key = TextCache.scopeKey(c.id, c.origin, c.signerNpub, id)
            textServices.remove(key)?.cache?.clear()
            File(File(environment.baseDir, "text-cache-v2"), key).deleteRecursively()
        }
        withContext(Dispatchers.IO) {
            workspaces.forEach { File(File(androidContext.cacheDir, "AmbulandoFiles"), it).deleteRecursively() }
            stagedWrites.forEach { name -> File(writes.staging, name).takeIf { it.canonicalFile.parentFile == writes.staging.canonicalFile }?.delete() }
            outbox.staging?.let { dir -> attachments.forEach { name -> File(dir, name).takeIf { it.canonicalFile.parentFile == dir.canonicalFile }?.delete() } }
        }
    }
    private suspend fun restore() {
        showAgents = store.reader.state("settings.agents") == "true"
        showContext = store.reader.state("settings.context") == "true"
        documentOpensInEdit = store.reader.state("settings.documentOpening") == "edit"
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
        // Open editors keep their drafts but lose authority; a slow wiki step cannot act in the workspace now in front.
        documents.forgetAll(); wikiContextEpoch++
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
    fun refresh(force: Boolean = false) {
        val c = connection ?: return; val w = workspace ?: return
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return
        act {
            sync.refreshWorkspace(c, w, force = force)
            context.channelId?.let { sync.catchUpChannel(c, w, it, force = force) }
            sync.refreshTasks(c, w, context.scopeId, force = force); sync.refreshDocs(c, w, context.scopeId, force = force)
            sync.refreshFiles(c, w, context.scopeId, force = force); sync.refreshInbox(c, w, force = force)
            sync.refreshNotifications(c, w, force = force)
            if (!environment.isSample) profileSync.refresh(c, w.id)
            outbox.kick(c); writes.kick(c)
            runCatching { resumeTextEdits(c) }.onFailure { if (it is CancellationException) throw it }
        }
    }
    fun retryConnection() = act {
        val c = connection ?: return@act
        if (identityState !is IdentityState.Unlocked) return@act
        sync.discover(c, force = true)
        startStream(); refresh(force = true)
    }
    fun clearDiagnostics() = act {
        withContext(Dispatchers.IO) {
            diagnostics.clear()
            File(androidContext.cacheDir, "diagnostics/connection.txt").delete()
        }
    }
    fun exportDiagnostics() = act {
        val file = withContext(Dispatchers.IO) {
            File(androidContext.cacheDir, "diagnostics/connection.txt").also {
                it.parentFile!!.mkdirs()
                it.writeText(diagnostics.export(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT))
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(androidContext, "${BuildConfig.APPLICATION_ID}.files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Diagnostics", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        androidContext.startActivity(android.content.Intent.createChooser(send, "Export diagnostics").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
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

/** How often the sidebar structure is re-read while the app is in front, whatever the event stream reports. */
private const val STRUCTURE_RECONCILE_MILLIS = 60_000L
