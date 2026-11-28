package studio.otherstuff.ambulando.app.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.PersonAvatar
import studio.otherstuff.ambulando.app.ui.work.personFor
import studio.otherstuff.ambulando.core.profiles.PeopleDirectory
import studio.otherstuff.ambulando.core.transport.MemberRecord

/** Search uses only the authorized membership directory; profile cache entries do not grant eligibility. */
@Composable fun AssigneeTypeahead(selected: String?, members: List<MemberRecord>, people: PeopleDirectory?, enabled: Boolean,
                                  tag: String = "task.assignee", save: (String?) -> Boolean) {
    var open by remember { mutableStateOf(false) }
    val names = members.associate { it.npub to it.label }
    val label = selected?.let { personFor(people, it, null, names).name } ?: "Unassigned"
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = "Assignee: $label" }) {
        if (selected != null) { PersonAvatar(personFor(people, selected, null, names), 24.dp); Spacer(Modifier.width(8.dp)) }
        Text(label, maxLines = 1)
    }
    if (!open) return
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(selected) }
    var cursor by remember(query) { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val choices = members.distinctBy { it.npub }.filter { personFor(people, it.npub, null, names).name.contains(query.trim(), true) || it.label.contains(query.trim(), true) || it.npub.contains(query.trim(), true) }
    val options: List<MemberRecord?> = if (query.isBlank() || "Unassigned".contains(query, true)) listOf(null) + choices else choices
    val listState = rememberLazyListState()
    LaunchedEffect(cursor, query) { if (options.isNotEmpty()) listState.scrollToItem(cursor.coerceIn(options.indices)) }
    fun choose(index: Int) { options.getOrNull(index)?.let { draft = it.npub } ?: run { if (index in options.indices) draft = null } }
    AlertDialog(onDismissRequest = { open = false }, title = { Text("Assign task") }, text = {
        Column(Modifier.fillMaxWidth().testTag("$tag.dialog").onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.Escape -> { open = false; true }
                Key.DirectionDown -> if (!searchFocused) false else { cursor = (cursor + 1).coerceAtMost((options.size - 1).coerceAtLeast(0)); true }
                Key.DirectionUp -> if (!searchFocused) false else { cursor = (cursor - 1).coerceAtLeast(0); true }
                Key.Enter -> if (!searchFocused) false else { choose(cursor); true }
                else -> false
            }
        }) {
            LaunchedEffect(focus) { focus.requestFocus() }
            OutlinedTextField(query, { query = it }, label = { Text("Search people and agents") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { searchFocused = it.isFocused }.testTag("$tag.search"))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), state = listState) {
                itemsIndexed(options, key = { _, member -> member?.npub ?: "unassigned" }) { index, member ->
                    val name = member?.let { personFor(people, it.npub, null, names).name } ?: "Unassigned"
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { cursor = index; draft = member?.npub }
                        .background(if (cursor == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .semantics { role = Role.RadioButton; this.selected = draft == member?.npub; contentDescription = name }
                        .testTag("$tag.option.${member?.npub ?: "unassigned"}").padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (member != null) { PersonAvatar(personFor(people, member.npub, null, names), 32.dp); Spacer(Modifier.width(10.dp)) }
                        Text(name, modifier = Modifier.weight(1f))
                        RadioButton(selected = draft == member?.npub, onClick = null)
                    }
                }
                if (options.isEmpty()) item { Text("No matching people or agents", Modifier.padding(12.dp)) }
            }
            Text("Selected: " + (draft?.let { personFor(people, it, null, names).name } ?: "Unassigned"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (draft != null && members.none { it.npub == draft }) error = "This assignee is no longer eligible."
            else if (draft == selected || save(draft)) open = false
            else error = "Assignment could not be saved. Try again."
        }, modifier = Modifier.testTag("$tag.save")) { Text("Save") }
    }, dismissButton = { TextButton(onClick = { open = false }, modifier = Modifier.testTag("$tag.cancel")) { Text("Cancel") } })
}
