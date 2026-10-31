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
import androidx.compose.runtime.mutableStateMapOf
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
import studio.otherstuff.ambulando.core.transport.authorNpub
import studio.otherstuff.ambulando.core.transport.TaskState
import studio.otherstuff.ambulando.core.store.TaskRelationshipState
import studio.otherstuff.ambulando.core.surfaces.TaskHierarchy
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute

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
                Hierarchy(row, value, intents, editable)
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
            CommentRow(c, personFor(value.people, c.authorNpub(value.members), scope.signerNpub, value.names), actions)
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
    val draft = studio.otherstuff.ambulando.app.ui.work.rememberCommentDraft(taskId)
    Row(Modifier.fillMaxWidth().background(amb.card).imePadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        studio.otherstuff.ambulando.app.ui.work.MentionTextEntry(draft.text, { draft.text = it }, draft.mentions, { draft.mentions = it }, intents::mentionCandidates, "Add a comment",
            Modifier.weight(1f), Modifier.fillMaxWidth().background(amb.muted, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 8.dp).testTag("comment.field"))
        IconButton(onClick = {
            if (!draft.isBlank && intents.comment(taskId, draft.encoded().body)) draft.clear()
        }, enabled = !draft.isBlank,
            modifier = Modifier.testTag("comment.send").semantics { contentDescription = "Send comment" }) {
            Icon(Icons.Filled.ArrowCircleUp, contentDescription = null, tint = if (!draft.isBlank) amb.primary else amb.mutedForeground, modifier = Modifier.size(30.dp))
        }
    }
}

private fun label(item: WriteItem): String = when (item.kind) {
    WriteKind.TASK_STATE -> "State change"
    WriteKind.TASK_PATCH -> "Edit"
    WriteKind.TASK_ASSIGN -> "Assignment"
    else -> "Change"
}

/**
 * The task's place in its tree: its parent (labelled unavailable rather than guessed), its direct children with status,
 * descendants that expand in place, and Add subtask. Each parent's list comes from its stored membership and read state:
 * the count is direct children including archived ones, and a `+` means the list is not confirmed complete (unloaded,
 * cached, partial, offline or restricted). Opening, expanding, a refresh, and a minute-by-minute foreground fallback each
 * read that parent's direct children again.
 */
@Composable
private fun Hierarchy(row: TaskRow, value: TaskDetailSnapshot, intents: TasksIntents, editable: Boolean) {
    val amb = Amb.colors
    val services = LocalAppServices.current
    val workspaceId = row.task.workspaceId
    // The outcome of this session's latest read of each parent (absent: in flight); the stored state says what was ever confirmed.
    val attempts = remember(row.id) { mutableStateMapOf<String, RelationshipStatus>() }
    var expanded by rememberSaveable(row.id) { mutableStateOf(listOf<String>()) }
    var adding by remember(row.id) { mutableStateOf(false) }
    var refresh by remember(row.id) { mutableStateOf(0) }
    val rootId = row.id.lowercase()
    val epoch = intents.readEpoch
    LaunchedEffect(row.id, refresh, epoch) { attempts.remove(rootId); attempts[rootId] = intents.loadRelationships(row.id) }
    // Expanding a descendant reads that task's own direct list.
    expanded.forEach { id -> LaunchedEffect(id, refresh, epoch) { attempts.remove(id); attempts[id] = intents.loadRelationships(id) } }
    // Foreground fallback for relationship events missed while this screen stays open.
    LaunchedEffect(row.id) { while (true) { kotlinx.coroutines.delay(60_000); refresh += 1 } }
    fun kindOf(id: String) = TaskHierarchy.kind(value.relationships[id], readable = attempts[id] != RelationshipStatus.OFFLINE)
    val direct = value.children[rootId].orEmpty()
    val kind = kindOf(rootId)
    Column(Modifier.testTag("task.hierarchy"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        value.parentId?.let { parentId ->
            val parent = value.parent
            Text(if (parent != null) "Subtask of ${parent.title}" else "Parent task unavailable",
                style = MaterialTheme.typography.bodyMedium, color = if (parent != null) amb.primary else amb.mutedForeground,
                modifier = Modifier.fillMaxWidth().clickable(enabled = parent != null) { services.open(WorkRoute.Task(workspaceId, parentId)) }.padding(vertical = 4.dp).testTag("task.parent"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subtasks " + TaskHierarchy.countLabel(direct.size, TaskHierarchy.isConfirmed(kind)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = amb.primary, modifier = Modifier.weight(1f).testTag("task.subtasks.count"))
            TextButton(onClick = { refresh += 1 }, modifier = Modifier.testTag("task.subtasks.refresh")) { Text("Refresh") }
            TextButton(onClick = { adding = true }, enabled = editable, modifier = Modifier.testTag("task.subtask.add")) { Text("Add subtask") }
        }
        RelationshipNote(kind, value.relationships[rootId], reading = rootId !in attempts, modifier = Modifier.testTag("task.subtasks.state"))
        val creating = value.creatingChildren[rootId].orEmpty()
        if (direct.isEmpty() && creating.isEmpty() && kind == TaskHierarchy.Kind.COMPLETE) Text("No subtasks.", style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
        SubtaskRows(direct, value, listOf(rootId), 0, expanded, { id -> expanded = if (id in expanded) expanded - id else expanded + id }, intents, ::kindOf, attempts)
        creating.forEach { item ->
            Column(Modifier.testTag("task.subtask.pending")) {
                Text(item.string("title") ?: "", style = MaterialTheme.typography.bodyMedium, color = amb.mutedForeground)
                WriteStatusLine(item, "Subtask", intents)
            }
        }
    }
    if (adding) {
        var title by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add subtask") }, text = {
            androidx.compose.material3.OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("task.subtask.title"))
        }, confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { if (intents.addSubtask(row, title)) { adding = false; refresh += 1 } }, modifier = Modifier.testTag("task.subtask.create")) { Text("Add") }
        }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}

/** What this device knows about one parent's list, in words: unloaded, cached, partial, offline and restricted are different. */
@Composable
private fun RelationshipNote(kind: TaskHierarchy.Kind, state: TaskRelationshipState?, reading: Boolean, modifier: Modifier = Modifier) {
    val amb = Amb.colors
    val (text, warn) = when (kind) {
        TaskHierarchy.Kind.COMPLETE -> (if (reading) "Refreshing subtasks…" else "") to false
        TaskHierarchy.Kind.UNLOADED -> (if (reading) "Checking for subtasks… Saved tasks may be incomplete." else "Subtasks not loaded yet. Showing saved tasks.") to false
        TaskHierarchy.Kind.CACHED -> ("Saved subtask list" + (state?.refreshedAt?.let { ", last confirmed ${it.take(16).replace('T', ' ')}" } ?: "") +
            ". It could not be refreshed, so it may be out of date.") to false
        TaskHierarchy.Kind.PARTIAL -> "Showing the subtasks read so far; there may be more. Refresh to read the rest." to true
        TaskHierarchy.Kind.OFFLINE -> "Offline or locked. Showing saved subtasks; they update when this device is connected." to false
        TaskHierarchy.Kind.RESTRICTED -> "Some subtasks may not be visible to you. Only subtasks you can read are listed." to true
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = if (warn) amb.warning else amb.mutedForeground, modifier = modifier)
}

@Composable
private fun SubtaskRows(children: List<TaskRow>, value: TaskDetailSnapshot, path: List<String>, depth: Int, expanded: List<String>, toggle: (String) -> Unit, intents: TasksIntents,
                        kindOf: (String) -> TaskHierarchy.Kind, attempts: Map<String, RelationshipStatus>) {
    val amb = Amb.colors
    val services = LocalAppServices.current
    children.forEach { child ->
        val id = child.id.lowercase()
        val cycle = TaskHierarchy.closesCycle(path, id)
        val open = id in expanded && !cycle
        Column(Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (cycle) "·" else if (open) "▾" else "▸", color = amb.mutedForeground,
                    modifier = Modifier.clickable(enabled = !cycle) { toggle(id) }.padding(horizontal = 10.dp, vertical = 10.dp).testTag("task.subtask.expand.$id")
                        .semantics { contentDescription = if (open) "Collapse subtasks" else "Show subtasks" })
                Column(Modifier.weight(1f).clickable { services.open(WorkRoute.Task(child.task.workspaceId, child.id)) }.testTag("task.subtask.$id")) {
                    Text(child.title, style = MaterialTheme.typography.bodyMedium, color = amb.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((child.taskState?.label ?: child.state) + if (cycle) " · already shown above" else "", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                }
            }
            child.pending.filter { it.kind != WriteKind.TASK_CREATE }.forEach { WriteStatusLine(it, label(it), intents) }
            if (open) {
                val nested = value.children[id].orEmpty()
                val creating = value.creatingChildren[id].orEmpty()
                val kind = kindOf(id)
                RelationshipNote(kind, value.relationships[id], reading = id !in attempts, modifier = Modifier.padding(start = 16.dp).testTag("task.subtask.state.$id"))
                if (nested.isEmpty() && creating.isEmpty() && kind == TaskHierarchy.Kind.COMPLETE) Text("No subtasks.", style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground, modifier = Modifier.padding(start = 16.dp))
                SubtaskRows(nested, value, path + id, depth + 1, expanded, toggle, intents, kindOf, attempts)
                creating.forEach { item -> WriteStatusLine(item, "Subtask ${item.string("title") ?: ""}".trim(), intents, Modifier.padding(start = 16.dp)) }
            }
        }
    }
}
