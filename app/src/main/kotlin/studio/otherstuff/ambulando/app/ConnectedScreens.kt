@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.focusRequester
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
    val scopedTextAttachments = remember(c.id, c.origin, c.signerNpub, w.id) { model.textAttachments(c, w.id) }
    // One Messages list (no separate Threads view): channel posts carry their thread's reply count and open the thread.
    val feed by remember(target) { model.store.liveQuery({ it.workspaceId == w.id }) {
        if (threadId == null) model.store.reader.channelFeed(w.id, channel, 300) else model.store.reader.threadMessages(w.id, threadId, 300).map { FeedPost(it, 0, null) }
    } }.collectAsState(null)
    val posts = feed ?: emptyList()
    val messages = posts.map { it.message }
    val pending by remember(target) { model.store.liveQuery({ it.table == StoreTable.OUTBOX }) { model.store.reader.outbox(target) } }.collectAsState(emptyList())
    val field = remember(target) { ComposerField() }
    var text by field::text
    val draftAttachments by remember(target) { model.store.liveQuery({ it.table == StoreTable.DRAFTS }) { model.store.reader.draft(target)?.attachments ?: emptyList() } }.collectAsState(emptyList())
    var openDraft by remember(target) { mutableStateOf<PendingAttachment?>(null) }
    var justAdded by remember(target) { mutableStateOf<PendingAttachment?>(null) }
    var openSent by remember(target) { mutableStateOf<Pair<String, MessageAttachment>?>(null) }
    LaunchedEffect(justAdded) { if (justAdded != null) { kotlinx.coroutines.delay(8000); justAdded = null } }
    var ready by remember(target) { mutableStateOf(false) }
    var sent by remember(target) { mutableLongStateOf(0L) }
    val draftScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var mentions by remember(target) { mutableStateOf(emptyList<Mention>()) }
    // The `@` lookahead: the query at the caret, answered from the local store as it is typed.
    // A query that is just a chosen mention's own text (right after picking it) is not a new search.
    val activeMention = field.value.selection.takeIf { it.collapsed }?.let { MentionCodec.activeQuery(field.value.text, it.end) }
        ?.takeIf { active -> mentions.none { field.value.text.startsWith(it.visible, active.start) } }
    val mentionResults by produceState<List<studio.otherstuff.ambulando.core.store.MentionCandidate>?>(null, w.id, activeMention?.query) {
        value = activeMention?.let { model.store.reader.mentionCandidates(w.id, it.query, viewerNpub = c.signerNpub) }
    }
    val uploadRows by model.uploads.items.collectAsState()
    val uploadHost = remember(target) { object : studio.otherstuff.ambulando.core.chat.UploadTokenHost {
        override val uploadTarget = target
        override val uploadText get() = text
        override fun replaceUploadToken(token: String, replacement: String): Boolean {
            // The finished upload's Markdown takes the placeholder's place; the caret stays where the person is typing.
            val current = field.value
            val selection = studio.otherstuff.ambulando.core.docs.TextRange(current.selection.min, current.selection.length)
            val edit = ComposerText.replacing(token, replacement, current.text, selection) ?: return false
            field.value = current.copy(text = edit.text, selection = edit.selection?.let { androidx.compose.ui.text.TextRange(it.location, it.end) } ?: current.selection)
            model.saveDraft(target, text, mentions); return true
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
            // Placeholders go where the caret is, so the image is positioned in the message and its Markdown stays editable.
            field.value = studio.otherstuff.ambulando.app.ui.chat.ComposerInsert.placeholders(field.value, tokens); model.saveDraft(target, text, mentions)
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
        if (threadId != null) ThreadHeader(model, threadId, !removed)
        val listState = remember(target) { androidx.compose.foundation.lazy.LazyListState() }
        ChatFollowsNewest(listState, target, feed != null, posts.map { it.id } + pending.map { it.id }, model.chatPositions[target.draftKey], sent) {
            model.chatPositions[target.draftKey] = it
        }
        if (feed == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Loading conversation…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chat.loading"))
        } else if (posts.isEmpty() && pending.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(if (threadId == null) "No messages yet" else "No replies yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chat.empty"))
        } else LazyColumn(Modifier.weight(1f).testTag("chat.messages"), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(posts, key = { it.id }) { post ->
                val message = post.message
                Column(Modifier.fillMaxWidth().testTag("chat.message.${message.id}").clickable(enabled = threadId == null && message.threadId != null) {
                    message.threadId?.let { services.open(WorkRoute.Thread(w.id, channel, it)); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
                }) {
                    Text(message.createdAt.take(10), style = MaterialTheme.typography.labelSmall)
                    Text(message.senderLabel ?: message.senderNpub?.take(18) ?: "Member", style = MaterialTheme.typography.labelSmall)
                    if (message.deleted) Text("Message deleted") else {
                        if (message.body.isNotBlank()) RichBlocks(DocumentRender.markdownBlocks(message.body), actions = richActions)
                        MessageAttachment.from(message.raw).filter { it.isText }.forEach { attachment ->
                            val preview by produceState<String?>(null, c.id, c.origin, w.id, attachment.storageObjectId) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    scopedTextAttachments.cache.peek(attachment.storageObjectId, 8192)?.let { TextAttachments.firstLinePreview(String(it, Charsets.UTF_8)) }
                                }
                            }
                            TextAttachmentCard(
                                attachment.filename, attachment.sizeBytes, attachment.textAttachment?.lines,
                                preview, null, "chat.attachment.${attachment.storageObjectId}",
                                onOpen = { openSent = message.id to attachment },
                            )
                        }
                        // Attachments not already placed in the body (as on the web, an inline image is never shown twice).
                        StorageMarkdown.standalone(MessageAttachment.from(message.raw), message.body).filter { !it.isText }.forEach { attachment ->
                            if (attachment.kind == "image" || attachment.mimeType?.startsWith("image/") == true) {
                                StoredImage(model, attachment.storageObjectId, attachment.filename, !removed)
                            } else TextButton(onClick = { services.openUrl("storage://${attachment.storageObjectId}") }, enabled = !removed,
                                modifier = Modifier.testTag("chat.file.${attachment.storageObjectId}")) { Text(attachment.filename) }
                        }
                    }
                    if (threadId == null && message.threadId != null) ThreadSummaryRow(post) {
                        services.open(WorkRoute.Thread(w.id, channel, message.threadId!!)); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT
                    }
                    ChatMessageActions(model, message, !removed)
                    message.threadId?.let { RecordPin(model, studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.THREAD, it, !removed) }
                }
            }
            items(pending, key = { it.id }) { item ->
                if (item.body.isNotBlank()) Text(item.body)
                item.attachments.filter { it.isText }.forEach { a ->
                    TextAttachmentCard(a.filename, a.sizeBytes, a.text?.lines, null, if (a.complete) "Uploaded" else "Waiting to upload", "chat.pending.attachment.${a.id}", onOpen = null)
                }
                Text(item.lastError ?: item.state.wire, style = MaterialTheme.typography.labelSmall)
                if (item.state == OutboxState.FAILED || item.state == OutboxState.RETRYING) {
                    TextButton(onClick = { model.act { model.outbox.retry(item.id, c) } }) { Text("Retry") }
                    TextButton(onClick = { model.act { model.outbox.discard(item.id, true); text = model.store.reader.draft(target)?.text ?: text } }) { Text("Edit draft") }
                    TextButton(onClick = { model.act { model.outbox.discard(item.id, false) } }) { Text("Discard") }
                }
            }
        }
        if (removed) Text("No longer available to you", Modifier.padding(12.dp))
        uploadRows.filter { it.target == target && it.markdown == null }.forEach { upload ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(upload.filename + " · " + uploadPhase(upload.phase), style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f).testTag("chat.upload.${upload.id}"))
                if (upload.retryable) TextButton(onClick = { model.uploads.retry(upload.id) }) { Text("Retry") }
                TextButton(onClick = { model.uploads.remove(upload.id) }) { Text("Remove") }
            }
        }
        draftAttachments.filter { it.isText }.forEach { a ->
            val preview = rememberStagedPreview(model, a)
            TextAttachmentCard(
                a.filename, a.sizeBytes, a.text?.lines, preview, if (a.complete) "Uploaded" else "Saved on this device", "chat.draft.attachment.${a.id}",
                onOpen = { openDraft = a }, onRemove = { model.removeDraftAttachment(target, a.id); if (justAdded?.id == a.id) justAdded = null },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        justAdded?.let { added -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${added.filename} added from your paste", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).testTag("chat.paste.notice"))
            TextButton(onClick = { model.removeDraftAttachment(target, added.id); justAdded = null }, modifier = Modifier.testTag("chat.paste.undo")) { Text("Undo") }
        } }
        val composing = ready && !removed
        if (composing && activeMention != null) mentionResults?.let { results ->
            studio.otherstuff.ambulando.app.ui.chat.MentionLookahead(results, onPick = { mention ->
                field.value = studio.otherstuff.ambulando.app.ui.chat.ComposerInsert.mention(field.value, mention)
                mentions = (mentions.filter { it.npub != mention.npub || it.type != mention.type } + mention)
                model.saveDraft(target, text, mentions)
            })
        }
        val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
        val composerFocus = remember(target) { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val pills = remember(mentions, amb) {
            studio.otherstuff.ambulando.app.ui.chat.MentionPills(mentions, androidx.compose.ui.text.SpanStyle(color = amb.primary, background = amb.muted, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        }
        Row(Modifier.fillMaxWidth().imePadding().clickable(enabled = composing) { composerFocus.requestFocus(); keyboard?.show() }.testTag("chat.composer.area").padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            studio.otherstuff.ambulando.app.ui.chat.ComposerControls(composing, onPick = { picker.launch(arrayOf("image/*", "*/*")) }, onPaste = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.primaryClip?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::pasteUri) }
            }, onMention = { field.value = studio.otherstuff.ambulando.app.ui.chat.ComposerInsert.at(field.value); model.saveDraft(target, text, mentions) })
            OutlinedTextField(field.value, { value ->
                if (value.text == field.value.text) { field.value = value; return@OutlinedTextField }
                var decision = ComposerPaste.decide(field.value.text, value.text, selectionStart = field.value.selection.start, selectionEnd = field.value.selection.end)
                // A short paste that would pass the cap looks like typing; the clipboard tells them apart (read only then).
                if (decision == ComposerPaste.Decision.Reject) {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    decision = ComposerPaste.decide(field.value.text, value.text, clip, field.value.selection.start, field.value.selection.end)
                }
                when (decision) {
                    ComposerPaste.Decision.Accept -> { field.value = value; if (ready) model.saveDraft(target, value.text, mentions) }
                    // Typing at the cap: the field keeps what it had (the counter shows why).
                    ComposerPaste.Decision.Reject -> Unit
                    // A big paste: the field keeps its text and selection; the pasted text becomes a staged file.
                    is ComposerPaste.Decision.Attach -> if (ready) model.addPastedText(target, decision.text) { justAdded = it }
                }
            }, enabled = composing, maxLines = 8, placeholder = { Text(if (threadId == null) "Message" else "Reply") },
                visualTransformation = pills, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                supportingText = if (PastedTextPolicy.showsCounter(text)) ({ Text("${PastedTextPolicy.remaining(text)} characters left", modifier = Modifier.testTag("chat.counter")) }) else null,
                modifier = Modifier.weight(1f).then(Modifier.focusRequester(composerFocus)).testTag("chat.composer").contentReceiver(receiver))
            studio.otherstuff.ambulando.app.ui.chat.SendButton(composing && (text.isNotBlank() || draftAttachments.any { it.isText }) && !UploadToken.containsAny(text)) {
                val body = text
                model.send(target, body, c, w, mentions) { if (text == body) text = ""; sent++ }
            }
        }
        openDraft?.let { a -> draftAttachments.firstOrNull { it.id == a.id }?.let { current -> DraftTextDialog(model, target, current) { openDraft = null } } ?: run { openDraft = null } }
        openSent?.let { (messageId, attachment) -> SentTextDialog(model, messageId, channel, attachment, editable = !removed) { openSent = null } }
    }
}

@Composable internal fun ConnectedDocuments(model: AppModel, docId: String?) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    if (docId != null) {
        val record by remember(w.id, docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.doc(w.id, docId) } }.collectAsState(null)
        val body by remember(w.id, docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.docBody(w.id, docId) } }.collectAsState(null)
        LaunchedEffect(w.id, docId) { model.sync.refreshDoc(model.connection!!, w, docId); model.markViewed(ViewStateRecord.ResourceType.DOCUMENT, docId) }
        // Edit as the default opening mode: start editing once per visit when the document can be edited.
        val editable = body != null && record?.accessRemoved == false && !w.accessRemoved
        LaunchedEffect(docId, editable, model.documentOpensInEdit) {
            if (model.documentOpensInEdit && editable && model.autoEditedDoc != docId) { model.autoEditedDoc = docId; model.editorDoc = docId }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp)) { item {
            Row {
                TextButton(onClick = { model.editorDoc = docId }, enabled = body != null && record?.accessRemoved == false && !w.accessRemoved) { Text("Edit") }
                record?.let { MoveDocument(model, it, !it.accessRemoved && !w.accessRemoved) }
            }
            body?.let { RichDocument(model, it, record?.accessRemoved == false) } ?: Text("Document body not cached yet")
            LinkedFrom(model, docId)
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

/** The composer's text value, kept whole (text, selection and IME composition) so a keyboard is never disturbed. */
@Stable internal class ComposerField {
    var value by mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    /** Replaces the text from the app (a draft restore, a mention); the caret goes to the end. */
    var text: String
        get() = value.text
        set(next) { value = androidx.compose.ui.text.input.TextFieldValue(next, androidx.compose.ui.text.TextRange(next.length)) }
}

/** Where a conversation was left: the first visible row, its offset, and whether it was following the newest. */
data class ChatPosition(val key: Any?, val offset: Int, val follow: Boolean)

/**
 * Keeps a conversation readable from its first layout: it opens on its newest message (never an empty or stale scroll
 * position), or where the reader left it when they had scrolled back. While the reader is at the bottom it follows new
 * messages and rows that grow after they are drawn (images, previews); scrolling up stops following until they return to
 * the bottom. [keys] are the list's item keys in order.
 */
@Composable internal fun ChatFollowsNewest(
    state: androidx.compose.foundation.lazy.LazyListState, target: ChatTarget, loaded: Boolean, keys: List<Any>,
    saved: ChatPosition?, sent: Long = 0L, onSave: (ChatPosition) -> Unit,
) {
    var follow by remember(target) { mutableStateOf(saved?.follow ?: true) }
    var placed by remember(target) { mutableStateOf(false) }
    val count = keys.size
    LaunchedEffect(sent) { if (sent > 0) { follow = true; if (count > 0) state.requestScrollToItem(count - 1, FOLLOW_OFFSET) } }
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) wasScrolling = true
            else if (wasScrolling && placed) { follow = !state.canScrollForward; wasScrolling = false }
        }
    }
    LaunchedEffect(state, loaded, count) {
        if (!loaded || count == 0) return@LaunchedEffect
        if (!placed) {
            placed = true
            val index = saved?.takeIf { !it.follow }?.let { keys.indexOf(it.key) }?.takeIf { it >= 0 }
            if (index != null) { state.requestScrollToItem(index, saved.offset); return@LaunchedEffect }
            follow = true
        }
        if (follow) state.requestScrollToItem(count - 1, FOLLOW_OFFSET)
    }
    LaunchedEffect(state, loaded) {
        if (loaded) snapshotFlow { state.canScrollForward }.collect { more ->
            val total = state.layoutInfo.totalItemsCount
            // A layout snapshot may arrive during measurement. Request the next layout instead of re-entering it.
            if (more && placed && follow && !state.isScrollInProgress && total > 0) state.requestScrollToItem(total - 1, FOLLOW_OFFSET)
        }
    }
    DisposableEffect(state) {
        onDispose { if (placed) onSave(ChatPosition(state.layoutInfo.visibleItemsInfo.firstOrNull()?.key, state.firstVisibleItemScrollOffset, follow)) }
    }
}

/** Scrolling to the last row with a large offset lands on the end of the list (the offset is clamped). */
private const val FOLLOW_OFFSET = 1_000_000

/** A channel post's thread: reply count and latest activity; opens the conversation. */
@Composable internal fun ThreadSummaryRow(post: FeedPost, onOpen: () -> Unit) {
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clickable(onClickLabel = "Open conversation", onClick = onOpen)
            .background(amb.muted, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("chat.thread.${post.message.threadId}"),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        val replies = if (post.replyCount == 1) "1 reply" else "${post.replyCount} replies"
        val last = post.lastReplyAt?.let { " · last ${studio.otherstuff.ambulando.app.ui.chat.ChatTime.label(it)}" }.orEmpty()
        Text(replies + last, style = MaterialTheme.typography.labelMedium, color = amb.primary, modifier = Modifier.weight(1f))
        Text("Open", style = MaterialTheme.typography.labelMedium, color = amb.primary)
    }
}

/** An upload's state in words. */
internal fun uploadPhase(phase: ComposerUpload.Phase): String = when (phase) {
    ComposerUpload.Phase.Preparing -> "Preparing"
    ComposerUpload.Phase.Waiting -> "Waiting to upload"
    ComposerUpload.Phase.Uploading -> "Uploading"
    is ComposerUpload.Phase.Failed -> "Failed: ${phase.message}"
    is ComposerUpload.Phase.Done -> "Uploaded"
}

/** "Linked from": saved pages that link to this document, with how many saved pages could be checked on this device. */
@Composable internal fun LinkedFrom(model: AppModel, docId: String) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    val links by remember(w.id, docId) { model.store.liveQuery({ it.workspaceId == w.id && (it.table == StoreTable.DOCS || it.table == StoreTable.DOC_BODIES) }) { model.store.reader.docBacklinks(w.id, docId) } }.collectAsState(null)
    val found = links ?: return
    Column(Modifier.padding(vertical = 8.dp).testTag("doc.linkedfrom")) {
        Text("Linked from " + found.docs.size, style = MaterialTheme.typography.titleMedium)
        found.docs.forEach { page ->
            val place = model.channels.firstOrNull { it.id == page.channelId }?.let { channel ->
                listOfNotNull(model.scopes.firstOrNull { it.id == channel.scopeId }?.name, "#" + channel.name).joinToString(" · ")
            }
            TextButton(onClick = { services.open(WorkRoute.Doc(w.id, page.id)) }, modifier = Modifier.testTag("doc.linkedfrom.${page.id}")) {
                Column { Text(page.title); place?.let { Text(it, style = MaterialTheme.typography.labelSmall) } }
            }
        }
        Text("Checked ${found.checked} saved page" + (if (found.checked == 1) "" else "s") + " on this device.", style = MaterialTheme.typography.labelSmall)
    }
}
