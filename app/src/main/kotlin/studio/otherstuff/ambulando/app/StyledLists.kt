package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.chat.ChatTime
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.app.ui.work.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.DocRecord

// The Docs, Toolbox, Agents and Context lists, drawn as the iPhone's inset-grouped lists (see docs/visual-parity.md).

/** Documents: a filter and New document above rows of icon, title, then #channel, time and Unsaved edits (iOS `DocsView`). */
@Composable internal fun DocsList(model: AppModel) {
    val w = model.workspace ?: return
    val c = model.connection
    val services = LocalAppServices.current
    var filter by remember { mutableStateOf("") }
    val rows by remember(w.id, model.context) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.docs(w.id, model.context) } }.collectAsState(null)
    val creating by remember(c?.id, w.id) { model.store.liveQuery({ it.table == StoreTable.WRITES }) {
        if (c == null) emptyList() else model.store.reader.workspaceWrites(c.id, w.id).filter { it.kind == WriteKind.DOC_CREATE }
    } }.collectAsState(emptyList())
    val drafts by remember(c?.id, w.id) { model.store.liveQuery({ it.table == StoreTable.DOC_DRAFTS }) {
        if (c == null) emptySet() else model.store.reader.docDraftIds(c.id, w.id)
    } }.collectAsState(emptySet())
    val channelNames = channelLabels(model)
    val actions = rememberWriteActions(model)
    val query = filter.trim()
    val visible = rows?.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) } ?: emptyList()
    GroupedSurface(Modifier.fillMaxSize()) {
        ListHeaderBar {
            FilterField(filter, { filter = it }, "Filter documents", Modifier.weight(1f), tag = "docs.filter")
            CreateDocument(model) { open -> IconButton(onClick = open, modifier = Modifier.testTag("docs.new")) {
                Icon(Icons.Filled.AddCircle, "New document", Modifier.size(28.dp), tint = Amb.colors.primary)
            } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            groupedSection(null, creating, key = { "creating:${it.id}" }) { item, first, last ->
                GroupedRow(first, last) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.string("title") ?: "Document", fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground)
                        WriteStatusLine(item, "New document", actions)
                    }
                }
            }
            item(key = "creating-gap") { if (creating.isNotEmpty() && visible.isNotEmpty()) Spacer(Modifier.height(12.dp)) }
            groupedSection(null, visible, key = { it.id }) { doc, first, last ->
                DocRow(doc, channelNames[doc.channelId], doc.id in drafts, first, last) { services.open(WorkRoute.Doc(w.id, doc.id)) }
            }
            if (rows != null && visible.isEmpty() && creating.isEmpty()) item(key = "empty") {
                EmptyNote(if (query.isEmpty()) "No documents" else "No matches", Icons.Outlined.Description,
                    if (query.isNotEmpty()) "No document title contains “$query”." else if (services.unlocked) "Documents in this scope appear here." else "Unlock to load documents.")
            }
        }
    }
}

@Composable private fun DocRow(doc: DocRecord, channel: String?, unsaved: Boolean, first: Boolean, last: Boolean, onOpen: () -> Unit) {
    val amb = Amb.colors
    GroupedRow(first, last, onClick = onOpen, chevron = true, tag = "docs.row") {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = amb.primary)
                Text(doc.title, fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val caption = listOfNotNull(channel?.let { "#$it" }, ChatTime.label(doc.updatedAt).takeIf { it.isNotBlank() }).joinToString(" · ")
                if (caption.isNotEmpty()) Text(caption, fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (unsaved) Row(Modifier.testTag("docs.unsaved"), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EditNote, null, Modifier.size(14.dp), tint = amb.warning)
                    Text("Unsaved edits", fontSize = AmbType.caption, color = amb.warning, maxLines = 1)
                }
            }
            if (doc.accessRemoved) AccessRemovedNote()
        }
    }
}

/** Queued-write actions for the status lines in the lists (retry, apply mine, discard). */
@Composable internal fun rememberWriteActions(model: AppModel): WriteActions = remember(model) {
    object : WriteActions {
        override fun retryWrite(item: WriteItem) { model.connection?.let { c -> model.act { model.writes.retry(item.id, c) } } }
        override fun applyMine(item: WriteItem) { model.connection?.let { c -> model.act { model.writes.retry(item.id, c, force = true) } } }
        override fun discardWrite(item: WriteItem) { model.act { model.store.writer.discardWrite(item.id) } }
    }
}

/** Toolbox: one grouped section of WApps (icon, title, description, origin), with the explanation as its footer (iOS `ToolboxView`). */
@Composable internal fun ConnectedToolbox(model: AppModel) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = LocalAppServices.current
    val rows by remember(w.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.personalWapps(c.id, w.id) } }.collectAsState(null)
    LaunchedEffect(w.id) { model.sync.refreshToolbox(c, w) }
    val footer = "WApps are web apps their publishers own. They open as tabs in the Ambulando browser, where a WApp must ask before it can see your Nostr key and you approve each signature. " +
        "Adding, editing and placing WApps is done in the web app."
    GroupedSurface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            groupedSection(null, rows ?: emptyList(), key = { it.id }, footer = footer) { app, first, last ->
                val url = app.safeLaunchURL
                GroupedRow(first, last, onClick = url?.let { u -> { services.openUrl(u.toString()) } }, enabled = url != null, alpha = if (url != null) 1f else 0.6f, tag = "toolbox.app") {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Widgets, null, Modifier.size(22.dp), tint = Amb.colors.primary) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(app.title, fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = Amb.colors.foreground)
                        app.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = AmbType.caption, color = Amb.colors.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(if (url != null) "Web content · ${studio.otherstuff.ambulando.core.browser.WebContentPolicy.origin(url)}" else "Not opened: its address is not HTTPS",
                            fontSize = AmbType.caption2, color = Amb.colors.mutedForeground)
                    }
                }
            }
            if (rows != null && rows!!.isEmpty()) item(key = "empty") {
                EmptyNote("No WApps", Icons.Outlined.Handyman, if (services.unlocked) "WApps you add in the web app appear here." else "Unlock to load your Toolbox.")
                GroupedFooter(footer)
            }
        }
    }
}

/** Agents: who is installed and whether they are working, then activity grouped Working now / Not responding / Recently finished (iOS `AgentsView`). */
@Composable internal fun ConnectedAgents(model: AppModel) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    val amb = Amb.colors
    val ids = model.channels.filter { model.context.scopeId == null || it.scopeId == model.context.scopeId }.filter { model.context.channelId == null || it.id == model.context.channelId }.map { it.id }
    val rows by remember(w.id) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.workspaceAgents(w.id) } }.collectAsState(null)
    val activity by remember(w.id, model.context) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.agentActivities(w.id, ids) } }.collectAsState(emptyList())
    LaunchedEffect(w.id, model.context) { model.sync.refreshAgents(model.connection!!, w, ids) }
    val channelNames = channelLabels(model)
    val live = activity.filter { !it.isTerminal && it.leaseHealth == "live" }
    val stale = activity.filter { !it.isTerminal && it.leaseHealth != "live" }
    val recent = activity.filter { it.isTerminal }.take(30)
    val working = live.map { it.agentNpub }.toSet()
    fun agentName(npub: String) = rows?.firstOrNull { it.agentNpub == npub }?.displayName ?: (npub.take(12) + "…")
    val open: (AgentActivityRecord) -> Unit = { item -> item.threadId?.let { services.open(WorkRoute.Thread(w.id, item.channelId, it)) } }
    GroupedSurface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "h:agents") { GroupedHeader("Agents in this workspace") }
            val agents = rows
            if (agents.isNullOrEmpty()) item(key = "agents-empty") {
                Text(if (agents == null) "Loading…" else "No agents are installed in this workspace.", fontSize = AmbType.footnote, color = amb.mutedForeground,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp))
            } else agents.forEachIndexed { index, agent ->
                item(key = "agent:${agent.id}") {
                    GroupedRow(index == 0, index == agents.lastIndex, tag = "agents.row") {
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp), tint = amb.primary) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(agent.displayName, fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = amb.foreground)
                            Text((listOf(agent.agentNpub.take(12) + "…") + agent.capabilities.take(3)).joinToString(" · "), fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val busy = agent.agentNpub in working
                        Text(if (busy) "Working" else "Idle", fontSize = AmbType.caption, fontWeight = FontWeight.SemiBold, color = if (busy) Amb.brandOrange else amb.mutedForeground)
                    }
                }
            }
            activityGroup("Working now", live, null, ::agentName, channelNames, open)
            activityGroup("Not responding", stale, "These agents stopped reporting before finishing.", ::agentName, channelNames, open)
            activityGroup("Recently finished", recent, null, ::agentName, channelNames, open)
            item(key = "h:unavailable") { GroupedHeader("Not available on this device") }
            item(key = "unavailable") {
                GroupedRow(first = true, last = true) {
                    Icon(Icons.Outlined.AltRoute, null, Modifier.size(18.dp), tint = amb.mutedForeground)
                    Text("Pipeline runs and session control are not implemented in this native app. Agent setup uses advertised HTTPS.", fontSize = AmbType.footnote, color = amb.mutedForeground)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.activityGroup(
    header: String, items: List<AgentActivityRecord>, footer: String?, name: (String) -> String, channels: Map<String, String>, open: (AgentActivityRecord) -> Unit,
) = groupedSection(header, items, key = { "activity:${it.id}" }, footer = footer) { item, first, last ->
    val amb = Amb.colors
    GroupedRow(first, last, onClick = if (item.threadId != null) ({ open(item) }) else null, tag = "agents.activity") {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name(item.agentNpub), fontSize = AmbType.subheadline, fontWeight = FontWeight.SemiBold, color = amb.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(activityState(item), fontSize = AmbType.caption, fontWeight = FontWeight.SemiBold, color = activityColor(item))
                Spacer(Modifier.weight(1f))
                Text(ChatTime.label(item.updatedAt), fontSize = AmbType.caption, color = amb.mutedForeground, maxLines = 1)
            }
            Text(item.label ?: item.summary ?: "Agent activity", fontSize = AmbType.footnote, color = amb.foreground, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("#" + (channels[item.channelId] ?: "channel"), fontSize = AmbType.caption, color = amb.mutedForeground)
        }
    }
}

private fun activityState(item: AgentActivityRecord): String =
    if (item.state == "queued" && item.queuePosition != null) "Queued #${item.queuePosition}"
    else item.state.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable private fun activityColor(item: AgentActivityRecord): Color = when (item.state) {
    "failed", "cancelled" -> Amb.colors.warning
    "completed" -> Amb.colors.mutedForeground
    else -> Amb.brandOrange
}

/** Context: a scope picker, then the scope's components as an outline with its linked records (iOS `ContextTreeView`). */
@Composable internal fun ConnectedContext(model: AppModel) {
    val w = model.workspace ?: return
    val amb = Amb.colors
    val s = model.context.scopeId
    if (s == null) {
        GroupedSurface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "h") { GroupedHeader("Each scope has its own Context Tree. Choose one.") }
                val scopes = model.scopes.filter { !it.accessRemoved }
                scopes.forEachIndexed { index, scope ->
                    item(key = scope.id) {
                        GroupedRow(index == 0, index == scopes.lastIndex, onClick = { model.selectContext(scope.id, null) }, chevron = true, tag = "context.scope") {
                            Icon(Icons.Outlined.AccountTree, null, Modifier.size(20.dp), tint = amb.primary)
                            Text(scope.name, fontSize = AmbType.body, color = amb.foreground)
                        }
                    }
                }
            }
        }
        return
    }
    val rows by remember(w.id, s) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.contextTree(w.id, s) } }.collectAsState(null)
    LaunchedEffect(w.id, s) { model.sync.refreshContextTree(model.connection!!, w, s) }
    var filter by remember(s) { mutableStateOf("") }
    val tree = rows?.let { filterContext(it, filter.trim()) } ?: emptyList()
    GroupedSurface(Modifier.fillMaxSize()) {
        ListHeaderBar { FilterField(filter, { filter = it }, "Find component", Modifier.weight(1f), tag = "context.filter") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (tree.isNotEmpty()) {
                item(key = "tree") { GroupedCard { tree.forEachIndexed { i, node -> ContextBranch(model, node, 0, filter.isNotBlank(), i == tree.lastIndex) } } }
                item(key = "footer") { GroupedFooter("Creating, renaming, moving and linking components is done in the web app for now.") }
            } else if (rows != null) item(key = "empty") {
                EmptyNote(if (filter.isBlank()) "No components" else "No matches", Icons.Outlined.AccountTree,
                    if (filter.isBlank()) "This scope's Context Tree is empty." else "No component title contains “${filter.trim()}”.")
            }
        }
    }
}

/** Keeps matching components with their ancestors (iOS `filtered`); an empty query keeps everything. */
private fun filterContext(nodes: List<ContextNode>, query: String): List<ContextNode> {
    if (query.isEmpty()) return nodes
    return nodes.mapNotNull { node ->
        val kids = filterContext(node.children, query)
        if (node.component.title.contains(query, ignoreCase = true)) node.copy(children = if (kids.isEmpty()) node.children else kids)
        else if (kids.isNotEmpty()) node.copy(children = kids) else null
    }
}

@Composable private fun ContextBranch(model: AppModel, node: ContextNode, depth: Int, forceOpen: Boolean, last: Boolean) {
    val services = LocalAppServices.current
    val amb = Amb.colors
    var expanded by remember(node.id) { mutableStateOf(false) }
    val references by remember(node.id) { model.store.liveQuery({ it.workspaceId == node.component.workspaceId }) { model.store.reader.contextReferences(node.component.workspaceId, node.id) } }.collectAsState(emptyList())
    Row(
        Modifier.fillMaxWidth().heightIn(min = Amb.touchTarget).testTag("context.node")
            .clickable { expanded = !expanded; if (expanded && !node.accessRemoved) model.act { model.sync.refreshContextReferences(model.connection!!, model.workspace!!, node.component.scopeId, node.id) } }
            .padding(start = (16 + depth * 20).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (node.children.isEmpty()) Icons.Outlined.CropSquare else Icons.Outlined.Layers, null, Modifier.size(20.dp), tint = amb.primary)
        Text(node.component.title, fontSize = AmbType.body, color = amb.foreground, modifier = Modifier.weight(1f))
        if (node.children.isNotEmpty() || references.isNotEmpty()) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = amb.mutedForeground)
    }
    if (node.accessRemoved) AccessRemovedNote(Modifier.padding(start = (46 + depth * 20).dp, bottom = 6.dp))
    if (expanded) references.forEach { ref -> ContextReference(ref, depth + 1) {
        val w = node.component.workspaceId
        when (ref.targetType) {
            ContextReferenceRecord.TargetType.TASK -> ref.targetRecordId?.let { services.open(WorkRoute.Task(w, it)) }
            ContextReferenceRecord.TargetType.DOC -> ref.targetRecordId?.let { services.open(WorkRoute.Doc(w, it)) }
            ContextReferenceRecord.TargetType.FILE -> ref.targetRecordId?.let { services.open(WorkRoute.File(w, it)) }
            ContextReferenceRecord.TargetType.ARTIFACT -> ref.artifact?.takeIf { it.isHTTPS }?.let { services.openUrl(it.latestURL) }
            null -> Unit
        }
    } }
    node.children.forEachIndexed { i, child -> ContextBranch(model, child, depth + 1, forceOpen, last && i == node.children.lastIndex) }
    if (!(last && depth == 0)) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = (46 + depth * 20).dp), thickness = 0.5.dp, color = amb.border)
}

@Composable private fun ContextReference(ref: ContextReferenceRecord, depth: Int, onOpen: () -> Unit) {
    val amb = Amb.colors
    val available = ref.resolution != ContextReferenceRecord.Resolution.UNAVAILABLE
    val icon = when (ref.targetType) {
        ContextReferenceRecord.TargetType.TASK -> Icons.Outlined.Checklist
        ContextReferenceRecord.TargetType.DOC -> Icons.Outlined.Description
        ContextReferenceRecord.TargetType.FILE -> Icons.Outlined.AttachFile
        ContextReferenceRecord.TargetType.ARTIFACT -> Icons.Outlined.Public
        null -> Icons.Outlined.HelpOutline
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Amb.touchTarget).then(if (available) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = (16 + depth * 20).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (available) icon else Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = if (available) amb.mutedForeground else amb.border)
        Text(if (available) ref.resolvedTitle ?: "Untitled" else "Not available to you", fontSize = AmbType.subheadline, color = if (available) amb.foreground else amb.mutedForeground)
    }
}
