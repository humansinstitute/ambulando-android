package studio.otherstuff.ambulando.app.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.work.ContentTextEntry
import studio.otherstuff.ambulando.app.ui.work.DEVICE_NAME
import studio.otherstuff.ambulando.app.ui.work.FormDivider
import studio.otherstuff.ambulando.app.ui.work.FormGroup
import studio.otherstuff.ambulando.app.ui.work.PickerRow
import studio.otherstuff.ambulando.app.ui.work.ScopeChoice
import studio.otherstuff.ambulando.app.ui.work.WorkSheet
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.transport.TaskPriority
import studio.otherstuff.ambulando.core.transport.TaskState

/** New task: title, description, channel (in the shared context), state, priority and assignee. */
@Composable
fun TaskCreateSheet(choice: ScopeChoice, members: List<MemberRecord>, context: WorkContext, intents: TasksIntents, onDismiss: () -> Unit) {
    val services = LocalAppServices.current
    val channels = remember(choice, context) { choice.channels(context) }
    var title by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    var channelId by rememberSaveable { mutableStateOf(context.channelId ?: channels.firstOrNull()?.id) }
    var state by rememberSaveable { mutableStateOf(TaskState.NEW) }
    var priority by rememberSaveable { mutableStateOf(TaskPriority.SAND) }
    var assignee by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(channels) { if (channelId == null || channels.none { it.id == channelId }) channelId = channels.firstOrNull()?.id }

    WorkSheet(
        "New task", onDismiss, confirmLabel = "Add", confirmTag = "newtask.add",
        confirmEnabled = title.isNotBlank() && channelId != null,
        onConfirm = {
            val channel = channelId ?: return@WorkSheet
            if (intents.createTask(channel, title.trim(), detail, state, priority, assignee)) onDismiss()
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FormGroup {
                ContentTextEntry(title, { title = it }, "Title", Modifier.fillMaxWidth().padding(16.dp).testTag("newtask.title"), maxLines = 3)
                FormDivider()
                ContentTextEntry(detail, { detail = it }, "Description", Modifier.fillMaxWidth().padding(16.dp).testTag("newtask.description"),
                    minLines = 3, maxLines = 8)
            }
            FormGroup {
                PickerRow("Channel", channels.firstOrNull { it.id == channelId }?.let { "#${it.name}" } ?: "",
                    channels.map { "#${it.name} · ${choice.scopeName(it.scopeId) ?: ""}" to it.id }, { channelId = it }, tag = "newtask.channel")
                FormDivider()
                PickerRow("State", state.label, TaskState.board.map { it.label to it }, { state = it }, tag = "newtask.state")
                FormDivider()
                PickerRow("Priority", priority.label, TaskPriority.editable.map { it.label to it }, { priority = it }, tag = "newtask.priority")
                FormDivider()
                PickerRow("Assignee", members.firstOrNull { it.npub == assignee }?.label ?: "Unassigned",
                    listOf<Pair<String, String?>>("Unassigned" to null) + members.map { it.label to it.npub }, { assignee = it }, tag = "newtask.assignee")
            }
            if (!services.unlocked) {
                Text("Saved on this $DEVICE_NAME and sent after you unlock.", style = MaterialTheme.typography.bodySmall,
                    color = Amb.colors.mutedForeground, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
            }
        }
    }
}

/** Edit a task's title and description; only changed fields are sent, with the version they were made on. */
@Composable
fun TaskEditSheet(row: TaskRow, intents: TasksIntents, onDismiss: () -> Unit) {
    var title by rememberSaveable(row.id) { mutableStateOf(row.title) }
    var detail by rememberSaveable(row.id) { mutableStateOf(row.task.description ?: "") }
    WorkSheet(
        "Edit task", onDismiss, confirmLabel = "Save", confirmTag = "edittask.save", confirmEnabled = title.isNotBlank(),
        onConfirm = { if (intents.edit(row, title, detail)) onDismiss() },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FormGroup(header = "Title") {
                ContentTextEntry(title, { title = it }, "Title", Modifier.fillMaxWidth().padding(16.dp).testTag("edittask.title"), maxLines = 4)
            }
            FormGroup(header = "Description") {
                ContentTextEntry(detail, { detail = it }, "Description",
                    Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(16.dp).testTag("edittask.description"), minLines = 6, maxLines = 30)
            }
        }
    }
}
