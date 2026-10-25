package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbColors
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.NavScope
import studio.otherstuff.ambulando.core.context.SidebarGroup
import studio.otherstuff.ambulando.core.context.WorkContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The sidebar's navigation as Deck, Agents, Context and the management sheets read it (iOS `NavigationSnapshot`): the
 * groups in the member's personal order, resolved channel labels (DM names, "Note to self") and the shared context.
 * The app model builds it from the store, as it does for the sidebar.
 */
@Immutable
data class WorkNavigation(
    val groups: List<SidebarGroup> = emptyList(),
    /** Channel id → display label. */
    val labels: Map<String, String> = emptyMap(),
    val context: WorkContext = WorkContext.HOME,
) {
    fun scope(id: String?): NavScope? = id?.let { s -> groups.firstOrNull { it.id == s }?.scope }
    fun channel(id: String?): NavChannel? = id?.let { c -> groups.firstNotNullOfOrNull { g -> g.channels.firstOrNull { it.id == c } } }
    fun label(channel: NavChannel): String = labels[channel.id] ?: channel.name

    /** Channels of a context: Home → every channel; a scope → its channels; a channel → that channel. */
    fun channels(context: WorkContext = this.context): List<NavChannel> {
        val scopeId = context.scopeId ?: return groups.flatMap { it.channels }
        context.channelId?.let { id -> return groups.flatMap { it.channels }.filter { it.id == id } }
        return groups.firstOrNull { it.id == scopeId }?.channels ?: emptyList()
    }

    /** Header label of the current context (Deck's Hello card). */
    fun contextLabel(): String {
        val scope = scope(context.scopeId) ?: return "All workspace activity"
        return channel(context.channelId)?.let { "${scope.name} / ${label(it)}" } ?: scope.name
    }
}

/** Progress or failure of one sync job, as the app model reports it for a surface (iOS `isSyncing` / `syncError`). */
@Immutable
data class SurfaceStatus(val syncing: Boolean = false, val error: String? = null) {
    companion object {
        val IDLE = SurfaceStatus()
    }
}

/**
 * Looks up the sync state of a job key (see [SurfaceKeys]). The app model backs it with snapshot state, so a screen that
 * reads it recomposes when the job starts, finishes or fails.
 */
fun interface SurfaceStatusLookup {
    fun status(key: String): SurfaceStatus

    companion object {
        val NONE = SurfaceStatusLookup { SurfaceStatus.IDLE }
    }
}

/** Sync job keys the surfaces show progress and errors for (the iOS `WorkspaceSync` keys, same strings). */
object SurfaceKeys {
    fun inbox(workspaceId: String) = "inbox:$workspaceId"
    fun canvases(workspaceId: String) = "canvases:$workspaceId"
    fun agents(workspaceId: String) = "agents:$workspaceId"
    fun toolbox(workspaceId: String) = "toolbox:$workspaceId"
    fun contextTree(workspaceId: String, scopeId: String) = "context:$workspaceId:$scopeId"
    fun contextReferences(workspaceId: String, componentId: String) = "context-refs:$workspaceId:$componentId"
    fun personalAgents(connectionId: String, workspaceId: String) = "personal-agents:$connectionId:$workspaceId"
}

/** Tower timestamps as list rows show them (iOS `ChatTime.label`): the time today, otherwise day, month and time. */
object DeckTime {
    private val dayMonthTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

    fun label(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
        val local = instant.atZone(zone)
        return if (local.toLocalDate() == today) local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) else local.format(dayMonthTime)
    }
}

/** A Deck card row: card fill, panel radius, an orange edge while unread (iOS `DeckRowStyle`). */
fun Modifier.deckRow(colors: AmbColors, unread: Boolean = false): Modifier = this
    .fillMaxWidth()
    .background(colors.card, RoundedCornerShape(Amb.radiusPanel))
    .border(
        BorderStroke(if (unread) 1.2.dp else 0.8.dp, if (unread) Amb.brandOrange.copy(alpha = 0.7f) else colors.border),
        RoundedCornerShape(Amb.radiusPanel),
    )
    .padding(horizontal = 12.dp, vertical = 10.dp)

/** Muted footnote inside a card or list (iOS `DeckNote`). */
@Composable
fun DeckNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = Amb.colors.mutedForeground,
    )
}

/** Centered empty or unavailable state (iOS `EmptyNote`). */
@Composable
fun EmptyNote(title: String, icon: ImageVector, detail: String, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    Column(
        modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = amb.mutedForeground, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = amb.mutedForeground, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, textAlign = TextAlign.Center)
    }
}

/** The muted note on a record this identity can no longer see (Android keeps such records readable). */
@Composable
fun AccessRemovedNote(modifier: Modifier = Modifier) {
    val amb = Amb.colors
    androidx.compose.foundation.layout.Row(
        modifier.testTag("access.removed"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = amb.mutedForeground, modifier = Modifier.size(12.dp))
        Text("No longer available to you", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
    }
}

/** The sample-data banner (iOS `FixtureBanner`), shown only when the app runs on local sample data. */
@Composable
fun SampleDataLabel(modifier: Modifier = Modifier) {
    if (!LocalAppServices.current.sample) return
    val amb = Amb.colors
    Text(
        "Sample data on this phone. Not connected to a Tower.",
        modifier.fillMaxWidth().background(amb.warningBackground).padding(horizontal = 12.dp, vertical = 6.dp).testTag("fixture.banner"),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = amb.warning,
    )
}

/** Section header of a grouped list. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Amb.colors.mutedForeground,
    )
}

/** Section footer of a grouped list. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Amb.colors.mutedForeground) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = color)
}
