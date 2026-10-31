package studio.otherstuff.ambulando.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import studio.otherstuff.ambulando.app.AppModel
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.chat.EncodedMentions
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.store.MentionCandidate
import studio.otherstuff.ambulando.core.store.mentionCandidates
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Edits a sent message with the composer's `@` lookahead. The stored body is decoded to visible `@Label` text with its
 * mentions re-selected, so saving re-encodes the same structured mentions instead of silently dropping them; a
 * mention whose text is edited away is dropped, as in the composer.
 */
@Composable
fun MessageEditDialog(model: AppModel, workspaceId: String, body: String, onSave: (EncodedMentions) -> Unit, onDismiss: () -> Unit) {
    val decoded = remember(body) { MentionCodec.decode(body) }
    var input by remember(body) { mutableStateOf(TextFieldValue(decoded.text, TextRange(decoded.text.length))) }
    var mentions by remember(body) { mutableStateOf(decoded.mentions) }
    val active = input.selection.takeIf { it.collapsed }?.let { MentionCodec.activeQuery(input.text, it.end) }
        ?.takeIf { query -> mentions.none { input.text.startsWith(it.visible, query.start) } }
    val results by produceState<List<MentionCandidate>?>(null, workspaceId, active?.query) {
        value = active?.let { model.store.reader.mentionCandidates(workspaceId, it.query, viewerNpub = model.connection?.signerNpub) }
    }
    val amb = Amb.colors
    val pills = remember(mentions, amb) { MentionPills(mentions, SpanStyle(color = amb.primary, background = amb.muted, fontWeight = FontWeight.SemiBold)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit message") }, text = {
        Column {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("chat.edit.field"), visualTransformation = pills)
            if (active != null) results?.let { list ->
                MentionLookahead(list, onPick = { mention ->
                    input = ComposerInsert.mention(input, mention)
                    mentions = mentions.filter { it.npub != mention.npub || it.type != mention.type } + mention
                })
            }
        }
    }, confirmButton = {
        TextButton(enabled = input.text.isNotBlank(), onClick = { onSave(MentionCodec.encode(input.text.trim(), mentions)) }, modifier = Modifier.testTag("chat.edit.save")) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
