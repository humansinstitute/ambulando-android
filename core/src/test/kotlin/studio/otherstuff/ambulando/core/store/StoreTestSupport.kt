package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import java.io.File

/** Synthetic store fixtures. Ids are placeholder UUIDs; hosts are reserved example domains. */
object StoreFixtures {
    const val W = Fixtures.WORKSPACE_ID
    const val W2 = Fixtures.WORKSPACE_ID_2

    fun store(dir: File = tempDir(), intervalMillis: Long = 10): LocalStore = LocalStore(dir, intervalMillis)

    fun scope(id: String, name: String, workspace: String = W, rowVersion: Long = 1, updated: String = "2026-10-01T00:00:00.000Z"): ScopeRecord {
        val o = Fixtures.scope(id, workspace, name, updated)
        o["row_version"] = rowVersion
        return ScopeRecord.parse(Fixtures.obj(o))
    }

    fun channel(id: String, scope: String, name: String, workspace: String = W, position: Double? = null): ChannelRecord =
        ChannelRecord.parse(Fixtures.obj(Fixtures.channel(id, scope, workspace, name, position)))

    fun workspace(id: String, label: String): WorkspaceRecord = WorkspaceRecord.parse(Fixtures.obj(Fixtures.workspace(id, label)))

    fun message(
        id: String, body: String, at: String, channel: String = Fixtures.CHANNEL_1, workspace: String = W, thread: String? = null,
        source: String? = null, sender: String? = TestKeys.NPUB, clientRequestId: String? = null, rowVersion: Long = 1,
    ) = MessageRecord(
        workspaceId = workspace, id = id, channelId = channel, threadId = thread, threadSourceId = source, body = body, senderNpub = sender,
        clientRequestId = clientRequestId, createdAt = at, updatedAt = at, rowVersion = rowVersion,
        raw = Fixtures.obj(mapOf("id" to id, "body" to body, "row_version" to rowVersion)),
    )

    suspend fun connect(store: LocalStore, origin: String = "https://tower.example.com", signer: String = TestKeys.NPUB): ConnectionRecord =
        store.writer.saveConnection(ConnectionRecord.create(TowerOrigin(origin), signer, null))
}

/** Polls `condition` (real time) until it holds or `timeoutMillis` passes. */
suspend fun waitFor(timeoutMillis: Long = 3_000, condition: suspend () -> Boolean) {
    withTimeout(timeoutMillis) { while (!condition()) delay(10) }
}
