package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

// Tower routes for tasks, documents, files, view states, reactions, leases and storage. Paths are built only from
// validated UUIDs, and every response is checked to belong to the requested workspace before it is materialised.

/** Tower serialises timestamps as `2026-10-24T00:01:55.789Z`. */
object TowerTime {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    fun format(instant: Instant): String = FORMAT.format(instant)
    fun now(): String = format(Instant.now())
}

sealed class DocumentBodyError(message: String) : Exception(message, null, false, false) {
    data object Integrity : DocumentBodyError("The document body Tower returned does not match its checksum; it was not used.")
}

sealed class FileTransferError(message: String) : Exception(message, null, false, false) {
    data object Integrity : FileTransferError("The file Tower returned does not match its checksum; it was not saved.")
    data object TooLarge : FileTransferError("This file is larger than this app downloads (100 MB).")
    data object UnsafeUploadURL : FileTransferError("Tower offered an upload address this app does not trust; nothing was uploaded.")
    data object FallbackTooLarge : FileTransferError("This Tower only accepts uploads through its API, limited to 8 MB.")
    data object NotAttachable : FileTransferError("Tower did not confirm the uploaded file.")
    data object TextTooLarge : FileTransferError("This text is larger than the 8 MB limit for text attachments.")
}

/** Typed file content from `GET /files/:f/object`, verified against Tower's hash. */
class FileObject(val bytes: ByteArray, val contentType: String?, val fileName: String?, val sha256: String)

/** Documents and files Tower lists in one page; `complete` is false when the page was full (possibly truncated). */
data class ListResult<T>(val items: List<T>, val complete: Boolean)

// MARK: Tasks

suspend fun TowerAPI.channelTasks(workspaceId: String, appNpub: String, channelId: String): List<TaskRecord> {
    val rows = pages(TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/tasks", appNpub, "tasks")
    val tasks = rows.map { TaskRecord.parse(it) }
    if (!tasks.all { it.workspaceId == workspaceId && it.channelId == channelId }) throw TowerError.Malformed("tasks")
    return tasks
}

suspend fun TowerAPI.task(workspaceId: String, appNpub: String, taskId: String): TaskRecord {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/tasks/" + TowerAPI.uuid(taskId), appNpub = appNpub), "task")
    val task = TaskRecord.parse(o.obj("task") ?: throw TowerError.Malformed("task"))
    if (task.workspaceId != workspaceId || task.id != taskId.lowercase()) throw TowerError.Malformed("task")
    return task
}

suspend fun TowerAPI.taskComments(workspaceId: String, appNpub: String, taskId: String): List<CommentRecord> {
    val rows = pages(TowerAPI.base(workspaceId) + "/tasks/" + TowerAPI.uuid(taskId) + "/comments", appNpub, "comments")
    return rows.map { CommentRecord.parse(it, CommentRecord.Parent.TASK) }
        .filter { it.workspaceId == workspaceId && it.parentId == taskId.lowercase() }
}

// MARK: Documents

/** Tower returns one page (≤ 200) with `next_cursor: null`; a full page is reported as possibly incomplete. */
suspend fun TowerAPI.channelDocs(workspaceId: String, appNpub: String, channelId: String): ListResult<DocRecord> {
    val o = obj(
        client.request(path = TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/docs", query = listOf("limit" to "200"), appNpub = appNpub),
        "docs",
    )
    val rows = o.objects("docs") ?: throw TowerError.Malformed("docs")
    val docs = rows.map { DocRecord.parse(it) }
    if (!docs.all { it.workspaceId == workspaceId && it.channelId == channelId }) throw TowerError.Malformed("docs")
    return ListResult(docs, rows.size < 200)
}

suspend fun TowerAPI.doc(workspaceId: String, appNpub: String, docId: String): DocRecord {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/docs/" + TowerAPI.uuid(docId), appNpub = appNpub), "document")
    val doc = DocRecord.parse(o.obj("doc") ?: throw TowerError.Malformed("document"))
    if (doc.workspaceId != workspaceId || doc.id != docId.lowercase()) throw TowerError.Malformed("document")
    return doc
}

/**
 * The canonical body with the document row it belongs to. The bytes must hash to Tower's sha256 when Tower reports
 * one; a body that does not match is refused rather than shown or used as an edit base.
 */
suspend fun TowerAPI.docBody(workspaceId: String, appNpub: String, docId: String): Pair<DocRecord, DocBodyRecord> {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/docs/" + TowerAPI.uuid(docId) + "/body", appNpub = appNpub), "document body")
    val row = o.obj("doc")
    val body = o.obj("body")
    val encoded = body?.string("base64_data")
    val bytes = encoded?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    val objectId = lowerUUID(body?.string("object_id"))
    if (row == null || body == null || body.string("encoding") != "base64" || bytes == null || objectId == null) {
        throw TowerError.Malformed("document body")
    }
    val doc = DocRecord.parse(row)
    if (doc.workspaceId != workspaceId || doc.id != docId.lowercase() || doc.storageObjectId != objectId) throw TowerError.Malformed("document body")
    val computed = Sha256Hex.of(bytes)
    val reported = body.string("sha256_hex")?.lowercase()
    if (reported != null && reported != computed) throw DocumentBodyError.Integrity
    val record = DocBodyRecord(
        workspaceId = workspaceId, docId = doc.id, storageObjectId = objectId, sha256 = reported ?: computed,
        contentType = body.string("content_type") ?: "", docRowVersion = doc.rowVersion, bytes = bytes, fetchedAt = TowerTime.now(),
    )
    return doc to record
}

suspend fun TowerAPI.docComments(workspaceId: String, appNpub: String, docId: String): List<CommentRecord> {
    val rows = pages(TowerAPI.base(workspaceId) + "/docs/" + TowerAPI.uuid(docId) + "/comments", appNpub, "comments")
    return rows.map { CommentRecord.parse(it, CommentRecord.Parent.DOC) }
        .filter { it.workspaceId == workspaceId && it.parentId == docId.lowercase() }
}

// MARK: Files

suspend fun TowerAPI.channelFiles(workspaceId: String, appNpub: String, channelId: String): ListResult<FileRecord> {
    val o = obj(
        client.request(path = TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/files", query = listOf("limit" to "200"), appNpub = appNpub),
        "files",
    )
    val rows = o.objects("files") ?: throw TowerError.Malformed("files")
    val files = rows.map { FileRecord.parse(it) }
    if (!files.all { it.workspaceId == workspaceId && it.channelId == channelId }) throw TowerError.Malformed("files")
    return ListResult(files, rows.size < 200)
}

suspend fun TowerAPI.channelFolders(workspaceId: String, appNpub: String, channelId: String): ListResult<FolderRecord> {
    val o = obj(
        client.request(
            path = TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/file-folders", query = listOf("limit" to "200"), appNpub = appNpub,
        ),
        "folders",
    )
    val rows = o.objects("folders") ?: throw TowerError.Malformed("folders")
    val folders = rows.map { FolderRecord.parse(it) }
    if (!folders.all { it.workspaceId == workspaceId && it.channelId == channelId }) throw TowerError.Malformed("folders")
    return ListResult(folders, rows.size < 200)
}

const val MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024

/** File content through the typed object route (base64 with sha256). The bytes must match Tower's hash. */
suspend fun TowerAPI.fileObject(workspaceId: String, appNpub: String, fileId: String): FileObject {
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/object", appNpub = appNpub), "file")
    val row = o.obj("file")
    val objectInfo = o.obj("object")
    val encoded = objectInfo?.string("base64_data")
    if (row == null || objectInfo == null || objectInfo.string("encoding") != "base64" || encoded == null) throw TowerError.Malformed("file")
    if (encoded.toByteArray(Charsets.UTF_8).size.toLong() / 4 * 3 > MAX_DOWNLOAD_BYTES) throw FileTransferError.TooLarge
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: throw TowerError.Malformed("file")
    val file = FileRecord.parse(row)
    if (file.workspaceId != workspaceId || file.id != fileId.lowercase()) throw TowerError.Malformed("file")
    val computed = Sha256Hex.of(bytes)
    val reported = objectInfo.string("sha256_hex")?.lowercase()
    if (reported != null && reported != computed) throw FileTransferError.Integrity
    return FileObject(bytes, objectInfo.string("content_type"), objectInfo.string("file_name"), computed)
}

/** Raw storage content (`GET /api/v4/storage/:id/content`) for chat attachments, signed, on the pinned origin only. */
suspend fun TowerAPI.storageContent(objectId: String): ByteArray =
    client.request(path = "/api/v4/storage/" + TowerAPI.uuid(objectId) + "/content")

// MARK: Inbox

suspend fun TowerAPI.viewStates(workspaceId: String, appNpub: String): List<ViewStateRecord> {
    val rows = pages(TowerAPI.base(workspaceId) + "/resource-view-states", appNpub, "states")
    return rows.mapNotNull { runCatching { ViewStateRecord.parse(it) }.getOrNull() }.filter { it.workspaceId == workspaceId }
}

// MARK: Messages and reactions

/** One message, or null when Tower answers 404 (deleted or no longer visible). */
suspend fun TowerAPI.message(workspaceId: String, appNpub: String, messageId: String): MessageRecord? {
    return try {
        val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/messages/" + TowerAPI.uuid(messageId), appNpub = appNpub), "message")
        val message = MessageRecord.parse(o.obj("message") ?: throw TowerError.Malformed("message"))
        if (message.workspaceId != workspaceId) throw TowerError.Malformed("message")
        message
    } catch (e: TowerError.Http) {
        if (e.status == 404) null else throw e
    }
}

suspend fun TowerAPI.reactions(workspaceId: String, appNpub: String, targetType: String, targetId: String): List<ReactionRecord> {
    val query = listOf("target_type" to targetType, "target_id" to TowerAPI.uuid(targetId), "limit" to "200")
    val o = obj(client.request(path = TowerAPI.base(workspaceId) + "/reactions", query = query, appNpub = appNpub), "reactions")
    val rows = o.objects("reactions") ?: throw TowerError.Malformed("reactions")
    return rows.mapNotNull { runCatching { ReactionRecord.parse(it) }.getOrNull() }
        .filter { it.workspaceId == workspaceId && it.targetId == targetId.lowercase() }
}

// MARK: Edit leases

/** An edit lease Tower granted for one record. Document leases also carry the protocol Tower acknowledged and the editor session. */
data class EditLease(
    val id: String,
    val token: String,
    val expiresAt: String,
    val documentEditProtocol: String? = null,
    val editorSessionId: String? = null,
)

sealed class LeaseError(message: String) : Exception(message, null, false, false) {
    data class Held(val by: String?, val until: String?) : LeaseError(
        "Someone else is editing this document" + (by?.let { " (${shortLabel(it)})" } ?: "") + ". Your changes stay on this device.",
    )
    data class Invalid(val code: String) : LeaseError("The edit lease is no longer valid ($code). Reopen the editor to continue.")
}

const val LEASE_SECONDS = 120

/** The exclusive-lease document protocol (explicit takeover, fenced previous tokens). */
const val DOCUMENT_EDIT_PROTOCOL = "document_edit_session_v1"

/** The concurrent protocol: several editor sessions at once, each save checked against its base. Selected only when advertised. */
const val CONCURRENT_DOCUMENT_EDIT_PROTOCOL = "document_concurrent_edit_v1"

/**
 * What Tower says about who may edit a document now. Never exposes another editor's token, only the lease identity the
 * explicit takeover is checked against.
 */
data class DocumentEditInspection(
    val supported: Boolean,
    val leaseId: String?,
    val holderNpub: String?,
    val concurrentSupported: Boolean = false,
)

/** `GET …/edit-leases?entity_type=document&entity_id=…`. A server without the route answers 404, which callers treat as "not inspectable". */
suspend fun TowerAPI.inspectDocumentEdit(workspaceId: String, appNpub: String, docId: String): DocumentEditInspection {
    val reply = client.request(
        path = TowerAPI.base(workspaceId) + "/edit-leases",
        query = listOf("entity_type" to "document", "entity_id" to TowerAPI.uuid(docId)),
        appNpub = appNpub,
    )
    val o = obj(reply, "document edit authority")
    val lease = o.obj("lease")
    val protocols = (o["document_edit_protocols"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    return DocumentEditInspection(
        supported = o.string("document_edit_protocol") == DOCUMENT_EDIT_PROTOCOL,
        leaseId = lease?.string("id"),
        holderNpub = lease?.string("holder_actor_npub"),
        concurrentSupported = protocols?.contains(CONCURRENT_DOCUMENT_EDIT_PROTOCOL) == true,
    )
}

suspend fun TowerAPI.acquireLease(
    workspaceId: String,
    appNpub: String,
    entityType: String,
    entityId: String,
    editorSessionId: String? = null,
    takeoverLeaseId: String? = null,
    documentProtocol: String = DOCUMENT_EDIT_PROTOCOL,
): EditLease {
    val fields = linkedMapOf<String, Any>("entity_type" to entityType, "entity_id" to TowerAPI.uuid(entityId), "ttl_seconds" to LEASE_SECONDS)
    if (entityType == "document" && editorSessionId != null) {
        fields["document_edit_protocol"] = documentProtocol
        fields["editor_session_id"] = TowerAPI.uuid(editorSessionId)
    }
    if (takeoverLeaseId != null) {
        fields["takeover"] = true
        fields["expected_lease_id"] = TowerAPI.uuid(takeoverLeaseId)
    }
    val body = jsonBody(jsonObjectOf(*fields.map { it.key to it.value }.toTypedArray()))
    val reply = client.exchange("POST", TowerAPI.base(workspaceId) + "/edit-leases/acquire", appNpub, body)
    val o = parseJsonObjectOrNull(reply.body)
    if (reply.status == 409) {
        val details = o?.obj("details") ?: o
        throw LeaseError.Held(details?.string("holder_actor_npub"), details?.string("expires_at"))
    }
    if (reply.status !in 200..299) throw TowerError.Http(reply.status, o?.string("code"), "edit lease")
    val lease = o?.obj("lease")
    val id = lease?.string("id")
    val token = lease?.string("lease_token")
    if (lease == null || id == null || token == null || lease.string("entity_id")?.lowercase() != entityId.lowercase()) {
        throw TowerError.Malformed("edit lease")
    }
    val marker = o.string("document_edit_protocol")
    // A concurrent acquire is only real when Tower echoes the protocol and this exact session; never silently downgraded.
    if (documentProtocol == CONCURRENT_DOCUMENT_EDIT_PROTOCOL &&
        (marker != documentProtocol || lease.string("editor_session_id")?.lowercase() != editorSessionId?.lowercase())) {
        throw LeaseError.Invalid("concurrent_protocol_not_acknowledged")
    }
    if (takeoverLeaseId != null && marker != DOCUMENT_EDIT_PROTOCOL) throw LeaseError.Invalid("takeover_not_supported")
    return EditLease(id, token, lease.string("expires_at") ?: "", marker, lease.string("editor_session_id"))
}

suspend fun TowerAPI.renewLease(workspaceId: String, appNpub: String, lease: EditLease): EditLease {
    val body = jsonBody(jsonObjectOf("lease_token" to lease.token, "ttl_seconds" to LEASE_SECONDS))
    val reply = client.exchange("POST", TowerAPI.base(workspaceId) + "/edit-leases/" + TowerAPI.uuid(lease.id) + "/renew", appNpub, body)
    val o = parseJsonObjectOrNull(reply.body)
    if (reply.status == 409) throw LeaseError.Invalid(o?.string("code") ?: "expired")
    val next = o?.obj("lease")
    if (reply.status !in 200..299 || next == null) throw TowerError.Http(reply.status, o?.string("code"), "edit lease")
    if (lease.documentEditProtocol == CONCURRENT_DOCUMENT_EDIT_PROTOCOL &&
        (o.string("document_edit_protocol") != lease.documentEditProtocol || next.string("editor_session_id")?.lowercase() != lease.editorSessionId?.lowercase())) {
        throw LeaseError.Invalid("concurrent_protocol_not_acknowledged")
    }
    return EditLease(lease.id, next.string("lease_token") ?: lease.token, next.string("expires_at") ?: lease.expiresAt,
        lease.documentEditProtocol, lease.editorSessionId)
}

suspend fun TowerAPI.releaseLease(workspaceId: String, appNpub: String, lease: EditLease) {
    client.exchange(
        "POST", TowerAPI.base(workspaceId) + "/edit-leases/" + TowerAPI.uuid(lease.id) + "/release", appNpub,
        jsonBody(jsonObjectOf("lease_token" to lease.token)),
    )
}

// MARK: Storage

/** A prepared storage object. Persisted with an upload so a retry resumes it (field names match iOS). */
@Serializable
data class PreparedObject(val objectId: String, val uploadURL: String)

/** A presigned download address Tower offered on another host. */
data class PresignedDownload(val url: String, val sha256: String?, val sizeBytes: Long?)

const val FALLBACK_UPLOAD_LIMIT = 8 * 1024 * 1024

/**
 * Workspace storage prepare (`POST /workspaces/:w/storage/prepare`); Tower assigns the workspace owner and refuses
 * public objects for ordinary content.
 */
suspend fun TowerAPI.prepareStorage(
    workspaceId: String, appNpub: String, contentType: String, sizeBytes: Long, fileName: String, sha256: String,
): PreparedObject {
    val body = jsonBody(
        jsonObjectOf("content_type" to contentType, "size_bytes" to sizeBytes, "file_name" to fileName, "sha256_hex" to sha256, "is_public" to false),
    )
    val o = obj(client.request(method = "POST", path = TowerAPI.base(workspaceId) + "/storage/prepare", appNpub = appNpub, jsonBody = body), "storage prepare")
    val id = lowerUUID(o.string("object_id"))
    val upload = o.string("upload_url")
    if (id == null || o.bool("is_public") == true || upload == null) throw TowerError.Malformed("storage prepare")
    return PreparedObject(id, upload)
}

/**
 * Presigned download URL of a storage object, when Tower offers one on another host (otherwise null: the signed
 * Tower routes are used in-process).
 */
suspend fun TowerAPI.presignedDownloadURL(objectId: String): PresignedDownload? {
    val meta = storageObject(objectId) ?: return null
    val raw = meta.string("download_url") ?: return null
    runCatching { TransferPolicy.validate(raw, client.origin.host) }.getOrNull() ?: return null
    return PresignedDownload(raw, meta.string("sha256_hex")?.lowercase(), flexibleLong(meta["size_bytes"]))
}

/** Storage metadata (`GET /api/v4/storage/:id`), or null when the object is gone. */
suspend fun TowerAPI.storageObject(objectId: String): JsonObject? = try {
    obj(client.request(path = "/api/v4/storage/" + TowerAPI.uuid(objectId)), "storage object")
} catch (e: TowerError.Http) {
    if (e.status == 404) null else throw e
}

/**
 * Uploads bytes for a prepared object.
 *
 * - A presigned URL on another HTTPS host gets an **unsigned** PUT: no Authorization header, no cookies, redirects
 *   refused. The NIP-98 identity never leaves the pinned Tower origin.
 * - Tower's own object route (`/api/v4/storage/:id` on the pinned origin) gets the signed JSON upload (≤ 8 MB).
 * - Anything else (another path on the Tower host, plain HTTP elsewhere, credentials in the URL) is refused.
 */
suspend fun TowerAPI.upload(prepared: PreparedObject, bytes: ByteArray, contentType: String) {
    val url = prepared.uploadURL.toHttpUrlOrNull()
    if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.host.isEmpty()) {
        throw FileTransferError.UnsafeUploadURL
    }
    val towerHost = client.origin.host
    val objectPath = "/api/v4/storage/" + TowerAPI.uuid(prepared.objectId)
    val path = TransferPolicy.decodedPath(url)
    if (url.host == towerHost) {
        // Tower's API fallback can advertise http:// behind a proxy: only the exact object path is accepted, and the
        // request goes to the pinned HTTPS origin.
        if (path != objectPath || url.query != null) throw FileTransferError.UnsafeUploadURL
        if (bytes.size > FALLBACK_UPLOAD_LIMIT) throw FileTransferError.FallbackTooLarge
        client.request(method = "PUT", path = objectPath, jsonBody = jsonBody(jsonObjectOf("base64_data" to Base64.getEncoder().encodeToString(bytes))))
        return
    }
    if (url.scheme != "https" || path.startsWith("/api/v4/")) throw FileTransferError.UnsafeUploadURL
    val transfers = transfers
    if (transfers != null) {
        // Out-of-process transfer, keyed by object id so a finished upload is not repeated.
        transfers.upload("upload:" + prepared.objectId, prepared.uploadURL, bytes, contentType, towerHost)
    } else {
        client.putUnsigned(prepared.uploadURL, bytes, contentType)
    }
}

suspend fun TowerAPI.completeStorage(objectId: String, sizeBytes: Long, sha256: String) {
    val o = obj(
        client.request(
            method = "POST", path = "/api/v4/storage/" + TowerAPI.uuid(objectId) + "/complete",
            jsonBody = jsonBody(jsonObjectOf("size_bytes" to sizeBytes, "sha256_hex" to sha256)),
        ),
        "storage complete",
    )
    if (o.string("completed_at") == null) throw FileTransferError.NotAttachable
}

/**
 * Prepare → upload → complete, verifying Tower's recorded size and hash. [existing] resumes an earlier attempt;
 * [onPrepared] persists a fresh prepared object before any bytes are sent. Returns the storage object id.
 */
suspend fun TowerAPI.store(
    workspaceId: String, appNpub: String, bytes: ByteArray, contentType: String, fileName: String,
    existing: PreparedObject?, onPrepared: suspend (PreparedObject) -> Unit,
): String {
    val sha = Sha256Hex.of(bytes)
    var prepared = existing
    if (prepared != null && storageObject(prepared.objectId) == null) prepared = null   // gone: prepare a fresh one
    if (prepared == null) {
        val fresh = prepareStorage(workspaceId, appNpub, contentType, bytes.size.toLong(), fileName, sha)
        onPrepared(fresh)
        prepared = fresh
    }
    val meta = storageObject(prepared.objectId)
    if (meta?.string("completed_at") == null) {
        upload(prepared, bytes, contentType)
        completeStorage(prepared.objectId, bytes.size.toLong(), sha)
    }
    val done = storageObject(prepared.objectId)
    if (done == null || done.string("completed_at") == null || done.string("sha256_hex")?.lowercase() != sha ||
        flexibleLong(done["size_bytes"]) != bytes.size.toLong() || done.bool("is_public") == true
    ) throw FileTransferError.NotAttachable
    return prepared.objectId
}

/** Unsigned upload to a presigned object-store URL. Never carries the NIP-98 header or cookies. */
suspend fun TowerClient.putUnsigned(url: String, body: ByteArray, contentType: String) {
    val parsed = url.toHttpUrlOrNull()
    if (parsed == null || parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
        throw FileTransferError.UnsafeUploadURL
    }
    currentCoroutineContext().ensureActive()
    val response = transport.send(TransferPolicy.unsignedRequest(url, "PUT", contentType, body))
    if (response.status !in 200..299) throw TowerError.Http(response.status, null, "upload")
}

/** A fresh lower-case UUID (mutation ids, client request ids). */
fun newRequestId(): String = UUID.randomUUID().toString().lowercase()
