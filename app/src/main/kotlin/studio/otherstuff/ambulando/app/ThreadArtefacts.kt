package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.documentVersions
import studio.otherstuff.ambulando.core.transport.DocumentVersion
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.ArtefactType
import studio.otherstuff.ambulando.core.sync.*

internal class ThreadArtefactTab(val kind: String, val id: String, title: String) {
    var title by mutableStateOf(title)
    var open by mutableStateOf(true)
    var versionLabel by mutableStateOf("Loading revision…")
    var pinnedRevision by mutableStateOf<String?>(null)
    var selectedRevision by mutableStateOf<String?>(null)
    var viewport by mutableStateOf<String?>(null)
    var validate: (suspend (ArtefactChatReference) -> Boolean)? = null
    var reference by mutableStateOf<ArtefactChatReference?>(null)
    var prepare: (suspend () -> ArtefactChatReference)? = null
    var checkpoint: (suspend () -> Boolean)? = null
}
internal class ThreadArtefacts {
    var chatPosition by mutableStateOf<ChatPosition?>(null)
    var checkpointChat: (() -> Unit)? = null
    var restored by mutableStateOf(false)
    val tabs = mutableStateListOf<ThreadArtefactTab>()
    var selected by mutableStateOf<String?>(null)
    var shown by mutableStateOf(false)
    var browsing by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var discuss by mutableStateOf(false)
    fun open(kind: String, id: String, title: String = "Artefact") {
        if (busy) return
        val k = if (kind == "document") "doc" else kind
        val tab = tabs.firstOrNull { it.id == id && it.kind == k } ?: ThreadArtefactTab(k, id, title).also { tabs.add(it) }
        tab.open = true; selected = id; shown = true; discuss = true; browsing = false
    }
    fun snapshot(): String = buildJsonObject {
        put("selected", selected); put("shown", shown); put("discuss", discuss)
        chatPosition?.let { position -> put("chatPosition", buildJsonObject { put("key", position.key?.toString()); put("offset", position.offset); put("follow", position.follow) }) }
        put("tabs", JsonArray(tabs.map { tab -> buildJsonObject {
            put("kind", tab.kind); put("id", tab.id); put("title", tab.title); put("open", tab.open)
            put("pinned", tab.pinnedRevision); put("revision", tab.selectedRevision); put("viewport", tab.viewport)
        } }))
    }.toString()
    fun restore(raw: String?) {
        if (raw != null) runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            (o["chatPosition"] as? JsonObject)?.let { position ->
                chatPosition = ChatPosition(position["key"]?.jsonPrimitive?.contentOrNull, position["offset"]!!.jsonPrimitive.int.coerceAtLeast(0), position["follow"]!!.jsonPrimitive.boolean)
            }
            o["tabs"]!!.jsonArray.take(32).forEach { value -> val t = value.jsonObject
                val kind = t["kind"]!!.jsonPrimitive.content; val id = t["id"]!!.jsonPrimitive.content
                require(kind in listOf("doc", "file") && studio.otherstuff.ambulando.core.transport.isUUID(id))
                tabs.add(ThreadArtefactTab(kind, id, t["title"]!!.jsonPrimitive.content).also { tab ->
                    tab.open = t["open"]!!.jsonPrimitive.boolean
                    tab.pinnedRevision = t["pinned"]?.jsonPrimitive?.contentOrNull
                    tab.selectedRevision = t["revision"]?.jsonPrimitive?.contentOrNull
                    tab.viewport = t["viewport"]?.jsonPrimitive?.contentOrNull
                })
            }
            selected = o["selected"]?.jsonPrimitive?.contentOrNull?.takeIf { id -> tabs.any { it.id == id && it.open } }
            shown = selected != null && o["shown"]?.jsonPrimitive?.boolean == true
            discuss = selected != null && o["discuss"]?.jsonPrimitive?.boolean == true
        }.onFailure { failure = "Saved workspace could not be restored. Your editor drafts remain available." }
        restored = true
    }
    suspend fun prepare(target: ChatTarget): String? {
        check(!busy) { "An artefact operation is already running." }
        if (tabs.none { it.open }) return null
        busy = true; failure = null
        try {
            val refs = tabs.filter { it.open }.map { tab ->
                (tab.prepare ?: error("${tab.title} is still opening. Retry Send."))().also { tab.reference = it }
            }
            tabs.filter { it.open }.zip(refs).forEach { (tab, ref) -> check(tab.validate?.invoke(ref) == true) { "${tab.title} changed during preparation. Retry Send." } }
            return if (!discuss && !shown) null else ArtefactChatContext.encode(target, shown, selected ?: refs.first().recordID, refs)
        } catch (e: Exception) { busy = false; failure = "Send paused: ${e.message}"; throw e }
    }
}
internal val LocalThreadArtefacts = staticCompositionLocalOf<ThreadArtefacts?> { null }
internal val LocalThreadPaneActive = staticCompositionLocalOf { true }
internal val LocalThreadArtefactTab = staticCompositionLocalOf<ThreadArtefactTab?> { null }

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
    LaunchedEffect(target, state) { if (!state.restored) state.restore(runtime.store.reader.state(key)) }
    if (!state.restored) { Text("Restoring thread workspace…"); return }
    state.chatPosition?.let { model.chatPositions.putIfAbsent(target.draftKey, it) }
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
            state.tabs.forEach { tab -> check(tab.checkpoint?.invoke() == true) { "${tab.title} has not preserved pending changes. Retry closing." } }
            runtime.store.writer.setState(key, state.snapshot())
            true
        } catch (e: Exception) { state.failure = e.message; false }
        finally { state.busy = false }
    }
    androidx.activity.compose.BackHandler {
        if (state.shown) state.shown = false else scope.launch { if (checkpoint()) exit?.invoke() }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle, state) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.launchDetached {
                state.checkpointChat?.invoke()
                state.tabs.forEach { runCatching { it.checkpoint?.invoke() } }
                runtime.store.writer.setState(key, state.snapshot())
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    var switcher by remember { mutableStateOf(false) }
    val services = LocalAppServices.current
    val intercepted = remember(services, state) {
        fun mention(kind: String, id: String) { if (kind in listOf("doc", "document", "file")) state.open(kind, id) else services.openMention(kind, id) }
        AppServices(services.avatars, services.openUrl, services.open, services.focusThread, ::mention, services.unlocked, services.sample, services.viewImage)
    }
    CompositionLocalProvider(LocalThreadArtefacts provides state, LocalAppServices provides intercepted,
        studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit provides { scope.launch { if (checkpoint()) exit?.invoke() }; Unit }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RetainedPane(!state.shown) { CompositionLocalProvider(LocalThreadPaneActive provides !state.shown) { chat() } }
                RetainedPane(state.shown) {
                    Column(Modifier.fillMaxSize().background(studio.otherstuff.ambulando.app.ui.theme.Amb.colors.background)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = { switcher = true }, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("thread.artefacts.title")) {
                                Text(state.tabs.firstOrNull { it.id == state.selected }?.let { "${it.title} · ${it.versionLabel}" } ?: "Open an artefact", maxLines = 1)
                            }
                            TextButton(onClick = { state.discuss = true; state.shown = false }, enabled = state.selected != null && !state.busy, modifier = Modifier.testTag("thread.artefacts.discuss")) { Text("Discuss this") }
                        }
                        Box(Modifier.weight(1f)) {
                            state.tabs.forEach { tab -> key(tab.kind, tab.id) {
                                RetainedPane(tab.id == state.selected && tab.open) {
                                    CompositionLocalProvider(LocalThreadArtefactTab provides tab, LocalThreadPaneActive provides (state.shown && tab.id == state.selected && tab.open),
                                        studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit provides { state.shown = false }) {
                                        if (tab.kind == "doc") ThreadDocument(model, tab) else DirectArtefact(model, tab.id)
                                    }
                                }
                            } }
                            if (state.tabs.none { it.open }) Text("Use + to open an existing Doc, whiteboard or HTML.", Modifier.padding(24.dp))
                        }
                    }
                }
                if (state.busy) Box(Modifier.fillMaxSize().clickable { }.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .08f)))
            }
            state.failure?.let { Text(it, Modifier.padding(8.dp).testTag("thread.artefacts.failure"), style = MaterialTheme.typography.bodySmall) }
            val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            if (!keyboardVisible || state.shown) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { if (checkpoint()) exit?.invoke() } }, enabled = !state.busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 56.dp).testTag("thread.artefacts.back")) { Text("Back") }
                FilterChip(selected = !state.shown, onClick = { state.shown = false }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("thread.chat"), label = { Text("Chat") })
                FilterChip(selected = state.shown, onClick = { state.shown = true }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("thread.artefacts"), label = { Text("Artefacts (${state.tabs.count { it.open }})") })
                TextButton(onClick = { state.browsing = true }, enabled = !state.busy, modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp).testTag("thread.artefacts.add")) { Text("+") }
            }
        }
    }
    if (state.browsing) ThreadArtefactBrowser(model, target, state)
    if (switcher) AlertDialog(onDismissRequest = { switcher = false }, title = { Text("Open artefacts") }, text = {
        Column { state.tabs.filter { it.open }.forEach { tab -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { state.selected = tab.id; state.shown = true; switcher = false }, modifier = Modifier.weight(1f)) { Text(tab.title + (" · ${tab.versionLabel}")) }
            TextButton(onClick = { scope.launch {
                state.busy = true
                try {
                    check(tab.checkpoint?.invoke() == true) { "Editor could not preserve pending changes. Retry close." }
                    tab.open = false; state.selected = state.tabs.firstOrNull { it.open }?.id
                    if (state.selected == null) { state.shown = false; state.discuss = false }
                } catch (e: Exception) { state.failure = "Tab kept open: ${e.message}" }
                finally { state.busy = false }
            } }, enabled = !state.busy) { Text("Close") }
        } } }
    }, confirmButton = { TextButton(onClick = { switcher = false; state.browsing = true }) { Text("+ Browse") } }, dismissButton = { TextButton(onClick = { switcher = false }) { Text("Done") } })
}

@Composable private fun ThreadDocument(model: AppModel, tab: ThreadArtefactTab) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val scope = rememberCoroutineScope()
    val state = LocalThreadArtefacts.current
    val editor = remember(tab) { ThreadArtefactTab("doc", tab.id, tab.title) }
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
    SideEffect {
        tab.title = selected?.title ?: editor.title
        tab.versionLabel = if (pinned != null) "Pinned v$pinned" else editor.versionLabel
        tab.pinnedRevision = pinned
        tab.selectedRevision = pinned ?: editor.selectedRevision
        tab.checkpoint = { editor.checkpoint?.invoke() == true }
        tab.prepare = {
            check(model.connection?.id == c.id && model.workspace?.id == w.id) { "Return to the original workspace before Send." }
            val saved = editor.prepare?.invoke() ?: error("Document is still opening. Retry Send.")
            preparedUnderlying = saved
            if (pinned == null) saved else (selected ?: error("Pinned document revision is unavailable. Retry history.")).reference(tab.id, true)
        }
        tab.validate = { ref -> if (pinned != null) pinned == ref.revision && preparedUnderlying?.let { editor.validate?.invoke(it) } == true else editor.validate?.invoke(ref) == true }
    }
    DisposableEffect(tab) { onDispose { tab.prepare = null; tab.checkpoint = null; tab.validate = null } }
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = { history = true; scope.launch { load() } }, modifier = Modifier.testTag("thread.doc.history")) { Text("Saved versions") }
            if (pinned != null) TextButton(onClick = { pinned = null }, modifier = Modifier.testTag("thread.doc.latest")) { Text("Return to current draft") }
        }
        failure?.let { Text(it); TextButton(onClick = { scope.launch { load() } }) { Text("Retry") } }
        Box(Modifier.weight(1f)) {
            RetainedPane(pinned == null) {
                CompositionLocalProvider(LocalThreadArtefactTab provides editor) {
                    if (body != null) DocumentEditor(model, tab.id) { state?.shown = false } else Text("Opening document…", Modifier.padding(16.dp))
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
    AlertDialog(onDismissRequest = { state.browsing = false }, title = { Text("Open existing artefact") }, text = {
        Column {
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
                TextButton(onClick = { if (model.connection?.id == target.connectionId && model.workspace?.id == target.workspaceId && services.unlocked) state.open(row.kind, row.id, row.title) }, modifier = Modifier.fillMaxWidth().testTag("thread.artefacts.row.${row.id}")) { Text(row.title) }
            } }
            Text("Saved authorised records; content loads when opened.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { state.browsing = false }) { Text("Cancel") } })
}
