@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package studio.otherstuff.ambulando.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import studio.otherstuff.ambulando.app.media.readBounded
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.rich.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.surfaces.ensureThread
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.docs.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.deck.DeckActivity

/** Only local database reads occur in these native surfaces. */
@Composable internal fun ConnectedChat(model: AppModel, threadId: String?) {
    val w = model.workspace ?: return
    val c = model.connection ?: return
    val threadChannel by remember(w.id, threadId) { model.store.liveQuery({ it.workspaceId == w.id }) { threadId?.let { model.store.reader.thread(w.id, it)?.channelId } } }.collectAsState(null)
    LaunchedEffect(w.id, threadId) { if (threadId != null) model.sync.ensureThread(c, w, threadId) }
    val channel = if (threadId != null) threadChannel else model.context.channelId
    if (channel == null) {
        if (threadId != null) { Text("Opening conversation…"); return }
        LazyColumn { items(model.channels.filter { model.context.scopeId == null || it.scopeId == model.context.scopeId }, key = { it.id }) { row ->
            TextButton(onClick = { model.selectContext(row.scopeId, row.id) }) { Text("#${row.name}") }
        } }; return
    }
    val target = remember(c.id, w.id, channel, threadId) { ChatTarget(c.id, w.id, channel, threadId) }
    val services = LocalAppServices.current
    var threads by remember(target) { mutableStateOf(false) }
    val conversations by remember(target) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.channelThreads(w.id, channel) } }.collectAsState(emptyList())
    val messages by remember(target) { model.store.liveQuery({ it.workspaceId == w.id }) {
        if (threadId == null) model.store.reader.channelFeed(w.id, channel, 300).map { it.message } else model.store.reader.threadMessages(w.id, threadId, 300)
    } }.collectAsState(emptyList())
    val pending by remember(target) { model.store.liveQuery({ it.table == StoreTable.OUTBOX }) { model.store.reader.outbox(target) } }.collectAsState(emptyList())
    var text by remember(target) { mutableStateOf("") }
    var ready by remember(target) { mutableStateOf(false) }
    val draftScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var mentions by remember(target) { mutableStateOf(emptyList<Mention>()) }
    var choosingMention by remember(target) { mutableStateOf(false) }
    val members by remember(w.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.members(w.id) } }.collectAsState(emptyList())
    val uploadRows by model.uploads.items.collectAsState()
    val uploadHost = remember(target) { object : studio.otherstuff.ambulando.core.chat.UploadTokenHost {
        override val uploadTarget = target
        override val uploadText get() = text
        override fun replaceUploadToken(token: String, replacement: String): Boolean {
            if (!text.contains(token)) return false
            text = text.replaceFirst(token, replacement); model.saveDraft(target, text, mentions); return true
        }
    } }
    LaunchedEffect(ready, uploadHost) { if (ready) model.uploads.attach(uploadHost) }
    DisposableEffect(uploadHost) { onDispose { model.uploads.detach(uploadHost) } }
    fun pasteUri(uri: android.net.Uri) {
        if (!ready || w.accessRemoved || model.channels.firstOrNull { it.id == channel }?.accessRemoved == true) return
        if (uri != null) {
            val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val image = type.startsWith("image/")
            val item = PastedItem(if (image) UploadToken.Kind.IMAGE else UploadToken.Kind.FILE, mimeType = type, read = {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(32 * 1024 * 1024) } ?: error("Cannot read attachment")
                require(bytes.size <= 32 * 1024 * 1024) { "Attachment too large" }
                if (image) studio.otherstuff.ambulando.app.media.ImagePrivacy.strippedPng(bytes) else bytes
            })
            val tokens = model.uploads.paste(listOf(item), target, w.appNpub, "message")
            text += "\n" + tokens.joinToString("\n"); model.saveDraft(target, text, mentions)
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::pasteUri) }
    val receiver = remember(target) { ReceiveContentListener { content -> content.consume { item ->
        val uri = item.uri
        if (uri != null && ready) { pasteUri(uri); true } else false
    } } }
    LaunchedEffect(target) { val saved = model.store.reader.draft(target); text = saved?.text ?: ""; mentions = saved?.mentions ?: emptyList(); ready = true
        if (threadId != null) model.sync.refreshThread(c, w, channel, threadId) else model.sync.catchUpChannel(c, w, channel)
    }
    val ids = messages.map { it.id }
    LaunchedEffect(target, ids) { if (threadId != null) model.markViewed(ViewStateRecord.ResourceType.THREAD, threadId); if (!w.accessRemoved && model.channels.firstOrNull { it.id == channel }?.accessRemoved != true) model.sync.refreshReactions(c, w, threadId ?: channel, ids) }
    val threadRemoved by remember(w.id, threadId) { model.store.liveQuery({ it.workspaceId == w.id }) { threadId?.let { model.store.reader.isAccessRemoved(w.id, FlaggedTable.THREADS, it) } ?: false } }.collectAsState(false)
    val removed = threadRemoved || w.accessRemoved || model.channels.firstOrNull { it.id == channel }?.accessRemoved == true
    val richActions = storedRichActions(model, !removed)
    Column(Modifier.fillMaxSize()) {
        if (threadId == null) Row { TextButton(onClick = { threads = false }) { Text("Messages") }; TextButton(onClick = { threads = true }) { Text("Threads") } }
        if (threads && threadId == null) LazyColumn(Modifier.weight(1f)) { items(conversations, key = { it.id }) { conversation ->
            TextButton(onClick = { services.open(WorkRoute.Thread(w.id, channel, conversation.id)) }) { Column { Text(conversation.title ?: conversation.rootBody.take(100)); Text("${conversation.replyCount} replies · ${conversation.latestBody.take(120)}") } }
        } } else LazyColumn(Modifier.weight(1f).testTag("chat.messages"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth().clickable(enabled = threadId == null && message.threadId != null) {
                    message.threadId?.let { services.open(WorkRoute.Thread(w.id, channel, it)); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
                }) {
                    Text(message.createdAt.take(10), style = MaterialTheme.typography.labelSmall)
                    Text(message.senderLabel ?: message.senderNpub?.take(18) ?: "Member", style = MaterialTheme.typography.labelSmall)
                    if (message.deleted) Text("Message deleted") else RichBlocks(DocumentRender.markdownBlocks(message.body), actions = richActions)
                    ChatMessageActions(model, message, !removed)
                    message.threadId?.let { RecordPin(model, studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.THREAD, it, !removed) }
                }
            }
            items(pending, key = { it.id }) { item ->
                Text(item.body); Text(item.lastError ?: item.state.wire, style = MaterialTheme.typography.labelSmall)
                if (item.state == OutboxState.FAILED || item.state == OutboxState.RETRYING) {
                    TextButton(onClick = { model.act { model.outbox.retry(item.id, c) } }) { Text("Retry") }
                    TextButton(onClick = { model.act { model.outbox.discard(item.id, true); text = model.store.reader.draft(target)?.text ?: text } }) { Text("Edit draft") }
                    TextButton(onClick = { model.act { model.outbox.discard(item.id, false) } }) { Text("Discard") }
                }
            }
        }
        if (removed) Text("No longer available to you", Modifier.padding(12.dp))
        uploadRows.filter { it.target == target && it.markdown == null }.forEach { upload -> Row {
            Text(upload.filename + " · " + upload.phase.toString())
            if (upload.retryable) TextButton(onClick = { model.uploads.retry(upload.id) }) { Text("Retry") }
            TextButton(onClick = { model.uploads.remove(upload.id) }) { Text("Remove") }
        } }
        Row {
            TextButton(enabled = ready && !removed, onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.primaryClip?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::pasteUri) }
            }) { Text("Paste attachment") }
            TextButton(enabled = ready && !removed, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Attach") }
            TextButton(enabled = ready && !removed, onClick = { choosingMention = true }) { Text("Mention") }
        }
        if (choosingMention) AlertDialog(onDismissRequest = { choosingMention = false }, title = { Text("Mention a member") }, text = { Column { members.take(20).forEach { member -> TextButton(onClick = {
            val mention = Mention(MentionType.PERSON, member.npub, member.label)
            mentions = mentions + mention; text = MentionCodec.complete(text, mention); model.saveDraft(target, text, mentions); choosingMention = false
        }) { Text(member.label) } } } }, confirmButton = { TextButton(onClick = { choosingMention = false }) { Text("Close") } })
        Row(Modifier.imePadding().padding(8.dp)) {
            OutlinedTextField(text, { value ->
                text = value
                if (ready) model.saveDraft(target, value, mentions)
            }, enabled = ready && !removed, maxLines = 5, label = { Text(if (threadId == null) "New conversation" else "Reply") }, modifier = Modifier.weight(1f).testTag("chat.composer").contentReceiver(receiver))
            TextButton(enabled = ready && text.isNotBlank() && !removed && !UploadToken.containsAny(text), onClick = {
                val body = text
                model.send(target, body, c, w, mentions) { if (text == body) text = "" }
            }) { Text("Send") }
        }
    }
}

@Composable internal fun ConnectedDocuments(model: AppModel, docId: String?) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    if (docId != null) {
        val record by remember(w.id, docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.doc(w.id, docId) } }.collectAsState(null)
        val body by remember(w.id, docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.docBody(w.id, docId) } }.collectAsState(null)
        LaunchedEffect(w.id, docId) { model.sync.refreshDoc(model.connection!!, w, docId); model.markViewed(ViewStateRecord.ResourceType.DOCUMENT, docId) }
        LazyColumn(contentPadding = PaddingValues(16.dp)) { item {
            TextButton(onClick = { model.editorDoc = docId }, enabled = body != null && record?.accessRemoved == false && !w.accessRemoved) { Text("Edit") }
            RecordPin(model, studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.DOCUMENT, docId, record?.accessRemoved == false && !w.accessRemoved)
            body?.let { RichDocument(model, it, record?.accessRemoved == false) } ?: Text("Document body not cached yet")
            DocumentComments(model, docId, record?.accessRemoved == false && !w.accessRemoved)
        } }
    } else {
        val rows by remember(w.id, model.context) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.docs(w.id, model.context) } }.collectAsState(emptyList())
        LazyColumn { item { CreateDocument(model) }; items(rows, key = { it.id }) { doc ->
            TextButton(onClick = { services.open(WorkRoute.Doc(w.id, doc.id)) }) { Text(doc.title) }
            if (doc.accessRemoved) Text("No longer available to you")
        } }
    }
}

@Composable internal fun ConnectedFiles(model: AppModel) {
    val w = model.workspace ?: return
    val rows by remember(w.id, model.context) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.files(w.id, model.context) } }.collectAsState(emptyList())
    LazyColumn(contentPadding = PaddingValues(16.dp)) { items(rows, key = { it.id }) { file ->
        Text(file.displayName); Text(file.contentType ?: "File", style = MaterialTheme.typography.labelSmall)
        if (file.accessRemoved) Text("No longer available to you")
    } }
}

@Composable internal fun ConnectedActivity(model: AppModel) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    val snapshot by remember(w.id, model.context) { model.store.liveQuery({ it.workspaceId == w.id }) {
        studio.otherstuff.ambulando.app.ui.deck.DeckInbox.load(model.store, w.id, model.connection?.signerNpub, model.context,
            model.context.scopeId?.let { model.channels.filter { it.scopeId == model.context.scopeId && (model.context.channelId == null || it.id == model.context.channelId) }.map { it.id } })
    } }.collectAsState(studio.otherstuff.ambulando.app.ui.deck.DeckInboxSnapshot())
    LazyColumn(contentPadding = PaddingValues(12.dp)) { items(snapshot.items, key = { it.id }) { row ->
        TextButton(onClick = {
            when (row.kind) {
                DeckActivity.Kind.TASK -> { model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.TASKS; services.open(WorkRoute.Task(w.id, row.recordId)) }
                DeckActivity.Kind.DOCUMENT -> { model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.DOCS; services.open(WorkRoute.Doc(w.id, row.recordId)) }
                DeckActivity.Kind.FILE -> model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.FILES
                DeckActivity.Kind.CHAT -> { model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT; row.channelId?.let { id -> model.channels.firstOrNull { it.id == id }?.let { model.selectContext(it.scopeId, id) } }; services.open(WorkRoute.Thread(w.id, row.channelId, row.recordId)) }
            }
        }) { Column { Text(row.title); Text(row.subtitle, style = MaterialTheme.typography.bodySmall) } }
    } }
}
