package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.app.media.readBounded
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.json.JSONObject
import studio.otherstuff.ambulando.app.web.EditorWebHost
import studio.otherstuff.ambulando.core.docs.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.downloadAttachment
import studio.otherstuff.ambulando.core.transport.ConnectionFailure
import androidx.compose.ui.platform.testTag
import kotlinx.serialization.json.put

/** Drives the bundled editor page for [DocumentEditController]. Results come back as the JSON text `evaluateJavascript` yields. */
internal class WebDocumentPage(private val host: () -> EditorWebHost?) : DocumentPage {
    private fun unquote(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    override suspend fun snapshot(): String? = unquote(host()?.query("window.ambulandoDocument.snapshot"))
    override fun load(payload: String) { host()?.call("window.ambulandoDocument.load", payload) }
    override fun setEditable(editable: Boolean) { host()?.call("window.ambulandoDocument.setEditable", editable.toString()) }

    override suspend fun wikiBind(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): Int? {
        val request = JSONObject().put("title", title).put("documentId", documentId)
            .put("occurrence", occurrence ?: JSONObject.NULL).put("expectedCount", expectedCount ?: JSONObject.NULL)
        return host()?.query("window.ambulandoDocument.wikiBind", request.toString())?.toIntOrNull()
    }

    override suspend fun wikiInsert(documentId: String?, title: String, range: WikiRange?): WikiPlacement? {
        val request = JSONObject().put("documentId", documentId ?: JSONObject.NULL).put("title", title)
        range?.let { request.put("from", it.from).put("to", it.to) }
        val placed = unquote(host()?.query("window.ambulandoDocument.wikiInsert", request.toString())) ?: return null
        val o = runCatching { JSONObject(placed) }.getOrNull() ?: return null
        return WikiPlacement(o.optInt("occurrence", 0), o.optInt("count", 0))
    }

    override suspend fun wikiSelection(): WikiSelection? {
        val raw = unquote(host()?.query("window.ambulandoDocument.wikiSelection")) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return WikiSelection(WikiRange(o.optInt("from"), o.optInt("to")), o.optString("text"))
    }

    override fun wikiTitles(titles: Map<String, String>) { host()?.call("window.ambulandoDocument.wikiTitles", JSONObject(titles).toString()) }
}

/** The original bundled lossless editor, with device-persisted drafts, preserved reviewable reconciliation and lease-checked saves. */
@Composable internal fun DocumentEditor(model: AppModel, docId: String, close: () -> Unit) {
    val c = model.connection ?: return
    val w = model.workspace ?: return
    val loaded by produceState<Pair<Boolean, studio.otherstuff.ambulando.core.transport.DocRecord?>>(false to null, c.id, w.id, docId) {
        value = true to model.store.reader.doc(w.id, docId)
    }
    val record = loaded.second
    if (record == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TextButton(onClick = close) { Text("Back") }
            Text(if (loaded.first) "Document not cached. Go back, unlock and open it again." else "Opening editor; your draft stays on this device.", Modifier.padding(12.dp))
        }
        return
    }
    DocumentEditorBody(model, c, w, record, close)
}

@Composable private fun DocumentEditorBody(
    model: AppModel, c: ConnectionRecord, w: studio.otherstuff.ambulando.core.transport.WorkspaceRecord, record: studio.otherstuff.ambulando.core.transport.DocRecord, close: () -> Unit,
) {
    val docId = record.id
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember(docId) { mutableStateOf<EditorWebHost?>(null) }
    val page = remember(docId) { WebDocumentPage { host } }
    var notice by remember(docId) { mutableStateOf<String?>(null) }
    var ready by remember(docId) { mutableStateOf(false) }
    var hasSelection by remember(docId) { mutableStateOf(false) }
    var anchor by remember(docId) { mutableStateOf<JSONObject?>(null) }
    var showReview by remember(docId) { mutableStateOf(false) }
    var showTakeover by remember(docId) { mutableStateOf(false) }
    var showVersions by remember(docId) { mutableStateOf(false) }
    var closing by remember(docId) { mutableStateOf(false) }
    val inserted = remember(docId) { mutableStateListOf<String>() }
    val uploadTarget = remember(docId) { ChatTarget(c.id, w.id, "content-doc-$docId") }
    val uploadRows by model.uploads.items.collectAsState()
    var visitEpoch by remember(docId) { mutableIntStateOf(0) }
    var visible by remember(docId) { mutableStateOf(true) }

    val controller = remember(docId, c.id, w.id) {
        lateinit var self: DocumentEditController
        self = DocumentEditController(
            record, c, w, model.store, RunnerAuthority(model.documents, c, w), scope,
            isUnlocked = { model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked },
            uploadsPending = { inserted.isNotEmpty() || UploadToken.containsAny(self.currentContent) },
        )
        self
    }
    val s by controller.state.collectAsState()

    // Wiki behaviour for this screen: it reads the local store, keeps the draft safe before leaving and binds through the editor.
    val wiki = remember(controller) {
        WikiLinkController(docId, appWikiEnvironment(model, c, w, docId,
            visit = { WikiVisit(model.connection?.id, model.workspace?.id ?: "", visible && !closing, visitEpoch, c.signerNpub, model.wikiContextEpoch) },
            beforeLeaving = { controller.checkpoint() && controller.persistReview() },
            navigate = { documentId ->
                // The draft is stored; leave the editor (releasing its lease) and open the linked page. Returning later resumes the draft.
                scope.launch {
                    closing = true
                    try { controller.close() } finally { model.editorDoc = null; model.pendingRecordOpen = "document" to documentId }
                }
            }))
    }
    wiki.editor = controller
    val wikiState by wiki.state.collectAsState()
    LaunchedEffect(wikiState.pendingOpen) { if (wikiState.pendingOpen != null) wiki.openPendingCreated() }
    val titles = remember(wikiState.context) { wikiState.context.docs.associate { it.id to it.title } }
    LaunchedEffect(titles, ready) { if (ready && titles.isNotEmpty()) page.wikiTitles(titles) }

    val uploadHost = remember(docId) { object : UploadTokenHost {
        override val uploadTarget = uploadTarget
        override val uploadText get() = controller.currentContent + inserted.joinToString("\n")
        override fun replaceUploadToken(token: String, replacement: String): Boolean {
            if (!uploadText.contains(token)) return false
            host?.call("window.ambulandoDocument.replaceUpload", token, editorUploadNode(replacement)) ?: return false
            inserted.remove(token)
            scope.launch { controller.checkpoint() }
            return true
        }
    } }
    LaunchedEffect(ready) { if (ready) model.uploads.attach(uploadHost) }
    DisposableEffect(uploadHost) { onDispose { model.uploads.detach(uploadHost) } }
    DisposableEffect(controller) {
        WikiEditors.register(controller)
        onDispose {
            WikiEditors.unregister(controller)
            // Android can recreate the activity (rotation, theme, font size) without Back: the draft is stored and the lease released, so the
            // reopened editor takes over its own document instead of waiting out the old session's lease.
            if (!closing) model.launchDetached { runCatching { controller.checkpoint(); controller.persistReview() }; controller.close() }
        }
    }
    fun paste(item: PastedItem) {
        val tokens = model.uploads.paste(listOf(item), uploadTarget, w.appNpub, "doc-rich")
        inserted.addAll(tokens)
        host?.call("window.ambulandoDocument.insertUploads", org.json.JSONArray(tokens).toString())
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val image = type.startsWith("image/")
            paste(PastedItem(if (image) UploadToken.Kind.IMAGE else UploadToken.Kind.FILE, mimeType = type, read = {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(32 * 1024 * 1024) } ?: error("Cannot read attachment")
                if (image) studio.otherstuff.ambulando.app.media.ImagePrivacy.strippedPng(bytes) else bytes
            }))
        }
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, controller) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // Android may end the process right after stop: local durability first, then a best-effort cloud flush.
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { visible = false; visitEpoch++; scope.launch { controller.setActive(false) } }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { visible = true; visitEpoch++; scope.launch { controller.setActive(true) } }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun finish() {
        if (closing) return
        closing = true
        // Nothing is thrown away on the way out: the page's last text is stored as the draft and the review work is kept.
        scope.launch(NonCancellable) {
            try { controller.checkpoint(); controller.persistReview(); controller.close() } finally { close() }
        }
    }
    BackHandler { finish() }

    LaunchedEffect(controller) {
        try {
            controller.load()
            if (controller.mode is DocumentEditController.Mode.Unavailable) notice = (controller.mode as DocumentEditController.Mode.Unavailable).reason
            else if (controller.prepareOpening()) controller.holdLease()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = (e as? UserFacingError)?.userMessage ?: ConnectionFailure.userMessage(e) }
    }
    DisposableEffect(docId) {
        val made = EditorWebHost.create(context, "document.html", setOf("document", "documentFile"), 45 * 1024 * 1024) { channel, raw ->
            if (channel == "documentFile") {
                scope.launch {
                    try {
                        val fields = withContext(Dispatchers.Default) { raw.split('\n', limit = 4) }
                        if (fields.size == 4) {
                            val type = fields[2]; val image = type.startsWith("image/")
                            paste(PastedItem(if (image) UploadToken.Kind.IMAGE else UploadToken.Kind.FILE, fields[1], type, read = {
                                val bytes = android.util.Base64.decode(fields[3], android.util.Base64.DEFAULT)
                                require(bytes.size <= 32 * 1024 * 1024)
                                if (image) studio.otherstuff.ambulando.app.media.ImagePrivacy.strippedPng(bytes) else bytes
                            }))
                        }
                    } catch (e: Exception) { notice = e.message }
                }
            } else scope.launch { try {
                val event = withContext(Dispatchers.Default) { JSONObject(raw) }
                when (event.optString("type")) {
                    "ready" -> { ready = true; controller.pageAttached(page) }
                    "change" -> controller.pageChanged(event.getString("content"))
                    "state" -> hasSelection = event.optJSONObject("active")?.optBoolean("selection") == true
                    "anchor" -> anchor = event.optJSONObject("anchor")
                    "wiki" -> {
                        val from = if (event.has("from") && !event.isNull("from")) event.getInt("from") else null
                        val to = if (event.has("to") && !event.isNull("to")) event.getInt("to") else null
                        wiki.pageWiki(event.optString("action"), event.optString("documentId").takeIf { it.isNotEmpty() && it != "null" }, event.optString("title"),
                            event.optInt("occurrence", 0), event.optInt("count", 0), if (from != null && to != null) WikiRange(from, to) else null, event.optString("message").takeIf { it.isNotEmpty() })
                    }
                    "wiki-query" -> wiki.pageWikiQuery(event.optBoolean("active"), event.optString("query"),
                        if (event.has("from")) event.optInt("from") else null, if (event.has("to")) event.optInt("to") else null)
                    "need-image" -> scope.launch {
                        val id = event.optString("objectId")
                        if (studio.otherstuff.ambulando.core.transport.isUUID(id)) {
                            val cache = model.fileCache()
                            model.sync.downloadAttachment(c, w, id, cache)
                            repeat(60) {
                                val file = withContext(Dispatchers.IO) { cache.attachment(w.id, id) }
                                if (file != null) {
                                    val data = withContext(Dispatchers.Default) { "data:image/png;base64," + android.util.Base64.encodeToString(studio.otherstuff.ambulando.app.media.ImagePrivacy.strippedPng(file.readBytes()), android.util.Base64.NO_WRAP) }
                                    host?.call("window.ambulandoDocument.setImage", id, data)
                                    return@launch
                                }
                                delay(1000)
                            }
                        }
                    }
                    "error" -> controller.pageError(event.optString("message"))
                    "loaded" -> controller.pageLoaded(event.optBoolean("ok"), event.optString("message").takeIf { it.isNotEmpty() })
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "Editor message could not be read; the previous draft is preserved." } }
        }
        made.onSuccess { host = it; it.load() }.onFailure { notice = it.message }
        onDispose { made.getOrNull()?.destroy(); host = null }
    }

    val saving = s.saving
    val canType = ready && !saving && !s.followingRemote && !s.permissionDenied && !closing
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (model.environment.isSample) Text("Sample data", style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = ::finish, enabled = !saving, modifier = Modifier.testTag("doc.editor.back")) { Text("Back") }
            TextButton(enabled = !saving && ready && host?.failure == null && inserted.isEmpty() && !s.permissionDenied && !closing, modifier = Modifier.testTag("doc.editor.save"), onClick = {
                scope.launch { controller.save() }
            }) { Text(if (saving) "Saving…" else "Save") }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = ready && !saving && !s.permissionDenied, modifier = Modifier.testTag("doc.editor.versions"), onClick = { showVersions = true }) { Text("Versions") }
        }
        OutlinedTextField(s.title, { controller.setTitle(it) }, label = { Text("Title") }, enabled = !s.followingRemote && !s.permissionDenied, modifier = Modifier.fillMaxWidth().testTag("doc.editor.title"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("bold" to "Bold", "italic" to "Italic", "heading" to "Heading", "bulletList" to "Bullets", "orderedList" to "Numbered", "taskList" to "Checklist", "blockquote" to "Quote", "codeBlock" to "Code", "undo" to "Undo", "redo" to "Redo").forEach { (command, label) ->
                TextButton(enabled = canType, onClick = { host?.call("window.ambulandoDocument.command", command, "{}") }) { Text(label) }
            }
            TextButton(enabled = canType, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Attach") }
            TextButton(enabled = canType, onClick = { scope.launch { wiki.openToolbarPicker() } }, modifier = Modifier.testTag("doc.editor.link")) { Text("Link") }
            TextButton(enabled = canType && hasSelection && !s.dirty, onClick = { host?.call("window.ambulandoDocument.captureAnchor") },
                modifier = Modifier.testTag("doc.editor.comment")) { Text("Comment") }
        }
        uploadRows.filter { it.target == uploadTarget && it.markdown == null }.forEach { upload -> Row { Text(upload.filename); if (upload.retryable) TextButton(onClick = { model.uploads.retry(upload.id) }) { Text("Retry") }; TextButton(onClick = { model.uploads.remove(upload.id); inserted.remove(upload.token) }) { Text("Remove") } } }
        if (hasSelection && s.dirty) Text("Save your changes before commenting on a selection.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)

        // Authority: who may type, and the explicit way to take over from another editor.
        if (s.authorityLost || s.permissionDenied) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("doc.editor.authority")) {
                Text(if (s.permissionDenied) s.leaseState.takeIf { it.startsWith("Read only") } ?: "Read only. Your local work remains on this device." else s.leaseState, style = MaterialTheme.typography.bodySmall)
                if (s.canTakeover && !s.permissionDenied) TextButton(onClick = { showTakeover = true }, modifier = Modifier.testTag("doc.editor.takeover")) { Text("Take over editing…") }
            }
        } else if (s.concurrentEditing) Text("Others can edit this document at the same time; newer versions are offered for review.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)

        // A newer shared version never replaces the working copy; review is explicit.
        if (s.remoteVersion != null || s.hasReviewWork && (s.mergeReady || s.pendingMergedSave)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (s.remoteVersion != null) "Newer version available" else "Merge in progress", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { if (controller.openReview()) showReview = true } }, modifier = Modifier.testTag("doc.editor.review")) { Text("Review changes") }
            }
        } else if (s.hasReviewWork) TextButton(onClick = { scope.launch { if (controller.openReview()) showReview = true } }, modifier = Modifier.testTag("doc.editor.preserved")) { Text("Preserved drafts") }

        s.status?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("doc.editor.status"),
            color = when (s.tone) { DocumentEditController.Tone.ERROR -> MaterialTheme.colorScheme.error; DocumentEditController.Tone.WARNING -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant },
            style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, Modifier.padding(12.dp)) }
        WikiSheets(wiki, wikiState, scope)
        WikiQueryPanel(wiki, wikiState, scope)
        anchor?.let { captured -> SelectionCommentDialog(model, docId, captured, s.localBaseVersion ?: record.rowVersion.toInt()) { anchor = null } }
        if (s.mode == DocumentEditController.Mode.Loading) Text("Opening editor; your draft stays on this device.", Modifier.padding(12.dp))
        host?.failure?.let { Text(it) }
        host?.let { webHost -> AndroidView({ webHost.webView }, Modifier.weight(1f).fillMaxWidth()) }
    }
    if (showReview) DocumentReviewDialog(controller) { showReview = false }
    if (showTakeover) TakeoverDialog(controller) { showTakeover = false }
    if (showVersions) SavedVersionsDialog(controller) { showVersions = false }
}

/**
 * A comment on selected text: the editor's selection anchor (quote, offsets and lines, as the web writes them) bound to
 * the stored document version, sent through the durable comment queue with the same mention handling as other comments.
 */
@Composable private fun SelectionCommentDialog(model: AppModel, docId: String, anchor: JSONObject, baseRowVersion: Int, dismiss: () -> Unit) {
    val w = model.workspace ?: return
    val draft = studio.otherstuff.ambulando.app.ui.work.rememberCommentDraft(docId, anchor.optString("anchor_quote"))
    AlertDialog(onDismissRequest = dismiss, title = { Text("Comment on selection") }, text = {
        Column {
            Text("“" + anchor.optString("anchor_quote").take(160) + "”", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            studio.otherstuff.ambulando.app.ui.work.MentionTextEntry(draft.text, { draft.text = it }, draft.mentions, { draft.mentions = it },
                { query -> model.store.reader.mentionCandidates(w.id, query, viewerNpub = model.connection?.signerNpub) }, "Add comment", Modifier.fillMaxWidth().padding(top = 8.dp), Modifier.fillMaxWidth().testTag("doc.selection.field"))
        }
    }, confirmButton = { TextButton(enabled = !draft.isBlank, modifier = Modifier.testTag("doc.selection.send"), onClick = {
        val encoded = draft.encoded()
        if (model.submit(WriteIntent(WriteKind.DOC_COMMENT, docId, kotlinx.serialization.json.buildJsonObject {
            put("body", encoded.body)
            put("metadata", kotlinx.serialization.json.buildJsonObject {
                anchor.optString("anchor_quote").takeIf { it.isNotEmpty() }?.let { put("anchor_quote", it) }
                listOf("anchor_line_number", "anchor_end_line_number", "anchor_start_offset", "anchor_end_offset").forEach { key ->
                    if (anchor.has(key) && !anchor.isNull(key)) put(key, anchor.getInt(key))
                }
                put("anchor_doc_row_version", baseRowVersion)
            })
        }))) dismiss()
    }) { Text("Comment") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
