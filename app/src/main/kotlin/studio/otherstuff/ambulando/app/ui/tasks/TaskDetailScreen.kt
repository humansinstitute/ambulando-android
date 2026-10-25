package studio.otherstuff.ambulando.app.ui.tasks

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.ScreenScope
import studio.otherstuff.ambulando.app.ui.rich.RichBlocks
import studio.otherstuff.ambulando.app.ui.rich.RichStyle
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.EditorialStyle
import studio.otherstuff.ambulando.app.ui.theme.NoticeTone
import studio.otherstuff.ambulando.app.ui.theme.ambCard
import studio.otherstuff.ambulando.app.ui.work.AccessRemovedNote
import studio.otherstuff.ambulando.app.ui.work.CommentRow
import studio.otherstuff.ambulando.app.ui.work.ContentTextEntry
import studio.otherstuff.ambulando.app.ui.work.DEVICE_NAME
import studio.otherstuff.ambulando.app.ui.work.SampleLabel
import studio.otherstuff.ambulando.app.ui.work.SyncNote
import studio.otherstuff.ambulando.app.ui.work.WriteStatusLine
import studio.otherstuff.ambulando.app.ui.work.displayName
import studio.otherstuff.ambulando.app.ui.work.personFor
import studio.otherstuff.ambulando.app.ui.work.workRichActions
import studio.otherstuff.ambulando.core.docs.DocumentRender
import studio.otherstuff.ambulando.core.store.LiveQueryState
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.transport.TaskPriority
import studio.otherstuff.ambulando.core.transport.TaskState

/** One task: title, place, state / priority / assignee chips, pending changes, description, comments and a composer. */
@Composable
fun TaskDetailScreen(state: TaskDetailState, scope: ScreenScope, intents: TasksIntents) {
    val snapshot by state.snapshot.collectAsState()
    val error by remember(state.taskId) { intents.taskError(state.taskId) }.collectAsState(initial = null)
    LaunchedEffect(state.taskId) { intents.openTask(state.taskId, force = false, markViewed = true) }
    TaskDetailContent(snapshot, state.taskId, scope, intents, error)
}

@Composable
fun TaskDetailContent(snapshot: LiveQueryState<TaskDetailSnapshot>, taskId: String, scope: ScreenScope, intents: TasksIntents, syncError: String? = null) {
    val amb = Amb.colors
    val services = LocalAppServices.current
    val value = snapshot.value
    val row = value.row
    val editable = row != null && !row.task.accessRemoved
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(amb.background)) {
        SampleLabel()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Task", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = amb.foreground, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editing = true }, enabled = editable, modifier = Modifier.testTag("task.edit")) { Text("Edit") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (row != null) {
                Header(row, value, scope, intents, editable)
                row.pending.forEach { WriteStatusLine(it, label(it), intents) }
                row.task.description?.takeIf { it.isNotEmpty() }?.let {
                    Box(Modifier.fillMaxWidth().ambCard(amb)) {
                        RichBlocks(DocumentRender.markdownBlocks(it), style = RichStyle.CHAT, muted = row.task.accessRemoved, actions = workRichActions())
                    }
                }
                Comments(value, scope, intents)
            } else if (snapshot.loaded) {
                SyncNote(if (services.unlocked) "Loading task…" else "This task is not saved on this $DEVICE_NAME yet. Unlock to load it.")
            }
            syncError?.let { SyncNote(it, NoticeTone.WARNING) }
        }
        if (row == null || editable) CommentComposer(taskId, intents)
    }
    if (editing && row != null) TaskEditSheet(row, intents, onDismiss = { editing = false })
}

@Composable
private fun Header(row: TaskRow, value: TaskDetailSnapshot, scope: ScreenScope, intents: TasksIntents, editable: Boolean) {
    val amb = Amb.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(row.title, style = EditorialStyle, color = amb.foreground, modifier = Modifier.testTag("task.title"))
        value.choice.channelName(row.task.channelId)?.let { channel ->
            Text(listOfNotNull(value.choice.scopeName(row.task.scopeId), "#$channel").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
        }
        if (row.task.accessRemoved) AccessRemovedNote()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuChip(row.taskState?.label ?: row.state, Icons.Outlined.TripOrigin, "task.state", editable,
                (TaskState.board + listOf(TaskState.BACKLOG, TaskState.ARCHIVE)).map { s -> s.label to { intents.setState(row, s); Unit } })
            MenuChip(TaskPriority.from(row.priority)?.label ?: row.priority, Icons.Outlined.Flag, "task.priority", editable,
                TaskPriority.editable.map { p -> p.label to { intents.setPriority(row, p); Unit } })
            MenuChip(row.assignee?.let { displayName(it, value.names, scope.signerNpub) } ?: "Unassigned", Icons.Outlined.Person, "task.assignee", editable,
                listOf("Unassigned" to { intents.assign(row, null); Unit }) + value.members.map { m -> m.label to { intents.assign(row, m.npub); Unit } })
        }
    }
}

@Composable
private fun MenuChip(text: String, icon: ImageVector, tag: String, enabled: Boolean, options: List<Pair<String, () -> Unit>>) {
    val amb = Amb.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.heightIn(min = 32.dp).background(amb.accent, CircleShape).clickable(enabled = enabled) { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = amb.accentForeground, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = amb.accentForeground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}

@Composable
private fun Comments(value: TaskDetailSnapshot, scope: ScreenScope, intents: TasksIntents) {
    val amb = Amb.colors
    val actions = workRichActions()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Comments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = amb.primary)
        value.comments.forEach { c ->
            CommentRow(c, personFor(value.people, c.senderNpub, scope.signerNpub, value.names), actions)
        }
        value.pendingComments.forEach { item ->
            Column(Modifier.testTag("comment.pending"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.string("body") ?: "", style = MaterialTheme.typography.bodyLarge, color = amb.mutedForeground)
                WriteStatusLine(item, "Comment", intents)
            }
        }
        if (value.comments.isEmpty() && value.pendingComments.isEmpty()) {
            Text("No comments yet.", style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
        }
    }
}

@Composable
private fun CommentComposer(taskId: String, intents: TasksIntents) {
    val amb = Amb.colors
    var comment by rememberSaveable(taskId) { mutableStateOf("") }
    val trimmed = comment.trim()
    Row(Modifier.fillMaxWidth().background(amb.card).imePadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ContentTextEntry(comment, { comment = it }, "Add a comment",
            Modifier.weight(1f).background(amb.muted, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 8.dp).testTag("comment.field"))
        IconButton(onClick = { if (trimmed.isNotEmpty() && intents.comment(taskId, trimmed)) comment = "" }, enabled = trimmed.isNotEmpty(),
            modifier = Modifier.testTag("comment.send").semantics { contentDescription = "Send comment" }) {
            Icon(Icons.Filled.ArrowCircleUp, contentDescription = null, tint = if (trimmed.isNotEmpty()) amb.primary else amb.mutedForeground, modifier = Modifier.size(30.dp))
        }
    }
}

private fun label(item: WriteItem): String = when (item.kind) {
    WriteKind.TASK_STATE -> "State change"
    WriteKind.TASK_PATCH -> "Edit"
    WriteKind.TASK_ASSIGN -> "Assignment"
    else -> "Change"
}
