package studio.otherstuff.ambulando.core.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.PreparedObject
import java.io.File

/** Where a local edit of a sent text attachment stands. */
enum class TextEditState {
    /** Saved on this device only, not yet (fully) accepted by Tower. */
    PENDING,
    /** Tower holds a newer version or another lineage file; the edit is kept and waits for the user's choice. */
    CONFLICT,
    /** Tower refused the edit (permission, file or channel gone). The edit is kept. */
    FAILED,
    /** Tower accepted the edit. The record is kept briefly so the UI can show it, then removed. */
    SAVED,
}

/**
 * A durable local edit of a sent text attachment. The text lives beside it as exact bytes ([staged]); this record holds
 * the routing state needed to finish the save after an offline spell, a relaunch or an uncertain answer. `id` is also
 * the stable `client_mutation_id`.
 */
@Serializable
data class TextEdit(
    val id: String,
    val connectionId: String,
    val backendOrigin: String = "",
    val workspaceId: String,
    val channelId: String,
    val appNpub: String,
    val originMessageId: String,
    val originObjectId: String,
    val originFilename: String,
    val filename: String,
    val staged: String,
    val sha256: String,
    val sizeBytes: Long,
    val info: TextAttachmentInfo,
    /** The lineage File once known, with the version the edit is based on. */
    val fileId: String? = null,
    val baseVersionId: String? = null,
    /** Prepared / uploaded object for the edit bytes (kept across retries so nothing is uploaded twice). */
    val objectId: String? = null,
    val uploadURL: String? = null,
    val uploaded: Boolean = false,
    val state: TextEditState = TextEditState.PENDING,
    val message: String? = null,
    /** The newer version Tower reported with a conflict. */
    val conflictVersionId: String? = null,
    /** True once a send was attempted whose answer is unknown; the next try reloads Tower's state first. */
    val uncertain: Boolean = false,
    /** A successful version is durable before the separate filename PATCH begins. */
    val versionAccepted: Boolean = false,
    /** Kept intentionally on this device; never resumed automatically until explicit Save/Retry. */
    val localOnly: Boolean = false,
    val renamePending: Boolean = false,
    val fileRowVersion: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val lineage: studio.otherstuff.ambulando.core.transport.TextLineage
        get() = studio.otherstuff.ambulando.core.transport.TextLineage(originMessageId, originObjectId, originFilename)
}

/**
 * Durable store for [TextEdit]s: one JSON record and one byte file per edit, written to a temp file, flushed and
 * renamed so a crash leaves either the old or the new state. Staged bytes are verified against their hash on read.
 */
class TextEditStore(private val directory: File, private val beforeRecordCommit: () -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun safe(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid edit path" }
        return value
    }
    private fun record(id: String) = File(directory, "${safe(id)}.json")
    private fun bytesFile(edit: TextEdit): File {
        require(edit.staged.matches(Regex("[A-Za-z0-9_-]+\\.bin"))) { "Invalid staged path" }
        return File(directory, edit.staged)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        directory.mkdirs()
        val temp = File(directory, target.name + ".tmp")
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!temp.renameTo(target)) { temp.delete(); throw java.io.IOException("Could not store the edit") }
    }

    /** Creates or replaces the edit for [id], staging [text] as exact UTF-8 bytes. */
    @Synchronized fun create(base: TextEdit, text: String): TextEdit {
        val previous = get(base.id)
        val bytes = TextAttachments.bytes(text)
        require(bytes.size <= TextAttachments.MAX_BYTES) { "Text is too large" }
        val edit = base.copy(
            staged = "edit-${safe(base.id)}-${java.util.UUID.randomUUID()}.bin", sha256 = Sha256Hex.of(bytes), sizeBytes = bytes.size.toLong(),
            info = TextAttachmentInfo.of(text, TextAttachmentInfo.SOURCE_EDIT), objectId = null, uploadURL = null, uploaded = false,
            state = TextEditState.PENDING, message = null, conflictVersionId = null, uncertain = false, updatedAt = System.currentTimeMillis(),
        )
        atomicWrite(bytesFile(edit), bytes)
        beforeRecordCommit()
        atomicWrite(record(edit.id), json.encodeToString(edit).toByteArray())
        previous?.takeIf { it.staged != edit.staged }?.let { bytesFile(it).delete() }
        return edit
    }

    @Synchronized fun update(edit: TextEdit): TextEdit {
        val current = get(edit.id) ?: throw IllegalStateException("Edit was removed")
        check(current.staged == edit.staged) { "A newer edit replaced this save" }
        val next = edit.copy(updatedAt = System.currentTimeMillis())
        beforeRecordCommit()
        atomicWrite(record(edit.id), json.encodeToString(next).toByteArray())
        return next
    }

    @Synchronized fun get(id: String): TextEdit? = runCatching { json.decodeFromString<TextEdit>(record(id).readText()) }.getOrNull()

    @Synchronized fun list(): List<TextEdit> =
        (directory.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            runCatching { json.decodeFromString<TextEdit>(f.readText()) }.getOrNull()
        }.sortedBy { it.updatedAt }

    /** The unfinished edit of one sent attachment (a conflict or pending save), if any. */
    fun forAttachment(connectionId: String, backendOrigin: String, workspaceId: String, messageId: String, objectId: String): TextEdit? =
        list().lastOrNull { it.connectionId == connectionId && it.backendOrigin == backendOrigin && it.workspaceId == workspaceId && it.originMessageId == messageId && it.originObjectId == objectId && it.state != TextEditState.SAVED }

    /** The staged text, or null when the bytes are missing or no longer match their hash. */
    fun text(edit: TextEdit): String? {
        val file = bytesFile(edit)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (Sha256Hex.of(bytes) != edit.sha256) return null
        return TextAttachments.text(bytes)
    }

    fun bytes(edit: TextEdit): ByteArray? = bytesFile(edit).takeIf { it.isFile }?.readBytes()

    @Synchronized fun remove(id: String) {
        val edit = get(id)
        val metadata = record(id)
        if (metadata.exists() && !metadata.delete()) throw java.io.IOException("Could not remove edit record")
        if (edit != null) bytesFile(edit).delete()
    }

    @Synchronized fun removeRevision(edit: TextEdit) {
        if (get(edit.id)?.staged == edit.staged) remove(edit.id)
    }

    @Synchronized fun removeConnection(connectionId: String) { list().filter { it.connectionId == connectionId }.forEach { remove(it.id) } }
}

/**
 * Bounded on-device cache of immutable text bytes (an attachment object or a file version), so a sent card can be read
 * offline. Entries are keyed by their storage object id and verified against the recorded hash on read.
 */
class TextCache(private val directory: File, private val limitBytes: Long = 64L * 1024 * 1024) {
    companion object {
        fun scopeKey(connectionId: String, backendOrigin: String, signerNpub: String, workspaceId: String): String =
            Sha256Hex.of(listOf(connectionId, backendOrigin, signerNpub, workspaceId).joinToString("\u0000").toByteArray())
    }
    private fun file(objectId: String) = File(directory, "text-${Sha256Hex.of(objectId.toByteArray())}.bin")

    @Synchronized fun get(objectId: String, sha256: String?): ByteArray? {
        val f = file(objectId)
        if (!f.isFile) return null
        val bytes = f.readBytes()
        if (sha256 != null && Sha256Hex.of(bytes) != sha256.lowercase()) { f.delete(); return null }
        f.setLastModified(System.currentTimeMillis())
        return bytes
    }

    /** The first bytes of a cached entry for a card preview (no hash check: the full read verifies). */
    @Synchronized fun peek(objectId: String, max: Int): ByteArray? {
        val f = file(objectId)
        if (!f.isFile) return null
        return f.inputStream().use { input -> ByteArray(minOf(max.toLong(), f.length()).toInt()).also { input.read(it) } }
    }

    @Synchronized fun put(objectId: String, bytes: ByteArray) {
        if (bytes.size > limitBytes / 2) return
        directory.mkdirs()
        val temp = File(directory, "text-${Sha256Hex.of(objectId.toByteArray())}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file(objectId))) temp.delete()
        prune()
    }

    private fun prune() {
        val files = (directory.listFiles { f -> f.name.startsWith("text-") && f.name.endsWith(".bin") } ?: return).sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= limitBytes) break; total -= f.length(); f.delete() }
    }

    private fun note(key: String) = File(directory, "text-note-" + Sha256Hex.of(key.toByteArray()) + ".json")

    /** Small metadata notes (the last known version history) beside the cached bytes. */
    @Synchronized fun putNote(key: String, value: String) {
        directory.mkdirs()
        val target = note(key)
        val temp = File(directory, target.name + ".tmp")
        temp.outputStream().use { it.write(value.toByteArray()); it.fd.sync() }
        if (!temp.renameTo(target)) { temp.delete(); throw java.io.IOException("Could not cache history") }
    }
    @Synchronized fun getNote(key: String): String? = note(key).takeIf { it.isFile }?.readText()

    @Synchronized fun clear() { directory.listFiles()?.filter { it.name.startsWith("text-") }?.forEach { it.delete() } }
}
