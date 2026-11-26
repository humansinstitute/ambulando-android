package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.ensureActive
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
    val runtime = model.active ?: return
    val store = runtime.store
    fun currentBoundary() = model.active === runtime && model.activeBoundaryId == runtime.boundary.id && !runtime.workspace.accessRemoved && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked && model.identityState?.npub == c.signerNpub
    val api = remember(runtime,c,w,fileId) { model.fileNappletAPI(c).fencedArtefactAPI(store.reader,w,fileId,::currentBoundary) }
    var file by remember(c.id, w.id, fileId) { mutableStateOf<FileRecord?>(null) }
    var error by remember(c.id, w.id, fileId) { mutableStateOf<String?>(null) }
    val services = LocalAppServices.current
    var localOnly by remember(c.id, w.id, fileId) { mutableStateOf(false) }
    val retained by remember(c.id, w.id, fileId) {
        store.liveQuery({ it.workspaceId == w.id }) { store.reader.file(w.id, fileId) }
    }.collectAsState(null)
    LaunchedEffect(runtime, w, fileId, services.unlocked, w.accessRemoved, retained?.accessRemoved == true) {
        val cached = store.reader.file(w.id, fileId)
        localOnly = !services.unlocked || w.accessRemoved || cached?.accessRemoved == true ||
            cached?.let { store.reader.isChannelAccessRemoved(w.id, it.channelId) } == true
        if (localOnly) {
            file = cached
            error = if (cached == null) "Unlock and reconnect to open this artefact." else null
            return@LaunchedEffect
        }
        try {
            val received = runtime.sync.genericNappletRead(fileId) {
                store.reader.withArtefactAccess(w, fileId, ::currentBoundary) { api.artefactFile(w.id, w.appNpub, fileId) }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            check(currentBoundary())
            file = received
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (currentBoundary() && cached != null &&
                (e is java.io.IOException || e is TowerError.Http && e.status in setOf(403,404))) {
                if (e is TowerError.Http) store.writer.markAccessRemoved(w.id, AccessTarget.File(fileId))
                file = store.reader.file(w.id,fileId) ?: cached
                localOnly = true
            } else error = e.message ?: "This artefact is unavailable."
        }
    }
    val exit = studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit.current
    val current = file
    if (current == null) { Text(error ?: "Loading artefact…", Modifier.padding(16.dp)); return }
    // The normal File reader can preview/share retained verified bytes without server authority.
    if (localOnly) { NativeFiles(model, fileId, direct = false); return }
    when (ArtefactType.of(current)) {
        ArtefactType.WHITEBOARD -> {
            var reference by remember(current) { mutableStateOf<BoardReference?>(null) }
            LaunchedEffect(current) {
                try {
                    val versions = runtime.sync.genericNappletRead(fileId) { TowerBoardTransport(model.identity, runtime.directTransport, c, w, fileId, current.displayName).versions() }
                    val version = versions.second.firstOrNull { it.id == versions.first } ?: error("No saved board version")
                    reference = BoardReference(fileId, version.id, version.number, version.sha256, 0, current.updatedAt, current.raw.bool("can_write") == true)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
            }
            reference?.let { board -> BoardEditor(model, board, current.displayName, provenance = { ArtefactProvenance(current) }, exportFile = current) { exit?.invoke() } } ?: Text(error ?: "Loading whiteboard…")
        }
        ArtefactType.SYSTEM_FLOW -> SystemFlowFile(model, current)
        ArtefactType.HTML -> HtmlFileArtefact(model, current)
        null -> NativeFiles(model, fileId, direct = false)
    }
}


private data class HtmlArtefactRead(
    val file: FileRecord,
    val versions: FileVersionList,
    val payload: HtmlArtefactPayload,
    val html: String,
    val binding: GenericNapplet.Binding?,
)

@Composable internal fun HtmlFileArtefact(model: AppModel, file: FileRecord, appMode: Boolean = false) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val runtime = model.active ?: return
    val store = runtime.store
    fun currentBoundary() = model.active === runtime && !runtime.workspace.accessRemoved &&
        model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked && model.identityState?.npub == c.signerNpub
    val api = remember(runtime,c,w,file.id) { model.fileNappletAPI(c).fencedArtefactAPI(store.reader,w,file.id,::currentBoundary) }
    var reload by remember(file.id) { mutableStateOf(0) }
    var html by remember(file.id) { mutableStateOf<String?>(null) }
    var htmlPayload by remember(file.id) { mutableStateOf<HtmlArtefactPayload?>(null) }
    var verifiedFile by remember(file.id) { mutableStateOf(file) }
    var generic by remember(file.id) { mutableStateOf<GenericNapplet.Binding?>(null) }
    var versions by remember(file.id) { mutableStateOf<FileVersionList?>(null) }
    val initialThreadPin = LocalThreadArtefactTab.current?.let { it.pinnedRevision ?: it.selectedRevision }
    var selected by remember(file.id) { mutableStateOf(initialThreadPin) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var source by remember(file.id) { mutableStateOf<String?>(null) }
    var base by remember(file.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val draftFile = remember(c.id, w.id, file.id) { model.htmlDraftFile(file.id) }
    val drafts = remember(draftFile) { HtmlDraftStore(draftFile) }
    var recovery by remember(draftFile) { mutableStateOf<HtmlDraftStore.Draft?>(null) }
    val sourceSave = remember(drafts) { HtmlSourceSave(drafts) }

    val threadTab = LocalThreadArtefactTab.current
    var pendingThreadVersion by remember(file.id) { mutableStateOf<String?>(null) }
    suspend fun saveThreadSource(): String? {
        val edit = recovery ?: drafts.load() ?: return null
        check(!saving && currentBoundary()) { "HTML is busy or locked. Retry Send." }
        saving = true
        try {
            val savedVersion = sourceSave.save(edit, upload = { pending -> api.store(w.id, w.appNpub, pending.source.toByteArray(), "text/html", "artefact.html", null) { } },
                commit = { pending -> api.addFileVersion(w.id, w.appNpub, file.id, pending.base, requireNotNull(pending.uploadedObjectId), pending.mutationId) },
                head = { api.genericNappletHead(w.id,w.appNpub,file.id).currentVersionId },
                read = { id -> api.savedHtmlBytes(w.id,w.appNpub,file.id,id) },
                checkCurrent = { store.reader.withArtefactAccess(w,file.id,::currentBoundary) { Unit } })
            source = null; recovery = null
            return savedVersion
        } catch (e: Exception) { recovery = drafts.load(); throw e }
        finally { saving = false }
    }
    SideEffect {
        threadTab?.title = file.displayName
        threadTab?.versionLabel = if (pendingThreadVersion != null) "Saved revision awaiting readback" else htmlPayload?.let { "${if (selected != null) "Pinned " else ""}v${it.versionNumber}" } ?: "Loading revision…"
        if (htmlPayload != null) { threadTab?.pinnedRevision = selected; threadTab?.selectedRevision = selected ?: pendingThreadVersion ?: htmlPayload?.versionId }
        threadTab?.checkpoint = { recovery?.let { drafts.checkpoint(it) }; !saving }
        threadTab?.prepare = {
            check(currentBoundary()) { "Unlock the original workspace before Send." }
            val loaded = htmlPayload ?: error("HTML is still loading. Retry Send.")
            val saved = saveThreadSource()
            if (saved != null && selected == null) {
                pendingThreadVersion = saved
                threadTab?.selectedRevision = saved
            }
            val exact = pendingThreadVersion
            val payload = if (exact != null && selected == null) api.artefactHTMLPayload(w.id,w.appNpub,file.id,exact) else loaded
            if (exact != null && selected == null) {
                check(payload.versionId == exact) { "Saved HTML revision readback did not match. Retry Send." }
                htmlPayload = payload; html = payload.html
                versions = versions?.copy(currentVersionId = payload.currentVersionId ?: exact)
                pendingThreadVersion = null
            }
            studio.otherstuff.ambulando.core.chat.ArtefactChatReference("file", file.id, payload.versionId, selected != null, payload.digest)
        }
        threadTab?.validate = { ref -> pendingThreadVersion == null && !saving && currentBoundary() && htmlPayload?.versionId == ref.revision && recovery == null }
    }
    DisposableEffect(threadTab) { onDispose { threadTab?.prepare = null; threadTab?.checkpoint = null; threadTab?.validate = null } }

    // Visual review state fenced to (connection, workspace, file, activeBoundaryId) so switching boundaries clears all state.
    val reviewBoundary = listOf(c.id, w.id, file.id, model.activeBoundaryId)
    var commentMode by remember(reviewBoundary) { mutableStateOf(false) }
    var commentsSheetOpen by remember(reviewBoundary) { mutableStateOf(false) }
    var showEarlierUnresolved by remember(reviewBoundary) { mutableStateOf(true) }
    var pendingTarget by remember(reviewBoundary) { mutableStateOf<HtmlCommentAnchor?>(null) }
    var focusedThreadId by remember(reviewBoundary) { mutableStateOf<String?>(null) }
    var focusThreadNonce by remember(reviewBoundary) { mutableStateOf<Pair<String, Int>?>(null) }
    var commentList by remember(reviewBoundary) { mutableStateOf<FileCommentList?>(null) }
    var commentsBusy by remember(reviewBoundary) { mutableStateOf(false) }
    var commentsError by remember(reviewBoundary) { mutableStateOf<String?>(null) }
    var commentsReload by remember(reviewBoundary) { mutableStateOf(0) }
    var anchorMatches by remember(reviewBoundary) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val commentDraftStore = remember(draftFile) { FileCommentDraftStore(File(draftFile.parentFile, "${file.id}-comments.json")) }
    var commentDrafts by remember(draftFile) { mutableStateOf<Map<String, FileCommentDraftStore.Draft>>(emptyMap()) }
    var versionMenu by remember(reviewBoundary) { mutableStateOf(false) }

    fun preserveSource(text: String) {
        val expected = base ?: return
        if (recovery?.accepted == true) return
        source = text
        val edit = HtmlDraftStore.Draft(expected, text)
        recovery = edit
        val ticket = drafts.ticket()
        // View-model ownership survives closing this dialog or navigating away. Writes are ordered.
        model.act {
            try { drafts.save(edit, ticket) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (currentBoundary()) error = e.message ?: "Could not preserve your HTML source." }
        }
    }
    LaunchedEffect(commentDraftStore) {
        commentDrafts = commentDraftStore.loadAll()
    }
    LaunchedEffect(runtime, w, file.id, selected, reload) {
        try {
            html = null; htmlPayload = null; generic = null; error = null; anchorMatches = emptyMap()
            val received = runtime.sync.genericNappletRead(file.id) {
              store.reader.withArtefactAccess(w, file.id, ::currentBoundary) {
                val verified = api.artefactFile(w.id,w.appNpub,file.id)
                val history = if(selected==null && verified.raw.obj("metadata")?.obj("napplet")?.get("version")==JsonPrimitive(2)) api.genericNappletHead(w.id,w.appNpub,file.id) else try { api.fileVersions(w.id, w.appNpub, file.id) } catch (_: TowerError.ListLimitReached) { api.genericNappletHead(w.id,w.appNpub,file.id) }
                val payload = api.artefactHTMLPayload(w.id, w.appNpub, file.id, selected ?: history.currentVersionId)
                val rendered = payload.html
                val parsed = GenericNapplet.parse(rendered, verified.raw.obj("metadata") ?: JsonObject(emptyMap()))
                val manifest = nappletManifest(rendered)
                val bound = manifest?.let { runCatching { FileNapplet.binding(file.raw.getValue("metadata").jsonObject, it) }.getOrNull() }
                val legacyReport=manifest?.let { runCatching { WorkspaceReport.binding(file.raw.getValue("metadata").jsonObject,it) }.getOrNull() }
                require(parsed!=null || bound!=null || legacyReport!=null || !GenericNapplet.claimsRuntime(rendered,verified.raw.obj("metadata") ?: JsonObject(emptyMap()))) { "This napplet declaration is invalid or unsupported." }
                val preview = if (selected == null && (parsed != null || bound != null || manifest?.let { runCatching { WorkspaceReport.binding(file.raw.getValue("metadata").jsonObject, it) }.getOrNull() } != null)) {
                    val version = history.versions.first { it.id == history.currentVersionId }
                    require(version.contentType?.substringBefore(';') == "text/html" && version.sizeBytes in 1..FileNapplet.MAX_BYTES.toLong() && version.sha256 != null)
                    val bytes = api.fileVersionBytes(w.id, w.appNpub, file.id, version)
                    FileNapplet.verifiedHTML(bytes, rendered, requireNotNull(version.sizeBytes), requireNotNull(version.sha256))
                } else rendered
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(currentBoundary())
                HtmlArtefactRead(verified,history,payload,preview,parsed)
              }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            check(currentBoundary())
            if (received.binding != null) {
                store.reader.withArtefactAccess(runtime.workspace,file.id,::currentBoundary) {
                    store.writer.cacheVerifiedArtefact(received.file,::currentBoundary)
                }
            }
            verifiedFile = received.file; versions = received.versions; htmlPayload = received.payload; generic = received.binding; html = received.html
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message; html = null }
    }
    var commentsAccessRevoked by remember(model.activeBoundaryId, file.id) { mutableStateOf(false) }
    LaunchedEffect(runtime, w, file.id, commentsReload, reload, commentsSheetOpen) {
        if (appMode) return@LaunchedEffect
        suspend fun fetchAllComments() {
            try {
                val list = runtime.sync.genericNappletRead("comments:${file.id}") {
                    store.reader.withArtefactAccess(w, file.id, ::currentBoundary) {
                        api.allFileComments(w.id, w.appNpub, file.id)
                    }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(currentBoundary())
                commentList = list
                commentsAccessRevoked = false
                commentsError = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (!currentBoundary()) return
                if (e is TowerError.Http && (e.status == 403 || e.status == 404)) {
                    commentList = null
                    pendingTarget = null
                    focusedThreadId = null
                    anchorMatches = emptyMap()
                    commentMode = false
                    commentDrafts = emptyMap()
                    commentsAccessRevoked = true
                    commentsError = "Comments are unavailable for this artefact."
                } else {
                    commentsError = e.message
                }
            }
        }
        fetchAllComments()
        // Lifecycle-bounded periodic refresh so concurrent reviewers' comments appear without reopening, preserving local drafts.
        while (currentBoundary()) {
            kotlinx.coroutines.delay(15_000)
            if (!currentBoundary()) break
            fetchAllComments()
        }
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
    studio.otherstuff.ambulando.app.ui.shell.OwnEditorNavigation()
    val exit = studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit.current
    BackHandler(enabled = studio.otherstuff.ambulando.app.ui.shell.LocalEditorActive.current && source == null) { exit?.invoke() }
    var menu by remember(file.id) { mutableStateOf(false) }
    var exporting by remember(file.id) { mutableStateOf(false) }
    val viewedVersionId = htmlPayload?.versionId?.takeIf { it.isNotBlank() } ?: selected ?: versions?.currentVersionId
    val viewedVersionNumber: Int = htmlPayload?.versionNumber ?: versions?.versions?.firstOrNull { it.id == viewedVersionId }?.versionNumber?.toInt() ?: 1
    val isViewingLatest = selected == null || (versions?.currentVersionId != null && selected == versions?.currentVersionId)
    val availableVersions = remember(htmlPayload, versions) {
        val fromPayload = htmlPayload?.versions.orEmpty()
        if (fromPayload.isNotEmpty()) fromPayload.sortedByDescending { it.versionNumber }
        else versions?.versions?.map {
            HtmlArtefactVersion(it.id, it.versionNumber.toInt(), it.createdAt, null, it.contentType, it.sizeBytes)
        }?.sortedByDescending { it.versionNumber }.orEmpty()
    }
    val visibleThreads = remember(commentList, viewedVersionId, isViewingLatest, showEarlierUnresolved) {
        groupFileCommentThreads(
            comments = commentList?.comments.orEmpty(),
            viewedVersionId = viewedVersionId,
            isViewingLatest = isViewingLatest,
            includeEarlierUnresolved = showEarlierUnresolved,
        )
    }
    val openThreadCount = remember(visibleThreads) { visibleThreads.count { !it.root.isResolved } }
    val anchorSpecs = remember(visibleThreads) {
        visibleThreads.mapNotNull { thread ->
            val anchor = thread.root.anchor ?: return@mapNotNull null
            if (anchor.kind != "html_element") return@mapNotNull null
            studio.otherstuff.ambulando.app.web.HtmlAnchorTargetSpec(
                threadId = thread.root.id,
                anchor = anchor,
                resolved = thread.root.isResolved,
                replyCount = thread.replies.size,
            )
        }
    }
    val effectivePermissions = when {
        commentsAccessRevoked -> FileCommentPermissions(canRead = false, canComment = false, canResolveAny = false, canEditSource = false)
        else -> commentList?.permissions ?: FileCommentPermissions(
            canRead = true,
            canComment = verifiedFile.raw.bool("can_write") == true,
            canResolveAny = verifiedFile.raw.bool("can_write") == true,
            canEditSource = verifiedFile.raw.bool("can_write") == true,
        )
    }
    fun shortAuthor(npub: String?): String {
        val clean = npub?.trim().orEmpty()
        if (clean.isEmpty()) return "Member"
        return if (clean.length > 16) "${clean.take(10)}…${clean.takeLast(4)}" else clean
    }

    if (exporting) ArtefactFileActions(model, verifiedFile) { exporting = false }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { exit?.invoke() }, modifier = Modifier.testTag("artefacts.back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(file.displayName, Modifier.weight(1f).testTag("artefacts.fileTitle"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Compact version selector directly in control bar; browsing history never mutates current head.
            Box {
                TextButton(
                    onClick = { versionMenu = true },
                    enabled = !saving && availableVersions.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("artefacts.versionPicker"),
                ) {
                    Text(
                        text = if (isViewingLatest) "v$viewedVersionNumber" else "v$viewedVersionNumber · History",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Switch version", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(versionMenu, { versionMenu = false }) {
                    availableVersions.forEach { v ->
                        val isHead = v.id == (htmlPayload?.currentVersionId ?: versions?.currentVersionId)
                        DropdownMenuItem(
                            text = { Text(if (isHead) "v${v.versionNumber} (Latest)" else "v${v.versionNumber}") },
                            enabled = !saving,
                            onClick = {
                                selected = if (isHead) null else v.id
                                commentMode = false
                                pendingTarget = null
                                versionMenu = false
                            },
                            modifier = Modifier.testTag("artefacts.version.v${v.versionNumber}"),
                        )
                    }
                }
            }
            // Explicit Comment mode toggle for Android tap-to-target review.
            IconButton(
                onClick = { commentMode = !commentMode },
                enabled = html != null && !saving && !commentsAccessRevoked,
                modifier = Modifier.testTag("artefacts.commentMode"),
            ) {
                Icon(
                    imageVector = if (commentMode) Icons.Outlined.Close else Icons.Outlined.AddComment,
                    contentDescription = if (commentMode) "Exit comment mode" else "Comment mode",
                    tint = if (commentMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            // Threads button with open count.
            TextButton(
                onClick = { commentsSheetOpen = true },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier = Modifier.testTag("artefacts.threads"),
            ) {
                Icon(Icons.Outlined.Forum, contentDescription = "Comments", modifier = Modifier.size(18.dp))
                if (openThreadCount > 0) {
                    Spacer(Modifier.width(3.dp))
                    Text("$openThreadCount", style = MaterialTheme.typography.labelMedium)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("artefacts.menu")) { Icon(Icons.Outlined.MoreHoriz, "Artefact options") }
                DropdownMenu(menu, { menu = false }) {
                    ArtefactProvenance(file)
                    DropdownMenuItem(text = { Text("Download / share File") }, onClick = { menu = false; exporting = true }, modifier = Modifier.testTag("artefacts.export"))
                    RecordPin(model, PinRecord.ResourceType.FILE, file.id, inMenu = true)
                    DropdownMenuItem(text = { Text("Refresh comments") }, onClick = { menu = false; commentsReload++ }, modifier = Modifier.testTag("artefacts.refreshComments"))
                    DropdownMenuItem(text = { Text(if (selected == null) "Edit source" else "Restore source") }, enabled = verifiedFile.raw.bool("can_write") == true && !saving, onClick = {
                        menu = false
                        model.act {
                            try {
                                val restored = drafts.load()
                                check(currentBoundary())
                                if (restored != null) {
                                    base = restored.base
                                    recovery = restored
                                    source = restored.source
                                    error = if (restored.accepted) "This edit was saved. Verify its saved content before starting another edit." else "Restored your unsaved source. Saving still checks the version it started from."
                                    return@act
                                }
                                val list = runtime.sync.genericNappletRead(file.id) { store.reader.withArtefactAccess(w,file.id,::currentBoundary) { try { api.fileVersions(w.id, w.appNpub, file.id) } catch (_: TowerError.ListLimitReached) { api.genericNappletHead(w.id,w.appNpub,file.id) } } }
                                val version = list.versions.first { it.id == (selected ?: list.currentVersionId) }
                                require(version.contentType?.substringBefore(';') == "text/html") { "This HTML manifest must be edited by its authoring tool." }
                                val bytes = runtime.sync.genericNappletRead(file.id) { store.reader.withArtefactAccess(w,file.id,::currentBoundary) { api.fileVersionBytes(w.id, w.appNpub, file.id, version) } }
                                check(currentBoundary())
                                source = bytes.toString(Charsets.UTF_8); base = list.currentVersionId
                                recovery = HtmlDraftStore.Draft(requireNotNull(base), requireNotNull(source))
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                        }
                    }, modifier = Modifier.testTag("artefacts.editSource"))
                }
            }
        }
        if (commentMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("artefacts.commentModeBanner"),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "Comment mode: tap any element in the HTML to attach feedback",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { commentMode = false }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Done")
                    }
                }
            }
        }
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        html?.let { renderedHtml ->
            val capturedVersionId = viewedVersionId
            val capturedVersionNumber = viewedVersionNumber
            key(file.id, capturedVersionId, renderedHtml) {
                studio.otherstuff.ambulando.app.web.SandboxedHtmlScreen(
                    html = renderedHtml,
                    label = file.displayName,
                    showLabel = false,
                    commentMode = commentMode,
                    anchorSpecs = anchorSpecs,
                    focusThreadNonce = focusThreadNonce,
                    onTargetSelected = { anchor ->
                        // Fence against late callbacks from an older WebView after switching versions or boundary.
                        if (!currentBoundary() || viewedVersionId != capturedVersionId) return@SandboxedHtmlScreen
                        val enriched = anchor.copy(
                            fileVersionId = capturedVersionId,
                            fileVersionNumber = capturedVersionNumber,
                            versionDigest = htmlPayload?.digest?.takeIf { it.length == 64 },
                        )
                        pendingTarget = enriched
                        commentMode = false
                        commentsSheetOpen = true
                    },
                    onAnchorMatches = { matches ->
                        if (currentBoundary() && viewedVersionId == capturedVersionId) anchorMatches = matches
                    },
                    onBadgeTapped = { threadId ->
                        if (currentBoundary() && viewedVersionId == capturedVersionId) {
                            focusedThreadId = threadId
                            commentsSheetOpen = true
                        }
                    },
                )
            }
        }
        if (html == null && error == null) Text("Loading HTML…")
    }
    if (commentsSheetOpen && viewedVersionId != null) {
        HtmlArtefactCommentsSheet(
            viewedVersionId = viewedVersionId,
            viewedVersionNumber = viewedVersionNumber,
            isViewingLatest = isViewingLatest,
            threads = visibleThreads,
            permissions = effectivePermissions,
            anchorMatches = anchorMatches,
            pendingTarget = pendingTarget,
            focusedThreadId = focusedThreadId,
            showEarlierUnresolved = showEarlierUnresolved,
            onToggleShowEarlier = { showEarlierUnresolved = it },
            drafts = commentDrafts,
            busy = commentsBusy,
            error = commentsError,
            authorLabel = ::shortAuthor,
            onClearPendingTarget = { pendingTarget = null },
            onDraftChange = { key, body, parentId, anchor ->
                val existing = commentDrafts[key]
                val next = FileCommentDraftStore.Draft(
                    key = key,
                    body = body,
                    clientRequestId = existing?.clientRequestId ?: UUID.randomUUID().toString(),
                    fileVersionId = viewedVersionId,
                    parentCommentId = parentId,
                    anchor = anchor,
                )
                commentDrafts = if (body.isBlank()) commentDrafts - key else commentDrafts + (key to next)
                model.act {
                    runCatching { commentDraftStore.save(next) }
                }
            },
            onSubmitRoot = { anchor, body, reqId ->
                val targetVersionId = anchor?.fileVersionId ?: viewedVersionId
                val rootKey = (anchor ?: HtmlCommentAnchor(kind = "document")).draftKey(targetVersionId)
                commentsBusy = true; commentsError = null
                model.act {
                    try {
                        api.createFileComment(
                            workspaceId = w.id,
                            appNpub = w.appNpub,
                            fileId = file.id,
                            body = body,
                            fileVersionId = targetVersionId,
                            anchor = anchor,
                            clientRequestId = reqId,
                        )
                        commentDraftStore.remove(rootKey)
                        commentDrafts = commentDrafts - rootKey
                        pendingTarget = null
                        commentsReload++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        if (currentBoundary()) commentsError = "Could not post comment (your draft is preserved): ${e.message ?: "offline"}"
                    } finally {
                        if (currentBoundary()) commentsBusy = false
                    }
                }
            },
            onSubmitReply = { parentId, body, reqId ->
                val replyKey = "reply:$parentId"
                commentsBusy = true; commentsError = null
                model.act {
                    try {
                        api.createFileComment(
                            workspaceId = w.id,
                            appNpub = w.appNpub,
                            fileId = file.id,
                            body = body,
                            parentCommentId = parentId,
                            clientRequestId = reqId,
                        )
                        commentDraftStore.remove(replyKey)
                        commentDrafts = commentDrafts - replyKey
                        commentsReload++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        if (currentBoundary()) commentsError = "Could not post reply (your draft is preserved): ${e.message ?: "offline"}"
                    } finally {
                        if (currentBoundary()) commentsBusy = false
                    }
                }
            },
            onToggleResolve = { comment ->
                val nextStatus = if (comment.isResolved) "open" else "resolved"
                commentsBusy = true; commentsError = null
                model.act {
                    try {
                        api.updateFileComment(
                            workspaceId = w.id,
                            appNpub = w.appNpub,
                            fileId = file.id,
                            commentId = comment.id,
                            rowVersion = comment.rowVersion,
                            commentStatus = nextStatus,
                        )
                        commentsReload++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        if (currentBoundary()) commentsError = e.message ?: "Could not update comment status; refresh and retry."
                    } finally {
                        if (currentBoundary()) commentsBusy = false
                    }
                }
            },
            onEditComment = { comment, body ->
                commentsBusy = true; commentsError = null
                model.act {
                    try {
                        api.updateFileComment(
                            workspaceId = w.id,
                            appNpub = w.appNpub,
                            fileId = file.id,
                            commentId = comment.id,
                            rowVersion = comment.rowVersion,
                            body = body,
                        )
                        commentsReload++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        if (currentBoundary()) commentsError = e.message ?: "Could not edit comment; refresh and retry."
                    } finally {
                        if (currentBoundary()) commentsBusy = false
                    }
                }
            },
            onDeleteComment = { comment ->
                commentsBusy = true; commentsError = null
                model.act {
                    try {
                        api.deleteFileComment(
                            workspaceId = w.id,
                            appNpub = w.appNpub,
                            fileId = file.id,
                            commentId = comment.id,
                            rowVersion = comment.rowVersion,
                        )
                        commentsReload++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        if (currentBoundary()) commentsError = e.message ?: "Could not delete comment."
                    } finally {
                        if (currentBoundary()) commentsBusy = false
                    }
                }
            },
            onJumpToVersion = { versionId ->
                val headId = htmlPayload?.currentVersionId ?: versions?.currentVersionId
                selected = if (versionId == headId) null else versionId
                commentsSheetOpen = false
            },
            onFocusTarget = { threadId ->
                val nextNonce = (focusThreadNonce?.second ?: 0) + 1
                focusThreadNonce = threadId to nextNonce
            },
            onRefresh = { commentsReload++ },
            onDismiss = { commentsSheetOpen = false },
        )
    }
    source?.let { text -> AlertDialog(onDismissRequest = { if (!saving) source = null }, title = { Text("Edit HTML source") }, text = {
        Column { OutlinedTextField(text, { preserveSource(it) }, enabled = !saving && recovery?.accepted != true, modifier = Modifier.heightIn(max = 400.dp).testTag("artefacts.source")); error?.let { Text(it) } }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        val draft = source ?: return@TextButton; val expected = base ?: return@TextButton
        saving = true
        model.act {
            try {
                store.reader.withArtefactAccess(w,file.id,::currentBoundary) { Unit }
                val edit = recovery?.takeIf { it.base == expected && it.source == draft } ?: HtmlDraftStore.Draft(expected, draft)
                sourceSave.save(edit, upload = { pending ->
                    api.store(w.id, w.appNpub, pending.source.toByteArray(), "text/html", "artefact.html", null) { }
                }, commit = { pending ->
                    api.addFileVersion(w.id, w.appNpub, file.id, pending.base, requireNotNull(pending.uploadedObjectId), pending.mutationId)
                }, head = {
                    runtime.sync.genericNappletRead(file.id) { api.genericNappletHead(w.id,w.appNpub,file.id).currentVersionId }
                }, read = { id ->
                    runtime.sync.genericNappletRead(file.id) { api.savedHtmlBytes(w.id,w.appNpub,file.id,id) }
                }, checkCurrent = {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    store.reader.withArtefactAccess(runtime.workspace,file.id,::currentBoundary) { Unit }
                })
                check(currentBoundary())
                source = null; recovery = null; selected = null; reload++
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (currentBoundary()) {
                    recovery = drafts.load()
                    error = if (recovery?.accepted == true) "Saved, but readback could not be confirmed. Your draft is kept. Retry only verifies the saved content." else e.message
                }
            }
            finally { if (currentBoundary()) saving = false }
        }
    }, modifier = Modifier.testTag("artefacts.saveSource")) { Text(if (saving) "Saving…" else if (recovery?.accepted == true) "Verify saved version" else "Save version") } }, dismissButton = { TextButton(enabled = !saving, onClick = { source = null }) { Text("Close (keep draft)") } }) }
}
