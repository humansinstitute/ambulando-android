package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import androidx.compose.runtime.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ReactionEmoji
import studio.otherstuff.ambulando.core.transport.ReactionRecord
import androidx.compose.ui.platform.testTag
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.chat.AutopilotSessionLink

/** The message's top-right controls (iOS `messageControls`): the sync dot and the ⋮ menu holding every action, Edit and Delete included. */
@Composable internal fun ChatMessageControls(model: AppModel, message: MessageRecord, enabled: Boolean, onEdit: (MessageRecord) -> Unit, onDelete: (MessageRecord) -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val writes by remember(message.id) { model.store.liveQuery({ it.table == StoreTable.WRITES }) { model.store.reader.workspaceWrites(c.id, w.id).filter { it.targetId == message.id } } }.collectAsState(emptyList())
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        SyncDot(failed = writes.any { !it.isPending }, pending = writes.isNotEmpty(), modifier = androidx.compose.ui.Modifier.testTag("message.${message.id}.sync"))
        MessageMenu(model, message, message.threadSourceId ?: message.takeIf { it.isThreadRoot }?.id,
            "@[Chat message](mention:chat:${message.channelId}#${message.id})", message.takeIf { AutopilotSessionLink.sessionId(it.raw) != null },
            enabled, "message.${message.id}", "Message actions",
            onEdit = { onEdit(message) }.takeIf { message.senderNpub == c.signerNpub && !message.deleted },
            onDelete = { onDelete(message) })
    }
}

/** Reactions already on the message, then the quick thumbs-up and "+" (iOS `ReactionBar`). Reactions go by wire name. */
@Composable internal fun ChatMessageReactions(model: AppModel, message: MessageRecord, enabled: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val reactions by remember(message.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.reactions(w.id, listOf(message.id)) } }.collectAsState(emptyList())
    fun payload(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    ReactionBar(reactions, c.signerNpub, enabled, modifier) { emoji, existing ->
        model.submit(if (existing == null) WriteIntent(WriteKind.REACTION_ADD, message.id, payload { put("target_type", "message"); put("emoji", emoji.wire) })
        else WriteIntent(WriteKind.REACTION_REMOVE, message.id, payload { put("reaction_id", existing.id) }))
    }
}

/**
 * The edit and delete dialogs of a chat message. They belong to the screen, not to the message row: a row scrolls out of the
 * list (a new message, the keyboard resizing the list) and its state goes with it, which closed the dialog and lost the edit.
 * [edit] is the message as it was when Edit was tapped: the text being edited and the version it is saved against stay together.
 */
@Composable internal fun ChatMessageDialogs(model: AppModel, edit: MessageRecord?, delete: MessageRecord?, onEditDone: () -> Unit, onDeleteDone: () -> Unit) {
    val w = model.workspace ?: return
    fun payload(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    edit?.let { editing -> studio.otherstuff.ambulando.app.ui.chat.MessageEditDialog(model, w.id, editing.body, onSave = { encoded ->
        model.submit(WriteIntent(WriteKind.MESSAGE_EDIT, editing.id, payload {
            put("channel_id", editing.channelId); editing.threadId?.let { put("thread_id", it) }; put("body", encoded.body); put("row_version", editing.rowVersion)
            put("mentions", JsonArray(encoded.mentions.map { m -> buildJsonObject { m.requestObject.forEach { (k, v) -> put(k, v) } } }))
        })); onEditDone()
    }, onDismiss = { onEditDone() }) }
    delete?.let { message -> AlertDialog(onDismissRequest = { onDeleteDone() }, title = { Text("Delete message?") }, confirmButton = { TextButton(onClick = {
        model.submit(WriteIntent(WriteKind.MESSAGE_DELETE, message.id, payload { put("channel_id", message.channelId); put("row_version", message.rowVersion); message.threadId?.let { put("thread_id", it) } })); onDeleteDone()
    }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { onDeleteDone() }) { Text("Cancel") } }) }
}

/**
 * Existing reactions with their count (yours filled), then the quick thumbs-up and a "+" that opens the picker of every reaction
 * Tower accepts. Reactions are sent by wire name (`thumbs_up`); rows an earlier build stored by glyph still count and toggle.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun ReactionBar(reactions: List<ReactionRecord>, me: String?, enabled: Boolean, modifier: androidx.compose.ui.Modifier, toggle: (ReactionEmoji, ReactionRecord?) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    val live = reactions.filter { !it.deleted }
    // Wraps, so seven reactions never overflow a narrow screen.
    FlowRow(modifier, verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        ReactionEmoji.entries.forEach { emoji ->
            val count = live.count { ReactionEmoji.fromStored(it.emoji) == emoji }
            if (count == 0) return@forEach
            val mine = live.firstOrNull { ReactionEmoji.fromStored(it.emoji) == emoji && it.reactorNpub == me }
            ReactionPill(enabled, mine != null, "${emoji.displayName}, $count" + if (mine != null) ", including you" else "", "reaction.${emoji.wire}", { toggle(emoji, mine) }) {
                Text(emoji.glyph + " " + count, fontSize = AmbType.footnote, fontWeight = FontWeight.SemiBold, color = if (mine != null) Amb.colors.primary else Amb.colors.foreground)
            }
        }
        val thumbs = live.firstOrNull { ReactionEmoji.fromStored(it.emoji) == ReactionEmoji.THUMBS_UP && it.reactorNpub == me }
        ReactionPill(enabled, false, "React with thumbs up", "reaction.quick", { toggle(ReactionEmoji.THUMBS_UP, thumbs) }) {
            Icon(Icons.Outlined.ThumbUp, null, Modifier.size(16.dp), tint = Amb.colors.mutedForeground)
        }
        Box {
            ReactionPill(enabled, false, "Add reaction", "reaction.add", { picker = true }) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp), tint = Amb.colors.mutedForeground)
            }
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

/** One reaction control: a 32dp bordered pill (iOS `ReactionBar`) inside a 44dp touch target. */
@Composable private fun ReactionPill(enabled: Boolean, selected: Boolean, label: String, tag: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val amb = Amb.colors
    Box(
        androidx.compose.ui.Modifier.defaultMinSize(minWidth = 40.dp, minHeight = Amb.touchTarget)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }.testTag(tag),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(
            androidx.compose.ui.Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                .background(if (selected) amb.selectedFill else amb.card, RoundedCornerShape(Amb.radiusControl))
                .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusControl)).padding(horizontal = 8.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { content() }
    }
}

/** The message's sync state (iOS sync dot): green when saved, amber while a change is queued, red when one failed. */
@Composable internal fun SyncDot(failed: Boolean, pending: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val amb = Amb.colors
    val (color, label) = when { failed -> amb.destructive to "A change to this message was not saved"; pending -> amb.warning to "Saving a change to this message"; else -> amb.success to "Saved" }
    Box(modifier.size(8.dp).background(color, CircleShape).semantics { contentDescription = label })
}
