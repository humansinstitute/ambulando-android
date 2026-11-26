package studio.otherstuff.ambulando.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.deck.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.chat.ChatTime
import studio.otherstuff.ambulando.core.context.*
import studio.otherstuff.ambulando.core.chat.MentionCodec
import studio.otherstuff.ambulando.core.chat.catchUpChannel
import studio.otherstuff.ambulando.core.store.*

/** Chat's root is a conversation inbox; channel deep links keep their existing message view. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ConversationInbox(model: AppModel) {
    val w = model.workspace ?: return
    val c = model.connection ?: return
    val store = model.store
    val syncService = model.sync
    val outbox = model.outbox
    val services = LocalAppServices.current
    val labels = channelLabels(model)
    val channels = model.channels.filter { !w.accessRemoved && !it.accessRemoved &&
        model.scopes.any { s -> s.id == it.scopeId && !s.accessRemoved } &&
        (model.context.scopeId == null || it.scopeId == model.context.scopeId) &&
        (model.context.channelId == null || it.id == model.context.channelId) }
    val ids = channels.map { it.id }.toSet()
    var query by rememberSaveable(c.id, w.id) { mutableStateOf("") }
    var filter by rememberSaveable(c.id, w.id) { mutableStateOf(ConversationFilter.ALL) }
    var composing by rememberSaveable(c.id, w.id) { mutableStateOf(false) }
    val parsed = remember(query) { ConversationQuery.parse(query) }
    val rows by key(c.id, w.id, ids, query, filter) {
        remember(store, c.id, w.id, ids, query, filter) { store.liveQuery({ it.workspaceId == w.id && it.table in ConversationInvalidation.rows }) {
            store.reader.conversations(w.id, ids, c.signerNpub, query, filter)
        } }.collectAsState(null)
    }
    val people by remember(store, c.id, w.id) { store.liveQuery({ it.workspaceId == w.id || it.table == StoreTable.PROFILES }) {
        store.reader.people(c.id, w.id)
    } }.collectAsState(studio.otherstuff.ambulando.core.profiles.PeopleDirectory())
    var coverageDetails by remember { mutableStateOf(false) }
    val coverage by remember(w.id, ids) { store.liveQuery({ it.workspaceId == w.id && it.table in ConversationInvalidation.coverage }) {
        ids.count { store.reader.channelSync(w.id, it)?.complete == true } to store.reader.cachedConversationMessageCount(w.id, ids)
    } }.collectAsState(0 to 0L)
    val sync by model.engine.status.collectAsState()
    val historyKeys = ids.map { studio.otherstuff.ambulando.core.sync.WorkspaceSync.messagesKey(w.id, it) }
    val downloading = historyKeys.any { key -> (sync.running + sync.queued).any { it.endsWith(key) } }
    val historyError = historyKeys.any { key -> sync.errors.keys.any { it.endsWith(key) } }
    Column(Modifier.fillMaxSize().testTag("chat.inbox")) {
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search conversations") },
            supportingText = { Text(parsed.error ?: "Words, \"phrase\", title: or body:") }, isError = parsed.error != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("chat.inbox.search"))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConversationFilter.entries.forEach { item ->
                FilterChip(filter == item, { filter = item }, label = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.testTag("chat.inbox.${item.name.lowercase()}"))
            }
            TextButton(onClick = { composing = true }, enabled = channels.isNotEmpty(), modifier = Modifier.testTag("chat.inbox.new")) { Text("New thread") }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Search downloaded history · " + if (historyError) "download failed" else if (coverage.first < ids.size) "incomplete" else "cached",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).testTag("chat.inbox.coverage"))
            IconButton(onClick = { coverageDetails = true }, modifier = Modifier.testTag("chat.inbox.coverage.details")) { Icon(Icons.Outlined.Info, "Downloaded history details") }
            TextButton(enabled = !downloading && channels.isNotEmpty(), onClick = {
                model.act { channels.forEach { syncService.catchUpChannel(c, w, it.id, force = true) } }
            }, modifier = Modifier.testTag("chat.inbox.download")) { Text(if (downloading) "Downloading…" else if (historyError) "Retry" else if (coverage.first == ids.size) "Update" else "Download") }
        }
        if (rows == null) Text("Loading conversations…", Modifier.padding(16.dp))
        else if (rows!!.isEmpty()) Text(if (parsed.error != null) "Correct the search above." else "No conversations in this view.", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).testTag("chat.inbox.rows"), state = rememberLazyListState()) {
            items(rows.orEmpty(), key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth().clickable {
                    services.open(row.threadId?.let { WorkRoute.Thread(w.id, row.channelId, it, row.hitMessageId) } ?: WorkRoute.Channel(w.id, row.channelId))
                }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("chat.inbox.row.${row.id}")) {
                    Row {
                        Text(MentionCodec.plain(row.title).ifBlank { "Conversation" }, fontWeight = if (row.unread) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 2, modifier = Modifier.weight(1f))
                        Text(ChatTime.label(row.latestAt), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.participantNpubs.take(3).forEach { npub -> PersonAvatar(people.person(npub), size = 20.dp) }
                        Text("${labels[row.channelId] ?: "Channel"} · ${row.participants.joinToString(", ")}", maxLines = 1, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("${row.replyCount} " + if (row.replyCount == 1) "reply" else "replies", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(highlightedExcerpt(MentionCodec.plain(row.excerpt)), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
    if (coverageDetails) AlertDialog(onDismissRequest = { coverageDetails = false }, title = { Text("Downloaded history") },
        text = { Text("${coverage.second} messages cached. ${coverage.first} of ${ids.size} selected channel histories have completed a download. Search works offline over content on this device. Download or update history to include older messages and recent changes; messages inherited from another thread become searchable after that thread is opened.") },
        confirmButton = { TextButton(onClick = { coverageDetails = false }) { Text("OK") } })
    if (composing) {
        val navigation = WorkNavigation(model.scopes.filter { !it.accessRemoved }.map { s -> SidebarGroup(NavScope(s.id, s.name, s.kind),
            channels.filter { it.scopeId == s.id }.map { NavChannel(it.id, it.scopeId, it.name, it.kind, it.position, it.raw) },
            SidebarNavigation.isDirectMessageScope(NavScope(s.id, s.name, s.kind))) }, labels, model.context)
        DeckNewThread(store, c.id to w.id, ScreenScope(c.id, w.id, w.appNpub, c.signerNpub, model.context.scopeId, model.context.channelId), navigation,
            onClose = { composing = false }, onRetry = { model.act { outbox.retry(it, c) } },
            onCreated = { channel, thread -> services.open(WorkRoute.Thread(w.id, channel, thread)) },
            composer = { channel, enqueued -> ConnectedChat(model, null, ComposeOnly(channel, enqueued)) })
    }
}

@Composable private fun highlightedExcerpt(text: String): androidx.compose.ui.text.AnnotatedString {
    val highlightBackground = MaterialTheme.colorScheme.secondaryContainer
    return buildAnnotatedString {
        var highlighted = false
        for (ch in text) when (ch) {
            '⟦' -> { highlighted = true; pushStyle(SpanStyle(background = highlightBackground, fontWeight = FontWeight.Bold)) }
            '⟧' -> if (highlighted) { pop(); highlighted = false }
            else -> append(ch)
        }
        if (highlighted) pop()
    }
}
