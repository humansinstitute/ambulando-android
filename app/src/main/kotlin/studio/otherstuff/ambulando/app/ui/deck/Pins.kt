package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.store.ChangeKey
import studio.otherstuff.ambulando.core.store.LiveQuery
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.PinnedItem
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.pinnedItems
import studio.otherstuff.ambulando.core.store.workspaceWrites
import studio.otherstuff.ambulando.core.surfaces.PinRecord

/**
 * The member's pins with the latest queued pin write per record overlaid until Tower acknowledges it (iOS
 * `PinsSnapshot`): a pin shows the user's latest intent at once, and a refused one shows as failed.
 */
@Immutable
data class PinsSnapshot(
    /** Stored pinned rows in Tower's order, with titles from the local store. */
    val items: List<PinnedItem> = emptyList(),
    /** Latest pin write per resource (`type:id`). */
    val pending: Map<String, WriteItem> = emptyMap(),
    val loaded: Boolean = false,
    val orderWrite: WriteItem? = null,
) {
    private val stored: Set<String> by lazy { items.mapTo(HashSet()) { key(it.pin.resourceType, it.pin.resourceId) } }

    /** Pinned as the user last asked (a pending write wins over the stored row). */
    fun isPinned(type: PinRecord.ResourceType, id: String): Boolean {
        val write = pending[key(type, id)]
        if (write != null && write.isPending) {
            (write.payloadObject["pinned"] as? JsonPrimitive)?.booleanOrNull?.let { return it }
        }
        return key(type, id) in stored
    }

    /** The refused (failed or conflicting) pin write for a record, if any. */
    fun failure(type: PinRecord.ResourceType, id: String): WriteItem? = pending[key(type, id)]?.takeIf { !it.isPending }

    /** Whether a write for this record is still on its way to Tower. */
    fun isSaving(type: PinRecord.ResourceType, id: String): Boolean = pending[key(type, id)]?.isPending == true

    /** Stored pins the user still wants (Deck's Pinned items card; it shows only when this is not empty). */
    val visibleItems: List<PinnedItem> get() = items.filter { isPinned(it.pin.resourceType, it.pin.resourceId) }

    companion object {
        fun key(type: PinRecord.ResourceType, id: String): String = "${type.wire}:$id"
    }
}

/** Pin writes. Implemented by the app model on its durable write queue. */
interface PinIntents {
    /**
     * Pins or unpins a thread, task, document or channel (iOS `AppModel.setPinned`): queues a `pin.set` write
     * (`PUT /me/pins/:type/:id`) with the stored pin's `expected_row_version` and a fresh `mutation_id`.
     */
    fun setPinned(pinned: Boolean, type: PinRecord.ResourceType, id: String)

    /** Retries a refused pin write now (iOS `retryWrite(id:force:)` + `kickOutbox`). */
    fun retryPinWrite(writeId: String)

    /** Drops a refused pin write (iOS `discardWrite(id:)`). */
    fun discardPinWrite(writeId: String)
}

/** Reads [PinsSnapshot] for one connection and workspace from the store. */
class PinsState(private val store: LocalStore, private val connectionId: String, private val workspaceId: String, scope: CoroutineScope) {
    private val query = LiveQuery(PinsSnapshot(), scope)
    val state = query.state

    fun start() {
        val w = workspaceId
        val c = connectionId
        query.start(store, matches = { (it.workspaceId == w && it.table == StoreTable.PINS) || it.table == StoreTable.WRITES || matchesRecordTitles(it, w) }) {
            val items = store.reader.pinnedItems(c, w)
            val pending = LinkedHashMap<String, WriteItem>()
            val writes = store.reader.workspaceWrites(c, w)
            for (write in writes) {
                if (write.kind != WriteKind.PIN_SET) continue
                val type = PinRecord.ResourceType.from(write.string("resource_type")) ?: continue
                val id = write.targetId ?: continue
                pending[PinsSnapshot.key(type, id)] = write // oldest first: the latest write per record wins
            }
            PinsSnapshot(items, pending, loaded = true, orderWrite = writes.lastOrNull { it.kind == WriteKind.PIN_ORDER })
        }
    }

    fun stop() = query.stop()

    private fun matchesRecordTitles(key: ChangeKey, w: String): Boolean =
        key.workspaceId == w && key.table in setOf(StoreTable.TASKS, StoreTable.DOCS, StoreTable.CHANNELS, StoreTable.THREADS, StoreTable.ACCESS)
}

/** The pins and pin intents a record screen's [PinToggleButton] uses when none are passed in. */
val LocalPins = compositionLocalOf { PinsSnapshot() }
val LocalPinIntents = compositionLocalOf<PinIntents?> { null }

/** Pin or unpin from a Deck row (iOS Deck `PinButton`): the latest intent shows while the write is queued. */
@Composable
fun PinButton(type: PinRecord.ResourceType, id: String, title: String, pins: PinsSnapshot, onSetPinned: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val pinned = pins.isPinned(type, id)
    val amb = Amb.colors
    IconButton(
        onClick = { onSetPinned(!pinned) },
        modifier = modifier.size(32.dp).semantics { contentDescription = if (pinned) "Unpin $title" else "Pin $title" }.testTag("deck.pin"),
    ) {
        Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null, tint = if (pinned) amb.primary else amb.mutedForeground)
    }
}

/**
 * The pin toolbar button of a task, document, conversation or channel screen (iOS `PinToggleButton` / `pinToolbar`):
 * Pin / Unpin, or "Pin not saved" with Retry and Discard when Tower refused the write (for example a record the viewer
 * can no longer read).
 */
@Composable
fun PinToggleButton(
    type: PinRecord.ResourceType,
    id: String,
    pins: PinsSnapshot = LocalPins.current,
    intents: PinIntents? = LocalPinIntents.current,
    modifier: Modifier = Modifier,
) {
    val amb = Amb.colors
    val pinned = pins.isPinned(type, id)
    val failure = pins.failure(type, id)
    var showFailure by remember { mutableStateOf(false) }
    val label = if (failure != null) "Pin not saved" else if (pinned) "Unpin" else "Pin"
    IconButton(
        onClick = { if (failure != null) showFailure = true else intents?.setPinned(!pinned, type, id) },
        enabled = intents != null,
        modifier = modifier.semantics { contentDescription = label }.testTag("pin.toggle"),
    ) {
        Icon(
            when {
                failure != null -> Icons.Outlined.WarningAmber
                pinned -> Icons.Filled.PushPin
                else -> Icons.Outlined.PushPin
            },
            contentDescription = null, tint = if (failure != null) amb.warning else amb.primary,
        )
    }
    if (showFailure && failure != null) {
        AlertDialog(
            onDismissRequest = { showFailure = false },
            title = { Text(failure.lastError ?: "Pin not saved") },
            confirmButton = {
                TextButton(onClick = { showFailure = false; intents?.retryPinWrite(failure.id) }, modifier = Modifier.testTag("pin.retry")) { Text("Retry") }
            },
            dismissButton = {
                TextButton(onClick = { showFailure = false; intents?.discardPinWrite(failure.id) }, modifier = Modifier.testTag("pin.discard")) {
                    Text("Discard", color = amb.destructive)
                }
            },
        )
    }
}
