package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.core.management.AdministrationRow
import studio.otherstuff.ambulando.core.management.ListRefresh

/**
 * A page of Manage Workspace. The workspace identity stays visible on every page, so a form is never mistaken for another
 * workspace's, and the status line reports the outcome of the last request (or says one is in flight).
 */
@Composable
internal fun AdminPage(title: String, session: AdministrationSession, back: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = back)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = back, modifier = Modifier.testTag("admin.back")) { Text("Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(session.workspaceLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("admin.workspace"))
        Text("Changes need a connection and are made on Tower immediately. Nothing is queued while offline; saved lists may be out of date.", style = MaterialTheme.typography.bodySmall)
        AuthorityNote(session)
        PhaseBar(session)
        content()
    }
}

@Composable
internal fun AuthorityNote(session: AdministrationSession) {
    when (val a = session.authority) {
        Authority.Checking -> Text("Checking your access…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.authority.checking"))
        is Authority.Unavailable -> Text("Your access could not be verified (${a.message}). Controls that need it are off until it can.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.authority.unavailable"))
        is Authority.Known -> if (!a.membership.canAdminister) {
            Text(
                "Read only. Workspace administrator access is required for these controls. Scope and channel access use your permissions on that exact scope or channel.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.authority.readOnly"),
            )
        }
    }
}

@Composable
internal fun PhaseBar(session: AdministrationSession) {
    when (val p = session.phase) {
        Phase.Idle -> {}
        is Phase.Working -> Column(Modifier.testTag("admin.status.working")) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(p.label, style = MaterialTheme.typography.bodySmall)
        }
        is Phase.Done -> Text(p.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("admin.status.done"))
        is Phase.Failed -> Column(Modifier.testTag("admin.status.failed"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Text("Your entries are kept.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Says why a list is empty or out of date, so "nothing" is never read as "not allowed" or "loaded". */
@Composable
internal fun ListStatus(session: AdministrationSession, list: studio.otherstuff.ambulando.core.management.AdministrationList, noun: String, tag: String) {
    when (val r = session.listState[list]) {
        null -> Text("Loading $noun…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("$tag.loading"))
        ListRefresh.Fresh -> {}
        is ListRefresh.Restricted -> Text(r.message + " Saved rows, if any, are shown below.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("$tag.restricted"))
        is ListRefresh.Unsupported -> Text(r.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("$tag.unsupported"))
        is ListRefresh.Failed -> Text(r.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("$tag.failed"))
    }
}

@Composable
internal fun AdminField(form: FormState, key: String, label: String, enabled: Boolean = true, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = form[key], onValueChange = { form[key] = it }, label = { Text(label) }, singleLine = singleLine, enabled = enabled && form.loaded,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag("admin.field.$key"),
    )
}

/** A small fixed set (Person or agent; View, Contribute or Manage), shown as chips. */
@Composable
internal fun AdminChoice(form: FormState, key: String, label: String, options: List<Pair<String, String>>, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, title) in options) {
                FilterChip(
                    selected = form[key] == value, onClick = { form[key] = value }, enabled = enabled && form.loaded, label = { Text(title) },
                    modifier = Modifier.testTag("admin.choice.$key.$value"),
                )
            }
        }
    }
}

/** One option of a [RowPicker]: [state] says why it cannot be picked (an existing member, a cycle), or is null. */
internal data class PickOption(val id: String, val title: String, val detail: String = "", val state: String? = null, val keywords: String = "")

/** Chooses one row by name from a local, searchable list. Nothing typed here is sent anywhere. */
@Composable
internal fun RowPicker(form: FormState, key: String, label: String, options: List<PickOption>, enabled: Boolean = true, emptyLabel: String = "Nothing to choose") {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = options.firstOrNull { it.id == form[key] }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { open = true }, enabled = enabled && form.loaded, modifier = Modifier.testTag("admin.pick.$key")) {
            Text(selected?.title ?: if (form[key].isNotEmpty()) "Unavailable (${form[key].take(8)}…)" else "Choose…")
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("admin.pick.search"))
                val shown = options.filter { query.isBlank() || (it.title + " " + it.detail + " " + it.id + " " + it.keywords).contains(query.trim(), ignoreCase = true) }.sortedBy { it.title.lowercase() }
                if (shown.isEmpty()) Text(if (options.isEmpty()) emptyLabel else "No match", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.id }) { option ->
                        val pickable = option.state == null
                        Column(
                            Modifier.fillMaxWidth().testTag("admin.pick.option.${option.id}").clickable(enabled = pickable) { form[key] = option.id; open = false }.padding(vertical = 6.dp)
                                .semantics { contentDescription = option.title + (option.state?.let { ", $it" } ?: "") },
                        ) {
                            Text(option.title, style = MaterialTheme.typography.bodyMedium, color = if (pickable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                            val extra = listOfNotNull(option.detail.takeIf { it.isNotBlank() }, option.state).joinToString(" · ")
                            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, tag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("$tag.confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("$tag.cancel")) { Text("Cancel") } },
    )
}

/**
 * Loads and keeps the draft of [form] and offers the one save button. Saving first persists the draft, so an interruption
 * cannot erase what was submitted, then asks for confirmation; [onConfirmed] sends the request. An unchanged acknowledged
 * draft cannot be saved again without an edit.
 */
@Composable
internal fun SaveBar(
    form: FormState, session: AdministrationSession, allowed: Boolean, confirmTitle: String, confirmText: String,
    saveLabel: String = "Save on Tower", onConfirmed: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(form) { form.load() }
    LaunchedEffect(form.fingerprint, form.loaded) {
        if (form.loaded) { delay(200); form.persist() }
    }
    val working = session.phase is Phase.Working
    val enabled = form.loaded && !working && allowed && session.isCurrent && !form.unchangedSinceAcknowledged
    TextButton(onClick = { confirming = true }, enabled = enabled, modifier = Modifier.testTag("admin.save")) { Text(saveLabel) }
    if (form.unchangedSinceAcknowledged) Text("Tower already has this. Edit it to save again.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.alreadySaved"))
    if (!allowed && form.loaded) Text("You do not have the authority for this change, or it could not be verified.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin.notAllowed"))
    Text(
        "Your entries stay on this device after a failure. If the connection drops mid-request Tower may still have accepted it, so refresh before trying again.",
        style = MaterialTheme.typography.bodySmall,
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (confirming) ConfirmDialog(confirmTitle, confirmText, "Confirm change", "admin", onConfirm = {
        confirming = false
        // The entries are on disk before anything is sent, so an interruption cannot erase what was submitted.
        scope.launch { form.persist(); onConfirmed() }
    }, onDismiss = { confirming = false })
}

/** Row text for the lists: the title and the detail on one line each. */
@Composable
internal fun RowSummary(row: AdministrationRow, tag: String, extra: String? = null) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(row.title, style = MaterialTheme.typography.bodyLarge)
        if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodySmall)
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
