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
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import studio.otherstuff.ambulando.app.web.EditorWebHost
import studio.otherstuff.ambulando.core.docs.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.downloadAttachment

/** The original bundled lossless editor, with device-persisted drafts and lease-checked saves. */
@Composable internal fun DocumentEditor(model: AppModel, docId: String, close: () -> Unit) {
    val c = model.connection ?: return
    val w = model.workspace ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember(model) { DocumentSaveRunner(model.store, model.engine, model.identity, model.transport) }
    var host by remember(docId) { mutableStateOf<EditorWebHost?>(null) }
    var notice by remember(docId) { mutableStateOf<String?>(null) }
    var draft by remember(docId) { mutableStateOf<DocDraft?>(null) }
    var base by remember(docId) { mutableStateOf<DocumentBody?>(null) }
    var payload by remember(docId) { mutableStateOf<String?>(null) }
    var ready by remember(docId) { mutableStateOf(false) }
    var saving by remember(docId) { mutableStateOf(false) }
    var encodingFailed by remember(docId) { mutableStateOf(false) }
    var title by remember(docId) { mutableStateOf("") }
    val inserted = remember(docId) { mutableStateListOf<String>() }
    val uploadTarget = remember(docId) { ChatTarget(c.id, w.id, "content-doc-$docId") }
    val uploadRows by model.uploads.items.collectAsState()
    val commands = remember(docId) { Channel<suspend () -> Unit>(Channel.UNLIMITED) }
    LaunchedEffect(commands) { for (command in commands) try { command() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = e.message } }
    fun changed(content: String) {
        val decoded = base ?: return
        commands.trySend {
            val old = draft ?: return@trySend
            val encoded = try { withContext(Dispatchers.Default) {
                if (decoded.editing == DocumentBody.Editing.ProseMirror) {
                    val state = JsonValue.parseOrNull(content.toByteArray()) ?: error("Invalid editor state")
                    DocumentBody.checkEdited(state, decoded.editorState)
                    decoded.encoded(state)
                } else decoded.encoded(content)
            } } catch (e: Exception) { encodingFailed = true; throw e }
            encodingFailed = false
            val next = DocDraft(c.id, w.id, docId, title.ifBlank { old.title }, encoded.bytes, encoded.contentType, old.baseRowVersion, old.baseSha256, old.baseStorageObjectId, DocDraft.State.EDITING)
            model.store.writer.saveDocDraft(next); draft = next
        }
    }
    fun snapshotThen(action: suspend () -> Unit) {
        val page = host ?: run { commands.trySend(action); return }
        page.evaluate("window.ambulandoDocument.snapshot()") { result ->
            if (result != null && result != "null") {
                val content = org.json.JSONTokener(result).nextValue() as? String
                content?.let(::changed)
            }
            commands.trySend { if (!encodingFailed) action() else saving = false }
        }
    }
    val uploadHost = remember(docId) { object : UploadTokenHost {
        override val uploadTarget = uploadTarget
        override val uploadText get() = (draft?.body?.toString(Charsets.UTF_8) ?: "") + inserted.joinToString("\n")
        override fun replaceUploadToken(token: String, replacement: String): Boolean {
            if (!uploadText.contains(token)) return false
            host?.call("window.ambulandoDocument.replaceUpload", token, editorUploadNode(replacement)) ?: return false
            inserted.remove(token)
            snapshotThen { }
            return true
        }
    } }
    LaunchedEffect(ready, payload) { if (ready && payload != null) model.uploads.attach(uploadHost) }
    DisposableEffect(uploadHost) { onDispose { model.uploads.detach(uploadHost) } }
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
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) snapshotThen { } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun finish() { snapshotThen { try { runner.release(c, w, docId) } finally { close() } } }
    BackHandler { finish() }
    LaunchedEffect(c.id, w.id, docId) {
        try {
            val doc = model.store.reader.doc(w.id, docId) ?: error("Document not cached")
            check(!doc.accessRemoved && !w.accessRemoved) { "No longer available to you" }
            val stored = model.store.reader.docBody(w.id, docId) ?: error("Download the document before editing")
            val previous = model.store.reader.docDraft(DocDraft.key(c.id, w.id, docId))
            val initial = previous ?: DocDraft(c.id, w.id, docId, doc.title, stored.bytes, stored.contentType, doc.rowVersion.toInt(), stored.sha256, stored.storageObjectId, DocDraft.State.EDITING)
            val decoded = withContext(Dispatchers.Default) { DocumentBody(initial.body, initial.contentType) }
            check(decoded.isEditable) { "This document format is read-only" }
            base = decoded; draft = initial; title = initial.title
            model.store.writer.saveDocDraft(initial)
            runner.acquire(c, w, docId)
            payload = if (decoded.editorState != null) JSONObject().put("mode", "rich").put("state", JSONObject(decoded.editorState!!.serializedString())).toString()
                else JSONObject().put("mode", "markdown").put("markdown", decoded.markdown).toString()
            while (isActive) { delay(30_000); runner.renew(c, w, docId) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = e.message }
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
                    "ready" -> ready = true
                    "change" -> changed(event.getString("content"))
                    "need-image" -> scope.launch {
                        val id = event.optString("objectId")
                        if (studio.otherstuff.ambulando.core.transport.isUUID(id)) {
                            val cache = studio.otherstuff.ambulando.core.sync.FileCache(java.io.File(context.cacheDir, "AmbulandoFiles"))
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
                    "error" -> notice = event.optString("message")
                    "loaded" -> if (!event.optBoolean("ok")) notice = event.optString("message")
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "Editor message could not be read; the previous draft is preserved." } }
        }
        made.onSuccess { host = it; it.load() }.onFailure { notice = it.message }
        onDispose { made.getOrNull()?.destroy(); commands.close() }
    }
    LaunchedEffect(ready, payload) { if (ready) payload?.let { host?.call("window.ambulandoDocument.load", it) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (model.environment.isSample) Text("Sample data", style = MaterialTheme.typography.labelSmall)
        Row {
            TextButton(onClick = ::finish, enabled = !saving) { Text("Back") }
            TextButton(enabled = !saving && payload != null && ready && host?.failure == null && inserted.isEmpty(), onClick = {
                saving = true
                snapshotThen {
                    try {
                        val current = draft ?: error("No draft")
                        val savingDraft = DocDraft(c.id, w.id, docId, title.ifBlank { current.title }, current.body, current.contentType, current.baseRowVersion, current.baseSha256, current.baseStorageObjectId, current.state)
                        val result = runner.save(savingDraft, c, w)
                        when (result) {
                            is DocSaveResult.Saved -> { try { runner.release(c, w, docId) } finally { close() } }
                            is DocSaveResult.NotSaved -> notice = result.message
                            is DocSaveResult.Recovery -> notice = result.message
                            is DocSaveResult.LeaseHeld -> notice = result.message
                        }
                    } finally { saving = false }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        }
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("bold" to "Bold", "italic" to "Italic", "heading" to "Heading", "bulletList" to "Bullets", "orderedList" to "Numbered", "taskList" to "Checklist", "blockquote" to "Quote", "codeBlock" to "Code", "undo" to "Undo", "redo" to "Redo").forEach { (command, label) ->
                TextButton(enabled = ready && !saving, onClick = { host?.call("window.ambulandoDocument.command", command, "{}") }) { Text(label) }
            }
            TextButton(enabled = ready && !saving, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Attach") }
        }
        uploadRows.filter { it.target == uploadTarget && it.markdown == null }.forEach { upload -> Row { Text(upload.filename); if (upload.retryable) TextButton(onClick = { model.uploads.retry(upload.id) }) { Text("Retry") }; TextButton(onClick = { model.uploads.remove(upload.id); inserted.remove(upload.token) }) { Text("Remove") } } }
        notice?.let { Text(it, Modifier.padding(12.dp)) }
        if (payload == null) Text("Opening editor; your draft stays on this device.", Modifier.padding(12.dp))
        host?.failure?.let { Text(it) }
        host?.let { page -> AndroidView({ page.webView }, Modifier.weight(1f).fillMaxWidth()) }
    }
}
