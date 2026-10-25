package studio.otherstuff.ambulando.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.NavScope
import studio.otherstuff.ambulando.core.context.NavigationLoadState
import studio.otherstuff.ambulando.core.context.SidebarGroup
import studio.otherstuff.ambulando.core.context.SidebarNavigation
import studio.otherstuff.ambulando.core.context.WorkContext

/** Everything the sidebar draws, read from the store by the app model. */
@Immutable
data class SidebarState(
    val workspaceLabel: String,
    val selectedWorkspaceId: String?,
    val workspaces: List<Pair<String, String>>,
    val workspacesLoaded: Boolean,
    val groups: List<SidebarGroup>,
    val unread: Set<String>,
    val collapsed: Set<String>,
    val context: WorkContext,
    /** Channel id → display label (DM labels resolved, "Note to self"). */
    val labels: Map<String, String>,
    /** Scope ids where the member may create channels (sol `.sidebar-scope-add-channel`). */
    val canCreateChannelIn: Set<String>,
    val loadState: NavigationLoadState,
    /** The identity exists but cannot be opened right now (device locked), as opposed to locked by the user. */
    val identityUnavailable: Boolean,
)

interface SidebarActions {
    fun chooseHome()
    fun chooseScope(scopeId: String)
    fun chooseChannel(scopeId: String, channelId: String)
    fun toggleCollapsed(scopeId: String)
    fun newChannel(scope: NavScope)
    fun channelSettings(channel: NavChannel)
    fun manageScopes()
    fun selectWorkspace(workspaceId: String)
    fun retry()
    fun close()
}

/** The drawer content: Home, Unread & Active, scopes (collapsible, in the personal order) and the workspace switcher. */
@Composable
fun SidebarContent(state: SidebarState, actions: SidebarActions, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    val context = state.context
    Column(modifier.fillMaxHeight().widthIn(max = 340.dp).background(amb.card).testTag("sidebar")) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .background(if (context.isHome) amb.selectedFill else Color.Transparent, RoundedCornerShape(Amb.radiusPanel))
                        .border(1.dp, amb.border, RoundedCornerShape(Amb.radiusPanel))
                        .clickable { actions.chooseHome() }
                        .semantics { contentDescription = "All workspace activity"; selected = context.isHome }
                        .testTag("sidebar.home")
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WorkspaceAvatar(state.workspaceLabel, 24.dp)
                    Text("Home", fontWeight = if (context.isHome) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (context.isHome) amb.selectedForeground else amb.foreground, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { NavigationStatus(state.loadState, compact = true, identityUnavailable = state.identityUnavailable, onRetry = actions::retry) }
            val unread = SidebarNavigation.unreadChannels(state.groups, state.unread)
            if (unread.isNotEmpty()) {
                item { SidebarHeading("Unread & Active") }
                items(unread, key = { "unread:" + it.id }) { channel ->
                    ChannelRow(channel, state.labels[channel.id] ?: channel.name, unread = true, active = context.channelId == channel.id,
                        tagPrefix = "sidebar.unread", onClick = { actions.chooseChannel(channel.scopeId, channel.id) })
                }
                item { Spacer(Modifier.heightIn(min = 6.dp)) }
            }
            for (group in state.groups) {
                val collapsed = group.id in state.collapsed
                item(key = "scope:" + group.id) {
                    val scopeActive = context.scopeId == group.id && context.channelId == null
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp, 32.dp).clickable { actions.toggleCollapsed(group.id) }
                                .semantics { contentDescription = "${if (collapsed) "Expand" else "Collapse"} ${group.scope.name} channels" }
                                .testTag("sidebar.collapse.${group.scope.name}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (collapsed) Icons.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown, null, tint = amb.mutedForeground, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            group.scope.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold,
                            color = if (scopeActive) amb.primary else amb.mutedForeground,
                            modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                                .background(if (scopeActive) amb.selectedFill else Color.Transparent, RoundedCornerShape(Amb.radiusControl))
                                .clickable { actions.chooseScope(group.id) }
                                .semantics { contentDescription = "Open ${group.scope.name} home"; selected = scopeActive }
                                .testTag("sidebar.scope.${group.scope.name}")
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                        if (group.id in state.canCreateChannelIn) {
                            Box(
                                Modifier.size(30.dp, 32.dp).clickable { actions.newChannel(group.scope) }
                                    .semantics { contentDescription = "Create channel in ${group.scope.name}" }
                                    .testTag("sidebar.addChannel.${group.scope.name}"),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Outlined.Add, null, tint = amb.mutedForeground, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
                if (!collapsed) {
                    if (group.channels.isEmpty()) item(key = "empty:" + group.id) {
                        Text("No channels visible", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground,
                            modifier = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 4.dp))
                    }
                    items(group.channels, key = { "channel:" + it.id }) { channel ->
                        val label = state.labels[channel.id] ?: channel.name
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ChannelRow(channel, label, unread = channel.id in state.unread, active = context.channelId == channel.id,
                                    tagPrefix = "sidebar.channel", onClick = { actions.chooseChannel(channel.scopeId, channel.id) })
                            }
                            Box(
                                Modifier.size(30.dp, 36.dp).clickable { actions.channelSettings(channel) }
                                    .semantics { contentDescription = "Channel settings for $label" }
                                    .testTag("sidebar.channelSettings.$label"),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Outlined.MoreHoriz, null, tint = amb.mutedForeground, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
            if (state.groups.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 36.dp).clickable { actions.manageScopes() }
                        .testTag("sidebar.manageScopes").padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Tune, null, tint = amb.primary, modifier = Modifier.size(18.dp))
                    Text("Manage scopes", color = amb.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        WorkspaceFooter(state, actions)
    }
}

@Composable
private fun SidebarHeading(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold, color = Amb.colors.mutedForeground,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 2.dp).semantics { heading() })
}

/** One channel in the sidebar (sol `.sidebar-scope-channel-row`): active rows are muted fill with bold primary text. */
@Composable
private fun ChannelRow(channel: NavChannel, label: String, unread: Boolean, active: Boolean, tagPrefix: String, onClick: () -> Unit) {
    val amb = Amb.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp)
            .background(if (active) amb.selectedFill else Color.Transparent, RoundedCornerShape(Amb.radiusControl))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label + if (unread) ", unread" else ""; selected = active }
            .testTag("$tagPrefix.$label")
            .padding(start = 28.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (SidebarNavigation.isDirectMessageChannel(channel)) Icon(Icons.Outlined.People, null, tint = amb.mutedForeground, modifier = Modifier.size(14.dp))
        else Text("#", color = amb.mutedForeground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(14.dp))
        Text(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = when { active -> FontWeight.Bold; unread -> FontWeight.SemiBold; else -> FontWeight.Normal },
            color = when { active -> amb.selectedForeground; unread -> amb.foreground; else -> amb.mutedForeground },
            modifier = Modifier.weight(1f),
        )
        if (unread) Box(Modifier.size(7.dp).background(Amb.brandOrange, CircleShape))
    }
}

/** Workspace switcher at the bottom of the sidebar (sol `.sidebar-workspace-footer`). */
@Composable
private fun WorkspaceFooter(state: SidebarState, actions: SidebarActions) {
    val amb = Amb.colors
    var open by remember { mutableStateOf(false) }
    Column {
        HorizontalDivider(thickness = 0.5.dp, color = amb.border)
        Box(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().background(amb.muted, RoundedCornerShape(Amb.radiusControl)).clickable { open = true }
                    .semantics { contentDescription = "Switch workspace, current ${state.workspaceLabel}" }
                    .testTag("sidebar.workspace").padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkspaceAvatar(state.workspaceLabel, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text("Workspace", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                    Text(state.workspaceLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.UnfoldMore, null, tint = amb.mutedForeground, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.workspaces.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { if (id == state.selectedWorkspaceId) Icon(Icons.Outlined.Check, null) },
                        onClick = { open = false; actions.selectWorkspace(id); actions.close() },
                    )
                }
                if (state.workspaces.isEmpty()) DropdownMenuItem(text = { Text(if (state.workspacesLoaded) "No workspaces yet" else "Loading…") }, onClick = { open = false }, enabled = false)
            }
        }
    }
}

/**
 * The sidebar's (and the Chat channel list's) account of scopes and channels when they are not simply listed: loading,
 * a failure with Retry, a locked identity, or a list that may be out of date. An empty list is never shown without one.
 */
@Composable
fun NavigationStatus(state: NavigationLoadState, compact: Boolean, identityUnavailable: Boolean, onRetry: () -> Unit) {
    val amb = Amb.colors
    when (state) {
        is NavigationLoadState.Ready -> state.refreshError?.let {
            StatusMessage(amb.warning, amb.warningBackground, "Couldn't refresh; showing scopes saved on this device. $it", "sidebar.state.stale", true, compact, onRetry)
        }
        NavigationLoadState.Loading, NavigationLoadState.FindingWorkspaces -> Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 0.dp, vertical = 6.dp),
            horizontalArrangement = if (compact) Arrangement.spacedBy(8.dp) else Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(if (state == NavigationLoadState.Loading) "Loading scopes and channels…" else "Finding your workspaces…",
                style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, modifier = Modifier.testTag("sidebar.state.loading"))
        }
        is NavigationLoadState.Failed -> StatusMessage(amb.destructive, amb.destructiveBackground, "Couldn't load scopes and channels. ${state.error}", "sidebar.state.error", true, compact, onRetry)
        is NavigationLoadState.WorkspacesFailed -> StatusMessage(amb.destructive, amb.destructiveBackground, "Couldn't load your workspaces. ${state.error}", "sidebar.state.error", true, compact, onRetry)
        NavigationLoadState.Locked -> StatusMessage(amb.mutedForeground, amb.muted,
            if (identityUnavailable) "Scopes and channels load once this device is unlocked." else "Unlock your identity to load scopes and channels.",
            "sidebar.state.locked", false, compact, onRetry)
        NavigationLoadState.Empty -> StatusMessage(amb.mutedForeground, amb.muted, "No scopes are shared with you in this workspace yet.", "sidebar.state.empty", true, compact, onRetry)
        NavigationLoadState.NoWorkspaces -> StatusMessage(amb.mutedForeground, amb.muted, "This identity isn't a member of any workspace on this Tower yet.", "sidebar.state.empty", true, compact, onRetry)
    }
}

@Composable
private fun StatusMessage(fg: Color, bg: Color, text: String, tag: String, retry: Boolean, compact: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 360.dp)).padding(bottom = if (compact) 6.dp else 0.dp)
            .background(bg, RoundedCornerShape(Amb.radiusControl)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.testTag(tag))
        if (retry) OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("sidebar.retry")) {
            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Retry", style = MaterialTheme.typography.labelMedium)
        }
    }
}
