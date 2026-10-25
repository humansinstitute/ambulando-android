package studio.otherstuff.ambulando.app.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.PersonAvatar
import studio.otherstuff.ambulando.app.ui.onboarding.shortNpub
import studio.otherstuff.ambulando.app.ui.rich.RichActions
import studio.otherstuff.ambulando.app.ui.rich.RichBlocks
import studio.otherstuff.ambulando.app.ui.rich.RichStyle
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.Notice
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.core.context.ScopeOrdering
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.docs.DocumentRender
import studio.otherstuff.ambulando.core.profiles.PeopleDirectory
import studio.otherstuff.ambulando.core.profiles.PersonDisplay
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteState
import studio.otherstuff.ambulando.core.store.channels
import studio.otherstuff.ambulando.core.store.scopes
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Shared pieces of the Tasks, Docs and Files sections (iOS WorkComponents.swift).

/** What the screens call the device in user-facing copy ("on this device"). */
const val DEVICE_NAME = "device"

/** Scope filter shared by Tasks, Docs and Files (nil = every scope in the workspace). */
@Immutable
data class ScopeChoice(val scopes: List<ScopeRecord> = emptyList(), val channels: List<ChannelRecord> = emptyList()) {
    fun channelName(id: String?): String? = channels.firstOrNull { it.id == id }?.name
    fun scopeName(id: String?): String? = scopes.firstOrNull { it.id == id }?.name
    fun scopeOfChannel(id: String): String? = channels.firstOrNull { it.id == id }?.scopeId

    /**
     * Channels a new record can be written to in `context`: the context's channel, its scope's channels, or (Home) all.
     * Tower lists only channels this member can read; it still decides write access and rejects a write without it.
     * Channels whose access was removed are never offered.
     */
    fun channels(context: WorkContext): List<ChannelRecord> {
        val writable = channels.filter { !it.accessRemoved }
        val scopeId = context.scopeId ?: return writable
        context.channelId?.let { id -> return writable.filter { it.id == id } }
        return writable.filter { it.scopeId == scopeId }
    }
}

/** App-state key of the member's personal scope order (iOS `WorkspaceSync.scopeOrderKey`), written by chat sync. */
fun scopeOrderKey(workspaceId: String): String = "scope-order:$workspaceId"

suspend fun scopeChoice(store: LocalStore, workspaceId: String): ScopeChoice {
    val scopes = store.reader.scopes(workspaceId)
    val channels = store.reader.channels(workspaceId)
    val order = ScopeOrdering.decode(store.reader.state(scopeOrderKey(workspaceId)))
    return ScopeChoice(ScopeOrdering.apply(scopes, order) { it.id }, channels)
}

/** A name for an npub from the directory names, "You" for the viewer without a name, a short npub otherwise. */
fun displayName(npub: String?, names: Map<String, String>, me: String?): String {
    if (npub == null) return "Unknown"
    if (npub == me) return names[npub] ?: "You"
    return names[npub] ?: shortNpub(npub)
}

/**
 * A person for a comment or row: the directory's display (avatar, best name), with the iOS `displayName` rules on top
 * (a name from `names` when the directory has none; "You" for the viewer without one; "Unknown" without an npub).
 */
fun personFor(people: PeopleDirectory?, npub: String?, me: String?, names: Map<String, String>): PersonDisplay {
    if (npub == null) return PersonDisplay("", "Unknown", null, PersonDisplay.NameSource.NPUB)
    val base = people?.person(npub) ?: PersonDisplay.unknown(npub)
    if (base.nameSource != PersonDisplay.NameSource.NPUB) return base
    return base.copy(name = displayName(npub, names, me))
}

/** Time labels for work records (iOS `ChatTime.label`): the time today, day, month and time otherwise. */
object WorkTime {
    fun label(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
        val local = instant.atZone(zone)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(local)
        if (local.toLocalDate() == today) return time
        return DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(local) + ", " + time
    }
}

/** Byte count for display, decimal units as the iOS file-style formatter uses. */
fun byteLabel(count: Long?): String {
    if (count == null) return ""
    if (count < 1000) return if (count == 1L) "1 byte" else "$count bytes"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = count.toDouble()
    var unit = -1
    while (value >= 1000 && unit < units.size - 1) { value /= 1000; unit++ }
    val text = if (value >= 100 || unit == 0) String.format(Locale.getDefault(), "%.0f", value) else String.format(Locale.getDefault(), "%.1f", value)
    return "$text ${units[unit]}"
}

/** Retry, apply-anyway and discard for a queued write. The integrator implements these on the write queue. */
interface WriteActions {
    /** iOS `WriteSender.retry(id, force: false)`: send a failed or retrying write again. */
    fun retryWrite(item: WriteItem)
    /** iOS `WriteSender.retry(id, force: true)` ("Apply mine"): resend a conflicted change over the newer version. */
    fun applyMine(item: WriteItem)
    /** iOS `store.writer.discardWrite(id)`: drop the queued change. */
    fun discardWrite(item: WriteItem)
}

/** State of a queued write, shown beside the thing it changes. */
@Composable
fun WriteStatusLine(item: WriteItem, label: String, actions: WriteActions, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    val unlocked = LocalAppServices.current.unlocked
    val text = when (item.state) {
        WriteState.QUEUED, WriteState.SENDING -> if (unlocked) "$label: sending…" else "$label: waits for unlock"
        WriteState.RETRYING -> "$label: ${item.lastError ?: "will retry"}"
        WriteState.FAILED -> "$label not saved. ${item.lastError ?: ""}".trimEnd()
        WriteState.CONFLICT -> item.lastError ?: "Changed elsewhere."
    }
    val icon: ImageVector = when (item.state) {
        WriteState.QUEUED, WriteState.SENDING -> Icons.Outlined.ArrowCircleUp
        WriteState.RETRYING -> Icons.Outlined.Refresh
        WriteState.FAILED -> Icons.Outlined.ErrorOutline
        WriteState.CONFLICT -> Icons.Outlined.CallSplit
    }
    val tone = when (item.state) {
        WriteState.QUEUED, WriteState.SENDING -> amb.mutedForeground
        WriteState.RETRYING, WriteState.CONFLICT -> amb.warning
        WriteState.FAILED -> amb.destructive
    }
    Row(modifier.fillMaxWidth().testTag("write.status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(14.dp))
        Text(text, color = tone, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        when (item.state) {
            WriteState.FAILED, WriteState.RETRYING -> {
                SmallAction("Retry", "write.retry") { actions.retryWrite(item) }
                if (item.state == WriteState.FAILED) SmallAction("Discard", "write.discard", destructive = true) { actions.discardWrite(item) }
            }
            WriteState.CONFLICT -> {
                SmallAction("Apply mine", "write.force") { actions.applyMine(item) }
                SmallAction("Discard", "write.discard", destructive = true) { actions.discardWrite(item) }
            }
            else -> Unit
        }
    }
}

@Composable
private fun SmallAction(text: String, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val amb = Amb.colors
    Text(
        text, color = if (destructive) amb.destructive else amb.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp).testTag(tag),
    )
}

/** Empty list state: title, icon and a detail line, centred. */
@Composable
fun EmptyNote(title: String, icon: ImageVector, detail: String, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = amb.mutedForeground, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = amb.mutedForeground)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, textAlign = TextAlign.Center)
    }
}

/** A notice padded like the iOS `SyncNote`. */
@Composable
fun SyncNote(text: String, tone: NoticeTone = NoticeTone.INFO) {
    Notice(tone, text, Modifier.padding(vertical = 6.dp))
}

/** Filter and action row under the top bar. */
@Composable
fun ListHeaderBar(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Amb.colors.background).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** The muted note on a record whose access was removed (kept readable, not editable). */
@Composable
fun AccessRemovedNote(modifier: Modifier = Modifier) {
    Text("No longer available to you", style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground,
        modifier = modifier.testTag("access.removed"))
}

/** The sample-data label shown on record screens (iOS `fixtureLabel`). */
@Composable
fun SampleLabel() {
    if (!LocalAppServices.current.sample) return
    Text("Sample data on this $DEVICE_NAME. Not connected to a Tower.", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        color = Amb.colors.mutedForeground, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("fixture.banner"))
}

/**
 * Text entry for record content (task descriptions, comments, document text). On phones it is plain system input, as
 * on iOS; pasted files go through chat's composer, not these fields.
 */
@Composable
fun ContentTextEntry(
    text: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    minLines: Int = 1, maxLines: Int = 5, enabled: Boolean = true,
) {
    val amb = Amb.colors
    BasicTextField(
        value = text, onValueChange = onChange, enabled = enabled, minLines = minLines, maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = amb.foreground), cursorBrush = SolidColor(amb.primary),
        modifier = modifier.semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = amb.mutedForeground)
                inner()
            }
        },
    )
}

/** A comment: avatar, name, time, resolved mark and its Markdown body. */
@Composable
fun CommentRow(comment: CommentRecord, person: PersonDisplay, actions: RichActions, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    Row(modifier.fillMaxWidth().testTag("comment"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PersonAvatar(person, 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(WorkTime.label(comment.createdAt), style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                if (comment.status == "resolved") Text("Resolved", style = MaterialTheme.typography.labelSmall, color = amb.success)
            }
            RichBlocks(DocumentRender.markdownBlocks(comment.body), style = RichStyle.CHAT, muted = comment.accessRemoved, actions = actions)
            if (comment.accessRemoved) AccessRemovedNote()
        }
    }
}

/** Rich actions bound to the app services (mentions, links). */
@Composable
fun workRichActions(): RichActions {
    val services = LocalAppServices.current
    return remember(services) { RichActions(openMention = services.openMention, openUrl = services.openUrl) }
}

/** A labelled form row whose value is chosen from a menu (iOS `Picker` in a `Form`). */
@Composable
fun <T> PickerRow(label: String, value: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit, tag: String? = null, enabled: Boolean = true) {
    val amb = Amb.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Amb.touchTarget).clickable(enabled = enabled) { open = true }
                .then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = amb.foreground, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(value, color = amb.mutedForeground, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, option) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
}

/** A white form group on the recessed surface (iOS grouped `Form` section). */
@Composable
fun FormGroup(header: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    val amb = Amb.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) Text(header, style = MaterialTheme.typography.labelMedium, color = amb.mutedForeground, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().background(amb.card, RoundedCornerShape(Amb.radiusPanel))) { content() }
        if (footer != null) Text(footer, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp))
    }
}

@Composable
fun FormDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Amb.colors.border)

/**
 * A full-screen sheet with Cancel and a confirmation action in its bar (iOS `.sheet` with a `NavigationStack` and
 * cancellation / confirmation toolbar items).
 */
@Composable
fun WorkSheet(
    title: String, onDismiss: () -> Unit, confirmLabel: String? = null, confirmEnabled: Boolean = true, confirmTag: String? = null,
    onConfirm: () -> Unit = {}, content: @Composable () -> Unit,
) {
    val amb = Amb.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(amb.canvas)) {
            Row(Modifier.fillMaxWidth().background(amb.background).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.width(96.dp).testTag("sheet.cancel")) { Text("Cancel") }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                    if (confirmLabel != null) {
                        TextButton(onClick = onConfirm, enabled = confirmEnabled, modifier = if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier) {
                            Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = amb.border)
            content()
        }
    }
}
