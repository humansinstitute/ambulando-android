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
import studio.otherstuff.ambulando.core.workspaces.*
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Retained session owner. UI observes SQLite; all requests use the bounded sync engine. */
class AppModel(val environment: AppEnvironment, private val androidContext: android.content.Context) : studio.otherstuff.ambulando.app.background.BackgroundEntryPoint {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Work that must finish even though the screen that asked for it is already gone (a recreated activity, for example). */
    fun launchDetached(block: suspend () -> Unit) { viewModelScope.launch(kotlinx.coroutines.NonCancellable) { block() } }
    val browser by lazy { studio.otherstuff.ambulando.app.browser.BrowserModel(androidContext) }
    val identity = environment.identityService()
    /** Connections, discovered workspace lists and presentation preferences. Never content, drafts or queues (those live in partitions). */
    val catalog = LocalStore(environment.storeFile.parentFile!!)
    val diagnostics = DiagnosticsLog(file = File(environment.baseDir, "diagnostics/connection.json"))
    /** The one bounded scheduler every workspace runtime shares (through its own scoped view). */
    val sharedEngine = SyncEngine(maxConcurrent = 4, reservedForeground = 1, maxPerGroup = 3, retryEvent = diagnostics::retry)
    private val sample = if (environment.isSample) SampleTower(File(environment.baseDir, "tower")) else null
    /** Sample data also has a second synthetic Tower serving the same workspace ids, so adding a Tower can be exercised on a device. */
    private val secondSample = if (environment.isSample) SampleTower(File(environment.baseDir, "tower2"), originUrl = "https://second.sample.invalid") else null
    private val sampleRouter = if (sample != null && secondSample != null) studio.otherstuff.ambulando.core.sample.SampleTowerRouter(listOf(sample, secondSample)) else null
    val transport = ObservedTransport(sampleRouter ?: OkHttpTransport(), diagnostics)
    internal val secondSampleTower: SampleTower? get() = secondSample
    /** The in-process synthetic Tower of sample-data mode (null otherwise); connected tests move its documents as "someone else". */
    internal val sampleTower: SampleTower? get() = sample
    val transfers = if (environment.isSample) null else File(environment.baseDir, "Transfers").let { directory ->
        BackgroundTransfers(directory, studio.otherstuff.ambulando.app.background.WorkTransferBackend(androidContext, directory)).also { it.activate() }
    }
    /** Descriptor reads and connecting a Tower use the catalog; workspace content never does. */
    val catalogSync = WorkspaceSync(catalog, sharedEngine, identity, transport, transfers = transfers)
    private val streamTransport = sampleRouter?.events ?: OkHttpEventStreamTransport()
    private val storePool = WorkspaceStorePool(catalog, File(environment.storeFile.parentFile!!, "Workspaces"))
    val runtimes = WorkspaceRuntimes(catalog, storePool) { hook ->
        RuntimeEnvironment(
            sharedEngine, identity, transport, streamTransport, transfers, File(environment.baseDir, "attachments"),
            File(environment.baseDir, "uploads"), File(environment.baseDir, "composer-uploads"),
            studio.otherstuff.ambulando.core.profiles.RelayProfileSource(), Dispatchers.Main.immediate, hook,
            afterWikiCreate = { runtime, c, item, created -> bindCreatedWikiPage(runtime, c, item, created) },
        )
    }
    private val networkReturn = NetworkReturn(androidContext, viewModelScope) {
        if (foreground && identityState is IdentityState.Unlocked) { resumeEnabled(force = true); refresh() }
    }
    var foreground = true; private set
    fun foreground(active: Boolean) = act {
        foreground = active
        browser.updateLocked(!active || identityState !is IdentityState.Unlocked)
        browser.tabs.forEach { if (active) it.webView.onResume() else it.webView.onPause() }
        runStructureTicker(active)
        runtimes.setForeground(active)
        if (active) { networkReturn.start(); resumeEnabled(); refresh() } else { networkReturn.stop() }
    }
    private var structureTicker: kotlinx.coroutines.Job? = null
    /**
     * While the app is in front, the sidebar's scopes and channels of the workspace in view are re-read every minute even when
     * the event stream looks healthy, so a missed hint (a grant change, an archived channel) cannot leave the sidebar stale
     * until restart. Other enabled workspaces rely on their own streams; nothing else polls.
     */
    private fun runStructureTicker(active: Boolean) {
        structureTicker?.cancel(); structureTicker = null
        if (!active) return
        structureTicker = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(STRUCTURE_RECONCILE_MILLIS)
                val rt = this@AppModel.active; val w = workspace
                if (foreground && rt != null && w != null && !w.accessRemoved && identityState is IdentityState.Unlocked) {
                    try { rt.sync.refreshWorkspace(rt.connection, w, SyncPriority.BACKGROUND) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the next tick tries again */ }
                }
            }
        }
    }

    private val refresher = studio.otherstuff.ambulando.app.background.BackgroundRefresher(catalog, runtimes, identity)
    /** Opportunistic background window: enabled workspaces take turns (the start rotates each window), each within a share of the budget. */
    override suspend fun backgroundRefresh(budgetMillis: Long): Boolean = if (foreground) false else refresher.refresh(budgetMillis)
    override suspend fun finishSending(budgetMillis: Long): Boolean = refresher.finishSending(budgetMillis)

    // MARK: The workspace in front. Every property below delegates to its runtime; nothing here is workspace-qualified by hand.

    /** Runtimes that exist, mirrored into snapshot state for the UI. */
    var runtimeMap by mutableStateOf(emptyMap<String, WorkspaceRuntime>()); private set
    /** The boundary id of the workspace in front (persisted; empty selection is an explicit state). */
    var activeBoundaryId by mutableStateOf<String?>(null); private set
    val active: WorkspaceRuntime? get() = activeBoundaryId?.let { runtimeMap[it] }
    val activeEntry: WorkspaceEntry? get() = activeBoundaryId?.let { id -> entries.firstOrNull { it.id == id } }
    private val unattached: LocalStore by lazy { LocalStore(File(androidContext.cacheDir, "unattached-store-${java.util.UUID.randomUUID()}")) }
    /** The partition of the workspace in front (an empty store when none is: the catalog's frozen legacy content is never shown). */
    val store: LocalStore get() = active?.store ?: unattached
    /** The scoped scheduler of the workspace in front (its status and keys are its own); the shared engine when none is. */
    val engine: SyncScheduler get() = active?.engine ?: sharedEngine
    val sync: WorkspaceSync get() = active?.sync ?: catalogSync
    val outbox: OutboxSender get() = requireNotNull(active) { "No workspace is open" }.outbox
    val writes: WriteSender get() = requireNotNull(active) { "No workspace is open" }.writes
    val uploads: ComposerUploads get() = requireNotNull(active) { "No workspace is open" }.uploads
    val profileSync: studio.otherstuff.ambulando.core.profiles.ProfileSync get() = requireNotNull(active?.profileSync) { "No workspace is open" }
    /** Edit leases and document saves for every open editor of the workspace in front. */
    val documents: studio.otherstuff.ambulando.core.docs.DocumentSaveRunner get() = requireNotNull(active) { "No workspace is open" }.documents
    /** Downloaded files of the workspace in front: a cache rooted at its boundary, so equal ids elsewhere never share bytes. */
    fun fileCache(): FileCache = FileCache(File(File(androidContext.cacheDir, "AmbulandoFiles"), requireNotNull(active) { "No workspace is open" }.boundary.id))
    /** Unsaved board edits of the workspace in front, in its boundary's own directory. */
    fun boardDrafts(): studio.otherstuff.ambulando.core.surfaces.BoardDraftStore = studio.otherstuff.ambulando.core.surfaces.BoardDraftStore(boardDraftDirectory(requireNotNull(active) { "No workspace is open" }))
    private fun boardDraftDirectory(runtime: WorkspaceRuntime) = File(File(environment.baseDir, "board-drafts"), runtime.boundary.id)
    /** Direct writes from screens (canvas, board, scopes, task tree): the workspace's own guarded, write-gated transport. */
    val workspaceTransport: HttpTransport get() = active?.directTransport ?: transport
    /** The workspace's own guarded read transport. */
    val readTransport: HttpTransport get() = active?.readTransport ?: transport
    /** Sent text attachments: version history, offline cache and durable local edits (connection- and workspace-qualified files). */
    private val textEdits = TextEditStore(File(environment.baseDir, "text-edits"))
    private val textServices = mutableMapOf<String, TextAttachmentService>()
    fun textAttachments(c: ConnectionRecord, workspaceId: String): TextAttachmentService {
        val key = TextCache.scopeKey(c.id, c.origin, c.signerNpub, workspaceId)
        return textServices.getOrPut(key) { TextAttachmentService(textEdits, TextCache(File(File(environment.baseDir, "text-cache-v2"), key))) }
    }
    val textAttachments: TextAttachmentService get() = textAttachments(requireNotNull(connection), requireNotNull(workspace).id)
    /** A Tower API for direct reads and writes of the workspace in front: its own guarded, write-gated transport only. */
    fun towerAPI(c: ConnectionRecord) = TowerAPI(TowerClient(TowerOrigin(c.origin), workspaceTransport, identity), transfers)
    /** Bumped whenever the account or workspace in front changes, so a slow wiki step cannot act in the wrong place. */
    var wikiContextEpoch = 0; private set

    /**
     * A page made by "create and link" exists on Tower: its id goes into the document that asked for it, before the creation write
     * is retired. An open editor on that document (of this account) receives the link itself and stores it as a durable draft;
     * otherwise the link goes through the draft-first save path. Anything that leaves the link unrecorded throws, which keeps the
     * write queued with the page's location; the retry finds the page already made and never makes a second one.
     */
    private suspend fun bindCreatedWikiPage(runtime: WorkspaceRuntime, c: ConnectionRecord, item: WriteItem, created: DocRecord) {
        val epoch = wikiContextEpoch
        if (item.connectionId != c.id || item.origin != c.origin || item.signerNpub != c.signerNpub || created.workspaceId != item.workspaceId) {
            throw studio.otherstuff.ambulando.core.docs.WikiBindError.NotReady
        }
        val source = item.string(studio.otherstuff.ambulando.core.docs.WikiCreate.SOURCE_KEY) ?: return
        val title = item.string(studio.otherstuff.ambulando.core.docs.WikiCreate.TITLE_KEY) ?: return
        val store = runtime.store
        if (runtime.connection.id != c.id) throw studio.otherstuff.ambulando.core.docs.WikiBindError.NotReady
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
                studio.otherstuff.ambulando.core.docs.RunnerAuthority(runtime.documents, c, workspace)) == studio.otherstuff.ambulando.core.docs.WikiBindOutcome.NoLongerRequested
        }
        // An offscreen workspace may finish its durable binding, but cannot navigate or show notices in another.
        if (epoch != wikiContextEpoch || activeBoundaryId != runtime.boundary.id) return
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
    /** Every connection of this identity (one per Tower). */
    var connections by mutableStateOf(emptyList<ConnectionRecord>()); private set
    /** Every workspace any of those connections lists, in the saved order, with its presentation and runtime. */
    var entries by mutableStateOf(emptyList<WorkspaceEntry>()); private set
    var preferences by mutableStateOf(WorkspacePreferences()); private set
    val enabledEntries: List<WorkspaceEntry> get() = entries.filter { it.enabled }
    /** The connection and workspace in front (null while every workspace is paused). */
    val connection: ConnectionRecord? get() = activeEntry?.connection
    val workspace: WorkspaceRecord? get() = activeEntry?.workspace
    /** Workspaces of the connection in front, for callers that only choose among a Tower's workspaces. */
    val workspaces: List<WorkspaceRecord> get() = connection?.let { c -> entries.filter { it.connection.id == c.id }.map { it.workspace } } ?: emptyList()
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
    /** The runtime that owns a target's connection and workspace, however the screen has moved since (never "whatever is in front"). */
    private fun runtimeFor(connectionId: String, workspaceId: String): WorkspaceRuntime? =
        runtimeMap.values.firstOrNull { it.connection.id == connectionId && it.workspace.id == workspaceId }
    private fun ownerOf(target: ChatTarget): WorkspaceRuntime = runtimeFor(target.connectionId, target.workspaceId) ?: error("That workspace is not open on this device")
    fun saveDraft(target: ChatTarget, text: String, mentions: List<Mention> = emptyList()) {
        composerCommands.trySend { ownerOf(target).store.writer.saveDraft(target, Draft(text, mentions)) }
    }
    /** The composer's staged text attachments are read in the same ordered step, so none added just before Send is missed. */
    fun send(target: ChatTarget, text: String, connection: ConnectionRecord, workspace: WorkspaceRecord, mentions: List<Mention> = emptyList(), accepted: () -> Unit) {
        composerCommands.trySend {
            val owner = runtimeMap.values.firstOrNull { it.boundary == WorkspaceBoundary.of(connection, workspace) } ?: error("That workspace is not open on this device")
            owner.outbox.send(text, mentions, target, connection, workspace, owner.store.reader.draft(target)?.attachments ?: emptyList()); accepted()
        }
    }
    /** Pasted text becomes a staged `.txt` attachment (ordered with draft saves and sends). */
    fun addPastedText(target: ChatTarget, text: String, added: (PendingAttachment) -> Unit) {
        composerCommands.trySend { added(ownerOf(target).outbox.addDraftText(target, text)) }
    }
    fun removeDraftAttachment(target: ChatTarget, id: String) { composerCommands.trySend { ownerOf(target).outbox.removeDraftText(target, id) } }
    fun saveDraftAttachment(target: ChatTarget, id: String, text: String, filename: String, done: (Boolean) -> Unit) {
        composerCommands.trySend {
            try { done(ownerOf(target).outbox.replaceDraftText(target, id, text, filename) != null) }
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

    init {
        File(androidContext.cacheDir, "diagnostics/connection.txt").delete()
        // Every runtime that appears gets its notification-notice collector; only the one in front may show a notice.
        viewModelScope.launch {
            runtimes.runtimes.collect { map ->
                runtimeMap = map
                for (entry in entries) entry.runtime = map[entry.id]
                for (runtime in map.values) watchNotices(runtime)
            }
        }
        act {
            identity.resumeSession(); identityState = identity.state()
            if (sample != null) {
                if (identityState == IdentityState.None) {
                    val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    try { identityState = identity.importIdentity(studio.otherstuff.ambulando.core.nostr.Bech32.encode("nsec", secret), "123456") }
                    finally { secret.fill(0) }
                } else if (identityState is IdentityState.Locked) identityState = identity.unlock("123456")
                sample.prepare(identityState!!.npub!!)
                secondSample?.prepare(identityState!!.npub!!)
            }
            restore()
            if (sample != null && connections.isEmpty()) connect(sample.origin.url)
        }
    }

    private val noticeWatchers = HashSet<String>()
    private fun watchNotices(runtime: WorkspaceRuntime) {
        if (!noticeWatchers.add(runtime.boundary.id + "|" + runtime.store.path)) return
        viewModelScope.launch {
            runtime.writes.notificationNotices.collect { n ->
                if (activeBoundaryId != runtime.boundary.id || n.connectionId != runtime.connection.id) return@collect
                notificationMessage = when (n.kind) {
                    NotificationNotice.Kind.CHANGED_ELSEWHERE -> "That notification had new activity, so your change was not applied. It is unread again."
                    NotificationNotice.Kind.REJECTED -> "Tower rejected that change and the notifications were reloaded."
                }
                if (identityState is IdentityState.Unlocked) act { runtime.sync.refreshNotifications(runtime.connection, runtime.workspace, SyncPriority.USER, force = true) }
            }
        }
    }

    /** Feedback about a notification action that did not apply as tapped (shown in the inbox until dismissed). */
    var notificationMessage by mutableStateOf<String?>(null); private set
    fun dismissNotificationMessage() { notificationMessage = null }
    fun showNotificationMessage(text: String) { notificationMessage = text }
    /** Reloads the inbox now (the refresh button, or opening it). */
    fun refreshNotifications(force: Boolean = true) {
        val rt = active ?: return; val w = workspace ?: return
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return
        act { rt.sync.refreshNotifications(rt.connection, w, SyncPriority.USER, force) }
    }
    /** Queues a read / dismiss / archive change for one notification, checked against the version the user saw. Needs the identity unlocked (signed write). */
    fun changeNotification(id: String, patch: studio.otherstuff.ambulando.core.transport.NotificationPatch, observed: ObservedNotification): Boolean {
        val rt = active ?: return false; val w = workspace ?: return false
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return false
        act { rt.writes.submitNotificationState(rt.connection, w, id, patch, observed) }; return true
    }
    /** Applies one bulk change to what the user has seen (up to the observed sequence); newer arrivals are untouched. */
    fun bulkNotifications(op: studio.otherstuff.ambulando.core.transport.NotificationBulkOp, upToSeq: Long, category: String? = null): Boolean {
        val rt = active ?: return false; val w = workspace ?: return false
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return false
        act { rt.writes.submitNotificationBulk(rt.connection, w, op, upToSeq, category) }; return true
    }
    fun loadArchivedNotifications() {
        val rt = active ?: return; val w = workspace ?: return
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return
        act { rt.sync.loadArchivedNotifications(rt.connection, w) }
    }

    fun clearNotice() { notice = null }
    fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = ConnectionFailure.userMessage(e) }
    }
    suspend fun import(nsec: String, pin: String) { identityState = identity.importIdentity(nsec, pin); restore() }
    suspend fun unlock(pin: String) { identityState = identity.unlock(pin); restore(); resumeEnabled(force = true); refresh() }
    /** Locking pauses every runtime (jobs, streams, leases); the enabled set is kept, so unlocking resumes exactly it. */
    fun lock() = act { browser.updateLocked(true); browser.hide(); runtimes.pauseAll(); sharedEngine.cancelAll(); wikiContextEpoch++; identityState = identity.lock() }
    suspend fun descriptor(origin: String) = catalogSync.checkDescriptor(TowerOrigin(origin))

    /** Connects a Tower for the unlocked identity (the first one, or another: each is its own connection and boundary set). */
    suspend fun connect(origin: String) {
        val c = catalogSync.connect(TowerOrigin(origin))
        connections = (connections.filter { it.id != c.id } + c)
        startCatalogObserver()
        runtimes.discover(c, SyncPriority.USER, true)
    }

    /** Disconnects every Tower of this identity and removes the identity. Local recovery copies are deleted with it. */
    fun removeIdentity() = act {
        browser.reset()
        runtimes.pauseAll(); sharedEngine.cancelAll(); wikiContextEpoch++
        for (c in catalog.reader.connections()) clearConnection(c)
        withContext(Dispatchers.IO) {
            File(environment.storeFile.parentFile!!, "Workspaces").deleteRecursively()
            // Unsaved drawings of this identity (the flat folder of earlier builds and every boundary's own folder).
            File(environment.baseDir, "board-drafts").deleteRecursively()
        }
        catalog.writer.setState(WorkspacePreferences.STATE_KEY, null); catalog.writer.setState(WorkspaceCatalog.SELECTED_KEY, null)
        identityState = identity.remove()
        stopAllEntries(); entries = emptyList(); connections = emptyList(); activeBoundaryId = null; preferences = WorkspacePreferences()
        catalogObservation?.cancel(); catalogObservation = null
    }
    /** Disconnects the Tower of the workspace in front (Settings). */
    fun disconnect() = act {
        val c = connection ?: return@act
        disconnect(c)
    }
    /** Disconnects one Tower: its jobs, observers and leases stop first, its partitions are retired, a reconnect starts fresh. */
    suspend fun disconnect(c: ConnectionRecord) {
        browser.reset()
        clearConnection(c)
        identity.disableBackgroundRead()
        connections = connections.filter { it.id != c.id }
        val removedIds = entries.filter { it.connection.id == c.id }.map { it.id }.toSet()
        entries.filter { it.id in removedIds }.forEach { stopEntry(it) }
        entries = entries.filter { it.id !in removedIds }
        preferences = removedIds.fold(preferences) { p, id -> p.withEnabled(id, false).copy(order = p.order - id, collapsed = p.collapsed - id, colours = p.colours - id) }
        WorkspaceCatalog.save(catalog, preferences)
        if (activeBoundaryId in removedIds) { activeBoundaryId = null; chooseFallbackActive() }
    }

    /** Deletes what is outside SQLite for a connection (staged bytes, text caches, file caches), then retires its runtimes and partitions. */
    private suspend fun clearConnection(c: ConnectionRecord) {
        val listed = storeWorkspaces(c)
        val stagedWrites = ArrayList<String>(); val attachments = ArrayList<String>()
        for (w in listed) {
            // Names of staged files live in the partition's rows; read them before the partition is retired.
            val partition = runCatching { storePool.store(c, w) }.getOrNull() ?: continue
            stagedWrites += partition.reader.writes(c.id, c.signerNpub).mapNotNull { it.string("staged") }
            attachments += (partition.reader.outbox(c.id, c.signerNpub).flatMap { it.attachments } + partition.reader.draftAttachments(c.id)).map { it.staged }
        }
        runtimeMap.values.filter { it.connection.id == c.id }.forEach { rt -> rt.uploads.items.value.forEach { rt.uploads.remove(it.id) } }
        androidx.work.WorkManager.getInstance(androidContext).cancelAllWorkByTag("ambulando.transfers")
        runtimes.removeConnection(c)
        textEdits.removeConnection(c.id)
        val boundaries = listed.map { WorkspaceBoundary.of(c, it) }
        listed.forEach { w ->
            val key = TextCache.scopeKey(c.id, c.origin, c.signerNpub, w.id)
            textServices.remove(key)?.cache?.clear()
            File(File(environment.baseDir, "text-cache-v2"), key).deleteRecursively()
        }
        withContext(Dispatchers.IO) {
            boundaries.forEach {
                File(File(androidContext.cacheDir, "AmbulandoFiles"), it.id).deleteRecursively()
                File(File(environment.baseDir, "board-drafts"), it.id).deleteRecursively()
            }
            val writeStaging = File(environment.baseDir, "uploads")
            stagedWrites.forEach { name -> File(writeStaging, name).takeIf { it.canonicalFile.parentFile == writeStaging.canonicalFile }?.delete() }
            val chatStaging = File(environment.baseDir, "attachments")
            attachments.forEach { name -> File(chatStaging, name).takeIf { it.canonicalFile.parentFile == chatStaging.canonicalFile }?.delete() }
        }
    }
    private suspend fun storeWorkspaces(c: ConnectionRecord): List<WorkspaceRecord> = catalog.reader.workspaces(c.id)

    // MARK: Catalog → entries, preferences and runtimes

    private var catalogObservation: Job? = null
    private val listingLock = kotlinx.coroutines.sync.Mutex()
    private var restoredPreferences = false

    private suspend fun restore() {
        showAgents = catalog.reader.state("settings.agents") == "true"
        showContext = catalog.reader.state("settings.context") == "true"
        documentOpensInEdit = catalog.reader.state("settings.documentOpening") == "edit"
        appearance = runCatching { studio.otherstuff.ambulando.app.ui.theme.AppearancePreference.valueOf(catalog.reader.state("settings.appearance") ?: "SYSTEM") }.getOrDefault(studio.otherstuff.ambulando.app.ui.theme.AppearancePreference.SYSTEM)
        val signer = identityState?.npub
        connections = catalog.reader.connections().filter { it.signerNpub == signer }
        if (connections.isNotEmpty()) {
            startCatalogObserver()
            if (identityState is IdentityState.Unlocked) connections.forEach { runtimes.discover(it, SyncPriority.VISIBLE, false) }
        }
    }

    /** The catalog (connections, workspace lists, preferences) drives the entries; changes are applied one at a time. */
    private fun startCatalogObserver() {
        if (catalogObservation?.isActive == true) return
        catalogObservation = viewModelScope.launch {
            // The value is the listing itself: a query that returns an unchanged constant would never publish a change.
            catalog.liveQuery(matches = { it.table == StoreTable.WORKSPACES || it.table == StoreTable.CONNECTIONS }) { WorkspaceCatalog.listings(catalog, identityState?.npub) }.collect {
                try { applyListings() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = "Could not open a workspace safely: ${ConnectionFailure.userMessage(e) ?: "try again"}" }
            }
        }
    }

    private suspend fun applyListings() = listingLock.withLock {
        val signer = identityState?.npub ?: return@withLock
        val listings = WorkspaceCatalog.listings(catalog, signer)
        connections = catalog.reader.connections().filter { it.signerNpub == signer }
        val existing = entries.associateBy { it.id }
        val next = ArrayList<WorkspaceEntry>()
        for (l in listings) {
            val entry = existing[l.id]?.also { it.connection = l.connection; it.workspace = l.workspace } ?: WorkspaceEntry(l.boundary, l.connection, l.workspace)
            next.add(entry)
        }
        for (gone in entries) if (next.none { it.id == gone.id }) stopEntry(gone)
        var prefs = WorkspaceCatalog.preferences(catalog)
        if (prefs == null && listings.isNotEmpty()) {
            prefs = WorkspaceCatalog.initialPreferences(catalog, listings)
            WorkspaceCatalog.save(catalog, prefs)
        }
        preferences = prefs ?: WorkspacePreferences()
        val ordered = preferences.sorted(next.map { it.id }).mapNotNull { id -> next.firstOrNull { it.id == id } }
        for (entry in ordered) {
            entry.enabled = entry.id in preferences.enabled
            entry.collapsed = entry.id in preferences.collapsed
            entry.colour = preferences.colour(entry.id)
        }
        entries = ordered
        for (entry in ordered) if (entry.enabled) ensureRuntime(entry)
        if (activeEntry?.enabled != true) chooseFallbackActive()
    }

    /** Opens (never fails over to the legacy catalog) the entry's partition and starts its runtime; read-only until unlocked. */
    private suspend fun ensureRuntime(entry: WorkspaceEntry, force: Boolean = false) {
        if (!entry.enabled) return
        try {
            val runtime = runtimes.enable(entry.connection, entry.workspace, force)
            entry.runtime = runtime
            startNav(entry, runtime)
            viewModelScope.launch { runCatching { studio.otherstuff.ambulando.core.surfaces.BoardDraftRestore.restore(runtime.store, File(environment.baseDir, "board-drafts"), boardDraftDirectory(runtime)) } }
            // The person's public-profile choice applies to every partition.
            runtime.profileSync?.let { runtime.store.writer.setState(studio.otherstuff.ambulando.core.profiles.ProfileSync.PREFERENCE_KEY, if (publicProfilesOn()) null else "off") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            notice = "Could not open ${entry.workspace.label} safely: ${ConnectionFailure.userMessage(e) ?: "try again"}"
        }
    }

    private suspend fun publicProfilesOn() = studio.otherstuff.ambulando.core.profiles.ProfileSync.isEnabled(catalog)

    private fun startNav(entry: WorkspaceEntry, runtime: WorkspaceRuntime) {
        if (entry.navJob?.isActive == true && entry.navStorePath == runtime.store.path) return
        entry.navJob?.cancel()
        entry.navStorePath = runtime.store.path
        val navTables = setOf(StoreTable.SCOPES, StoreTable.CHANNELS, StoreTable.VIEW_STATES, StoreTable.MEMBERS, StoreTable.APP_STATE, StoreTable.ACCESS, StoreTable.WORKSPACES, StoreTable.PROFILES)
        entry.navJob = viewModelScope.launch {
            try {
                runtime.store.liveQuery({ it.table in navTables }) { NavSnapshot.load(runtime.store, runtime.connection, runtime.workspace) }.collect { entry.nav = it }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* a retired store ends its own observation */ }
        }
    }

    private suspend fun stopEntry(entry: WorkspaceEntry) {
        entry.enabled = false
        entry.navJob?.cancel(); entry.navJob = null
        entry.runtime?.pause()
        entry.runtime = null
    }
    private suspend fun stopAllEntries() { entries.forEach { stopEntry(it) } }

    private suspend fun chooseFallbackActive() {
        val saved = WorkspaceCatalog.selected(catalog)
        val next = enabledEntries.firstOrNull { it.id == saved } ?: enabledEntries.firstOrNull()
        if (next != null) activate(next) else clearActive()
    }

    private fun clearActive() {
        observation?.cancel(); observation = null
        activeBoundaryId = null; scopes = emptyList(); channels = emptyList(); context = WorkContext.HOME
        wikiContextEpoch++
    }

    /** Resumes every enabled runtime (unlock, foreground, network return). Validation and catch-up precede any send. */
    fun resumeEnabled(force: Boolean = false) {
        if (identityState == null) return
        viewModelScope.launch {
            for (entry in enabledEntries) {
                try { ensureRuntime(entry, force); entry.runtime?.resumeWrites() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    // MARK: Workspace choices

    private val toggleLock = kotlinx.coroutines.sync.Mutex()

    fun setWorkspaceEnabled(entry: WorkspaceEntry, enabled: Boolean) {
        if (entry.enabled == enabled || entries.none { it === entry }) return
        entry.enabled = enabled
        preferences = preferences.withEnabled(entry.id, enabled)
        // The synchronous gates close before anything else, so a click can never be outrun by a request already on its way.
        if (!enabled) entry.runtime?.closeGates()
        act {
            // One change at a time, and the latest choice wins: a quick off then on (or the reverse) ends in what the person last
            // asked for, never in the earlier request's pause or start landing after the later one.
            toggleLock.withLock {
                WorkspaceCatalog.save(catalog, preferences)
                if (entry.enabled) {
                    ensureRuntime(entry, force = true)
                    if (activeEntry?.enabled != true) activate(entry)
                } else {
                    entry.navJob?.cancel(); entry.navJob = null
                    entry.runtime?.pause()
                    if (activeBoundaryId == entry.id) chooseFallbackActive()
                }
            }
        }
    }

    fun toggleWorkspaceCollapsed(entry: WorkspaceEntry) {
        entry.collapsed = !entry.collapsed
        preferences = preferences.withCollapsed(entry.id, entry.collapsed)
        act { WorkspaceCatalog.save(catalog, preferences) }
    }

    fun moveWorkspace(entry: WorkspaceEntry, offset: Int) {
        val index = entries.indexOfFirst { it === entry }
        if (index < 0) return
        val destination = (index + offset).coerceIn(0, entries.size - 1)
        val reordered = entries.toMutableList().also { java.util.Collections.swap(it, index, destination) }
        entries = reordered
        preferences = preferences.withOrder(reordered.map { it.id })
        act { WorkspaceCatalog.save(catalog, preferences) }
    }

    fun setWorkspaceColour(entry: WorkspaceEntry, colour: WorkspaceColour) {
        entry.colour = colour
        preferences = preferences.withColour(entry.id, colour)
        act { WorkspaceCatalog.save(catalog, preferences) }
    }

    /** Scope collapse inside one workspace; stored in that workspace's own partition. */
    fun toggleScopeCollapsed(entry: WorkspaceEntry, scopeId: String) {
        val runtime = entry.runtime ?: return
        val collapsed = entry.nav.collapsed.let { if (scopeId in it) it - scopeId else it + scopeId }
        entry.nav = entry.nav.copy(collapsed = collapsed)
        act { runtime.store.writer.setState("collapsed:${runtime.connection.id}:${runtime.workspace.id}", kotlinx.serialization.json.JsonArray(collapsed.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()) }
    }

    /** The one switch for public Nostr profiles (Settings): the catalog keeps the choice, every partition follows it. */
    fun setPublicProfiles(on: Boolean) = act {
        catalog.writer.setState(studio.otherstuff.ambulando.core.profiles.ProfileSync.PREFERENCE_KEY, if (on) null else "off")
        for (runtime in runtimeMap.values) runtime.profileSync?.setEnabled(on, runtime.connection)
        if (on) refresh()
    }

    /** Brings a workspace in front (enabling it first when it is paused) and optionally opens a scope or channel in it. */
    fun openWorkspace(entry: WorkspaceEntry, scopeId: String? = null, channelId: String? = null) = act {
        if (!entry.enabled) { entry.enabled = true; preferences = preferences.withEnabled(entry.id, true); WorkspaceCatalog.save(catalog, preferences); ensureRuntime(entry, force = true) }
        activate(entry)
        // An explicit open goes where it was asked to (Home when no scope or channel is named), as the old switcher did.
        if (activeBoundaryId == entry.id) selectContextNow(scopeId, channelId)
    }

    /** Compatibility entry point: choose a workspace of the Tower in front. */
    fun selectWorkspace(w: WorkspaceRecord) {
        val entry = entries.firstOrNull { it.connection.id == connection?.id && it.workspace.id == w.id && it.workspace.appNpub == w.appNpub } ?: return
        openWorkspace(entry)
    }

    /** Switches the content pane to [entry]'s runtime. Other enabled runtimes keep syncing; stale callbacks check [activeBoundaryId]. */
    private suspend fun activate(entry: WorkspaceEntry) {
        if (!entry.enabled) return
        ensureRuntime(entry)
        val runtime = entry.runtime ?: return
        if (activeBoundaryId == entry.id && observation?.isActive == true) return
        // Open editors keep their drafts but lose authority; a slow wiki step cannot act in the workspace now in front.
        active?.documents?.forgetAll(); wikiContextEpoch++
        // The new workspace's own scopes and channels are in place before it is in front, so nothing of the previous one (even
        // under identical ids) is drawn for it.
        loadNavigation(runtime).let { (s, c) -> scopes = s; channels = c }
        context = WorkContext.decode(runtime.store.reader.state(WorkContext.stateKey(runtime.connection.id, runtime.workspace.id))) ?: WorkContext.HOME
        activeBoundaryId = entry.id
        editorDoc = null; editorBoard = null; autoEditedDoc = null
        section = AppSection.DECK
        WorkspaceCatalog.saveSelected(catalog, entry.id)
        catalog.writer.setState("workspace:${runtime.connection.id}", runtime.workspace.id)
        observeActive(runtime)
        refresh()
    }

    private suspend fun loadNavigation(runtime: WorkspaceRuntime): Pair<List<ScopeRecord>, List<ChannelRecord>> {
        val w = runtime.workspace
        return studio.otherstuff.ambulando.core.context.ScopeOrdering.apply(runtime.store.reader.scopes(w.id), studio.otherstuff.ambulando.core.context.ScopeOrdering.decode(runtime.store.reader.state("scope-order:${w.id}"))) { it.id } to runtime.store.reader.channels(w.id)
    }

    private fun observeActive(runtime: WorkspaceRuntime) {
        observation?.cancel()
        observation = viewModelScope.launch {
            runtime.store.liveQuery(matches = { true }) { loadNavigation(runtime) }
                .collect { (s, c) -> if (activeBoundaryId == runtime.boundary.id) { scopes = s; channels = c } }
        }
    }

    fun selectContext(scopeId: String?, channelId: String?) = act {
        if (viewLocked) return@act
        selectContextNow(scopeId, channelId)
    }
    private suspend fun selectContextNow(scopeId: String?, channelId: String?) {
        val rt = active ?: return
        val boundary = rt.boundary
        context = WorkContext(scopeId, channelId)
        rt.store.writer.setState(WorkContext.stateKey(rt.connection.id, rt.workspace.id), context.encoded())
        if (activeBoundaryId == boundary.id) refresh()
    }

    /** Foreground refresh of the workspace in front. Other workspaces are kept current by their own streams. */
    fun refresh(force: Boolean = false) {
        val rt = active ?: return; val w = workspace ?: return
        val c = rt.connection
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return
        val boundary = rt.boundary
        act {
            rt.resumeWrites()
            val ctx = context
            rt.sync.refreshWorkspace(c, w, force = force)
            ctx.channelId?.let { rt.sync.catchUpChannel(c, w, it, force = force) }
            rt.sync.refreshTasks(c, w, ctx.scopeId, force = force); rt.sync.refreshDocs(c, w, ctx.scopeId, force = force)
            rt.sync.refreshFiles(c, w, ctx.scopeId, force = force); rt.sync.refreshInbox(c, w, force = force)
            rt.sync.refreshNotifications(c, w, force = force)
            if (!environment.isSample) rt.profileSync?.refresh(c, w.id)
            if (activeBoundaryId == boundary.id) { rt.kickSenders() }
            runCatching { resumeTextEdits(c) }.onFailure { if (it is CancellationException) throw it }
        }
    }
    fun retryConnection() = act {
        val c = connection ?: return@act
        if (identityState !is IdentityState.Unlocked) return@act
        runtimes.discover(c, SyncPriority.USER, true)
        resumeEnabled(force = true); refresh(force = true)
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
        val rt = active ?: return@act; val w = workspace ?: return@act
        val state = rt.store.reader.viewState(w.id, type, id) ?: return@act
        val table = when (type.wire) { "task" -> FlaggedTable.TASKS; "document" -> FlaggedTable.DOCS; else -> FlaggedTable.THREADS }
        if (!state.unread || w.accessRemoved || rt.store.reader.isAccessRemoved(w.id, table, id)) return@act
        rt.writes.submit(WriteIntent(WriteKind.VIEW_MARK, id, kotlinx.serialization.json.buildJsonObject {
            put("resources", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("resource_type", kotlinx.serialization.json.JsonPrimitive(type.wire)); put("resource_id", kotlinx.serialization.json.JsonPrimitive(id)); put("viewed_activity_version", kotlinx.serialization.json.JsonPrimitive(state.activityVersion))
            })))
        }), rt.connection, w)
        rt.store.writer.upsertViewStates(w.id, listOf(state.copy(viewedActivityVersion = state.activityVersion)))
    }
    fun submit(intent: WriteIntent): Boolean {
        val rt = active ?: return false; val w = workspace ?: return false
        if (identityState !is IdentityState.Unlocked || w.accessRemoved) return false
        act { rt.writes.submit(intent, rt.connection, w) }; return true
    }
}

/** How often the sidebar structure is re-read while the app is in front, whatever the event stream reports. */
private const val STRUCTURE_RECONCILE_MILLIS = 60_000L
private const val BACKGROUND_ROTATION_KEY = "background.rotation"
