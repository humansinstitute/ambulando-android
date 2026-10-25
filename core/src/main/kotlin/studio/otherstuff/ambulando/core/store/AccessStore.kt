package studio.otherstuff.ambulando.core.store

// Non-destructive access loss (Android owner decision). Where the iOS store deletes local copies because a scope,
// channel or record stopped being visible (403/404, missing from a complete list, access revoked), this store sets
// `access_removed = 1` instead: the rows stay readable (screens label them "no longer available"), every sync selection
// excludes them, and new content for a flagged channel is not written. Records Tower reports deleted (tombstones) are
// still removed, and disconnect / identity removal still delete everything for the connection.
//
// A flag is cleared when Tower shows the record again: any upsert of a flagged row rewrites it with the flag cleared,
// and a flagged channel or scope that is listed again also clears the flags of its content (the next catch-up then
// re-flags whatever is still missing).

/** A record (or a subtree) that stopped being visible to this identity. */
sealed class AccessTarget {
    /** The scope, its channels with all their content, and its Context Tree. */
    data class Scope(val id: String) : AccessTarget()
    /** The channel and its messages, threads, tasks, documents, files, folders, comments and agent activity. */
    data class Channel(val id: String) : AccessTarget()
    data class Message(val id: String) : AccessTarget()
    /** The thread and its messages. */
    data class Thread(val id: String) : AccessTarget()
    /** The task and its comments. */
    data class Task(val id: String) : AccessTarget()
    /** The document and its comments (a cached body stays readable). */
    data class Doc(val id: String) : AccessTarget()
    data class File(val id: String) : AccessTarget()
    data class Folder(val id: String) : AccessTarget()
    data class Comment(val id: String) : AccessTarget()
    /** The component and its references. */
    data class ContextComponent(val id: String) : AccessTarget()
    /** A scope's whole Context Tree. */
    data class ContextTree(val scopeId: String) : AccessTarget()
    /** The canvas definition (its stored snapshot and boards stay readable). */
    data class Canvas(val id: String) : AccessTarget()
    /** Tower's materialised agent activity of one channel (its activity route answered 403/404). */
    data class AgentActivity(val channelId: String) : AccessTarget()
}

/** Tables with an `access_removed` flag, for [accessRemovedIds]. */
enum class FlaggedTable(internal val sql: String) {
    SCOPES("scopes"), CHANNELS("channels"), MESSAGES("messages"), THREADS("threads"), TASKS("tasks"), COMMENTS("comments"),
    DOCS("docs"), FILES("files"), FOLDERS("folders"), CONTEXT_COMPONENTS("context_components"),
    CONTEXT_REFERENCES("context_references"), CANVASES("canvases"), AGENT_ACTIVITIES("agent_activities"),
}

/**
 * Flags records of one workspace as no longer visible (never deletes). Returns how many rows were newly flagged; emits
 * the affected tables plus [StoreTable.ACCESS] so paused screens do not resume with unlabelled rows.
 */
suspend fun StoreWriter.markAccessRemoved(workspaceId: String, targets: Collection<AccessTarget>): Int = write {
    targets.sumOf { flag(workspaceId, it) }
}

suspend fun StoreWriter.markAccessRemoved(workspaceId: String, vararg targets: AccessTarget): Int = markAccessRemoved(workspaceId, targets.toList())

/**
 * Flags one connection's workspace as no longer listed (membership lost). Its rows stay readable under the flag; it is
 * left out of [syncableWorkspaces]. Listing it again ([replaceWorkspaces]) clears the flag.
 */
suspend fun StoreWriter.markWorkspaceAccessRemoved(connectionId: String, workspaceId: String): Boolean = write {
    flagWorkspace(connectionId, workspaceId)
}

internal fun WriteTx.flagWorkspace(connectionId: String, workspaceId: String): Boolean {
    val n = db.run("UPDATE workspaces SET access_removed = 1 WHERE connection_id = ? AND workspace_id = ? AND access_removed = 0", connectionId, workspaceId)
    if (n > 0) {
        emit(StoreTable.WORKSPACES)
        emit(StoreTable.ACCESS)
        emit(StoreTable.ACCESS, workspaceId)
        emit(workspaceContentKeys(workspaceId))
    }
    return n > 0
}

internal fun workspaceContentKeys(w: String): Set<ChangeKey> = listOf(
    StoreTable.SCOPES, StoreTable.CHANNELS, StoreTable.MESSAGES, StoreTable.THREADS, StoreTable.MEMBERS, StoreTable.TASKS,
    StoreTable.COMMENTS, StoreTable.DOCS, StoreTable.DOC_BODIES, StoreTable.FILES, StoreTable.FOLDERS, StoreTable.VIEW_STATES,
    StoreTable.REACTIONS, StoreTable.CONTEXT_TREE, StoreTable.CANVASES, StoreTable.BOARDS, StoreTable.AGENTS, StoreTable.TOOLBOX, StoreTable.PINS,
).mapTo(LinkedHashSet()) { ChangeKey(it, w) }

internal fun channelContentKeys(w: String): Set<ChangeKey> = listOf(
    StoreTable.CHANNELS, StoreTable.MESSAGES, StoreTable.THREADS, StoreTable.TASKS, StoreTable.COMMENTS, StoreTable.DOCS,
    StoreTable.DOC_BODIES, StoreTable.FILES, StoreTable.FOLDERS, StoreTable.VIEW_STATES, StoreTable.REACTIONS, StoreTable.CONTEXT_TREE,
    StoreTable.AGENTS,
).mapTo(LinkedHashSet()) { ChangeKey(it, w) }

private fun SqlConnection.setFlag(table: String, where: String, vararg args: Any?, value: Boolean = true): Int =
    run("UPDATE $table SET access_removed = ? WHERE $where AND access_removed = ?", value, *args, !value)

/** Flags one target; emits its keys (and the access key when anything changed). Returns rows newly flagged. */
internal fun WriteTx.flag(w: String, target: AccessTarget): Int {
    val n: Int
    val keys = LinkedHashSet<StoreTable>()
    when (target) {
        is AccessTarget.Scope -> return flagScope(w, target.id)
        is AccessTarget.Channel -> return flagChannel(w, target.id)
        is AccessTarget.Message -> { n = db.setFlag("messages", "workspace_id = ? AND id = ?", w, target.id); keys.add(StoreTable.MESSAGES) }
        is AccessTarget.Thread -> {
            n = db.setFlag("threads", "workspace_id = ? AND id = ?", w, target.id) +
                db.setFlag("messages", "workspace_id = ? AND thread_id = ?", w, target.id)
            keys += listOf(StoreTable.THREADS, StoreTable.MESSAGES)
        }
        is AccessTarget.Task -> {
            n = db.setFlag("tasks", "workspace_id = ? AND id = ?", w, target.id) +
                db.setFlag("comments", "workspace_id = ? AND parent = 'task' AND parent_id = ?", w, target.id)
            keys += listOf(StoreTable.TASKS, StoreTable.COMMENTS)
        }
        is AccessTarget.Doc -> {
            n = db.setFlag("docs", "workspace_id = ? AND id = ?", w, target.id) +
                db.setFlag("comments", "workspace_id = ? AND parent = 'doc' AND parent_id = ?", w, target.id)
            keys += listOf(StoreTable.DOCS, StoreTable.COMMENTS)
        }
        is AccessTarget.File -> { n = db.setFlag("files", "workspace_id = ? AND id = ?", w, target.id); keys.add(StoreTable.FILES) }
        is AccessTarget.Folder -> { n = db.setFlag("folders", "workspace_id = ? AND id = ?", w, target.id); keys.add(StoreTable.FOLDERS) }
        is AccessTarget.Comment -> { n = db.setFlag("comments", "workspace_id = ? AND id = ?", w, target.id); keys.add(StoreTable.COMMENTS) }
        is AccessTarget.ContextComponent -> {
            n = db.setFlag("context_components", "workspace_id = ? AND id = ?", w, target.id) +
                db.setFlag("context_references", "workspace_id = ? AND component_id = ?", w, target.id)
            keys.add(StoreTable.CONTEXT_TREE)
        }
        is AccessTarget.ContextTree -> { n = flagScopeSurfaces(w, target.scopeId); keys.add(StoreTable.CONTEXT_TREE) }
        is AccessTarget.Canvas -> { n = db.setFlag("canvases", "workspace_id = ? AND id = ?", w, target.id); keys += listOf(StoreTable.CANVASES, StoreTable.BOARDS) }
        is AccessTarget.AgentActivity -> {
            n = db.setFlag("agent_activities", "workspace_id = ? AND channel_id = ?", w, target.channelId)
            keys.add(StoreTable.AGENTS)
        }
    }
    if (n > 0) {
        for (k in keys) emit(k, w)
        emit(StoreTable.ACCESS, w)
    }
    return n
}

internal fun WriteTx.flagScope(w: String, scopeId: String): Int {
    var n = db.setFlag("scopes", "workspace_id = ? AND id = ?", w, scopeId)
    for (c in db.query("SELECT id FROM channels WHERE workspace_id = ? AND scope_id = ?", w, scopeId) { it.text(0)!! }) n += flagChannel(w, c)
    n += flagScopeSurfaces(w, scopeId)
    if (n > 0) {
        emit(channelContentKeys(w))
        emit(StoreTable.SCOPES, w)
        emit(StoreTable.ACCESS, w)
    }
    return n
}

internal fun WriteTx.flagScopeSurfaces(w: String, scopeId: String): Int =
    db.setFlag("context_references", "workspace_id = ? AND scope_id = ?", w, scopeId) +
        db.setFlag("context_components", "workspace_id = ? AND scope_id = ?", w, scopeId)

internal fun WriteTx.flagChannel(w: String, channelId: String): Int {
    val n = db.setFlag("channels", "workspace_id = ? AND id = ?", w, channelId) + setChannelContent(w, channelId, true)
    if (n > 0) {
        emit(channelContentKeys(w))
        emit(StoreTable.ACCESS, w)
    }
    return n
}

/**
 * Sets (or clears) the flag on everything stored for a channel. Drafts, unsent rows, reactions, view states and cached
 * bodies are untouched: they belong to the user or are only reachable through the flagged rows.
 */
internal fun WriteTx.setChannelContent(w: String, c: String, value: Boolean): Int {
    var n = db.setFlag(
        "comments",
        "workspace_id = ? AND ((parent = 'task' AND parent_id IN (SELECT id FROM tasks WHERE workspace_id = ? AND channel_id = ?)) " +
            "OR (parent = 'doc' AND parent_id IN (SELECT id FROM docs WHERE workspace_id = ? AND channel_id = ?)))",
        w, w, c, w, c, value = value,
    )
    for (table in listOf("messages", "threads", "tasks", "docs", "files", "folders", "agent_activities")) {
        n += db.setFlag(table, "workspace_id = ? AND channel_id = ?", w, c, value = value)
    }
    return n
}

/** Channel ids of a workspace that are flagged (content for them is not written). */
internal fun SqlConnection.removedChannelIds(w: String): Set<String> =
    query("SELECT id FROM channels WHERE workspace_id = ? AND access_removed = 1", w) { it.text(0)!! }.toHashSet()

// MARK: Reads

/** Ids of flagged rows in one table of a workspace (for screens over records without their own flag field). */
suspend fun StoreReader.accessRemovedIds(workspaceId: String, table: FlaggedTable): Set<String> = read {
    query("SELECT id FROM ${table.sql} WHERE workspace_id = ? AND access_removed = 1", workspaceId) { it.text(0)!! }.toSet()
}

suspend fun StoreReader.isChannelAccessRemoved(workspaceId: String, channelId: String): Boolean = read {
    first(
        """SELECT 1 FROM channels c WHERE c.workspace_id = ? AND c.id = ? AND (c.access_removed = 1
           OR EXISTS (SELECT 1 FROM scopes s WHERE s.workspace_id = c.workspace_id AND s.id = c.scope_id AND s.access_removed = 1))""",
        workspaceId, channelId,
    ) { true } ?: false
}

/** Scopes sync may read: everything stored except flagged scopes. */
suspend fun StoreReader.syncableScopes(workspaceId: String) = read {
    query("SELECT ${Rows.SCOPE_COLUMNS} FROM scopes WHERE workspace_id = ? AND access_removed = 0 ORDER BY name COLLATE NOCASE, id", workspaceId) {
        Rows.scope(it, workspaceId)
    }
}

/** Channels catch-up may read: not flagged and not in a flagged scope. */
suspend fun StoreReader.syncableChannels(workspaceId: String) = read {
    query(
        """SELECT ${Rows.CHANNEL_COLUMNS.split(", ").joinToString(", ") { "c.$it" }} FROM channels c
           WHERE c.workspace_id = ? AND c.access_removed = 0
             AND NOT EXISTS (SELECT 1 FROM scopes s WHERE s.workspace_id = c.workspace_id AND s.id = c.scope_id AND s.access_removed = 1)
           ORDER BY c.scope_id, c.position IS NULL, c.position, c.name COLLATE NOCASE, c.id""",
        workspaceId,
    ) { Rows.channel(it, workspaceId) }
}

/** Targeted access check for queued work; unlike a full flagged-id scan this stays constant-size. */
suspend fun StoreReader.isAccessRemoved(workspaceId: String, table: FlaggedTable, id: String): Boolean = read {
    first("SELECT access_removed FROM ${table.sql} WHERE workspace_id = ? AND id = ?", workspaceId, id) { it.bool(0) } ?: false
}
