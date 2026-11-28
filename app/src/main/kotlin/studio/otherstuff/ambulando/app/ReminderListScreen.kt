package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.tasks.*
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.work.scopeChoice
import studio.otherstuff.ambulando.app.ui.work.WriteStatusLine
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.chat.refreshDirectory

private data class ReminderSnapshot(val list: ReminderList, val tasks: TasksSnapshot)

/** The destination is composed before any signed read is scheduled. */
@Composable internal fun ReminderListScreen(model: AppModel, screen: ScreenScope, intents: TasksIntents) {
    val runtime = model.active ?: return
    val workspace = model.workspace ?: return
    val store = runtime.store
    val snapshot by remember(runtime) { store.liveQuery({ it.workspaceId == workspace.id || it.table == StoreTable.ACCESS || it.table == StoreTable.PROFILES }) {
        val pending = store.reader.workspaceWrites(screen.connectionId, workspace.id)
        val choice = scopeChoice(store, workspace.id)
        val members = store.reader.directoryMembers(workspace.id)
        val writesByTask = pending.groupBy { it.targetId }
        val rows = store.reader.tasks(workspace.id, limit = Int.MAX_VALUE).map { TaskRow.of(it, writesByTask[it.id].orEmpty()) }
        ReminderSnapshot(ReminderSelection.select(workspace.id, screen.signerNpub, members, choice.scopes, choice.channels, rows),
            TasksSnapshot(rows = rows, choice = choice, members = members, people = store.reader.people(screen.connectionId, workspace.id)))
    } }.collectAsState(null)
    val sync by runtime.engine.status.collectAsState()
    val channels = snapshot?.tasks?.choice?.channels?.filter { !it.accessRemoved }?.map { it.id }
    LaunchedEffect(runtime) {
        if (!workspace.accessRemoved && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked) {
            runtime.sync.refreshWorkspace(runtime.connection, workspace)
            runtime.sync.refreshDirectory(runtime.connection, workspace)
        }
    }
    // Newly discovered channels need their task lists too; never repeat discovery because a query emitted.
    LaunchedEffect(runtime, channels) {
        if (channels != null && !workspace.accessRemoved) runtime.sync.refreshTasks(runtime.connection, workspace)
    }
    val services = LocalAppServices.current
    val current = snapshot?.takeUnless { workspace.accessRemoved || !services.unlocked }
    Column(Modifier.fillMaxSize().background(Amb.colors.canvas).testTag("reminders.list")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Reminders", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { model.act { runtime.sync.refreshWorkspace(runtime.connection, workspace, force = true); runtime.sync.refreshDirectory(runtime.connection, workspace, force = true); runtime.sync.refreshTasks(runtime.connection, workspace, force = true) } }) { Text("Refresh") }
        }
        val error = sync.errors.filterKeys { it.startsWith("tasks:${workspace.id}") || it == WorkspaceSync.scopesKey(workspace.id) || it == WorkspaceSync.directoryKey(workspace.id) }.values.firstOrNull()
        Text(if (error != null) "Updates unavailable · Showing saved tasks. Refresh to retry." else if (!sync.isIdle) "Updating · Saved tasks are available" else "Your assigned New, Ready and In progress tasks", modifier = Modifier.padding(horizontal = 16.dp).testTag("reminders.sync"), style = MaterialTheme.typography.bodySmall)
        if (snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reminders.loading"))
        if (snapshot != null && current?.list?.viewer == null) Text("Reminders require a verified human account in this workspace. Connect to refresh membership.", modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize().testTag("reminders.rows"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Note to Self", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("reminders.self")) }
            val self = current?.list?.self.orEmpty()
            val others = current?.list?.others.orEmpty()
            if (current != null && self.isEmpty()) item { Text("No saved open tasks assigned to you in Note to Self.", style = MaterialTheme.typography.bodySmall) }
            items(self, key = { it.id }) { row -> ReminderRow(row, current!!.tasks, screen, intents) }
            item { HorizontalDivider(Modifier.padding(vertical = 12.dp).testTag("reminders.divider")) }
            item { Text("Other tasks", style = MaterialTheme.typography.titleMedium) }
            if (current != null && others.isEmpty()) item { Text("No other saved open tasks assigned to you.", style = MaterialTheme.typography.bodySmall) }
            items(others, key = { it.id }) { row -> ReminderRow(row, current!!.tasks, screen, intents) }
        }
    }
}

@Composable private fun ReminderRow(row: TaskRow, snapshot: TasksSnapshot, scope: ScreenScope, intents: TasksIntents) {
    val services = LocalAppServices.current
    Card(onClick = { services.open(WorkRoute.Task(scope.workspaceId, row.id)) }, modifier = Modifier.fillMaxWidth().testTag("reminder.${row.id}")) {
        Column(Modifier.padding(12.dp)) {
            TaskRowView(row, snapshot, scope.signerNpub)
            Text(row.taskState?.label ?: row.state, style = MaterialTheme.typography.labelSmall)
            row.pending.filter { !it.isPending }.forEach { WriteStatusLine(it, "Task change", intents) }
        }
    }
}
