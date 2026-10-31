package studio.otherstuff.ambulando.app

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import studio.otherstuff.ambulando.app.browser.*
import studio.otherstuff.ambulando.core.browser.NIP07SitePersistence
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.onboarding.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.rich.RichBlocks
import studio.otherstuff.ambulando.app.ui.shell.*
import studio.otherstuff.ambulando.app.ui.tasks.*
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.docs.DocumentRender

@Composable fun AmbulandoApp(model: AppModel) {
    model.editorBoard?.let { (board, title) -> BoardEditor(model, board, title) { model.editorBoard = null }; return }
    model.editorDoc?.let { id -> DocumentEditor(model, id) { model.editorDoc = null }; return }
    when (val identity = model.identityState) {
        null -> CircularProgressIndicator()
        IdentityState.None -> ImportIdentityScreen(model.notice, model::import)
        IdentityState.Unavailable -> IdentityUnavailableScreen(false) { model.identity.refreshAvailability() }
        is IdentityState.Locked -> LockScreen(identity.npub, model.notice, model::unlock)
        is IdentityState.Unlocked -> if (model.connections.isEmpty()) {
            ConnectScreen(identity.npub, model.notice, { origin -> runCatching {
                val d = model.descriptor(origin)
                DescriptorSummaryModel(d.tower?.availability?.status ?: "Available", d.tower?.protocols ?: emptyList(), d.services.map { it.kind }, d.advertisesFIPS)
            } }, model::connect)
        } else if (model.activeBoundaryId == null) {
            studio.otherstuff.ambulando.app.ui.workspaces.WorkspaceSelectionEmptyState(model)
        } else if (model.active == null) {
            CircularProgressIndicator()
        } else {
            // Everything below is rebuilt for the workspace in front, so no remembered state, query or editor can follow a switch
            // into another workspace (identical workspace ids on two Towers included).
            androidx.compose.runtime.key(model.activeBoundaryId) { ConnectedApp(model) }
        }
    }
}

@Composable private fun ConnectedApp(model: AppModel) {
    val coroutineScope = rememberCoroutineScope()
    val androidContext = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val browser = remember(model) { model.browser }
    val avatars = remember(model) { studio.otherstuff.ambulando.app.media.AvatarStore(java.io.File(androidContext.cacheDir, if (model.environment.isSample) "SampleAvatars" else "AmbulandoAvatars"), if (model.environment.isSample) studio.otherstuff.ambulando.app.media.AvatarTransport { _, _ -> throw java.io.IOException("Sample avatars are offline") } else studio.otherstuff.ambulando.app.media.OkHttpAvatarTransport()) }
    // Navigating to a document makes it eligible to open in the editor again when Edit is the default opening mode.
    val stacks = remember(model.workspace?.id) { SectionStacks().also { it.onNavigate = { route -> if (route is WorkRoute.Doc) model.autoEditedDoc = null } } }
    // Sections and open records survive the process ending: saved per connection and workspace, restored once at launch.
    val navKey = model.workspace?.let { "nav-stacks:${model.connection?.id}:${it.id}" }
    // This composition's own partition: a late write after a switch must land in the workspace it belongs to.
    val navStore = remember(navKey) { model.store }
    var navRestored by remember(navKey) { mutableStateOf(false) }
    LaunchedEffect(navKey) {
        val workspace = model.workspace
        if (navKey != null && workspace != null && model.restoreNavigation) {
            studio.otherstuff.ambulando.app.ui.nav.RouteState.decode(navStore.reader.state(navKey), workspace.id)?.let { saved ->
                if (stacks.snapshot().values.all { it.isEmpty() }) {
                    stacks.restore(saved.stacks)
                    AppSection.entries.firstOrNull { it.key == saved.section }?.takeIf { model.section == AppSection.DECK }?.let { model.section = it }
                }
            }
        }
        navRestored = true
    }
    // The newest route state of THIS workspace. It is only taken while this workspace is still the one in front, so the reset that
    // comes with a switch (section back to Deck) is never saved as where this workspace was left.
    val boundaryHere = remember(navKey) { model.activeBoundaryId }
    val latestRoute = remember(navKey) { arrayOfNulls<String>(1) }
    LaunchedEffect(navKey, navRestored) {
        if (navKey != null && navRestored) androidx.compose.runtime.snapshotFlow {
            if (model.activeBoundaryId == boundaryHere) studio.otherstuff.ambulando.app.ui.nav.RouteState.encode(model.section.key, stacks.snapshot()) else null
        }.filterNotNull().distinctUntilChanged().collectLatest { encoded -> latestRoute[0] = encoded; kotlinx.coroutines.delay(400); navStore.writer.setState(navKey, encoded) }
    }
    // Leaving a workspace (a switch, a pause, lock) saves where its sections were left, without waiting for the debounce.
    DisposableEffect(navKey, navRestored) {
        onDispose {
            val encoded = latestRoute[0]
            if (navKey != null && navRestored && encoded != null) model.launchDetached { runCatching { navStore.writer.setState(navKey, encoded) } }
        }
    }
    var sidebar by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<String?>(null) }
    var viewingImage by remember(model.workspace?.id) { mutableStateOf<Pair<String, String>?>(null) }
    var chooser by remember { mutableStateOf(false) }
    val c = model.connection ?: return
    val w = model.workspace
    val syncStatus by model.engine.status.collectAsState()
    val profilesEnabled by remember(model) { model.catalog.liveQuery({ it.table == StoreTable.APP_STATE }) { studio.otherstuff.ambulando.core.profiles.ProfileSync.isEnabled(model.catalog) } }.collectAsState(false)
    LaunchedEffect(profilesEnabled) { avatars.setEnabled(profilesEnabled && !model.environment.isSample) }
    LaunchedEffect(model.identityState) {
        val key = "${studio.otherstuff.ambulando.core.browser.NIP07Sites.STATE_KEY}:${c.signerNpub}"
        browser.configure(false, studio.otherstuff.ambulando.core.browser.NIP07Sites.decode(model.catalog.reader.state(key)), { model.identity.publicKeyHex() }, { model.identity.sign(it) }, NIP07SitePersistence { sites -> model.act { model.catalog.writer.setState(key, studio.otherstuff.ambulando.core.browser.NIP07Sites.encode(sites)) } })
    }
    DisposableEffect(browser) { onDispose { browser.updateLocked(true) } }
    /** Hands a stored attachment to Android's share sheet (downloading it into the private cache first). */
    fun shareStorage(objectId: String) {
        if (w == null || w.accessRemoved) return
        model.act {
            val cache = model.fileCache()
            val known = model.store.reader.files(w.id).firstOrNull { it.storageObjectId == objectId }
            if (known?.accessRemoved == true) throw IllegalStateException("No longer available to you")
            model.sync.downloadAttachment(c, w, objectId, cache)
            var file: java.io.File? = null
            repeat(60) {
                if (file == null) { file = cache.attachment(w.id, objectId); if (file == null) kotlinx.coroutines.delay(500) }
            }
            val local = file ?: throw IllegalStateException("Attachment is not cached; retry when connected")
            val uri = androidx.core.content.FileProvider.getUriForFile(androidContext, "${androidContext.packageName}.files", local)
            androidContext.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType(known?.contentType ?: "application/octet-stream").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share attachment"))
        }
    }
    fun openUrl(url: String) {
        val objectId = studio.otherstuff.ambulando.app.ui.rich.RichLinks.storageObjectId(url)
        if (objectId != null && w != null && !w.accessRemoved) model.act {
            // A stored image opens in the image overlay (as on the web), not as a file; other files go to the share sheet.
            val known = model.store.reader.files(w.id).firstOrNull { it.storageObjectId == objectId }
            if (known?.contentType?.startsWith("image/") == true && !known.accessRemoved) viewingImage = objectId to known.displayName
            else shareStorage(objectId)
        } else if (!browser.handleLink(url)) {
            val decision = runCatching { studio.otherstuff.ambulando.core.browser.BrowserURLPolicy.decide(java.net.URI(url), true) }.getOrNull()
            if (decision is studio.otherstuff.ambulando.core.browser.BrowserURLPolicy.Decision.External) runCatching { androidContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        }
    }
    val services = AppServices(avatars, ::openUrl, { route ->
        if (route.workspaceId == w?.id) {
            val target = when (route) {
                is WorkRoute.Task -> AppSection.TASKS
                is WorkRoute.Doc -> AppSection.DOCS
                is WorkRoute.File -> AppSection.FILES
                is WorkRoute.Thread, is WorkRoute.Channel -> AppSection.CHAT
                else -> model.section
            }
            model.section = target
            if (route is WorkRoute.Channel) model.channels.firstOrNull { it.id == route.channelId }?.let { model.selectContext(it.scopeId, it.id) }
            else {
                stacks.push(target, route)
                if (route is WorkRoute.Thread) model.act {
                    val id = route.channelId ?: model.store.reader.thread(w.id, route.threadId)?.channelId
                    model.channels.firstOrNull { it.id == id }?.let { model.selectContext(it.scopeId, it.id) }
                }
            }
        }
    }, { focus -> stacks.push(AppSection.CHAT, WorkRoute.Thread(focus.workspaceId, focus.channelId, focus.threadId)); model.section = AppSection.CHAT }, { kind, id ->
        if (w != null) when (kind) {
            "task" -> { model.section = AppSection.TASKS; stacks.push(AppSection.TASKS, WorkRoute.Task(w.id, id)) }
            "document", "doc" -> { model.section = AppSection.DOCS; stacks.push(AppSection.DOCS, WorkRoute.Doc(w.id, id)) }
            "file" -> { model.section = AppSection.FILES; stacks.push(AppSection.FILES, WorkRoute.File(w.id, id)) }
            "thread" -> { model.section = AppSection.CHAT; stacks.push(AppSection.CHAT, WorkRoute.Thread(w.id, null, id)) }
            // The web's thread reference: `chat:<channelId>#<messageId>` opens that message's conversation.
            "chat", "message", "chat_message" -> model.act {
                val (channelId, messageId) = studio.otherstuff.ambulando.core.store.MentionSearch.parseThreadId(id) ?: (id to null)
                val message = messageId?.let { model.store.reader.message(w.id, it) }
                val threadId = message?.threadId
                val channelRow = model.channels.firstOrNull { it.id == (message?.channelId ?: channelId) } ?: throw IllegalStateException("This conversation is not available on this device.")
                model.section = AppSection.CHAT
                model.selectContext(channelRow.scopeId, channelRow.id)
                if (threadId != null) stacks.push(AppSection.CHAT, WorkRoute.Thread(w.id, channelRow.id, threadId))
            }
            "channel" -> model.channels.firstOrNull { it.id == id }?.let { model.section = AppSection.CHAT; model.selectContext(it.scopeId, it.id) }
                ?: model.act { error("This channel is not available to you.") }
            "scope" -> model.scopes.firstOrNull { it.id == id }?.let { model.selectContext(it.id, null) }
                ?: model.act { error("This scope is not available to you.") }
            "person", "agent" -> person = id
            else -> model.act { error("This reference cannot be opened in the native app.") }
        }
    }, true, model.environment.isSample, viewImage = { id, title -> viewingImage = id to title })
    if (browser.presented) { BrowserScreen(browser); return }
    // A page opened from the document editor (the editor is shown outside the services provider and leaves its draft stored).
    LaunchedEffect(model.pendingRecordOpen) { model.pendingRecordOpen?.let { (kind, id) -> model.pendingRecordOpen = null; services.openMention(kind, id) } }
    CompositionLocalProvider(LocalAppServices provides services, LocalAvatars provides avatars) {
        model.notice?.let { message -> AlertDialog(onDismissRequest = model::clearNotice, title = { Text("Not completed") }, text = { Text(message) }, confirmButton = { TextButton(onClick = model::clearNotice) { Text("Dismiss") } }) }
        viewingImage?.let { (id, title) -> StoredImageViewer(model, id, title, onShare = { shareStorage(id) }) { viewingImage = null } }
        if (chooser) { studio.otherstuff.ambulando.app.ui.workspaces.WorkspaceChooser(model) { chooser = false }; return@CompositionLocalProvider }
        if (settings) { NativeSettings(model) { settings = false }; return@CompositionLocalProvider }
        if (management) { WorkspaceManagement(model) { management = false }; return@CompositionLocalProvider }
        if (search) { NativeSearch(model) { search = false }; return@CompositionLocalProvider }
        if (notifications) { NativeNotifications(model) { notifications = false }; return@CompositionLocalProvider }
        val notificationUnread by remember(c.id, w?.id) { model.store.liveQuery({ it.table == StoreTable.NOTIFICATIONS }) { w?.let { model.store.reader.notificationCounts(c.id, it.id).unread } ?: 0 } }.collectAsState(0)
        val unread by remember(w?.id) { model.store.liveQuery({ it.workspaceId == w?.id }) { w?.let { model.store.reader.unreadChannelIds(it.id) } ?: emptySet() } }.collectAsState(emptySet())
        val people by remember(c.id, w?.id) { model.store.liveQuery({ it.workspaceId == w?.id || it.table == StoreTable.PROFILES }) { w?.let { model.store.reader.people(c.id, it.id) } } }.collectAsState(null)
        person?.let { npub ->
            AlertDialog(onDismissRequest = { person = null }, title = { Text(people?.name(npub) ?: "Workspace member") }, text = { Column { people?.person(npub)?.let { PersonAvatar(it) }; Text(npub) } }, confirmButton = { TextButton(onClick = { person = null }) { Text("Done") } })
        }
        val labels = model.channels.associate { row -> row.id to studio.otherstuff.ambulando.core.context.SidebarNavigation.label(studio.otherstuff.ambulando.core.context.NavChannel(row.id, row.scopeId, row.name, row.kind, row.position, row.raw), c.signerNpub, people?.names ?: emptyMap()) }
        val groups = model.scopes.map { scope -> studio.otherstuff.ambulando.core.context.SidebarGroup(
            studio.otherstuff.ambulando.core.context.NavScope(scope.id, scope.name, scope.kind),
            model.channels.filter { it.scopeId == scope.id }.map { studio.otherstuff.ambulando.core.context.NavChannel(it.id, it.scopeId, it.name, it.kind) }, studio.otherstuff.ambulando.core.context.SidebarNavigation.isDirectMessageScope(studio.otherstuff.ambulando.core.context.NavScope(scope.id, scope.name, scope.kind))) }
        fun choose(scopeId: String?, channelId: String?) { if (!model.viewLocked) { stacks.resetContextBound(); model.selectContext(scopeId, channelId) }; sidebar = false }
        val actions = object : ShellActions {
            override fun select(section: AppSection) { focusManager.clearFocus(force = true); keyboard?.hide(); model.section = section; model.refresh() }
            override fun reselect(section: AppSection) { stacks.popToRoot(section) }
            override fun openSidebar() { focusManager.clearFocus(force = true); keyboard?.hide(); sidebar = true }
            override fun closeSidebar() { sidebar = false }
            override fun chooseHome() { choose(null, null) }
            override fun toggleViewLock() { model.viewLocked = !model.viewLocked }
            override fun search() { focusManager.clearFocus(force = true); keyboard?.hide(); search = true }
            override fun notifications() { focusManager.clearFocus(force = true); keyboard?.hide(); notifications = true }
            override fun showBrowser() { focusManager.clearFocus(force = true); keyboard?.hide(); browser.show() }
            override fun settings() { focusManager.clearFocus(force = true); keyboard?.hide(); settings = true }
            override fun unlock() { model.lock() }
        }
        val chrome = ShellChromeState(model.section, AppSection.visible(model.showAgents, model.showContext), w?.label ?: "Workspaces",
            model.scopes.firstOrNull { it.id == model.context.scopeId }?.name, model.channels.firstOrNull { it.id == model.context.channelId }?.name,
            model.context.isHome, model.viewLocked, !syncStatus.isIdle, browser.tabs.size, people?.person(c.signerNpub), false, model.environment.isSample, sidebar, syncStatus.errors.values.firstOrNull()?.let { "Stored content is available. Update failed: $it" }, studio.otherstuff.ambulando.core.notifications.NotificationInboxLogic.badge(notificationUnread),
            if (model.enabledEntries.size > 1) model.activeEntry?.let { studio.otherstuff.ambulando.app.ui.shell.WorkspaceTint.swatch(it.colour) } else null)
        val screen = w?.let { ScreenScope(c.id, it.id, it.appNpub, c.signerNpub, model.context.scopeId, model.context.channelId) }
        val intents = remember(screen) { object : TasksIntents {
            override fun submit(intent: WriteIntent) = model.submit(intent)
            override fun refresh(force: Boolean) { model.refresh() }
            override fun openTask(taskId: String, force: Boolean, markViewed: Boolean) { if (w != null) model.act { model.sync.refreshTask(c, w, taskId, force = force); if (markViewed) model.markViewed(studio.otherstuff.ambulando.core.transport.ViewStateRecord.ResourceType.TASK, taskId) } }
            override val readEpoch: Any? get() = model.identityState
            override suspend fun loadRelationships(taskId: String): studio.otherstuff.ambulando.app.ui.tasks.RelationshipStatus {
                if (w == null || w.accessRemoved || model.identityState !is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked) return studio.otherstuff.ambulando.app.ui.tasks.RelationshipStatus.OFFLINE
                return try {
                    val outcome = model.engine.perform(studio.otherstuff.ambulando.core.sync.WorkspaceSync.taskRelationshipsKey(w.id, taskId), studio.otherstuff.ambulando.core.sync.SyncPriority.VISIBLE) {
                        studio.otherstuff.ambulando.core.surfaces.TaskTreeWrites(model.store, model.identity, model.workspaceTransport).loadRelationships(c, w, taskId)
                    }
                    when (outcome) {
                        studio.otherstuff.ambulando.core.surfaces.TaskRelationshipOutcome.COMPLETE -> studio.otherstuff.ambulando.app.ui.tasks.RelationshipStatus.COMPLETE
                        else -> studio.otherstuff.ambulando.app.ui.tasks.RelationshipStatus.PARTIAL
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: studio.otherstuff.ambulando.core.surfaces.TaskTreeError.Unavailable) { studio.otherstuff.ambulando.app.ui.tasks.RelationshipStatus.RESTRICTED
                } catch (e: Exception) { studio.otherstuff.ambulando.app.ui.tasks.RelationshipStatus.OFFLINE }
            }
            override suspend fun mentionCandidates(query: String) = if (w == null) emptyList() else model.store.reader.mentionCandidates(w.id, query, viewerNpub = c.signerNpub)
            override fun retryWrite(item: WriteItem) { model.act { model.writes.retry(item.id, c) } }
            override fun applyMine(item: WriteItem) { model.act { model.writes.retry(item.id, c, true) } }
            override fun discardWrite(item: WriteItem) { model.act { model.store.writer.discardWrite(item.id) } }
        } }
        WorkspaceShell(chrome, actions, stacks, pinControl = { route ->
            val target = when (route) {
                is WorkRoute.Task -> studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.TASK to route.taskId
                is WorkRoute.Doc -> studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.DOCUMENT to route.docId
                is WorkRoute.Thread -> studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.THREAD to route.threadId
                is WorkRoute.Channel -> studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.CHANNEL to route.channelId
                null -> if (model.section == AppSection.CHAT) model.context.channelId?.let { studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.CHANNEL to it } else null
                else -> null
            }
            target?.let { (type, id) -> RecordPin(model, type, id) }
        }, sidebar = {
            val enabled = model.enabledEntries
            val stacked = if (enabled.size > 1) enabled.map { e ->
                SidebarWorkspace(e.id, e.workspace.label, e.connection.origin.removePrefix("https://"), e.colour, e.collapsed, e.id == model.activeBoundaryId,
                    e.workspace.accessRemoved, e.nav.groups, e.nav.collapsed, e.nav.unread, e.nav.labels, e.nav.loaded)
            } else emptyList()
            SidebarContent(SidebarState(w?.label ?: "Workspaces", w?.id, model.workspaces.map { it.id to it.label }, true, groups, unread, model.activeEntry?.nav?.collapsed ?: emptySet(), model.context,
                labels, model.scopes.filter { !it.accessRemoved }.map { it.id }.toSet(),
                if (w == null) studio.otherstuff.ambulando.core.context.NavigationLoadState.FindingWorkspaces else studio.otherstuff.ambulando.core.context.NavigationLoadState.Ready(model.notice), false, stacked), object : SidebarActions {
                override fun chooseHome() { choose(null, null) }
                override fun chooseScope(scopeId: String) { choose(scopeId, null) }
                override fun chooseChannel(scopeId: String, channelId: String) { choose(scopeId, channelId) }
                override fun toggleCollapsed(scopeId: String) { model.activeEntry?.let { model.toggleScopeCollapsed(it, scopeId) } }
                override fun newChannel(scope: studio.otherstuff.ambulando.core.context.NavScope) { model.selectContext(scope.id, null); management = true }
                override fun channelSettings(channel: studio.otherstuff.ambulando.core.context.NavChannel) { model.selectContext(channel.scopeId, channel.id); management = true }
                override fun manageScopes() { management = true }
                override fun selectWorkspace(workspaceId: String) { model.workspaces.firstOrNull { it.id == workspaceId }?.let { model.selectWorkspace(it) }; sidebar = false }
                override fun retry() { model.retryConnection() }
                override fun close() { sidebar = false }
                override fun chooseWorkspaces() { sidebar = false; chooser = true }
                override fun openIn(id: String, scopeId: String?, channelId: String?) { model.entries.firstOrNull { it.id == id }?.let { stacks.resetContextBound(); model.openWorkspace(it, scopeId, channelId) } }
                override fun toggleWorkspaceCollapsed(id: String) { model.entries.firstOrNull { it.id == id }?.let(model::toggleWorkspaceCollapsed) }
                override fun toggleScopeIn(id: String, scopeId: String) { model.entries.firstOrNull { it.id == id }?.let { model.toggleScopeCollapsed(it, scopeId) } }
                override fun moveWorkspace(id: String, offset: Int) { model.entries.firstOrNull { it.id == id }?.let { model.moveWorkspace(it, offset) } }
                override fun pauseWorkspace(id: String) { model.entries.firstOrNull { it.id == id }?.let { model.setWorkspaceEnabled(it, false) } }
            })
        }, sectionRoot = { section ->
            if (w == null || screen == null) Text("Discovering workspaces…") else when (section) {
                AppSection.TASKS -> TasksScreen(remember(screen) { TasksScreenState(model.store, screen, coroutineScope) }, screen, intents)
                AppSection.CHAT -> ConnectedChat(model, null)
                AppSection.DOCS -> ConnectedDocuments(model, null)
                AppSection.FILES -> NativeFiles(model)
                AppSection.DECK -> ConnectedDeck(model)
                AppSection.TOOLBOX -> ConnectedToolbox(model)
                AppSection.AGENTS -> ConnectedAgents(model)
                AppSection.CONTEXT -> ConnectedContext(model)
            }
        }, routeScreen = { _, route ->
            if (screen != null) when (route) {
                is WorkRoute.Task -> Column { Box(Modifier.weight(1f)) { TaskDetailScreen(remember(screen, route.taskId) { TaskDetailState(model.store, screen, route.taskId, coroutineScope) }, screen, intents) } }
                is WorkRoute.File -> NativeFiles(model, route.fileId)
                is WorkRoute.Doc -> ConnectedDocuments(model, route.docId)
                is WorkRoute.Thread -> ConnectedChat(model, route.threadId)
                is WorkRoute.Canvas -> ConnectedCanvas(model, route.canvasId)
                is WorkRoute.HtmlArtefact -> ConnectedHtml(model, route)
                else -> Text("This record has no native detail view")
            }
        })
    }
}
