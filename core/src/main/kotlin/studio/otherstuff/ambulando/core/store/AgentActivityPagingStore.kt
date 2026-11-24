package studio.otherstuff.ambulando.core.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord

/** Private to a connection and exact activity query, in that account's workspace partition. */
@Serializable
data class AgentActivityTraversal(val next: String? = null, val seen: Set<String> = emptySet(), val restarted: Boolean = false, val pending: Boolean = next != null,
    val anchor: String? = next, val span: Long = 1, val steps: Long = 0, val completedAt: Long = 0)

suspend fun StoreReader.agentActivityTraversal(key: String): AgentActivityTraversal? =
    state(key)?.let { Json.decodeFromString<AgentActivityTraversal>(it) }

/** The cursor and completion claim commit with the rows they describe. A head refresh cannot reset pending history. */
suspend fun StoreWriter.saveAgentActivityPage(
    workspaceId: String, channelId: String, records: List<AgentActivityRecord>, key: String,
    traversal: AgentActivityTraversal, syncKey: String, head: Boolean = false, reuseRecent: Boolean = false,
): AgentActivityTraversal = write {
    if (channelId in db.removedChannelIds(workspaceId)) throw kotlinx.coroutines.CancellationException("Channel access removed")
    mergeAgentActivities(workspaceId, channelId, records)
    val previous = db.textValue("SELECT value FROM app_state WHERE key = ?", key)?.let { Json.decodeFromString<AgentActivityTraversal>(it) }
    // The regular 20-second head poll need not replay the entire channel each time. A terminal pass
    // remains dated at its actual completion; a five-minute sweep recovers late changes beyond the head.
    val recent = previous != null && System.currentTimeMillis() - previous.completedAt in 0 until 300_000L
    val saved = if (head && previous != null && (previous.pending || (reuseRecent && recent))) previous
        else if (!traversal.pending) traversal.copy(completedAt = System.currentTimeMillis()) else traversal
    db.run("INSERT OR REPLACE INTO app_state(key,value) VALUES(?,?)", key, Json.encodeToString(saved))
    if (!(head && saved === previous && !saved.pending)) db.run("""INSERT INTO list_sync(workspace_id,key,complete,synced_at) VALUES(?,?,?,?)
        ON CONFLICT(workspace_id,key) DO UPDATE SET complete=excluded.complete,synced_at=excluded.synced_at""",
        workspaceId, syncKey, !saved.pending, StoreTime.now())
    saved
}
