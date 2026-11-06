package studio.otherstuff.ambulando.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** Model-owned commands; views observe snapshots and SQLite. No screen owns a network loop. */
class FamilyCoordinator(private val model: AppModel) {
    var choicesKey by mutableStateOf<Pair<String?, String>?>(null); private set
    var threads by mutableStateOf<List<ThreadRecord>?>(null); private set
    var choicesError by mutableStateOf<String?>(null); private set
    var status by mutableStateOf("Checking family chat…"); private set
    var accessDenied by mutableStateOf(false); private set
    private var listGeneration = 0
    private var verifyGeneration = 0

    fun list(channel: ChannelRecord) {
        val c = model.connection ?: return
        val w = model.workspace ?: return
        val boundary = model.activeBoundaryId
        val generation = ++listGeneration
        choicesKey = boundary to channel.id; threads = null; choicesError = null
        model.act {
            try {
                val result = model.towerAPI(c).threads(w.id, w.appNpub, channel.id)
                if (generation != listGeneration || boundary != model.activeBoundaryId) return@act
                threads = result.items.filter { !it.deleted && !it.archived && !it.accessRemoved }
                choicesError = if (result.complete) null else "Tower returned a partial list. If your conversation is missing, find it in Ambulando."
                model.store.writer.upsertThreads(w.id, result.items)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == listGeneration) choicesError = "Cannot load conversations. Check your connection and try again."
            }
        }
    }

    fun bind(channel: ChannelRecord, chosen: ThreadRecord) {
        val c = model.connection ?: return
        val w = model.workspace ?: return
        val boundary = model.activeBoundaryId
        val store = model.store
        model.act {
            val api = model.towerAPI(c)
            val currentChannel = api.channels(w.id, channel.scopeId, w.appNpub).firstOrNull { it.id == channel.id && it.workspaceId == w.id }
                ?: error("This channel is no longer available.")
            require(currentChannel.raw.string("record_state") !in setOf("archived", "deleted")) { "This channel is no longer available." }
            val fresh = api.thread(w.id, w.appNpub, chosen.id)
            val binding = FamilyBinding.of(c, w, currentChannel, fresh)
            require(binding.allows(fresh)) { "This conversation is no longer available." }
            require(model.activeBoundaryId == boundary && model.connection?.id == c.id && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked) { "Account changed. Choose again." }
            store.writer.upsertThreads(w.id, listOf(fresh))
            model.catalog.writer.setState(FamilyBinding.KEY, binding.encode())
            verify(binding)
        }
    }

    fun verify(binding: FamilyBinding) {
        val c = model.connection ?: return
        val w = model.workspace ?: return
        if (!binding.owns(c, w)) return
        val generation = ++verifyGeneration
        val store = model.store
        val sync = model.sync
        model.act {
            try {
                val api = model.towerAPI(c)
                val channel = api.channels(w.id, binding.scopeId, w.appNpub).firstOrNull { it.id == binding.channelId && it.workspaceId == w.id }
                val fresh = api.thread(w.id, w.appNpub, binding.threadId)
                if (generation != verifyGeneration || model.activeBoundaryId != binding.boundaryId) return@act
                val available = channel != null && !channel.accessRemoved && channel.raw.string("record_state") !in setOf("archived", "deleted") && binding.allows(fresh)
                store.writer.upsertThreads(w.id, listOf(fresh))
                accessDenied = !available
                status = if (available) "Connected" else "Chat unavailable · your draft is kept"
                if (available) sync.refreshThread(c, w, binding.channelId, binding.threadId, force = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation != verifyGeneration || model.activeBoundaryId != binding.boundaryId) return@act
                accessDenied = e is TowerError.Http && e.status in setOf(403, 404)
                status = if (accessDenied) "Access unavailable · your draft is kept" else "Offline · your draft is saved"
            }
        }
    }
}
