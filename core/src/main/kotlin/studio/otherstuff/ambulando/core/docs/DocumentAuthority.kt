package studio.otherstuff.ambulando.core.docs

import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.transport.DocBodyRecord
import studio.otherstuff.ambulando.core.transport.DocumentEditInspection
import studio.otherstuff.ambulando.core.transport.EditLease
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/**
 * What one open document needs from the lease and save machinery, already bound to its connection and workspace.
 * [DocumentSaveRunner] provides it for the app (through the sync scheduler); tests provide it over a [DocumentSaver].
 */
interface DocumentAuthority {
    suspend fun acquire(docId: String, session: String?): EditLease
    suspend fun renew(docId: String, session: String?)
    suspend fun release(docId: String, session: String?)
    suspend fun save(draft: DocDraft, session: String?, asRecovery: Boolean = false): DocSaveResult
    suspend fun latest(docId: String): DocHead?
    suspend fun inspect(docId: String): DocumentEditInspection?
    suspend fun takeover(docId: String, session: String, expectedLeaseId: String?): DocHead
    suspend fun sessionLost(docId: String, session: String): Boolean
    suspend fun resumeUnchangedSession(docId: String, session: String): Boolean
    suspend fun recoveries(docId: String): List<DocumentRecovery>
    suspend fun recoveryBody(docId: String, recoveryId: String): EncodedBody
    suspend fun resolveRecovery(docId: String, recoveryId: String, promote: Boolean, head: DocBodyRecord, session: String)
}

/** [DocumentAuthority] over the app's scheduled runner. */
class RunnerAuthority(
    private val runner: DocumentSaveRunner,
    private val connection: ConnectionRecord,
    private val workspace: WorkspaceRecord,
) : DocumentAuthority {
    override suspend fun acquire(docId: String, session: String?) = runner.acquire(connection, workspace, docId, session)
    override suspend fun renew(docId: String, session: String?) = runner.renew(connection, workspace, docId, session)
    override suspend fun release(docId: String, session: String?) = runner.release(connection, workspace, docId, session)
    override suspend fun save(draft: DocDraft, session: String?, asRecovery: Boolean) = runner.save(draft, connection, workspace, session, asRecovery)
    override suspend fun latest(docId: String) = runner.latest(connection, workspace, docId)
    override suspend fun inspect(docId: String) = runner.inspect(connection, workspace, docId)
    override suspend fun takeover(docId: String, session: String, expectedLeaseId: String?) = runner.takeover(connection, workspace, docId, session, expectedLeaseId)
    override suspend fun sessionLost(docId: String, session: String) = runner.sessionLost(connection, workspace, docId, session)
    override suspend fun resumeUnchangedSession(docId: String, session: String) = runner.resumeUnchangedSession(connection, workspace, docId, session)
    override suspend fun recoveries(docId: String) = runner.recoveries(connection, workspace, docId)
    override suspend fun recoveryBody(docId: String, recoveryId: String) = runner.recoveryBody(connection, workspace, docId, recoveryId)
    override suspend fun resolveRecovery(docId: String, recoveryId: String, promote: Boolean, head: DocBodyRecord, session: String) =
        runner.resolveRecovery(connection, workspace, docId, recoveryId, promote, head, session)
}

/** [DocumentAuthority] straight over a [DocumentSaver] (tests, and anything not scheduled through the sync engine). */
class SaverAuthority(private val saver: DocumentSaver, private val target: DocSaveTarget) : DocumentAuthority {
    override suspend fun acquire(docId: String, session: String?) = saver.acquire(target, docId, session)
    override suspend fun renew(docId: String, session: String?) = saver.renew(target, docId, session)
    override suspend fun release(docId: String, session: String?) = saver.release(target, docId, session)
    override suspend fun save(draft: DocDraft, session: String?, asRecovery: Boolean) = saver.save(draft, target, session, asRecovery)
    override suspend fun latest(docId: String) = saver.latest(target, docId)
    override suspend fun inspect(docId: String) = saver.inspect(target, docId)
    override suspend fun takeover(docId: String, session: String, expectedLeaseId: String?) = saver.takeover(target, docId, session, expectedLeaseId)
    override suspend fun sessionLost(docId: String, session: String) = saver.sessionLost(target, docId, session)
    override suspend fun resumeUnchangedSession(docId: String, session: String) = saver.resumeUnchangedSession(target, docId, session)
    override suspend fun recoveries(docId: String) = saver.recoveries(target, docId)
    override suspend fun recoveryBody(docId: String, recoveryId: String) = saver.recoveryBody(target, docId, recoveryId)
    override suspend fun resolveRecovery(docId: String, recoveryId: String, promote: Boolean, head: DocBodyRecord, session: String) =
        saver.resolveRecovery(target, docId, recoveryId, promote, head, session)
}
