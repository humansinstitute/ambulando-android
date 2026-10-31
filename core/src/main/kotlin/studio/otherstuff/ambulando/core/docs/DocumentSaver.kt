package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.chat.ComposerUploadError
import studio.otherstuff.ambulando.core.chat.UploadToken
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.CONCURRENT_DOCUMENT_EDIT_PROTOCOL
import studio.otherstuff.ambulando.core.transport.DOCUMENT_EDIT_PROTOCOL
import studio.otherstuff.ambulando.core.transport.DocBodyRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.DocumentEditInspection
import studio.otherstuff.ambulando.core.transport.EditLease
import studio.otherstuff.ambulando.core.transport.TowerError
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

/** A document's unsaved edit, kept on the device until Tower accepts it (or the user discards it). */
class DocDraft(
    val connectionId: String,
    val workspaceId: String,
    val docId: String,
    val title: String,
    /** Complete encoded body in its own representation (what would be uploaded). */
    val body: ByteArray,
    val contentType: String,
    val baseRowVersion: Int,
    val baseSha256: String,
    val baseStorageObjectId: String,
    val state: State,
    val lastError: String? = null,
    val recoveryId: String? = null,
) {
    /**
     * HELD is an edit that only an explicit Save may publish (a merged or restored draft). It is stored in the draft row itself,
     * so the hold commits atomically with the text and survives a lost review journal, a crash or a stale record.
     */
    enum class State(val raw: String) { EDITING("editing"), HELD("held"), SAVING("saving"), CONFLICT("conflict"), RECOVERY("recovery"), FAILED("failed") }

    val key: String get() = key(connectionId, workspaceId, docId)

    /** A held draft returning to plain editing after a failed or interrupted save stays held. */
    fun with(state: State, error: String? = null, recoveryId: String? = null): DocDraft = DocDraft(
        connectionId, workspaceId, docId, title, body, contentType, baseRowVersion, baseSha256, baseStorageObjectId,
        if (state == State.EDITING && this.state == State.HELD) State.HELD else state, error, recoveryId ?: this.recoveryId,
    )

    /** State and error labels change during a retry; body, title and the complete base identity define the edit. */
    fun matchesContentAndBase(other: DocDraft): Boolean = key == other.key && body.contentEquals(other.body) && title == other.title &&
        contentType == other.contentType && baseRowVersion == other.baseRowVersion && baseSha256 == other.baseSha256 &&
        baseStorageObjectId == other.baseStorageObjectId

    override fun equals(other: Any?): Boolean = other is DocDraft && connectionId == other.connectionId &&
        workspaceId == other.workspaceId && docId == other.docId && title == other.title && body.contentEquals(other.body) &&
        contentType == other.contentType && baseRowVersion == other.baseRowVersion && baseSha256 == other.baseSha256 &&
        baseStorageObjectId == other.baseStorageObjectId && state == other.state && lastError == other.lastError &&
        recoveryId == other.recoveryId

    override fun hashCode(): Int = listOf(connectionId, workspaceId, docId, state, body.contentHashCode()).hashCode()

    companion object {
        fun key(connectionId: String, workspaceId: String, docId: String): String = "$connectionId:$workspaceId:$docId"
    }
}

/** The connection and workspace a save runs against (the signer is checked by whoever supplies the API). */
data class DocSaveTarget(val connectionId: String, val workspaceId: String, val appNpub: String)

/** Edit lease refusals. */
sealed class LeaseException(message: String) : Exception(message), UserFacingError {
    override val userMessage: String get() = message ?: ""

    /** Someone else holds the lease. */
    class Held(val by: String?, val until: String?) :
        LeaseException("Someone else is editing this document" + (by?.let { " (${shortLabel(it)})" } ?: "") + ". Your changes stay on this device.")

    /** Tower no longer honours this lease (expired, released or replaced). */
    class Invalid(val code: String) : LeaseException("The edit lease is no longer valid ($code). Reopen the editor to continue.")

    companion object {
        internal fun shortLabel(npub: String): String = if (npub.length > 16) "${npub.take(10)}…${npub.takeLast(4)}" else npub
    }
}

/** An exception whose message is written for the user (it is shown after "Not saved yet: "). */
interface UserFacingError {
    val userMessage: String
}

/** Tower's reply to a request: status and body bytes. */
class SaveReply(val status: Int, val body: ByteArray)

/**
 * The network calls a save needs. Implemented over the signed Tower client by the transport layer; every call is
 * signed by the connection's unlocked identity. Lease calls throw [LeaseException] for 409 answers.
 */
interface DocumentSaveApi {
    /** `POST …/edit-leases/acquire` for `entityType`/`entityId`. Throws [LeaseException.Held] when someone else has it. */
    suspend fun acquireLease(workspaceId: String, appNpub: String, entityType: String, entityId: String): EditLease

    /** `POST …/edit-leases/:id/renew`. Throws [LeaseException.Invalid] when Tower no longer honours the lease. */
    suspend fun renewLease(workspaceId: String, appNpub: String, lease: EditLease): EditLease

    /** `POST …/edit-leases/:id/release`. */
    suspend fun releaseLease(workspaceId: String, appNpub: String, lease: EditLease)

    /** Uploads bytes as a new storage object (prepare → upload → complete) and returns its object id. */
    suspend fun storeObject(workspaceId: String, appNpub: String, bytes: ByteArray, contentType: String, fileName: String): String

    /** `PATCH …/docs/:docId` with a JSON body; returns Tower's answer whatever its status. */
    suspend fun patchDocument(workspaceId: String, appNpub: String, docId: String, body: JsonObject): SaveReply

    /**
     * Acquires a document lease for one editor session under [protocol]. [takeoverLeaseId] is the explicit takeover of exactly
     * that inspected lease. The default keeps the plain exclusive acquisition for stores that cannot negotiate.
     */
    suspend fun acquireDocumentLease(
        workspaceId: String, appNpub: String, docId: String, editorSessionId: String, protocol: String, takeoverLeaseId: String? = null,
    ): EditLease = acquireLease(workspaceId, appNpub, "document", docId)

    /** Who may edit now. Null: this server cannot be inspected (older servers answer 404), so only exclusive acquisition exists. */
    suspend fun inspect(workspaceId: String, appNpub: String, docId: String): DocumentEditInspection? = null

    /** The latest canonical document row and body (`GET …/docs/:docId/body`), verified against its hash. Null: not available from this api. */
    suspend fun fetchHead(workspaceId: String, appNpub: String, docId: String): DocHead? = null

    /** Tower's recovery copies of this document, newest first. */
    suspend fun recoveries(workspaceId: String, appNpub: String, docId: String): List<DocumentRecovery> = emptyList()

    /** One recovery copy's bytes, verified against the hash Tower reports. */
    suspend fun recoveryBody(workspaceId: String, appNpub: String, docId: String, recoveryId: String): EncodedBody =
        throw LeaseException.Invalid("saved_versions_unavailable")

    /** Promote or discard one recovery. A promotion carries the reviewed head's row version and hash and a valid lease token. */
    suspend fun resolveRecovery(
        workspaceId: String, appNpub: String, docId: String, recoveryId: String, promote: Boolean, headRowVersion: Int?, headSha256: String?, leaseToken: String?,
    ): Unit = throw LeaseException.Invalid("saved_versions_unavailable")
}

/** The document row and body Tower holds now. */
class DocHead(val doc: DocRecord, val body: DocBodyRecord)

/** One of Tower's stored recovery copies of a document (a previous or diverged version). */
data class DocumentRecovery(val id: String, val storageObjectId: String, val title: String?, val createdAt: String)

/** The local persistence a save needs (implemented over the SQLite store). */
interface DocDraftStore {
    suspend fun saveDocDraft(draft: DocDraft)
    suspend fun deleteDocDraft(key: String, workspaceId: String)
    /** Stores the new canonical body and document row; `searchText` is the body's Markdown. */
    suspend fun saveDocBody(body: SavedDocBody, doc: SavedDocument, searchText: String)

    // Claimed, compare-and-set access. The defaults suit simple stores; the SQLite store makes each a single statement.

    suspend fun currentDraft(key: String): DocDraft? = null

    /** Replaces the draft only while it is exactly [expected]; false: it changed and nothing was written. */
    suspend fun replaceIfUnchanged(expected: DocDraft, next: DocDraft): Boolean { saveDocDraft(next); return true }

    /** Stores the draft only when none exists; false: one appeared first. */
    suspend fun insertIfAbsent(draft: DocDraft): Boolean { saveDocDraft(draft); return true }

    /** Retires exactly the submitted snapshot; typing persisted meanwhile keeps its text and advances to the accepted base. */
    suspend fun finishSave(submitted: DocDraft, saved: SavedDocBody) { deleteDocDraft(submitted.key, submitted.workspaceId) }

    /** The document body this device last downloaded (the base of a clean document). */
    suspend fun cachedBody(workspaceId: String, docId: String): DocBodyRecord? = null

    /** Caches a fetched canonical head so the screens see what the saver saw. */
    suspend fun saveHead(head: DocHead, searchText: String) {}

    /** A small durable note (an upload that completed before a lost answer). */
    suspend fun receipt(key: String): String? = null
    suspend fun setReceipt(key: String, value: String?) {}
}

/** The document row Tower returned for an accepted save (only the fields the save checks; `raw` is the row JSON). */
data class SavedDocument(val workspaceId: String, val id: String, val rowVersion: Int, val raw: JsonObject)

/** A body Tower accepted, with the identity the next save is made against. */
class SavedDocBody(
    val workspaceId: String,
    val docId: String,
    val storageObjectId: String,
    /** sha256 of the canonical body. */
    val sha256: String,
    val contentType: String,
    val docRowVersion: Int,
    val bytes: ByteArray,
    val fetchedAt: String,
)

sealed class DocSaveResult {
    /** Tower made this the canonical body; the draft is gone and `doc` is the new base. */
    class Saved(val doc: SavedDocument, val body: SavedDocBody) : DocSaveResult()

    /**
     * The document moved on since the edit began. Tower kept the submission as a recovery version
     * (`document_recovery_created`) and the draft stays on this device.
     */
    data class Recovery(val id: String?, val message: String) : DocSaveResult()

    /** Someone else holds the edit lease. */
    data class LeaseHeld(val message: String) : DocSaveResult()

    /** The draft stays; the message says why (offline, refused, lease lost). */
    data class NotSaved(val message: String) : DocSaveResult()
}

/**
 * Edit lease and save flow for one connection's documents.
 *
 * Contract (`PATCH /docs/:d`): a body save uploads a new storage object and submits it with the base `row_version`,
 * the base body's sha256 and a valid edit lease token. A stale or unverifiable base never overwrites the head: Tower
 * stores the submission as a recovery and answers 409 `document_recovery_created`. The draft is written before any
 * network step and is removed only after Tower confirms the canonical save, so a crash, lock or offline period cannot
 * lose the edit.
 *
 * Beyond that contract this class:
 *  - claims exactly the durable draft snapshot being saved (a newer editor checkpoint always wins);
 *  - checks the latest head before uploading, so a newer shared save sends the person to review instead of creating a
 *    Tower recovery, and a save whose answer was lost is acknowledged without a second body mutation;
 *  - reuses an upload that completed before a lost answer;
 *  - keeps one lease per editor session, selecting `document_concurrent_edit_v1` only when Tower advertises it and
 *    never downgrading a selected concurrent session;
 *  - offers the explicit, expected-lease takeover and the recovery-body save that precedes it.
 *
 * `api` returns the signed API for a target, or throws (with a [UserFacingError] message) when the identity is locked
 * or does not match the connection.
 */
class DocumentSaver(
    private val store: DocDraftStore,
    private val api: suspend (DocSaveTarget) -> DocumentSaveApi,
    private val now: () -> Instant = Instant::now,
    private val newSessionId: () -> String = { java.util.UUID.randomUUID().toString().lowercase() },
) {
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, EditLease>()
    private val gates = mutableMapOf<String, Mutex>()
    private val saving = mutableSetOf<String>()
    private val lostSessions = mutableSetOf<String>()
    private val sessionIds = mutableMapOf<String, String>()
    private val selectedProtocols = mutableMapOf<String, String>()
    private var authorityGeneration = 0L

    /** A background (default) session can always take a fresh lease; only named editor sessions are fenced once lost. */
    private fun markLost(key: String) { if (!key.endsWith(":default")) lostSessions.add(key) }

    /** Whether any session of this document holds a lease. */
    suspend fun hasLease(workspaceId: String, docId: String): Boolean = mutex.withLock { leases.keys.any { it.contains(":$workspaceId:$docId:") } }

    /**
     * Acquires (or keeps) the lease of one editor session. Throws [LeaseException.Held] when another editor has it, and
     * [LeaseException.Invalid] for a session that was superseded, closed or whose selected concurrent protocol vanished.
     */
    suspend fun acquire(target: DocSaveTarget, docId: String, editorSessionId: String? = null): EditLease {
        val key = leaseKey(target.connectionId, target.workspaceId, docId, editorSessionId)
        val gate = mutex.withLock {
            leases[key]?.let { return it }
            if (key in lostSessions) throw LeaseException.Invalid("editor_superseded_or_expired")
            gates.getOrPut(key) { Mutex() }
        }
        // One acquisition per session at a time: a second caller waits and then finds the lease the first obtained.
        return gate.withLock {
            val generation = mutex.withLock {
                leases[key]?.let { return@withLock -1L }
                if (key in lostSessions) throw LeaseException.Invalid("editor_superseded_or_expired")
                authorityGeneration
            }
            if (generation < 0) return@withLock mutex.withLock { leases.getValue(key) }
            val session = mutex.withLock { editorSessionId ?: sessionIds.getOrPut(key) { newSessionId() } }
            val api = api(target)
            val inspection = try {
                api.inspect(target.workspaceId, target.appNpub, docId)
            } catch (e: TowerError.Http) {
                if (e.status == 404) null else throw e // Older servers lack inspection.
            }
            val protocol = mutex.withLock {
                if (generation != authorityGeneration || key in lostSessions) throw LeaseException.Invalid("editor_closed")
                if (selectedProtocols[key] == CONCURRENT_DOCUMENT_EDIT_PROTOCOL && inspection?.concurrentSupported != true) {
                    throw LeaseException.Invalid("concurrent_protocol_unavailable")
                }
                (if (inspection?.concurrentSupported == true) CONCURRENT_DOCUMENT_EDIT_PROTOCOL else DOCUMENT_EDIT_PROTOCOL).also { selectedProtocols[key] = it }
            }
            // An advertised concurrent mode is never silently downgraded on acquire failure.
            val lease = api.acquireDocumentLease(target.workspaceId, target.appNpub, docId, session, protocol)
            val stale = mutex.withLock {
                if (generation != authorityGeneration || key in lostSessions) true else { leases[key] = lease; false }
            }
            if (stale) {
                runCatching { api.releaseLease(target.workspaceId, target.appNpub, lease) }
                throw LeaseException.Invalid("editor_closed")
            }
            lease
        }
    }

    /** Renews a held lease; a lease Tower no longer honours is forgotten and its session marked lost. */
    suspend fun renew(target: DocSaveTarget, docId: String, editorSessionId: String? = null) {
        val key = leaseKey(target.connectionId, target.workspaceId, docId, editorSessionId)
        val lease = mutex.withLock { leases[key] } ?: return
        try {
            val renewed = api(target).renewLease(target.workspaceId, target.appNpub, lease)
            mutex.withLock { if (leases[key] == lease && key !in lostSessions) leases[key] = renewed }
        } catch (error: LeaseException) {
            mutex.withLock { leases.remove(key); markLost(key) }
            throw error
        }
    }

    suspend fun release(target: DocSaveTarget, docId: String, editorSessionId: String? = null) {
        val key = leaseKey(target.connectionId, target.workspaceId, docId, editorSessionId)
        val lease = mutex.withLock {
            if (editorSessionId != null) lostSessions.add(key)
            leases.remove(key)
        } ?: return
        try {
            api(target).releaseLease(target.workspaceId, target.appNpub, lease)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Tower expires leases on its own.
        }
    }

    /** Forgets every lease (lock, workspace switch); Tower expires them on its own. Open editors keep their identity but lose authority. */
    suspend fun forgetAll() = mutex.withLock {
        authorityGeneration++
        lostSessions.addAll((leases.keys + gates.keys).filter { !it.endsWith(":default") })
        // Default background operations get a fresh identity after unlock; open editors keep their original one.
        sessionIds.keys.removeAll { it.endsWith(":default") }
        selectedProtocols.keys.removeAll { it.endsWith(":default") }
        leases.clear()
    }

    /** Whether this session's authority ended (superseded, expired, released). */
    suspend fun sessionLost(target: DocSaveTarget, docId: String, editorSessionId: String): Boolean =
        mutex.withLock { leaseKey(target.connectionId, target.workspaceId, docId, editorSessionId) in lostSessions }

    /**
     * An editor that expired while offline retries only its original session, and only while nobody holds the document and
     * the shared head is still the one its draft is based on. A session taken over by another editor is fenced by Tower and
     * cannot pass acquire, even after the successor expires.
     */
    suspend fun resumeUnchangedSession(target: DocSaveTarget, docId: String, editorSessionId: String): Boolean {
        val key = leaseKey(target.connectionId, target.workspaceId, docId, editorSessionId)
        return try {
            val api = api(target)
            val inspection = api.inspect(target.workspaceId, target.appNpub, docId) ?: return false
            if (inspection.leaseId != null) return false
            val head = api.fetchHead(target.workspaceId, target.appNpub, docId) ?: return false
            val draft = store.currentDraft(DocDraft.key(target.connectionId, target.workspaceId, docId))
            val cached = store.cachedBody(target.workspaceId, docId)
            val version = draft?.baseRowVersion?.toLong() ?: cached?.docRowVersion ?: return false
            val baseSha = draft?.baseSha256 ?: cached?.sha256 ?: return false
            if (version != head.doc.rowVersion || baseSha != head.body.sha256) return false
            mutex.withLock { lostSessions.remove(key) }
            try {
                acquire(target, docId, editorSessionId)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutex.withLock { lostSessions.add(key) }
                false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun inspect(target: DocSaveTarget, docId: String): DocumentEditInspection? =
        api(target).inspect(target.workspaceId, target.appNpub, docId)

    /** The latest canonical document, also cached for the screens. Null when the api cannot fetch heads. */
    suspend fun latest(target: DocSaveTarget, docId: String): DocHead? {
        val head = api(target).fetchHead(target.workspaceId, target.appNpub, docId) ?: return null
        store.saveHead(head, DocumentBody(head.body.bytes, head.body.contentType).markdown)
        return head
    }

    /**
     * Explicit user action only. The server must be inspectable, support the exclusive protocol, and still show exactly the
     * lease the person saw. Tower authorizes the requesting actor (write access), not a client-side holder identity. A supporting
     * server revokes the prior authority, fences its token and advances the document version; the latest body is loaded and
     * verified before the editor continues.
     */
    suspend fun takeover(target: DocSaveTarget, docId: String, editorSessionId: String, expectedLeaseId: String?): DocHead {
        val api = api(target)
        val inspected = api.inspect(target.workspaceId, target.appNpub, docId)
        if (inspected == null || !inspected.supported || inspected.leaseId != expectedLeaseId) throw LeaseException.Invalid("takeover_not_available")
        // A server that lets every session edit has nothing to take over; never fence another editor there on a stale button.
        if (inspected.concurrentSupported) throw LeaseException.Invalid("takeover_not_available")
        val lease = api.acquireDocumentLease(target.workspaceId, target.appNpub, docId, editorSessionId, DOCUMENT_EDIT_PROTOCOL, expectedLeaseId)
        val key = leaseKey(target.connectionId, target.workspaceId, docId, editorSessionId)
        mutex.withLock { leases[key] = lease; lostSessions.remove(key); selectedProtocols[key] = DOCUMENT_EDIT_PROTOCOL }
        try {
            val fresh = api.fetchHead(target.workspaceId, target.appNpub, docId) ?: throw LeaseException.Invalid("latest_version_unavailable")
            renew(target, docId, editorSessionId)
            store.saveHead(fresh, DocumentBody(fresh.body.bytes, fresh.body.contentType).markdown)
            return fresh
        } catch (error: Exception) {
            release(target, docId, editorSessionId)
            throw error
        }
    }

    suspend fun recoveries(target: DocSaveTarget, docId: String): List<DocumentRecovery> = api(target).recoveries(target.workspaceId, target.appNpub, docId)

    suspend fun recoveryBody(target: DocSaveTarget, docId: String, recoveryId: String): EncodedBody =
        api(target).recoveryBody(target.workspaceId, target.appNpub, docId, recoveryId)

    /**
     * Promotion is an explicit command against the exact head the person reviewed and never retries a stale choice. Discard
     * only retires the selected recovery; canonical content and stored recovery bytes are untouched.
     */
    suspend fun resolveRecovery(target: DocSaveTarget, docId: String, recoveryId: String, promote: Boolean, head: DocBodyRecord, editorSessionId: String) {
        val token = if (promote) acquire(target, docId, editorSessionId).token else null
        api(target).resolveRecovery(target.workspaceId, target.appNpub, docId, recoveryId, promote,
            if (promote) head.docRowVersion.toInt() else null, if (promote) head.sha256 else null, token)
    }

    suspend fun save(draft: DocDraft, target: DocSaveTarget, editorSessionId: String? = null, asRecovery: Boolean = false): DocSaveResult {
        if (draft.connectionId != target.connectionId || draft.workspaceId != target.workspaceId) {
            return DocSaveResult.NotSaved("This draft belongs to another workspace.")
        }
        if (UploadToken.containsAny(draft.body.toString(Charsets.UTF_8))) return DocSaveResult.NotSaved(ComposerUploadError.UNRESOLVED.message)
        if (!mutex.withLock { saving.add(draft.key) }) {
            return DocSaveResult.NotSaved("A save is already in progress. Your latest edits stay on this device.")
        }
        val submitted = draft.with(DocDraft.State.SAVING)
        try {
            // Claim exactly this durable snapshot; a newer editor checkpoint must win.
            val current = store.currentDraft(draft.key)
            val claimed = if (current != null) current.matchesContentAndBase(draft) && store.replaceIfUnchanged(current, submitted) else store.insertIfAbsent(submitted)
            if (!claimed) return DocSaveResult.NotSaved("Your draft changed before this save. Save again to sync the latest edits.")
            val api = api(target)
            val sha = Sha256Hex.of(draft.body)
            if (!asRecovery) {
                // Verify current content before upload as well as Tower's atomic compare-and-set at commit. An uncertain
                // successful response can be acknowledged without submitting a duplicate version.
                val fresh = latest(target, draft.docId)
                if (fresh != null) {
                    if (fresh.doc.title == draft.title && fresh.body.sha256 == sha) {
                        val saved = savedBody(fresh.body, target)
                        store.finishSave(submitted, saved)
                        store.setReceipt(uploadKey(draft), null)
                        return DocSaveResult.Saved(SavedDocument(fresh.doc.workspaceId, fresh.doc.id, fresh.doc.rowVersion.toInt(), fresh.doc.raw), saved)
                    }
                    if (fresh.body.docRowVersion != draft.baseRowVersion.toLong() || fresh.body.sha256 != draft.baseSha256 ||
                        fresh.body.storageObjectId != draft.baseStorageObjectId) {
                        val message = "Newer version available — Review changes. Your local draft is unchanged."
                        store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.CONFLICT, error = message))
                        return DocSaveResult.Recovery(null, message)
                    }
                }
            }
            val lease: EditLease? = if (asRecovery) null else try {
                acquire(target, draft.docId, editorSessionId)
            } catch (held: LeaseException.Held) {
                val message = held.message ?: "Someone else is editing."
                store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.EDITING, error = message))
                return DocSaveResult.LeaseHeld(message)
            }
            val body = DocumentBody(draft.body, draft.contentType)
            val summary = body.markdown
            val objectId = uploadedObject(api, target, draft, sha)
            val request = buildJsonObject {
                put("row_version", draft.baseRowVersion)
                put("base_available", !asRecovery)
                put("base_body_sha256_hex", draft.baseSha256)
                put("storage_object_id", objectId)
                put("lease_token", lease?.token ?: "")
                put("title", draft.title)
                put("summary", summary)
            }
            val reply = api.patchDocument(target.workspaceId, target.appNpub, draft.docId, request)
            return handleReply(reply, draft, submitted, target, objectId, summary, editorSessionId)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching { store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.EDITING, error = "Not saved yet: the save was interrupted.")) }
            }
            throw error
        } catch (error: Exception) {
            val message = "Not saved yet: " + ((error as? UserFacingError)?.userMessage ?: networkMessage(error))
            runCatching { store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.EDITING, error = message)) }
            return DocSaveResult.NotSaved(message)
        } finally {
            withContext(NonCancellable) { mutex.withLock { saving.remove(draft.key) } }
        }
    }

    private fun uploadKey(draft: DocDraft) = studio.otherstuff.ambulando.core.store.DocumentReviewCodec.UPLOAD_RECEIPT_PREFIX + draft.key

    /** The storage object for this exact body: one a previous attempt already completed (its answer may have been lost), or a new upload. */
    private suspend fun uploadedObject(api: DocumentSaveApi, target: DocSaveTarget, draft: DocDraft, sha: String): String {
        val retained = store.receipt(uploadKey(draft))?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val retainedId = (retained?.get("object_id") as? JsonPrimitive)?.content?.lowercase()?.takeIf { uuid.matches(it) }
        if (retained != null && (retained["sha256"] as? JsonPrimitive)?.content == sha &&
            (retained["content_type"] as? JsonPrimitive)?.content == draft.contentType && retainedId != null) {
            return retainedId
        }
        val id = api.storeObject(target.workspaceId, target.appNpub, draft.body, draft.contentType, fileName(draft))
        store.setReceipt(uploadKey(draft), buildJsonObject { put("sha256", sha); put("content_type", draft.contentType); put("object_id", id) }.toString())
        return id
    }

    private fun savedBody(body: DocBodyRecord, target: DocSaveTarget) = SavedDocBody(target.workspaceId, body.docId, body.storageObjectId, body.sha256,
        body.contentType, body.docRowVersion.toInt(), body.bytes, body.fetchedAt)

    private suspend fun handleReply(
        reply: SaveReply, draft: DocDraft, submitted: DocDraft, target: DocSaveTarget, objectId: String, summary: String, editorSessionId: String?,
    ): DocSaveResult {
        val obj = parseObject(reply.body)
        val code = obj?.string("code")
        val doc = (obj?.get("doc") as? JsonObject)?.let { savedDocument(it) }
        return when (classify(reply.status, code, doc, target, draft.docId)) {
            Outcome.SAVED -> {
                val saved = doc!!
                val sha = Sha256Hex.of(draft.body)
                val canonical = ((obj?.get("canonical_version") as? JsonObject)?.string("body_sha256_hex"))?.lowercase()
                val body = SavedDocBody(target.workspaceId, saved.id, objectId, canonical ?: sha, draft.contentType, saved.rowVersion,
                    draft.body, now().toString())
                store.saveDocBody(body, saved, summary)
                store.finishSave(submitted, body)
                store.setReceipt(uploadKey(draft), null)
                DocSaveResult.Saved(saved, body)
            }
            Outcome.RECOVERY -> {
                val recovery = (obj?.get("recovery") as? JsonObject)?.string("id")
                val message = "The document changed before your save. Tower kept your version as a recovery copy, and your draft stays on this device."
                store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.RECOVERY, error = message, recoveryId = recovery))
                DocSaveResult.Recovery(recovery, message)
            }
            Outcome.STALE -> {
                val message = "The document changed before your save. Your draft stays on this device."
                store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.CONFLICT, error = message))
                DocSaveResult.Recovery(null, message)
            }
            Outcome.LEASE_LOST -> {
                val key = leaseKey(target.connectionId, target.workspaceId, draft.docId, editorSessionId)
                mutex.withLock { leases.remove(key); markLost(key) }
                val message = LeaseException.Invalid(code ?: "lease").message!!
                store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.EDITING, error = message))
                DocSaveResult.NotSaved(message)
            }
            Outcome.FAILED -> {
                val message = failureMessage(reply.status, obj)
                store.replaceIfUnchanged(submitted, draft.with(DocDraft.State.FAILED, error = message))
                DocSaveResult.NotSaved(message)
            }
        }
    }

    /** How Tower answered a save. */
    enum class Outcome {
        /** 2xx with this workspace's document row: the body is canonical. */
        SAVED,
        /** 409 `document_recovery_created`: the base was stale; Tower kept a recovery copy. */
        RECOVERY,
        /** 409 `stale_row_version`: the base was stale and nothing was kept. */
        STALE,
        /** Any other 409: the edit lease is gone. */
        LEASE_LOST,
        /** Everything else. */
        FAILED,
    }

    companion object {
        internal fun leaseKey(connectionId: String, workspaceId: String, docId: String, session: String?): String = "$connectionId:$workspaceId:$docId:${session ?: "default"}"

        /** Classifies a `PATCH /docs/:d` answer. A 2xx without a matching document row is not trusted as a save. */
        fun classify(status: Int, code: String?, doc: SavedDocument?, target: DocSaveTarget, docId: String): Outcome = when {
            status in 200..299 && doc != null && doc.workspaceId == target.workspaceId && doc.id == docId -> Outcome.SAVED
            status == 409 && code == "document_recovery_created" -> Outcome.RECOVERY
            status == 409 && code == "stale_row_version" -> Outcome.STALE
            status == 409 -> Outcome.LEASE_LOST
            else -> Outcome.FAILED
        }

        /** Upload file name: the title made filename-safe (at most 80 characters) plus the document id. */
        internal fun fileName(draft: DocDraft): String =
            draft.title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80) + "-${draft.docId}.document.json"

        /** User-facing message for a refused save (one attempt, "edit this document"). */
        internal fun failureMessage(status: Int, obj: JsonObject?): String {
            val code = obj?.string("code") ?: obj?.string("error")
            val field = (((obj?.get("details") as? JsonObject)?.get("fields") as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull() as? JsonObject)?.string("message")
            return when (status) {
                401 -> "Tower did not accept the signature; retrying with a fresh one."
                403 -> "You do not have permission to edit this document."
                404 -> "This document no longer exists."
                408, 425, 429, in 500..599 -> "Tower is unavailable (HTTP $status); will retry."
                else -> field?.let { "Tower rejected this: $it" } ?: "Tower rejected this (HTTP $status${code?.let { ", $it" } ?: ""})."
            }
        }

        /** Message for a transport failure. */
        internal fun networkMessage(error: Throwable): String = when (error) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> "Offline; will send when the connection returns."
            is SocketTimeoutException -> "Tower did not answer in time; will retry."
            is InterruptedIOException, is java.io.IOException -> "Network problem (${error.javaClass.simpleName}); will retry."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Send failed; will retry."
        }

        private fun parseObject(bytes: ByteArray): JsonObject? = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private val uuid = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

        /** The fields of a Tower document row a save checks: lower-cased ids and the row version. */
        fun savedDocument(row: JsonObject): SavedDocument? {
            val id = row.string("id")?.takeIf { uuid.matches(it) }?.lowercase() ?: return null
            val workspace = row.string("workspace_id")?.takeIf { uuid.matches(it) }?.lowercase() ?: return null
            row.string("channel_id")?.takeIf { uuid.matches(it) } ?: return null
            val version = (row["row_version"] as? JsonPrimitive)?.let { p -> if (p.isString) p.content.toIntOrNull() else p.doubleOrNull?.toInt() } ?: 0
            return SavedDocument(workspace, id, version, row)
        }
    }
}
