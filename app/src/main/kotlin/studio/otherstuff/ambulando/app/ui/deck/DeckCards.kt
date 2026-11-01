package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.semantics.contentDescription
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

/** A Recent or Canvas row: leading icon, title and caption, optional time (iOS `DeckRecentCard` rows). */
@Composable private fun ActivityRow(title: String, subtitle: String?, removed: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, time: String? = null, iconTint: androidx.compose.ui.graphics.Color? = null, open: () -> Unit) {
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(onClick = open).tintedDeckRow(null, false), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        icon?.let { androidx.compose.material3.Icon(it, null, Modifier.size(20.dp), tint = iconTint ?: amb.mutedForeground); androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            DeckRowText(title, subtitle)
            if (removed) AccessRemovedNote()
        }
        time?.let { androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)); Text(it, fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.caption2, color = amb.mutedForeground) }
    }
}

@Composable internal fun DeckHelloCard(store: LocalStore, screen: ScreenScope, navigation: WorkNavigation, intents: DeckIntents, status: SurfaceStatusLookup, visible: Boolean) {
    val query by rememberPausableQuery(DeckLaunchers(), store, visible, "hello:${screen.workspaceId}", DeckHello.matches(screen.workspaceId), { intents.refreshLaunchers() }) {
        DeckHello.load(store, screen.connectionId, screen.workspaceId, screen.signerNpub)
    }
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    val type = studio.otherstuff.ambulando.app.ui.theme.AmbType
    val hour = remember { java.time.LocalTime.now().hour }
    val greeting = (if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening") + (query.value.name?.let { ", $it" } ?: "")
    DeckCardFrame("Hello and Links", "hello") {
        item {
            Column(Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                Text(greeting, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = amb.foreground)
                Text(navigation.contextLabel(), fontSize = type.subheadline, color = amb.mutedForeground)
            }
        }
        item { Text("Apps", Modifier.padding(horizontal = 4.dp), fontSize = type.footnote, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = amb.mutedForeground) }
        query.error?.let { item { DeckNote(it) } }
        items(query.value.apps, key = { it.id }) { app ->
            AppLauncherCard(app.title, app.description ?: "WApp", { LauncherLogo(app.logoCandidates, app.title, 44.dp) }) { app.safeLaunchURL?.let { intents.launchApp(it.toString()) } }
        }
        items(query.value.agents, key = { "agent:${it.npub}" }) { agent ->
            val name = query.value.people?.name(agent.npub) ?: agent.npub.take(18)
            AppLauncherCard(name, "Personal agent", { LauncherLogo(emptyList(), name, 44.dp) }) { agent.safeURL?.let { intents.launchApp(it.toString()) } }
        }
        item { TextButton(onClick = intents::showToolbox) { Text("Manage apps") } }
    }
}

@Composable internal fun DeckInboxCard(store: LocalStore, screen: ScreenScope, navigation: WorkNavigation, pins: PinsSnapshot, intents: DeckIntents, status: SurfaceStatusLookup, visible: Boolean, onNewThread: (() -> Unit)? = null) {
    val services = LocalAppServices.current
    val query by rememberPausableQuery(DeckInboxSnapshot(), store, visible, "inbox:$screen", DeckInbox.matches(screen.workspaceId), { intents.refreshInbox(navigation.context) }) {
        DeckInbox.load(store, screen.workspaceId, screen.signerNpub, navigation.context, navigation.context.scopeId?.let { navigation.channels().map { it.id } })
    }
    // Every record type shows until its toggle is turned off (iOS `kinds`).
    var kinds by remember { mutableStateOf(DeckActivity.Kind.entries.toSet()) }
    var search by remember { mutableStateOf("") }
    var readMenu by remember { mutableStateOf(false) }
    val channel = navigation.channel(navigation.context.channelId)
    val snapshot = query.value
    fun markAll(label: String, of: Set<DeckActivity.Kind>) {
        val states = snapshot.items.filter { it.isUnread && it.kind in of }.mapNotNull { snapshot.viewState(it) }
        if (states.isNotEmpty()) intents.markRead(states)
    }
    DeckCardFrame(channel?.let { "Inbox: #${navigation.label(it)}" } ?: "Inbox", "inbox", actions = {
        channel?.let { PinButton(studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.CHANNEL, it.id, "#" + navigation.label(it), pins, onSetPinned = { on -> intents.setPinned(on, studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.CHANNEL, it.id) }) }
        onNewThread?.let { open ->
            androidx.compose.material3.IconButton(onClick = open, modifier = Modifier.testTag("deck.inbox.new")) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, "New thread")
            }
        }
        androidx.compose.foundation.layout.Box {
            androidx.compose.material3.IconButton(onClick = { readMenu = true }, modifier = Modifier.testTag("deck.inbox.readMenu")) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.MoreHoriz, "Inbox options") }
            androidx.compose.material3.DropdownMenu(readMenu, { readMenu = false }) {
                listOf("Mark all tasks as read" to setOf(DeckActivity.Kind.TASK), "Mark all docs as read" to setOf(DeckActivity.Kind.DOCUMENT), "Mark all chats as read" to setOf(DeckActivity.Kind.CHAT),
                    "Mark everything as read" to setOf(DeckActivity.Kind.CHAT, DeckActivity.Kind.TASK, DeckActivity.Kind.DOCUMENT)).forEach { (label, of) ->
                    androidx.compose.material3.DropdownMenuItem({ Text(label) }, onClick = { readMenu = false; markAll(label, of) })
                }
            }
        }
    }) {
        item {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                DeckActivity.Kind.entries.forEach { kind -> DeckTypeToggle(kind, kind in kinds) { kinds = if (kind in kinds) kinds - kind else kinds + kind } }
                DeckSearchField(search, { search = it }, Modifier.weight(1f))
            }
        }
        query.error?.let { item { DeckNote(it) } }
        val shown = snapshot.items.filter { it.kind in kinds && (search.isBlank() || it.searchText.contains(search.trim().lowercase())) }
        if (shown.isEmpty()) item { DeckNote(when {
            kinds.isEmpty() -> "All record types are hidden. Turn on a type to show activity."
            !query.loaded -> "Loading activity…"
            search.isNotBlank() -> "No matches for “${search.trim()}”."
            else -> "No activity in this context"
        }) }
        items(shown, key = { it.id }) { row ->
            val state = snapshot.viewState(row)
            val pinType = when (row.kind) {
                DeckActivity.Kind.CHAT -> if (row.isThread) studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.THREAD else null
                DeckActivity.Kind.TASK -> studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.TASK
                DeckActivity.Kind.DOCUMENT -> studio.otherstuff.ambulando.core.surfaces.PinRecord.ResourceType.DOCUMENT
                DeckActivity.Kind.FILE -> null
            }
            DeckInboxRow(row, snapshot.channelLabels.ifEmpty { navigation.labels }, row.id in snapshot.removed, state != null,
                pin = pinType?.let { type -> { PinButton(type, row.recordId, row.title, pins, onSetPinned = { on -> intents.setPinned(on, type, row.recordId) }) } },
                onMarkDone = { intents.markDone(row.recordId) }.takeIf { row.kind == DeckActivity.Kind.TASK && row.taskState == "review" && row.isUnread },
                onMarkRead = state?.takeIf { row.isUnread }?.let { vs -> { intents.markRead(listOf(vs)) } },
            ) {
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
                        Text(row.title ?: unavailablePin(row.pin.resourceType.wire), fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.subheadline, color = if (row.title == null) amb.mutedForeground else amb.foreground, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (row.accessRemoved) AccessRemovedNote()
                    }
                    if (pins.isSaving(row.pin.resourceType, row.pin.resourceId)) androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(14.dp).semantics { contentDescription = "Saving pin" }, strokeWidth = 1.5.dp)
                }
                PinButton(row.pin.resourceType, row.pin.resourceId, row.title ?: unavailablePin(row.pin.resourceType.wire), pins,
                    onSetPinned = { intents.setPinned(it, row.pin.resourceType, row.pin.resourceId) })
            }
        }
    }
}

/** What a pin shows when its record is not stored on this device (iOS `unavailable`). */
private fun unavailablePin(type: String): String = when (type) {
    "thread" -> "Conversation not on this device"
    "task" -> "Task not on this device"
    "document" -> "Document not on this device"
    else -> "Channel not available"
}

@Composable internal fun DeckCanvasCard(store: LocalStore, screen: ScreenScope, intents: DeckIntents, status: SurfaceStatusLookup, visible: Boolean) {
    val services = LocalAppServices.current
    val query by rememberPausableQuery(DeckCanvasSnapshot(), store, visible, "canvases:${screen.workspaceId}", DeckCanvases.matches(screen.workspaceId), { intents.refreshCanvases() }) { DeckCanvases.load(store, screen.workspaceId) }
    var creating by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    // The iOS alert: a title field and "Everyone in the workspace can see it."; a failure is its own "Canvas not created" notice.
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New canvas") }, text = {
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Text("Everyone in the workspace can see it.")
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.testTag("canvas.new.title"))
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && !working, modifier = Modifier.testTag("canvas.new.create"), onClick = {
            creating = false; working = true
            scope.launch { intents.createCanvas(title).onSuccess { services.open(WorkRoute.Canvas(screen.workspaceId, it)) }.onFailure { error = it.message ?: "Canvas not created" }; working = false }
        }) { Text("Create") }
    }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    DeckCardFrame("Canvas", "canvas", actions = {
        androidx.compose.material3.IconButton(onClick = { title = ""; creating = true }, enabled = !working, modifier = Modifier.testTag("canvas.new")) {
            if (working) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, "New canvas")
        }
    }) {
        error?.let { item { DeckNote("Canvas not created: $it") } }
        query.error?.let { item { DeckNote(it) } }
        if (query.loaded && query.value.canvases.isEmpty()) item { DeckNote("No canvases yet.") }
        items(query.value.canvases, key = { it.id }) { row ->
            ActivityRow(
                row.title, if (row.visibilityMode == "workspace") "Everyone in the workspace" else "Shared with selected people", row.id in query.value.removed,
                androidx.compose.material.icons.Icons.Outlined.Dashboard, time = row.updatedAt.takeIf { it.isNotEmpty() }?.let { studio.otherstuff.ambulando.app.ui.chat.ChatTime.label(it) },
                iconTint = amb.primary,
            ) { services.open(WorkRoute.Canvas(screen.workspaceId, row.id)) }
        }
        item { DeckNote("Create a canvas with +. Sharing settings are changed in the web app.") }
    }
}

@Composable internal fun DeckFeedCard() {
    DeckCardFrame("Feed", "feed") { item { DeckNote("Feed is not available in the native app.") } }
}

@Composable internal fun DeckRecentCard(store: LocalStore, screen: ScreenScope, navigation: WorkNavigation, intents: DeckIntents, visible: Boolean) {
    val query by rememberPausableQuery(emptyList<DeckActivity.ThreadActivity>(), store, visible, "recent:$screen", DeckRecent.matches(screen.workspaceId)) { DeckRecent.load(store, screen.workspaceId, screen.scopeId) }
    DeckCardFrame("Recent channels", "recent", actions = { Text("Latest activity", fontSize = studio.otherstuff.ambulando.app.ui.theme.AmbType.caption, color = studio.otherstuff.ambulando.app.ui.theme.Amb.colors.mutedForeground, modifier = Modifier.padding(end = 8.dp)) }) {
        query.error?.let { item { DeckNote(it) } }
        items(query.value, key = { it.channelId }) { row ->
            val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
            val type = studio.otherstuff.ambulando.app.ui.theme.AmbType
            Column(Modifier.fillMaxWidth().clickable { intents.openInChat(row.channelId, row.threadId) }.tintedDeckRow(null, false).testTag("deck.recent.item"), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("# " + (navigation.labels[row.channelId] ?: row.title ?: "Conversation"), Modifier.weight(1f), fontSize = type.subheadline, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = amb.foreground, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(studio.otherstuff.ambulando.app.ui.chat.ChatTime.label(row.latestAt), fontSize = type.caption2, color = amb.mutedForeground)
                }
                Text(DeckActivity.threadTitle(row), fontSize = type.footnote, color = amb.foreground, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(studio.otherstuff.ambulando.core.chat.MentionCodec.plain(row.latestBody), fontSize = type.caption, color = amb.mutedForeground, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
