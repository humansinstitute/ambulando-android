package studio.otherstuff.ambulando.app

import kotlinx.coroutines.CancellationException
import studio.otherstuff.ambulando.core.goals.GoalMentions
import studio.otherstuff.ambulando.core.goals.Goal
import studio.otherstuff.ambulando.core.goals.GoalsSpaceState
import studio.otherstuff.ambulando.core.store.MentionCandidate
import studio.otherstuff.ambulando.core.store.MentionSearch
import studio.otherstuff.ambulando.core.store.mentionCandidates
import studio.otherstuff.ambulando.core.store.file
import studio.otherstuff.ambulando.core.store.refreshMentionFiles
import studio.otherstuff.ambulando.core.chat.MentionType

internal class MentionLookupFailure(val candidates: List<MentionCandidate>, message: String) : Exception(message)

private val fileDiscoveryTimes = java.util.WeakHashMap<studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntime, MutableMap<String, Long>>()

/** Personal discovery uses the same owner/grant checks and complete paginated reads as Goals itself. */
internal suspend fun AppModel.mentionCandidates(query: String): List<MentionCandidate> {
    val c = connection ?: return emptyList(); val w = workspace ?: return emptyList()
    val runtime = active ?: return emptyList(); val boundary = activeBoundaryId
    fun current() = active === runtime && activeBoundaryId == boundary && connection == c && workspace?.id == w.id && workspace?.accessRemoved == false && (identityState as? studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked)?.npub == c.signerNpub
    val goalsOnly = GoalMentions.isQuery(query)
    val only = MentionSearch.parse(query).first
    var fileFailure: String? = null
    if (!goalsOnly && (only == null || only == MentionType.FILE)) {
        try {
            val times = synchronized(fileDiscoveryTimes) { fileDiscoveryTimes.getOrPut(runtime) { mutableMapOf() } }
            val pending = channels.filterNot { it.accessRemoved }.map { it.id }.filter { channel ->
                synchronized(times) { System.nanoTime() - (times[channel] ?: Long.MIN_VALUE / 2) > 30_000_000_000L }
            }
            if (pending.isNotEmpty()) runtime.engine.perform("mention-files:${w.id}") {
                runtime.store.refreshMentionFiles(towerAPI(c), w.id, w.appNpub, pending, onRefreshed = { channel ->
                    synchronized(times) { times[channel] = System.nanoTime() }
                }, current = ::current)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fileFailure = "Files could not be refreshed: ${e.message ?: "Try again."}" }
    }
    check(current()) { "The workspace changed. Search again." }
    val local = if (goalsOnly) emptyList() else runtime.store.reader.mentionCandidates(w.id, query, viewerNpub = c.signerNpub)
    if (fileFailure != null) throw MentionLookupFailure(local, fileFailure)
    if (!goalsOnly && MentionSearch.parse(query).first != null) return local
    // A cached goal must not re-enter personal discovery through the generic File fallback.
    // Explicit file: searches retain their ordinary authorized-File behavior above.
    val regular = local.filterNot { candidate ->
        // Identify the format independently of availability/schema, so a deleted or newer goal cannot bypass this filter.
        candidate.mention.type == MentionType.FILE && runtime.store.reader.file(w.id, candidate.mention.id)?.contentType
            ?.substringBefore(';')?.trim()?.equals(Goal.MIME, ignoreCase = true) == true
    }
    if (query.isBlank()) return regular
    try {
        val controller = goalsController() ?: error("Unlock the workspace to search goals.")
        controller.refresh()
        check(current()) { "The workspace changed. Search again." }
        val state = controller.state.value
        state.error?.let { error(it) }
        (state.space as? GoalsSpaceState.Unsafe)?.let { error(it.reason) }
        if (state.unreadable.isNotEmpty()) error("Some personal goals could not be read. Open Goals to check them.")
        val goals = GoalMentions.candidates(state.goals, query)
        return if (goalsOnly) goals else (goals + regular).distinctBy { it.mention.type to it.mention.id }.take(MentionSearch.LIMIT)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        throw MentionLookupFailure(if (current()) regular else emptyList(), "Personal goals unavailable: ${e.message ?: "Try again when connected."}")
    }
}
