package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.*
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
    val bounds = remember { mutableMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var beforeId by remember { mutableStateOf<String?>(null) }
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    DeckCardFrame("Pinned items", "pinned") {
        error?.let { message -> item {
            DeckNote(message)
            TextButton(onClick = { error = null }) { Text("Dismiss") }
        } }
        pins.orderWrite?.takeIf { !it.isPending }?.let { write -> item {
            DeckNote(write.lastError ?: "Pin order not saved")
            TextButton(onClick = { intents.retryPinWrite(write.id) }) { Text("Retry move") }
            TextButton(onClick = { intents.discardPinWrite(write.id) }) { Text("Discard move") }
        } }
        items(preview, key = { it.id }) { row ->
            var distance by remember(row.id) { mutableFloatStateOf(0f) }
            val contentTop = remember(row.id) { floatArrayOf(0f) }
            fun moveBy(offset: Int): Boolean {
                val index = currentRows.indexOfFirst { it.id == row.id }
                val destination = (index + offset).coerceIn(0, currentRows.lastIndex)
                if (!enabled || index < 0 || index == destination) return false
                val reordered = currentRows.toMutableList().apply { add(destination, removeAt(index)) }
                save(row, reordered.getOrNull(destination + 1))
                return true
            }
            val isDragging = dragging == row.id
            val insertionAbove = dragging != null && beforeId == row.id
            val insertionBelow = dragging != null && beforeId == null && row.id == preview.lastOrNull()?.id
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().testTag("pin.row.${row.pin.resourceId}")
                    .onGloballyPositioned { bounds[row.id] = it.boundsInRoot() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .drawWithContent {
                        drawContent()
                        val y = if (insertionAbove) 0f else size.height
                        if (insertionAbove || insertionBelow) drawLine(amb.primary.copy(alpha = .65f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 2.dp.toPx())
                    }
                    .graphicsLayer { translationY = distance; alpha = if (isDragging) .8f else 1f }
                    .deckRow(amb),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.weight(1f).testTag("pin.drag.${row.pin.resourceId}")
                        .onGloballyPositioned { contentTop[0] = it.boundsInRoot().top }
                        .semantics {
                            customActions = if (!enabled) emptyList() else listOf(
                                CustomAccessibilityAction("Move up") { moveBy(-1) },
                                CustomAccessibilityAction("Move down") { moveBy(1) },
                                CustomAccessibilityAction("Move to start") { moveBy(-currentRows.size) },
                                CustomAccessibilityAction("Move to end") { moveBy(currentRows.size) },
                            )
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || !event.isAltPressed) false
                            else when (event.key) {
                                Key.DirectionUp -> moveBy(-1)
                                Key.DirectionDown -> moveBy(1)
                                Key.MoveHome -> moveBy(-currentRows.size)
                                Key.MoveEnd -> moveBy(currentRows.size)
                                else -> false
                            }
                        }
                        .pointerInput(row, enabled) {
                            if (enabled) {
                                var fingerY = 0f
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start ->
                                        distance = 0f
                                        fingerY = contentTop[0] + start.y
                                        dragging = row.id
                                        beforeId = currentRows.getOrNull(currentRows.indexOfFirst { it.id == row.id } + 1)?.id
                                    },
                                    onDragCancel = { dragging = null; distance = 0f },
                                    onDragEnd = {
                                        val anchor = currentRows.firstOrNull { it.id == beforeId }
                                        val reordered = currentRows.filter { it.id != row.id }.toMutableList()
                                        val destination = anchor?.let { reordered.indexOf(it) } ?: reordered.size
                                        reordered.add(destination, row)
                                        if (reordered != currentRows) { preview = reordered; save(row, anchor) }
                                        dragging = null
                                        distance = 0f
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        distance += drag.y
                                        fingerY += drag.y
                                        beforeId = currentRows.firstOrNull { candidate ->
                                            candidate.id != row.id && bounds[candidate.id]?.let { fingerY < it.center.y } == true
                                        }?.id
                                    },
                                )
                            }
                        }
                        .clickable {
                            val id = row.pin.resourceId
                            when (row.pin.resourceType.wire) {
                                "task" -> services.open(WorkRoute.Task(screen.workspaceId, id))
                                "document" -> services.open(WorkRoute.Doc(screen.workspaceId, id))
                                "thread" -> services.open(WorkRoute.Thread(screen.workspaceId, row.channelId, id))
                                "channel" -> intents.openInChat(id)
                            }
                        }.padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        when (row.pin.resourceType.wire) {
                            "task" -> Icons.Outlined.TaskAlt
                            "document" -> Icons.Outlined.Description
                            "channel" -> Icons.Outlined.Tag
                            else -> Icons.Outlined.ChatBubbleOutline
                        }, null, Modifier.size(20.dp), tint = amb.mutedForeground,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(row.title ?: "Pinned record", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (row.accessRemoved) AccessRemovedNote()
                    }
                }
                PinButton(row.pin.resourceType, row.pin.resourceId, row.title ?: "Pinned record", pins,
                    onSetPinned = { intents.setPinned(it, row.pin.resourceType, row.pin.resourceId) })
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
