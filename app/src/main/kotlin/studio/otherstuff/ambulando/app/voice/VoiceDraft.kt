package studio.otherstuff.ambulando.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import java.io.File
import java.util.UUID

/** Immutable authority captured before asking Android for permission. No navigation state is used by Send. */
@Serializable
data class VoiceDestination(
    val connectionId: String, val origin: String, val towerNpub: String, val signerNpub: String,
    val workspaceId: String, val appNpub: String, val channelId: String, val threadId: String?, val label: String,
) {
    val target get() = ChatTarget(connectionId, workspaceId, channelId, threadId)
    fun matches(c: ConnectionRecord, w: WorkspaceRecord) = c.id == connectionId && c.origin == origin &&
        c.towerNpub == towerNpub && c.signerNpub == signerNpub && w.id == workspaceId && w.appNpub == appNpub && !w.accessRemoved
    companion object {
        fun capture(c: ConnectionRecord, w: WorkspaceRecord, target: ChatTarget, label: String): VoiceDestination {
            require(c.id == target.connectionId && w.id == target.workspaceId && !w.accessRemoved)
            return VoiceDestination(c.id, c.origin, requireNotNull(c.towerNpub) { "Verify the Tower before recording." }, c.signerNpub, w.id, w.appNpub, target.channelId, target.threadId, label)
        }
    }
}

@Serializable
enum class VoicePhase { STARTING, RECORDING, REVIEW, INTERRUPTED, SENDING }
@Serializable
data class VoiceDraft(
    val id: String = UUID.randomUUID().toString(), val destination: VoiceDestination,
    val phase: VoicePhase = VoicePhase.STARTING, val startedAt: Long = System.currentTimeMillis(),
    val elapsedMillis: Long = 0, val startedElapsed: Long = 0, val error: String? = null,
    val headsetOnly: Boolean = false,
    val objectId: String? = null, val uploadURL: String? = null, val fileId: String? = null,
) {
    val filename get() = "Voice-$id.m4a"
}

/** App-private, flushed before capture/upload. Stopped audio is never kept in an evictable cache. */
class VoiceDraftStore(private val directory: File) {
    private val journal = File(directory, "draft.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read(): VoiceDraft? = if (!journal.isFile) null else runCatching {
        json.decodeFromString<VoiceDraft>(journal.readText()).also { require(UUID.fromString(it.id).toString() == it.id) }.let {
            when (it.phase) {
                VoicePhase.STARTING, VoicePhase.RECORDING -> it.copy(phase = VoicePhase.INTERRUPTED, error = "Recording was interrupted. Review any recovered audio before sending.")
                VoicePhase.SENDING -> it.copy(phase = VoicePhase.REVIEW, error = "Send was interrupted. Retry uses the same recording and message request.")
                else -> it
            }
        }
    }.getOrNull() // A corrupt journal confers no destination authority. Never guess from the current screen.
    fun audio(draft: VoiceDraft) = File(directory, draft.filename)
    fun save(draft: VoiceDraft) {
        require(UUID.fromString(draft.id).toString() == draft.id)
        AtomicFiles.write(journal, json.encodeToString(VoiceDraft.serializer(), draft).toByteArray())
        mutable.value = draft
    }
    fun begin(destination: VoiceDestination, id: String = UUID.randomUUID().toString(), headsetOnly: Boolean = false): VoiceDraft {
        check(state.value == null) { "Finish or cancel the existing recording first." }
        return VoiceDraft(id = id, destination = destination, headsetOnly = headsetOnly).also(::save)
    }
    fun clear() {
        // Remove authority first; leftover bytes after a crash can never be sent to a guessed destination.
        check(!journal.exists() || journal.delete()) { "Could not remove recording journal." }
        state.value?.let { audio(it).delete() }
        mutable.value = null
    }
}
