package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.app.ui.work.WorkSheet
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
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
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(existing?.description ?: "") }
    var start by rememberSaveable { mutableStateOf(existing?.startDate) }
    var target by rememberSaveable { mutableStateOf(existing?.targetDate) }
    val issue = GoalForm.goalIssue(name, description, start, target)
    GoalFormSheet(
        title = if (existing == null) "New goal" else "Edit goal", confirmLabel = if (existing == null) "Create" else "Save", tag = "goal.input",
        issue = issue, onDismiss = onDismiss, save = { onSave(name.trim(), description.trim(), start, target) },
    ) { first, error ->
        FormField("Name", "goal.input.name.label", counter = name.length to Goal.MAX_NAME) { focused ->
            FieldText(name, { name = it; error() }, "What are you working toward?", focused, Modifier.focusRequester(first).testTag("goal.input.name"), singleLine = true)
        }
        FormField("Description", "goal.input.description.label", counter = description.length to Goal.MAX_DESCRIPTION) { focused ->
            FieldText(description, { description = it; error() }, "What will be different when it’s done?", focused, Modifier.testTag("goal.input.description"), minLines = 4, maxLines = 12)
        }
        DateRow {
            DateField("Start date", start, "goal.input.start", Modifier.weight(1f)) { start = it; error() }
            DateField("Target date", target, "goal.input.target", Modifier.weight(1f), invalid = GoalForm.targetBeforeStart(start, target)) { target = it; error() }
        }
    }
}

/** Title, optional date and description of one checkpoint. */
@Composable internal fun CheckpointDialog(existing: GoalCheckpoint?, onDismiss: () -> Unit, onSave: suspend (String, String?, String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var date by rememberSaveable { mutableStateOf(existing?.date) }
    var description by rememberSaveable { mutableStateOf(existing?.description ?: "") }
    GoalFormSheet(
        title = if (existing == null) "Add checkpoint" else "Edit checkpoint", confirmLabel = if (existing == null) "Add" else "Save", tag = "checkpoint.input",
        issue = GoalForm.checkpointIssue(title, description), onDismiss = onDismiss,
        save = { onSave(title.trim(), date, description.trim().ifEmpty { null }) },
    ) { first, error ->
        FormField("Title", "checkpoint.input.title.label", counter = title.length to GoalForm.MAX_CHECKPOINT_TITLE) { focused ->
            FieldText(title, { title = it; error() }, "A step along the way", focused, Modifier.focusRequester(first).testTag("checkpoint.input.title"), singleLine = true)
        }
        DateRow { DateField("Date (optional)", date, "checkpoint.input.date", Modifier.weight(1f)) { date = it; error() } }
        FormField("Description (optional)", "checkpoint.input.description.label", counter = description.length to GoalForm.MAX_CHECKPOINT_DESCRIPTION) { focused ->
            FieldText(description, { description = it; error() }, "What reaching it means", focused, Modifier.testTag("checkpoint.input.description"), minLines = 3, maxLines = 10)
        }
    }
}

/**
 * The app's full-screen form sheet (Cancel · title · confirm, as New task) for goal and checkpoint forms. Save stays in
 * the bar above the keyboard and is disabled with [issue] shown under the fields. The first field is focused on open.
 * Hardware keys: Tab / Shift+Tab move between fields, Escape cancels, Ctrl/Cmd+Enter saves; Enter in a one-line field
 * moves on, and in a multi-line field starts a new line. A failed save keeps the sheet open with what was typed.
 */
@Composable private fun GoalFormSheet(
    title: String, confirmLabel: String, tag: String, issue: String?, onDismiss: () -> Unit, save: suspend () -> Unit,
    fields: @Composable ColumnScope.(first: FocusRequester, clearError: () -> Unit) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    fun cancel() { if (!busy) onDismiss() }
    fun submit() {
        if (busy || issue != null) return
        busy = true; error = null
        scope.launch {
            try { save() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = readable(e) }
            finally { busy = false }
        }
    }
    WorkSheet(title, ::cancel, confirmLabel = if (busy) "Saving…" else confirmLabel, confirmEnabled = !busy && issue == null, confirmTag = "$tag.save", onConfirm = ::submit) {
        // The sheet is its own window: move focus with that window's manager, not the screen's behind it.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus(); keyboard?.show() } }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Escape -> { cancel(); true }
                        (e.key == Key.Enter || e.key == Key.NumPadEnter) && (e.isCtrlPressed || e.isMetaPressed) -> { submit(); true }
                        // Never typed into a field: the form has no use for tab characters.
                        e.key == Key.Tab -> { focus.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true }
                        else -> false
                    }
                }
                .padding(vertical = 8.dp).testTag("$tag.form"),
        ) {
            fields(first) { error = null }
            val message = error ?: issue
            if (message != null) Text(message, color = if (error != null) Amb.colors.destructive else Amb.colors.mutedForeground, fontSize = AmbType.footnote,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp).testTag(if (error != null) "$tag.error" else "$tag.issue"))
            Text("Ctrl+Enter saves · Esc cancels", fontSize = AmbType.caption2, color = Amb.colors.mutedForeground, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
        }
    }
}

/** A label above a filled, bordered field (FormGroup's look); the border turns primary while the field has focus. */
@Composable private fun FormField(label: String, labelTag: String, counter: Pair<Int, Int>? = null, content: @Composable (focused: Boolean) -> Unit) {
    val amb = Amb.colors
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = amb.foreground, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).testTag(labelTag))
        Box(
            Modifier.fillMaxWidth().background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                .border(if (focused) 2.dp else 1.dp, if (focused) amb.primary else amb.border, RoundedCornerShape(Amb.radiusPanel))
                .onFocusChanged { focused = it.hasFocus }.padding(horizontal = 14.dp, vertical = 12.dp),
        ) { content(focused) }
        counter?.let { (used, max) ->
            if (used > max * 4 / 5) Text("$used / $max", fontSize = AmbType.caption, color = if (used > max) amb.destructive else amb.mutedForeground,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 4.dp))
        }
    }
}

/** Left-aligned text with the app's primary-coloured cursor and selection. One-line fields move to the next field on Enter. */
@Composable private fun FieldText(value: String, onChange: (String) -> Unit, placeholder: String, focused: Boolean, modifier: Modifier, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = 1) {
    val amb = Amb.colors
    val focus = LocalFocusManager.current
    val selection = TextSelectionColors(handleColor = amb.primary, backgroundColor = amb.primary.copy(alpha = 0.3f))
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        BasicTextField(
            value, onChange, singleLine = singleLine, minLines = if (singleLine) 1 else minLines, maxLines = if (singleLine) 1 else maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = amb.foreground, textAlign = TextAlign.Start), cursorBrush = SolidColor(amb.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
            modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder }
                .onPreviewKeyEvent { e -> if (singleLine && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isCtrlPressed && !e.isMetaPressed) focus.moveFocus(FocusDirection.Next) else false },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = amb.mutedForeground)
                    inner()
                }
            },
        )
    }
}

/** Dates side by side on a wide screen, one per line on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DateRow(content: @Composable FlowRowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (maxWidth >= 520.dp) 2 else 1, content = content)
    }
}

/** A date-only value picked from a calendar; the clear button removes it. No time or zone is ever stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DateField(label: String, value: String?, tag: String, modifier: Modifier = Modifier, invalid: Boolean = false, onChange: (String?) -> Unit) {
    val amb = Amb.colors
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = amb.foreground, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).background(amb.card, RoundedCornerShape(Amb.radiusPanel))
                .border(if (focused || invalid) 2.dp else 1.dp, if (invalid) amb.destructive else if (focused) amb.primary else amb.border, RoundedCornerShape(Amb.radiusPanel))
                .onFocusChanged { focused = it.isFocused }.focusable()
                .clickable(role = Role.Button, onClickLabel = "Choose $label") { open = true }
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.Spacebar) && !e.isCtrlPressed && !e.isMetaPressed) { open = true; true } else false }
                .semantics { contentDescription = "$label: ${GoalFormat.date(value) ?: "not set"}" }
                .padding(start = 14.dp, end = 4.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp), tint = amb.mutedForeground)
            Spacer(Modifier.width(10.dp))
            Text(GoalFormat.date(value) ?: "Not set", color = if (value == null) amb.mutedForeground else amb.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) IconButton(onClick = { onChange(null) }, modifier = Modifier.testTag("$tag.clear")) { Icon(Icons.Outlined.Close, "Clear $label", tint = amb.mutedForeground) }
            else Spacer(Modifier.height(48.dp))
        }
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
@Composable internal fun GoalLinkPicker(model: AppModel, goal: Goal, onDismiss: () -> Unit, pins: Boolean = false, onPick: suspend (GoalLink) -> Boolean) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    var type by remember(pins) { mutableStateOf(if (pins) GoalLinkType.DOC else GoalLinkType.TASK) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val candidates by produceState<List<LinkCandidate>?>(null, type, query, w.id) {
        value = null
        value = linkCandidates(model, type, query)
    }
    val already = (if (pins) goal.pinnedRefs else goal.links).map { it.type to it.recordId }.toSet()
    // Lazy content is evaluated after composition too; capture this render's result before a new search resets state.
    val visibleCandidates = candidates
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 16.dp).testTag(if (pins) "goal.pinPicker" else "goal.picker")) {
            Text(if (pins) "Pin a key reference" else "Link work to this goal", style = MaterialTheme.typography.titleMedium)
            Text("Only records you can already open appear here. References keep their original location and access.", fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val types = if (pins) listOf(GoalLinkType.DOC to "Docs", GoalLinkType.FILE to "Files") else listOf(GoalLinkType.TASK to "Tasks", GoalLinkType.CHAT to "Chats", GoalLinkType.DOC to "Docs", GoalLinkType.FILE to "Files")
                types.forEach { (t, label) ->
                    FilterChip(selected = type == t, onClick = { type = t }, enabled = !busy, label = { Text(label) }, modifier = Modifier.testTag("goal.picker.${t.wire}"))
                }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("goal.picker.search"))
            error?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.padding(top = 6.dp).testTag("goal.picker.error")) }
            when {
                visibleCandidates == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                visibleCandidates.isEmpty() -> Text("Nothing to link here yet.", color = Amb.colors.mutedForeground, modifier = Modifier.padding(vertical = 16.dp))
                else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(visibleCandidates, key = { it.link.type.wire + it.link.recordId }) { cand ->
                        val linked = (cand.link.type to cand.link.recordId) in already
                        Column(Modifier.fillMaxWidth().clickable(enabled = !linked && !busy) {
                            busy = true
                            scope.launch {
                                error = try { if (onPick(cand.link)) onDismiss(); null }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { readable(e) }
                                finally { busy = false }
                            }
                        }.padding(vertical = 10.dp).testTag("goal.picker.item")) {
                            Text(cand.title, color = if (linked) Amb.colors.mutedForeground else Amb.colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(cand.location, if (linked) (if (pins) "Already pinned" else "Already linked") else null).joinToString(" · "), fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal data class LinkCandidate(val link: GoalLink, val title: String, val location: String?)

/** Available destinations in this workspace; a retained channel after access loss is never a choice. */
internal fun goalDiscussionChannels(model: AppModel): List<ChannelRecord> {
    val workspace = model.workspace ?: return emptyList()
    if (workspace.accessRemoved) return emptyList()
    val scopes = model.scopes.filter { it.workspaceId == workspace.id && !it.accessRemoved && it.raw.string("archived_at") == null && it.raw.string("deleted_at") == null && it.raw.string("record_state") != "deleted" }.map { it.id }.toSet()
    return model.channels.filter { it.workspaceId == workspace.id && it.scopeId in scopes && !it.accessRemoved && it.raw.string("archived_at") == null && it.raw.string("deleted_at") == null && it.raw.string("record_state") != "deleted" }
}

/** Re-read normal channel access before saving a destination; no alternate channel is substituted. */
internal suspend fun validateGoalDiscussionChannel(model: AppModel, channelId: String): ChannelRecord {
    val workspace = model.workspace ?: throw GoalError.Invalid("This workspace is no longer available.")
    val connection = model.connection ?: throw GoalError.Invalid("This connection is no longer available.")
    fun current() = model.workspace?.id == workspace.id && model.connection?.id == connection.id && model.workspace?.accessRemoved == false
    if (!current()) throw GoalError.Invalid("This workspace is no longer available.")
    val candidate = goalDiscussionChannels(model).firstOrNull { it.id == channelId } ?: throw GoalError.Invalid("This channel is unavailable. Choose another channel explicitly.")
    val api = model.towerAPI(connection)
    val scope = api.scopes(workspace.id, workspace.appNpub).firstOrNull { it.id == candidate.scopeId && it.workspaceId == workspace.id && !it.accessRemoved && it.raw.string("archived_at") == null && it.raw.string("deleted_at") == null && it.raw.string("record_state") != "deleted" }
        ?: throw GoalError.Invalid("This channel’s scope is unavailable.")
    val verified = api.channels(workspace.id, scope.id, workspace.appNpub).firstOrNull { it.id == channelId && it.workspaceId == workspace.id && it.scopeId == scope.id && !it.accessRemoved && it.raw.string("archived_at") == null && it.raw.string("deleted_at") == null && it.raw.string("record_state") != "deleted" }
        ?: throw GoalError.Invalid("This channel is unavailable. Choose another channel explicitly.")
    if (!current()) throw GoalError.Invalid("The workspace changed. Open the goal again to continue.")
    return verified
}

/** A missing saved default remains selected until the user explicitly chooses a replacement or clears it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GoalChannelPicker(model: AppModel, current: String?, onDismiss: () -> Unit, allowUnset: Boolean = true, onPick: suspend (String?) -> Unit) {
    val channels = goalDiscussionChannels(model)
    var selected by remember(current) { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 16.dp).testTag("goal.channelPicker")) {
            Text(if (allowUnset) "Default discussion channel" else "Choose a discussion channel", style = MaterialTheme.typography.titleMedium)
            if (current != null && channels.none { it.id == current }) Text("The saved default channel is unavailable. Choose a replacement explicitly.", color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("goal.channelPicker.unavailable"))
            LazyColumn(Modifier.weight(1f, fill = false).testTag("goal.channelPicker.channels")) {
                if (allowUnset) item("unset") {
                    TextButton(onClick = { selected = null; error = null }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("goal.channelPicker.unset")) { Text((if (selected == null) "✓ " else "") + "Ask each time") }
                }
                items(channels, key = { it.id }) { channel ->
                    val location = model.scopes.firstOrNull { it.id == channel.scopeId }?.name
                    TextButton(onClick = { selected = channel.id; error = null }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("goal.channelPicker.${channel.id}")) {
                        Text((if (selected == channel.id) "✓ " else "") + listOfNotNull(location, channel.name).joinToString(" · "))
                    }
                }
            }
            if (channels.isEmpty()) Text("No accessible channels are available.", color = Amb.colors.mutedForeground, fontSize = AmbType.footnote)
            error?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.testTag("goal.channelPicker.error")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                TextButton(enabled = !busy && (selected == null && allowUnset || channels.any { it.id == selected }), modifier = Modifier.testTag("goal.channelPicker.save"), onClick = {
                    val chosen = selected
                    busy = true; error = null
                    scope.launch {
                        try { chosen?.let { validateGoalDiscussionChannel(model, it) }; onPick(chosen); onDismiss() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = readable(e) }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Saving…" else if (allowUnset) "Save" else "Continue") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

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
