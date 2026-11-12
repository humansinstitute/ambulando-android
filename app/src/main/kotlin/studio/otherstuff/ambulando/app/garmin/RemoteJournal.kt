package studio.otherstuff.ambulando.app.garmin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.app.voice.VoiceDestination
import studio.otherstuff.ambulando.core.crypto.AtomicFiles
import java.io.File
import java.util.UUID

const val GARMIN_APP_ID = "4edbc3d41f924958b53a29734ba5a821"
@Serializable data class RemoteBinding(val device: Long, val destination: VoiceDestination, val association: Int? = null)
@Serializable data class RemoteSession(val session: String, val recording: String, val destination: VoiceDestination,
    val status: String = "waiting", val detail: String = "Confirm on phone", val revision: Int = 0)
@Serializable data class RemoteData(val binding: RemoteBinding? = null, val sessions: List<RemoteSession> = emptyList())

/** Main-thread actor journal. Tombstones never evict: full journal fails closed instead of replaying old Start. */
class RemoteJournal(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded = runCatching { if (file.exists()) json.decodeFromString<RemoteData>(file.readText()) else RemoteData() }
    val damaged = loaded.isFailure
    var data = loaded.getOrDefault(RemoteData())
        private set
    fun bind(binding: RemoteBinding?) {
        check(!damaged) { "Watch journal damaged. Remote disabled; preserve app data for support." }
        check(data.sessions.none { it.status in setOf("waiting", "recording", "sending") }) { "Finish or cancel the watch session first." }
        write(data.copy(binding = binding))
    }
    fun find(id: String) = data.sessions.firstOrNull { it.session == id }
    fun begin(id: String): RemoteSession {
        check(!damaged) { "Watch journal damaged. Remote disabled." }
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid session" }
        find(id)?.let { return it }
        check(data.sessions.size < 4096) { "Remote history full. Remote disabled to prevent replay." }
        check(data.sessions.none { it.status in setOf("waiting", "recording", "sending") }) { "Another watch session is active." }
        val binding = requireNotNull(data.binding) { "Bind a destination on the phone first." }
        val record = UUID.nameUUIDFromBytes("${binding.device}:$id".toByteArray()).toString()
        return RemoteSession(id, record, binding.destination).also { write(data.copy(sessions = data.sessions + it)) }
    }
    fun update(id: String, status: String, detail: String): RemoteSession {
        val old = requireNotNull(find(id))
        if (old.status == status && old.detail == detail.take(160)) return old
        val next = old.copy(status = status, detail = detail.take(160), revision = old.revision + 1)
        write(data.copy(sessions = data.sessions.map { if (it.session == id) next else it }))
        return next
    }
    private fun write(next: RemoteData) {
        AtomicFiles.write(file, json.encodeToString(RemoteData.serializer(), next).toByteArray())
        data = next
    }
}
