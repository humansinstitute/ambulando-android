package studio.otherstuff.ambulando.core.deck

import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/** Personal launchers are read from Tower; editing their configuration stays in the web client. */
suspend fun WorkspaceSync.refreshPersonalAgentLaunchers(connection: ConnectionRecord, workspace: WorkspaceRecord, force: Boolean = false) {
    if (workspace.accessRemoved) return
    engine.enqueue("personal-agents:${workspace.id}", SyncPriority.BACKGROUND, force) {
        identity.requireReader(connection.signerNpub)
        val path = PersonalAgentLauncher.path(workspace.id) ?: return@enqueue
        val response = api(connection).client.exchange("GET", path, workspace.appNpub, null)
        if (response.status !in 200..299) throw IllegalStateException("Personal agents unavailable (HTTP ${response.status})")
        val rows = PersonalAgentLauncher.decode(response.body)
        store.writer.setState(PersonalAgentLauncher.stateKey(connection.id, workspace.id), PersonalAgentLauncher.encodeList(rows))
    }
}
