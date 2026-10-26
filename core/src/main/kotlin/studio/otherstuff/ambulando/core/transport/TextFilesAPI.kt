package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.nostr.Sha256Hex

// Files and versions routes used by text attachment edits (shared contract v1). Every path is built from validated
// UUIDs and every response is checked to belong to the requested workspace and file before it is used.

/** One immutable version of a Tower File (`serializeFlightDeckPgFileVersion`). */
data class FileVersionRecord(
    val id: String,
    val fileId: String,
    val versionNumber: Long,
    val storageObjectId: String,
    val sizeBytes: Long?,
    val contentType: String?,
    val sha256: String?,
    val baseVersionId: String?,
    val operation: String?,
    val createdByNpub: String?,
    val createdAt: String,
) {
    companion object {
        fun parse(raw: JsonObject): FileVersionRecord {
            val id = lowerUUID(raw.string("id"))
            val file = lowerUUID(raw.string("file_id"))
            val storage = lowerUUID(raw.string("storage_object_id"))
            if (id == null || file == null || storage == null) throw TowerError.Malformed("file version")
            return FileVersionRecord(
                id, file, flexibleLong(raw["version_number"]) ?: 0, storage, flexibleLong(raw["size_bytes"]), raw.string("content_type"),
                raw.string("sha256_hex")?.lowercase(), lowerUUID(raw.string("base_version_id")), raw.string("operation"),
                raw.string("created_by_actor_npub"), raw.string("created_at") ?: "",
            )
        }
    }
}

/** The file Tower holds for one sent text attachment, with its current version id. */
data class LineageFile(val file: FileRecord, val currentVersionId: String?) {
    companion object {
        fun parse(raw: JsonObject): LineageFile {
            val file = FileRecord.parse(raw)
            return LineageFile(file, lowerUUID(raw.string("current_version_id")) ?: lowerUUID(raw.obj("current_version")?.string("id")))
        }
    }
}

data class FileVersionList(val fileId: String, val currentVersionId: String?, val versions: List<FileVersionRecord>)

/** Identifies the sent attachment a lineage file continues (`metadata.text_attachment_lineage`). */
data class TextLineage(val originMessageId: String, val originStorageObjectId: String, val originFilename: String) {
    val requestObject: JsonObject
        get() = jsonObjectOf(
            "v" to 1, "origin_message_id" to originMessageId, "origin_storage_object_id" to originStorageObjectId, "origin_filename" to originFilename,
        )

    fun matches(file: JsonObject): Boolean {
        val l = file.obj("metadata")?.obj("text_attachment_lineage") ?: return false
        return l.string("origin_message_id")?.lowercase() == originMessageId.lowercase() &&
            l.string("origin_storage_object_id")?.lowercase() == originStorageObjectId.lowercase()
    }
}

/** How a first edit (lineage file create) or a later version POST ended. */
sealed class VersionOutcome {
    /** Tower accepted the bytes; [versionId] is the version now current. */
    data class Saved(val file: LineageFile, val versionId: String?, val replay: Boolean) : VersionOutcome()
    /** Another save holds the lineage / is newer: keep the local edit, show the conflict. [current] is Tower's latest version. */
    data class Conflict(val file: LineageFile?, val current: FileVersionRecord?, val code: String) : VersionOutcome()
    /** The channel or file is gone or not writable for this identity; the edit stays local. */
    data class Denied(val status: Int, val code: String?, val message: String) : VersionOutcome()
    /** Transport or server trouble; the same request may be repeated (version POSTs are keyed by their base version). */
    data class Retryable(val status: Int?, val message: String) : VersionOutcome()
}

suspend fun TowerAPI.fileVersions(workspaceId: String, appNpub: String, fileId: String): FileVersionList {
    val path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/versions"
    val o = obj(client.request(path = path, query = listOf("limit" to "200"), appNpub = appNpub), "versions")
    val rows = o.objects("versions") ?: throw TowerError.Malformed("versions")
    if (rows.size >= 200) throw TowerError.ListLimitReached("versions")
    val versions = rows.map { FileVersionRecord.parse(it) }.filter { it.fileId == fileId.lowercase() }
    return FileVersionList(fileId.lowercase(), lowerUUID(o.string("current_version_id")), versions.sortedBy { it.versionNumber })
}

/** Immutable bytes of one version, verified against the version's recorded hash when it has one. */
suspend fun TowerAPI.fileVersionBytes(workspaceId: String, appNpub: String, fileId: String, version: FileVersionRecord): ByteArray {
    val bytes = client.request(
        path = TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/versions/" + TowerAPI.uuid(version.id) + "/object", appNpub = appNpub,
    )
    if (bytes.size > MAX_DOWNLOAD_BYTES) throw FileTransferError.TooLarge
    if (version.sha256 != null && Sha256Hex.of(bytes) != version.sha256) throw FileTransferError.Integrity
    return bytes
}

/** The original sent attachment's bytes (the message's own storage object), verified against its hash when known. */
suspend fun TowerAPI.attachmentBytes(objectId: String, sha256: String?): ByteArray {
    val bytes = storageContent(objectId)
    if (bytes.size > MAX_DOWNLOAD_BYTES) throw FileTransferError.TooLarge
    if (sha256 != null && Sha256Hex.of(bytes) != sha256.lowercase()) throw FileTransferError.Integrity
    return bytes
}

/**
 * The channel File that continues a sent text attachment, matched only by both its origin message and origin storage
 * object ids. Never guessed from names, creation order or siblings. Null when Tower has none yet.
 */
suspend fun TowerAPI.lineageFile(workspaceId: String, appNpub: String, channelId: String, lineage: TextLineage): LineageFile? {
    val path = TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/files"
    val query = listOf("origin_message_id" to TowerAPI.uuid(lineage.originMessageId),
        "origin_storage_object_id" to TowerAPI.uuid(lineage.originStorageObjectId), "limit" to "2")
    suspend fun lookup(archived: Boolean): List<JsonObject> = obj(client.request(path = path,
        query = query + ("archived" to archived.toString()), appNpub = appNpub), "lineage files")
        .objects("files") ?: throw TowerError.Malformed("lineage files")
    val active = lookup(false)
    val rows = if (active.isEmpty()) lookup(true) else active
    val matches = rows.filter { lineage.matches(it) && it.string("deleted_at") == null }
    if (matches.isEmpty()) return null
    // The backend allows one canonical file per lineage; more than one is a backend fault we must not paper over.
    if (matches.size > 1) throw TowerError.Malformed("lineage files")
    return LineageFile.parse(matches[0])
}

private fun errorMessage(status: Int, code: String?): String = when {
    status == 403 -> "You do not have permission to edit this file."
    status == 404 -> "The file or channel is no longer available."
    else -> "Tower rejected the edit (HTTP $status${code?.let { ", $it" } ?: ""})."
}

private fun retryable(status: Int): Boolean = status == 408 || status == 425 || status == 429 || status in 500..599

/**
 * First edit of a sent attachment: registers the already uploaded [newObjectId] as a channel File carrying the lineage.
 * 201 creates it; 200 replays a create that already holds this object; 409 `lineage_file_exists` returns the canonical
 * file, which the caller adopts and rebases onto with a normal version POST (never an automatic overwrite).
 */
suspend fun TowerAPI.createLineageFile(
    workspaceId: String, appNpub: String, channelId: String, newObjectId: String, displayName: String, lineage: TextLineage,
): VersionOutcome {
    val body = jsonBody(jsonObjectOf(
        "storage_object_id" to newObjectId, "display_name" to displayName,
        "metadata" to jsonObjectOf("text_attachment_lineage" to lineage.requestObject),
    ))
    val reply = try {
        client.exchange("POST", TowerAPI.base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/files", appNpub, body)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        return VersionOutcome.Retryable(null, e.message ?: "Network error")
    }
    val o = parseJsonObjectOrNull(reply.body)
    val code = o?.string("code")
    return when {
        reply.status == 201 -> {
            val file = o?.obj("file")?.let { runCatching { LineageFile.parse(it) }.getOrNull() } ?: return VersionOutcome.Retryable(reply.status, "Unreadable reply")
            VersionOutcome.Saved(file, file.currentVersionId, replay = false)
        }
        reply.status == 200 && o?.bool("idempotent_replay") == true -> {
            val file = o.obj("file")?.let { runCatching { LineageFile.parse(it) }.getOrNull() } ?: return VersionOutcome.Retryable(reply.status, "Unreadable reply")
            VersionOutcome.Saved(file, file.currentVersionId, replay = true)
        }
        reply.status == 409 && code == "lineage_file_exists" -> VersionOutcome.Conflict(
            o?.obj("file")?.let { runCatching { LineageFile.parse(it) }.getOrNull() },
            o?.obj("current_version")?.let { runCatching { FileVersionRecord.parse(it) }.getOrNull() }, code,
        )
        retryable(reply.status) -> VersionOutcome.Retryable(reply.status, "Tower is unavailable (HTTP ${reply.status}); will retry.")
        else -> VersionOutcome.Denied(reply.status, code, errorMessage(reply.status, code))
    }
}

/**
 * Later edit: a new immutable version on top of [baseVersionId]. 409 `stale_base_version` carries the latest version;
 * the local edit is kept and the user chooses how to continue. [clientMutationId] is stable per edit.
 */
suspend fun TowerAPI.addFileVersion(
    workspaceId: String, appNpub: String, fileId: String, baseVersionId: String, newObjectId: String, clientMutationId: String,
): VersionOutcome {
    val body = jsonBody(jsonObjectOf(
        "base_version_id" to baseVersionId, "storage_object_id" to newObjectId, "client_mutation_id" to clientMutationId,
    ))
    val reply = try {
        client.exchange("POST", TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId) + "/versions", appNpub, body)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        return VersionOutcome.Retryable(null, e.message ?: "Network error")
    }
    val o = parseJsonObjectOrNull(reply.body)
    val code = o?.string("code")
    return when {
        reply.status == 201 -> {
            val file = o?.obj("file")?.let { runCatching { LineageFile.parse(it) }.getOrNull() } ?: return VersionOutcome.Retryable(reply.status, "Unreadable reply")
            VersionOutcome.Saved(file, lowerUUID(o.obj("version")?.string("id")), replay = false)
        }
        reply.status == 409 && code == "stale_base_version" -> VersionOutcome.Conflict(
            o?.obj("file")?.let { runCatching { LineageFile.parse(it) }.getOrNull() },
            o?.obj("current_version")?.let { runCatching { FileVersionRecord.parse(it) }.getOrNull() }, code,
        )
        retryable(reply.status) -> VersionOutcome.Retryable(reply.status, "Tower is unavailable (HTTP ${reply.status}); will retry.")
        else -> VersionOutcome.Denied(reply.status, code, errorMessage(reply.status, code))
    }
}

class TextFilenameConflict(val file: LineageFile?) : Exception("The filename changed elsewhere")

/** Separate filename update, always after the accepted version has been persisted locally. */
suspend fun TowerAPI.renameTextFile(workspaceId: String, appNpub: String, fileId: String, name: String, rowVersion: Long?): LineageFile {
    val fields = mutableMapOf<String, Any?>("display_name" to name)
    if (rowVersion != null) fields["row_version"] = rowVersion
    val reply = client.exchange("PATCH", TowerAPI.base(workspaceId) + "/files/" + TowerAPI.uuid(fileId), appNpub, jsonBody(toJsonElement(fields) as JsonObject))
    val body = parseJsonObjectOrNull(reply.body)
    if (reply.status == 409) throw TextFilenameConflict(body?.obj("file")?.let { LineageFile.parse(it) })
    if (reply.status !in 200..299) throw java.io.IOException("Filename update refused (HTTP ${reply.status})")
    val file = body?.obj("file")?.let { LineageFile.parse(it) } ?: throw TowerError.Malformed("renamed file")
    if (file.file.id != fileId || file.file.workspaceId != workspaceId || file.file.displayName != name) throw TowerError.Malformed("renamed file")
    return file
}
