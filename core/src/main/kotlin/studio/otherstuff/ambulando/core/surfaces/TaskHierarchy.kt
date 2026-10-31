package studio.otherstuff.ambulando.core.surfaces

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
}
