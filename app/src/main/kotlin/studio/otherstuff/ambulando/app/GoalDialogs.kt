package studio.otherstuff.ambulando.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.ConnectionFailure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Turns a failed save into the sentence shown in a dialog; the dialog stays open and keeps what was typed. */
private fun readable(e: Exception): String = when (e) {
    is GoalError -> e.message ?: "The goal could not be saved."
    is GoalSaveFailed -> e.message ?: "The goal could not be saved."
    else -> ConnectionFailure.userMessage(e) ?: "The goal could not be saved."
}

/** Name, description and optional date-only start/target. [existing] null creates a goal. */
@Composable internal fun GoalEditorDialog(existing: Goal?, onDismiss: () -> Unit, onSave: suspend (String, String, String?, String?) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var start by remember { mutableStateOf(existing?.startDate) }
    var target by remember { mutableStateOf(existing?.targetDate) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "New goal" else "Edit goal") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("goal.input.name"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(description, { description = it; error = null }, label = { Text("Goal") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("goal.input.description"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                DateField("Start date (optional)", start, "goal.input.start") { start = it; error = null }
                DateField("Target date (optional)", target, "goal.input.target") { target = it; error = null }
                error?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("goal.input.error")) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.testTag("goal.input.save"), onClick = {
                busy = true; error = null
                scope.launch {
                    try { onSave(name.trim(), description.trim(), start, target) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = readable(e) }
                    finally { busy = false }
                }
            }) { Text(if (existing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.testTag("goal.input.cancel")) { Text("Cancel") } },
    )
}

/** Title, optional date and description of one checkpoint. */
@Composable internal fun CheckpointDialog(existing: GoalCheckpoint?, onDismiss: () -> Unit, onSave: suspend (String, String?, String?) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var date by remember { mutableStateOf(existing?.date) }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Add checkpoint" else "Edit checkpoint") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it; error = null }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("checkpoint.input.title"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                DateField("Date (optional)", date, "checkpoint.input.date") { date = it; error = null }
                OutlinedTextField(description, { description = it; error = null }, label = { Text("Description (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("checkpoint.input.description"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                error?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("checkpoint.input.error")) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.isNotBlank(), modifier = Modifier.testTag("checkpoint.input.save"), onClick = {
                busy = true; error = null
                scope.launch {
                    try { onSave(title.trim(), date, description.trim().ifEmpty { null }) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = readable(e) }
                    finally { busy = false }
                }
            }) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A date-only value picked from a calendar; "Clear" removes it. No time or zone is ever stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DateField(label: String, value: String?, tag: String, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f).testTag(tag)) {
            Text(GoalFormat.date(value)?.let { "$label: $it" } ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) TextButton(onClick = { onChange(null) }, modifier = Modifier.testTag("$tag.clear")) { Text("Clear") }
    }
    if (open) {
        val initial = value?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }, modifier = Modifier.testTag("$tag.ok")) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Find an existing chat, task, Doc or File and link it. Linking copies nothing and grants nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GoalLinkPicker(model: AppModel, goal: Goal, onDismiss: () -> Unit, onPick: suspend (GoalLink) -> Boolean) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    var type by remember { mutableStateOf(GoalLinkType.TASK) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val candidates by produceState<List<LinkCandidate>?>(null, type, query, w.id) {
        value = null
        value = linkCandidates(model, type, query)
    }
    val already = goal.links.map { it.type to it.recordId }.toSet()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 16.dp).testTag("goal.picker")) {
            Text("Link work to this goal", style = MaterialTheme.typography.titleMedium)
            Text("Only records you can already open appear here. Linking keeps them where they are.", fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(GoalLinkType.TASK to "Tasks", GoalLinkType.CHAT to "Chats", GoalLinkType.DOC to "Docs", GoalLinkType.FILE to "Files").forEach { (t, label) ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label) }, modifier = Modifier.testTag("goal.picker.${t.wire}"))
                }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("goal.picker.search"))
            error?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.padding(top = 6.dp).testTag("goal.picker.error")) }
            when {
                candidates == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                candidates!!.isEmpty() -> Text("Nothing to link here yet.", color = Amb.colors.mutedForeground, modifier = Modifier.padding(vertical = 16.dp))
                else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(candidates!!, key = { it.link.type.wire + it.link.recordId }) { cand ->
                        val linked = (cand.link.type to cand.link.recordId) in already
                        Column(Modifier.fillMaxWidth().clickable(enabled = !linked) {
                            scope.launch {
                                error = try { if (onPick(cand.link)) onDismiss(); null }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { readable(e) }
                            }
                        }.padding(vertical = 10.dp).testTag("goal.picker.item")) {
                            Text(cand.title, color = if (linked) Amb.colors.mutedForeground else Amb.colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(cand.location, if (linked) "Already linked" else null).joinToString(" · "), fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal data class LinkCandidate(val link: GoalLink, val title: String, val location: String?)

/** Records of the workspace in front that this device already holds and may show; bounded and filtered by [query]. */
private suspend fun linkCandidates(model: AppModel, type: GoalLinkType, query: String): List<LinkCandidate> {
    val w = model.workspace ?: return emptyList(); val c = model.connection ?: return emptyList()
    val reader = model.store.reader
    val channels = model.channels.filter { !it.accessRemoved }.associateBy { it.id }
    val scopes = model.scopes.associate { it.id to it.name }
    fun place(channelId: String?): String? = channels[channelId]?.let { ch -> listOfNotNull(ch.name, scopes[ch.scopeId]).joinToString(" · ") }
    val q = query.trim().lowercase()
    fun keep(title: String) = q.isEmpty() || title.lowercase().contains(q)
    return when (type) {
        GoalLinkType.TASK -> reader.tasks(w.id, null, null, false, 500).filter { !it.accessRemoved && keep(it.title) }
            .map { LinkCandidate(GoalLink(type, it.id, it.channelId), it.title, place(it.channelId)) }
        GoalLinkType.DOC -> reader.docs(w.id).filter { !it.accessRemoved && !it.archived && keep(it.title) }
            .map { LinkCandidate(GoalLink(type, it.id, it.channelId), it.title, place(it.channelId)) }
        GoalLinkType.FILE -> reader.files(w.id).filter { !it.accessRemoved && !it.archived && !GoalFiles.isGoal(it) && keep(it.displayName) }
            .map { LinkCandidate(GoalLink(type, it.id, it.channelId), it.displayName, place(it.channelId)) }
        GoalLinkType.CHAT -> reader.conversations(w.id, channels.keys, c.signerNpub, q).filter { it.threadId != null }
            .map { LinkCandidate(GoalLink(type, it.threadId!!, it.channelId), it.title.ifBlank { "Conversation" }, place(it.channelId)) }
    }.take(200)
}

/**
 * "Add to goal" for the record being viewed. Choosing a goal links the record there; a record can belong to several
 * goals. Goals already holding it are marked, and linking again does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AddToGoalSheet(model: AppModel, type: GoalLinkType, recordId: String, channelId: String?, onDismiss: () -> Unit) {
    val goals = rememberGoals(model) ?: return
    val state by goals.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(goals) { goals.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("addtogoal.sheet")) {
            Text("Add to goal", style = MaterialTheme.typography.titleMedium)
            val choices = state.goals.filter { it.goal.status != GoalStatus.ARCHIVED }
            if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            else if (choices.isEmpty()) Text("You have no goals yet. Create one from Apps → Goals.", color = Amb.colors.mutedForeground, modifier = Modifier.padding(vertical = 16.dp))
            choices.forEach { doc ->
                val has = doc.goal.links.any { it.type == type && it.recordId == recordId }
                Row(Modifier.fillMaxWidth().clickable(enabled = !has) {
                    scope.launch {
                        message = try { goals.edit(doc.id, GoalEdit.Link(GoalLink(type, recordId, channelId))); onDismiss(); null }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { readable(e) }
                    }
                }.padding(vertical = 12.dp).testTag("addtogoal.goal"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(doc.goal.name, color = if (has) Amb.colors.mutedForeground else Amb.colors.foreground)
                        Text(GoalFormat.progress(doc.goal), fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
                    }
                    if (has) Text("Added", fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
                }
            }
            message?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("addtogoal.error")) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** A compact "Add to goal" row for screens without a menu (a task); the sheet is owned here so it outlives nothing it should not. */
@Composable internal fun AddToGoalButton(model: AppModel, type: GoalLinkType, recordId: String, channelId: String? = null) {
    val services = studio.otherstuff.ambulando.app.ui.nav.LocalAppServices.current
    var open by remember(recordId) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = services.unlocked && model.workspace?.accessRemoved == false, modifier = Modifier.testTag("addtogoal.open")) {
        Icon(androidx.compose.material.icons.Icons.Outlined.Flag, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to goal")
    }
    if (open) AddToGoalSheet(model, type, recordId, channelId) { open = false }
}
