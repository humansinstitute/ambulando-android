package studio.otherstuff.ambulando.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.store.PendingAttachment
import studio.otherstuff.ambulando.core.transport.MessageAttachment

/** Compact card for one text attachment: name, size, lines and the first line of text; Open and (optionally) Remove. */
@Composable internal fun TextAttachmentCard(
    name: String, sizeBytes: Long?, lines: Int?, preview: String?, status: String?, tag: String,
    onOpen: (() -> Unit)?, onRemove: (() -> Unit)? = null, modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("$tag.name"))
                val meta = listOfNotNull(sizeBytes?.let { TextAttachments.formatSize(it) }, lines?.let { if (it == 1) "1 line" else "$it lines" }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall)
            }
            if (!preview.isNullOrEmpty()) Text(preview, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("$tag.preview"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.orEmpty(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                if (onOpen != null) TextButton(onClick = onOpen, modifier = Modifier.testTag("$tag.open")) { Text("Open") }
                if (onRemove != null) TextButton(onClick = onRemove, modifier = Modifier.testTag("$tag.remove")) { Text("Remove") }
            }
        }
    }
}

/** First-line preview of a staged attachment, read off the main thread from at most the first 8 KB. */
@Composable internal fun rememberStagedPreview(model: AppModel, attachment: PendingAttachment): String? {
    val preview by produceState<String?>(null, attachment.id, attachment.sha256) {
        value = withContext(Dispatchers.IO) {
            val dir = model.outbox.staging ?: return@withContext null
            runCatching {
                val file = java.io.File(dir, attachment.staged)
                val buffer = ByteArray(minOf(8192L, file.length()).toInt())
                file.inputStream().use { it.read(buffer) }
                TextAttachments.firstLinePreview(String(buffer, Charsets.UTF_8))
            }.getOrNull()
        }
    }
    return preview
}

/**
 * Large text held as bounded chunks (see [TextChunks]); the viewer and editor only ever show one chunk-sized piece of
 * text at a time, so a multi-megabyte log or a single huge line never reaches one text widget.
 */
@Stable internal class ChunkedText(initial: List<String>) {
    val chunks = mutableStateListOf<String>().also { it.addAll(initial) }
    var page by mutableIntStateOf(0)
    var dirty by mutableStateOf(false); private set
    var bytes by mutableLongStateOf(initial.sumOf { PastedTextPolicy.utf8Bytes(it).toLong() }); private set
    private var breaks = initial.sumOf { breaksIn(it) }
    private var joinedCrLf = initial.zipWithNext().count { (a, b) -> a.endsWith("\r") && b.startsWith("\n") }
    val lines: Int get() = if (bytes == 0L) 0 else breaks - joinedCrLf + 1
    val tooLarge: Boolean get() = bytes > TextAttachments.MAX_BYTES

    fun text(): String = TextChunks.join(chunks.toList())
    fun markDirty() { dirty = true }
    fun markSaved() { dirty = false }

    /** Bumped whenever an edit had to be re-split under the field, so the field starts again from the text the page now holds. */
    var rewrites by mutableIntStateOf(0); private set
    /** Where the caret goes on the page the editor moves to after a re-split (a local offset), read once by that page. */
    var caretHint: Int? = null

    /**
     * Applies an edit of one chunk. The page being edited may grow without being split, so the text field's own text is never
     * rewritten under the keyboard while a person types. Only a paste past [EDIT_CAP] or growth past [HARD_CAP] is split, here;
     * [normalize] restores bounded pages when the editor is left.
     */
    fun update(index: Int, value: String) {
        val old = chunks[index]
        fun boundary(left: String?, right: String?) = if (left?.endsWith("\r") == true && right?.startsWith("\n") == true) 1 else 0
        joinedCrLf -= boundary(chunks.getOrNull(index - 1), old) + boundary(old, chunks.getOrNull(index + 1))
        bytes += PastedTextPolicy.utf8Bytes(value) - PastedTextPolicy.utf8Bytes(old)
        breaks += breaksIn(value) - breaksIn(old)
        dirty = true
        // Typing and composing add a few characters at a time and are never split under the keyboard; a paste-sized edit that takes
        // the page past the cap is, and so is a page that has grown past the hard limit.
        val splitNow = value.length > HARD_CAP || (value.length > EDIT_CAP && value.length - old.length > PASTE_UNITS)
        val parts = if (splitNow) TextChunks.split(value) else listOf(value)
        if (parts.size > 1) {
            chunks[index] = parts[0]
            chunks.addAll(index + 1, parts.drop(1))
            rewrites++
        } else chunks[index] = value
        val inserted = parts.size
        joinedCrLf += parts.zipWithNext().sumOf { (l, r) -> boundary(l, r) } +
            boundary(chunks.getOrNull(index - 1), chunks[index]) +
            boundary(chunks[index + inserted - 1], chunks.getOrNull(index + inserted))
    }

    /** Splits every page that grew past the page size, so the reader and the next edit see bounded pages. Text is unchanged. */
    fun normalize() {
        var i = chunks.size - 1
        while (i >= 0) {
            if (chunks[i].length > TextChunks.DEFAULT_UNITS) {
                val parts = TextChunks.split(chunks[i])
                chunks[i] = parts[0]
                chunks.addAll(i + 1, parts.drop(1))
            }
            i--
        }
        joinedCrLf = chunks.zipWithNext().count { (a, b) -> a.endsWith("\r") && b.startsWith("\n") }
    }

    companion object {
        /** The most a page may hold while it is being edited: three pages' worth. */
        const val EDIT_CAP = 3 * TextChunks.DEFAULT_UNITS
        /** An edit that adds more than this at once is a paste, not typing. */
        const val PASTE_UNITS = 4_000
        /** A page being edited is split regardless of how it grew once it passes this. */
        const val HARD_CAP = 10 * TextChunks.DEFAULT_UNITS
        private fun breaksIn(text: String): Int {
            var n = 0; var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n') n++ else if (c == '\r') { if (i + 1 < text.length && text[i + 1] == '\n') i++; n++ }
                i++
            }
            return n
        }
        /** Splits off the main thread; callers wrap this in `withContext(Dispatchers.Default)`. */
        fun of(text: String): ChunkedText = ChunkedText(TextChunks.split(text))
    }
}

/** Read-only view: lazily composed monospace chunks. */
@Composable private fun ChunkViewer(doc: ChunkedText, modifier: Modifier) {
    LazyColumn(modifier.testTag("text.viewer")) {
        itemsIndexed(doc.chunks, key = { index, _ -> index }) { _, chunk ->
            Text(chunk, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * What one page of the editor does with a value the text field reports. A page may grow while it is edited, so
 * ordinary typing, composing and undo never see the page's text rewritten under the keyboard. Only a large paste that takes the
 * page past [ChunkedText.EDIT_CAP] (or a page past [ChunkedText.HARD_CAP]) is split, so the field is then given back a shorter value than the one it reported. Until the field has been
 * composed with that value its input buffer still holds the longer text, and an input-method event in that gap is made against the
 * stale buffer: a plain echo reports it unchanged, and a real keystroke reports it plus the keystroke.
 *
 * The gap is closed by an event, never by comparing text or waiting: [fieldShows] is called once the field has been composed with
 * the value this returned. While it is open an unchanged stale buffer is dropped as the echo, and any other value is read as an
 * edit of the stale buffer and re-applied to the page as it is now. Once it is closed every value is a person's edit, so typing
 * exactly the characters that were cut off, or the same insert twice, is applied.
 */
internal class ChunkPageEdit(private val doc: ChunkedText, private val page: Int) {
    private var stale: String? = null

    /** Applies [value] to the page and returns what the field must show next; [shown] is what it shows now. */
    fun apply(value: TextFieldValue, shown: TextFieldValue): TextFieldValue {
        val base = stale
        val kept = doc.chunks[page]
        if (base != null) {
            if (value.text == base) return TextFieldValue(kept, TextRange(clamp(kept, value.selection.end)))
            return commit(rebase(base, value, kept), value)
        }
        if (value.text == shown.text) return value
        return commit(value.text, value)
    }

    /** The field has been composed with what [apply] returned, so its input buffer holds that text and no echo can follow. */
    fun fieldShows(shown: TextFieldValue) {
        if (stale != null && shown.text == doc.chunks[page]) stale = null
    }

    private fun commit(text: String, value: TextFieldValue): TextFieldValue {
        val before = doc.chunks.size
        doc.update(page, text)
        val now = doc.chunks[page]
        stale = if (now == text && text == value.text) null else value.text
        if (now == value.text) return value
        // The edit was split in place. If the caret now lies beyond this page, the person is typing on a later one: go there, so
        // what they type next lands after the text they just put in rather than before it.
        var local = value.selection.end
        var target = page
        val last = page + doc.chunks.size - before
        while (target < last && local > doc.chunks[target].length) { local -= doc.chunks[target].length; target++ }
        if (target != page) { doc.caretHint = clamp(doc.chunks[target], local); doc.page = target }
        return TextFieldValue(now, TextRange(clamp(now, value.selection.end)))
    }

    /** [value] is an edit of the stale buffer [base]; the same edit made to [kept], which is what the page holds now. */
    private fun rebase(base: String, value: TextFieldValue, kept: String): String {
        val t = value.text
        var start = 0
        val limit = minOf(base.length, t.length)
        while (start < limit && base[start] == t[start]) start++
        var tail = 0
        while (tail < limit - start && base[base.length - 1 - tail] == t[t.length - 1 - tail]) tail++
        // Never cut a surrogate pair: move the edges out to whole characters.
        if (start > 0 && start < base.length && Character.isLowSurrogate(base[start]) && Character.isHighSurrogate(base[start - 1])) start--
        val cutAt = base.length - tail
        if (tail > 0 && cutAt > 0 && cutAt < base.length && Character.isLowSurrogate(base[cutAt]) && Character.isHighSurrogate(base[cutAt - 1])) tail--
        val removedEnd = base.length - tail
        val inserted = t.substring(start, t.length - tail)
        val from = clamp(kept, start)
        val to = clamp(kept, maxOf(removedEnd, from))
        return kept.substring(0, from) + inserted + kept.substring(to)
    }

    private fun clamp(text: String, index: Int): Int {
        val i = index.coerceIn(0, text.length)
        return if (i in 1 until text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i - 1 else i
    }
}

/** Editing view: one chunk in a text field, with page controls. Switching pages never loses edits (they are written through). */
@Composable private fun ChunkEditor(doc: ChunkedText, modifier: Modifier) {
    val count = doc.chunks.size
    val page = doc.page.coerceIn(0, count - 1)
    var field by remember(doc, page) {
        val text = doc.chunks[page]
        mutableStateOf(TextFieldValue(text, TextRange((doc.caretHint ?: 0).coerceIn(0, text.length)).also { doc.caretHint = null }))
    }
    val edit = remember(doc, page) { ChunkPageEdit(doc, page) }
    // Pages are bounded again when the editor is left.
    DisposableEffect(doc) { onDispose { doc.normalize() } }
    var wantsFocus by remember(doc) { mutableStateOf(false) }
    Column(modifier) {
        if (count > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = page > 0, onClick = { doc.normalize(); doc.page = page - 1 }, modifier = Modifier.testTag("text.prev")) { Text("Previous") }
            Text("Page ${page + 1} of $count", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).testTag("text.page"))
            TextButton(enabled = page < count - 1, onClick = { doc.normalize(); doc.page = page + 1 }, modifier = Modifier.testTag("text.next")) { Text("Next") }
        }
        // A new field for each page and after each re-split: its undo history and the keyboard's view of it then start from the
        // text the page holds, so undo can never apply an older page's text to this one.
        key(page, doc.rewrites) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (wantsFocus) focus.requestFocus() }
            OutlinedTextField(
                field, { value -> field = edit.apply(value, field) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("text.editor").focusRequester(focus).onFocusChanged { if (it.isFocused) wantsFocus = true },
            )
        }
        SideEffect { edit.fieldShows(field) }
    }
}

/**
 * The shared modal: a centred dialog on wide screens, a full-screen sheet on phones. Content is bounded text; the
 * caller supplies state and actions.
 */
@Composable internal fun TextSheet(
    filename: String, onFilenameChange: ((String) -> Unit)?, doc: ChunkedText?, loading: Boolean, error: String?,
    stateLabel: String, editing: Boolean, onDismiss: () -> Unit, onCopy: (() -> Unit)?, onDownload: (() -> Unit)?,
    header: @Composable ColumnScope.() -> Unit = {}, footer: @Composable RowScope.() -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = (if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 800.dp).fillMaxWidth(0.92f).fillMaxHeight(0.9f))
                    .testTag(if (compact) "text.sheet" else "text.modal"),
                shape = if (compact) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp).imePadding().systemBarsPadding().fillMaxSize()) {
                    if (onFilenameChange != null) OutlinedTextField(filename, onFilenameChange, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("text.filename"))
                    else Text(filename, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("text.filename"))
                    Text(
                        listOfNotNull(
                            doc?.let { "${if (it.lines == 1) "1 line" else "${it.lines} lines"} · ${TextAttachments.formatSize(it.bytes)}" },
                            stateLabel.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp).testTag("text.stats"),
                    )
                    header()
                    Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(8.dp)) {
                        when {
                            error != null -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("text.error"))
                            loading || doc == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            editing -> ChunkEditor(doc, Modifier.fillMaxSize())
                            else -> ChunkViewer(doc, Modifier.fillMaxSize())
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onCopy != null) TextButton(onClick = onCopy, modifier = Modifier.testTag("text.copy")) { Text("Copy") }
                        if (onDownload != null) TextButton(onClick = onDownload, modifier = Modifier.testTag("text.download")) { Text("Download") }
                        Spacer(Modifier.weight(1f))
                        footer()
                    }
                }
            }
        }
    }
}

private const val CLIPBOARD_LIMIT_BYTES = 512 * 1024

/** Copies text to the clipboard, or explains why not: Android refuses very large clips. */
internal fun copyText(context: android.content.Context, text: String, bytes: Long): String {
    if (bytes > CLIPBOARD_LIMIT_BYTES) return "Too large to copy (${TextAttachments.formatSize(bytes)}). Use Download."
    return try {
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Text", text))
        "Copied"
    } catch (e: Exception) { "Could not copy. Use Download." }
}

/** Download launcher: the exact text bytes go to a file the user chooses. */
@Composable internal fun rememberTextDownload(filename: String, provider: () -> ByteArray?, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = provider()
        if (bytes == null) { onResult("Nothing to save"); return@rememberLauncherForActivityResult }
        onResult(runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Could not open the file")
            "Saved"
        }.getOrElse { "Could not save the file" })
    }
    return { launcher.launch(TextAttachments.normalizeName(filename)) }
}

/**
 * Modal for a text attachment still in the composer: editable name and text, Cancel/Save. Opens on the staged bytes,
 * so it works offline; Save replaces the staged bytes and name in place and keeps the upload to the send.
 */
@Composable internal fun DraftTextDialog(model: AppModel, target: studio.otherstuff.ambulando.core.store.ChatTarget, attachment: PendingAttachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember(attachment.id) { mutableStateOf(attachment.filename) }
    var doc by remember(attachment.id) { mutableStateOf<ChunkedText?>(null) }
    var error by remember(attachment.id) { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(attachment.id) {
        withContext(Dispatchers.Default) {
            val text = model.outbox.readStagedText(attachment)
            if (text == null) error = "This attachment's text is no longer on the device." else doc = ChunkedText.of(text)
        }
    }
    val download = rememberTextDownload(name, { doc?.let { TextAttachments.bytes(it.text()) } }) { message = it }
    val changed = doc?.dirty == true || name != attachment.filename
    fun close() { if (changed) confirmDiscard = true else onDismiss() }
    TextSheet(
        filename = name, onFilenameChange = { name = it }, doc = doc, loading = doc == null && error == null, error = error,
        stateLabel = message ?: if (attachment.complete) "Uploaded" else "Saved on this device · uploads when you send",
        editing = true, onDismiss = ::close,
        onCopy = doc?.let { d -> { message = copyText(context, d.text(), d.bytes) } }, onDownload = if (doc != null) download else null,
        footer = {
            TextButton(onClick = ::close, modifier = Modifier.testTag("text.cancel")) { Text("Cancel") }
            Button(enabled = doc != null && !saving && doc?.tooLarge == false && changed, onClick = {
                val d = doc ?: return@Button
                saving = true
                model.saveDraftAttachment(target, attachment.id, d.text(), name) { ok -> saving = false; if (ok) onDismiss() else message = "Could not save" }
            }, modifier = Modifier.testTag("text.save")) { Text("Save") }
        },
    )
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard changes?") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
}

/**
 * Modal for a sent text attachment. Read mode shows the current version (original or the latest file version) with the
 * earlier ones one tap away; Edit saves a new version without touching the signed message or the original object.
 */
@Composable internal fun SentTextDialog(
    model: AppModel, messageId: String, channelId: String, attachment: MessageAttachment, editable: Boolean, onDismiss: () -> Unit,
) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    key(c.id, c.origin, c.signerNpub, w.id, messageId, attachment.storageObjectId) {
        SentTextDialogContent(model, messageId, channelId, attachment, editable, onDismiss)
    }
}

@Composable private fun SentTextDialogContent(
    model: AppModel, messageId: String, channelId: String, attachment: MessageAttachment, editable: Boolean, onDismiss: () -> Unit,
) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val context = LocalContext.current
    val service = model.textAttachments(c, w.id)
    var history by remember { mutableStateOf<TextAttachmentService.History?>(service.cachedHistory(w.id, messageId, attachment)) }
    var selected by remember { mutableStateOf<TextAttachmentService.Entry?>(history?.current) }
    var doc by remember { mutableStateOf<ChunkedText?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var name by remember(attachment.storageObjectId) { mutableStateOf(attachment.filename) }
    var edit by remember { mutableStateOf(service.edits.forAttachment(c.id, c.origin, w.id, messageId, attachment.storageObjectId)) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmDeletePending by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf(attachment.filename) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    // Cached history is visible immediately; network refresh must not replace an active local editor.
    LaunchedEffect(messageId, attachment.storageObjectId, reload) {
        val h = try {
            offline = false
            service.history(model.towerAPI(c), w.id, w.appNpub, channelId, messageId, attachment)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            offline = true
            service.cachedHistory(w.id, messageId, attachment)
        }
        if (!editing && (reload > 0 || doc?.dirty != true)) {
            history = h
            name = h.filename ?: attachment.filename
            savedName = name
            if (selected == null || reload > 0 || selected?.isCurrent == true) selected = h.current
        }
    }
    // The selected version's text: cache first, then Tower; never more than one version is held.
    LaunchedEffect(selected, reload) {
        if (editing) return@LaunchedEffect
        val h = history ?: return@LaunchedEffect
        val entry = selected ?: return@LaunchedEffect
        error = null; doc = null
        val bytes = try {
            service.cached(entry) ?: service.read(model.towerAPI(c), w.id, w.appNpub, h.fileId, entry)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            error = if (offline || e is java.io.IOException) "Not available offline yet. Open it once while connected to keep a copy on this device." else "Could not load this version."
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.Default) { ChunkedText.of(TextAttachments.text(bytes)) }
        if (!editing) doc = loaded
    }
    val download = rememberTextDownload(name, { doc?.let { TextAttachments.bytes(it.text()) } }) { message = it }

    fun stateLabel(): String = message ?: when {
        busy -> "Saving…"
        edit?.state == TextEditState.CONFLICT -> "Conflict: a newer version exists"
        edit?.state == TextEditState.FAILED -> edit?.message ?: "Could not save"
        edit?.localOnly == true -> "Saved on this device · not sent to Tower"
        edit?.state == TextEditState.PENDING -> edit?.message ?: "Edit saved on this device · waiting to upload"
        offline -> "Offline · showing the copy on this device"
        else -> selected?.let { if (it.isOriginal && history?.entries?.size == 1) "Sent" else it.label + if (it.isCurrent) " (current)" else "" } ?: ""
    }

    val unsaved = editing && (doc?.dirty == true || name != savedName)
    fun requestClose() { if (unsaved) confirmClose = true else onDismiss() }

    /** A journal commit is a local save, independent of upload or conflict resolution. */
    fun saveOnDevice(localOnly: Boolean = false): TextEdit? {
        val h = history ?: return null
        val d = doc ?: return null
        return try {
            val previous = edit?.takeIf { it.state != TextEditState.SAVED }
            check(previous?.uncertain != true) { "Retry the previous save before changing its contents" }
            val id = previous?.id ?: studio.otherstuff.ambulando.core.transport.newRequestId()
            // Resuming mine keeps its original base. Only explicit Save over latest may adopt a newer remote base.
            val baseFile = if (previous != null) previous.fileId else h.fileId
            val baseVersion = if (previous != null) previous.baseVersionId else h.currentVersionId
            val begun = previous?.takeIf { it.versionAccepted } ?: service.beginEdit(id, c.id, w.id, channelId, w.appNpub,
                messageId, attachment, name, d.text(), baseFile, baseVersion, c.origin, localOnly)
            edit = begun; name = begun.filename; savedName = begun.filename; d.markSaved()
            message = null
            begun
        } catch (e: Exception) {
            message = "Could not save on this device. Keep this editor open and retry."
            null
        }
    }

    fun finishSave(result: TextEdit) {
        busy = false; edit = result; message = null
        if ((result.versionAccepted || result.uncertain) && result.state != TextEditState.SAVED) {
            editing = false; doc?.markSaved(); reload++
        }
        if (result.state == TextEditState.SAVED) {
            editing = false; doc?.markSaved(); message = "Saved as a new version"
            service.edits.removeRevision(result); edit = null; reload++
        }
    }

    fun beginSave() {
        val begun = saveOnDevice() ?: return
        busy = true
        model.saveSentEdit(begun.id, ::finishSave)
    }

    TextSheet(
        filename = name, onFilenameChange = if (editing && !busy && edit?.versionAccepted != true) ({ name = it }) else null, doc = doc, loading = doc == null && error == null, error = error,
        stateLabel = stateLabel(), editing = editing && !busy && edit?.versionAccepted != true, onDismiss = ::requestClose,
        onCopy = doc?.let { d -> { message = copyText(context, d.text(), d.bytes) } }, onDownload = if (doc != null) download else null,
        header = {
            val entries = history?.entries.orEmpty()
            if (entries.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp).testTag("text.history"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { entry ->
                    FilterChip(selected = entry == selected, enabled = !editing, onClick = { selected = entry },
                        label = { Text(entry.label + if (entry.isCurrent) " ✓" else "") }, modifier = Modifier.testTag("text.history.${entry.label}"))
                }
            }
            val pending = edit
            if (pending != null) Card(Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("text.pending")) {
                Column(Modifier.padding(8.dp)) {
                    Text(pending.message ?: if (pending.localOnly) "Your edit is kept on this device. Save to Tower when ready." else "Your edit is saved on this device.", style = MaterialTheme.typography.bodySmall)
                    if (pending.versionAccepted && pending.renamePending) Text("Requested filename: ${pending.filename}", style = MaterialTheme.typography.bodySmall)
                    if (!editing && !pending.versionAccepted && !pending.uncertain) TextButton(enabled = !busy, onClick = {
                        service.edits.text(pending)?.let { t ->
                            name = pending.filename; savedName = name; doc = ChunkedText.of(t); editing = true; message = null
                        }
                    }, modifier = Modifier.testTag("text.resume")) { Text("Resume editing") }
                    Row {
                        if (pending.state == TextEditState.CONFLICT) TextButton(enabled = !busy && !unsaved, onClick = {
                            busy = true
                            model.act {
                                try {
                                    val rebased = service.rebase(model.towerAPI(c), pending.id); edit = rebased
                                    model.saveSentEdit(rebased.id, ::finishSave)
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                    busy = false; message = "Could not reach Tower. Your edit is kept on this device."
                                }
                            }
                        }, modifier = Modifier.testTag("text.saveOverLatest")) { Text(if (pending.versionAccepted) "Use my filename" else "Save over latest") }
                        if (pending.state == TextEditState.PENDING) TextButton(enabled = !busy && !unsaved, onClick = {
                            busy = true; model.saveSentEdit(pending.id, ::finishSave)
                        }, modifier = Modifier.testTag("text.retry")) { Text(if (pending.localOnly) "Save to Tower" else "Retry") }
                        TextButton(enabled = !busy, onClick = { confirmDeletePending = true }, modifier = Modifier.testTag("text.discardSaved")) { Text("Discard saved edit") }
                    }
                    if (unsaved) Text("Save your new changes before resolving the pending edit.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        footer = {
            if (editing) {
                TextButton(enabled = !busy, onClick = ::requestClose, modifier = Modifier.testTag("text.close")) { Text("Close") }
                Button(enabled = doc != null && !busy && doc?.tooLarge == false && (unsaved || edit == null), onClick = ::beginSave, modifier = Modifier.testTag("text.save")) { Text("Save") }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("text.close")) { Text("Close") }
                if (editable) Button(enabled = doc != null && error == null && edit?.versionAccepted != true && edit?.uncertain != true, onClick = { editing = true; message = null }, modifier = Modifier.testTag("text.edit")) { Text("Edit") }
            }
        },
    )
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Unsaved changes") },
        text = { Text("Keep these changes on this device before closing, or discard only changes since your last local save. Any earlier saved pending edit is kept.") },
        confirmButton = { TextButton(onClick = { if (saveOnDevice(localOnly = true) != null) { confirmClose = false; onDismiss() } }, modifier = Modifier.testTag("text.keepLocalClose")) { Text("Keep on device and close") } },
        dismissButton = { Column {
            TextButton(onClick = { confirmClose = false; onDismiss() }, modifier = Modifier.testTag("text.discardUnsaved")) { Text("Discard unsaved changes") }
            TextButton(onClick = { confirmClose = false }) { Text("Keep editing") }
        } })
    if (confirmDeletePending) AlertDialog(onDismissRequest = { confirmDeletePending = false }, title = { Text("Discard saved edit?") },
        text = { Text("This removes your pending copy and any unsaved changes in this editor. The sent attachment and its file history stay available.") },
        confirmButton = { TextButton(onClick = {
            edit?.let { service.discard(it.id) }; edit = null; editing = false; doc?.markSaved();
            confirmDeletePending = false; message = null; reload++
        }, modifier = Modifier.testTag("text.confirmDiscardSaved")) { Text("Discard saved edit") } },
        dismissButton = { TextButton(onClick = { confirmDeletePending = false }) { Text("Keep edit") } })
}
