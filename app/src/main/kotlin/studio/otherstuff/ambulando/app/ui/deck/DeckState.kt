package studio.otherstuff.ambulando.app.ui.deck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.deck.DeckActivity
import studio.otherstuff.ambulando.core.deck.PersonalAgentLauncher
import studio.otherstuff.ambulando.core.profiles.PeopleDirectory
import studio.otherstuff.ambulando.core.store.ChangeKey
import studio.otherstuff.ambulando.core.store.FlaggedTable
import studio.otherstuff.ambulando.core.store.LiveQuery
import studio.otherstuff.ambulando.core.store.LiveQueryState
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.accessRemovedIds
import studio.otherstuff.ambulando.core.store.agentActivities
import studio.otherstuff.ambulando.core.store.canvases
import studio.otherstuff.ambulando.core.store.channels
import studio.otherstuff.ambulando.core.store.deckCommentActivity
import studio.otherstuff.ambulando.core.store.deckThreads
import studio.otherstuff.ambulando.core.store.docs
import studio.otherstuff.ambulando.core.store.files
import studio.otherstuff.ambulando.core.store.members
import studio.otherstuff.ambulando.core.store.people
import studio.otherstuff.ambulando.core.store.personalWapps
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.store.tasks
import studio.otherstuff.ambulando.core.store.viewStates
import studio.otherstuff.ambulando.core.surfaces.CanvasRecord
import studio.otherstuff.ambulando.core.surfaces.PersonalWappRecord
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.ViewStateRecord

/**
 * A store query for a card or screen that can go out of sight (iOS `LiveQuery.start(keepingValue:)` / `pause()`).
 * Hidden, it stops reading but keeps its rows; shown again for the same key it resumes with them until the fresh read
 * replaces them. Rows are not kept across an access change (records removed or flagged while hidden) or a new key:
 * the card then starts empty until a fresh read.
 */
class PausableQuery<T>(initial: T, scope: CoroutineScope) {
    private val query = LiveQuery(initial, scope)
    val state = query.state
    private var startedKey: String? = null

    /** Starts or resumes the query for `key`. Returns true when it resumed the same key (rows kept if still valid). */
    fun show(store: LocalStore, key: String, matches: (ChangeKey) -> Boolean, load: suspend () -> T): Boolean {
        val resuming = startedKey == key
        startedKey = key
        query.start(store, keepingValue = resuming, matches = matches, load = load)
        return resuming
    }

    fun hide() = query.pause()

    fun stop() {
        startedKey = null
        query.stop()
    }
}

/**
 * Observes a [PausableQuery] while `visible`; `key` names the query's inputs (workspace, context …). `onFreshStart` runs
 * when the query starts for a new key (not when it resumes), where a card asks for a refresh.
 */
@Composable
fun <T> rememberPausableQuery(
    initial: T, store: LocalStore, visible: Boolean, key: String,
    matches: (ChangeKey) -> Boolean, onFreshStart: () -> Unit = {}, load: suspend () -> T,
): State<LiveQueryState<T>> {
    val scope = rememberCoroutineScope()
    val query = remember(store) { PausableQuery(initial, scope) }
    val currentLoad = rememberUpdatedState(load)
    val currentMatches = rememberUpdatedState(matches)
    val fresh = rememberUpdatedState(onFreshStart)
    LaunchedEffect(visible, key) {
        if (visible) {
            val resumed = query.show(store, key, { currentMatches.value(it) }) { currentLoad.value() }
            if (!resumed) fresh.value()
        } else {
            query.hide()
        }
    }
    DisposableEffect(query) { onDispose { query.stop() } }
    return query.state.collectAsState()
}

// MARK: Inbox

/** Deck's Inbox rows with the read positions and channel names they need. */
@Immutable
data class DeckInboxSnapshot(
    val items: List<DeckActivity.Item> = emptyList(),
    /** View states by `type:id`. */
    val viewStates: Map<String, ViewStateRecord> = emptyMap(),
    val channelLabels: Map<String, String> = emptyMap(),
    /** Item ids (`kind:recordId`) of records this identity can no longer see (kept readable, labelled). */
    val removed: Set<String> = emptySet(),
) {
    /** The view state Mark read acts on (chats: the thread; files have none). */
    fun viewState(item: DeckActivity.Item): ViewStateRecord? {
        val type = when (item.kind) {
            DeckActivity.Kind.CHAT -> ViewStateRecord.ResourceType.THREAD
            DeckActivity.Kind.TASK -> ViewStateRecord.ResourceType.TASK
            DeckActivity.Kind.DOCUMENT -> ViewStateRecord.ResourceType.DOCUMENT
            DeckActivity.Kind.FILE -> return null
        }
        return viewStates["${type.wire}:${item.recordId}"]
    }
}

object DeckInbox {
    /** Cards revealed per "Load older activity" (sol `DECK_INBOX_PAGE_SIZE`). */
    const val PAGE_SIZE = 50

    fun toggleLabel(kind: DeckActivity.Kind): String = when (kind) {
        DeckActivity.Kind.CHAT -> "Show chats"
        DeckActivity.Kind.TASK -> "Show tasks"
        DeckActivity.Kind.DOCUMENT -> "Show documents"
        DeckActivity.Kind.FILE -> "Show files"
    }

    /** Tables whose changes re-read the Inbox (as the iOS card). */
    fun matches(workspaceId: String): (ChangeKey) -> Boolean = { key ->
        key.workspaceId == workspaceId && key.table in setOf(
            StoreTable.THREADS, StoreTable.MESSAGES, StoreTable.TASKS, StoreTable.DOCS, StoreTable.FILES, StoreTable.VIEW_STATES,
            StoreTable.COMMENTS, StoreTable.CHANNELS, StoreTable.AGENTS, StoreTable.ACCESS,
        )
    }

    /**
     * Reads the Inbox for `context` from the store. `channelIds` are the context's channels (null for Home), which bound
     * the conversation and agent activity reads.
     */
    suspend fun load(store: LocalStore, workspaceId: String, viewerNpub: String?, context: WorkContext, channelIds: List<String>?): DeckInboxSnapshot =
        coroutineScope {
            val r = store.reader
            val w = workspaceId
            val channels = async { r.channels(w) }
            val threads = async { r.deckThreads(w, channelIds) }
            val tasks = async { r.tasks(w, context) }
            val docs = async { r.docs(w, context) }
            val files = async { r.files(w, context) }
            val states = async { r.viewStates(w) }
            val taskComments = async { r.deckCommentActivity(w, CommentRecord.Parent.TASK, viewerNpub) }
            val docComments = async { r.deckCommentActivity(w, CommentRecord.Parent.DOC, viewerNpub) }
            val activities = async { r.agentActivities(w, channelIds) }
            val removedThreads = async { r.accessRemovedIds(w, FlaggedTable.THREADS) }
            val channelRows = channels.await()
            val taskRows = tasks.await()
            val docRows = docs.await()
            val fileRows = files.await()
            val stateRows = states.await()
            val sources = DeckActivity.Sources(
                threads = threads.await(),
                tasks = taskRows.map { DeckActivity.TaskRow(it.id, it.scopeId, it.channelId, it.title, it.description, it.state, it.createdAt, it.updatedAt) },
                docs = docRows.map { DeckActivity.DocRow(it.id, it.scopeId, it.channelId, it.title, it.summary, it.archived, it.createdAt, it.updatedAt) },
                files = fileRows.map {
                    DeckActivity.FileRow(it.id, it.scopeId, it.channelId, it.displayName, it.description, it.contentType, it.archived, it.createdAt, it.updatedAt)
                },
                viewStates = stateRows.map { DeckActivity.ViewStateRow(it.resourceType.wire, it.resourceId, it.unread) },
                taskComments = taskComments.await(),
                docComments = docComments.await(),
                activities = activities.await(),
                channelScopes = channelRows.associate { it.id to it.scopeId },
            )
            val removedChannels = channelRows.filter { it.accessRemoved }.mapTo(HashSet()) { it.id }
            val removed = HashSet<String>()
            taskRows.filter { it.accessRemoved }.forEach { removed.add("task:${it.id}") }
            docRows.filter { it.accessRemoved }.forEach { removed.add("document:${it.id}") }
            fileRows.filter { it.accessRemoved }.forEach { removed.add("file:${it.id}") }
            val flaggedThreads = removedThreads.await()
            val items = DeckActivity.inbox(sources, context)
            for (item in items) {
                if (item.kind == DeckActivity.Kind.CHAT && (item.recordId in flaggedThreads || item.channelId in removedChannels)) removed.add(item.id)
            }
            DeckInboxSnapshot(
                items = items,
                viewStates = stateRows.associateBy { it.id },
                channelLabels = channelRows.associate { it.id to it.name },
                removed = removed,
            )
        }
}

// MARK: Recent channels

object DeckRecent {
    fun matches(workspaceId: String): (ChangeKey) -> Boolean = { key ->
        key.workspaceId == workspaceId && key.table in setOf(StoreTable.MESSAGES, StoreTable.THREADS, StoreTable.CHANNELS, StoreTable.ACCESS)
    }

    /** Each channel's newest conversation in the scope (the channel choice is ignored, as on the web). */
    suspend fun load(store: LocalStore, workspaceId: String, scopeId: String?): List<DeckActivity.ThreadActivity> {
        val channels = store.reader.channels(workspaceId)
        val scopes = channels.associate { it.id to it.scopeId }
        val ids = scopeId?.let { s -> channels.filter { it.scopeId == s }.map { it.id } }
        return DeckActivity.recentChannels(store.reader.deckThreads(workspaceId, ids), scopeId, scopes)
    }
}

// MARK: Canvas

@Immutable
data class DeckCanvasSnapshot(val canvases: List<CanvasRecord> = emptyList(), val removed: Set<String> = emptySet())

object DeckCanvases {
    fun matches(workspaceId: String): (ChangeKey) -> Boolean = { it.workspaceId == workspaceId && (it.table == StoreTable.CANVASES || it.table == StoreTable.ACCESS) }

    suspend fun load(store: LocalStore, workspaceId: String): DeckCanvasSnapshot =
        DeckCanvasSnapshot(store.reader.canvases(workspaceId), store.reader.accessRemovedIds(workspaceId, FlaggedTable.CANVASES))
}

// MARK: Hello and Links

/** What the Hello and Links card shows: the member's name, their WApps and their personal agent launchers. */
@Immutable
data class DeckLaunchers(
    val name: String? = null,
    val apps: List<PersonalWappRecord> = emptyList(),
    val agents: List<PersonalAgentLauncher> = emptyList(),
    val people: PeopleDirectory? = null,
)

object DeckHello {
    fun matches(workspaceId: String): (ChangeKey) -> Boolean = { key ->
        key.table == StoreTable.APP_STATE || key.table == StoreTable.PROFILES ||
            (key.workspaceId == workspaceId && key.table in setOf(StoreTable.TOOLBOX, StoreTable.MEMBERS, StoreTable.AGENTS))
    }

    suspend fun load(store: LocalStore, connectionId: String, workspaceId: String, signerNpub: String): DeckLaunchers = coroutineScope {
        val apps = async { store.reader.personalWapps(connectionId, workspaceId) }
        val members = async { store.reader.members(workspaceId) }
        val saved = async { store.reader.state(PersonalAgentLauncher.stateKey(connectionId, workspaceId)) }
        val people = async { store.reader.people(connectionId, workspaceId) }
        DeckLaunchers(
            name = members.await().firstOrNull { it.npub == signerNpub }?.label,
            apps = apps.await(),
            agents = PersonalAgentLauncher.decodeList(saved.await()),
            people = people.await(),
        )
    }
}
