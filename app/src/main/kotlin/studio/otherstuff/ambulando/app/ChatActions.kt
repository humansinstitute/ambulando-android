package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.chat.AutopilotSessionLink

@Composable internal fun ChatMessageActions(model: AppModel, message: MessageRecord, enabled: Boolean) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    var edit by remember(message.id) { mutableStateOf(false) }
    var text by remember(message.id) { mutableStateOf(message.body) }
    var delete by remember(message.id) { mutableStateOf(false) }
    val reactions by remember(message.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.reactions(w.id, listOf(message.id)) } }.collectAsState(emptyList())
    fun payload(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    Row {
        MessageMenu(model, message, message.threadSourceId ?: message.takeIf { it.isThreadRoot }?.id,
            "@[Chat message](mention:chat:${message.channelId}#${message.id})", message.takeIf { AutopilotSessionLink.sessionId(it.raw) != null },
            enabled, "message.${message.id}", "Message actions")
        listOf("👍", "❤️", "✅").forEach { emoji ->
            val existing = reactions.firstOrNull { it.emoji == emoji && it.reactorNpub == c.signerNpub && !it.deleted }
            TextButton(enabled = enabled, onClick = {
                model.submit(if (existing == null) WriteIntent(WriteKind.REACTION_ADD, message.id, payload { put("target_type", "message"); put("emoji", emoji) })
                else WriteIntent(WriteKind.REACTION_REMOVE, message.id, payload { put("reaction_id", existing.id) }))
            }) { Text(emoji + reactions.count { !it.deleted && it.emoji == emoji }.takeIf { it > 0 }?.let { " $it" }.orEmpty()) }
        }
        if (message.senderNpub == c.signerNpub && !message.deleted) {
            TextButton(enabled = enabled, onClick = { text = message.body; edit = true }) { Text("Edit") }
            TextButton(enabled = enabled, onClick = { delete = true }) { Text("Delete") }
        }
    }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("Edit message") }, text = { OutlinedTextField(text, { text = it }) }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
        model.submit(WriteIntent(WriteKind.MESSAGE_EDIT, message.id, payload {
            put("channel_id", message.channelId); message.threadId?.let { put("thread_id", it) }; put("body", text.trim()); put("row_version", message.rowVersion); put("mentions", JsonArray(emptyList()))
        })); edit = false
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete message?") }, confirmButton = { TextButton(onClick = {
        model.submit(WriteIntent(WriteKind.MESSAGE_DELETE, message.id, payload { put("channel_id", message.channelId); put("row_version", message.rowVersion); message.threadId?.let { put("thread_id", it) } })); delete = false
    }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}
