package studio.otherstuff.ambulando.app

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
    TextButton(enabled = enabled && !removed && !w.accessRemoved, onClick = { model.submit(WriteIntent(WriteKind.PIN_SET, id, buildJsonObject {
        put("resource_type", type.wire); put("pinned", !current.third); put("expected_row_version", current.first?.rowVersion ?: 0); put("mutation_id", UUID.randomUUID().toString())
    })) }) { Text(if (current.third) "Unpin" else "Pin") }
    current.second?.lastError?.let { Text(it) }
}

@Composable internal fun DocumentComments(model: AppModel, docId: String, enabled: Boolean) {
    val w = model.workspace ?: return
    val rows by remember(w.id, docId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.comments(w.id, CommentRecord.Parent.DOC, docId) } }.collectAsState(emptyList())
    var text by remember(docId) { mutableStateOf("") }
    val pending by remember(docId) { model.store.liveQuery({ it.table == StoreTable.WRITES }) { model.store.reader.workspaceWrites(model.connection!!.id, w.id, docId).filter { it.kind == WriteKind.DOC_COMMENT } } }.collectAsState(emptyList())
    Text("Comments", style = MaterialTheme.typography.titleMedium)
    rows.forEach { row ->
        RichBlocks(DocumentRender.markdownBlocks(row.body), actions = storedRichActions(model))
        TextButton(enabled = enabled && !row.accessRemoved, onClick = { model.submit(WriteIntent(WriteKind.DOC_COMMENT_STATUS, row.id, buildJsonObject {
            put("doc_id", docId); put("status", if (row.status == "resolved") "open" else "resolved"); put("row_version", row.rowVersion)
        })) }) { Text(if (row.status == "resolved") "Reopen" else "Resolve") }
    }
    pending.forEach { item -> Column { Text(item.string("body") ?: "Comment"); Text(item.lastError ?: item.state.wire)
        Row { TextButton(onClick = { model.act { model.writes.retry(item.id, model.connection!!) } }) { Text("Retry") }; TextButton(onClick = { model.act { model.store.writer.discardWrite(item.id) } }) { Text("Discard") } }
    } }
    OutlinedTextField(text, { text = it }, enabled = enabled, label = { Text("Add comment") })
    TextButton(enabled = enabled && text.isNotBlank(), onClick = {
        if (model.submit(WriteIntent(WriteKind.DOC_COMMENT, docId, buildJsonObject { put("body", text) }))) text = ""
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
