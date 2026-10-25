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
        TaskDetailSnapshot(
            row = store.reader.task(w, taskId)?.let { TaskRow.of(it, pending.filter { p -> p.kind != WriteKind.TASK_COMMENT }) },
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
