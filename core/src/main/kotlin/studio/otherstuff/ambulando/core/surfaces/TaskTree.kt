package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.transport.TowerError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.json.JsonFields.lowerUuid
import studio.otherstuff.ambulando.core.json.JsonFields.string

// Task Tree naplets (the web's task tree page, runtime and graph). A tree shows real channel tasks from a root task:
// children (`metadata.parent_task_id`) below, successors (`metadata.predecessor_task_ids`) to the right. Tasks are
// read through Tower's relationships route and stored like any other task, so a node opens the normal task screen.
// The naplet's saved layout keeps the view (`layout.task_tree`: root, collapsed branches, upstream context, viewport).
// Routes (existing):
//
// - `GET  /tasks/:t/relationships?cursor&limit` (the task and the tasks that name it as parent or predecessor)
// - `GET  /channels/:c/tasks` (choose an existing root)
// - `POST /channels/:c/tasks` with `client_request_id` and `metadata.parent_task_id` / `metadata.predecessor_task_ids`

/** One task as the tree draws it. */
data class TaskTreeNode(
    val id: String,
    val title: String,
    val state: String,
    val channelId: String,
    val channelTitle: String?,
    val parentId: String?,
    val predecessors: List<String>,
    val createdAt: String,
) {
    companion object {
        /**
         * From a stored task's fields and its canonical JSON (seam: the transport layer's task record supplies these).
         * Relationship ids come from `metadata` (or the row itself for older payloads).
         */
        fun from(id: String, title: String, state: String, channelId: String, createdAt: String, raw: JsonObject,
                 channelTitle: String? = null): TaskTreeNode {
            val metadata = raw["metadata"] as? JsonObject ?: raw
            val seen = HashSet<String>()
            val predecessors = (metadata["predecessor_task_ids"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { lowerUuid(it) }.filter { seen.add(it) }
            return TaskTreeNode(id, title, state, channelId, channelTitle ?: string(raw["channel_title"]), lowerUuid(metadata["parent_task_id"]),
                predecessors, createdAt)
        }

        /** From a Tower task row (`serializeFlightDeckPgTask` shape); null when its ids are not UUIDs. */
        fun fromRow(raw: JsonObject, channelTitle: String? = null): TaskTreeNode? {
            val id = lowerUuid(raw["id"]) ?: return null
            val channel = lowerUuid(raw["channel_id"]) ?: return null
            return from(id, string(raw["title"]) ?: "Untitled task", string(raw["state"]) ?: "new", channel, string(raw["created_at"]) ?: "", raw, channelTitle)
        }
    }
}

/** The view a tree naplet saves in its layout. */
data class TaskTreeView(
    val instanceId: String,
    val rootTaskId: String? = null,
    val collapsed: List<String> = emptyList(),
    val upstream: List<String> = emptyList(),
    val scale: Double = 1.0,
) {
    fun toJson(): JsonObject = JsonObject(linkedMapOf(
        "instance_id" to JsonPrimitive(instanceId), "root_task_id" to (rootTaskId?.let(::JsonPrimitive) ?: JsonNull),
        "collapsed" to JsonArray(collapsed.map(::JsonPrimitive)), "upstream" to JsonArray(upstream.map(::JsonPrimitive)),
        "viewport" to JsonObject(linkedMapOf("x" to JsonPrimitive(0), "y" to JsonPrimitive(0), "scale" to JsonPrimitive(scale)))))

    companion object {
        /** From a naplet's saved layout; a naplet without one uses its own id as the instance. */
        fun fromLayout(savedLayout: JsonObject, napletId: String): TaskTreeView {
            val tree = savedLayout["task_tree"] as? JsonObject ?: JsonObject(emptyMap())
            val scale = JsonFields.finite((tree["viewport"] as? JsonObject)?.get("scale"))?.let { minOf(2.0, maxOf(0.25, it)) } ?: 1.0
            return TaskTreeView(string(tree["instance_id"])?.takeIf { it.isNotEmpty() } ?: napletId, lowerUuid(tree["root_task_id"]),
                (tree["collapsed"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { lowerUuid(it) },
                (tree["upstream"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { lowerUuid(it) }, scale)
        }
    }
}

/** A bounded projection of the tree from its root. */
data class TaskTreeGraph(
    val nodes: List<TaskTreeNode> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val references: List<Reference> = emptyList(),
    /** Grid cell of each node: columns follow dependencies (successors to the right), rows hierarchy (children below). */
    val positions: Map<String, Cell> = emptyMap(),
    val bounded: Boolean = false,
) {
    data class Edge(val from: String, val to: String, val child: Boolean)
    data class Reference(val taskId: String, val targetId: String, val parent: Boolean, val available: Boolean)
    data class Cell(val x: Int, val y: Int)

    companion object {
        fun project(tasks: List<TaskTreeNode>, root: String, collapsed: List<String> = emptyList(), upstream: List<String> = emptyList(),
                    limit: Int = 100): TaskTreeGraph {
            val byId = LinkedHashMap<String, TaskTreeNode>()
            for (task in tasks) byId[task.id] = task
            val outgoing = HashMap<String, MutableList<Edge>>()
            for (task in byId.values.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
                task.parentId?.let { outgoing.getOrPut(it) { ArrayList() }.add(Edge(it, task.id, true)) }
                for (p in task.predecessors) outgoing.getOrPut(p) { ArrayList() }.add(Edge(p, task.id, false))
            }
            val closed = collapsed.toSet()
            val visited = HashSet<String>()
            val queue = (listOf(root) + upstream).toMutableList()
            val queued = queue.toHashSet()
            val nodes = ArrayList<TaskTreeNode>()
            val budget = maxOf(1, minOf(2000, limit))
            var cursor = 0
            while (cursor < queue.size && nodes.size < budget) {
                val key = queue[cursor]; cursor += 1
                if (!visited.add(key)) continue
                val task = byId[key] ?: continue
                nodes.add(task)
                if (key in closed) continue
                for (link in outgoing[key] ?: emptyList()) if (queued.add(link.to)) queue.add(link.to)
            }
            val visible = nodes.map { it.id }.toSet()
            val positions = HashMap<String, Cell>()
            val occupied = HashSet<String>()
            fun reserve(id: String, x: Int, y0: Int) {
                var y = y0
                while ("$x:$y" in occupied) y += 1
                occupied.add("$x:$y")
                positions[id] = Cell(x, y)
            }
            fun order(task: TaskTreeNode) = nodes.indexOf(task).coerceAtLeast(0)
            if (root in visible) reserve(root, 0, 0)
            var pending = nodes.filter { positions[it.id] == null }
            while (pending.isNotEmpty()) {
                val deferred = ArrayList<TaskTreeNode>()
                var progressed = false
                for (task in pending) {
                    val predecessors = task.predecessors.filter { it in visible && it != task.id }
                    val placed = predecessors.firstOrNull { positions[it] != null }
                    if (predecessors.isNotEmpty() && placed == null) { deferred.add(task); continue }
                    val parent = task.parentId
                    if (predecessors.isEmpty() && parent != null && parent in visible && positions[parent] == null) { deferred.add(task); continue }
                    val origin = placed?.let { positions[it] }
                    val parentOrigin = parent?.let { positions[it] }
                    when {
                        origin != null -> reserve(task.id, origin.x + 1, origin.y)
                        parentOrigin != null -> reserve(task.id, parentOrigin.x, parentOrigin.y + 1)
                        else -> reserve(task.id, 0, order(task))
                    }
                    progressed = true
                }
                if (!progressed && deferred.isNotEmpty()) { val first = deferred.removeAt(0); reserve(first.id, 0, order(first)) }
                pending = deferred
            }
            val edges = ArrayList<Edge>()
            val references = ArrayList<Reference>()
            for (task in nodes) {
                for (link in outgoing[task.id] ?: emptyList()) if (link.to in visible) edges.add(link)
                val parent = task.parentId
                if (parent != null && parent !in visible) references.add(Reference(task.id, parent, true, byId[parent] != null))
                for (p in task.predecessors) if (p !in visible) references.add(Reference(task.id, p, false, byId[p] != null))
            }
            return TaskTreeGraph(nodes, edges, references, positions, cursor < queue.size)
        }
    }
}

sealed class TaskTreeError(message: String) : Exception(message) {
    data object Unavailable : TaskTreeError("Task relationship unavailable. Access may have changed.")
    data object Locked : TaskTreeError("Unlock to add tasks.")
    data class Failed(val detail: String) : TaskTreeError(detail)
}

/**
 * Tower reads and writes for task tree naplets. Reads are stored like other task reads; creation is one confirmed
 * request with a stable `client_request_id`, so a retry after an uncertain answer finds the same task.
 */
object TaskTreeService {
    /** One page of `GET /tasks/:t/relationships`. */
    data class RelationshipsPage(
        val task: JsonObject,
        val tasks: List<JsonObject>,
        val channelTitles: Map<String, String>,
        val unavailable: Boolean,
        val next: String?,
    )

    /** Reads one relationships page (throws [TowerHttpFailure] for refusals). */
    fun interface RelationshipsReader {
        suspend fun page(taskId: String, cursor: String?): RelationshipsPage
    }

    data class Loaded(val tasks: List<TaskTreeNode>, val rows: List<JsonObject>, val unavailable: Boolean, val partial: Boolean)

    /** Checks and splits one relationships answer for `taskId` in `workspaceId`. */
    fun parseRelationships(o: JsonObject, workspaceId: String, taskId: String, cursor: String?): RelationshipsPage {
        val row = o["task"] as? JsonObject
        val rows = (o["tasks"] as? JsonArray)?.map { it as? JsonObject ?: throw TowerError.Malformed("task relationships") }
        if (lowerUuid(o["workspace_id"]) != workspaceId.lowercase() || row == null || rows == null) throw TowerError.Malformed("task relationships")
        if (lowerUuid(row["id"]) != taskId.lowercase()) throw TowerError.Malformed("task relationships")
        if (!(listOf(row) + rows).all { lowerUuid(it["workspace_id"]) == workspaceId.lowercase() && lowerUuid(it["id"]) != null && lowerUuid(it["channel_id"]) != null }) {
            throw TowerError.Malformed("task relationships")
        }
        val titles = HashMap<String, String>()
        for (r in listOf(row) + rows) {
            val c = lowerUuid(r["channel_id"]); val t = string(r["channel_title"])
            if (c != null && t != null) titles[c] = t
        }
        val hasMore = JsonFields.isTrue(o["has_more"])
        val next = if (hasMore) string(o["next_cursor"]) else null
        if (hasMore && (next == null || next == cursor)) throw TowerError.Malformed("task relationships cursor")
        return RelationshipsPage(row, rows, titles, JsonFields.isTrue(o["unavailable"]), next)
    }

    /** The authorized tasks reachable from `root` (and `upstream` context tasks), bounded like the web's tree. */
    suspend fun load(reader: RelationshipsReader, root: String, upstream: List<String> = emptyList(), limit: Int = 100): Loaded {
        data class Item(val id: String, var cursor: String?, val contextOnly: Boolean)
        val found = LinkedHashMap<String, TaskTreeNode>()
        val queue = ArrayList<Item>()
        queue.add(Item(root, null, false)); upstream.forEach { queue.add(Item(it, null, true)) }
        val visited = HashSet<String>()
        var unavailable = false
        var requests = 0
        val records = ArrayList<JsonObject>()
        while (queue.isNotEmpty() && found.size < limit && requests < maxOf(100, limit)) {
            val item = queue[0]
            requests += 1
            val page = try {
                reader.page(item.id, item.cursor)
            } catch (e: Exception) {
                if (e is TowerHttpFailure && e.status in setOf(401, 403, 404)) {
                    if (item.id == root) throw TaskTreeError.Unavailable
                    queue.removeAt(0); visited.add(item.id); unavailable = true; continue
                }
                throw e
            }
            val rows = if (item.contextOnly) listOf(page.task) else listOf(page.task) + page.tasks
            records.addAll(rows)
            for (r in rows) TaskTreeNode.fromRow(r, page.channelTitles[lowerUuid(r["channel_id"])])?.let { found[it.id] = it }
            unavailable = unavailable || page.unavailable
            if (page.next != null && !item.contextOnly) item.cursor = page.next else { queue.removeAt(0); visited.add(item.id) }
            if (!item.contextOnly) {
                for (r in page.tasks) {
                    val id = lowerUuid(r["id"]) ?: continue
                    if (id !in visited && queue.none { it.id == id }) queue.add(Item(id, null, false))
                }
            }
        }
        return Loaded(found.values.toList(), records, unavailable, queue.isNotEmpty())
    }

    /**
     * `POST /channels/:c/tasks` body: a child of `parent`, or the successor of `predecessor` (in its parent), or a base
     * task. The title must not be blank.
     */
    fun createBody(title: String, parent: String? = null, predecessor: TaskTreeNode? = null, requestId: String): JsonObject {
        val name = title.trim()
        if (name.isEmpty()) throw TaskTreeError.Failed("Enter a title.")
        val parentId = parent ?: predecessor?.parentId
        val metadata = JsonObject(linkedMapOf("parent_task_id" to (parentId?.let(::JsonPrimitive) ?: JsonNull),
            "predecessor_task_ids" to JsonArray(predecessor?.let { listOf(JsonPrimitive(it.id)) } ?: emptyList<JsonElement>())))
        return JsonObject(linkedMapOf("client_request_id" to JsonPrimitive(requestId), "title" to JsonPrimitive(name), "state" to JsonPrimitive("new"),
            "metadata" to metadata))
    }

    /** Reads the confirmed task from a create answer, checking it landed where it was asked to. */
    fun confirmCreated(status: Int, o: JsonObject?, channelId: String, scopeId: String?): JsonObject {
        val row = o?.get("task") as? JsonObject
        if (status !in 200..299 || row == null || TaskTreeNode.fromRow(row) == null) {
            if (status == 401 || status == 403) throw TaskTreeError.Failed("You can't add tasks in this channel.")
            throw TaskTreeError.Failed("The task was not confirmed (HTTP $status). Retry the same title.")
        }
        val taskScope = lowerUuid(row["scope_id"])
        if (lowerUuid(row["channel_id"]) != channelId.lowercase() || !(scopeId == null || taskScope == null || taskScope == scopeId)) {
            throw TaskTreeError.Failed("Task destination was not confirmed. Retry the same entry.")
        }
        return row
    }
}
