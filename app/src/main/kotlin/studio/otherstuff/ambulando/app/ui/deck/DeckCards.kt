package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.core.deck.DeckActivity
import studio.otherstuff.ambulando.core.store.LocalStore

@Composable private fun ActivityRow(title: String, subtitle: String?, removed: Boolean = false, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = open).deckRow(studio.otherstuff.ambulando.app.ui.theme.Amb.colors)) {
        Text(title)
        subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = studio.otherstuff.ambulando.app.ui.theme.Amb.colors.mutedForeground, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        if (removed) AccessRemovedNote()
    }
}

@Composable internal fun DeckHelloCard(store: LocalStore, screen: ScreenScope, navigation: WorkNavigation, intents: DeckIntents, status: SurfaceStatusLookup, visible: Boolean) {
    val query by rememberPausableQuery(DeckLaunchers(), store, visible, "hello:${screen.workspaceId}", DeckHello.matches(screen.workspaceId), { intents.refreshLaunchers() }) {
        DeckHello.load(store, screen.connectionId, screen.workspaceId, screen.signerNpub)
    }
    DeckCardFrame("Hello and Links", "hello") {
        item { Text("Hello${query.value.name?.let { ", $it" } ?: ""}"); DeckNote(navigation.contextLabel()) }
        query.error?.let { item { DeckNote(it) } }
        items(query.value.apps, key = { it.id }) { app ->
            ActivityRow(app.title, app.description) { app.safeLaunchURL?.let { intents.launchApp(it.toString()) } }
        }
        items(query.value.agents, key = { "agent:${it.npub}" }) { agent -> ActivityRow(query.value.people?.name(agent.npub) ?: agent.npub.take(18), "Personal agent") { agent.safeURL?.let { intents.launchApp(it.toString()) } } }
        item { TextButton(onClick = intents::showToolbox) { Text("Manage apps") } }
    }
}

@Composable internal fun DeckInboxCard(store: LocalStore, screen: ScreenScope, navigation: WorkNavigation, pins: PinsSnapshot, intents: DeckIntents, status: SurfaceStatusLookup, visible: Boolean) {
    val services = LocalAppServices.current
    val query by rememberPausableQuery(DeckInboxSnapshot(), store, visible, "inbox:$screen", DeckInbox.matches(screen.workspaceId), { intents.refreshInbox(navigation.context) }) {
        DeckInbox.load(store, screen.workspaceId, screen.signerNpub, navigation.context, navigation.context.scopeId?.let { navigation.channels().map { it.id } })
    }
    var filter by remember { mutableStateOf<DeckActivity.Kind?>(null) }
    var search by remember { mutableStateOf("") }
    DeckCardFrame("Inbox", "inbox", actions = { TextButton(onClick = { intents.markRead(query.value.viewStates.values.filter { it.unread }) }) { Text("Mark read") } }) {
        item { OutlinedTextField(search, { search = it }, label = { Text("Filter activity") }) }
        item { androidx.compose.foundation.layout.Row { TextButton(onClick = { filter = null }) { Text("All") }; DeckActivity.Kind.entries.forEach { kind -> TextButton(onClick = { filter = kind }) { Text(kind.wire) } } } }
        query.error?.let { item { DeckNote(it) } }
        if (query.loaded && query.value.items.isEmpty()) item { DeckNote("No activity in this context") }
        items(query.value.items.filter { (filter == null || it.kind == filter) && (search.isBlank() || it.searchText.contains(search.lowercase())) }, key = { it.id }) { row ->
            ActivityRow((if (row.isUnread) "● " else "") + row.title, row.subtitle, row.id in query.value.removed) {
                val w = screen.workspaceId
                services.open(when (row.kind) {
                    DeckActivity.Kind.CHAT -> if (row.isThread) WorkRoute.Thread(w, row.channelId, row.recordId) else WorkRoute.Channel(w, row.channelId ?: row.recordId)
                    DeckActivity.Kind.TASK -> WorkRoute.Task(w, row.recordId)
                    DeckActivity.Kind.DOCUMENT -> WorkRoute.Doc(w, row.recordId)
                    DeckActivity.Kind.FILE -> WorkRoute.File(w, row.recordId)
                })
            }
        }
    }
}

@Composable internal fun DeckPinnedCard(screen: ScreenScope, pins: PinsSnapshot, intents: DeckIntents) {
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf(false) }
    var preview by remember(pins.visibleItems) { mutableStateOf(pins.visibleItems) }
    val currentRows by rememberUpdatedState(preview)
    val enabled = !moving && pins.orderWrite == null && pins.pending.isEmpty()
    fun save(target: studio.otherstuff.ambulando.core.store.PinnedItem, before: studio.otherstuff.ambulando.core.store.PinnedItem?) {
        moving = true
        scope.launch {
            intents.movePin(target.pin, before?.pin).onFailure { error = it.message }
            moving = false
            preview = pins.visibleItems
        }
    }
    DeckCardFrame("Pinned items", "pinned") {
        error?.let { item { DeckNote(it) } }
        pins.orderWrite?.let { write -> item {
            if (write.isPending) DeckNote("Saving pin order…") else {
                DeckNote(write.lastError ?: "Pin order not saved")
                TextButton(onClick = { intents.discardPinWrite(write.id) }) { Text("Discard move") }
            }
        } }
        items(preview, key = { it.id }) { row ->
            var distance by remember(row.id) { mutableFloatStateOf(0f) }
            var height by remember(row.id) { mutableIntStateOf(64) }
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().graphicsLayer { translationY = distance }.onSizeChanged { height = it.height }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Outlined.DragHandle, "Drag to reorder ${row.title ?: "pin"}",
                    Modifier.padding(12.dp).size(24.dp).testTag("pin.drag.${row.pin.resourceId}")
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Move up") {
                                    val index = currentRows.indexOfFirst { it.id == row.id }
                                    if (enabled && index > 0) { save(row, currentRows[index - 1]); true } else false
                                },
                                CustomAccessibilityAction("Move down") {
                                    val index = currentRows.indexOfFirst { it.id == row.id }
                                    if (enabled && index >= 0 && index < currentRows.lastIndex) { save(row, currentRows.getOrNull(index + 2)); true } else false
                                })
                        }
                        .pointerInput(row.id, enabled) {
                            if (enabled) detectDragGestures(
                                onDragStart = { distance = 0f },
                                onDragCancel = { preview = pins.visibleItems; distance = 0f },
                                onDragEnd = {
                                    val index = currentRows.indexOfFirst { it.id == row.id }
                                    val destination = (index + (distance / height.coerceAtLeast(1)).roundToInt()).coerceIn(0, currentRows.lastIndex)
                                    if (index >= 0 && destination != index) {
                                        val reordered = currentRows.toMutableList().apply { add(destination, removeAt(index)) }
                                        preview = reordered
                                        save(row, reordered.getOrNull(destination + 1))
                                    }
                                    distance = 0f
                                },
                                onDrag = { change, drag -> change.consume(); distance += drag.y })
                        })
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    ActivityRow(row.title ?: "Pinned record", row.pin.resourceType.wire, row.accessRemoved) {
                        val id = row.pin.resourceId
                        when (row.pin.resourceType.wire) {
                            "task" -> services.open(WorkRoute.Task(screen.workspaceId, id))
                            "document" -> services.open(WorkRoute.Doc(screen.workspaceId, id))
                            "thread" -> services.open(WorkRoute.Thread(screen.workspaceId, row.channelId, id))
                            "channel" -> intents.openInChat(id)
                        }
                    }
                }
                // Also works with a keyboard and switch access; drag never opens or unpins a row.
                androidx.compose.material3.IconButton(enabled = enabled && preview.indexOf(row) > 0, onClick = { save(row, preview[preview.indexOf(row) - 1]) }, modifier = Modifier.testTag("pin.up.${row.pin.resourceId}")) {
                    androidx.compose.material3.Icon(Icons.Outlined.KeyboardArrowUp, "Move up")
                }
                androidx.compose.material3.IconButton(enabled = enabled && preview.indexOf(row) < preview.lastIndex, onClick = { save(row, preview.getOrNull(preview.indexOf(row) + 2)) }, modifier = Modifier.testTag("pin.down.${row.pin.resourceId}")) {
                    androidx.compose.material3.Icon(Icons.Outlined.KeyboardArrowDown, "Move down")
                }
            }
        }
    }
}

@Composable internal fun DeckCanvasCard(store: LocalStore, screen: ScreenScope, intents: DeckIntents, status: SurfaceStatusLookup, visible: Boolean) {
    val services = LocalAppServices.current
    val query by rememberPausableQuery(DeckCanvasSnapshot(), store, visible, "canvases:${screen.workspaceId}", DeckCanvases.matches(screen.workspaceId), { intents.refreshCanvases() }) { DeckCanvases.load(store, screen.workspaceId) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New canvas") }, text = { OutlinedTextField(title, { title = it }) }, confirmButton = {
        TextButton(enabled = title.isNotBlank(), onClick = { scope.launch { intents.createCanvas(title).onSuccess { creating = false; services.open(WorkRoute.Canvas(screen.workspaceId, it)) }.onFailure { error = it.message } } }) { Text("Create") }
    }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    DeckCardFrame("Canvas", "canvas", actions = { TextButton(onClick = { creating = true }) { Text("New") } }) {
        error?.let { item { DeckNote(it) } }
        query.error?.let { item { DeckNote(it) } }
        if (query.loaded && query.value.canvases.isEmpty()) item { DeckNote("No canvases yet") }
        items(query.value.canvases, key = { it.id }) { row -> ActivityRow(row.title, null, row.id in query.value.removed) { services.open(WorkRoute.Canvas(screen.workspaceId, row.id)) } }
    }
}

@Composable internal fun DeckFeedCard() {
    DeckCardFrame("Feed", "feed") { item { DeckNote("Feed is not available in the native app.") } }
}

@Composable internal fun DeckRecentCard(store: LocalStore, screen: ScreenScope, navigation: WorkNavigation, intents: DeckIntents, visible: Boolean) {
    val query by rememberPausableQuery(emptyList<DeckActivity.ThreadActivity>(), store, visible, "recent:$screen", DeckRecent.matches(screen.workspaceId)) { DeckRecent.load(store, screen.workspaceId, screen.scopeId) }
    DeckCardFrame("Recent channels", "recent") {
        query.error?.let { item { DeckNote(it) } }
        items(query.value, key = { it.channelId }) { row -> ActivityRow(navigation.labels[row.channelId] ?: row.title ?: "Conversation", row.latestBody) { intents.openInChat(row.channelId, row.threadId) } }
    }
}
