package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.replaceDraftToken
import studio.otherstuff.ambulando.core.sync.SyncEngine
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.transport.FileTransferError
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.MAX_DOWNLOAD_BYTES
import studio.otherstuff.ambulando.core.transport.ObjectTransfers
import studio.otherstuff.ambulando.core.transport.PreparedObject
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.isUUID
import studio.otherstuff.ambulando.core.transport.store
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/** One image or file pasted into a chat composer, from the paste until its placeholder becomes storage Markdown. */
data class ComposerUpload(
    val id: String,
    /** Connection, workspace, channel and thread captured at paste. The result only ever goes to this draft. */
    val target: ChatTarget,
    val appNpub: String,
    val kind: UploadToken.Kind,
    val filename: String,
    val mimeType: String,
    val phase: Phase,
    /** False when the staged bytes are gone (only Remove is left). */
    val retryable: Boolean,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    /** The pasted file came with its own name (otherwise the staged copy's name is used). */
    val named: Boolean = true,
    val prepared: PreparedObject? = null,
) {
    sealed class Phase {
        /** Reading the pasted item and staging it on the device. */
        data object Preparing : Phase()
        /** Staged; waiting for the upload ahead of it. */
        data object Waiting : Phase()
        data object Uploading : Phase()
        data class Failed(val message: String) : Phase()
        data class Done(val objectId: String) : Phase()
    }

    val token: String get() = UploadToken.make(kind, id)
    val isActive: Boolean get() = phase == Phase.Preparing || phase == Phase.Waiting || phase == Phase.Uploading

    /** The Markdown that replaces the placeholder (the web's storage image / file Markdown). */
    val markdown: String? get() = (phase as? Phase.Done)?.let { ComposerUploadText.markdown(kind, it.objectId, filename) }
}

/**
 * A live composer showing one target's text. Results for that target are applied to it directly. Called on the
 * coordinator's thread (the app's main thread).
 */
interface UploadTokenHost {
    val uploadTarget: ChatTarget
    val uploadText: String
    /** Replaces the first `token` in the live text, keeping the caret; false when the text no longer holds it. */
    fun replaceUploadToken(token: String, replacement: String): Boolean
}

/**
 * One pasted image or file as the app hands it over. Images are already privacy-stripped (the app re-encodes them as
 * PNG without metadata inside [read]); [read] runs off the coordinator's thread.
 */
class PastedItem(
    val kind: UploadToken.Kind,
    /** The item's own name, if it had one. */
    val suggestedName: String? = null,
    /** MIME type of a file (images are always uploaded as `image/png`). */
    val mimeType: String = "application/octet-stream",
    /** Extension for a file without one in its name. */
    val preferredExtension: String? = null,
    /** Known size, so an oversized item is refused before it is read. */
    val sizeBytes: Long? = null,
    val read: suspend () -> ByteArray,
)

/** What an upload needs to reach Tower: staged bytes, the captured target and a prepared object to reuse. */
class ComposerUploadJob(
    val id: String,
    val target: ChatTarget,
    val appNpub: String,
    val staged: File,
    val mimeType: String,
    val filename: String,
    val sha256: String,
    val prepared: PreparedObject?,
    val onPrepared: suspend (PreparedObject) -> Unit,
)

/**
 * Uploads a job through the sync engine (`USER` priority) with the existing storage route ([TowerAPI.store]: prepare →
 * upload → complete, prepared object reused, out-of-process transfers for presigned URLs), signed by the connection's
 * own unlocked identity.
 */
class ComposerUploadTransfer(
    private val engine: SyncEngine,
    private val identity: IdentityService,
    private val transport: HttpTransport,
    private val transfers: ObjectTransfers? = null,
) {
    suspend fun upload(job: ComposerUploadJob, connection: ConnectionRecord): String {
        if (connection.id != job.target.connectionId) throw ComposerUploadException(ComposerUploadError.OTHER_CONNECTION)
        return engine.perform("paste:${connection.id}:${job.id}", SyncPriority.USER) {
            val state = identity.state()
            if (state !is IdentityState.Unlocked || state.npub != connection.signerNpub) throw ComposerUploadException(ComposerUploadError.LOCKED)
            val bytes = job.staged.readBytes()
            if (Sha256Hex.of(bytes) != job.sha256) throw FileTransferError.Integrity
            val client = TowerClient(TowerOrigin(connection.origin), transport, identity)
            TowerAPI(client, transfers).store(job.target.workspaceId, job.appNpub, bytes, job.mimeType, job.filename, job.prepared, job.onPrepared)
        }
    }
}

/**
 * Pasted images and files of every composer (the web's chat paste handling).
 *
 * - A paste returns one placeholder per item at once (to insert at the caret); reading and staging run off the
 *   coordinator's thread, and one upload runs at a time.
 * - Each upload keeps the target captured at paste. Its Markdown replaces the placeholder in that target's live
 *   composer, or in that target's stored draft when the user has moved on (the store's replace-draft-token step),
 *   never anywhere else.
 * - Staged bytes and a small record sit in the staging directory until the result is in the draft, so a relaunch can
 *   still retry or apply it.
 *
 * Like the iOS main-actor class this is used from one thread: every public method must be called on [main] (the
 * app's main dispatcher), where all state changes and host calls happen.
 */
class ComposerUploads(
    private val store: LocalStore,
    private val staging: File,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val upload: suspend (ComposerUploadJob) -> String,
) {
    companion object {
        /** At most this many items per paste (as the photo picker). */
        const val MAX_ITEMS_PER_PASTE = ComposerUploadText.MAX_ITEMS_PER_PASTE
        /** The same per-file limit as Files uploads. */
        const val MAX_BYTES: Long = MAX_DOWNLOAD_BYTES.toLong()

        private val json = Json { ignoreUnknownKeys = true }

        fun message(error: Throwable): String = when (error) {
            is CancellationException -> "Upload was interrupted."
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> "Offline. Retry when connected."
            is SocketTimeoutException -> "Tower did not answer in time."
            is IOException -> "Network problem (${error.javaClass.simpleName})."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Upload failed."
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val _items = MutableStateFlow<List<ComposerUpload>>(emptyList())
    private val _notices = MutableStateFlow<Map<String, String>>(emptyMap())
    val items: StateFlow<List<ComposerUpload>> = _items.asStateFlow()
    /** A short message about the last paste (for example items over the limit), keyed by draft. */
    val notices: StateFlow<Map<String, String>> = _notices.asStateFlow()

    private val hosts = HashMap<String, WeakReference<UploadTokenHost>>()
    private var running: Pair<String, Job>? = null
    private val loads = HashMap<String, Job>()

    fun uploads(target: ChatTarget): List<ComposerUpload> = _items.value.filter { it.target == target && it.markdown == null }
    fun notice(target: ChatTarget): String? = _notices.value[target.draftKey]
    fun clearNotice(target: ChatTarget) { _notices.value = _notices.value - target.draftKey }

    private fun update(id: String, change: (ComposerUpload) -> ComposerUpload) {
        _items.value = _items.value.map { if (it.id == id) change(it) else it }
    }

    private fun item(id: String): ComposerUpload? = _items.value.firstOrNull { it.id == id }

    // MARK: Paste

    /**
     * Starts uploads for the pasted images and files and returns the placeholders to insert at the caret, in order
     * (empty: nothing to upload). `context` names images as the web does ("message", "thread").
     */
    fun paste(pasted: List<PastedItem>, target: ChatTarget, appNpub: String, context: String): List<String> {
        if (pasted.isEmpty()) return emptyList()
        val notices = _notices.value.toMutableMap()
        if (pasted.size > MAX_ITEMS_PER_PASTE) notices[target.draftKey] = "Only the first $MAX_ITEMS_PER_PASTE pasted items are uploaded."
        else notices.remove(target.draftKey)
        _notices.value = notices
        val tokens = ArrayList<String>()
        for (p in pasted.take(MAX_ITEMS_PER_PASTE)) {
            val id = UUID.randomUUID().toString().lowercase()
            val image = p.kind == UploadToken.Kind.IMAGE
            val name = if (image) ComposerUploadText.imageName(context) else ComposerUploadText.fileName(p.suggestedName, p.preferredExtension)
            val item = ComposerUpload(
                id, target, appNpub, p.kind, name, if (image) "image/png" else p.mimeType, ComposerUpload.Phase.Preparing, retryable = false,
                named = image || !p.suggestedName.isNullOrBlank(),
            )
            _items.value = _items.value + item
            tokens.add(item.token)
            loads[id] = scope.launch {
                val staged = withContext(io) { stage(p, id) }
                staged(id, staged)
            }
        }
        return tokens
    }

    private class Staged(val file: String, val size: Long, val sha256: String)

    /** Reads and writes one pasted item into the staging directory (off the coordinator's thread). */
    private suspend fun stage(p: PastedItem, id: String): Result<Staged> = try {
        if ((p.sizeBytes ?: 0) > MAX_BYTES) throw ComposerUploadException(ComposerUploadError.TOO_LARGE)
        staging.mkdirs()
        val bytes = try { p.read() } catch (e: CancellationException) { throw e } catch (e: Exception) { throw ComposerUploadException(ComposerUploadError.UNREADABLE) }
        if (bytes.size > MAX_BYTES) throw ComposerUploadException(ComposerUploadError.TOO_LARGE)
        val ext = if (p.kind == UploadToken.Kind.IMAGE) ".png" else p.preferredExtension?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }?.let { ".$it" } ?: ""
        val name = "paste-$id$ext"
        try { AtomicFiles.write(File(staging, name), bytes) } catch (e: IOException) { throw ComposerUploadException(ComposerUploadError.UNREADABLE) }
        Result.success(Staged(name, bytes.size.toLong(), Sha256Hex.of(bytes)))
    } catch (e: ComposerUploadException) {
        Result.failure(e)
    }

    private fun staged(id: String, result: Result<Staged>) {
        loads.remove(id)
        val current = item(id)
        if (current == null) {
            result.getOrNull()?.let { File(staging, it.file).delete() }   // removed meanwhile
            return
        }
        result.fold(
            onSuccess = { s ->
                val next = current.copy(sizeBytes = s.size, sha256 = s.sha256, phase = ComposerUpload.Phase.Waiting, retryable = true)
                update(id) { next }
                saveRecord(next, s.file)
                pump()
            },
            onFailure = { e -> update(id) { it.copy(phase = ComposerUpload.Phase.Failed(e.message ?: ComposerUploadError.UNREADABLE.message)) } },
        )
    }

    // MARK: Uploading

    fun retry(id: String) {
        val current = item(id) ?: return
        if (current.phase !is ComposerUpload.Phase.Failed || !current.retryable) return
        update(id) { it.copy(phase = ComposerUpload.Phase.Waiting) }
        pump()
    }

    /** Cancels the upload, deletes its staged bytes and takes its placeholder out of the target's text. */
    fun remove(id: String) {
        val current = item(id) ?: return
        loads.remove(id)?.cancel()
        if (running?.first == id) { running?.second?.cancel(); running = null }
        _items.value = _items.value.filter { it.id != id }
        deliver(current.token, "", current.target, completing = null)
        discardFiles(id)
        pump()
    }

    private fun pump() {
        if (running != null) return
        val next = _items.value.firstOrNull { it.phase == ComposerUpload.Phase.Waiting } ?: return
        val sha = next.sha256
        val record = readRecord(next.id)
        if (sha == null || record == null) {
            update(next.id) { it.copy(phase = ComposerUpload.Phase.Failed("This pasted item is no longer on this device."), retryable = false) }
            pump()
            return
        }
        val id = next.id
        update(id) { it.copy(phase = ComposerUpload.Phase.Uploading) }
        val job = ComposerUploadJob(id, next.target, next.appNpub, File(staging, record.staged), next.mimeType, next.filename, sha, next.prepared,
            onPrepared = { fresh -> withContext(main) { prepared(id, fresh) } })
        val task = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val result = try {
                Result.success(upload(job))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            finished(id, result)
        }
        running = id to task
        task.start()
    }

    private fun prepared(id: String, fresh: PreparedObject) {
        if (item(id) == null) return
        update(id) { it.copy(prepared = fresh) }
        readRecord(id)?.let { writeRecord(it.copy(prepared = fresh)) }
    }

    private fun finished(id: String, result: Result<String>) {
        if (running?.first == id) running = null
        val current = item(id) ?: return pump()   // removed
        result.fold(
            onSuccess = { objectId ->
                readRecord(id)?.let { record ->
                    File(staging, record.staged).delete()
                    writeRecord(record.copy(objectId = objectId))
                }
                val done = current.copy(phase = ComposerUpload.Phase.Done(objectId), retryable = false)
                update(id) { done }
                done.markdown?.let { deliver(done.token, it, done.target, completing = id) }
            },
            onFailure = { e -> update(id) { it.copy(phase = ComposerUpload.Phase.Failed(message(e))) } },
        )
        pump()
    }

    // MARK: Results

    /**
     * Puts `replacement` where `token` is in the target's live composer, else in its stored draft. A completed upload is
     * forgotten once its Markdown is in the text; if neither holds the placeholder (it is still being saved), the result
     * is kept and applied when that composer shows the text again.
     */
    private fun deliver(token: String, replacement: String, target: ChatTarget, completing: String?) {
        val host = hosts[target.draftKey]?.get()
        if (host != null && host.uploadTarget == target) {
            val applied = host.replaceUploadToken(token, replacement)
            if (completing != null && applied) forget(completing)
            return
        }
        scope.launch {
            val applied = runCatching { store.writer.replaceDraftToken(target, token, replacement) }.getOrDefault(false)
            if (completing != null && applied) forget(completing)
        }
    }

    private fun forget(id: String) {
        _items.value = _items.value.filter { it.id != id }
        discardFiles(id)
    }

    /**
     * A composer starts showing `host.uploadTarget`: finished results are applied to its text, and placeholders from an
     * earlier launch become retryable (or removable) uploads again.
     */
    fun attach(host: UploadTokenHost) {
        val target = host.uploadTarget
        hosts[target.draftKey] = WeakReference(host)
        for (found in UploadToken.ids(host.uploadText)) {
            val id = found.id
            val existing = item(id)
            if (existing != null) {
                if (existing.target != target) continue
                val markdown = existing.markdown
                if (markdown != null && host.replaceUploadToken(existing.token, markdown)) forget(id)
                continue
            }
            val record = readRecord(id)
            if (record == null || record.target.chat() != target || record.kind != found.kind.raw) {
                _items.value = _items.value + ComposerUpload(id, target, "", found.kind, if (found.kind == UploadToken.Kind.IMAGE) "Image" else "File", "",
                    ComposerUpload.Phase.Failed("This pasted item is no longer on this device."), retryable = false)
                continue
            }
            var restored = ComposerUpload(id, target, record.appNpub, found.kind, record.filename, record.mimeType,
                ComposerUpload.Phase.Failed("Upload was interrupted."), retryable = false, sizeBytes = record.sizeBytes, sha256 = record.sha256,
                prepared = record.prepared)
            val objectId = record.objectId
            if (objectId != null) {
                restored = restored.copy(phase = ComposerUpload.Phase.Done(objectId))
                val markdown = restored.markdown
                if (markdown != null && host.replaceUploadToken(restored.token, markdown)) { discardFiles(id); continue }
            } else {
                val present = File(staging, record.staged).isFile
                restored = restored.copy(retryable = present)
                if (!present) restored = restored.copy(phase = ComposerUpload.Phase.Failed("This pasted item is no longer on this device."))
            }
            _items.value = _items.value + restored
        }
    }

    fun detach(host: UploadTokenHost) {
        val key = host.uploadTarget.draftKey
        if (hosts[key]?.get() === host) hosts.remove(key)
    }

    /** Stops every pending read and upload (process teardown in tests). */
    fun close() = scope.coroutineContext[Job]?.cancel()

    // MARK: Staging records

    @Serializable
    private data class Record(
        val id: String,
        val target: Target,
        val appNpub: String,
        val kind: String,
        val filename: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val staged: String,
        val prepared: PreparedObject? = null,
        val objectId: String? = null,
    ) {
        @Serializable
        data class Target(val connectionId: String, val workspaceId: String, val channelId: String, val threadId: String? = null) {
            fun chat() = ChatTarget(connectionId, workspaceId, channelId, threadId)
        }
    }

    private fun recordFile(id: String) = File(staging, "paste-$id.json")

    private fun saveRecord(item: ComposerUpload, staged: String) {
        val sha = item.sha256 ?: return
        val t = item.target
        writeRecord(Record(item.id, Record.Target(t.connectionId, t.workspaceId, t.channelId, t.threadId), item.appNpub, item.kind.raw, item.filename,
            item.mimeType, item.sizeBytes, sha, staged, item.prepared))
    }

    private fun writeRecord(record: Record) {
        runCatching { AtomicFiles.write(recordFile(record.id), json.encodeToString(Record.serializer(), record).toByteArray(Charsets.UTF_8)) }
    }

    private fun readRecord(id: String): Record? {
        if (!isUUID(id)) return null
        val record = runCatching { json.decodeFromString(Record.serializer(), recordFile(id).readText()) }.getOrNull() ?: return null
        // The staged name is used as a file inside the staging directory only.
        return record.takeIf { it.id == id && it.staged.matches(Regex("paste-[A-Za-z0-9.-]+")) }
    }

    private fun discardFiles(id: String) {
        if (!isUUID(id)) return
        readRecord(id)?.let { File(staging, it.staged).delete() }
        recordFile(id).delete()
    }
}
