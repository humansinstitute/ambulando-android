package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/** Retained local history is readable, but cannot authorize another server request. */
suspend fun <T> StoreReader.withArtefactAccess(workspace: WorkspaceRecord, fileId: String, current: () -> Boolean = { true }, operation: suspend () -> T): T {
    suspend fun checkAccess() {
        val file = file(workspace.id, fileId)
        val workspaceRemoved = read {
            first("SELECT 1 FROM workspaces WHERE workspace_id = ? AND access_removed = 1 LIMIT 1",workspace.id) { true } ?: false
        }
        check(current() && !workspace.accessRemoved && !workspaceRemoved && file?.accessRemoved != true &&
            (file == null || !isChannelAccessRemoved(workspace.id, file.channelId))) {
            "This artefact is no longer available to you. Previously downloaded content and drafts are kept on this device."
        }
    }
    checkAccess()
    val result = operation()
    // Access may have changed while the request was in flight. Never install late authority.
    checkAccess()
    return result
}

/** Capture the original API; each signing/HTTP boundary checks the live owner again. */
fun studio.otherstuff.ambulando.core.transport.TowerAPI.fencedArtefactAPI(
    reader: StoreReader,
    workspace: WorkspaceRecord,
    fileId: String,
    current: () -> Boolean,
): studio.otherstuff.ambulando.core.transport.TowerAPI {
    val original = client
    val signer = original.signer?.let { bound -> studio.otherstuff.ambulando.core.nostr.EventSigner { template ->
        reader.withArtefactAccess(workspace,fileId,current) { bound.sign(template) }
    } }
    val transport = object : studio.otherstuff.ambulando.core.transport.HttpTransport {
        override suspend fun send(request: studio.otherstuff.ambulando.core.transport.HttpRequest) =
            reader.withArtefactAccess(workspace,fileId,current) { original.transport.send(request) }
    }
    return studio.otherstuff.ambulando.core.transport.TowerAPI(
        studio.otherstuff.ambulando.core.transport.TowerClient(original.origin,transport,signer),transfers)
}
