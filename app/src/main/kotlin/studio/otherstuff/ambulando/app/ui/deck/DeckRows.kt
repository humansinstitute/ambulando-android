package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.chat.ChatTime
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.deck.DeckActivity

/** A quiet colour per record type, so chats, tasks, documents and files can be told apart at a glance (iOS `DeckInbox.tint`). */
@Composable internal fun deckKindTint(kind: DeckActivity.Kind): Color = Amb.colors.let {
    when (kind) { DeckActivity.Kind.CHAT -> it.info; DeckActivity.Kind.TASK -> it.warning; DeckActivity.Kind.DOCUMENT -> it.success; DeckActivity.Kind.FILE -> it.mutedForeground }
}

internal fun deckKindIcon(kind: DeckActivity.Kind): ImageVector = when (kind) {
    DeckActivity.Kind.CHAT -> Icons.AutoMirrored.Outlined.Chat
    DeckActivity.Kind.TASK -> Icons.Outlined.Checklist
    DeckActivity.Kind.DOCUMENT -> Icons.Outlined.Description
    DeckActivity.Kind.FILE -> Icons.Outlined.AttachFile
}

internal fun deckKindToggleLabel(kind: DeckActivity.Kind): String = when (kind) {
    DeckActivity.Kind.CHAT -> "Show chats"; DeckActivity.Kind.TASK -> "Show tasks"
    DeckActivity.Kind.DOCUMENT -> "Show documents"; DeckActivity.Kind.FILE -> "Show files"
}

/** The Inbox kicker: type, then the channel or task state (iOS `kicker`). */
internal fun deckKicker(item: DeckActivity.Item, channelLabels: Map<String, String>): String {
    val channel = item.channelId?.let { channelLabels[it] }?.let { "#$it" }
    return when (item.kind) {
        DeckActivity.Kind.CHAT -> listOfNotNull("Chat", channel).joinToString(" · ")
        DeckActivity.Kind.TASK -> "Task · ${item.subtitle}"
        DeckActivity.Kind.DOCUMENT -> "Document"
        DeckActivity.Kind.FILE -> listOfNotNull("File", channel).joinToString(" · ")
    }
}

/** A card row tinted by its kind at 6%, with an orange edge while unread (iOS `DeckRowStyle`). */
@Composable internal fun Modifier.tintedDeckRow(tint: Color?, unread: Boolean): Modifier {
    val amb = Amb.colors
    val shape = RoundedCornerShape(Amb.radiusPanel)
    return fillMaxWidth().background(amb.card, shape).let { if (tint != null) it.background(tint.copy(alpha = 0.06f), shape) else it }
        .border(BorderStroke(if (unread) 1.2.dp else 0.8.dp, if (unread) Amb.brandOrange.copy(alpha = 0.7f) else amb.border), shape)
        .padding(horizontal = 12.dp, vertical = 10.dp)
}

/** One Inbox row (iOS `DeckInboxCard.row`): kind tile, kicker and time, title, one-line detail, and the row's own actions. */
@Composable internal fun DeckInboxRow(
    item: DeckActivity.Item, channelLabels: Map<String, String>, removed: Boolean, canMarkRead: Boolean,
    pin: (@Composable () -> Unit)?, onMarkDone: (() -> Unit)?, onMarkRead: (() -> Unit)?, onOpen: () -> Unit,
) {
    val amb = Amb.colors
    val tint = deckKindTint(item.kind)
    val kicker = deckKicker(item, channelLabels)
    Row(Modifier.tintedDeckRow(tint, item.isUnread), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).testTag("deck.inbox.item").clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = "$kicker: ${item.title}" + if (item.isUnread) ", unread" else "" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                Icon(deckKindIcon(item.kind), null, Modifier.size(18.dp), tint = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(kicker, Modifier.weight(1f), fontSize = AmbType.caption, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ChatTime.label(item.activityAt), fontSize = AmbType.caption2, color = amb.mutedForeground, maxLines = 1)
                }
                Text(item.title, fontSize = AmbType.subheadline, fontWeight = if (item.isUnread) FontWeight.SemiBold else FontWeight.Normal, color = amb.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (item.kind == DeckActivity.Kind.CHAT) item.subtitle else item.reason, fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.isWorking) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(12.dp), tint = amb.primary)
                    Text("Agent working", fontSize = AmbType.caption2, color = amb.primary)
                }
                if (removed) AccessRemovedNote()
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            pin?.invoke()
            if (onMarkDone != null) IconButton(onClick = onMarkDone, modifier = Modifier.size(Amb.touchTarget).testTag("deck.inbox.done")) {
                Icon(Icons.Outlined.CheckBox, "Mark done", Modifier.size(20.dp), tint = amb.success)
            }
            if (onMarkRead != null) IconButton(onClick = onMarkRead, modifier = Modifier.size(Amb.touchTarget).testTag("deck.inbox.read")) {
                Icon(Icons.Outlined.Drafts, "Mark read", Modifier.size(20.dp), tint = amb.info)
            }
        }
    }
}

/** The four type toggles (iOS `typeToggles`): a 38x36 icon in a bordered control inside a 44dp touch target. */
@Composable internal fun DeckTypeToggle(kind: DeckActivity.Kind, on: Boolean, onToggle: () -> Unit) {
    val amb = Amb.colors
    val shape = RoundedCornerShape(Amb.radiusControl)
    Box(
        Modifier.defaultMinSize(Amb.touchTarget, Amb.touchTarget).clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = deckKindToggleLabel(kind); stateDescription = if (on) "Shown" else "Hidden"; selected = on }
            .testTag("deck.inbox.type.${kind.wire}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp, 36.dp).background(if (on) amb.selectedFill else Color.Transparent, shape).border(0.8.dp, amb.border, shape), contentAlignment = Alignment.Center) {
            Icon(deckKindIcon(kind), null, Modifier.size(20.dp), tint = if (on) amb.selectedForeground else amb.mutedForeground)
        }
    }
}

/** The bordered Search field beside the toggles (iOS `searchField`). */
@Composable internal fun DeckSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    val shape = RoundedCornerShape(Amb.radiusControl)
    BasicTextField(
        value, onChange, singleLine = true, textStyle = TextStyle(color = amb.foreground, fontSize = AmbType.body), cursorBrush = SolidColor(amb.primary),
        modifier = modifier.heightIn(min = Amb.touchTarget).semantics { contentDescription = "Search Inbox" }.testTag("deck.inbox.search"),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(amb.card, shape).border(0.8.dp, amb.border, shape).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = amb.mutedForeground)
                Box(Modifier.weight(1f)) { if (value.isEmpty()) Text("Search", fontSize = AmbType.body, color = amb.mutedForeground); inner() }
            }
        },
    )
}

/** A Recent, Pinned or Canvas row's leading kind glyph and text: title (subheadline semibold), caption (iOS `DeckRecentCard`). */
@Composable internal fun DeckRowText(title: String, caption: String?, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        caption?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** An app as a card with its logo, title and description (iOS `AppLauncherCard`). */
@Composable internal fun AppLauncherCard(title: String, subtitle: String, logo: @Composable () -> Unit, onOpen: () -> Unit) {
    val amb = Amb.colors
    val shape = RoundedCornerShape(Amb.radiusPanel)
    Row(
        Modifier.fillMaxWidth().background(amb.card, shape).border(1.dp, amb.border, shape).clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 9.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        logo()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = AmbType.subheadline, fontWeight = FontWeight.Bold, color = amb.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = amb.mutedForeground)
    }
}
