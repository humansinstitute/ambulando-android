package studio.otherstuff.ambulando.app

import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import studio.otherstuff.ambulando.core.docs.moveDocument
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.ui.rich.RichBlocks
import studio.otherstuff.ambulando.core.docs.DocumentRender
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.authorNpub
import studio.otherstuff.ambulando.core.transport.obj
import java.io.File
import java.util.UUID

@Composable internal fun RecordPin(model: AppModel, type: PinRecord.ResourceType, id: String, enabled: Boolean = true) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val current by remember(c.id, w.id, type, id) { model.store.liveQuery({ it.workspaceId == w.id || it.table == StoreTable.WRITES }) {
        val saved = model.store.reader.pin(c.id, w.id, type, id)
        val queued = model.store.reader.workspaceWrites(c.id, w.id).lastOrNull { it.kind == WriteKind.PIN_SET && it.targetId == id && it.string("resource_type") == type.wire }
        Triple(saved, queued, queued?.payloadObject?.get("pinned")?.jsonPrimitive?.booleanOrNull ?: saved?.pinned ?: false)
    } }.collectAsState(Triple(null, null, false))
    val removed by remember(w.id, type, id) { model.store.liveQuery({ it.workspaceId == w.id }) {
        val table = when (type.wire) { "task" -> FlaggedTable.TASKS; "document" -> FlaggedTable.DOCS; "thread" -> FlaggedTable.THREADS; else -> FlaggedTable.CHANNELS }
        model.store.reader.isAccessRemoved(w.id, table, id)
    } }.collectAsState(false)
    var showFailure by remember(type, id) { mutableStateOf(false) }
    val failure = current.second?.takeIf { !it.isPending }
    androidx.compose.material3.IconButton(modifier = androidx.compose.ui.Modifier.testTag("pin.toggle"), enabled = enabled && !removed && !w.accessRemoved, onClick = {
        if (failure != null) showFailure = true else model.submit(WriteIntent(WriteKind.PIN_SET, id, buildJsonObject {
        put("resource_type", type.wire); put("pinned", !current.third); put("expected_row_version", current.first?.rowVersion ?: 0); put("mutation_id", UUID.randomUUID().toString())
    })) }) {
        Icon(when { failure != null -> androidx.compose.material.icons.Icons.Outlined.WarningAmber; current.third -> androidx.compose.material.icons.Icons.Filled.PushPin; else -> androidx.compose.material.icons.Icons.Outlined.PushPin },
            if (failure != null) "Pin not saved" else if (current.third) "Unpin" else "Pin")
    }
    if (showFailure && failure != null) AlertDialog(onDismissRequest = { showFailure = false }, title = { Text("Pin not saved") }, text = { Text(failure.lastError ?: "Try again or discard this change.") },
        confirmButton = { TextButton(onClick = { showFailure = false; model.act { model.writes.retry(failure.id, c) } }) { Text("Retry") } },
        dismissButton = { TextButton(onClick = { showFailure = false; model.act { model.store.writer.discardWrite(failure.id) } }) { Text("Discard") } })
}

/**
 * Document comments: author photo and name, time, the quoted text a selection comment was made on, threaded replies,
 * resolve/reopen, and a composer with the `@` lookahead (people and agents become structured mentions). Pending and
 * failed comments stay visible with Retry and Discard.
 */
@Composable internal fun DocumentComments(model: AppModel, docId: String, enabled: Boolean) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val rows by remember(w.id, docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.comments(w.id, CommentRecord.Parent.DOC, docId) } }.collectAsState(emptyList())
    val members by remember(w.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.directoryMembers(w.id) } }.collectAsState(emptyList())
    val people by remember(c.id, w.id) { model.store.liveQuery({ it.workspaceId == w.id || it.table == StoreTable.PROFILES }) { model.store.reader.people(c.id, w.id) } }.collectAsState(null)
    val names = remember(members, people) { members.associate { it.npub to it.label } + (people?.names ?: emptyMap()) }
    val draft = studio.otherstuff.ambulando.app.ui.work.rememberCommentDraft(docId)
    var replyTo by remember(docId) { mutableStateOf<String?>(null) }
    val pending by remember(docId) { model.store.liveQuery({ it.table == StoreTable.WRITES }) { model.store.reader.workspaceWrites(c.id, w.id, docId).filter { it.kind == WriteKind.DOC_COMMENT } } }.collectAsState(emptyList())
    val actions = studio.otherstuff.ambulando.app.ui.work.workRichActions()
    val roots = rows.filter { it.parentCommentId == null || rows.none { other -> other.id == it.parentCommentId } }
    @Composable fun commentRow(row: CommentRecord, reply: Boolean) {
        val author = row.authorNpub(members)
        Column(Modifier.padding(start = if (reply) 24.dp else 0.dp, top = 8.dp).testTag("doc.comment.${row.id}")) {
            val quote = row.raw.obj("metadata")?.let { studio.otherstuff.ambulando.core.docs.DocumentAnchor.fromMetadata(it) }?.quote
            if (quote != null) Text("“" + quote.take(160) + "”", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("doc.comment.quote"))
            studio.otherstuff.ambulando.app.ui.work.CommentRow(row, studio.otherstuff.ambulando.app.ui.work.personFor(people, author, c.signerNpub, names), actions)
            Row {
                if (!reply) TextButton(enabled = enabled && !row.accessRemoved, onClick = { replyTo = row.id }, modifier = Modifier.testTag("doc.comment.reply")) { Text("Reply") }
                TextButton(enabled = enabled && !row.accessRemoved, onClick = { model.submit(WriteIntent(WriteKind.DOC_COMMENT_STATUS, row.id, buildJsonObject {
                    put("doc_id", docId); put("status", if (row.status == "resolved") "open" else "resolved"); put("row_version", row.rowVersion)
                })) }) { Text(if (row.status == "resolved") "Reopen" else "Resolve") }
            }
        }
    }
    Text("Comments", style = MaterialTheme.typography.titleMedium)
    roots.forEach { root ->
        commentRow(root, false)
        rows.filter { it.parentCommentId == root.id }.forEach { commentRow(it, true) }
    }
    pending.forEach { item -> Column(Modifier.padding(top = 8.dp)) { Text(item.string("body")?.let(studio.otherstuff.ambulando.core.chat.MentionCodec::plain) ?: "Comment"); Text(item.lastError ?: item.state.wire, style = MaterialTheme.typography.labelSmall)
        Row { TextButton(onClick = { model.act { model.writes.retry(item.id, c) } }) { Text("Retry") }; TextButton(onClick = { model.act { model.store.writer.discardWrite(item.id) } }) { Text("Discard") } }
    } }
    replyTo?.let { id -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Replying to " + (rows.firstOrNull { it.id == id }?.let { studio.otherstuff.ambulando.app.ui.work.displayName(it.authorNpub(members) ?: "", names, c.signerNpub) } ?: "a comment"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { replyTo = null }) { Text("Cancel") }
    } }
    studio.otherstuff.ambulando.app.ui.work.MentionTextEntry(draft.text, { draft.text = it }, draft.mentions, { draft.mentions = it },
        { query -> model.store.reader.mentionCandidates(w.id, query, viewerNpub = c.signerNpub) }, "Add comment", Modifier.fillMaxWidth().padding(vertical = 8.dp), Modifier.fillMaxWidth().testTag("doc.comment.field"), enabled = enabled)
    TextButton(enabled = enabled && !draft.isBlank, modifier = Modifier.testTag("doc.comment.send"), onClick = {
        val encoded = draft.encoded()
        if (model.submit(WriteIntent(WriteKind.DOC_COMMENT, docId, buildJsonObject {
            put("body", encoded.body); replyTo?.let { put("parent_comment_id", it) }
        }))) { draft.clear(); replyTo = null }
    }) { Text("Comment") }
}

@Composable internal fun CreateDocument(model: AppModel) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    var selectedChannel by remember(model.context) { mutableStateOf(model.context.channelId) }
    var showing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    TextButton(onClick = { showing = true }) { Text("New document") }
    if (showing) AlertDialog(onDismissRequest = { showing = false }, title = { Text("New document") }, text = { Column {
        OutlinedTextField(title, { title = it }, label = { Text("Title") })
        model.channels.filter { !it.accessRemoved && (model.context.scopeId == null || it.scopeId == model.context.scopeId) }.forEach { channel -> TextButton(onClick = { selectedChannel = channel.id }) { Text((if (selectedChannel == channel.id) "✓ " else "") + channel.name) } }
    } }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && selectedChannel != null, onClick = {
            val channel = selectedChannel ?: return@TextButton
            val captured = title
            model.act {
                val staged = "doc-${UUID.randomUUID()}"
                withContext(Dispatchers.IO) { model.writes.staging.mkdirs(); File(model.writes.staging, staged).writeText("# $captured\n") }
                model.writes.submit(WriteIntent(WriteKind.DOC_CREATE, null, buildJsonObject {
                    put("title", captured); put("channel_id", channel); put("file_name", "document.md"); put("staged", staged); put("content_type", "text/markdown")
                }), c, w)
            }
            showing = false
        }) { Text("Create") }
    }, dismissButton = { TextButton(onClick = { showing = false }) { Text("Cancel") } })
}

/**
 * Moves the document to another channel, online only. The dialog states what changes (access follows the destination,
 * scope changes, entering or leaving a private conversation needs an explicit acknowledgement) before anything is sent,
 * and reports exactly what Tower confirmed, including "not known" when an answer was lost.
 */
@Composable internal fun MoveDocument(model: AppModel, doc: studio.otherstuff.ambulando.core.transport.DocRecord, enabled: Boolean) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    var open by remember(doc.id) { mutableStateOf(false) }
    var destination by remember(doc.id) { mutableStateOf<studio.otherstuff.ambulando.core.transport.ChannelRecord?>(null) }
    var acknowledged by remember(doc.id) { mutableStateOf(false) }
    var working by remember(doc.id) { mutableStateOf(false) }
    var outcome by remember(doc.id) { mutableStateOf<String?>(null) }
    val source = model.channels.firstOrNull { it.id == doc.channelId }
    val names by produceState(emptyMap<String, String>(), w.id) { value = model.store.reader.directoryMembers(w.id).associate { it.npub to it.label } }
    TextButton(enabled = enabled && source != null && services.unlocked, onClick = { open = true; outcome = null }, modifier = Modifier.testTag("doc.move")) { Text("Move…") }
    if (!open || source == null) return
    val plan = destination?.let { studio.otherstuff.ambulando.core.docs.DocMovePlan.consequences(source, it, c.signerNpub, names) }
    AlertDialog(onDismissRequest = { if (!working) open = false }, title = { Text("Move document") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("Choose a destination channel", style = MaterialTheme.typography.labelLarge)
            model.channels.filter { !it.accessRemoved && it.id != doc.channelId }.forEach { channel ->
                val scope = model.scopes.firstOrNull { it.id == channel.scopeId }?.name
                TextButton(onClick = { destination = channel; acknowledged = false; outcome = null }, modifier = Modifier.testTag("doc.move.dest.${channel.id}")) {
                    Text((if (destination?.id == channel.id) "✓ " else "") + listOfNotNull(scope, "#" + channel.name).joinToString(" · "))
                }
            }
            plan?.lines?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            if (plan?.touchesPrivateConversation == true) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledged = it }, modifier = Modifier.testTag("doc.move.ack")); Text("I understand who can read it afterwards", style = MaterialTheme.typography.bodySmall)
            }
            outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).testTag("doc.move.outcome")) }
        }
    }, confirmButton = {
        TextButton(enabled = !working && destination != null && (plan?.touchesPrivateConversation != true || acknowledged), modifier = Modifier.testTag("doc.move.confirm"), onClick = {
            val target = destination ?: return@TextButton
            working = true; outcome = null
            model.act {
                try {
                    outcome = when (val result = model.sync.moveDocument(c, w, doc.id, target, doc.rowVersion.toInt())) {
                        is studio.otherstuff.ambulando.core.docs.DocMoveResult.Moved -> { open = false; result.draftNote?.let { outcome = it; open = true }; return@act }
                        is studio.otherstuff.ambulando.core.docs.DocMoveResult.Stale -> result.message
                        is studio.otherstuff.ambulando.core.docs.DocMoveResult.SameDestination -> result.message
                        is studio.otherstuff.ambulando.core.docs.DocMoveResult.Denied -> result.message
                        is studio.otherstuff.ambulando.core.docs.DocMoveResult.NotMoved -> result.message
                        is studio.otherstuff.ambulando.core.docs.DocMoveResult.Unknown -> result.message
                    }
                } finally { working = false }
            }
        }) { Text(if (working) "Moving…" else "Move") }
    }, dismissButton = { TextButton(enabled = !working, onClick = { open = false }) { Text("Close") } })
}
