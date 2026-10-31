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
import androidx.compose.foundation.isSystemInDarkTheme
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
    /** Every enabled workspace, in the saved order, when more than one is enabled (the column then stacks them). */
    val stacked: List<SidebarWorkspace> = emptyList(),
)

/** One enabled workspace in the stacked column; actions carry its [id] (the full-boundary id), never "the current workspace". */
@Immutable
data class SidebarWorkspace(
    val id: String,
    val label: String,
    /** Tower host, shown only where it tells two similarly named workspaces apart. */
    val towerLabel: String,
    val colour: studio.otherstuff.ambulando.core.workspaces.WorkspaceColour,
    val collapsed: Boolean,
    val active: Boolean,
    val accessRemoved: Boolean,
    val groups: List<SidebarGroup>,
    val scopeCollapsed: Set<String>,
    val unread: Set<String>,
    val labels: Map<String, String>,
    val loaded: Boolean,
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
    /** Opens the workspace chooser (enable, pause, colour, add a Tower). */
    fun chooseWorkspaces() {}
    /** Brings workspace [id] in front and optionally opens a scope or channel of it. */
    fun openIn(id: String, scopeId: String?, channelId: String?) {}
    fun toggleWorkspaceCollapsed(id: String) {}
    fun toggleScopeIn(id: String, scopeId: String) {}
    fun moveWorkspace(id: String, offset: Int) {}
    fun pauseWorkspace(id: String) {}
    /** Opens Manage Workspace for exactly this workspace (it is brought to the front first). */
    fun manageWorkspace(id: String) {}
}

/** The drawer content: Home, Unread & Active, scopes (collapsible, in the personal order) and the workspace switcher. */
@Composable
fun SidebarContent(state: SidebarState, actions: SidebarActions, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    val context = state.context
    Column(modifier.fillMaxHeight().widthIn(max = 340.dp).background(amb.card).testTag("sidebar")) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
            if (state.stacked.size > 1) { stackedItems(state, actions); return@LazyColumn }
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
            // Workspace administration (members, groups, access, apps) is reachable even before any scope exists.
            if (state.selectedWorkspaceId != null) item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 36.dp).clickable { actions.manageScopes() }
                        .testTag("sidebar.manageScopes").padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Tune, null, tint = amb.primary, modifier = Modifier.size(18.dp))
                    Text("Manage workspace", color = amb.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
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
private fun ChannelRow(
    channel: NavChannel, label: String, unread: Boolean, active: Boolean, tagPrefix: String, onClick: () -> Unit,
    /** Set on shared rows: the workspace this channel belongs to, kept in the label, description and a quiet suffix. */
    source: SidebarWorkspace? = null,
    dotColour: studio.otherstuff.ambulando.core.workspaces.WorkspaceColour? = null,
) {
    val amb = Amb.colors
    val dark = isSystemInDarkTheme()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp)
            .background(if (active) amb.selectedFill else Color.Transparent, RoundedCornerShape(Amb.radiusControl))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label + (source?.let { ", ${it.label}" + (if (it.towerLabel.isNotEmpty()) ", ${it.towerLabel}" else "") } ?: "") + if (unread) ", unread" else ""; selected = active }
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
            modifier = Modifier.weight(1f, fill = source == null),
        )
        if (source != null) Text(source.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground,
            modifier = Modifier.weight(1f, fill = false))
        val dot = (source?.colour ?: dotColour)?.let { WorkspaceTint.navigation(it, dark) } ?: Amb.brandOrange
        if (unread) Box(Modifier.size(7.dp).background(dot, CircleShape))
    }
}

/** The workspace chooser entry at the bottom of the sidebar (sol `.sidebar-workspace-footer`). */
@Composable
private fun WorkspaceFooter(state: SidebarState, actions: SidebarActions) {
    val amb = Amb.colors
    Column {
        HorizontalDivider(thickness = 0.5.dp, color = amb.border)
        Box(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().background(amb.muted, RoundedCornerShape(Amb.radiusControl)).clickable { actions.chooseWorkspaces() }
                    .semantics { contentDescription = if (state.stacked.size > 1) "Choose workspaces, ${state.stacked.size} enabled" else "Choose workspaces, current ${state.workspaceLabel}" }
                    .testTag("sidebar.workspace").padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkspaceAvatar(state.workspaceLabel, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (state.stacked.size > 1) "Workspaces" else "Workspace", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                    Text(if (state.stacked.size > 1) "${state.stacked.size} enabled" else state.workspaceLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.UnfoldMore, null, tint = amb.mutedForeground, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Several enabled workspaces in one column: shared Unread & Active rows first (each keeps its source workspace in its label
 * and accessibility description), then one collapsible group per workspace. Every tap names its workspace.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.stackedItems(state: SidebarState, actions: SidebarActions) {
    val unreadRows = state.stacked.flatMap { w -> SidebarNavigation.unreadChannels(w.groups, w.unread).map { w to it } }
    if (unreadRows.isNotEmpty()) {
        item { SidebarHeading("Unread & Active") }
        items(unreadRows, key = { (w, c) -> "unread:${w.id}:${c.id}" }) { (w, channel) ->
            ChannelRow(channel, w.labels[channel.id] ?: channel.name, unread = true, active = w.active && state.context.channelId == channel.id,
                tagPrefix = "workspace.unread.${w.label}", source = w, onClick = { actions.openIn(w.id, channel.scopeId, channel.id); actions.close() })
        }
        item { Spacer(Modifier.heightIn(min = 6.dp)) }
    }
    for (w in state.stacked) {
        item(key = "workspace:" + w.id) { WorkspaceGroupHeader(w, actions) }
        if (w.collapsed) continue
        item(key = "workspace-home:" + w.id) {
            val amb = Amb.colors
            val active = w.active && state.context.isHome
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).background(if (active) amb.selectedFill else Color.Transparent, RoundedCornerShape(Amb.radiusControl))
                    .clickable { actions.openIn(w.id, null, null); actions.close() }
                    .semantics { contentDescription = "Home in ${w.label}"; selected = active }
                    .testTag("workspace.home.${w.label}").padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { WorkspaceAvatar(w.label, 20.dp); Text("Home", fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, color = amb.foreground, style = MaterialTheme.typography.bodyMedium) }
        }
        if (w.accessRemoved) item(key = "workspace-lost:" + w.id) {
            Text("No longer available to you. Saved content stays readable; nothing more is sent.", style = MaterialTheme.typography.labelSmall,
                color = Amb.colors.destructive, modifier = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 4.dp).testTag("workspace.lost.${w.label}"))
        }
        if (!w.loaded) item(key = "workspace-loading:" + w.id) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp); Text("Loading saved navigation…", style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground)
            }
        } else if (w.groups.isEmpty()) item(key = "workspace-empty:" + w.id) {
            Text("No scopes visible", style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground, modifier = Modifier.padding(8.dp))
        }
        for (group in w.groups) {
            val collapsed = group.id in w.scopeCollapsed
            item(key = "scope:${w.id}:${group.id}") {
                val amb = Amb.colors
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp, 32.dp).clickable { actions.toggleScopeIn(w.id, group.id) }
                            .semantics { contentDescription = "${if (collapsed) "Expand" else "Collapse"} ${group.scope.name} in ${w.label}" }
                            .testTag("workspace.scopeCollapse.${w.label}.${group.scope.name}"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (collapsed) Icons.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown, null, tint = amb.mutedForeground, modifier = Modifier.size(18.dp)) }
                    Text(group.scope.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold,
                        color = amb.mutedForeground,
                        modifier = Modifier.weight(1f).heightIn(min = 32.dp).clickable { actions.openIn(w.id, group.id, null); actions.close() }
                            .semantics { contentDescription = "Open ${group.scope.name} in ${w.label}" }
                            .testTag("workspace.scope.${w.label}.${group.scope.name}").padding(horizontal = 4.dp, vertical = 8.dp))
                }
            }
            if (!collapsed) items(group.channels, key = { "channel:${w.id}:${it.id}" }) { channel ->
                ChannelRow(channel, w.labels[channel.id] ?: channel.name, unread = channel.id in w.unread, active = w.active && state.context.channelId == channel.id,
                    tagPrefix = "workspace.channel.${w.label}", dotColour = w.colour, onClick = { actions.openIn(w.id, channel.scopeId, channel.id); actions.close() })
            }
        }
    }
}

@Composable
private fun WorkspaceGroupHeader(w: SidebarWorkspace, actions: SidebarActions) {
    val amb = Amb.colors
    val dark = isSystemInDarkTheme()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(28.dp, 32.dp).clickable { actions.toggleWorkspaceCollapsed(w.id) }
                .semantics { contentDescription = "${if (w.collapsed) "Expand" else "Collapse"} ${w.label}" }.testTag("workspace.collapse.${w.label}"),
            contentAlignment = Alignment.Center,
        ) { Icon(if (w.collapsed) Icons.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown, null, tint = amb.mutedForeground, modifier = Modifier.size(18.dp)) }
        Box(Modifier.size(9.dp).background(WorkspaceTint.navigation(w.colour, dark), CircleShape))
        Text(w.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold,
            color = amb.foreground, modifier = Modifier.weight(1f).semantics { heading() })
        Box {
            Box(
                Modifier.size(28.dp, 32.dp).clickable { menu = true }.semantics { contentDescription = "Options for ${w.label}" }.testTag("workspace.options.${w.label}"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.MoreHoriz, null, tint = amb.mutedForeground, modifier = Modifier.size(16.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Manage workspace") }, onClick = { menu = false; actions.manageWorkspace(w.id) }, modifier = Modifier.testTag("workspace.manage.${w.label}"), enabled = !w.accessRemoved)
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; actions.moveWorkspace(w.id, -1) })
                DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; actions.moveWorkspace(w.id, 1) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Pause workspace") }, onClick = { menu = false; actions.pauseWorkspace(w.id) }, modifier = Modifier.testTag("workspace.pause.${w.label}"))
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
