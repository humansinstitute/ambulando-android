package studio.otherstuff.ambulando.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.profiles.PersonDisplay

/** The widest a conversation or Deck card list gets: wider windows centre it (iOS `tabletReadingWidth`). */
val TabletReadingWidth: Dp = 860.dp

/** Caps a column at [TabletReadingWidth]; narrower windows are unchanged. */
fun Modifier.readingWidth(): Modifier = widthIn(max = TabletReadingWidth).fillMaxWidth()

/** Where a message's text starts: avatar (34dp), its gap (10dp) and the row's side padding (16dp). Reactions and replies line up under it. */
val MessageTextInset: Dp = 60.dp

/**
 * One conversation in the message list (iOS `ConversationCard`, the web's `.channel-thread-card`): a bordered panel on the page
 * surface with a gap to the next. The border, not a line under every message, separates conversations.
 */
@Composable
fun ConversationCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val amb = Amb.colors
    val shape = RoundedCornerShape(Amb.radiusPanel)
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
        Box(modifier.fillMaxWidth().background(amb.card, shape).border(1.dp, amb.border, shape).padding(vertical = 4.dp)) { content() }
    }
}

/**
 * A message's avatar, author and time, then its content (iOS `MessageRow`). [trailing] is reserved at the end of the author line for
 * the card's controls, which overlay that corner.
 */
@Composable
fun MessageRowLayout(person: PersonDisplay, time: String, trailingReserve: Dp = 56.dp, avatar: @Composable () -> Unit, content: @Composable () -> Unit) {
    val amb = Amb.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        avatar()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // A name and its time share a line; at large text sizes the time wraps below instead of cutting the name short.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(end = trailingReserve), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Bottom) {
                Text(person.name, fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("message.author"))
                Text(time, fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1)
            }
            content()
        }
    }
}

/** A channel post's thread entry under its text: a rule, the reply count in primary and when it last moved, a chevron. */
@Composable
fun RepliesBar(count: Int, lastReply: String?, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    Column(modifier.fillMaxWidth().padding(start = MessageTextInset, end = 16.dp, bottom = 2.dp)) {
        HorizontalDivider(thickness = 1.dp, color = amb.border)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(16.dp), tint = amb.primary)
            Text(if (count == 1) "1 reply" else "$count replies", fontSize = AmbType.caption, fontWeight = FontWeight.Bold, color = amb.primary)
            lastReply?.let { Text("· last ${ChatTime.label(it)}", fontSize = AmbType.caption, color = amb.mutedForeground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) } ?: Box(Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = amb.mutedForeground)
        }
    }
}

/** A day break in the list (iOS `DaySeparator`): a rule, the day in caption semibold, a rule. */
@Composable
fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { heading() }.testTag("chat.day"),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = amb.border)
        Text(label, fontSize = AmbType.caption, fontWeight = FontWeight.SemiBold, color = amb.mutedForeground)
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = amb.border)
    }
}
