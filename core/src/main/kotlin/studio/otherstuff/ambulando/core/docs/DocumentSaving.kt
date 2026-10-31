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
import studio.otherstuff.ambulando.core.transport.DocumentEditInspection
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.docBody
import studio.otherstuff.ambulando.core.transport.inspectDocumentEdit
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    override suspend fun acquireDocumentLease(
        workspaceId: String, appNpub: String, docId: String, editorSessionId: String, protocol: String, takeoverLeaseId: String?,
    ): EditLease = try {
        api.acquireLease(workspaceId, appNpub, "document", docId, editorSessionId, takeoverLeaseId, protocol)
    } catch (e: LeaseError.Held) {
        throw LeaseException.Held(e.by, e.until)
    } catch (e: LeaseError.Invalid) {
        throw LeaseException.Invalid(e.code)
    }

    override suspend fun inspect(workspaceId: String, appNpub: String, docId: String): DocumentEditInspection? = try {
        api.inspectDocumentEdit(workspaceId, appNpub, docId)
    } catch (e: TowerError.Http) {
        if (e.status == 404) null else throw e // Older servers lack inspection.
    }

    override suspend fun fetchHead(workspaceId: String, appNpub: String, docId: String): DocHead? {
        val (doc, body) = api.docBody(workspaceId, appNpub, docId)
        return DocHead(doc, body)
    }

    override suspend fun recoveries(workspaceId: String, appNpub: String, docId: String): List<DocumentRecovery> {
        val rows = api.pages(TowerAPI.base(workspaceId) + "/docs/" + TowerAPI.uuid(docId) + "/recoveries", appNpub, "recoveries")
        return rows.map { parseRecovery(it, workspaceId, docId) }
    }

    override suspend fun recoveryBody(workspaceId: String, appNpub: String, docId: String, recoveryId: String): EncodedBody {
        val o = api.obj(api.client.request(path = TowerAPI.base(workspaceId) + "/docs/" + TowerAPI.uuid(docId) + "/recoveries/" + TowerAPI.uuid(recoveryId) + "/body", appNpub = appNpub), "recovery body")
        val recovery = parseRecovery(o.obj("recovery") ?: throw TowerError.Malformed("recovery identity"), workspaceId, docId)
        if (recovery.id != recoveryId.lowercase()) throw TowerError.Malformed("recovery identity")
        val body = o.obj("body")
        val bytes = body?.string("base64_data")?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        if (body == null || body.string("object_id")?.lowercase() != recovery.storageObjectId || body.string("encoding") != "base64" || bytes == null ||
            body.string("sha256_hex")?.lowercase() != studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(bytes)) throw studio.otherstuff.ambulando.core.transport.DocumentBodyError.Integrity
        return EncodedBody(bytes, body.string("content_type") ?: "")
    }

    override suspend fun resolveRecovery(
        workspaceId: String, appNpub: String, docId: String, recoveryId: String, promote: Boolean, headRowVersion: Int?, headSha256: String?, leaseToken: String?,
    ) {
        val payload = jsonBody(if (promote) buildJsonObject {
            put("row_version", headRowVersion); put("base_body_sha256_hex", headSha256); put("lease_token", leaseToken)
        } else JsonObject(emptyMap()))
        api.client.request(method = "POST", path = TowerAPI.base(workspaceId) + "/docs/" + TowerAPI.uuid(docId) + "/recoveries/" + TowerAPI.uuid(recoveryId) + (if (promote) "/promote" else "/discard"),
            appNpub = appNpub, jsonBody = payload)
    }

    private fun parseRecovery(row: JsonObject, workspaceId: String, docId: String): DocumentRecovery {
        val id = row.string("id")?.lowercase()
        if (id == null || row.string("workspace_id")?.lowercase() != workspaceId.lowercase() || row.string("doc_id")?.lowercase() != docId.lowercase()) {
            throw TowerError.Malformed("document recovery")
        }
        val objectId = (row.obj("submitted_body")?.string("storage_object_id") ?: row.string("storage_object_id"))?.lowercase() ?: throw TowerError.Malformed("recovery storage identity")
        return DocumentRecovery(id, objectId, row.obj("submitted_patch")?.string("title"), row.obj("provenance")?.string("created_at") ?: "")
    }

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

internal fun leaseKey(connectionId: String, workspaceId: String, docId: String, session: String?) =
    "doc-lease:$connectionId:$workspaceId:$docId" + (session?.let { ":$it" } ?: "")

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
    val saver: DocumentSaver = DocumentSaver(store.writer.docDraftStore(store.reader), ::api)

    private suspend fun api(target: DocSaveTarget): DocumentSaveApi {
        val connection = store.reader.connections().firstOrNull { it.id == target.connectionId } ?: throw DocumentSaveLocked()
        val state = identity.state()
        if (state !is IdentityState.Unlocked || state.npub != connection.signerNpub) throw DocumentSaveLocked()
        return TowerDocumentSaveApi(TowerAPI(TowerClient(TowerOrigin(connection.origin), transport, identity), transfers))
    }

    private fun key(target: DocSaveTarget, docId: String, what: String) = "doc-$what:${target.connectionId}:${target.workspaceId}:$docId"

    /**
     * Lease calls of one editor session queue behind each other, but never replace another session's: an old session's release
     * must not be dropped (leaving the lease held) because a new session on the same document asked to acquire.
     */
    private fun leaseKey(target: DocSaveTarget, docId: String, session: String?) = leaseKey(target.connectionId, target.workspaceId, docId, session)

    fun target(connection: ConnectionRecord, workspace: WorkspaceRecord) = DocSaveTarget(connection.id, workspace.id, workspace.appNpub)

    suspend fun save(draft: DocDraft, connection: ConnectionRecord, workspace: WorkspaceRecord, editorSessionId: String? = null, asRecovery: Boolean = false): DocSaveResult {
        val target = target(connection, workspace)
        return engine.perform(key(target, draft.docId, "save"), SyncPriority.USER) { saver.save(draft, target, editorSessionId, asRecovery) }
    }

    suspend fun acquire(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, editorSessionId: String? = null): EditLease {
        val target = target(connection, workspace)
        return engine.perform(leaseKey(target, docId, editorSessionId), SyncPriority.USER) { saver.acquire(target, docId, editorSessionId) }
    }

    suspend fun renew(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, editorSessionId: String? = null) {
        val target = target(connection, workspace)
        engine.perform(leaseKey(target, docId, editorSessionId), SyncPriority.USER) { saver.renew(target, docId, editorSessionId) }
    }

    suspend fun release(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, editorSessionId: String? = null) {
        val target = target(connection, workspace)
        engine.perform(leaseKey(target, docId, editorSessionId), SyncPriority.USER) { saver.release(target, docId, editorSessionId) }
    }

    suspend fun hasLease(workspaceId: String, docId: String): Boolean = saver.hasLease(workspaceId, docId)

    suspend fun sessionLost(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, editorSessionId: String): Boolean =
        saver.sessionLost(target(connection, workspace), docId, editorSessionId)

    suspend fun resumeUnchangedSession(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, editorSessionId: String): Boolean {
        val target = target(connection, workspace)
        return engine.perform(leaseKey(target, docId, editorSessionId), SyncPriority.USER) { saver.resumeUnchangedSession(target, docId, editorSessionId) }
    }

    suspend fun inspect(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String): DocumentEditInspection? {
        val target = target(connection, workspace)
        return engine.perform(key(target, docId, "inspect"), SyncPriority.USER) { saver.inspect(target, docId) }
    }

    /** The latest canonical document, cached for the screens. Throws when Tower cannot be reached. */
    suspend fun latest(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String): DocHead? {
        val target = target(connection, workspace)
        return engine.perform(key(target, docId, "latest"), SyncPriority.USER) { saver.latest(target, docId) }
    }

    suspend fun takeover(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, editorSessionId: String, expectedLeaseId: String?): DocHead {
        val target = target(connection, workspace)
        return engine.perform(leaseKey(target, docId, editorSessionId), SyncPriority.USER) { saver.takeover(target, docId, editorSessionId, expectedLeaseId) }
    }

    suspend fun recoveries(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String): List<DocumentRecovery> {
        val target = target(connection, workspace)
        return engine.perform(key(target, docId, "versions"), SyncPriority.USER) { saver.recoveries(target, docId) }
    }

    suspend fun recoveryBody(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, recoveryId: String): EncodedBody {
        val target = target(connection, workspace)
        return engine.perform(key(target, docId, "versions"), SyncPriority.USER) { saver.recoveryBody(target, docId, recoveryId) }
    }

    suspend fun resolveRecovery(connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, recoveryId: String, promote: Boolean,
                                head: studio.otherstuff.ambulando.core.transport.DocBodyRecord, editorSessionId: String) {
        val target = target(connection, workspace)
        engine.perform(key(target, docId, "versions"), SyncPriority.USER) { saver.resolveRecovery(target, docId, recoveryId, promote, head, editorSessionId) }
    }

    /** Forgets every lease (lock, workspace switch); Tower expires them on its own. */
    suspend fun forgetAll() = saver.forgetAll()
}
