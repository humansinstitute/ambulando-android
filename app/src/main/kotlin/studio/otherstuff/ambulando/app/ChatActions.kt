package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ReactionEmoji
import studio.otherstuff.ambulando.core.transport.ReactionRecord
import androidx.compose.ui.platform.testTag
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.chat.AutopilotSessionLink

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun ChatMessageActions(model: AppModel, message: MessageRecord, enabled: Boolean) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    // The message as it was when Edit was tapped: the text being edited and the version it is saved against stay together.
    var edit by remember(message.id) { mutableStateOf<MessageRecord?>(null) }
    var delete by remember(message.id) { mutableStateOf(false) }
    val reactions by remember(message.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.reactions(w.id, listOf(message.id)) } }.collectAsState(emptyList())
    fun payload(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    FlowRow(verticalArrangement = Arrangement.Center) {
        MessageMenu(model, message, message.threadSourceId ?: message.takeIf { it.isThreadRoot }?.id,
            "@[Chat message](mention:chat:${message.channelId}#${message.id})", message.takeIf { AutopilotSessionLink.sessionId(it.raw) != null },
            enabled, "message.${message.id}", "Message actions")
        ReactionBar(reactions, c.signerNpub, enabled) { emoji, existing ->
            model.submit(if (existing == null) WriteIntent(WriteKind.REACTION_ADD, message.id, payload { put("target_type", "message"); put("emoji", emoji.wire) })
            else WriteIntent(WriteKind.REACTION_REMOVE, message.id, payload { put("reaction_id", existing.id) }))
        }
        if (message.senderNpub == c.signerNpub && !message.deleted) {
            TextButton(enabled = enabled, onClick = { edit = message }, modifier = androidx.compose.ui.Modifier.testTag("message.edit.${message.id}")) { Text("Edit") }
            TextButton(enabled = enabled, onClick = { delete = true }, modifier = androidx.compose.ui.Modifier.testTag("message.delete.${message.id}")) { Text("Delete") }
        }
    }
    edit?.let { editing -> studio.otherstuff.ambulando.app.ui.chat.MessageEditDialog(model, w.id, editing.body, onSave = { encoded ->
        model.submit(WriteIntent(WriteKind.MESSAGE_EDIT, editing.id, payload {
            put("channel_id", editing.channelId); editing.threadId?.let { put("thread_id", it) }; put("body", encoded.body); put("row_version", editing.rowVersion)
            put("mentions", JsonArray(encoded.mentions.map { m -> buildJsonObject { m.requestObject.forEach { (k, v) -> put(k, v) } } }))
        })); edit = null
    }, onDismiss = { edit = null }) }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete message?") }, confirmButton = { TextButton(onClick = {
        model.submit(WriteIntent(WriteKind.MESSAGE_DELETE, message.id, payload { put("channel_id", message.channelId); put("row_version", message.rowVersion); message.threadId?.let { put("thread_id", it) } })); delete = false
    }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}

/**
 * Reactions with a count (yours highlighted by its "✓") plus a picker of every reaction Tower accepts. Reactions are
 * sent by wire name (`thumbs_up`); rows an earlier build stored by glyph still count and toggle.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun ReactionBar(reactions: List<ReactionRecord>, me: String?, enabled: Boolean, toggle: (ReactionEmoji, ReactionRecord?) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    val live = reactions.filter { !it.deleted }
    // Wraps, so seven reactions never overflow a narrow screen.
    FlowRow(verticalArrangement = Arrangement.Center) {
        ReactionEmoji.entries.forEach { emoji ->
            val count = live.count { ReactionEmoji.fromStored(it.emoji) == emoji }
            if (count == 0) return@forEach
            val mine = live.firstOrNull { ReactionEmoji.fromStored(it.emoji) == emoji && it.reactorNpub == me }
            TextButton(enabled = enabled, onClick = { toggle(emoji, mine) }, modifier = androidx.compose.ui.Modifier.testTag("reaction.${emoji.wire}")) {
                Text(emoji.glyph + " " + count + if (mine != null) " ✓" else "")
            }
        }
        Box {
            TextButton(enabled = enabled, onClick = { picker = true }, modifier = androidx.compose.ui.Modifier.testTag("reaction.add")) { Text("React") }
            DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                ReactionEmoji.entries.forEach { emoji ->
                    val mine = live.firstOrNull { ReactionEmoji.fromStored(it.emoji) == emoji && it.reactorNpub == me }
                    DropdownMenuItem(text = { Text(emoji.glyph + "  " + emoji.displayName + if (mine != null) "  ✓" else "") }, onClick = { picker = false; toggle(emoji, mine) },
                        modifier = androidx.compose.ui.Modifier.testTag("reaction.pick.${emoji.wire}"))
                }
            }
        }
    }
}
