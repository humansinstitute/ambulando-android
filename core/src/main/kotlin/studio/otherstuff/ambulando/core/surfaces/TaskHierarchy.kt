package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.store.TaskRelationshipState
import studio.otherstuff.ambulando.core.transport.TaskRecord

/**
 * The task detail screen's view of parent and child tasks (`metadata.parent_task_id`), as on iPhone and Mac. Counts are
 * direct children, including archived ones; dependency successors (`predecessor_task_ids`) never make a child. This reads
 * only what is stored on the device and never repairs or rewrites a relationship.
 */
object TaskHierarchy {
    /** The parent a stored task names, or null for a base task. */
    fun parentId(task: TaskRecord): String? = TaskTreeNode.from(task.id, task.title, task.state, task.channelId, task.createdAt, task.raw).parentId

    /** Direct children by parent id, oldest first. A task that names itself as parent is not its own child. */
    fun index(tasks: List<TaskRecord>): Map<String, List<TaskRecord>> {
        val out = LinkedHashMap<String, MutableList<TaskRecord>>()
        for (task in tasks.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
            val parent = parentId(task)?.takeIf { it != task.id.lowercase() } ?: continue
            out.getOrPut(parent) { ArrayList() }.add(task)
        }
        return out
    }

    /**
     * Whether expanding [candidate] under [path] would revisit a task already above it (a cycle). Expansion stops there
     * instead of recursing, so a malformed tree cannot loop.
     */
    fun closesCycle(path: List<String>, candidate: String): Boolean = path.any { it.equals(candidate, ignoreCase = true) }

    /** The text for a direct-child count: `+` marks a list that may be incomplete. */
    fun countLabel(count: Int, complete: Boolean): String = if (complete) "$count" else "$count+"

    /** What this device can say about one parent's direct-child list. */
    enum class Kind {
        /** Nothing read yet: only saved tasks that name the parent are shown. */
        UNLOADED,
        /** A read completed and Tower listed everything this identity may see; the count is certain. */
        COMPLETE,
        /** A read completed earlier; it cannot be refreshed now (offline, locked or a failed refresh), so the list may be out of date. */
        CACHED,
        /** A read began and stopped part-way: the children read so far are shown. */
        PARTIAL,
        /** No read could be made (offline, locked or failed) and none ever completed. */
        OFFLINE,
        /** Tower refused the parent or hid some related tasks: inaccessible tasks are never enumerated or counted. */
        RESTRICTED,
    }

    /**
     * Classifies a stored [state]. [readable] is false while the device cannot read from Tower (locked, offline or access
     * removed); [reading] is true while a read of this list is running now. Only [Kind.COMPLETE] has a certain count.
     *
     * A list that was once complete keeps its members while a later refresh runs, but the count is certain only when no
     * refresh is unfinished: a refresh that failed is [Kind.CACHED]; one that stopped at a page budget (a stored cursor) is
     * [Kind.PARTIAL]; one that was begun and never stored a page (a stored read with no pages, such as after the
     * app was closed) is [Kind.CACHED] unless it is running now. A refresh that is simply running leaves the last
     * confirmed count showing, labelled as refreshing by the caller.
     */
    fun kind(state: TaskRelationshipState?, readable: Boolean, reading: Boolean = false): Kind = when {
        state != null && state.restricted -> Kind.RESTRICTED
        state != null && state.complete -> when {
            !readable || state.failure != null -> Kind.CACHED
            state.nextCursor != null -> Kind.PARTIAL
            !reading && state.pages == 0 -> Kind.CACHED
            else -> Kind.COMPLETE
        }
        state != null && (state.pages > 0 || state.nextCursor != null) -> Kind.PARTIAL
        !readable || state?.failure != null -> Kind.OFFLINE
        else -> Kind.UNLOADED
    }

    fun isConfirmed(kind: Kind): Boolean = kind == Kind.COMPLETE
}
