package studio.otherstuff.ambulando.core.store

// Live-update bookkeeping: tombstones from Tower events and the per-connection event cursor of each workspace
// (iOS LiveStore.swift).

/** A record Tower reported deleted. Applying it removes the local copy (a real deletion, unlike access loss). */
sealed class Tombstone {
    abstract val id: String
    data class Message(override val id: String) : Tombstone()
    /** The thread and its messages. */
    data class Thread(override val id: String) : Tombstone()
    /** The task and its comments. */
    data class Task(override val id: String) : Tombstone()
    /** The document, its cached body and its comments. */
    data class Doc(override val id: String) : Tombstone()
    data class File(override val id: String) : Tombstone()
    data class Comment(override val id: String) : Tombstone()
    data class Reaction(override val id: String) : Tombstone()
}

/** Removes records Tower reported deleted, in one transaction. Rows of other workspaces are never touched. */
suspend fun StoreWriter.applyTombstones(workspaceId: String, tombstones: Set<Tombstone>): Int {
    if (tombstones.isEmpty()) return 0
    return write {
        var removed = 0
        fun run(sql: String, id: String, vararg tables: StoreTable) {
            val n = db.run(sql, workspaceId, id)
            if (n > 0) { removed += n; for (t in tables) emit(t, workspaceId) }
        }
        for (t in tombstones) when (t) {
            is Tombstone.Message -> run("DELETE FROM messages WHERE workspace_id = ? AND id = ?", t.id, StoreTable.MESSAGES)
            is Tombstone.Thread -> {
                run("DELETE FROM threads WHERE workspace_id = ? AND id = ?", t.id, StoreTable.THREADS, StoreTable.MESSAGES)
                run("DELETE FROM messages WHERE workspace_id = ? AND thread_id = ?", t.id, StoreTable.MESSAGES)
            }
            is Tombstone.Task -> {
                run("DELETE FROM tasks WHERE workspace_id = ? AND id = ?", t.id, StoreTable.TASKS)
                dropTaskRelationships(workspaceId, t.id)
                run("DELETE FROM comments WHERE workspace_id = ? AND parent = 'task' AND parent_id = ?", t.id, StoreTable.COMMENTS)
            }
            is Tombstone.Doc -> {
                run("DELETE FROM docs WHERE workspace_id = ? AND id = ?", t.id, StoreTable.DOCS)
                run("DELETE FROM doc_bodies WHERE workspace_id = ? AND doc_id = ?", t.id, StoreTable.DOC_BODIES)
                run("DELETE FROM comments WHERE workspace_id = ? AND parent = 'doc' AND parent_id = ?", t.id, StoreTable.COMMENTS)
            }
            is Tombstone.File -> run("DELETE FROM files WHERE workspace_id = ? AND id = ?", t.id, StoreTable.FILES)
            is Tombstone.Comment -> run("DELETE FROM comments WHERE workspace_id = ? AND id = ?", t.id, StoreTable.COMMENTS)
            is Tombstone.Reaction -> run("DELETE FROM reactions WHERE workspace_id = ? AND id = ?", t.id, StoreTable.REACTIONS)
        }
        if (removed > 0) emit(StoreTable.ACCESS, workspaceId)
        removed
    }
}

/**
 * Saves the event cursor without a change notification (no screen renders it), so a stream of events does not wake
 * observers of app state.
 */
suspend fun StoreWriter.saveEventCursor(connectionId: String, workspaceId: String, cursor: String?) = write {
    val key = StoreStateKeys.eventCursor(connectionId, workspaceId)
    if (cursor != null) db.run("INSERT INTO app_state(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, cursor)
    else db.run("DELETE FROM app_state WHERE key = ?", key)
    Unit
}

suspend fun StoreReader.eventCursor(connectionId: String, workspaceId: String): String? = state(StoreStateKeys.eventCursor(connectionId, workspaceId))
