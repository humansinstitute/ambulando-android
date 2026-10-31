package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.surfaces.TaskHierarchy
import studio.otherstuff.ambulando.core.transport.TaskRecord

// Per-parent task relationships (iOS `TaskRelationshipCache`): which tasks a complete read of Tower's relationships route
// listed as a parent's direct children, and how far that read got. A task record is never deleted or re-parented by this
// bookkeeping; a completed read only decides which saved tasks the parent's list shows.
//
// Membership is stamped with the generation of the read that confirmed it. A read replaces membership only when its last
// page arrives: members of earlier generations that it did not list are dropped, so a failed or partial read never empties
// a list. Authorized upserts and write acknowledgements note their parent in the same transaction (stamped with the
// parent's current generation, so a read already in flight does not drop them), which keeps a new child visible whatever
// the device and server clocks say: no time comparison decides membership.

/** The persisted read state of one parent's direct-child list. */
data class TaskRelationshipState(
    val parentId: String,
    /** A read reached its last page: the members are what Tower listed for this identity. */
    val complete: Boolean,
    /** Tower reported some related tasks unavailable, or refused the parent: inaccessible tasks are not enumerated. */
    val restricted: Boolean,
    /** The last read failed before finishing (null after a read that did not fail). */
    val failure: String?,
    /** Generation of the latest (possibly unfinished) read. */
    val generation: Long,
    /** Where an unfinished read stops; the next read resumes there. */
    val nextCursor: String?,
    /** Pages stored by the latest read. */
    val pages: Int,
    /** Device time of the last completed read. Informational only: never compared with Tower's clock. */
    val refreshedAt: String?,
    val attemptedAt: String?,
)

/** Where a read of one parent's list begins. */
data class TaskRelationshipRead(val generation: Long, val cursor: String?)

/** Direct children by parent id for a task and its descendants, with each parent's read state. */
data class TaskSubtasks(val children: Map<String, List<TaskRecord>>, val states: Map<String, TaskRelationshipState>)

// MARK: Writer

/**
 * Keeps known membership current after tasks are written: a task that names a parent whose list is tracked joins it, and a
 * task that no longer names a tracked parent leaves that list. Only rows that were actually stored are noted, so an
 * older copy never changes membership. Runs in the same transaction as the task write.
 */
internal fun WriteTx.noteTaskRelationships(written: List<TaskRecord>) {
    var changed = false
    val workspaces = LinkedHashSet<String>()
    for (task in written) {
        val parent = TaskHierarchy.parentId(task)?.takeIf { it != task.id.lowercase() }
        val id = task.id.lowercase()
        if (db.run("DELETE FROM task_relationship_members WHERE workspace_id = ? AND child_id = ? AND (? IS NULL OR parent_id <> ?)",
                task.workspaceId, id, parent, parent) > 0) { changed = true; workspaces.add(task.workspaceId) }
        if (parent == null) continue
        val gen = db.longValue("SELECT gen FROM task_relationships WHERE workspace_id = ? AND parent_id = ?", task.workspaceId, parent) ?: continue
        if (db.run("INSERT INTO task_relationship_members(workspace_id, parent_id, child_id, gen) VALUES(?,?,?,?) " +
                "ON CONFLICT(workspace_id, parent_id, child_id) DO UPDATE SET gen = excluded.gen WHERE task_relationship_members.gen <> excluded.gen",
                task.workspaceId, parent, id, gen) > 0) { changed = true; workspaces.add(task.workspaceId) }
    }
    if (changed) for (w in workspaces) emit(StoreTable.TASK_RELATIONSHIPS, w)
}

/** Forgets what a deleted task contributed: its own list and its place in its parent's. */
internal fun WriteTx.dropTaskRelationships(workspaceId: String, taskId: String): Int {
    val id = taskId.lowercase()
    val n = db.run("DELETE FROM task_relationship_members WHERE workspace_id = ? AND (child_id = ? OR parent_id = ?)", workspaceId, id, id) +
        db.run("DELETE FROM task_relationships WHERE workspace_id = ? AND parent_id = ?", workspaceId, id)
    if (n > 0) emit(StoreTable.TASK_RELATIONSHIPS, workspaceId)
    return n
}

/**
 * Starts (or resumes) a read of [parentId]'s direct children. A read that stopped part-way resumes at its stored cursor;
 * otherwise a new generation begins and the last confirmed list stays visible until the new one completes.
 */
suspend fun StoreWriter.beginTaskRelationshipRead(workspaceId: String, parentId: String, now: String = StoreTime.now()): TaskRelationshipRead = write {
    val parent = parentId.lowercase()
    val row = db.first("SELECT gen, next_cursor FROM task_relationships WHERE workspace_id = ? AND parent_id = ?", workspaceId, parent) { it.long(0) to it.text(1) }
    val read = when {
        row == null -> {
            db.run("INSERT INTO task_relationships(workspace_id, parent_id, gen, attempted_at) VALUES(?,?,1,?)", workspaceId, parent, now)
            TaskRelationshipRead(1, null)
        }
        row.second != null -> {
            db.run("UPDATE task_relationships SET attempted_at = ? WHERE workspace_id = ? AND parent_id = ?", now, workspaceId, parent)
            TaskRelationshipRead(row.first, row.second)
        }
        else -> {
            db.run("UPDATE task_relationships SET gen = gen + 1, pages = 0, read_restricted = 0, attempted_at = ? WHERE workspace_id = ? AND parent_id = ?",
                now, workspaceId, parent)
            TaskRelationshipRead(row.first + 1, null)
        }
    }
    emit(StoreTable.TASK_RELATIONSHIPS, workspaceId)
    read
}

/**
 * Stores one page of a read: its task rows, the children it listed (those whose stored record still names [parentId]),
 * and the new read state, in one transaction. The last page ([next] null) replaces membership with this read's and marks
 * the list complete. A page from a read that has since been superseded is ignored, and so is a replayed page: [fromCursor]
 * is the cursor the page was fetched from, which must still be the stored one (and a first page needs a read that has
 * not yet stored any), so a duplicate or late page cannot rewind or reopen a read that moved on or finished.
 */
suspend fun StoreWriter.applyTaskRelationshipPage(
    workspaceId: String, parentId: String, generation: Long, records: List<TaskRecord>, childIds: List<String>, unavailable: Boolean,
    next: String?, now: String = StoreTime.now(), fromCursor: String? = UNCHECKED_CURSOR,
): Boolean = write {
    val parent = parentId.lowercase()
    val row = db.first("SELECT gen, next_cursor, pages FROM task_relationships WHERE workspace_id = ? AND parent_id = ?", workspaceId, parent) { Triple(it.long(0), it.text(1), it.long(2)) }
    if (row == null || row.first != generation) return@write false
    if (fromCursor != UNCHECKED_CURSOR && (row.second != fromCursor || (fromCursor == null && row.third != 0L))) return@write false
    val rows = records.filter { it.workspaceId == workspaceId }
    if (writeTasks(rows) > 0) emit(StoreTable.TASKS, workspaceId)
    for (raw in childIds) {
        val id = raw.lowercase()
        val stored = db.taskRow(workspaceId, id) ?: continue
        if (id == parent || TaskHierarchy.parentId(stored) != parent) continue
        db.run("INSERT INTO task_relationship_members(workspace_id, parent_id, child_id, gen) VALUES(?,?,?,?) " +
            "ON CONFLICT(workspace_id, parent_id, child_id) DO UPDATE SET gen = excluded.gen", workspaceId, parent, id, generation)
    }
    if (next == null) {
        db.run("DELETE FROM task_relationship_members WHERE workspace_id = ? AND parent_id = ? AND gen <> ?", workspaceId, parent, generation)
        db.run("""UPDATE task_relationships SET complete = 1, restricted = ?, read_restricted = 0, failure = NULL, next_cursor = NULL,
                    pages = pages + 1, refreshed_at = ?, attempted_at = ? WHERE workspace_id = ? AND parent_id = ?""",
            unavailable || readRestricted(workspaceId, parent), now, now, workspaceId, parent)
    } else {
        db.run("""UPDATE task_relationships SET read_restricted = ?, failure = NULL, next_cursor = ?, pages = pages + 1, attempted_at = ?
                  WHERE workspace_id = ? AND parent_id = ?""",
            unavailable || readRestricted(workspaceId, parent), next, now, workspaceId, parent)
    }
    emit(StoreTable.TASK_RELATIONSHIPS, workspaceId)
    true
}

/** Marks a page whose source cursor is not checked (callers that do not page). */
private const val UNCHECKED_CURSOR = "\u0000unchecked"

private fun WriteTx.readRestricted(workspaceId: String, parent: String): Boolean =
    db.longValue("SELECT read_restricted FROM task_relationships WHERE workspace_id = ? AND parent_id = ?", workspaceId, parent) == 1L

/**
 * Records a read that did not finish. [restricted] (Tower refused the parent) means the list can no longer be confirmed and
 * no hidden children are implied; the saved members stay. Otherwise the failure is noted and the read can resume; a
 * [restart] drops a stored cursor Tower no longer accepts.
 */
suspend fun StoreWriter.failTaskRelationshipRead(
    workspaceId: String, parentId: String, generation: Long, restricted: Boolean, failure: String = "failed", restart: Boolean = false, now: String = StoreTime.now(),
) = write {
    val parent = parentId.lowercase()
    val current = db.longValue("SELECT gen FROM task_relationships WHERE workspace_id = ? AND parent_id = ?", workspaceId, parent)
    if (current == generation) {
        if (restricted) {
            db.run("UPDATE task_relationships SET restricted = 1, complete = 0, failure = NULL, next_cursor = NULL, attempted_at = ? WHERE workspace_id = ? AND parent_id = ?",
                now, workspaceId, parent)
        } else {
            db.run("UPDATE task_relationships SET failure = ?, next_cursor = CASE WHEN ? = 1 THEN NULL ELSE next_cursor END, attempted_at = ? WHERE workspace_id = ? AND parent_id = ?",
                failure, restart, now, workspaceId, parent)
        }
        emit(StoreTable.TASK_RELATIONSHIPS, workspaceId)
    }
}

// MARK: Reader

private const val STATE_COLUMNS = "parent_id, complete, restricted, read_restricted, failure, gen, next_cursor, pages, refreshed_at, attempted_at"

private fun state(r: SqlRow) = TaskRelationshipState(
    parentId = r.text(0)!!, complete = r.bool(1), restricted = r.bool(2) || r.bool(3), failure = r.text(4), generation = r.long(5),
    nextCursor = r.text(6), pages = r.long(7).toInt(), refreshedAt = r.text(8), attemptedAt = r.text(9),
)

suspend fun StoreReader.taskRelationship(workspaceId: String, parentId: String): TaskRelationshipState? = read {
    first("SELECT $STATE_COLUMNS FROM task_relationships WHERE workspace_id = ? AND parent_id = ?", workspaceId, parentId.lowercase()) { state(it) }
}

/** A task's direct children (archived included, dependency successors excluded), oldest first. */
suspend fun StoreReader.directSubtasks(workspaceId: String, parentId: String): List<TaskRecord> =
    subtaskTree(workspaceId, parentId, maxParents = 1).children[parentId.lowercase()].orEmpty()

/**
 * The direct children of [rootId] and of each descendant, with each parent's read state, in one consistent read. A parent
 * whose list a read completed shows exactly its confirmed members; any other parent shows its members plus the saved
 * tasks that name it, so a never-read or partly read list still shows what the device knows. Cycles stop at the first
 * repeat and at most [maxParents] parents are expanded.
 */
suspend fun StoreReader.subtaskTree(workspaceId: String, rootId: String, maxParents: Int = 200): TaskSubtasks = read {
    val children = LinkedHashMap<String, List<TaskRecord>>()
    val states = LinkedHashMap<String, TaskRelationshipState>()
    var frontier = listOf(rootId.lowercase())
    while (frontier.isNotEmpty() && children.size < maxParents) {
        val level = frontier.filter { it !in children }.distinct().take(maxParents - children.size)
        if (level.isEmpty()) break
        val byParent = HashMap<String, LinkedHashMap<String, TaskRecord>>()
        for (chunk in level.chunked(40)) {
            val marks = chunk.joinToString(",") { "?" }
            query("SELECT $STATE_COLUMNS FROM task_relationships WHERE workspace_id = ? AND parent_id IN ($marks)", workspaceId, *chunk.toTypedArray()) { state(it) }
                .forEach { states[it.parentId] = it }
            query(
                "SELECT m.parent_id, " + TASK_COLUMNS.split(", ").joinToString(", ") { "t.$it" } + " FROM task_relationship_members m " +
                    "JOIN tasks t ON t.workspace_id = m.workspace_id AND t.id = m.child_id WHERE m.workspace_id = ? AND m.parent_id IN ($marks) AND t.access_removed = 0",
                workspaceId, *chunk.toTypedArray(),
            ) { r -> r.text(0)!! to taskAt(r, 1, workspaceId) }.forEach { (parent, task) -> byParent.getOrPut(parent) { LinkedHashMap() }[task.id] = task }
            val uncertain = chunk.filter { states[it]?.complete != true }
            if (uncertain.isNotEmpty()) {
                // Saved tasks that name an unconfirmed parent: a text pre-filter, then the real metadata check.
                val like = uncertain.joinToString(" OR ") { "instr(lower(raw), ?) > 0" }
                query("SELECT $TASK_COLUMNS FROM tasks WHERE workspace_id = ? AND access_removed = 0 AND ($like)", workspaceId, *uncertain.toTypedArray()) { task(it, workspaceId) }
                    .forEach { saved ->
                        val parent = TaskHierarchy.parentId(saved)
                        if (parent != null && parent in uncertain && parent != saved.id.lowercase()) byParent.getOrPut(parent) { LinkedHashMap() }[saved.id] = saved
                    }
            }
        }
        val next = ArrayList<String>()
        for (parent in level) {
            val list = byParent[parent].orEmpty().values.sortedWith(compareBy({ it.createdAt }, { it.id }))
            children[parent] = list
            list.forEach { next.add(it.id.lowercase()) }
        }
        frontier = next
    }
    TaskSubtasks(children, states)
}

/** A task row whose columns begin at [offset] (the shared task column order). */
private fun taskAt(r: SqlRow, offset: Int, w: String) = TaskRecord(
    workspaceId = w, id = r.text(offset)!!, scopeId = r.text(offset + 1), channelId = r.text(offset + 2)!!, threadId = r.text(offset + 3),
    title = r.text(offset + 4)!!, description = r.text(offset + 5), state = r.text(offset + 6)!!, priority = r.text(offset + 7)!!,
    assignedToNpub = r.text(offset + 8), createdByNpub = r.text(offset + 9), rowVersion = r.long(offset + 10), activityVersion = r.long(offset + 11),
    createdAt = r.text(offset + 12)!!, updatedAt = r.text(offset + 13)!!, raw = parseRaw(r.text(offset + 14)), accessRemoved = r.bool(offset + 15),
)
