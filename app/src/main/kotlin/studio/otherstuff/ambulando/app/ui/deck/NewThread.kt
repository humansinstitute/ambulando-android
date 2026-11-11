package studio.otherstuff.ambulando.app.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.chat.readingWidth
import studio.otherstuff.ambulando.app.ui.nav.ScreenScope
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.core.deck.DeckCompose
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.liveQuery
import studio.otherstuff.ambulando.core.store.outbox
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.OutboxState
import studio.otherstuff.ambulando.core.store.outboxItem
import studio.otherstuff.ambulando.core.store.sentMessage

/**
 * Deck's New thread (iOS `DeckThreadComposer`): choose a channel of the context Deck shows (preselected when the context
 * names one or has only one), write the first message, and open the thread Tower creates for it. The composer is the
 * channel's own, handed in as [composer] so pasting, mentions, attachments, drafts and the IME behave exactly as in Chat.
 *
 * The workspace and connection are those Deck showed when it opened ([opened]); if either changes underneath, the
 * composer closes instead of writing somewhere else. Once the first message is saved, the request id of exactly that send
 * is followed: the saved copy (or the failure the outbox records) is shown, and the thread is opened when Tower answers,
 * whether that takes a moment or a retry after an uncertain reply (the same `client_request_id` replays, so no duplicate).
 */
@Composable
internal fun DeckNewThread(
    store: LocalStore,
    /** The connection and workspace Deck showed when the composer opened. */
    opened: Pair<String, String>,
    current: ScreenScope,
    navigation: WorkNavigation,
    onClose: () -> Unit,
    onRetry: (String) -> Unit,
    onCreated: (channelId: String, threadId: String) -> Unit,
    composer: @Composable (channelId: String, onEnqueued: (String) -> Unit) -> Unit,
) {
    val amb = Amb.colors
    val bound = opened.first == current.connectionId && opened.second == current.workspaceId
    if (!bound) { LaunchedEffect(Unit) { onClose() }; return }
    val openedContext = remember { navigation.context }
    val eligible = navigation.channels().map { it.id }
    // Saved across a rotation or window resize (the activity is recreated): a started thread is still followed afterwards.
    var channelId by rememberSaveable { mutableStateOf(DeckCompose.initialChannel(navigation.context, eligible)) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var requestId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var from by remember { mutableStateOf(openedContext) }
    // A scope change while open asks again; a chosen channel within the same scope is kept. Nothing moves once a send is saved.
    LaunchedEffect(navigation.context, eligible) {
        if (requestId == null) channelId = DeckCompose.reconcile(channelId, from, navigation.context, eligible)
        from = navigation.context
    }
    val workspaceId = opened.second
    val recoverable by remember(channelId) {
        store.liveQuery({ it.table == StoreTable.OUTBOX }) {
            channelId?.let { store.reader.outbox(ChatTarget(opened.first, workspaceId, it)) } ?: emptyList()
        }
    }.collectAsState(emptyList())
    val sent by remember(requestId) { store.liveQuery({ it.workspaceId == workspaceId && it.table == StoreTable.MESSAGES }) { requestId?.let { store.reader.sentMessage(workspaceId, it) } } }.collectAsState(null)
    val pending by remember(requestId) { store.liveQuery({ it.workspaceId == workspaceId && it.table == StoreTable.OUTBOX }) { requestId?.let { store.reader.outboxItem(it) } } }.collectAsState(null)
    LaunchedEffect(sent, requestId) {
        val message = sent ?: return@LaunchedEffect
        val thread = message.threadId ?: return@LaunchedEffect
        if (openedRequest == requestId) return@LaunchedEffect
        openedRequest = requestId
        onCreated(message.channelId, thread)
        onClose()
    }
    BackHandler(onBack = onClose)
    // Covers Deck and swallows touches, so nothing underneath answers while the composer is open.
    Column(
        Modifier.fillMaxSize().background(amb.background).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.testTag("deck.compose"),
    ) {
        Row(Modifier.fillMaxWidth().background(amb.card).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New thread", Modifier.weight(1f).semantics { heading() }, fontSize = AmbType.body, fontWeight = FontWeight.SemiBold, color = amb.foreground)
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Amb.touchTarget).testTag("deck.compose.cancel")) { Text(if (requestId == null) "Cancel" else "Close") }
        }
        HorizontalDivider(thickness = 1.dp, color = amb.border)
        Column(Modifier.readingWidth().align(Alignment.CenterHorizontally).weight(1f).padding(top = 8.dp)) {
            val channels = navigation.channels()
            val selected = channels.firstOrNull { it.id == channelId }
            Row(
                Modifier.padding(horizontal = 16.dp).heightIn(min = Amb.touchTarget)
                    .clickable(enabled = requestId == null) { choosing = true }
                    .semantics { contentDescription = "Channel" }.testTag("deck.compose.channel"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(selected?.let { "#" + navigation.label(it) } ?: "Choose channel", fontSize = AmbType.body, color = amb.primary)
                Icon(Icons.Outlined.UnfoldMore, null, tint = amb.primary)
            }
            val id = channelId
            if (id != null && !choosing) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("New conversation", fontSize = AmbType.body, fontWeight = FontWeight.SemiBold, color = amb.foreground)
                    Text("Write the first message to start a thread.", fontSize = AmbType.subheadline, color = amb.mutedForeground)
                }
                val request = requestId
                if (request == null) {
                    recoverable.forEach { saved ->
                        TextButton(onClick = { requestId = saved.id }, modifier = Modifier.testTag("deck.compose.recover")) {
                            Text("Review saved send" + if (saved.attachments.any { it.mimeType.startsWith("audio/") }) " · recording" else "")
                        }
                    }
                }
                if (request == null) Box(Modifier.weight(1f).fillMaxWidth()) { key(id) { composer(id) { requestId = it } } }
                else Column {
                    pending?.attachments?.firstOrNull { it.mimeType.startsWith("audio/") }?.let {
                        Text("Saved recording", Modifier.padding(horizontal = 16.dp), color = amb.foreground)
                    }
                    Text(
                        pending?.lastError ?: "Starting thread… Your message is saved on this device.",
                        Modifier.padding(horizontal = 16.dp).testTag("deck.compose.pending"), fontSize = AmbType.subheadline, color = amb.mutedForeground,
                    )
                    if (pending?.state == OutboxState.FAILED || pending?.state == OutboxState.RETRYING) {
                        TextButton(onClick = { onRetry(request) }, modifier = Modifier.testTag("deck.compose.retry")) { Text("Retry saved send") }
                    }
                }
            } else {
                Text(
                    if (channels.isEmpty()) "No channels in this context." else "Choose a channel here to start your thread.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = AmbType.subheadline, color = amb.mutedForeground,
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(channels, key = { it.id }) { channel ->
                        Text(
                            "#" + navigation.label(channel),
                            Modifier.fillMaxWidth().heightIn(min = Amb.touchTarget).clickable { channelId = channel.id; choosing = false }
                                .padding(horizontal = 16.dp, vertical = 12.dp).testTag("deck.compose.option.${channel.id}"),
                            fontSize = AmbType.body, color = amb.foreground,
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = amb.border)
                    }
                }
            }
        }
    }
}
