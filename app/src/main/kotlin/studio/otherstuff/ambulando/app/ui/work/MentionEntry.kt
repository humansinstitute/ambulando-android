package studio.otherstuff.ambulando.app.ui.work

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import studio.otherstuff.ambulando.app.ui.chat.ComposerInsert
import studio.otherstuff.ambulando.app.ui.chat.MentionLookahead
import studio.otherstuff.ambulando.app.ui.chat.MentionPills
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.store.MentionCandidate

/**
 * A comment text field with the chat composer's `@` lookahead (task and document comments, as on iPhone and Mac). Only
 * the `@query` at the caret is completed; the picked mention is drawn as a pill and encoded to its canonical token by
 * [MentionCodec.encode] when the comment is sent. [lookup] answers from the local workspace directory, never the network.
 */
@Composable
fun MentionTextEntry(
    text: TextFieldValue, onValue: (TextFieldValue) -> Unit, mentions: List<Mention>, onMentions: (List<Mention>) -> Unit,
    lookup: suspend (String) -> List<MentionCandidate>, placeholder: String, modifier: Modifier = Modifier, fieldModifier: Modifier = Modifier,
    minLines: Int = 1, maxLines: Int = 5, enabled: Boolean = true,
) {
    val amb = Amb.colors
    val active = text.selection.takeIf { it.collapsed }?.let { MentionCodec.activeQuery(text.text, it.end) }
        ?.takeIf { query -> mentions.none { text.text.startsWith(it.visible, query.start) } }
    val results by produceState<List<MentionCandidate>?>(null, active?.query) { value = active?.let { lookup(it.query) } }
    val pills = remember(mentions, amb) { MentionPills(mentions, SpanStyle(color = amb.primary, background = amb.muted, fontWeight = FontWeight.SemiBold)) }
    // [modifier] places the whole entry (a weight in a row, for instance); [fieldModifier] dresses the text field itself.
    Column(modifier) {
        if (enabled && active != null) results?.let { list ->
            MentionLookahead(list, onPick = { mention ->
                onValue(ComposerInsert.mention(text, mention))
                onMentions(mentions.filter { it.npub != mention.npub || it.type != mention.type } + mention)
            })
        }
        BasicTextField(
            value = text, onValueChange = onValue, enabled = enabled, minLines = minLines, maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = amb.foreground), cursorBrush = SolidColor(amb.primary),
            visualTransformation = pills, modifier = fieldModifier.semantics { contentDescription = placeholder },
            decorationBox = { inner -> Box { if (text.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = amb.mutedForeground); inner() } },
        )
    }
}

/** A comment being written: its text (with caret) and the mentions picked in it. Survives rotation and process recreation. */
@Stable
class CommentDraft {
    var text by androidx.compose.runtime.mutableStateOf(TextFieldValue(""))
    var mentions by androidx.compose.runtime.mutableStateOf(emptyList<Mention>())
    val isBlank: Boolean get() = text.text.isBlank()

    /** The wire body and structured mentions; a mention whose visible text was edited away is dropped. */
    fun encoded() = MentionCodec.encode(text.text.trim(), mentions)

    fun clear() { text = TextFieldValue(""); mentions = emptyList() }
}

@Composable
fun rememberCommentDraft(vararg keys: Any?): CommentDraft = androidx.compose.runtime.saveable.rememberSaveable(
    *keys,
    saver = androidx.compose.runtime.saveable.listSaver<CommentDraft, Any>(
        save = { draft -> listOf(draft.text.text, draft.text.selection.start, draft.text.selection.end) + draft.mentions.flatMap { listOf(it.type.raw, it.npub, it.label) } },
        restore = { saved ->
            CommentDraft().also { draft ->
                draft.text = TextFieldValue(saved[0] as String, androidx.compose.ui.text.TextRange(saved[1] as Int, saved[2] as Int))
                draft.mentions = saved.drop(3).chunked(3).mapNotNull { (type, npub, label) ->
                    studio.otherstuff.ambulando.core.chat.MentionType.fromRaw(type as String)?.let { Mention(it, npub as String, label as String) }
                }
            }
        },
    ),
) { CommentDraft() }
