package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import studio.otherstuff.ambulando.core.transport.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.documentVersions
import studio.otherstuff.ambulando.core.transport.DocumentVersion
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.ArtefactType
import studio.otherstuff.ambulando.core.surfaces.artefactFile
import studio.otherstuff.ambulando.core.sync.*

internal class ThreadArtefactTab(val kind: String, val id: String, title: String) {
    var title by mutableStateOf(title)
    var open by mutableStateOf(true)
    var attached by mutableStateOf(true)
    var compactHeaderOwned by mutableStateOf(false)
    var showHistory: (() -> Unit)? = null
    var resumeDraft: (() -> Unit)? = null
    var parent: ThreadArtefactTab? = null
    var recoveryHeld by mutableStateOf(false)
    var sourceDirty: (() -> Boolean)? = null
    var saveSource: (suspend () -> Boolean)? = null
    var holdRecovery: (suspend () -> Boolean)? = null
    val publicationHeld get() = recoveryHeld || parent?.recoveryHeld == true
    val automaticPublicationAllowed get() = attached && parent?.attached != false && !publicationHeld
    fun sourceSaved() { recoveryHeld = false; parent?.sourceSaved() }
    var versionLabel by mutableStateOf("Loading revision…")
    var pinnedRevision by mutableStateOf<String?>(null)
    var selectedRevision by mutableStateOf<String?>(null)
    var viewport by mutableStateOf<String?>(null)
    var validate: (suspend (ArtefactChatReference) -> Boolean)? = null
    var reference by mutableStateOf<ArtefactChatReference?>(null)
    var prepare: (suspend () -> ArtefactChatReference)? = null
    var checkpoint: (suspend () -> Boolean)? = null
    /** Why this open view cannot show its content (read failed, offline-only copy). It is then excluded from Send context. */
    var unavailable by mutableStateOf<String?>(null)
    /** Source edits or held recovery that Send must still save or refuse, whatever the view shows. */
    val holdsUnsavedWork get() = publicationHeld || sourceDirty?.invoke() == true
    /**
     * Preserves pending editor changes before close, removal or exit. Only a composed editor holds unsaved state in memory
     * and registers [checkpoint]; a view that is loading, failed or read-only has nothing to lose, so it never blocks.
     */
    suspend fun preserve(): Boolean = checkpoint?.invoke() ?: true
}
internal class ThreadArtefacts {
    var membershipReady by mutableStateOf(false)
    var membershipObserved by mutableStateOf(false)
    val legacyMigrationRefs get() = if (membershipObserved) emptyList() else tabs.filter { it.open && it.attached }.map { ThreadArtefactMember(it.kind, it.id) }
    var membershipVersion by mutableStateOf(0L)
    var members by mutableStateOf<List<ThreadArtefactMember>>(emptyList())
    var requestOpen: ((String, String, String) -> Unit)? = null
    var refreshMembership: (suspend () -> Unit)? = null
    var removeMember: ((ThreadArtefactTab) -> Unit)? = null
    var listing by mutableStateOf(false)
    var chatPosition by mutableStateOf<ChatPosition?>(null)
    fun reconcile(row: ThreadArtefactMembershipSync.Row) {
        if (row.version < membershipVersion) return
        val canonical = ThreadArtefactMembership.read(row.metadata).orEmpty()
        if (ThreadArtefactMembership.KEY in row.metadata) membershipObserved = true
        members = canonical; membershipVersion = row.version; membershipReady = true
        tabs.forEach { tab ->
            val attached = ThreadArtefactMember(tab.kind, tab.id) in canonical
            if (tab.attached != attached && tab.open) tab.recoveryHeld = true
            tab.attached = attached
        }
        canonical.forEach { member ->
            if (tabs.none { it.kind == member.kind && it.id == member.id }) tabs.add(ThreadArtefactTab(member.kind, member.id, if (member.kind == "doc") "Loading document…" else "Loading artefact…").also { it.open = false; it.attached = true })
        }
        if (tabs.firstOrNull { it.id == selected }?.attached == false) discuss = false
        if (canonical.isEmpty() && tabs.none { it.open && !it.attached }) shown = false
    }
    val attachedTabs get() = tabs.filter { it.attached }
    val sendTabs get() = tabs.filter { it.open && it.attached && (it.unavailable == null || it.holdsUnsavedWork) }
    /** Open attachments Send leaves out because they cannot be read here; named in [notice] so the omission is visible. */
    val omittedTabs get() = tabs.filter { it.open && it.attached && it.unavailable != null && !it.holdsUnsavedWork }
    var notice by mutableStateOf<String?>(null)

    var checkpointChat: (() -> Unit)? = null
    var restored by mutableStateOf(false)
    val tabs = mutableStateListOf<ThreadArtefactTab>()
    var selected by mutableStateOf<String?>(null)
    var shown by mutableStateOf(false)
    var browsing by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var discuss by mutableStateOf(false)
    /** The exact [failure] text a membership refresh set, so a later success clears only its own warning. */
    private var refreshFailure: String? = null
    /**
     * Runs the membership refresh for foreground and manual retries. Cancellation (the view leaving composition) is not a
     * failure and propagates. A real failure names its cause without presuming the network; a success clears only the
     * warning this refresh raised, never an editor, save or attachment failure raised since.
     */
    suspend fun refreshTracked(): Boolean {
        val refresh = refreshMembership ?: return true
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Keep the cause useful but bounded: one line, never a raw server body.
            val cause = (e.message ?: e::class.simpleName).orEmpty().lineSequence().first().trim().take(120)
            val message = "Attachments refresh failed: $cause. Open Artefacts and use Refresh attachments to retry."
            failure = message; refreshFailure = message
            return false
        }
        if (failure == refreshFailure) failure = null
        refreshFailure = null
        return true
    }
    fun open(kind: String, id: String, title: String = "Artefact") {
        if (busy) return
        requestOpen?.let { it(kind, id, title); return }
        focus(kind, id, title)
    }
    fun focus(kind: String, id: String, title: String = "Artefact") {
        val k = if (kind == "document") "doc" else kind
        val existing = tabs.firstOrNull { it.id == id && it.kind == k }
        check(tabs.count { it.open } < 32 || existing?.open == true) { "Close a local view before opening another (32 maximum)." }
        val tab = existing ?: ThreadArtefactTab(k, id, title).also { tabs.add(it) }
        if (title != "Artefact") tab.title = title
        tab.open = true; selected = id; shown = true; discuss = true; browsing = false; listing = false
    }
    fun snapshot(): String = buildJsonObject {
        put("selected", selected); put("shown", shown); put("discuss", discuss); put("membershipObserved", membershipObserved)
        chatPosition?.let { position -> put("chatPosition", buildJsonObject { put("key", position.key?.toString()); put("offset", position.offset); put("follow", position.follow) }) }
        put("tabs", JsonArray(tabs.map { tab -> buildJsonObject {
            put("kind", tab.kind); put("id", tab.id); put("title", tab.title); put("open", tab.open); put("attached", tab.attached); put("recoveryHeld", tab.recoveryHeld)
            put("pinned", tab.pinnedRevision); put("revision", tab.selectedRevision); put("viewport", tab.viewport)
        } }))
    }.toString()
    fun restore(raw: String?, cachedMembership: ThreadArtefactMembershipSync.Row? = null) {
        if (raw != null) runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            membershipObserved = o["membershipObserved"]?.jsonPrimitive?.boolean ?: false
            (o["chatPosition"] as? JsonObject)?.let { position ->
                chatPosition = ChatPosition(position["key"]?.jsonPrimitive?.contentOrNull, position["offset"]!!.jsonPrimitive.int.coerceAtLeast(0), position["follow"]!!.jsonPrimitive.boolean)
            }
            o["tabs"]!!.jsonArray.sortedByDescending {
                if (it.jsonObject["open"]?.jsonPrimitive?.boolean != true) 0
                else if (it.jsonObject["id"] == o["selected"]) 2 else 1
            }.forEach { value -> val t = value.jsonObject
                val kind = t["kind"]!!.jsonPrimitive.content; val id = t["id"]!!.jsonPrimitive.content
                require(kind in listOf("doc", "file") && studio.otherstuff.ambulando.core.transport.isUUID(id))
                tabs.add(ThreadArtefactTab(kind, id, t["title"]!!.jsonPrimitive.content).also { tab ->
                    tab.open = t["open"]!!.jsonPrimitive.boolean && tabs.count { it.open } < 32
                    tab.attached = t["attached"]?.jsonPrimitive?.boolean ?: true
                    tab.recoveryHeld = t["recoveryHeld"]?.jsonPrimitive?.boolean ?: false
                    tab.pinnedRevision = t["pinned"]?.jsonPrimitive?.contentOrNull
                    tab.selectedRevision = t["revision"]?.jsonPrimitive?.contentOrNull
                    tab.viewport = t["viewport"]?.jsonPrimitive?.contentOrNull
                })
            }
            selected = o["selected"]?.jsonPrimitive?.contentOrNull?.takeIf { id -> tabs.any { it.id == id && it.open } }
            shown = selected != null && o["shown"]?.jsonPrimitive?.boolean == true
            discuss = selected != null && o["discuss"]?.jsonPrimitive?.boolean == true
        }.onFailure { failure = "Saved workspace could not be restored. Your editor drafts remain available." }
        if (cachedMembership != null) runCatching { reconcile(cachedMembership); membershipReady = false }
            .onFailure { failure = "Saved attachments could not be read. Refresh from the server." }
        restored = true
    }
    suspend fun prepare(target: ChatTarget): String? {
        check(!busy) { "An artefact operation is already running." }
        notice = omittedTabs.takeIf { it.isNotEmpty() }?.let { omitted -> "Sent without ${omitted.joinToString { it.title }}: unavailable on this device." }
        if (sendTabs.isEmpty()) return null
        try { refreshMembership?.invoke() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = "Send paused: attachments could not be confirmed (${e.message ?: e::class.simpleName}). Retry Send."; throw e }
        if (sendTabs.isEmpty()) return null
        busy = true; failure = null
        try {
            val preparing = sendTabs.toList()
            val refs = preparing.map { tab ->
                check(!tab.publicationHeld) { "${tab.title} has detached recovery. Use explicit Save source or resolve it in the editor before Send." }
                check(tab.attached) { "${tab.title} was removed from the thread. Retry Send." }
                (tab.prepare ?: error("${tab.title} is still opening. Retry Send."))().also { tab.reference = it }
            }
            preparing.zip(refs).forEach { (tab, ref) -> check(tab.attached && tab.validate?.invoke(ref) == true) { "${tab.title} changed during preparation. Retry Send." } }
            return if (!discuss && (!shown || refs.none { it.recordID == selected })) null else ArtefactChatContext.encode(target, shown, selected?.takeIf { id -> refs.any { it.recordID == id } } ?: refs.first().recordID, refs)
        } catch (e: Exception) { busy = false; failure = "Send paused: ${e.message}"; throw e }
    }
}
internal val LocalThreadArtefacts = staticCompositionLocalOf<ThreadArtefacts?> { null }
internal val LocalThreadPaneActive = staticCompositionLocalOf { true }
internal val LocalThreadArtefactTab = staticCompositionLocalOf<ThreadArtefactTab?> { null }

/** Rich File mentions retain their native reader path unless this panel supports an exact editor context. */
internal suspend fun routeThreadReference(kind: String, id: String, state: ThreadArtefacts,
    file: suspend (String) -> FileRecord, native: (String, String) -> Unit) {
    when (kind) {
        "doc", "document" -> state.open(kind, id)
        "file" -> {
            val record = file(id)
            if (ArtefactType.of(record) in listOf(ArtefactType.WHITEBOARD, ArtefactType.HTML)) state.open(kind, id, record.displayName)
            else native(kind, id)
        }
        else -> native(kind, id)
    }
}

/** Hidden children keep their full constraints and composition (including each WebView and chat scroll). No swipe recognizer. */
@Composable private fun RetainedPane(visible: Boolean, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize().clipToBounds().then(if (visible) Modifier else Modifier.semantics { hideFromAccessibility() })) { children, constraints ->
        val p = children.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) { p.forEach { it.placeRelative(if (visible) 0 else -constraints.maxWidth * 2, 0) } }
    }
}

@Composable internal fun ThreadArtefactLayout(model: AppModel, target: ChatTarget, state: ThreadArtefacts, chat: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val runtime = model.active ?: return
    if (runtime.connection.id != target.connectionId || runtime.workspace.id != target.workspaceId) return
    val key = "thread.artefacts.${target.draftKey}"
    LaunchedEffect(target, state) { if (!state.restored) {
        val raw = runtime.store.reader.state(key)
        val cached = runtime.store.reader.thread(target.workspaceId, requireNotNull(target.threadId))
        val metadata = cached?.raw?.get("metadata") as? JsonObject
        state.restore(raw, if (cached != null && metadata != null && ThreadArtefactMembership.KEY in metadata)
            ThreadArtefactMembershipSync.Row(cached.rowVersion, metadata) else null)
    } }
    if (!state.restored) { Text("Restoring thread workspace…"); return }
    val membership = remember(runtime, target, state) {
        ThreadArtefactMembershipSync(object : ThreadArtefactMembershipSync.Transport {
            fun boundary() { check(model.active === runtime && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked && !runtime.workspace.accessRemoved) { "Unlock the original workspace and retry." } }
            fun row(record: ThreadRecord): ThreadArtefactMembershipSync.Row {
                require(record.workspaceId == target.workspaceId && record.channelId == target.channelId && record.id == target.threadId)
                return ThreadArtefactMembershipSync.Row(record.rowVersion, record.raw["metadata"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            override suspend fun read(): ThreadArtefactMembershipSync.Row = model.engine.perform(WorkspaceSync.threadMembershipReadKey(target.workspaceId, requireNotNull(target.threadId)), SyncPriority.USER) {
                boundary()
                val record = model.towerAPI(runtime.connection).thread(target.workspaceId, runtime.workspace.appNpub, requireNotNull(target.threadId))
                boundary(); val result = row(record)
                runtime.store.writer.upsertThreads(target.workspaceId, listOf(record))
                result
            }
            override suspend fun write(base: ThreadArtefactMembershipSync.Row, metadata: JsonObject): ThreadArtefactMembershipSync.Row = model.engine.perform(WorkspaceSync.threadMembershipWriteKey(target.workspaceId, requireNotNull(target.threadId)), SyncPriority.USER) {
                boundary()
                val record = model.towerAPI(runtime.connection).updateThreadMetadata(target.workspaceId, runtime.workspace.appNpub, requireNotNull(target.threadId), base, metadata)
                boundary(); val result = row(record)
                runtime.store.writer.upsertThreads(target.workspaceId, listOf(record))
                result
            }
            override suspend fun verify(member: ThreadArtefactMember) {
                boundary()
                model.sync.threadArtefactTitle(runtime.connection, runtime.workspace, member)
                boundary()
            }
        })
    }
    suspend fun reconcileMembership(row: ThreadArtefactMembershipSync.Row) {
        val previous = state.tabs.filter { it.open }.associateWith { it.attached to it.publicationHeld }
        state.reconcile(row)
        previous.filter { (tab, before) -> tab.attached != before.first }.forEach { (tab, before) ->
            val alreadyHeld = before.second
            val preserved = tab.preserve()
            if (preserved && tab.publicationHeld && tab.sourceDirty?.invoke() == true && tab.holdRecovery != null) {
                if (tab.holdRecovery?.invoke() != true) state.failure = "${tab.title} recovery could not be held durably. Keep this view open and retry."
            }
            if (preserved && !alreadyHeld && tab.sourceDirty?.invoke() == false) tab.sourceSaved()
            if (!preserved) state.failure = "${tab.title} was detached. Keep this view open and retry preserving recovery before closing."
        }
    }
    suspend fun refreshMembership() {
        reconcileMembership(membership.refresh())
        state.attachedTabs.filter { !it.open }.forEach { tab ->
            val title = if (tab.kind == "doc") runtime.store.reader.doc(target.workspaceId, tab.id)?.title else runtime.store.reader.file(target.workspaceId, tab.id)?.displayName
            if (title != null) tab.title = title
        }
    }
    SideEffect {
        state.refreshMembership = { refreshMembership() }
        state.requestOpen = { kind, id, title -> scope.launch {
            if (!state.busy) {
                state.busy = true; state.failure = null
                try {
                    val member = ThreadArtefactMember(if (kind == "document") "doc" else kind, id.lowercase())
                    check(state.tabs.count { it.open } < 32 || state.tabs.any { it.id == member.id && it.open }) { "Close a local view before opening another (32 maximum)." }
                    reconcileMembership(membership.add(member)); state.focus(member.kind, member.id, title)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (e is ThreadArtefactMembershipSync.Uncertain) reconcileMembership(e.observed); state.failure = "Attachment not confirmed: ${e.message}. Your work is retained; retry when connected." }
                finally { state.busy = false }
            }
        } }
        state.removeMember = { tab -> scope.launch {
            if (!state.busy) {
                state.busy = true; state.failure = null
                try {
                    check(!tab.open || tab.preserve()) { "Preserve editor changes before removing this attachment." }
                    reconcileMembership(membership.remove(ThreadArtefactMember(tab.kind, tab.id)))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (e is ThreadArtefactMembershipSync.Uncertain) reconcileMembership(e.observed); state.failure = "Remove not confirmed: ${e.message}. Existing attachments and drafts are retained; retry." }
                finally { state.busy = false }
            }
        } }
    }
    DisposableEffect(state) { onDispose { state.requestOpen = null; state.refreshMembership = null; state.removeMember = null } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(membership) {
        try {
            val legacy = state.legacyMigrationRefs
            reconcileMembership(membership.migrate(legacy))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.failure = "Thread attachments could not be refreshed: ${e.message}. Retry from Artefacts." }
    }
    state.chatPosition?.let { model.chatPositions.putIfAbsent(target.draftKey, it) }
    val canonicalThread by remember(runtime, target) {
        runtime.store.liveQuery({ it.workspaceId == target.workspaceId }) { runtime.store.reader.thread(target.workspaceId, requireNotNull(target.threadId)) }
    }.collectAsState(null)
    LaunchedEffect(canonicalThread?.rowVersion, state.membershipReady) {
        canonicalThread?.takeIf { it.rowVersion > state.membershipVersion }?.let { record ->
            try { reconcileMembership(ThreadArtefactMembershipSync.Row(record.rowVersion, record.raw["metadata"] as? JsonObject ?: JsonObject(emptyMap()))) }
            catch (e: Exception) { state.failure = "Attachments update could not be read: ${e.message}." }
        }
    }
    LaunchedEffect(state.members, runtime) {
        state.attachedTabs.filter { !it.open }.forEach { tab ->
            try {
                val title = model.sync.threadArtefactTitle(runtime.connection, runtime.workspace, ThreadArtefactMember(tab.kind, tab.id))
                if (model.active === runtime && !tab.open) tab.title = title
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (!tab.open) tab.title = if (tab.kind == "doc") "Unavailable document" else "Unavailable artefact" }
        }
    }
    val snapshot = state.snapshot()
    LaunchedEffect(snapshot) { runtime.store.writer.setState(key, snapshot) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(state.shown) { focus.clearFocus(force = true); keyboard?.hide() }
    LaunchedEffect(state.busy) { if (state.busy) focus.clearFocus(force = true) }
    val exit = studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit.current
    suspend fun checkpoint(): Boolean {
        if (state.busy) return false
        state.checkpointChat?.invoke()
        state.busy = true
        return try {
            state.tabs.filter { it.open }.forEach { tab -> check(tab.preserve()) { "${tab.title} has not preserved pending changes. Retry closing." } }
            runtime.store.writer.setState(key, state.snapshot())
            true
        } catch (e: Exception) { state.failure = e.message; false }
        finally { state.busy = false }
    }
    androidx.activity.compose.BackHandler {
        if (state.shown) state.shown = false else scope.launch { if (checkpoint()) exit?.invoke() }
    }
    DisposableEffect(lifecycle, state) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) scope.launch { state.refreshTracked() }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.launchDetached {
                state.checkpointChat?.invoke()
                state.tabs.forEach { runCatching { it.preserve() } }
                runtime.store.writer.setState(key, state.snapshot())
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val services = LocalAppServices.current
    val intercepted = remember(services, state) {
        fun mention(kind: String, id: String) { scope.launch {
            try {
                routeThreadReference(kind, id, state, file = { fileId ->
                    check(model.active === runtime && services.unlocked) { "Unlock the original workspace before opening this file." }
                    model.sync.threadFileMetadata(runtime.connection, runtime.workspace, fileId).also {
                        check(model.active === runtime) { "Return to the original workspace." }
                    }
                }, native = { nativeKind, nativeId -> scope.launch { if (checkpoint()) services.openMention(nativeKind, nativeId) }; Unit })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.failure = "Reference could not be opened: ${e.message}. Existing attachments are unchanged." }
        } }
        AppServices(services.avatars, services.openUrl, services.open, services.focusThread, ::mention, services.unlocked, services.sample, services.viewImage)
    }
    CompositionLocalProvider(LocalThreadArtefacts provides state, LocalAppServices provides intercepted,
        studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit provides { scope.launch { if (checkpoint()) exit?.invoke() }; Unit }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RetainedPane(!state.shown) { CompositionLocalProvider(LocalThreadPaneActive provides !state.shown) { chat() } }
                RetainedPane(state.shown) {
                    Column(Modifier.fillMaxSize().background(studio.otherstuff.ambulando.app.ui.theme.Amb.colors.background)) {
                        if (state.tabs.any { it.id == state.selected && (!it.attached || it.publicationHeld) }) Column {
                            val recovering = state.tabs.first { it.id == state.selected }
                            Text(if (!recovering.attached) "Detached from thread. Recovery stays on this device; excluded from Send." else "Recovery held after removal. Explicitly save source or resolve it in the editor before Send.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                            Row {
                            TextButton(onClick = { scope.launch {
                                state.busy = true
                                try { check(recovering.saveSource?.invoke() == true) { "Resolve the source save in the editor; recovery is retained." } }
                                catch (e: Exception) { state.failure = e.message }
                                finally { state.busy = false }
                            } }, enabled = !state.busy && recovering.saveSource != null, modifier = Modifier.testTag("thread.artefacts.saveRecovery")) { Text("Save source") }
                            }
                        }
                        if (!compactThreadArtefact() || state.tabs.firstOrNull { it.id == state.selected }?.compactHeaderOwned != true) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = { state.shown = false }, enabled = !state.busy, modifier = Modifier.testTag("thread.chat")) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to chat")
                            }
                            Text(state.tabs.firstOrNull { it.id == state.selected }?.let { "${it.title} · ${it.versionLabel}" } ?: "Open an artefact", Modifier.weight(1f).testTag("thread.artefacts.title"), maxLines = 1)
                            IconButton(onClick = { state.listing = true }, enabled = !state.busy, modifier = Modifier.testTag("thread.artefacts.menu")) {
                                Icon(Icons.Outlined.FolderOpen, "Thread artefacts (${state.members.size})")
                            }
                            if (compactThreadArtefact()) ThreadArtefactDiscussButton()
                            if (compactThreadArtefact()) Box {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Artefact options") }
                                DropdownMenu(menu, { menu = false }) { ThreadArtefactMenuItems { menu = false } }
                            } else TextButton(onClick = { state.discuss = true; state.shown = false }, enabled = state.tabs.any { it.id == state.selected && it.attached && !it.publicationHeld && it.unavailable == null } && !state.busy, modifier = Modifier.testTag("thread.artefacts.discuss")) { Text("Discuss this") }
                        }
                        Box(Modifier.weight(1f)) {
                            state.tabs.filter { it.open }.forEach { tab -> key(tab.kind, tab.id) {
                                RetainedPane(tab.id == state.selected && tab.open) {
                                    CompositionLocalProvider(LocalThreadArtefactTab provides tab, LocalThreadPaneActive provides (state.shown && tab.id == state.selected && tab.open),
                                        studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit provides { state.shown = false }) {
                                        if (tab.kind == "doc") ThreadDocument(model, tab) else DirectArtefact(model, tab.id)
                                    }
                                }
                            } }
                            if (state.tabs.none { it.open }) Text("Use the artefacts icon to open an existing Doc, whiteboard or HTML.", Modifier.padding(24.dp))
                        }
                    }
                }
                if (state.busy) Box(Modifier.fillMaxSize().clickable { }.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .08f)))
            }
            state.failure?.let { Text(it, Modifier.padding(8.dp).testTag("thread.artefacts.failure"), style = MaterialTheme.typography.bodySmall) }
            state.notice?.takeIf { state.failure == null }?.let { Text(it, Modifier.padding(8.dp).testTag("thread.artefacts.notice"), style = MaterialTheme.typography.bodySmall) }

        }
    }
    if (state.browsing) ThreadArtefactBrowser(model, target, state)
    if (state.listing) AlertDialog(onDismissRequest = { state.listing = false }, title = { Text("Thread artefacts") }, text = {
        Column {
            if (state.busy) Text("Updating thread attachments…")
            CreateArtefact(model, sourceThread = target) { open, enabled ->
                TextButton(onClick = open, enabled = enabled && !state.busy, modifier = Modifier.testTag("thread.artefacts.create")) { Text("Create artefact in this discussion") }
            }
            state.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.membershipReady) Text("Saved attachments shown; refresh to confirm the current server list.")
            if (state.members.isEmpty()) Text("No attachments in this thread.")
            TextButton(onClick = { scope.launch { state.refreshTracked() } }, enabled = !state.busy) { Text("Refresh attachments") }
            LazyColumn(Modifier.heightIn(max = 400.dp)) { items(state.tabs.filter { it.attached || it.open || it.publicationHeld }, key = { it.kind + it.id }) { tab -> Column {
            TextButton(onClick = { if (tab.attached) state.open(tab.kind, tab.id, tab.title) else state.focus(tab.kind, tab.id, tab.title); state.listing = false }, modifier = Modifier.fillMaxWidth().testTag("thread.artefacts.focus.${tab.id}")) { Text(tab.title + (if (!tab.attached) " · Detached recovery" else if (tab.publicationHeld) " · Recovery held" else if (tab.open && tab.unavailable != null) " · Unavailable" else if (tab.open) " · ${tab.versionLabel}" else "")) }
            Row { TextButton(onClick = { scope.launch {
                state.busy = true
                try {
                    check(!tab.open || tab.preserve()) { "Editor could not preserve pending changes. Retry close." }
                    tab.open = false; state.selected = state.tabs.firstOrNull { it.open }?.id
                    if (state.selected == null) { state.shown = false; state.discuss = false }
                } catch (e: Exception) { state.failure = "Tab kept open: ${e.message}" }
                finally { state.busy = false }
            } }, enabled = !state.busy && tab.open) { Text("Close view") }
            if (tab.attached) TextButton(onClick = { state.removeMember?.invoke(tab) }, enabled = !state.busy, modifier = Modifier.testTag("thread.artefacts.remove.${tab.id}")) { Text("Remove from thread") }
            else TextButton(onClick = { state.open(tab.kind, tab.id, tab.title) }, enabled = !state.busy) { Text("Add back to thread") }
        } } } } }
    }, confirmButton = { TextButton(onClick = { state.listing = false; state.browsing = true }) { Text("+ Browse") } }, dismissButton = { TextButton(onClick = { state.listing = false }) { Text("Done") } })
}

@Composable private fun ThreadDocument(model: AppModel, tab: ThreadArtefactTab) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val scope = rememberCoroutineScope()
    val state = LocalThreadArtefacts.current
    val editor = remember(tab) { ThreadArtefactTab("doc", tab.id, tab.title).also { it.parent = tab } }
    var history by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<DocumentVersion>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pinned by remember { mutableStateOf(tab.pinnedRevision ?: tab.selectedRevision) }
    val selected = versions?.firstOrNull { it.rowVersion.toString() == pinned }
    var preparedUnderlying by remember { mutableStateOf<ArtefactChatReference?>(null) }
    suspend fun load() {
        try { versions = model.fileNappletAPI(c).documentVersions(w.id, w.appNpub, tab.id); failure = null }
        catch (e: Exception) { failure = "Saved document versions unavailable. Reconnect and Retry." }
    }
    val body by remember(tab.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.docBody(w.id, tab.id) } }.collectAsState(null)
    LaunchedEffect(tab.id) { model.sync.refreshDoc(c, w, tab.id); if (pinned != null) load() }
    val syncStatus by model.engine.status.collectAsState()
    val openFailure = if (body == null) syncStatus.errors[WorkspaceSync.docKey(w.id, tab.id)] else null
    SideEffect {
        tab.showHistory = { history = true; scope.launch { load() } }
        tab.resumeDraft = if (pinned != null) ({ pinned = null }) else null
        tab.unavailable = openFailure?.let { "This document could not be opened: $it" }
        editor.attached = tab.attached
        tab.title = selected?.title ?: editor.title
        tab.versionLabel = if (pinned != null) "Pinned v$pinned" else editor.versionLabel
        tab.pinnedRevision = pinned
        tab.selectedRevision = pinned ?: editor.selectedRevision
        tab.checkpoint = { editor.preserve() }
        tab.sourceDirty = { editor.sourceDirty?.invoke() != false }
        tab.saveSource = { editor.saveSource?.invoke() == true }
        tab.holdRecovery = { editor.holdRecovery?.invoke() == true }
        tab.prepare = {
            check(model.connection?.id == c.id && model.workspace?.id == w.id) { "Return to the original workspace before Send." }
            val saved = editor.prepare?.invoke() ?: error("Document is still opening. Retry Send.")
            preparedUnderlying = saved
            if (pinned == null) saved else (selected ?: error("Pinned document revision is unavailable. Retry history.")).reference(tab.id, true)
        }
        tab.validate = { ref -> if (pinned != null) pinned == ref.revision && preparedUnderlying?.let { editor.validate?.invoke(it) } == true else editor.validate?.invoke(ref) == true }
    }
    DisposableEffect(tab) { onDispose { tab.prepare = null; tab.checkpoint = null; tab.validate = null; tab.sourceDirty = null; tab.saveSource = null; tab.holdRecovery = null } }
    Column(Modifier.fillMaxSize()) {
        if (!compactThreadArtefact()) Row {
            TextButton(onClick = { history = true; scope.launch { load() } }, modifier = Modifier.testTag("thread.doc.history")) { Text("Saved versions") }
            if (pinned != null) TextButton(onClick = { pinned = null }, modifier = Modifier.testTag("thread.doc.latest")) { Text("Return to current draft") }
        }
        failure?.let { Text(it); TextButton(onClick = { scope.launch { load() } }) { Text("Retry") } }
        Box(Modifier.weight(1f)) {
            RetainedPane(pinned == null) {
                CompositionLocalProvider(LocalThreadArtefactTab provides editor, LocalThreadPaneActive provides (state?.shown == true && pinned == null)) {
                    if (body != null) DocumentEditor(model, tab.id) { state?.shown = false }
                    else if (openFailure == null) Text("Opening document…", Modifier.padding(16.dp).testTag("thread.artefact.loading"))
                    else Column(Modifier.padding(16.dp).testTag("thread.artefact.unavailable")) {
                        Text("This document could not be opened: $openFailure")
                        Text("Chat, Back and other artefacts keep working, and Send leaves this one out. Your drafts on this device are kept; close it from the artefacts list.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { model.sync.refreshDoc(c, w, tab.id, force = true) } }, modifier = Modifier.testTag("thread.artefact.retry")) { Text("Retry") }
                    }
                }
            }
            if (pinned != null) LazyColumn(Modifier.fillMaxSize().background(studio.otherstuff.ambulando.app.ui.theme.Amb.colors.background)) {
                item { Text("Pinned v$pinned · read-only", Modifier.padding(12.dp).testTag("thread.doc.pinned")) }
                item {
                    selected?.bytes?.let { bytes ->
                        val document = studio.otherstuff.ambulando.core.docs.DocumentBody(bytes, selected.contentType)
                        studio.otherstuff.ambulando.app.ui.rich.RichBlocks(studio.otherstuff.ambulando.core.docs.DocumentRender.markdownBlocks(document.markdown))
                    } ?: Text("Loading exact saved revision…")
                }
            }
        }
    }
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("Saved document versions") }, text = {
        LazyColumn { items(versions.orEmpty(), key = { it.rowVersion }) { version -> TextButton(onClick = { pinned = version.rowVersion.toString(); history = false }, enabled = version.bytes != null) { Text("v${version.rowVersion} · ${version.title}") } } }
    }, confirmButton = { TextButton(onClick = { history = false }) { Text("Done") } })
}

@Composable private fun ThreadArtefactBrowser(model: AppModel, target: ChatTarget, state: ThreadArtefacts) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = LocalAppServices.current
    val valid = c.id == target.connectionId && w.id == target.workspaceId && services.unlocked && !w.accessRemoved
    LaunchedEffect(valid) { if (!valid) state.browsing = false }
    if (!valid) return
    var channel by remember { mutableStateOf(target.channelId) }
    var search by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("all") }
    val context = remember(channel) { WorkContext(scopeId = model.channels.firstOrNull { it.id == channel }?.scopeId, channelId = channel.ifEmpty { null }) }
    val docs by remember(context) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.docs(w.id, context) } }.collectAsState(emptyList())
    val files by remember(context) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.files(w.id, context) } }.collectAsState(emptyList())
    var refresh by remember { mutableIntStateOf(0) }
    val status by model.engine.status.collectAsState()
    val keys = model.channels.filter { channel.isEmpty() || it.id == channel }.flatMap { listOf(WorkspaceSync.docsKey(w.id,it.id), WorkspaceSync.filesKey(w.id,it.id)) }.toSet()
    val loading = (status.running + status.queued).any { it in keys }
    val failed = (status.errors.keys + status.connectionFailures).any { it in keys }
    LaunchedEffect(context, refresh) { model.sync.refreshDocs(c, w, context.scopeId, force = refresh > 0); model.sync.refreshFiles(c, w, context.scopeId, force = refresh > 0) }
    data class Row(val kind: String, val id: String, val title: String, val type: String)
    val allowed = model.channels.filter { !it.accessRemoved }.map { it.id }.toSet()
    val rows = docs.filter { !it.accessRemoved && it.channelId in allowed }.map { Row("doc", it.id, it.title, "document") } + files.filter { !it.accessRemoved && !it.archived && it.channelId in allowed && ArtefactType.of(it) in listOf(ArtefactType.WHITEBOARD, ArtefactType.HTML) }.map { Row("file", it.id, it.displayName, ArtefactType.of(it)!!.wire) }
    AlertDialog(onDismissRequest = { if (!state.busy) state.browsing = false }, title = { Text("Open existing artefact") }, text = {
        Column {
            if (state.busy) Text("Attaching to thread…")
            state.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(search, { search = it }, label = { Text("Search titles") }, modifier = Modifier.testTag("thread.artefacts.search"))
            var channels by remember { mutableStateOf(false) }
            Box { TextButton(onClick = { channels = true }) { Text(if (channel.isEmpty()) "All authorised channels" else model.channels.firstOrNull { it.id == channel }?.name ?: "Conversation channel") }
                DropdownMenu(channels, { channels = false }) { DropdownMenuItem(text = { Text("All authorised channels") }, onClick = { channel = ""; channels = false }); model.channels.filter { it.id in allowed }.forEach { ch -> DropdownMenuItem(text = { Text(ch.name) }, onClick = { channel = ch.id; channels = false }) } }
            }
            Row { listOf("all" to "All", "document" to "Docs", "excalidraw" to "Boards", "html" to "HTML").forEach { (key, label) -> TextButton(onClick = { type = key }) { Text(if (type == key) "● $label" else label) } } }
            val matching = rows.filter { (type == "all" || it.type == type) && it.title.contains(search.trim(), true) }
            if (loading) Text("Refreshing authorised artefacts…")
            if (failed) Text("Connection unavailable. Saved list shown; Retry refreshes authorised records.")
            if (matching.isEmpty() && !loading) Text(if (search.isBlank() && type == "all") "No artefacts in this channel." else "No matching artefacts. Change the search, type or channel.")
            TextButton(onClick = { refresh++ }, modifier = Modifier.testTag("thread.artefacts.retry")) { Text(if (failed) "Retry" else "Refresh") }
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(matching, key = { it.id }) { row ->
                TextButton(onClick = { if (model.connection?.id == target.connectionId && model.workspace?.id == target.workspaceId && services.unlocked) state.open(row.kind, row.id, row.title) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("thread.artefacts.row.${row.id}")) { Text(row.title) }
            } }
            Text("Saved authorised records; content loads when opened.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { state.browsing = false }, enabled = !state.busy) { Text("Cancel") } })
}

/** Native editor headers own navigation on narrow thread views; a loading/failed view keeps the fallback bar. */
@Composable internal fun compactThreadArtefact(): Boolean = LocalThreadArtefacts.current != null &&
    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 760

@Composable internal fun ThreadArtefactHeaderEntry() {
    if (!compactThreadArtefact()) return
    val state = LocalThreadArtefacts.current ?: return
    val tab = LocalThreadArtefactTab.current ?: return
    val owner = tab.parent ?: tab
    val active = LocalThreadPaneActive.current
    DisposableEffect(owner, active) { owner.compactHeaderOwned = active; onDispose { owner.compactHeaderOwned = false } }
    IconButton(onClick = { state.listing = true }, enabled = !state.busy, modifier = Modifier.testTag("thread.artefacts.menu")) {
        Icon(Icons.Outlined.FolderOpen, "Thread artefacts (${state.members.size})")
    }
    ThreadArtefactDiscussButton()
}

@Composable internal fun ThreadArtefactDiscussButton() {
    val state = LocalThreadArtefacts.current ?: return
    val tab = state.tabs.firstOrNull { it.id == state.selected }
    IconButton(onClick = { state.discuss = true; state.shown = false },
        enabled = tab?.let { it.attached && !it.publicationHeld && it.unavailable == null } == true && !state.busy,
        modifier = Modifier.testTag("thread.artefacts.discuss")) { Icon(Icons.Outlined.Forum, "Discuss this") }
}

@Composable internal fun ThreadArtefactMenuItems(dismiss: () -> Unit) {
    if (!compactThreadArtefact()) return
    val state = LocalThreadArtefacts.current ?: return
    val tab = state.tabs.firstOrNull { it.id == state.selected }
    DropdownMenuItem(text = { Text("Discuss this") }, enabled = tab?.let { it.attached && !it.publicationHeld && it.unavailable == null } == true && !state.busy,
        onClick = { dismiss(); state.discuss = true; state.shown = false }, modifier = Modifier.testTag("thread.artefacts.menu.discuss"))
    tab?.showHistory?.let { action -> DropdownMenuItem(text = { Text("Saved document versions") }, onClick = { dismiss(); action() }, modifier = Modifier.testTag("thread.doc.history")) }
    tab?.resumeDraft?.let { action -> DropdownMenuItem(text = { Text("Return to current draft") }, onClick = { dismiss(); action() }, modifier = Modifier.testTag("thread.doc.latest")) }
}
