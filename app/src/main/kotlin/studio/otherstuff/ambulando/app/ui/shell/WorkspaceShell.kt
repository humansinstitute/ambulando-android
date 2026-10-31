package studio.otherstuff.ambulando.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.PersonAvatar
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.nav.fullScreen
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.profiles.PersonDisplay

/**
 * Per-section navigation stacks, kept while the app runs so switching sections returns to the same screen. Chat keeps
 * its own conversations; conversations never join a stack (they open in the focused thread view).
 */
@Stable
class SectionStacks {
    private val stacks = mutableStateMapOf<AppSection, SnapshotStateList<WorkRoute>>()

    fun of(section: AppSection): SnapshotStateList<WorkRoute> = stacks.getOrPut(section) { mutableStateListOf() }
    /** Told of every record the person navigates to (restoring saved stacks is not navigation and does not call it). */
    var onNavigate: (WorkRoute) -> Unit = {}
    fun push(section: AppSection, route: WorkRoute) { of(section).add(route); onNavigate(route) }
    fun pop(section: AppSection): Boolean = of(section).removeLastOrNull() != null
    fun popToRoot(section: AppSection) = of(section).clear()

    /** A new sidebar choice starts the context-bound sections over (an open record would belong to the old context). */
    fun resetContextBound() = AppSection.entries.filter { it.contextBound && it != AppSection.CHAT }.forEach { of(it).clear() }
    fun clearAll() = stacks.values.forEach { it.clear() }

    /**
     * Every section's stack by section key, read for saving. It only reads (never creates a stack), so it is safe inside a
     * snapshot observer, and reading registers with Compose so changes can be observed.
     */
    fun snapshot(): Map<String, List<WorkRoute>> = AppSection.entries.associate { it.key to (stacks[it]?.toList() ?: emptyList()) }

    /** Puts saved stacks back; sections that already hold a screen (opened since launch) are left as they are. */
    fun restore(saved: Map<String, List<WorkRoute>>) {
        AppSection.entries.forEach { section -> if (of(section).isEmpty()) saved[section.key]?.let { of(section).addAll(it) } }
    }
}

/**
 * Leaves the record on top of the current section's stack (a full-screen record draws its own exit). Null where a
 * screen is not shown from a section stack.
 */
val LocalRouteExit = staticCompositionLocalOf<(() -> Unit)?> { null }

/** What the shell's chrome shows, read by the app model. */
data class ShellChromeState(
    val section: AppSection,
    val sections: List<AppSection>,
    val workspaceLabel: String,
    val scopeName: String?,
    val channelLabel: String?,
    val isHome: Boolean,
    val viewLocked: Boolean,
    val syncing: Boolean,
    val browserTabs: Int,
    val me: PersonDisplay?,
    val identityLocked: Boolean,
    val sample: Boolean,
    val sidebarOpen: Boolean,
    val syncError: String? = null,
    /** Unread notifications for the bell (null: none to show). */
    val notificationBadge: String? = null,
    /** Saved colour of the workspace in front; set only while several workspaces are enabled (a faint 12% header tint). */
    val workspaceTint: Color? = null,
)

interface ShellActions {
    fun select(section: AppSection)
    fun reselect(section: AppSection)
    fun openSidebar()
    fun closeSidebar()
    fun chooseHome()
    fun toggleViewLock()
    fun search()
    fun notifications()
    fun showBrowser()
    fun settings()
    fun unlock()
}

/**
 * Main navigation laid out like the web app on a phone: every section has its own stack, the bottom section bar stays
 * on every screen and no section covers another. Sections are created when first opened and kept, so switching back
 * returns to the same screen, scroll position, board position and composer draft; only the active one is placed (drawn,
 * touchable and in the accessibility tree).
 */
@Composable
fun WorkspaceShell(
    chrome: ShellChromeState,
    actions: ShellActions,
    stacks: SectionStacks,
    sidebar: @Composable () -> Unit,
    sectionRoot: @Composable (AppSection) -> Unit,
    routeScreen: @Composable (AppSection, WorkRoute) -> Unit,
    overlay: @Composable () -> Unit = {},
    pinControl: @Composable (WorkRoute?) -> Unit = {},
) {
    val amb = Amb.colors
    val opened = remember { mutableStateListOf<AppSection>() }
    if (chrome.section !in opened) opened.add(chrome.section)
    Box(Modifier.fillMaxSize().background(amb.background)) {
        Column(Modifier.fillMaxSize()) {
            Layout(
                content = {
                    opened.forEach { section ->
                        Box(Modifier.fillMaxSize().then(if (section != chrome.section) Modifier.clearAndSetSemantics { } else Modifier)) {
                            SectionStackView(section, chrome, actions, stacks, sectionRoot, routeScreen, pinControl)
                        }
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val index = opened.indexOf(chrome.section)
                    if (index >= 0) placeables[index].place(0, 0)
                }
            }
            // A full-screen record (a canvas) replaces the global section bar with its own bottom controls.
            if (stacks.of(chrome.section).lastOrNull()?.fullScreen != true) {
                SectionBar(chrome.sections, chrome.section, onSelect = actions::select, onReselect = actions::reselect)
            }
        }
        overlay()
        AnimatedVisibility(chrome.sidebarOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = actions::closeSidebar)
                .semantics { contentDescription = "Close navigation" }.testTag("sidebar.backdrop"))
        }
        AnimatedVisibility(chrome.sidebarOpen, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
            Box(Modifier.fillMaxHeight().statusBarsPadding()
                .pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if (drag < -24) actions.closeSidebar() } }) {
                sidebar()
            }
        }
        BackHandler(enabled = chrome.sidebarOpen) { actions.closeSidebar() }
    }
}

@Composable
private fun SectionStackView(
    section: AppSection,
    chrome: ShellChromeState,
    actions: ShellActions,
    stacks: SectionStacks,
    sectionRoot: @Composable (AppSection) -> Unit,
    routeScreen: @Composable (AppSection, WorkRoute) -> Unit,
    pinControl: @Composable (WorkRoute?) -> Unit,
) {
    val stack = stacks.of(section)
    val top = stack.lastOrNull()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (top?.fullScreen != true) TopBar(section, chrome, actions, canGoBack = top != null, route = top, pinControl = pinControl, onBack = { stacks.pop(section) })
        if (top == null && section != AppSection.TOOLBOX) {
            ContextBar(
                workspaceLabel = chrome.workspaceLabel, scopeName = chrome.scopeName, channelLabel = chrome.channelLabel, isHome = chrome.isHome,
                lockable = section.lockable, locked = chrome.viewLocked && chrome.section == section, sectionTitle = section.title,
                onHome = actions::chooseHome, onOpenSidebar = actions::openSidebar, onToggleLock = actions::toggleViewLock,
                tint = chrome.workspaceTint,
            )
        }
        Banners(chrome, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (top == null) sectionRoot(section) else androidx.compose.runtime.key(stack.size, top) {
                CompositionLocalProvider(LocalRouteExit provides { stacks.pop(section) }) { routeScreen(section, top) }
            }
        }
    }
    // Back pops this section's stack while it is the active one.
    BackHandler(enabled = top != null && chrome.section == section && !chrome.sidebarOpen) { stacks.pop(section) }
}

@Composable
private fun TopBar(section: AppSection, chrome: ShellChromeState, actions: ShellActions, canGoBack: Boolean, route: WorkRoute?, pinControl: @Composable (WorkRoute?) -> Unit, onBack: () -> Unit) {
    val amb = Amb.colors
    Column(Modifier.fillMaxWidth().background(amb.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoBack) IconButton(onClick = onBack, modifier = Modifier.testTag("nav.back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            else IconButton(onClick = actions::openSidebar, modifier = Modifier.testTag("sidebar.open")) { Icon(Icons.Outlined.Menu, "Open navigation") }
            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (chrome.syncing) Icon(Icons.Outlined.CloudSync, contentDescription = "Syncing", tint = amb.mutedForeground,
                modifier = Modifier.padding(horizontal = 6.dp).size(18.dp).testTag("sync.indicator"))
            IconButton(onClick = actions::search, modifier = Modifier.testTag("search")) { Icon(Icons.Outlined.Search, "Search") }
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = actions::notifications, modifier = Modifier.semantics { contentDescription = chrome.notificationBadge?.let { "Notifications, $it unread" } ?: "Notifications" }.testTag("notifications.open")) {
                    Icon(Icons.Outlined.Notifications, null)
                }
                chrome.notificationBadge?.let { badge -> Text(badge, style = MaterialTheme.typography.labelSmall, color = amb.primaryForeground,
                    modifier = Modifier.padding(top = 6.dp, end = 4.dp).background(amb.primary, CircleShape).padding(horizontal = 4.dp).testTag("notifications.badge")) }
            }
            pinControl(route)
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = actions::showBrowser, modifier = Modifier.semantics { contentDescription = if (chrome.browserTabs > 0) "Browser, ${chrome.browserTabs} tabs" else "Browser" }.testTag("browser.open")) {
                    Icon(Icons.Outlined.Public, null)
                }
                if (chrome.browserTabs > 0) Text("${chrome.browserTabs}", style = MaterialTheme.typography.labelSmall, color = amb.primaryForeground,
                    modifier = Modifier.padding(top = 6.dp, end = 6.dp).background(amb.primary, CircleShape).padding(horizontal = 4.dp))
            }
            Box(Modifier.size(40.dp).clickable(onClick = actions::settings).semantics { contentDescription = "Settings" }.testTag("settings"), contentAlignment = Alignment.Center) {
                val me = chrome.me
                if (me != null) Box(Modifier.border(1.dp, amb.border, CircleShape)) { PersonAvatar(me, 28.dp) }
                else InitialsAvatar("?", 28.dp)
            }
        }
    }
}

@Composable
private fun Banners(chrome: ShellChromeState, actions: ShellActions) {
    val amb = Amb.colors
    chrome.syncError?.let { Text(it, Modifier.fillMaxWidth().background(amb.warningBackground).padding(8.dp).testTag("sync.error"), style = MaterialTheme.typography.labelSmall, color = amb.warning) }
    if (chrome.sample) Row(
        Modifier.fillMaxWidth().background(amb.infoBackground).padding(horizontal = 12.dp, vertical = 4.dp).testTag("fixture.banner"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Science, null, tint = amb.info, modifier = Modifier.size(14.dp))
        Text("Sample data on this device — nothing is sent to a Tower", style = MaterialTheme.typography.labelSmall, color = amb.info)
    }
    if (chrome.identityLocked) Row(
        Modifier.fillMaxWidth().background(amb.warningBackground).clickable(onClick = actions::unlock).padding(horizontal = 12.dp, vertical = 6.dp).testTag("identity.banner"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Lock, null, tint = amb.warning, modifier = Modifier.size(14.dp))
        Text("Locked: showing saved data. Tap to unlock and sync.", style = MaterialTheme.typography.labelSmall, color = amb.warning)
    }
    HorizontalDivider(thickness = 0.dp, color = Color.Transparent)
}
