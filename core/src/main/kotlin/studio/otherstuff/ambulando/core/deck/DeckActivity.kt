package studio.otherstuff.ambulando.core.deck

import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord

/**
 * Deck's Inbox and Recent cards, built from stored records with the web app's overview rules (threads, tasks,
 * documents and files, the inbox filter and recent channels).
 *
 * Deck's Inbox is an activity feed for the current Scope | Channel: conversations, open tasks (finished ones only while
 * unread), documents and files, newest first. Unread comes from Tower's view states, "working" from agent activity that
 * has not ended.
 *
 * The input rows below are the seam to the store: its readers fill them from stored task, document, file and view
 * state records.
 */
object DeckActivity {
    enum class Kind(val wire: String) { CHAT("chat"), TASK("task"), DOCUMENT("document"), FILE("file") }

    data class Item(
        val kind: Kind,
        val recordId: String,
        val channelId: String?,
        val scopeId: String?,
        val title: String,
        /** Chat: latest message; task: state label; document: summary; file: description or type. */
        val subtitle: String,
        /** Why it is here ("2 recent comments", "Task updated", …). */
        val reason: String,
        val activityAt: String,
        val isUnread: Boolean,
        val isWorking: Boolean,
        val taskState: String?,
        /** Chat: false for a channel post stored without a thread id (it opens the channel). */
        val isThread: Boolean = true,
        /** Lower-cased text searched by the Inbox search field. */
        val searchText: String,
    ) {
        val id: String get() = "${kind.wire}:$recordId"
    }

    /** One conversation with its latest stored message (a Deck chat card or a Recent channel row). */
    data class ThreadActivity(
        val threadId: String,
        val channelId: String,
        /** False for a channel post stored without a thread id (opens the channel instead). */
        val isThread: Boolean,
        val title: String?,
        val rootBody: String?,
        val latestBody: String,
        val latestAt: String,
        val messageCount: Int,
    )

    /** Comment activity per parent record: count and newest time (tasks: comments by others; documents: open comments). */
    data class CommentActivity(val count: Int, val latestAt: String?)

    data class TaskRow(
        val id: String,
        val scopeId: String?,
        val channelId: String,
        val title: String,
        val description: String?,
        val state: String,
        val createdAt: String,
        val updatedAt: String,
    )

    data class DocRow(
        val id: String,
        val scopeId: String?,
        val channelId: String?,
        val title: String,
        val summary: String?,
        val archived: Boolean,
        val createdAt: String,
        val updatedAt: String,
    )

    data class FileRow(
        val id: String,
        val scopeId: String?,
        val channelId: String?,
        val displayName: String,
        val description: String?,
        val contentType: String?,
        val archived: Boolean,
        val createdAt: String,
        val updatedAt: String,
    )

    /** The viewer's read position for one resource. `resourceType` is `task`, `document` or `thread`. */
    data class ViewStateRow(val resourceType: String, val resourceId: String, val unread: Boolean)

    data class Sources(
        val threads: List<ThreadActivity> = emptyList(),
        val tasks: List<TaskRow> = emptyList(),
        val docs: List<DocRow> = emptyList(),
        val files: List<FileRow> = emptyList(),
        val viewStates: List<ViewStateRow> = emptyList(),
        val taskComments: Map<String, CommentActivity> = emptyMap(),
        val docComments: Map<String, CommentActivity> = emptyMap(),
        val activities: List<AgentActivityRecord> = emptyList(),
        /** Channel → scope, for records stored without a scope id. */
        val channelScopes: Map<String, String> = emptyMap(),
    )

    /** Task states the web counts as "completed": shown only while unread. */
    internal val completedStates: Set<String> = setOf("done", "complete", "completed")
    internal val terminalStates: Set<String> = setOf("done", "complete", "completed", "archive", "archived", "cancelled", "canceled")

    /** Board labels of Tower's task states; other states are shown capitalised. */
    fun taskStateLabel(state: String): String = when (state) {
        "new" -> "New"
        "backlog" -> "Backlog"
        "ready" -> "Ready"
        "in_progress" -> "In progress"
        "review" -> "Review"
        "blocked" -> "Blocked"
        "done" -> "Done"
        "archive", "archived" -> "Archived"
        else -> state.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { word ->
            word.lowercase().replaceFirstChar { it.titlecase() }
        }
    }

    /** Every Deck Inbox item in `context`, newest first. */
    fun inbox(sources: Sources, context: WorkContext): List<Item> {
        val unread = HashMap<String, Boolean>()
        for (v in sources.viewStates) { val key = "${v.resourceType}:${v.resourceId}"; unread[key] = (unread[key] ?: false) || v.unread }
        val working = workingKeys(sources.activities)
        fun scope(scopeId: String?, channelId: String?): String? = scopeId ?: channelId?.let { sources.channelScopes[it] }
        fun matches(scopeId: String?, channelId: String?): Boolean {
            val wanted = context.scopeId ?: return true
            context.channelId?.let { return channelId == it }
            return scope(scopeId, channelId) == wanted
        }
        val items = ArrayList<Item>()
        for (thread in sources.threads) {
            if (!matches(null, thread.channelId)) continue
            val title = threadTitle(thread)
            items.add(Item(Kind.CHAT, thread.threadId, thread.channelId, scope(null, thread.channelId), title, plainMentions(thread.latestBody),
                if (thread.messageCount == 1) "1 message" else "${thread.messageCount} messages", thread.latestAt,
                unread["thread:${thread.threadId}"] == true, "thread:${thread.threadId}" in working, null, thread.isThread,
                listOf(title, thread.latestBody, thread.rootBody ?: "").joinToString(" ").lowercase()))
        }
        for (task in sources.tasks) {
            if (!matches(task.scopeId, task.channelId)) continue
            val state = task.state.lowercase()
            val isUnread = unread["task:${task.id}"] == true
            if (state in terminalStates && !(state in completedStates && isUnread)) continue
            val comments = sources.taskComments[task.id]
            val updated = task.updatedAt.ifEmpty { task.createdAt }
            val commentDrove = (comments?.latestAt ?: "") > updated
            val activity = maxOf(updated, comments?.latestAt ?: "")
            if (activity.isEmpty()) continue
            val label = taskStateLabel(task.state)
            val reason = if (commentDrove) "${comments!!.count} recent ${if (comments.count == 1) "comment" else "comments"}"
            else if (task.updatedAt.isEmpty()) "Task created" else "Task updated"
            items.add(Item(Kind.TASK, task.id, task.channelId, scope(task.scopeId, task.channelId), task.title.ifEmpty { "Untitled task" }, label,
                reason, activity, isUnread, "task:${task.id}" in working, task.state,
                searchText = listOf(task.title, task.description ?: "", task.state, label).joinToString(" ").lowercase()))
        }
        for (doc in sources.docs) {
            if (doc.archived || !matches(doc.scopeId, doc.channelId)) continue
            val comments = sources.docComments[doc.id]
            val updated = doc.updatedAt.ifEmpty { doc.createdAt }
            val activity = maxOf(updated, comments?.latestAt ?: "")
            if (activity.isEmpty()) continue
            val open = comments?.count ?: 0
            items.add(Item(Kind.DOCUMENT, doc.id, doc.channelId, scope(doc.scopeId, doc.channelId), doc.title.ifEmpty { "Untitled document" },
                doc.summary ?: "Document",
                if (open > 0) "$open unresolved ${if (open == 1) "comment" else "comments"}" else if (doc.updatedAt.isEmpty()) "Document created" else "Document updated",
                activity, unread["document:${doc.id}"] == true, "document:${doc.id}" in working, null,
                searchText = listOf(doc.title, doc.summary ?: "").joinToString(" ").lowercase()))
        }
        for (file in sources.files) {
            if (file.archived || !matches(file.scopeId, file.channelId)) continue
            val activity = file.updatedAt.ifEmpty { file.createdAt }
            items.add(Item(Kind.FILE, file.id, file.channelId, scope(file.scopeId, file.channelId), file.displayName,
                file.description ?: file.contentType ?: "File",
                if (file.updatedAt.isEmpty() || file.updatedAt == file.createdAt) "Uploaded file" else "Edited file",
                activity, false, false, null,
                searchText = listOf(file.displayName, file.description ?: "", file.contentType ?: "").joinToString(" ").lowercase()))
        }
        return items.sortedWith { a, b -> if (a.activityAt != b.activityAt) b.activityAt.compareTo(a.activityAt) else a.id.compareTo(b.id) }
    }

    private val whitespace = Regex("\\s+")

    /** The web's type toggles and search: any combination of kinds, then a case-insensitive substring match. */
    fun filter(items: List<Item>, kinds: Set<Kind>, query: String): List<Item> {
        val needle = query.lowercase().split(whitespace).filter { it.isNotEmpty() }.joinToString(" ")
        return items.filter { item ->
            item.kind in kinds && (needle.isEmpty() || item.searchText.split(whitespace).filter { it.isNotEmpty() }.joinToString(" ").contains(needle))
        }
    }

    /**
     * Recent channels: each channel's newest conversation in the scope (the channel choice is ignored, as on the web),
     * at most `limit`.
     */
    fun recentChannels(threads: List<ThreadActivity>, scopeId: String?, channelScopes: Map<String, String>, limit: Int = 10): List<ThreadActivity> {
        val seen = HashSet<String>()
        val rows = ArrayList<ThreadActivity>()
        for (thread in threads.sortedWith { a, b -> if (a.latestAt != b.latestAt) b.latestAt.compareTo(a.latestAt) else a.threadId.compareTo(b.threadId) }) {
            if (scopeId != null && channelScopes[thread.channelId] != scopeId) continue
            if (!seen.add(thread.channelId)) continue
            rows.add(thread)
            if (rows.size == limit) break
        }
        return rows
    }

    private val newline = Regex("[\n\r\u000B\u000C\u0085  ]")

    fun threadTitle(thread: ThreadActivity): String {
        val source = listOfNotNull(thread.title, thread.rootBody, thread.latestBody).map(::plainMentions).firstOrNull { it.isNotBlank() } ?: "Conversation"
        var line = source.split(newline).firstOrNull { it.isNotEmpty() } ?: source
        // A Markdown heading's marks are not part of the title.
        if (line.startsWith("#")) line = line.dropWhile { it == '#' }.trim(' ', '\t')
        return if (line.codePointCount(0, line.length) > 90) line.substring(0, line.offsetByCodePoints(0, 90)) + "…" else line
    }

    /** Records an agent is working on now (activity not ended): `thread:<id>`. */
    internal fun workingKeys(activities: List<AgentActivityRecord>): Set<String> =
        activities.filter { !it.isTerminal }.mapNotNull { a -> a.threadId?.let { "thread:$it" } }.toSet()

    // Mention tokens: `@[Label](mention:kind:id)`, shown as `@Label` for people and agents and as the label for records.
    private val mentionToken = Regex("@\\[([^\\]]+)\\]\\(mention:([A-Za-z_][A-Za-z0-9_-]*):([^)\\s]+)\\)")
    private val labelDelimiters = Regex("[\\[\\]()\\n\\r]")

    /**
     * Plain-text preview of a message body with mention tokens shown as `@Label` (records as their label). Seam: the
     * chat module's mention codec provides the same once it is ported.
     */
    fun plainMentions(body: String): String = mentionToken.replace(body) { m ->
        val label = m.groupValues[1]; val kind = m.groupValues[2]; val id = m.groupValues[3]
        if (kind == "agent" || kind == "person") {
            val cleaned = label.replace(labelDelimiters, " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            "@" + (if (cleaned.isEmpty()) id else cleaned).take(80)
        } else label
    }
}
