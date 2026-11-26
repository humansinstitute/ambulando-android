package studio.otherstuff.ambulando.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.app.ui.work.EmptyNote
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.store.*
import java.util.UUID

/** Personal goals: a list of goals, each a named aim with a checkpoint timeline and links to existing work. */
@Composable internal fun GoalsScreen(model: AppModel) {
    val w = model.workspace ?: return
    val goals = rememberGoals(model) ?: return
    val services = LocalAppServices.current
    val state by goals.state.collectAsState()
    var creating by remember(goals) { mutableStateOf(false) }
    var showClosed by remember(goals) { mutableStateOf(false) }
    var access by remember(goals) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    GoalsRefresh(model, goals)
    val active = state.goals.filter { it.goal.status == GoalStatus.ACTIVE }
    val closed = state.goals.filter { it.goal.status != GoalStatus.ACTIVE }
    Column(Modifier.fillMaxSize().background(Amb.colors.canvas)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PERSONAL", fontSize = AmbType.caption2, letterSpacing = 1.5.sp, color = Amb.colors.mutedForeground)
                Text("Goals", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground, modifier = Modifier.testTag("goals.title"))
            }
            val ready = state.space as? GoalsSpaceState.Ready
            if (ready != null && !ready.ownerOnly) TextButton(onClick = { access = true }, modifier = Modifier.testTag("goals.agents")) { Text("Agents") }
            Button(onClick = { creating = true }, enabled = ready != null && services.unlocked && !w.accessRemoved, modifier = Modifier.testTag("goals.new")) { Text("New goal") }
        }
        state.error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.weight(1f).testTag("goals.error"))
                TextButton(onClick = { scope.launch { goals.refresh() } }) { Text("Retry") }
            }
        }
        if (!state.loaded && state.error == null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("goals.loading"))
        LazyColumn(Modifier.fillMaxSize().testTag("goals.list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val space = state.space) {
                is GoalsSpaceState.NeedsSetup -> item("setup") { GoalsSetupCard(goals, space) }
                is GoalsSpaceState.Unsafe -> item("unsafe") { Text(space.reason, color = Amb.colors.destructive, modifier = Modifier.testTag("goals.unsafe")) }
                is GoalsSpaceState.Ready -> if (space.ownerOnly) item("owneronly") {
                    Text("Owner-only space: only you can see these goals. Agents cannot be given access here.", fontSize = AmbType.caption, color = Amb.colors.mutedForeground, modifier = Modifier.testTag("goals.ownerOnly"))
                }
                null -> {}
            }
            if (state.space is GoalsSpaceState.Ready && state.loaded && state.goals.isEmpty() && state.unreadable.isEmpty()) item("empty") {
                EmptyNote("No goals yet", Icons.Outlined.Flag, "A goal is something you are working toward, with checkpoints along the way and the chats, tasks and documents that help. Only you and agents you allow can see your goals.")
            }
            items(active, key = { it.id }) { GoalCard(it) { services.open(WorkRoute.Goal(w.id, it.id)) } }
            if (closed.isNotEmpty()) {
                item("closed") {
                    TextButton(onClick = { showClosed = !showClosed }, modifier = Modifier.testTag("goals.closed")) {
                        Text((if (showClosed) "Hide " else "Show ") + "completed and archived (${closed.size})")
                    }
                }
                if (showClosed) items(closed, key = { "c" + it.id }) { GoalCard(it) { services.open(WorkRoute.Goal(w.id, it.id)) } }
            }
            items(state.unreadable, key = { "u" + it.fileId }) { bad ->
                Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Amb.colors.border), RoundedCornerShape(12.dp)).padding(14.dp).testTag("goals.unreadable")) {
                    Text(bad.name, fontWeight = FontWeight.Medium, color = Amb.colors.foreground)
                    Text(bad.reason, fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
                }
            }
        }
    }
    (state.space as? GoalsSpaceState.Ready)?.let { if (access) AgentAccessDialog(goals, it) { access = false } }
    if (creating) GoalEditorDialog(null, onDismiss = { creating = false }) { name, description, start, target ->
        val created = goals.create(Goal.create(name, description, start, target))
        creating = false
        services.open(WorkRoute.Goal(w.id, created.id))
    }
}


@Composable private fun GoalCard(doc: GoalDocument, onOpen: () -> Unit) {
    val goal = doc.goal
    Column(
        Modifier.fillMaxWidth().background(Amb.colors.card, RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, Amb.colors.border), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen).padding(16.dp).testTag("goals.card"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(goal.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (goal.status != GoalStatus.ACTIVE) StatusPill(goal.status)
        }
        Text(goal.description, fontSize = AmbType.subheadline, color = Amb.colors.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val bits = listOfNotNull(GoalFormat.progress(goal), GoalFormat.date(goal.targetDate)?.let { "Target $it" })
        Text(bits.joinToString(" · "), fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
    }
}

@Composable private fun StatusPill(status: GoalStatus) {
    Text(
        if (status == GoalStatus.COMPLETED) "Completed" else "Archived", fontSize = AmbType.caption2, color = Amb.colors.primary,
        modifier = Modifier.background(Amb.colors.accent, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("goal.status"),
    )
}

/** Reloads on entry, when the app returns to the foreground and when this workspace's files change (another device or an agent saved). */
@Composable internal fun GoalsRefresh(model: AppModel, goals: GoalsController) {
    val w = model.workspace ?: return
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(goals) { goals.refresh() }
    DisposableEffect(lifecycle, goals) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) model.act { goals.refresh() } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(goals, w.id) {
        model.store.changes.stream().collect { changes ->
            if (changes.any { (it.workspaceId == null || it.workspaceId == w.id) && it.table == StoreTable.FILES }) { delay(1500); goals.refresh() }
        }
    }
}

/**
 * A File opened by reference (a mention, search, a chip) that is one of the viewer's goals shows the goal, not its JSON.
 * Anything else, or a goal file this viewer's Goals space does not hold, keeps the ordinary [file] view.
 */
@Composable internal fun GoalOrFile(model: AppModel, fileId: String, file: @Composable () -> Unit) {
    val w = model.workspace ?: return file()
    val stored by remember(w.id, fileId) { model.store.liveQuery({ it.workspaceId == w.id && it.table == StoreTable.FILES }) { model.store.reader.file(w.id, fileId) } }.collectAsState(null)
    val record = stored
    if (record == null || record.accessRemoved || !GoalFiles.isGoal(record)) return file()
    val goals = rememberGoals(model) ?: return file()
    val state by goals.state.collectAsState()
    LaunchedEffect(goals) { if (!goals.state.value.loaded) goals.refresh() }
    when {
        state.goals.any { it.id == fileId } -> GoalScreen(model, fileId)
        !state.loaded && state.error == null -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("goal.file.loading"))
        else -> file()
    }
}

/** One goal, laid out as the approved example: aim, dates, timeline, then associated work. */
@Composable internal fun GoalScreen(model: AppModel, fileId: String) {
    val w = model.workspace ?: return
    val goals = rememberGoals(model) ?: return
    val services = LocalAppServices.current
    val state by goals.state.collectAsState()
    val doc = state.goals.firstOrNull { it.id == fileId }
    val scope = rememberCoroutineScope()
    var message by remember(fileId) { mutableStateOf<String?>(null) }
    var editing by remember(fileId) { mutableStateOf(false) }
    var checkpoint by remember(fileId) { mutableStateOf<GoalCheckpoint?>(null) }
    var addingCheckpoint by remember(fileId) { mutableStateOf(false) }
    var linking by remember(fileId) { mutableStateOf(false) }
    var statusMenu by remember(fileId) { mutableStateOf(false) }
    var confirm by remember(fileId) { mutableStateOf<GoalStatus?>(null) }
    GoalsRefresh(model, goals)
    val links by produceState(emptyList<ResolvedGoalLink>(), doc?.goal?.links, w.id) {
        val current = doc?.goal?.links ?: return@produceState
        model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.resolveGoalLinks(w.id, current) }.collect { value = it }
    }
    /** Runs one change; a conflict reloads the saved goal and says so, nothing is lost silently. */
    fun change(vararg edits: GoalEdit) {
        scope.launch {
            message = try { goals.edit(fileId, *edits); null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: GoalError.Conflict) { runCatching { goals.reload(fileId) }; e.message + " The latest version is shown; nothing was overwritten." }
            catch (e: GoalError) { e.message }
            catch (e: GoalSaveFailed) { e.message }
            catch (e: Exception) { studio.otherstuff.ambulando.core.transport.ConnectionFailure.userMessage(e) ?: "Could not save." }
        }
    }
    if (doc == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Text("This goal is not available.", color = Amb.colors.mutedForeground, modifier = Modifier.testTag("goal.missing"))
        }
        return
    }
    val goal = doc.goal
    val readOnly = goal.status == GoalStatus.ARCHIVED || !services.unlocked || w.accessRemoved
    LazyColumn(Modifier.fillMaxSize().background(Amb.colors.canvas).testTag("goal.detail"), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
        item("crumb") { Text("Home  /  Goals  /  ${goal.name}", fontSize = AmbType.caption, color = Amb.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item("head") {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (goal.status == GoalStatus.ACTIVE) "PERSONAL GOAL" else "PERSONAL GOAL · ${goal.status.wire.uppercase()}", fontSize = AmbType.caption2, color = Amb.colors.mutedForeground)
                    Text(goal.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground, modifier = Modifier.padding(top = 4.dp).testTag("goal.name"))
                }
                OutlinedButton(onClick = { editing = true }, enabled = !readOnly, modifier = Modifier.padding(start = 8.dp).testTag("goal.edit")) { Text("Edit goal") }
            }
            Text(goal.description, fontSize = 17.sp, color = Amb.colors.mutedForeground, modifier = Modifier.padding(top = 12.dp).testTag("goal.description"))
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                GoalFormat.date(goal.startDate)?.let { DateLabel("Started", it, "goal.start") }
                GoalFormat.date(goal.targetDate)?.let { DateLabel("Target", it, "goal.target") }
            }
            message?.let { Text(it, color = Amb.colors.destructive, fontSize = AmbType.footnote, modifier = Modifier.padding(top = 10.dp).testTag("goal.message")) }
        }
        item("timeline-head") {
            HorizontalDivider(Modifier.padding(top = 24.dp), color = Amb.colors.border)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Timeline", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Amb.colors.foreground, modifier = Modifier.weight(1f))
                Text(GoalFormat.progress(goal), fontSize = AmbType.caption, color = Amb.colors.mutedForeground, modifier = Modifier.testTag("goal.progress"))
            }
        }
        goal.checkpoints.forEachIndexed { index, cp ->
            item("cp-${cp.id}") {
                CheckpointRow(cp, next = cp.id == goal.nextCheckpointId, last = index == goal.checkpoints.lastIndex, first = index == 0, readOnly = readOnly,
                    onToggle = { change(GoalEdit.SetReached(cp.id, cp.reached, !cp.reached)) }, onEdit = { checkpoint = cp },
                    onUp = { change(GoalEdit.MoveCheckpoint(cp.id, index - 1)) }, onDown = { change(GoalEdit.MoveCheckpoint(cp.id, index + 1)) },
                    onRemove = { change(GoalEdit.RemoveCheckpoint(cp.id)) })
            }
        }
        item("add-checkpoint") {
            TextButton(onClick = { addingCheckpoint = true }, enabled = !readOnly && goal.checkpoints.size < Goal.MAX_CHECKPOINTS, modifier = Modifier.testTag("goal.addCheckpoint")) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add checkpoint")
            }
        }
        item("work-head") {
            HorizontalDivider(Modifier.padding(top = 14.dp), color = Amb.colors.border)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Associated work", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Amb.colors.foreground, modifier = Modifier.weight(1f))
                TextButton(onClick = { linking = true }, enabled = !readOnly, modifier = Modifier.testTag("goal.link")) { Text("Link work") }
            }
        }
        item("work") {
            Column(Modifier.fillMaxWidth().background(Amb.colors.card, RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, Amb.colors.border), RoundedCornerShape(12.dp))) {
                val groups = listOf(
                    Triple(GoalLinkType.CHAT, "Chats", Icons.Outlined.ChatBubbleOutline), Triple(GoalLinkType.TASK, "Tasks", Icons.Outlined.TaskAlt),
                    Triple(GoalLinkType.DOC, "Docs", Icons.Outlined.Description), Triple(GoalLinkType.FILE, "Files and artefacts", Icons.Outlined.Folder),
                )
                groups.forEach { (type, title, icon) ->
                    val rows = links.filter { it.link.type == type }
                    LinkGroup(type, title, icon, rows, readOnly,
                        onOpen = { r -> openLink(services, w.id, r) }, onUnlink = { r -> change(GoalEdit.Unlink(r.link.type, r.link.recordId)) })
                }
            }
        }
        item("lifecycle") {
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (goal.status) {
                    GoalStatus.ACTIVE -> {
                        OutlinedButton(onClick = { confirm = GoalStatus.COMPLETED }, enabled = !readOnly, modifier = Modifier.testTag("goal.complete")) { Text("Mark complete") }
                        OutlinedButton(onClick = { confirm = GoalStatus.ARCHIVED }, enabled = !readOnly, modifier = Modifier.testTag("goal.archive")) { Text("Archive") }
                    }
                    GoalStatus.COMPLETED -> {
                        OutlinedButton(onClick = { change(GoalEdit.SetStatus(goal.status, GoalStatus.ACTIVE)) }, enabled = !readOnly, modifier = Modifier.testTag("goal.reopen")) { Text("Reopen") }
                        OutlinedButton(onClick = { confirm = GoalStatus.ARCHIVED }, enabled = !readOnly, modifier = Modifier.testTag("goal.archive")) { Text("Archive") }
                    }
                    GoalStatus.ARCHIVED -> OutlinedButton(onClick = { change(GoalEdit.SetStatus(goal.status, GoalStatus.ACTIVE)) }, enabled = services.unlocked && !w.accessRemoved, modifier = Modifier.testTag("goal.restore")) { Text("Restore") }
                }
            }
            Text("Progress is the checkpoints you reach. Linked tasks and chats stay where they are; removing a link or archiving never deletes them.",
                fontSize = AmbType.caption, color = Amb.colors.mutedForeground, modifier = Modifier.padding(top = 14.dp, bottom = 24.dp))
        }
    }
    if (editing) GoalEditorDialog(goal, onDismiss = { editing = false }) { name, description, start, target ->
        val edits = buildList {
            if (name.trim() != goal.name) add(GoalEdit.SetName(goal.name, name))
            if (description.trim() != goal.description) add(GoalEdit.SetDescription(goal.description, description))
            if (start != goal.startDate) add(GoalEdit.SetStartDate(goal.startDate, start))
            if (target != goal.targetDate) add(GoalEdit.SetTargetDate(goal.targetDate, target))
        }
        // Validate the whole result before saving so a bad date range is reported in the dialog, not after it closes.
        GoalEdit.applyAll(goal, edits)
        if (edits.isNotEmpty()) goals.edit(fileId, *edits.toTypedArray())
        editing = false
    }
    if (addingCheckpoint) CheckpointDialog(null, onDismiss = { addingCheckpoint = false }) { title, date, description ->
        goals.edit(fileId, GoalEdit.AddCheckpoint(GoalCheckpoint(UUID.randomUUID().toString().replace("-", "").take(16), title.trim(), date, description?.trim()?.ifEmpty { null })))
        addingCheckpoint = false
    }
    checkpoint?.let { cp ->
        CheckpointDialog(cp, onDismiss = { checkpoint = null }) { title, date, description ->
            goals.edit(fileId, GoalEdit.UpdateCheckpoint(cp.id, cp, cp.copy(title = title, date = date, description = description)))
            checkpoint = null
        }
    }
    if (linking) GoalLinkPicker(model, goal, onDismiss = { linking = false }) { chosen ->
        goals.edit(fileId, GoalEdit.Link(chosen)); true
    }
    confirm?.let { target ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (target == GoalStatus.COMPLETED) "Mark this goal complete?" else "Archive this goal?") },
            text = { Text(if (target == GoalStatus.COMPLETED) "The goal moves to Completed with its checkpoints and links intact. You can reopen it." else "The goal is hidden from your active list and made read-only. Its checkpoints and links are kept, linked work is not touched, and you can restore it.") },
            confirmButton = { TextButton(onClick = { confirm = null; change(GoalEdit.SetStatus(goal.status, target)) }, modifier = Modifier.testTag("goal.confirm")) { Text(if (target == GoalStatus.COMPLETED) "Mark complete" else "Archive") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun openLink(services: studio.otherstuff.ambulando.app.ui.nav.AppServices, workspaceId: String, r: ResolvedGoalLink) {
    if (!r.available) return
    services.open(when (r.link.type) {
        GoalLinkType.TASK -> WorkRoute.Task(workspaceId, r.link.recordId)
        GoalLinkType.DOC -> WorkRoute.Doc(workspaceId, r.link.recordId)
        GoalLinkType.FILE -> WorkRoute.File(workspaceId, r.link.recordId)
        GoalLinkType.CHAT -> WorkRoute.Thread(workspaceId, r.channelId ?: r.link.channelId, r.link.recordId)
    })
}

@Composable private fun DateLabel(label: String, value: String, tag: String) {
    Row(Modifier.testTag(tag)) {
        Text("$label ", fontSize = AmbType.footnote, color = Amb.colors.mutedForeground)
        Text(value, fontSize = AmbType.footnote, color = Amb.colors.foreground)
    }
}

@Composable private fun CheckpointRow(
    cp: GoalCheckpoint, next: Boolean, last: Boolean, first: Boolean, readOnly: Boolean,
    onToggle: () -> Unit, onEdit: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit,
) {
    val line = Amb.colors.border
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("goal.checkpoint").drawBehind {
        if (!last) drawLine(line, Offset(10.dp.toPx(), 28.dp.toPx()), Offset(10.dp.toPx(), size.height), 1.dp.toPx())
    }.padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val dotColor = if (cp.reached) Amb.colors.primary else Amb.colors.border
        Box(
            Modifier.padding(top = 3.dp).size(22.dp)
                .then(if (cp.reached) Modifier.background(Amb.colors.primary, CircleShape) else Modifier.border(if (next) 6.dp else 1.5.dp, if (next) Amb.colors.primary else dotColor, CircleShape))
                .clickable(enabled = !readOnly, onClick = onToggle)
                .semantics { contentDescription = if (cp.reached) "Reached. Tap to mark not reached" else "Not reached. Tap to mark reached" }.testTag("goal.reach"),
            contentAlignment = Alignment.Center,
        ) { if (cp.reached) Text("✓", color = Amb.colors.primaryForeground, fontSize = 12.sp) }
        Column(Modifier.weight(1f)) {
            GoalFormat.short(cp.date)?.let { Text(it, fontSize = AmbType.caption, color = Amb.colors.mutedForeground) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cp.title, fontSize = AmbType.body, fontWeight = FontWeight.Medium, color = Amb.colors.foreground, modifier = Modifier.testTag("goal.checkpoint.title"))
                if (next) Text("Next", fontSize = AmbType.caption2, color = Amb.colors.primary, modifier = Modifier.padding(start = 8.dp).background(Amb.colors.accent, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            cp.description?.let { Text(it, fontSize = AmbType.footnote, color = Amb.colors.mutedForeground) }
        }
        if (!readOnly) Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp).testTag("goal.checkpoint.menu")) { Icon(Icons.Outlined.MoreVert, "Checkpoint options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() }, modifier = Modifier.testTag("goal.checkpoint.edit"))
                DropdownMenuItem(text = { Text("Move up") }, enabled = !first, onClick = { menu = false; onUp() }, modifier = Modifier.testTag("goal.checkpoint.up"))
                DropdownMenuItem(text = { Text("Move down") }, enabled = !last, onClick = { menu = false; onDown() }, modifier = Modifier.testTag("goal.checkpoint.down"))
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() }, modifier = Modifier.testTag("goal.checkpoint.remove"))
            }
        }
    }
}

@Composable private fun LinkGroup(
    type: GoalLinkType, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, rows: List<ResolvedGoalLink>, readOnly: Boolean,
    onOpen: (ResolvedGoalLink) -> Unit, onUnlink: (ResolvedGoalLink) -> Unit,
) {
    // A group opens when its first link arrives, so a just-linked record is visible.
    var open by remember(type, rows.isEmpty()) { mutableStateOf(rows.isNotEmpty()) }
    Column(Modifier.fillMaxWidth().testTag("goal.group.${type.wire}")) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = Amb.colors.primary)
            Text(title, fontSize = AmbType.subheadline, color = Amb.colors.foreground, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Text("${rows.size}", fontSize = AmbType.caption, color = Amb.colors.mutedForeground)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp), tint = Amb.colors.mutedForeground)
        }
        if (open) rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(start = 50.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = r.available) { onOpen(r) }.padding(vertical = 10.dp).testTag("goal.link.${type.wire}")) {
                    Text(r.title ?: "Not available to you", fontSize = AmbType.subheadline, color = if (r.available) Amb.colors.foreground else Amb.colors.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(r.location, r.detail).joinToString(" · ").ifEmpty { if (r.available) null else "Removed, restricted or not synced yet" }
                    sub?.let { Text(it, fontSize = AmbType.caption, color = Amb.colors.mutedForeground) }
                }
                if (!readOnly) IconButton(onClick = { onUnlink(r) }, modifier = Modifier.size(40.dp).testTag("goal.unlink")) { Icon(Icons.Outlined.LinkOff, "Remove link to ${r.title ?: "this item"}") }
            }
        }
        if (type != GoalLinkType.FILE) HorizontalDivider(color = Amb.colors.border)
    }
}
