package studio.otherstuff.ambulando.app.ui.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.ScreenScope
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.work.AccessRemovedNote
import studio.otherstuff.ambulando.app.ui.work.EmptyNote
import studio.otherstuff.ambulando.app.ui.work.ListHeaderBar
import studio.otherstuff.ambulando.app.ui.work.WriteStatusLine
import studio.otherstuff.ambulando.app.ui.work.displayName
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.store.LiveQueryState
import studio.otherstuff.ambulando.core.transport.TaskPriority
import studio.otherstuff.ambulando.core.transport.TaskState

/** The Tasks section: tasks of the shared context as a list grouped by state, or as board columns. */
@Composable
fun TasksScreen(state: TasksScreenState, scope: ScreenScope, intents: TasksIntents) {
    val snapshot by state.snapshot.collectAsState()
    val unlocked = LocalAppServices.current.unlocked
    LaunchedEffect(state.context) { intents.refresh(force = false) }
    LaunchedEffect(unlocked) { if (unlocked) intents.refresh(force = false) }
    TasksContent(snapshot, state.context, scope, intents)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksContent(snapshot: LiveQueryState<TasksSnapshot>, context: WorkContext, scope: ScreenScope, intents: TasksIntents) {
    var board by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val amb = Amb.colors
    Column(Modifier.fillMaxSize().background(amb.background)) {
        ListHeaderBar {
            Spacer(Modifier.weight(1f))
            Row(Modifier.testTag("tasks.layout").background(amb.muted, RoundedCornerShape(Amb.radiusControl)).padding(2.dp)) {
                LayoutSegment(Icons.AutoMirrored.Outlined.List, "List", !board, "tasks.layout.list") { board = false }
                LayoutSegment(Icons.Outlined.ViewColumn, "Board", board, "tasks.layout.board") { board = true }
            }
            IconButton(onClick = { creating = true }, modifier = Modifier.testTag("tasks.new").semantics { contentDescription = "New task" }) {
                Icon(Icons.Filled.AddCircle, contentDescription = null, tint = amb.primary, modifier = Modifier.size(30.dp))
            }
        }
        var refreshing by remember { mutableStateOf(false) }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { intents.refresh(force = true) }, modifier = Modifier.fillMaxSize()) {
            if (board) TaskBoard(snapshot.value, scope, intents) else TaskList(snapshot, scope, intents)
        }
    }
    if (creating) TaskCreateSheet(snapshot.value.choice, snapshot.value.members, context, intents, onDismiss = { creating = false })
}

@Composable
private fun LayoutSegment(icon: ImageVector, label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val amb = Amb.colors
    Box(
        Modifier.width(52.dp).background(if (active) amb.card else amb.muted, RoundedCornerShape(Amb.radiusControl))
            .combinedClickable(onClick = onClick).padding(vertical = 4.dp)
            .semantics { contentDescription = label; selected = active }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (active) amb.primary else amb.mutedForeground, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun TaskList(snapshot: LiveQueryState<TasksSnapshot>, scope: ScreenScope, intents: TasksIntents) {
    val amb = Amb.colors
    val value = snapshot.value
    val services = LocalAppServices.current
    if (snapshot.loaded && value.rows.isEmpty() && value.creating.isEmpty()) {
        EmptyNote("No tasks", Icons.Outlined.Checklist, if (services.unlocked) "Tasks in this scope appear here. Tap + to add one." else "Unlock to load tasks.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(amb.canvas).testTag("tasks.list")) {
        if (value.creating.isNotEmpty()) {
            item(key = "creating.header") { SectionHeader("Creating") }
            items(value.creating, key = { "creating." + it.id }) { item ->
                Column(Modifier.fillMaxWidth().background(amb.card).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.string("title") ?: "Task", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground)
                    WriteStatusLine(item, "New task", intents)
                }
            }
        }
        value.columns.filter { it.second.isNotEmpty() }.forEach { (state, rows) ->
            item(key = "header." + state.wire) { SectionHeader("${state.label} · ${rows.size}") }
            items(rows, key = { it.id }) { row ->
                TaskListRow(row, value, scope, intents)
                HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = amb.border)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Amb.colors.mutedForeground,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp))
}

/** A list row: opens the task; a long press offers the iOS swipe actions (Start, Done). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskListRow(row: TaskRow, snapshot: TasksSnapshot, scope: ScreenScope, intents: TasksIntents) {
    val services = LocalAppServices.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.fillMaxWidth().background(Amb.colors.card)
                .combinedClickable(onClick = { services.open(WorkRoute.Task(scope.workspaceId, row.id)) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("task.${row.title}"),
        ) { TaskRowView(row, snapshot, scope.signerNpub) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (row.taskState != TaskState.IN_PROGRESS && !row.task.accessRemoved) {
                DropdownMenuItem(text = { Text("Start") }, onClick = { menu = false; intents.setState(row, TaskState.IN_PROGRESS) })
            }
            if (row.taskState != TaskState.DONE && !row.task.accessRemoved) {
                DropdownMenuItem(text = { Text("Done") }, onClick = { menu = false; intents.setState(row, TaskState.DONE) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskBoard(snapshot: TasksSnapshot, scope: ScreenScope, intents: TasksIntents) {
    val amb = Amb.colors
    val services = LocalAppServices.current
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        snapshot.columns.forEach { (state, rows) ->
            Column(
                Modifier.width(280.dp).fillMaxHeight().background(amb.muted.copy(alpha = 0.6f), RoundedCornerShape(Amb.radiusPanel)).padding(10.dp)
                    .testTag("board.${state.wire}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${state.label} · ${rows.size}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = amb.primary)
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { row ->
                        var menu by remember(row.id) { mutableStateOf(false) }
                        Box {
                            Box(
                                Modifier.fillMaxWidth().background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                                    .combinedClickable(onClick = { services.open(WorkRoute.Task(scope.workspaceId, row.id)) }, onLongClick = { menu = true })
                                    .padding(10.dp).testTag("task.${row.title}"),
                            ) { TaskRowView(row, snapshot, scope.signerNpub) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!row.task.accessRemoved) TaskState.board.filter { it.wire != row.state }.forEach { target ->
                                    DropdownMenuItem(text = { Text("Move to ${target.label}") }, onClick = { menu = false; intents.setState(row, target) })
                                }
                            }
                        }
                    }
                    if (rows.isEmpty()) Text("Nothing here", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, modifier = Modifier.padding(vertical = 20.dp))
                }
            }
        }
    }
}

/** Title, priority, channel, assignee and the unsent / not-saved marks. */
@Composable
fun TaskRowView(row: TaskRow, snapshot: TasksSnapshot, me: String?) {
    val amb = Amb.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (row.task.accessRemoved) amb.mutedForeground else amb.foreground, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PriorityBadge(row.priority)
            val caption = MaterialTheme.typography.labelSmall
            snapshot.choice.channelName(row.task.channelId)?.let { Text("#$it", style = caption, color = amb.mutedForeground, maxLines = 1) }
            row.assignee?.let {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = amb.mutedForeground, modifier = Modifier.size(12.dp))
                Text(displayName(it, snapshot.names, me), style = caption, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.pending.any { it.isPending }) {
                Icon(Icons.Outlined.ArrowCircleUp, contentDescription = "Change not sent yet", tint = amb.mutedForeground, modifier = Modifier.size(14.dp))
            }
            if (row.pending.any { !it.isPending }) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = "Change not saved", tint = amb.destructive, modifier = Modifier.size(14.dp))
            }
        }
        if (row.task.accessRemoved) AccessRemovedNote()
    }
}

@Composable
fun PriorityBadge(priority: String) {
    val amb = Amb.colors
    val strong = priority == "rock" || priority == "urgent" || priority == "high"
    Text(
        (TaskPriority.from(priority)?.label ?: priority).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        color = if (strong) Amb.brandOrange else amb.accentForeground,
        modifier = Modifier.background(amb.accent, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
