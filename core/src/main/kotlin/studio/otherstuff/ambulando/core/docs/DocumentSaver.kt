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
import studio.otherstuff.ambulando.core.transport.EditLease
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
    enum class State(val raw: String) { EDITING("editing"), SAVING("saving"), CONFLICT("conflict"), RECOVERY("recovery"), FAILED("failed") }

    val key: String get() = key(connectionId, workspaceId, docId)

    fun with(state: State, error: String? = null, recoveryId: String? = null): DocDraft = DocDraft(
        connectionId, workspaceId, docId, title, body, contentType, baseRowVersion, baseSha256, baseStorageObjectId,
        state, error, recoveryId ?: this.recoveryId,
    )

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
}

/** The local persistence a save needs (implemented over the SQLite store). */
interface DocDraftStore {
    suspend fun saveDocDraft(draft: DocDraft)
    suspend fun deleteDocDraft(key: String, workspaceId: String)
    /** Stores the new canonical body and document row; `searchText` is the body's Markdown. */
    suspend fun saveDocBody(body: SavedDocBody, doc: SavedDocument, searchText: String)
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
 * `api` returns the signed API for a target, or throws (with a [UserFacingError] message) when the identity is locked
 * or does not match the connection.
 */
class DocumentSaver(
    private val store: DocDraftStore,
    private val api: suspend (DocSaveTarget) -> DocumentSaveApi,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, EditLease>()

    suspend fun hasLease(workspaceId: String, docId: String): Boolean = mutex.withLock { leases.containsKey(leaseKey(workspaceId, docId)) }

    /** Acquires (or keeps) the edit lease. Throws [LeaseException.Held] when another editor has it. */
    suspend fun acquire(target: DocSaveTarget, docId: String): EditLease {
        val key = leaseKey(target.workspaceId, docId)
        mutex.withLock { leases[key] }?.let { return it }
        val lease = api(target).acquireLease(target.workspaceId, target.appNpub, "document", docId)
        mutex.withLock { leases[key] = lease }
        return lease
    }

    /** Renews a held lease; a lease Tower no longer honours is forgotten so the next save acquires a fresh one. */
    suspend fun renew(target: DocSaveTarget, docId: String) {
        val key = leaseKey(target.workspaceId, docId)
        val lease = mutex.withLock { leases[key] } ?: return
        try {
            val renewed = api(target).renewLease(target.workspaceId, target.appNpub, lease)
            mutex.withLock { leases[key] = renewed }
        } catch (error: LeaseException) {
            mutex.withLock { leases.remove(key) }
            throw error
        }
    }

    suspend fun release(target: DocSaveTarget, docId: String) {
        val lease = mutex.withLock { leases.remove(leaseKey(target.workspaceId, docId)) } ?: return
        try {
            api(target).releaseLease(target.workspaceId, target.appNpub, lease)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Tower expires leases on its own.
        }
    }

    /** Forgets every lease (lock, workspace switch); Tower expires them on its own. */
    suspend fun forgetAll() = mutex.withLock { leases.clear() }

    suspend fun save(draft: DocDraft, target: DocSaveTarget): DocSaveResult {
        if (draft.connectionId != target.connectionId || draft.workspaceId != target.workspaceId) {
            return DocSaveResult.NotSaved("This draft belongs to another workspace.")
        }
        if (UploadToken.containsAny(draft.body.toString(Charsets.UTF_8))) return DocSaveResult.NotSaved(ComposerUploadError.UNRESOLVED.message)
        try {
            store.saveDocDraft(draft.with(DocDraft.State.SAVING))
            val api = api(target)
            val lease = try {
                acquire(target, draft.docId)
            } catch (held: LeaseException.Held) {
                val message = held.message ?: "Someone else is editing."
                store.saveDocDraft(draft.with(DocDraft.State.EDITING, error = message))
                return DocSaveResult.LeaseHeld(message)
            }
            val body = DocumentBody(draft.body, draft.contentType)
            val summary = body.markdown
            val objectId = api.storeObject(target.workspaceId, target.appNpub, draft.body, draft.contentType, fileName(draft))
            val request = buildJsonObject {
                put("row_version", draft.baseRowVersion)
                put("base_available", true)
                put("base_body_sha256_hex", draft.baseSha256)
                put("storage_object_id", objectId)
                put("lease_token", lease.token)
                put("title", draft.title)
                put("summary", summary)
            }
            val reply = api.patchDocument(target.workspaceId, target.appNpub, draft.docId, request)
            return handleReply(reply, draft, target, objectId, summary)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching { store.saveDocDraft(draft.with(DocDraft.State.EDITING, error = "Not saved yet: the save was interrupted.")) }
            }
            throw error
        } catch (error: Exception) {
            val message = "Not saved yet: " + ((error as? UserFacingError)?.userMessage ?: networkMessage(error))
            runCatching { store.saveDocDraft(draft.with(DocDraft.State.EDITING, error = message)) }
            return DocSaveResult.NotSaved(message)
        }
    }

    private suspend fun handleReply(reply: SaveReply, draft: DocDraft, target: DocSaveTarget, objectId: String, summary: String): DocSaveResult {
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
                store.deleteDocDraft(draft.key, target.workspaceId)
                DocSaveResult.Saved(saved, body)
            }
            Outcome.RECOVERY -> {
                val recovery = (obj?.get("recovery") as? JsonObject)?.string("id")
                val message = "The document changed before your save. Tower kept your version as a recovery copy, and your draft stays on this device."
                store.saveDocDraft(draft.with(DocDraft.State.RECOVERY, error = message, recoveryId = recovery))
                DocSaveResult.Recovery(recovery, message)
            }
            Outcome.STALE -> {
                val message = "The document changed before your save. Your draft stays on this device."
                store.saveDocDraft(draft.with(DocDraft.State.CONFLICT, error = message))
                DocSaveResult.Recovery(null, message)
            }
            Outcome.LEASE_LOST -> {
                mutex.withLock { leases.remove(leaseKey(target.workspaceId, draft.docId)) }
                val message = LeaseException.Invalid(code ?: "lease").message!!
                store.saveDocDraft(draft.with(DocDraft.State.EDITING, error = message))
                DocSaveResult.NotSaved(message)
            }
            Outcome.FAILED -> {
                val message = failureMessage(reply.status, obj)
                store.saveDocDraft(draft.with(DocDraft.State.FAILED, error = message))
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
        internal fun leaseKey(workspaceId: String, docId: String): String = "$workspaceId:$docId"

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
