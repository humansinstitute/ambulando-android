package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.transport.MessageRecord

/** Canonical messages and effective membership are separate, so parent sync cannot erase inherited history. */
suspend fun StoreWriter.upsertEffectiveThreadPage(workspaceId: String, channelId: String, threadId: String, rows: List<MessageRecord>) = write {
    require(rows.all { it.workspaceId == workspaceId && it.channelId == channelId &&
        (it.effectiveThreadId == threadId || it.effectiveThreadId == null && it.threadId == threadId) })
    writeMessages(rows)
    for (row in rows) if (db.run("INSERT OR IGNORE INTO thread_message_members(workspace_id, thread_id, message_id) VALUES(?,?,?)", workspaceId, threadId, row.id) > 0) {
        emit(StoreTable.MESSAGES, workspaceId)
    }
}

/** Only a completed forward scan may prune membership; an interrupted scan keeps previously received history. */
suspend fun StoreWriter.finishEffectiveThread(workspaceId: String, channelId: String, threadId: String, keep: Set<String>) = write {
    val listed = db.query("SELECT message_id FROM thread_message_members WHERE workspace_id = ? AND thread_id = ?", workspaceId, threadId) { it.text(0)!! }
    for (id in listed) if (id !in keep) {
        db.run("DELETE FROM thread_message_members WHERE workspace_id = ? AND thread_id = ? AND message_id = ?", workspaceId, threadId, id)
        emit(StoreTable.MESSAGES, workspaceId)
    }
    // Access loss remains non-destructive on Android. Canonical ownership is never changed to hide an inherited row.
    val owned = db.query("SELECT id FROM messages WHERE workspace_id = ? AND channel_id = ? AND thread_id = ? AND access_removed = 0", workspaceId, channelId, threadId) { it.text(0)!! }
    for (id in owned) if (id !in keep) flag(workspaceId, AccessTarget.Message(id))
}

suspend fun StoreReader.isEffectiveThreadMember(workspaceId: String, channelId: String, threadId: String, messageId: String): Boolean = read {
    first("""SELECT 1 FROM messages WHERE workspace_id = ? AND channel_id = ? AND id = ? AND deleted = 0 AND access_removed = 0
      AND (thread_id = ? OR id IN (SELECT message_id FROM thread_message_members WHERE workspace_id = ? AND thread_id = ?))""",
      workspaceId, channelId, messageId, threadId, workspaceId, threadId) { true } ?: false
}
