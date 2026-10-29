package studio.otherstuff.ambulando.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.chat.MentionType
import studio.otherstuff.ambulando.core.store.MentionCandidate

/** Composer text edits made by the app (not the keyboard). */
object ComposerInsert {
    /**
     * Inserts upload placeholders at the caret (replacing a selection), each on its own line, so the image or file lands
     * where the person is writing and its Markdown stays editable and movable. The caret goes after the insertion.
     */
    fun placeholders(value: TextFieldValue, tokens: List<String>): TextFieldValue {
        if (tokens.isEmpty()) return value
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(0, text.length)
        val before = text.substring(0, start)
        val after = text.substring(end)
        val lead = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
        val trail = if (after.isEmpty() || after.startsWith("\n")) "" else "\n"
        val inserted = lead + tokens.joinToString("\n") + trail
        return TextFieldValue(before + inserted + after, TextRange(before.length + inserted.length))
    }

    /** Starts an `@` lookahead at the caret (a space first when it would otherwise join a word). */
    fun at(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(0, text.length)
        val before = text.substring(0, start)
        val insert = if (before.isEmpty() || before.last().isWhitespace() || before.last() == '(') "@" else " @"
        return TextFieldValue(before + insert + text.substring(end), TextRange(start + insert.length))
    }

    /** Completes the `@query` at the caret with a chosen mention. */
    fun mention(value: TextFieldValue, mention: Mention): TextFieldValue {
        val (text, caret) = MentionCodec.complete(value.text, value.selection.end, mention)
        return TextFieldValue(text, TextRange(caret))
    }
}

/** The short type badge of a lookahead row (the web popover's). */
fun MentionType.badge(): String = when (this) {
    MentionType.PERSON, MentionType.AGENT -> "@"
    MentionType.DOC -> "DOC"
    MentionType.TASK -> "TASK"
    MentionType.CHAT -> "CHAT"
    MentionType.CHANNEL -> "CHAN"
    MentionType.SCOPE -> "SCOPE"
}

/**
 * The selected mentions' visible `@Label` text drawn as pills in the composer. The text itself is unchanged (offsets map
 * one to one), so editing and the caret behave as in plain text; a pill whose text is edited simply stops being one.
 */
class MentionPills(private val mentions: List<Mention>, private val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (mentions.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        for (mention in mentions.distinctBy { it.visible }) {
            MentionCodec.visibleRanges(mention, text.text).forEach { range -> builder.addStyle(style, range.first, range.last + 1) }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is MentionPills && other.mentions == mentions && other.style == style
    override fun hashCode(): Int = mentions.hashCode() * 31 + style.hashCode()
}

/** The `@` lookahead list shown above the composer while a query is being typed. */
@Composable
fun MentionLookahead(candidates: List<MentionCandidate>, onPick: (Mention) -> Unit, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    Column(modifier.fillMaxWidth().background(amb.card).testTag("chat.mentions")) {
        HorizontalDivider(thickness = 0.5.dp, color = amb.border)
        if (candidates.isEmpty()) {
            Text("No matches", style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("chat.mentions.empty"))
            return@Column
        }
        LazyColumn(Modifier.heightIn(max = 232.dp)) {
            items(candidates, key = { it.mention.type.raw + ":" + it.mention.id }) { candidate ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(candidate.mention) }.padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("chat.mention.${candidate.mention.type.raw}.${candidate.mention.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.widthIn(min = 40.dp).background(amb.muted, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                        Text(candidate.mention.type.badge(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = amb.primary, maxLines = 1)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(candidate.mention.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(candidate.sublabel, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * The composer's controls stacked at its left (attach above mention) so the text field takes the width. Attach offers
 * the photo/file picker and pasting an attachment from the clipboard.
 */
@Composable
fun ComposerControls(enabled: Boolean, onPick: () -> Unit, onPaste: () -> Unit, onMention: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(36.dp).testTag("chat.attach")) {
                Icon(Icons.Outlined.AttachFile, "Attach image or file", modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Photo or file…") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                    onClick = { menu = false; onPick() }, modifier = Modifier.testTag("chat.attach.pick"))
                DropdownMenuItem(text = { Text("Paste attachment") }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                    onClick = { menu = false; onPaste() }, modifier = Modifier.testTag("chat.attach.paste"))
            }
        }
        IconButton(onClick = onMention, enabled = enabled, modifier = Modifier.size(36.dp).testTag("chat.mention")) {
            Icon(Icons.Outlined.AlternateEmail, "Mention", modifier = Modifier.size(20.dp))
        }
    }
}

/** The round send button at the composer's right. */
@Composable
fun SendButton(enabled: Boolean, onSend: () -> Unit) {
    val amb = Amb.colors
    IconButton(
        onClick = onSend, enabled = enabled,
        modifier = Modifier.padding(start = 4.dp).size(40.dp).background(if (enabled) amb.primary else amb.muted, CircleShape).testTag("chat.send"),
    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (enabled) amb.primaryForeground else amb.mutedForeground, modifier = Modifier.size(18.dp)) }
}
