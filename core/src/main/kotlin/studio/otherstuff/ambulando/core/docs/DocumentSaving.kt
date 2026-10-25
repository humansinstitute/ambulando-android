package studio.otherstuff.ambulando.core.docs

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.connections
import studio.otherstuff.ambulando.core.store.docDraftStore
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.transport.EditLease
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.LeaseError
import studio.otherstuff.ambulando.core.transport.ObjectTransfers
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.acquireLease
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.releaseLease
import studio.otherstuff.ambulando.core.transport.renewLease
import studio.otherstuff.ambulando.core.transport.store

/**
 * [DocumentSaveApi] over the signed Tower client: edit leases, a new private storage object per save
 * (prepare → upload → complete, verified) and `PATCH /docs/:d`. Lease refusals become [LeaseException].
 */
class TowerDocumentSaveApi(private val api: TowerAPI) : DocumentSaveApi {
    override suspend fun acquireLease(workspaceId: String, appNpub: String, entityType: String, entityId: String): EditLease = try {
        api.acquireLease(workspaceId, appNpub, entityType, entityId)
    } catch (e: LeaseError.Held) {
        throw LeaseException.Held(e.by, e.until)
    }

    override suspend fun renewLease(workspaceId: String, appNpub: String, lease: EditLease): EditLease = try {
        api.renewLease(workspaceId, appNpub, lease)
    } catch (e: LeaseError.Invalid) {
        throw LeaseException.Invalid(e.code)
    }

    override suspend fun releaseLease(workspaceId: String, appNpub: String, lease: EditLease) = api.releaseLease(workspaceId, appNpub, lease)

    override suspend fun storeObject(workspaceId: String, appNpub: String, bytes: ByteArray, contentType: String, fileName: String): String =
        api.store(workspaceId, appNpub, bytes, contentType, fileName, null) { }

    override suspend fun patchDocument(workspaceId: String, appNpub: String, docId: String, body: JsonObject): SaveReply {
        val reply = api.client.exchange("PATCH", TowerAPI.base(workspaceId) + "/docs/" + TowerAPI.uuid(docId), appNpub, jsonBody(body))
        return SaveReply(reply.status, reply.body)
    }
}

/** The identity is locked or is not the one this connection was made with: nothing is sent. */
class DocumentSaveLocked : Exception("Unlock your identity to save."), UserFacingError {
    override val userMessage: String get() = message ?: ""
}

/**
 * Runs document saves and lease calls through [SyncEngine] (`USER` priority), each signed by the connection's own
 * unlocked identity and bound to that connection's origin and the workspace's `app_npub`. Drafts are stored in the
 * local store ([docDraftStore]) before any network step, so a lock, crash or offline period never loses the edit.
 */
class DocumentSaveRunner(
    private val store: LocalStore,
    private val engine: SyncEngine,
    private val identity: IdentityService,
    private val transport: HttpTransport,
    private val transfers: ObjectTransfers? = null,
) {
    /** The saver (the screen also uses it for `hasLease` and `forgetAll`). */
    val saver: DocumentSaver = DocumentSaver(store.writer.docDraftStore(), ::api)

    private suspend fun api(target: DocSaveTarget): DocumentSaveApi {
        val connection = store.reader.connections().firstOrNull { it.id == target.connectionId } ?: throw DocumentSaveLocked()
        val state = identity.state()
        if (state !is IdentityState.Unlocked || state.npub != connection.signerNpub) throw DocumentSaveLocked()
        return TowerDocumentSaveApi(TowerAPI(TowerClient(TowerOrigin(connection.origin), transport, identity), transfers))
    }

    private fun key(target: DocSaveTarget, docId: String, what: String) = "doc-$what:${target.connectionId}:${target.workspaceId}:$docId"

    fun target(connection: ConnectionRecord, workspace: WorkspaceRecord) = DocSaveTarget(connection.id, workspace.id, workspace.appNpub)

    suspend fun save(draft: DocDraft, connection: ConnectionRecord, workspace: WorkspaceRecord): DocSaveResult {
        val target = target(connection, workspace)
        return engine.perform(key(target, draft.docId, "save"), SyncPriority.USER) { saver.save(draft, target) }
    }

    suspend fun acquire(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String): EditLease {
        val target = target(connection, workspace)
        return engine.perform(key(target, docId, "lease"), SyncPriority.USER) { saver.acquire(target, docId) }
    }

    suspend fun renew(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String) {
        val target = target(connection, workspace)
        engine.perform(key(target, docId, "lease"), SyncPriority.USER) { saver.renew(target, docId) }
    }

    suspend fun release(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String) {
        val target = target(connection, workspace)
        engine.perform(key(target, docId, "lease"), SyncPriority.USER) { saver.release(target, docId) }
    }

    suspend fun hasLease(workspaceId: String, docId: String): Boolean = saver.hasLease(workspaceId, docId)

    /** Forgets every lease (lock, workspace switch); Tower expires them on its own. */
    suspend fun forgetAll() = saver.forgetAll()
}
