package studio.otherstuff.ambulando.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.otherstuff.ambulando.app.ui.theme.Amb

/** The app's sections, in the web client's mobile order and labels. */
enum class AppSection(val title: String, val icon: ImageVector, val contextBound: Boolean, val lockable: Boolean) {
    DECK("Deck", Icons.Outlined.ViewCarousel, contextBound = true, lockable = false),
    CHAT("Chat", Icons.Outlined.Forum, contextBound = true, lockable = true),
    TASKS("Tasks", Icons.Outlined.Checklist, contextBound = true, lockable = true),
    DOCS("Docs", Icons.Outlined.Description, contextBound = true, lockable = true),
    FILES("Files", Icons.Outlined.Folder, contextBound = true, lockable = true),
    AGENTS("Agents", Icons.Outlined.AutoAwesome, contextBound = true, lockable = false),
    CONTEXT("Context", Icons.Outlined.AccountTree, contextBound = true, lockable = false),
    TOOLBOX("Toolbox", Icons.Outlined.Handyman, contextBound = false, lockable = false);

    val key: String get() = name.lowercase()

    companion object {
        /** Agents and Context are optional (Settings › Sections), off for new installs as on the web. */
        fun visible(showAgents: Boolean, showContext: Boolean): List<AppSection> =
            entries.filter { (it != AGENTS || showAgents) && (it != CONTEXT || showContext) }

        fun fromKey(key: String?): AppSection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Bottom section bar (the web's `.mobile-section-switcher`). Tapping the current section returns it to its first
 * screen; buttons fill the width when they fit and scroll sideways otherwise.
 */
@Composable
fun SectionBar(sections: List<AppSection>, current: AppSection, onSelect: (AppSection) -> Unit, onReselect: (AppSection) -> Unit) {
    val amb = Amb.colors
    Column(Modifier.fillMaxWidth().background(amb.card).navigationBarsPadding().testTag("section.bar")) {
        HorizontalDivider(thickness = 0.5.dp, color = amb.border)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            val fits = maxWidth / sections.size >= 56.dp
            val content: @Composable RowScope.() -> Unit = {
                sections.forEach { section ->
                    val active = section == current
                    Column(
                        Modifier
                            .then(if (fits) Modifier.weight(1f) else Modifier.widthIn(min = 60.dp))
                            .heightIn(min = 50.dp)
                            .clickable(role = Role.Tab) { if (active) onReselect(section) else onSelect(section) }
                            .semantics { selected = active; contentDescription = section.title }
                            .testTag("section.${section.key}")
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(section.icon, contentDescription = null, tint = if (active) amb.primary else amb.mutedForeground, modifier = Modifier.size(22.dp))
                        Text(
                            section.title, fontSize = 10.sp, maxLines = 1,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) amb.primary else amb.mutedForeground,
                        )
                    }
                }
            }
            if (fits) Row(Modifier.fillMaxWidth(), content = content)
            else Row(Modifier.horizontalScroll(rememberScrollState()), content = content)
        }
    }
}

/** Square-ish workspace badge with the workspace's initial (sol `.workspace-avatar`). */
@Composable
fun WorkspaceAvatar(label: String, size: androidx.compose.ui.unit.Dp = 26.dp) {
    val amb = Amb.colors
    Box(Modifier.size(size).background(amb.primary, RoundedCornerShape(size * 0.25f)), contentAlignment = Alignment.Center) {
        Text(label.trim().firstOrNull()?.uppercase() ?: "A", color = amb.primaryForeground, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.5f).sp)
    }
}

/**
 * The context strip under the top bar: workspace badge (show everything), the current "Scope | #channel" opening the
 * sidebar, and the view lock that keeps Chat/Tasks/Docs/Files when another scope or channel is chosen.
 */
@Composable
fun ContextBar(
    workspaceLabel: String,
    scopeName: String?,
    channelLabel: String?,
    isHome: Boolean,
    lockable: Boolean,
    locked: Boolean,
    sectionTitle: String,
    onHome: () -> Unit,
    onOpenSidebar: () -> Unit,
    onToggleLock: () -> Unit,
    /** The saved colour of the workspace in front, blended faintly into this existing strip when several workspaces are enabled. */
    tint: Color? = null,
) {
    val amb = Amb.colors
    Column(Modifier.fillMaxWidth().background(tint?.let { WorkspaceTint.blend(amb.background, it) } ?: amb.background).testTag("context.strip")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clickable(onClick = onHome).semantics { contentDescription = "Show all in $workspaceLabel" }.testTag("context.home")) {
                WorkspaceAvatar(workspaceLabel)
            }
            Row(
                Modifier.weight(1f).clickable(onClick = onOpenSidebar).testTag("context.bar")
                    .semantics { contentDescription = describe(scopeName, channelLabel) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(scopeName ?: "All scopes", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("|", color = amb.border, style = MaterialTheme.typography.bodyMedium)
                Text(channelLabel?.let { "#$it" } ?: if (isHome) "Everything" else "All channels",
                    style = MaterialTheme.typography.bodyMedium, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = amb.mutedForeground, modifier = Modifier.size(18.dp))
            }
            if (lockable) {
                Box(
                    Modifier.size(width = 32.dp, height = 28.dp).clickable(onClick = onToggleLock).testTag("context.lock")
                        .semantics { selected = locked; contentDescription = if (locked) "Unlock $sectionTitle view" else "Keep $sectionTitle when changing scope or channel" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (locked) Icons.Filled.Lock else Icons.Outlined.LockOpen, contentDescription = null,
                        tint = if (locked) amb.primary else amb.mutedForeground, modifier = Modifier.size(16.dp))
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = amb.border.copy(alpha = 0.6f))
    }
}

private fun describe(scope: String?, channel: String?): String = when {
    scope == null -> "Current context: all scopes"
    channel != null -> "Current scope: $scope, channel: $channel"
    else -> "Current scope: $scope, all channels"
}

/** Initials avatar used when no picture is available (sol `getInitials`: first letter of each word, up to two). */
@Composable
fun InitialsAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val amb = Amb.colors
    val initials = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size).background(amb.primary, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, color = amb.primaryForeground, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
    }
}

