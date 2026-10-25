package studio.otherstuff.ambulando.core.surfaces

import studio.otherstuff.ambulando.core.transport.TowerError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Excalidraw board editing. The editor surface (the embedded Excalidraw page) owns no network or storage: it sends
// scene changes to a `BoardController`, which keeps the working draft on this device and only publishes on Save, as a
// new immutable File version against the version the draft started from. Tower routes (all existing):
//
// - `GET  /files/:f/versions` (history, current version)
// - `GET  /files/:f/versions/:v/object` (exact bytes, checked against the version's sha256)
// - `POST /storage/prepare`, object upload, `POST /storage/:o/complete` (private object for the new version)
// - `POST /files/:f/versions` with `base_version_id` (409 `stale_base_version` when someone saved first)
//
// The routes themselves are called by the app's transport adapter behind `BoardController.Transport`; this file holds
// the request bodies and response readers they use.

/**
 * An HTTP refusal from Tower with its status and error `code`. Seam: the transport layer's error type implements this
 * (or is mapped onto [SurfaceHttpException]) so editing code can classify refusals without depending on it.
 */
interface TowerHttpFailure {
    val status: Int
    val code: String?
}

class SurfaceHttpException(override val status: Int, override val code: String? = null) :
    Exception("Tower returned HTTP $status${code?.let { " ($it)" } ?: ""}."), TowerHttpFailure

data class BoardVersion(
    val id: String,
    val number: Int,
    val createdAt: String = "",
    val authorNpub: String = "",
    val summary: String = "",
    val sha256: String = "",
) {
    companion object {
        /** A version row from `GET /files/:f/versions` or `POST /files/:f/versions`; null when it is not usable. */
        fun parse(row: JsonObject): BoardVersion? {
            val id = JsonFields.lowerUuid(row["id"]) ?: return null
            val number = JsonFields.flexibleInt(row["version_number"])?.takeIf { it >= 1 } ?: return null
            val sha = (JsonFields.string(row["sha256_hex"]) ?: "").lowercase()
            return BoardVersion(id, number, JsonFields.string(row["created_at"]) ?: "", JsonFields.string(row["created_by_actor_npub"]) ?: "",
                JsonFields.prefix(JsonFields.string(row["summary"]) ?: "", BoardScene.MAX_SUMMARY), if (sha.length == 64) sha else "")
        }
    }
}

/** Request bodies and response readers for the board routes. */
object BoardRequests {
    /** `GET /files/:f/versions?limit=100`: versions newest first and the current version id. */
    fun parseVersions(o: JsonObject, fileId: String): Pair<String?, List<BoardVersion>> {
        val f = JsonFields.string(o["file_id"])
        if (f != null && JsonFields.lowerUuid(f) != fileId.lowercase()) throw TowerError.Malformed("board versions")
        val versions = (o["versions"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(BoardVersion::parse) }?.sortedByDescending { it.number } ?: emptyList()
        return JsonFields.lowerUuid(o["current_version_id"]) to versions
    }

    /** `POST /files/:f/versions` body for a stored object. A fresh `client_mutation_id` is used per publish. */
    fun createVersionBody(baseVersionId: String, storageObjectId: String, summary: String,
                          clientMutationId: String = UUID.randomUUID().toString().lowercase()): JsonObject {
        val base = JsonFields.lowerUuid(baseVersionId) ?: throw IllegalArgumentException("base version id")
        val body = linkedMapOf<String, JsonElement>("base_version_id" to JsonPrimitive(base), "storage_object_id" to JsonPrimitive(storageObjectId),
            "client_mutation_id" to JsonPrimitive(clientMutationId))
        val trimmed = JsonFields.prefix(summary.trim(), BoardScene.MAX_SUMMARY)
        if (trimmed.isNotEmpty()) body["summary"] = JsonPrimitive(trimmed)
        return JsonObject(body)
    }

    /** `PATCH /files/:f` body for a rename (metadata only: no new version). */
    fun renameBody(name: String): JsonObject = JsonObject(mapOf("display_name" to JsonPrimitive(name)))

    /** Tower must confirm the exact name for the right file. */
    fun confirmRename(o: JsonObject, fileId: String, name: String): Boolean {
        val file = o["file"] as? JsonObject ?: return false
        return JsonFields.lowerUuid(file["id"]) == fileId.lowercase() && JsonFields.string(file["display_name"]) == name
    }
}

sealed class BoardEditError(message: String) : Exception(message) {
    /** Someone saved a newer version first; the draft is kept. */
    class Stale(val current: BoardVersion? = null) : BoardEditError("Someone saved a newer version of this board. Your changes are kept as an unsaved draft.") {
        override fun equals(other: Any?) = other is Stale && other.current == current
        override fun hashCode() = current.hashCode()
    }
    /** Tower no longer lets this viewer read (or write) the board. */
    class Unavailable : BoardEditError("This board is no longer available to you.") {
        override fun equals(other: Any?) = other is Unavailable
        override fun hashCode() = 1
    }
    class Locked : BoardEditError("Unlock to save board changes.") {
        override fun equals(other: Any?) = other is Locked
        override fun hashCode() = 2
    }
    class Failed(val detail: String) : BoardEditError(detail) {
        override fun equals(other: Any?) = other is Failed && other.detail == detail
        override fun hashCode() = detail.hashCode()
    }

    companion object {
        fun from(error: Throwable): BoardEditError {
            if (error is BoardEditError) return error
            if (error is TowerHttpFailure) {
                val code = error.code ?: ""
                if (error.status == 409 && (code == "stale_base_version" || code.contains("stale"))) return Stale()
                if (error.status in setOf(401, 403, 404)) return Unavailable()
            }
            return Failed(error.message ?: error.toString())
        }
    }
}

/**
 * Unsaved board drafts on this device, one per viewer, workspace and board File. The directory is app-private; a
 * denial or removed identity deletes them.
 */
class BoardDraftStore(val directory: File) {
    class Draft(val baseVersionId: String, val baseVersionNumber: Int, val scene: JsonObject)

    private fun file(fileId: String) = File(directory, fileId.lowercase() + ".json")

    fun load(fileId: String): Draft? {
        val f = file(fileId)
        if (!f.isFile) return null
        val o = runCatching { JsonFields.parseObject(f.readBytes()) }.getOrNull() ?: return null
        val base = JsonFields.string(o["base_version_id"]) ?: return null
        val scene = o["scene"] as? JsonObject ?: return null
        return Draft(base, JsonFields.flexibleInt(o["base_version_number"]) ?: 0, scene)
    }

    internal fun save(fileId: String, baseVersionId: String, baseVersionNumber: Int, scene: JsonObject) {
        directory.mkdirs()
        val body = JsonObject(linkedMapOf("base_version_id" to JsonPrimitive(baseVersionId), "base_version_number" to JsonPrimitive(baseVersionNumber),
            "scene" to scene, "updated_at" to JsonPrimitive(towerTime.format(Instant.now()))))
        val target = file(fileId)
        val temp = File(directory, target.name + ".tmp")
        temp.writeBytes(JsonFields.encodeBytes(body, sortKeys = false))
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw java.io.IOException("draft could not be stored")
        }
    }

    fun delete(fileId: String) { file(fileId).delete() }

    private companion object {
        val towerTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

/** What a board screen shows about its board: load and save state, versions and whether the viewer may write. */
data class BoardState(
    val status: Status = Status.LOADING,
    val save: Save = Save.SAVED,
    val dirty: Boolean = false,
    val error: String = "",
    val notice: String = "",
    val canWrite: Boolean = false,
    val baseVersionId: String = "",
    val baseVersionNumber: Int = 0,
    val latestVersionId: String = "",
    val latestVersionNumber: Int = 0,
    val viewingVersionId: String = "",
    val viewingVersionNumber: Int = 0,
    val versions: List<BoardVersion> = emptyList(),
    val versionsLoading: Boolean = false,
    val conflicts: Int = 0,
    /** The board's name after a rename here (the canvas shows Tower's on its next snapshot). */
    val title: String = "",
    /** Increases with every change, so a screen receiving states across threads keeps the newest. */
    val revision: Int = 0,
) {
    enum class Status { LOADING, READY, DENIED, ERROR }
    enum class Save { SAVED, UNSAVED, SAVING, ERROR, STALE }

    data class SaveAction(val label: String, val enabled: Boolean)

    val newerAvailable: Boolean get() = dirty && latestVersionId.isNotEmpty() && baseVersionId.isNotEmpty() && latestVersionId != baseVersionId
    val readOnly: Boolean get() = !canWrite || viewingVersionId.isNotEmpty()

    /** The compact title-bar status of the web board window ("Unsaved · v7"). */
    val headerStatus: String
        get() {
            when (status) {
                Status.LOADING -> return ""
                Status.DENIED -> return "No access"
                Status.ERROR -> return "Error"
                Status.READY -> Unit
            }
            val base = if (baseVersionNumber > 0) "v$baseVersionNumber" else ""
            if (viewingVersionId.isNotEmpty()) return "Viewing v$viewingVersionNumber · read-only"
            if (save == Save.SAVING) return "Saving…"
            if (save == Save.ERROR) return "Save failed"
            if (save == Save.STALE || newerAvailable) return "Unsaved · newer v$latestVersionNumber saved"
            if (dirty) return if (base.isEmpty()) "Unsaved changes" else "Unsaved · $base"
            if (!canWrite) return if (base.isEmpty()) "Read-only" else "Read-only · $base"
            return base
        }

    /** Save as the editor floats it (hidden for read-only views). */
    val saveAction: SaveAction?
        get() {
            if (status != Status.READY || viewingVersionId.isNotEmpty() || !canWrite) return null
            if (save == Save.SAVING) return SaveAction("Saving…", false)
            return if (dirty) SaveAction("Save", true) else SaveAction("Saved", false)
        }
}

/**
 * The only owner of one board File's draft and saves. Every editor surface showing the board (inline on a canvas and
 * full screen) attaches as a session and shares the draft. All state is confined to one serial dispatcher, so calls
 * interleave only at suspension points (like an actor); scene JSON is parsed, compared and serialised there, never on
 * the UI thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardController(
    fileId: String,
    private var title: String,
    private val author: String,
    private val transport: Transport,
    private val drafts: BoardDraftStore,
    private var onState: (BoardState) -> Unit,
    private val flushTimeout: Duration = 3.seconds,
) {
    /** Tower access for one board, bound to the workspace and connection by the app. */
    interface Transport {
        suspend fun versions(): Pair<String?, List<BoardVersion>>
        suspend fun read(versionId: String, sha256: String?): ByteArray
        suspend fun publish(baseVersionId: String, bytes: ByteArray, summary: String): BoardVersion
        /** Whether a signer is available now (a locked identity can read but not save). */
        suspend fun canSign(): Boolean
        /** Renames the board File. */
        suspend fun rename(name: String): String = throw BoardEditError.Failed("Renaming is not available.")
    }

    val fileId: String = fileId.lowercase()

    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)

    private data class Base(val versionId: String, val number: Int, val scene: JsonObject, val fingerprint: String)
    private data class Viewing(val versionId: String, val number: Int, val scene: JsonObject)
    private class Session(val send: (String) -> Unit) {
        var lastFingerprint = ""
        val flushes = HashMap<String, CompletableDeferred<Boolean>>()
    }

    private var state = BoardState()
    private var meta: BoardReference? = null
    private var metaCanWrite = false
    private var base: Base? = null
    private var working: JsonObject? = null
    private var viewing: Viewing? = null
    private var loadKey = ""
    private val sessions = LinkedHashMap<String, Session>()
    private val cache = HashMap<String, JsonObject>()
    private var loading: Deferred<Unit>? = null
    private var saving = false

    private suspend fun <T> confined(block: suspend () -> T): T = withContext(confined) { block() }

    suspend fun setOnState(callback: (BoardState) -> Unit) = confined { onState = callback; callback(state) }
    suspend fun current(): BoardState = confined { state }

    private fun emit(change: (BoardState) -> BoardState = { it }) {
        state = change(state).let { it.copy(revision = state.revision + 1) }
        onState(state)
    }

    // MARK: Snapshot metadata

    /** Called with each authorized canvas projection of this board (its current version and `can_write`). */
    suspend fun sync(reference: BoardReference, title: String) = confined {
        val previous = meta
        meta = reference
        this.title = title
        metaCanWrite = reference.canWrite
        val canSign = transport.canSign()
        emit { s ->
            var n = s.copy(canWrite = reference.canWrite && canSign)
            if (n.latestVersionId.isEmpty() || previous?.versionId != reference.versionId) {
                n = n.copy(latestVersionId = reference.versionId, latestVersionNumber = reference.versionNumber)
            }
            n
        }
        if (base == null) {
            val job = loading ?: scope.async { initialLoad() }.also { loading = it }
            job.await()
            return@confined
        }
        if (previous?.canWrite != reference.canWrite) broadcastLoad()
        if (previous?.versionId != reference.versionId && !state.dirty && reference.versionId != base?.versionId) {
            loadLatestInternal(Head(reference.versionId, reference.versionNumber, reference.sha256))
        }
    }

    /** Lock state changed: a locked identity keeps the board readable but not editable. */
    suspend fun refreshSigner() = confined {
        val writable = metaCanWrite && transport.canSign()
        if (writable == state.canWrite) return@confined
        emit { it.copy(canWrite = writable) }
        broadcastLoad()
    }

    private suspend fun readVersion(versionId: String, sha256: String?): JsonObject {
        cache[versionId]?.let { return it }
        val bytes = transport.read(versionId, sha256)
        val scene = BoardScene.parse(bytes)
        if (cache.size > 8) cache.clear()
        cache[versionId] = scene
        return scene
    }

    private suspend fun initialLoad() {
        val meta = meta ?: run { loading = null; return }
        try {
            val draft = drafts.load(fileId)
            // A stored canvas snapshot can lag behind a save (this device's or another's): without a draft, open
            // Tower's current version. The projection is used when the version list cannot be read.
            var head = Head(meta.versionId, meta.versionNumber, meta.sha256)
            if (draft == null) {
                val listed = runCatching { transport.versions() }.getOrNull()
                val id = listed?.first
                val row = listed?.second?.firstOrNull { it.id == id }
                if (listed != null && id != null && row != null) {
                    head = Head(id, row.number, row.sha256.ifEmpty { if (id == meta.versionId) meta.sha256 else "" })
                    emit { it.copy(versions = listed.second, latestVersionId = id, latestVersionNumber = row.number) }
                }
            }
            val baseId = draft?.baseVersionId ?: head.id
            val baseNumber = draft?.baseVersionNumber ?: head.number
            val scene = readVersion(baseId, if (baseId == head.id && head.sha256.isNotEmpty()) head.sha256 else null)
            val fingerprint = BoardScene.fingerprint(scene)
            base = Base(baseId, baseNumber, scene, fingerprint)
            var dirty = false
            val restored = draft?.let { runCatching { BoardScene.normalize(it.scene) }.getOrNull() }
            if (restored != null && BoardScene.fingerprint(restored) != fingerprint) {
                working = restored; dirty = true
            } else {
                working = scene
                if (draft != null) drafts.delete(fileId)
            }
            loadKey = "$baseId:${UUID.randomUUID()}"
            emit {
                it.copy(status = BoardState.Status.READY, dirty = dirty, save = if (dirty) BoardState.Save.UNSAVED else BoardState.Save.SAVED, error = "",
                    notice = if (dirty) "Unsaved draft restored from this $DEVICE_NAME." else "", baseVersionId = baseId, baseVersionNumber = baseNumber)
            }
            broadcastLoad()
        } catch (e: CancellationException) {
            loading = null
            throw e
        } catch (e: Exception) {
            fail(e)
        }
        loading = null
    }

    private fun fail(error: Throwable) {
        val classified = BoardEditError.from(error)
        if (classified is BoardEditError.Unavailable) {
            drafts.delete(fileId)
            cache.clear()
            base = null; working = null; viewing = null
            emit { it.copy(status = BoardState.Status.DENIED, dirty = false, save = BoardState.Save.SAVED, error = "Ask your workspace owner for access.") }
            for (session in sessions.values) session.send(message(mapOf("type" to JsonPrimitive("clear"))))
            return
        }
        emit { it.copy(status = if (base == null) BoardState.Status.ERROR else BoardState.Status.READY, error = classified.message ?: "") }
    }

    // MARK: Editor sessions

    private data class EditorContent(val scene: JsonObject, val readOnly: Boolean, val key: String)

    private fun editorContent(): EditorContent? {
        viewing?.let { return EditorContent(it.scene, true, "history:${it.versionId}") }
        val working = working ?: return null
        return EditorContent(working, !state.canWrite, loadKey)
    }

    private fun broadcastLoad() { for (id in sessions.keys.toList()) sendLoad(id) }

    private fun sendLoad(id: String) {
        val content = editorContent() ?: return
        val session = sessions[id] ?: return
        session.lastFingerprint = BoardScene.fingerprint(content.scene)
        session.send(message(mapOf("type" to JsonPrimitive("load"), "key" to JsonPrimitive(content.key),
            "readOnly" to JsonPrimitive(content.readOnly), "scene" to content.scene)))
    }

    /** An editor surface joins; it receives the current draft once it reports `ready`. */
    suspend fun attach(sessionId: String, send: (String) -> Unit) = confined { sessions[sessionId] = Session(send) }

    suspend fun detach(sessionId: String) = confined {
        val session = sessions.remove(sessionId) ?: return@confined
        for (waiter in session.flushes.values) waiter.complete(false)
    }

    suspend fun attached(): Boolean = confined { sessions.isNotEmpty() }

    /** One message from an editor page (JSON text from the bridge). */
    suspend fun receive(sessionId: String, json: String) = confined {
        val message = JsonFields.parseObject(json) ?: return@confined
        when (JsonFields.string(message["type"])) {
            "ready" -> if (state.status == BoardState.Status.READY) sendLoad(sessionId)
            "change" -> message["scene"]?.let { editorChanged(sessionId, it) }
            "comment-op" -> (message["operation"] as? JsonObject)?.let { commentOperation(sessionId, it) }
            "flushed" -> JsonFields.string(message["token"])?.let { token -> sessions[sessionId]?.flushes?.remove(token)?.complete(true) }
            "error" -> emit { it.copy(error = "The board editor reported a problem: ${JsonFields.string(message["message"]) ?: "unknown"}") }
            else -> Unit   // "action" (Save) is handled by the screen, which re-checks the state first
        }
    }

    private fun editorChanged(sessionId: String, raw: JsonElement) {
        if (sessions[sessionId] == null || viewing != null || !state.canWrite) return
        val base = base ?: return
        val current = working ?: return
        var scene = try {
            BoardScene.normalize(raw)
        } catch (e: Exception) {
            emit { it.copy(error = "The editor sent a board this app could not read; nothing was changed.") }
            return
        }
        // Images are sent once; keep those already in the draft. Discussion is owned here, not by the editor.
        val files = (current["files"] as? JsonObject ?: JsonObject(emptyMap())) + (scene["files"] as? JsonObject ?: JsonObject(emptyMap()))
        scene = JsonObject(scene + ("files" to JsonObject(files)))
        scene = runCatching { BoardScene.withComments(scene, BoardScene.followCommentAnchors(BoardScene.comments(current), BoardScene.elements(scene))) }
            .getOrDefault(scene)
        val fingerprint = BoardScene.fingerprint(scene)
        sessions[sessionId]?.lastFingerprint = fingerprint
        if (fingerprint == BoardScene.fingerprint(current)) return
        working = scene
        for ((id, other) in sessions) {
            if (id == sessionId || other.lastFingerprint == fingerprint) continue
            other.lastFingerprint = fingerprint
            other.send(message(mapOf("type" to JsonPrimitive("patch"), "scene" to scene)))
        }
        val dirty = fingerprint != base.fingerprint
        emit { it.copy(dirty = dirty, save = if (dirty) BoardState.Save.UNSAVED else BoardState.Save.SAVED, error = "", notice = "") }
        persistDraft()
    }

    private fun commentOperation(sessionId: String, operation: JsonObject) {
        if (sessions[sessionId] == null || viewing != null || base == null || !state.canWrite) return
        val current = working ?: return
        try {
            val next = BoardScene.applyCommentOperation(current, operation, author)
            working = next
            persistDraft()
            emit { it.copy(dirty = true, save = BoardState.Save.UNSAVED, error = "") }
            val comments = JsonArray(BoardScene.comments(next))
            for (session in sessions.values) session.send(message(mapOf("type" to JsonPrimitive("comments"), "comments" to comments)))
        } catch (e: Exception) {
            emit { it.copy(error = e.message ?: "") }
        }
    }

    private fun persistDraft() {
        val base = base ?: return
        val working = working ?: return
        if (BoardScene.fingerprint(working) == base.fingerprint) { drafts.delete(fileId); return }
        try {
            drafts.save(fileId, base.versionId, base.number, working)
        } catch (e: Exception) {
            emit { it.copy(error = "Your draft could not be stored on this $DEVICE_NAME: ${e.message}") }
        }
    }

    /** Asks every editor to deliver its debounced change and waits for it. */
    private suspend fun flushEditors(): Boolean {
        var ok = true
        for (id in sessions.keys.toList()) {
            val session = sessions[id] ?: continue
            val token = UUID.randomUUID().toString()
            val waiter = CompletableDeferred<Boolean>()
            session.flushes[token] = waiter
            session.send(message(mapOf("type" to JsonPrimitive("flush"), "token" to JsonPrimitive(token))))
            val timer = scope.launch {
                delay(flushTimeout)
                sessions[id]?.flushes?.remove(token)
                waiter.complete(false)
            }
            val result = waiter.await()
            timer.cancel()
            if (!result) ok = false
        }
        return ok
    }

    // MARK: Save

    /** Publishes the draft as a new version against the version it started from. A stale base keeps the draft. */
    suspend fun save(summary: String = ""): Boolean = confined {
        if (saving || base == null || state.save == BoardState.Save.SAVING) return@confined false
        saving = true
        try {
            saveLocked(summary)
        } finally {
            saving = false
        }
    }

    private suspend fun saveLocked(summary: String): Boolean {
        if (!flushEditors()) {
            emit { it.copy(save = BoardState.Save.ERROR, error = "An open board editor did not confirm its latest changes. Your draft is kept; try Save again.") }
            return false
        }
        if (viewing != null) { emit { it.copy(error = "Return to the latest version before saving.") }; return false }
        if (!transport.canSign()) { emit { it.copy(error = BoardEditError.Locked().message ?: "") }; return false }
        if (!state.canWrite) { emit { it.copy(error = "You can view this board but not save it.") }; return false }
        val base = base
        val scene = working
        if (!state.dirty || base == null || scene == null) { emit { it.copy(notice = "No unsaved changes.") }; return false }
        val fingerprint = BoardScene.fingerprint(scene)
        emit { it.copy(save = BoardState.Save.SAVING, error = "", notice = "") }
        try {
            val bytes = BoardScene.serialize(scene, summary, base.versionId)
            val version = transport.publish(base.versionId, bytes, summary)
            cache[version.id] = scene
            this.base = Base(version.id, version.number, scene, fingerprint)
            val stillDirty = working?.let(BoardScene::fingerprint) != fingerprint
            if (stillDirty) persistDraft() else drafts.delete(fileId)
            emit {
                it.copy(dirty = stillDirty, save = if (stillDirty) BoardState.Save.UNSAVED else BoardState.Save.SAVED, conflicts = 0,
                    baseVersionId = version.id, baseVersionNumber = version.number, latestVersionId = version.id, latestVersionNumber = version.number,
                    notice = "Saved version ${version.number}.", versions = emptyList())
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (BoardEditError.from(e)) {
                is BoardEditError.Stale -> {
                    val head = runCatching { transport.versions() }.getOrNull()
                    val latest = head?.let { h -> h.second.firstOrNull { it.id == h.first } }
                    emit { s ->
                        val n = s.copy(save = BoardState.Save.STALE, error = BoardEditError.Stale().message ?: "")
                        if (latest != null) n.copy(latestVersionId = latest.id, latestVersionNumber = latest.number) else n
                    }
                }
                is BoardEditError.Unavailable -> fail(e)
                else -> emit { it.copy(save = BoardState.Save.ERROR, error = "The board could not be saved. Your draft is kept. ${e.message}") }
            }
            return false
        }
    }

    // MARK: Versions

    private data class Head(val id: String, val number: Int, val sha256: String)

    private suspend fun fetchHead(): Head {
        val head = transport.versions()
        val id = head.first
        val row = head.second.firstOrNull { it.id == id }
        if (id == null || row == null) throw BoardEditError.Failed("This board has no current version.")
        emit { it.copy(versions = head.second, latestVersionId = id, latestVersionNumber = row.number) }
        return Head(id, row.number, row.sha256)
    }

    /** Rebases the draft onto the newest saved version; the result stays unsaved for review. */
    suspend fun mergeLatest() = confined {
        val base = base ?: return@confined
        val draft = working ?: return@confined
        try {
            val head = fetchHead()
            val latest = readVersion(head.id, head.sha256.ifEmpty { null })
            val merged = BoardScene.rebase(base.scene, draft, latest)
            val latestFingerprint = BoardScene.fingerprint(latest)
            this.base = Base(head.id, head.number, latest, latestFingerprint)
            working = merged.scene
            val dirty = BoardScene.fingerprint(merged.scene) != latestFingerprint
            loadKey = "${head.id}:${UUID.randomUUID()}"
            persistDraft()
            val conflicts = merged.conflicts
            emit {
                it.copy(dirty = dirty, save = if (dirty) BoardState.Save.UNSAVED else BoardState.Save.SAVED, error = "", conflicts = conflicts,
                    baseVersionId = head.id, baseVersionNumber = head.number,
                    notice = if (conflicts > 0) "Merged with version ${head.number}; $conflicts change${if (conflicts == 1) "" else "s"} kept your edit. Review, then save."
                    else "Merged with version ${head.number}. Review, then save.")
            }
            broadcastLoad()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Loads the newest saved version, discarding the draft (the screen confirms a dirty discard first). */
    suspend fun loadLatest(): Boolean = confined { loadLatestInternal(null) }

    private suspend fun loadLatestInternal(given: Head?): Boolean {
        return try {
            val head = given ?: fetchHead()
            val latest = readVersion(head.id, head.sha256.ifEmpty { null })
            base = Base(head.id, head.number, latest, BoardScene.fingerprint(latest))
            working = latest; viewing = null
            drafts.delete(fileId)
            loadKey = "${head.id}:${UUID.randomUUID()}"
            emit {
                it.copy(dirty = false, save = BoardState.Save.SAVED, error = "", notice = "", conflicts = 0, baseVersionId = head.id,
                    baseVersionNumber = head.number, latestVersionId = head.id, latestVersionNumber = head.number,
                    viewingVersionId = "", viewingVersionNumber = 0)
            }
            broadcastLoad()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e); false
        }
    }

    suspend fun loadVersions() = confined {
        if (state.versionsLoading) return@confined
        emit { it.copy(versionsLoading = true) }
        try {
            val head = transport.versions()
            val latest = head.second.firstOrNull { it.id == head.first }
            emit { s ->
                val n = s.copy(versions = head.second, versionsLoading = false)
                if (latest != null) n.copy(latestVersionId = latest.id, latestVersionNumber = latest.number) else n
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit { it.copy(versionsLoading = false) }
            fail(e)
        }
    }

    /** Read-only history browsing; the draft is untouched. */
    suspend fun viewVersion(version: BoardVersion) = confined {
        if (version.id == base?.versionId && !state.dirty) { returnToLatestInternal(); return@confined }
        try {
            val scene = readVersion(version.id, version.sha256.ifEmpty { null })
            viewing = Viewing(version.id, version.number, scene)
            emit { it.copy(viewingVersionId = version.id, viewingVersionNumber = version.number, error = "") }
            broadcastLoad()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Leaves history: back to the draft, or (without one) to Tower's newest version. */
    suspend fun returnToLatest() = confined { returnToLatestInternal() }

    private suspend fun returnToLatestInternal() {
        viewing = null
        emit { it.copy(viewingVersionId = "", viewingVersionNumber = 0) }
        if (!state.dirty && state.latestVersionId.isNotEmpty() && state.latestVersionId != base?.versionId) { loadLatestInternal(null); return }
        broadcastLoad()
    }

    /** Starts a draft from the version being viewed, on top of the current base. */
    suspend fun restoreViewedVersion() = confined {
        val viewing = viewing ?: return@confined
        val base = base ?: return@confined
        if (!state.canWrite) return@confined
        working = BoardScene.restore(viewing.scene, base.scene)
        this.viewing = null
        loadKey = "${base.versionId}:${UUID.randomUUID()}"
        persistDraft()
        emit {
            it.copy(dirty = true, save = BoardState.Save.UNSAVED, viewingVersionId = "", viewingVersionNumber = 0,
                notice = "Older version restored as an unsaved draft. Save to make it the latest version.")
        }
        broadcastLoad()
    }

    suspend fun clearMessages() = confined { emit { it.copy(error = "", notice = "") } }

    /** Renames the board: 1–200 characters, only with write access and an unlocked signer. */
    suspend fun rename(name: String): Boolean = confined {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || JsonFields.length(trimmed) > BoardScene.MAX_TITLE) {
            emit { it.copy(error = "Enter a board name of 1–200 characters.") }
            return@confined false
        }
        if (!state.canWrite || !transport.canSign()) { emit { it.copy(error = "You cannot rename this board.") }; return@confined false }
        try {
            title = transport.rename(trimmed)
            emit { it.copy(title = trimmed, notice = "Renamed to “$trimmed”.", error = "") }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BoardEditError.from(e) is BoardEditError.Unavailable) emit { it.copy(error = "You cannot rename this board.") }
            else emit { it.copy(error = "The board could not be renamed. ${e.message}") }
            false
        }
    }

    /**
     * Lock without background reading: the scene leaves memory and every editor is cleared. The draft stays on disk
     * and is restored by the next `sync` after unlocking.
     */
    suspend fun suspendBoard() = confined {
        loading?.cancel(); loading = null
        base = null; working = null; viewing = null
        cache.clear()
        emit { it.copy(status = BoardState.Status.LOADING, canWrite = false, versions = emptyList()) }
        for (session in sessions.values) session.send(message(mapOf("type" to JsonPrimitive("clear"))))
    }

    /** Ends background work (flush timers, an initial load) when the board is no longer shown anywhere. */
    fun close() = scope.cancel()

    companion object {
        internal const val DEVICE_NAME = "device"

        /** One editor message as JSON text. */
        fun message(fields: Map<String, JsonElement>): String = JsonFields.encode(JsonObject(fields), sortKeys = false)
    }
}
