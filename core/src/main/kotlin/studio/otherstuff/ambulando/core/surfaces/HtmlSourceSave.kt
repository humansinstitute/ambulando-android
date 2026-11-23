package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.transport.*

/** Acknowledged saves are verification-only on retry, including after closing/reopening the editor. */
class HtmlSourceSave(private val drafts: HtmlDraftStore) {
    suspend fun save(
        draft: HtmlDraftStore.Draft,
        upload: suspend (HtmlDraftStore.Draft) -> String,
        commit: suspend (HtmlDraftStore.Draft) -> VersionOutcome,
        head: suspend () -> String?,
        read: suspend (String) -> ByteArray,
        checkCurrent: suspend () -> Unit,
    ) {
        checkCurrent()
        var receipt = draft
        // Persist the same mutation id before sending; interrupted submissions can be replayed safely.
        drafts.checkpoint(receipt)
        if (!receipt.accepted) {
            if (receipt.uploadedObjectId == null) {
                receipt = receipt.copy(uploadedObjectId = upload(receipt))
                withContext(NonCancellable) { drafts.save(receipt) }
            }
            checkCurrent()
            when (val result = commit(receipt)) {
                is VersionOutcome.Saved -> {
                    receipt = receipt.copy(accepted = true, savedVersionId = result.versionId ?: result.file.currentVersionId)
                    // Preserve acknowledgement before any readback request; never upload this edit twice.
                    withContext(NonCancellable) { drafts.save(receipt) }
                }
                is VersionOutcome.Conflict -> error("A newer version was saved elsewhere. Your source is kept; copy it before reloading.")
                is VersionOutcome.Denied -> error("Permission denied. Your source is kept here.")
                is VersionOutcome.Retryable -> error("Save could not be confirmed. Your source and retry identity are kept here.")
            }
        }
        checkCurrent()
        val version = receipt.savedVersionId ?: head()
            ?: error("Saved, but its version could not be verified. Your draft is kept. Retry only verifies the saved content.")
        val bytes = read(version)
        checkCurrent()
        check(bytes.contentEquals(receipt.source.toByteArray(Charsets.UTF_8))) {
            "Saved content could not be verified. Your draft is kept. Retry only verifies the saved content."
        }
        drafts.clear()
    }
}

/** Read one immutable version directly, independent of history length or a concurrent newer head. */
suspend fun TowerAPI.savedHtmlBytes(workspace: String, app: String, file: String, version: String): ByteArray =
    client.request(path = TowerAPI.base(workspace) + "/files/" + TowerAPI.uuid(file) + "/versions/" + TowerAPI.uuid(version) + "/object", appNpub = app).also { if (it.size > MAX_DOWNLOAD_BYTES) throw FileTransferError.TooLarge }
