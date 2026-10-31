package studio.otherstuff.ambulando.app.ui.tasks

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.app.ui.nav.ScreenScope
import studio.otherstuff.ambulando.app.ui.work.ScopeChoice
import studio.otherstuff.ambulando.app.ui.work.WriteActions
import studio.otherstuff.ambulando.app.ui.work.scopeChoice
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.profiles.PeopleDirectory
import studio.otherstuff.ambulando.core.store.ChangeKey
import studio.otherstuff.ambulando.core.store.LiveQueryState
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.comments
import studio.otherstuff.ambulando.core.store.directoryMembers
import studio.otherstuff.ambulando.core.store.liveQuery
import studio.otherstuff.ambulando.core.store.people
import studio.otherstuff.ambulando.core.store.task
import studio.otherstuff.ambulando.core.store.tasks
import studio.otherstuff.ambulando.core.store.channelTasks
import studio.otherstuff.ambulando.core.surfaces.TaskHierarchy
import studio.otherstuff.ambulando.core.store.workspaceWrites
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.transport.TaskPriority
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.TaskState
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string

/**
 * A task as shown: Tower's record with the user's pending changes applied on top (they are queued durably and marked
 * as unsent until Tower accepts them).
 */
@Immutable
data class TaskRow(
    val task: TaskRecord,
    val title: String,
    val state: String,
    val priority: String,
    val assignee: String?,
    val pending: List<WriteItem>,
) {
    val id: String get() = task.id
    val taskState: TaskState? get() = TaskState.from(state)

    companion object {
        fun of(task: TaskRecord, writes: List<WriteItem>): TaskRow {
            var title = task.title; var state = task.state; var priority = task.priority; var assignee = task.assignedToNpub
            for (w in writes.filter { it.targetId == task.id && it.isPending }) {
                val p = w.payloadObject
                when (w.kind) {
                    WriteKind.TASK_STATE -> state = p.string("state") ?: state
                    WriteKind.TASK_PATCH -> {
                        val patch = p.obj("patch") ?: JsonObject(emptyMap())
                        title = patch.string("title") ?: title
                        priority = patch.string("priority") ?: priority
                    }
                    WriteKind.TASK_ASSIGN -> assignee = p.string("assigned_to_npub")?.takeIf { it.isNotEmpty() }
                    else -> Unit
                }
            }
            return TaskRow(task, title, state, priority, assignee, writes.filter { it.targetId == task.id })
        }
    }
}

/**
 * Write payloads for task changes, exactly as the iOS views build them (`WriteIntent(kind: .task…)`). Edits record the
 * version and values they were made on, so a concurrent change is detected, not overwritten.
 */
object TaskWrites {
    fun create(channelId: String, title: String, description: String, state: TaskState, priority: TaskPriority, assignee: String?): WriteIntent =
        WriteIntent(WriteKind.TASK_CREATE, null, buildJsonObject {
            put("channel_id", channelId)
            put("title", title.trim())
            put("state", state.wire)
            put("priority", priority.wire)
            description.trim().takeIf { it.isNotEmpty() }?.let { put("description", it) }
            assignee?.let { put("assigned_to_npub", it) }
        })

    fun state(taskId: String, state: TaskState): WriteIntent =
        WriteIntent(WriteKind.TASK_STATE, taskId, buildJsonObject { put("state", state.wire) })

    fun patch(row: TaskRow, fields: Map<String, String>): WriteIntent = WriteIntent(WriteKind.TASK_PATCH, row.id, buildJsonObject {
        put("base_row_version", row.task.rowVersion)
        put("base", buildJsonObject {
            put("title", row.task.title)
            put("description", row.task.description ?: "")
            put("priority", row.task.priority)
        })
        put("patch", JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
    })

    fun assign(row: TaskRow, npub: String?): WriteIntent = WriteIntent(WriteKind.TASK_ASSIGN, row.id, buildJsonObject {
        put("assigned_to_npub", npub ?: "")
        put("base_row_version", row.task.rowVersion)
        put("base_assigned_to_npub", row.task.assignedToNpub ?: "")
    })

    fun comment(taskId: String, body: String): WriteIntent = WriteIntent(WriteKind.TASK_COMMENT, taskId, buildJsonObject { put("body", body) })

    /**
     * A new child of [parent], always in the parent's channel: the durable create queue with the web's relationship
     * metadata (`parent_task_id`, no predecessors), so pending and failed additions stay visible under the parent.
     */
    fun subtask(parent: TaskRecord, title: String): WriteIntent = WriteIntent(WriteKind.TASK_CREATE, null, buildJsonObject {
        put("channel_id", parent.channelId)
        put("title", title.trim())
        put("state", TaskState.NEW.wire)
        put("priority", TaskPriority.SAND.wire)
        put("metadata", buildJsonObject {
            put("parent_task_id", parent.id)
            put("predecessor_task_ids", kotlinx.serialization.json.JsonArray(emptyList()))
        })
    })

    /** The parent a queued create names (null for an ordinary create). */
    fun pendingParent(item: WriteItem): String? = item.payloadObject.obj("metadata")?.string("parent_task_id")?.lowercase()

    /** The edit sheet's patch: only fields that changed; null when nothing did. */
    fun edit(row: TaskRow, title: String, description: String): WriteIntent? {
        val fields = LinkedHashMap<String, String>()
        val t = title.trim()
        if (t != row.task.title) fields["title"] = t
        if (description != (row.task.description ?: "")) fields["description"] = description
        return if (fields.isEmpty()) null else patch(row, fields)
    }
}

/**
 * Everything the Tasks screens ask of the app. The integrator implements [submit] (iOS `AppModel.submit(_:in:)`: queue
 * the write durably, then send it through the write sender) and the refresh calls; the named intents default to
 * [submit] with the payloads of [TaskWrites]. Each returns whether the change was queued.
 */
interface TasksIntents : WriteActions {
    /** iOS `AppModel.submit(WriteIntent, in: workspace)`. */
    fun submit(intent: WriteIntent): Boolean

    /** iOS `AppModel.openTasks(workspace, scopeId:, force:)`: catch the list up for the current context (no-op locked). */
    fun refresh(force: Boolean)

    /** iOS `AppModel.openTask(id, in:, force:)` plus `markViewed(.task, id)` when [markViewed] is true. */
    fun openTask(taskId: String, force: Boolean, markViewed: Boolean)

    /** The last sync error for one task (iOS `syncError(forKey: WorkspaceSync.taskKey)`). */
    fun taskError(taskId: String): Flow<String?> = flowOf(null)

    fun createTask(channelId: String, title: String, description: String, state: TaskState, priority: TaskPriority, assignee: String?): Boolean =
        submit(TaskWrites.create(channelId, title, description, state, priority, assignee))

    fun setState(row: TaskRow, state: TaskState): Boolean = submit(TaskWrites.state(row.id, state))
    fun setPriority(row: TaskRow, priority: TaskPriority): Boolean = submit(TaskWrites.patch(row, mapOf("priority" to priority.wire)))
    fun assign(row: TaskRow, npub: String?): Boolean = submit(TaskWrites.assign(row, npub))

    /** True also when nothing changed (the sheet just closes). */
    fun edit(row: TaskRow, title: String, description: String): Boolean = TaskWrites.edit(row, title, description)?.let(::submit) ?: true
    fun comment(taskId: String, body: String): Boolean = submit(TaskWrites.comment(taskId, body))

    /** The `@` lookahead for comment fields: people, agents and records matching [query] from this workspace's local store. */
    suspend fun mentionCandidates(query: String): List<studio.otherstuff.ambulando.core.store.MentionCandidate> = emptyList()

    /** Adds a child under [parent]; false when the title is blank. */
    fun addSubtask(parent: TaskRow, title: String): Boolean = title.isNotBlank() && submit(TaskWrites.subtask(parent.task, title))

    /**
     * Reads this task's authorized relationships from Tower (children and dependents, paged) into the store, so the child
     * list reflects other devices. Never throws; the result says how complete the stored list is.
     */
    suspend fun loadRelationships(taskId: String): RelationshipStatus = RelationshipStatus.OFFLINE
}

/** How much of a task's child list this device could confirm. */
enum class RelationshipStatus {
    /** Every page was read. */
    COMPLETE,
    /** The read stopped at its budget or Tower reported some tasks unavailable: the list may be missing children. */
    PARTIAL,
    /** Tower refused the task: the list cannot be confirmed and no hidden children are implied. */
    RESTRICTED,
    /** Not read (locked, offline or failed): only saved tasks are shown. */
    OFFLINE,
}

@Immutable
data class TasksSnapshot(
    val rows: List<TaskRow> = emptyList(),
    val creating: List<WriteItem> = emptyList(),
    val choice: ScopeChoice = ScopeChoice(),
    val people: PeopleDirectory? = null,
    /** Assignee choices for the create sheet. */
    val members: List<MemberRecord> = emptyList(),
) {
    val names: Map<String, String> get() = people?.names ?: emptyMap()

    /** Board columns (backlog first when it has tasks), as on the web board. */
    val columns: List<Pair<TaskState, List<TaskRow>>>
        get() {
            val byState = rows.groupBy { TaskState.from(it.state) ?: TaskState.NEW }
            val out = TaskState.board.map { it to (byState[it] ?: emptyList()) }.toMutableList()
            byState[TaskState.BACKLOG]?.takeIf { it.isNotEmpty() }?.let { out.add(0, TaskState.BACKLOG to it) }
            return out
        }
}

private fun <T> Flow<T>.loaded(scope: CoroutineScope, initial: T): StateFlow<LiveQueryState<T>> =
    map { LiveQueryState(it, loaded = true) }.stateIn(scope, SharingStarted.Eagerly, LiveQueryState(initial))

/** Live store reads for the task list in the shared context. */
class TasksScreenState(store: LocalStore, scope: ScreenScope, coroutineScope: CoroutineScope) {
    val context = WorkContext(scope.scopeId, scope.channelId)
    private val w = scope.workspaceId

    val snapshot: StateFlow<LiveQueryState<TasksSnapshot>> = store.liveQuery({ key: ChangeKey ->
        (key.workspaceId == w && key.table in TABLES) || key.table == StoreTable.PROFILES || key.table == StoreTable.ACCESS
    }) {
        val pending = store.reader.workspaceWrites(scope.connectionId, w)
        TasksSnapshot(
            rows = store.reader.tasks(w, context, limit = 500).map { TaskRow.of(it, pending) },
            creating = pending.filter { it.kind == WriteKind.TASK_CREATE },
            choice = scopeChoice(store, w),
            people = store.reader.people(scope.connectionId, w),
            members = store.reader.directoryMembers(w),
        )
    }.loaded(coroutineScope, TasksSnapshot())

    private companion object {
        val TABLES = setOf(StoreTable.TASKS, StoreTable.WRITES, StoreTable.MEMBERS, StoreTable.CHANNELS, StoreTable.SCOPES)
    }
}

@Immutable
data class TaskDetailSnapshot(
    val row: TaskRow? = null,
    /** The stored parent task, when this task names one that is saved on this device. */
    val parent: TaskRecord? = null,
    /** The parent id this task names (even when that task is not saved here). */
    val parentId: String? = null,
    /** Direct children by parent id for this task and its descendants, oldest first, with pending changes applied. */
    val children: Map<String, List<TaskRow>> = emptyMap(),
    /** Queued or failed subtask creations by parent id (they are not Tower records yet). */
    val creatingChildren: Map<String, List<WriteItem>> = emptyMap(),
    val comments: List<CommentRecord> = emptyList(),
    val pendingComments: List<WriteItem> = emptyList(),
    val members: List<MemberRecord> = emptyList(),
    val people: PeopleDirectory? = null,
    val choice: ScopeChoice = ScopeChoice(),
) {
    /** Directory labels, overridden by cached public-profile names where the people directory has one. */
    val names: Map<String, String> get() = members.associate { it.npub to it.label } + (people?.names ?: emptyMap())
}

/** Live store reads for one task, its comments and its pending changes. */
class TaskDetailState(store: LocalStore, scope: ScreenScope, val taskId: String, coroutineScope: CoroutineScope) {
    private val w = scope.workspaceId

    val snapshot: StateFlow<LiveQueryState<TaskDetailSnapshot>> = store.liveQuery({ key: ChangeKey ->
        (key.workspaceId == w && key.table in TABLES) || key.table == StoreTable.PROFILES || key.table == StoreTable.ACCESS
    }) {
        val pending = store.reader.workspaceWrites(scope.connectionId, w, taskId)
        val record = store.reader.task(w, taskId)
        val everyWrite = if (record != null) store.reader.workspaceWrites(scope.connectionId, w) else emptyList()
        val index = record?.let { TaskHierarchy.index(store.reader.channelTasks(w, it.channelId)) } ?: emptyMap()
        // Only this task's subtree (cycle-safe, bounded), each child with its own pending changes applied.
        val subtree = LinkedHashMap<String, List<TaskRow>>()
        val queue = ArrayDeque(listOf(taskId.lowercase()))
        while (queue.isNotEmpty() && subtree.size < 200) {
            val parent = queue.removeFirst()
            if (parent in subtree) continue
            val direct = index[parent].orEmpty()
            subtree[parent] = direct.map { TaskRow.of(it, everyWrite) }
            direct.forEach { queue.add(it.id.lowercase()) }
        }
        val parentId = record?.let { TaskHierarchy.parentId(it) }
        TaskDetailSnapshot(
            parent = parentId?.let { store.reader.task(w, it) },
            parentId = parentId,
            children = subtree,
            creatingChildren = everyWrite.filter { it.kind == WriteKind.TASK_CREATE }.groupBy { TaskWrites.pendingParent(it) ?: "" }.filterKeys { it in subtree },
            row = record?.let { TaskRow.of(it, pending.filter { p -> p.kind != WriteKind.TASK_COMMENT }) },
            comments = store.reader.comments(w, CommentRecord.Parent.TASK, taskId),
            pendingComments = pending.filter { it.kind == WriteKind.TASK_COMMENT },
            members = store.reader.directoryMembers(w),
            people = store.reader.people(scope.connectionId, w),
            choice = scopeChoice(store, w),
        )
    }.loaded(coroutineScope, TaskDetailSnapshot())

    private companion object {
        val TABLES = setOf(StoreTable.TASKS, StoreTable.COMMENTS, StoreTable.WRITES, StoreTable.MEMBERS, StoreTable.CHANNELS)
    }
}
