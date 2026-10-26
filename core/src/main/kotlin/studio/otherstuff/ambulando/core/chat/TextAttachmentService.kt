package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.otherstuff.ambulando.core.transport.renameTextFile
import studio.otherstuff.ambulando.core.transport.TextFilenameConflict
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.FileTransferError
import studio.otherstuff.ambulando.core.transport.FileVersionRecord
import studio.otherstuff.ambulando.core.transport.LineageFile
import studio.otherstuff.ambulando.core.transport.MessageAttachment
import studio.otherstuff.ambulando.core.transport.PreparedObject
import studio.otherstuff.ambulando.core.transport.TextLineage
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.VersionOutcome
import studio.otherstuff.ambulando.core.transport.addFileVersion
import studio.otherstuff.ambulando.core.transport.attachmentBytes
import studio.otherstuff.ambulando.core.transport.createLineageFile
import studio.otherstuff.ambulando.core.transport.fileVersionBytes
import studio.otherstuff.ambulando.core.transport.fileVersions
import studio.otherstuff.ambulando.core.transport.lineageFile
import studio.otherstuff.ambulando.core.transport.store

/**
 * Reading, versioning and editing of text attachments that were already sent (shared contract v1).
 *
 * The signed message and its original storage object are never changed. The first edit uploads a new object and
 * registers a channel File that carries the lineage; later edits add versions on top of a base version. A save keeps
 * its local copy ([TextEditStore]) until Tower accepts it, and an uncertain answer is reconciled against Tower's state
 * before anything is sent again.
 */
class TextAttachmentService(val edits: TextEditStore, val cache: TextCache) {
    /** One readable state of the attachment: the original as sent, or a later file version. */
    @Serializable
    data class Entry(
        val label: String,
        val objectId: String,
        val versionId: String?,
        val sizeBytes: Long?,
        val sha256: String?,
        val createdAt: String?,
        val isOriginal: Boolean,
        val isCurrent: Boolean,
    )

    @Serializable
    data class History(val entries: List<Entry>, val fileId: String?, val currentVersionId: String?, val filename: String? = null) {
        val current: Entry get() = entries.first { it.isCurrent }
    }

    /** The original plus every file version, oldest first; the newest is current. Reads Tower (no cache). */
    suspend fun history(api: TowerAPI, workspaceId: String, appNpub: String, channelId: String, messageId: String, attachment: MessageAttachment): History {
        val lineage = TextLineage(messageId, attachment.storageObjectId, attachment.filename)
        val file = api.lineageFile(workspaceId, appNpub, channelId, lineage)
        val versions = file?.let { api.fileVersions(workspaceId, appNpub, it.file.id) }
        return historyOf(attachment, file, versions?.versions ?: emptyList(), versions?.currentVersionId).also { remember(workspaceId, messageId, attachment, it) }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun historyKey(workspaceId: String, messageId: String, attachment: MessageAttachment) = "history-$workspaceId-$messageId-${attachment.storageObjectId}"

    /** Keeps the last history Tower reported, so a sent card can be read from the device cache while offline. */
    private fun remember(workspaceId: String, messageId: String, attachment: MessageAttachment, history: History) {
        cache.putNote(historyKey(workspaceId, messageId, attachment), json.encodeToString(history))
    }

    /** The last history seen for this attachment, or just the original when nothing was seen. */
    fun cachedHistory(workspaceId: String, messageId: String, attachment: MessageAttachment): History =
        cache.getNote(historyKey(workspaceId, messageId, attachment))?.let { runCatching { json.decodeFromString<History>(it) }.getOrNull() }
            ?: historyOf(attachment, null, emptyList(), null)

    fun historyOf(attachment: MessageAttachment, file: LineageFile?, versions: List<FileVersionRecord>, currentVersionId: String?): History {
        val currentId = currentVersionId ?: versions.lastOrNull()?.id
        val entries = ArrayList<Entry>()
        entries.add(Entry("Original", attachment.storageObjectId, null, attachment.sizeBytes, attachment.sha256, null, isOriginal = true, isCurrent = versions.isEmpty()))
        versions.forEachIndexed { index, v ->
            entries.add(Entry("Edit ${index + 1}", v.storageObjectId, v.id, v.sizeBytes, v.sha256, v.createdAt, isOriginal = false, isCurrent = v.id == currentId))
        }
        // A current id Tower reported that is not in the list falls back to the newest entry.
        if (entries.none { it.isCurrent }) entries[entries.lastIndex] = entries.last().copy(isCurrent = true)
        return History(entries, file?.file?.id, currentId, file?.file?.displayName)
    }

    /** Bytes of an entry: the on-device cache first (immutable, hash-checked), then Tower. */
    suspend fun read(api: TowerAPI, workspaceId: String, appNpub: String, fileId: String?, entry: Entry): ByteArray {
        cache.get(entry.objectId, entry.sha256)?.let { return it }
        val bytes = if (entry.versionId != null && fileId != null) {
            api.fileVersionBytes(workspaceId, appNpub, fileId, FileVersionRecord(
                entry.versionId, fileId, 0, entry.objectId, entry.sizeBytes, null, entry.sha256, null, null, null, entry.createdAt ?: "",
            ))
        } else api.attachmentBytes(entry.objectId, entry.sha256)
        cache.put(entry.objectId, bytes)
        return bytes
    }

    /** Offline read of an entry already cached on the device. */
    fun cached(entry: Entry): ByteArray? = cache.get(entry.objectId, entry.sha256)

    /** Starts (or replaces) the device copy of an edit and returns it; nothing is sent. */
    fun beginEdit(
        id: String, connectionId: String, workspaceId: String, channelId: String, appNpub: String, messageId: String,
        attachment: MessageAttachment, filename: String, text: String, fileId: String?, baseVersionId: String?, backendOrigin: String = "", localOnly: Boolean = false,
    ): TextEdit {
        val base = TextEdit(
            id = id, connectionId = connectionId, backendOrigin = backendOrigin, localOnly = localOnly, workspaceId = workspaceId, channelId = channelId, appNpub = appNpub,
            originMessageId = messageId, originObjectId = attachment.storageObjectId, originFilename = attachment.filename,
            filename = TextAttachments.normalizeName(filename, attachment.filename), staged = "", sha256 = "", sizeBytes = 0,
            info = TextAttachmentInfo.of("", TextAttachmentInfo.SOURCE_EDIT), fileId = fileId, baseVersionId = baseVersionId,
        )
        return edits.create(base, text)
    }

    /**
     * Finishes a local edit: upload its bytes (resuming a prepared object), then create the lineage file or add a
     * version. Always returns the stored state; failures never lose the local copy.
     */
    private val saveMutex = Mutex()
    suspend fun save(api: TowerAPI, id: String): TextEdit = saveMutex.withLock { saveLocked(api, id) }

    private suspend fun saveLocked(api: TowerAPI, id: String): TextEdit {
        var e = edits.get(id) ?: throw IllegalStateException("No such edit")
        require(e.backendOrigin.isEmpty() || e.backendOrigin == api.client.origin.url) { "Backend changed" }
        if (e.state == TextEditState.SAVED) return e
        if (e.localOnly) e = edits.update(e.copy(localOnly = false))
        if (e.versionAccepted) return finishRename(api, e)
        if (!e.uploaded) {
            val bytes = edits.bytes(e)
            if (bytes == null || Sha256Hex.of(bytes) != e.sha256) return edits.update(e.copy(state = TextEditState.FAILED, message = "The device copy of this edit is missing."))
            val previous = e.objectId?.let { oid -> e.uploadURL?.let { PreparedObject(oid, it) } }
            try {
                val objectId = api.store(e.workspaceId, e.appNpub, bytes, TextAttachments.CONTENT_TYPE, e.filename, previous) { fresh ->
                    e = edits.update(e.copy(objectId = fresh.objectId, uploadURL = fresh.uploadURL))
                }
                e = edits.update(e.copy(objectId = objectId, uploaded = true, message = null))
            } catch (c: CancellationException) {
                throw c
            } catch (t: FileTransferError) {
                return edits.update(e.copy(state = TextEditState.FAILED, message = t.message))
            } catch (t: Exception) {
                return edits.update(e.copy(state = TextEditState.PENDING, message = "Saved on this device; will upload when online."))
            }
        }
        if (e.uncertain) {
            e = try { reconcile(api, e) } catch (c: CancellationException) { throw c } catch (t: Exception) {
                return edits.update(e.copy(message = "Saved on this device; Tower could not be reached."))
            }
            if (e.versionAccepted) return finishRename(api, e)
            if (e.state != TextEditState.PENDING) return e
        }
        val objectId = e.objectId ?: return edits.update(e.copy(uploaded = false))
        // Persist the uncertain phase before the request: a crash or failed acceptance write must reconcile first.
        e = edits.update(e.copy(uncertain = true))
        val outcome = try {
            if (e.fileId == null) firstEdit(api, e, objectId) else api.addFileVersion(e.workspaceId, e.appNpub, e.fileId!!, e.baseVersionId ?: return edits.update(e.copy(fileId = null)), objectId, e.id)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            VersionOutcome.Retryable(null, t.message ?: "Network error")
        }
        val accepted = edits.update(apply(e, outcome))
        return if (accepted.versionAccepted) finishRename(api, accepted) else accepted
    }

    /** First edit: reload the canonical lineage before creating, so a lost earlier answer is never duplicated. */
    private suspend fun firstEdit(api: TowerAPI, e: TextEdit, objectId: String): VersionOutcome {
        val existing = api.lineageFile(e.workspaceId, e.appNpub, e.channelId, e.lineage)
        if (existing != null) {
            val versions = api.fileVersions(e.workspaceId, e.appNpub, existing.file.id)
            val ours = versions.versions.firstOrNull { it.storageObjectId == objectId }
            return if (ours != null) VersionOutcome.Saved(existing, ours.id, replay = true)
            else VersionOutcome.Conflict(existing, versions.versions.lastOrNull { it.id == versions.currentVersionId } ?: versions.versions.lastOrNull(), "lineage_file_exists")
        }
        return api.createLineageFile(e.workspaceId, e.appNpub, e.channelId, objectId, e.filename, e.lineage)
    }

    private fun apply(e: TextEdit, outcome: VersionOutcome): TextEdit = when (outcome) {
        is VersionOutcome.Saved -> e.copy(
            state = TextEditState.PENDING, versionAccepted = true,
            renamePending = outcome.file.file.displayName != e.filename, fileRowVersion = outcome.file.file.rowVersion,
            fileId = outcome.file.file.id, baseVersionId = outcome.versionId ?: outcome.file.currentVersionId,
            message = null, uncertain = false, conflictVersionId = null,
        )
        is VersionOutcome.Conflict -> e.copy(
            state = TextEditState.CONFLICT, fileId = outcome.file?.file?.id ?: e.fileId, conflictVersionId = outcome.current?.id ?: outcome.file?.currentVersionId,
            message = "Someone saved a newer version. Your edit is kept on this device.", uncertain = false,
        )
        is VersionOutcome.Denied -> e.copy(state = TextEditState.FAILED, message = outcome.message, uncertain = false)
        is VersionOutcome.Retryable -> e.copy(state = TextEditState.PENDING, message = outcome.message, uncertain = true)
    }

    /** After an uncertain answer: did Tower take our object? Saved if so; otherwise safe to send again. */
    private suspend fun reconcile(api: TowerAPI, e: TextEdit): TextEdit {
        val file = if (e.fileId != null) null else api.lineageFile(e.workspaceId, e.appNpub, e.channelId, e.lineage)
        val fileId = e.fileId ?: file?.file?.id ?: return e.copy(uncertain = false)
        val versions = api.fileVersions(e.workspaceId, e.appNpub, fileId)
        val ours = versions.versions.firstOrNull { it.storageObjectId == e.objectId }
        if (ours != null) {
            val canonical = file ?: api.lineageFile(e.workspaceId, e.appNpub, e.channelId, e.lineage)
                ?: throw IllegalStateException("Accepted lineage unavailable")
            return edits.update(apply(e, VersionOutcome.Saved(canonical, ours.id, replay = true)))
        }
        if (e.fileId == null) {
            // A lineage file that does not hold our object belongs to someone else's first edit: conflict, not overwrite.
            return edits.update(e.copy(state = TextEditState.CONFLICT, fileId = fileId, conflictVersionId = versions.currentVersionId, uncertain = false,
                message = "Someone saved a newer version. Your edit is kept on this device."))
        }
        return edits.update(e.copy(uncertain = false))
    }

    private suspend fun finishRename(api: TowerAPI, edit: TextEdit): TextEdit {
        if (!edit.renamePending) return edits.update(edit.copy(state = TextEditState.SAVED, message = null))
        return try {
            val canonical = api.lineageFile(edit.workspaceId, edit.appNpub, edit.channelId, edit.lineage)
                ?: throw IllegalStateException("Accepted lineage unavailable")
            if (canonical.file.displayName == edit.filename) return edits.update(edit.copy(state = TextEditState.SAVED, renamePending = false, fileRowVersion = canonical.file.rowVersion, message = null))
            if (canonical.file.rowVersion != edit.fileRowVersion) throw TextFilenameConflict(canonical)
            val file = api.renameTextFile(edit.workspaceId, edit.appNpub, requireNotNull(edit.fileId), edit.filename, edit.fileRowVersion)
            edits.update(edit.copy(state = TextEditState.SAVED, renamePending = false, fileRowVersion = file.file.rowVersion, message = null))
        } catch (c: CancellationException) { throw c } catch (e: TextFilenameConflict) {
            edits.update(edit.copy(state = TextEditState.CONFLICT, fileRowVersion = e.file?.file?.rowVersion ?: edit.fileRowVersion,
                message = "Text version saved, but the filename changed elsewhere. Your desired name is kept. Compare before retrying the rename."))
        } catch (e: Exception) {
            edits.update(edit.copy(state = TextEditState.PENDING,
                message = "Text version saved; filename update is pending. Your desired name is kept on this device. Retry to finish renaming."))
        }
    }

    /**
     * The user's explicit choice after a conflict: keep this edit and base it on Tower's latest version. Nothing is
     * sent until [save] is called again.
     */
    suspend fun rebase(api: TowerAPI, id: String): TextEdit {
        val e = edits.get(id) ?: throw IllegalStateException("No such edit")
        if (e.versionAccepted) {
            val canonical = api.lineageFile(e.workspaceId, e.appNpub, e.channelId, e.lineage)
                ?: throw IllegalStateException("Accepted lineage unavailable")
            return edits.update(e.copy(state = TextEditState.PENDING, fileRowVersion = canonical.file.rowVersion, message = null))
        }
        val fileId = e.fileId ?: api.lineageFile(e.workspaceId, e.appNpub, e.channelId, e.lineage)?.file?.id
            ?: return edits.update(e.copy(state = TextEditState.PENDING, uncertain = false, message = null))
        val versions = api.fileVersions(e.workspaceId, e.appNpub, fileId)
        val latest = versions.currentVersionId ?: versions.versions.lastOrNull()?.id
        return edits.update(e.copy(state = TextEditState.PENDING, fileId = fileId, baseVersionId = latest, conflictVersionId = null, uncertain = false, message = null))
    }

    fun discard(id: String) = edits.remove(id)
}
