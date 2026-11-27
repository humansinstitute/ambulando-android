package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.otherstuff.ambulando.core.transport.*

/**
 * Screen state for the personal Goals list and the goal being viewed. One controller serves one workspace boundary;
 * every network step re-checks [current] so a locked, switched or access-removed workspace can neither load nor save.
 */
class GoalsController(
    private val api: TowerAPI,
    private val workspace: String,
    private val app: String,
    private val viewer: String,
    private val local: GoalsLocalContent? = null,
    private val current: () -> Boolean,
) {
    constructor(api: TowerAPI, workspace: String, app: String, viewer: String, current: () -> Boolean) : this(api, workspace, app, viewer, null, current)

    /** A goal file that exists but cannot be shown (newer app version, damaged content). It is never rewritten. */
    data class Unreadable(val fileId: String, val name: String, val reason: String)

    data class State(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val goals: List<GoalDocument> = emptyList(),
        /** Where goals live, or why they cannot be used yet. Null until the first load finishes. */
        val space: GoalsSpaceState? = null,
        val unreadable: List<Unreadable> = emptyList(),
        /** Plain-language reason the last load failed; the previous goals stay visible. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val gate = Mutex()
    @Volatile private var ownerOnly = false

    fun goal(fileId: String): GoalDocument? = _state.value.goals.firstOrNull { it.id == fileId }

    /** Local hydration never queues behind an in-flight online refresh. */
    suspend fun hydrate() {
        if (local != null && current()) {
            val expected=_state.value
            val saved=local.load()
            val cached = saved ?: local.visible(expected)
            if (current() && _state.compareAndSet(expected,cached.copy(loading=expected.loading))) {
                (saved?.space as? GoalsSpaceState.Ready)?.let { ownerOnly=it.ownerOnly }
            }
        }
    }
    suspend fun refresh() { hydrate(); refreshOnline() }
    private suspend fun refreshOnline() = gate.withLock {
        _state.update { it.copy(loading = true) }
        val localToken=local?.token()
        try {
            check(current()) { "Unlock an accessible workspace to open goals." }
            val space = api.goalsSpace(workspace, app, viewer, ownerOnly)
            val files = (space as? GoalsSpaceState.Ready)?.let { api.goalFiles(workspace, app, it.channel.id) }.orEmpty()
            val goals = ArrayList<GoalDocument>(); val unreadable = ArrayList<Unreadable>()
            for (file in files) {
                check(current())
                try { goals += api.readGoal(workspace, app, file) }
                catch(e: TowerError.Http) { if(e.status in setOf(403,404)) local?.remove(file.id) else throw e }
                catch (e: GoalError) { unreadable += Unreadable(file.id, file.displayName, e.message ?: "Could not be read.") }
            }
            check(current())
            if(local!=null && local.token()!=localToken) {
                hydrate(); _state.update { it.copy(loading=false) }; return@withLock
            }
            _state.value = State(loaded = true, goals = goals.sortedWith(order), space = space, unreadable = unreadable)
            local?.save(_state.value)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!current()) _state.value = State(error = userMessage(e))
            else if(e is TowerError.Http && e.status in setOf(403, 404)) {
                local?.clear(); _state.value = State(error = userMessage(e))
            } else _state.update { it.copy(loading = false, error = userMessage(e)) }
        }
    }

    /** Creates the private Goals channel (null [scopeId]: the disclosed owner-only fallback) and loads it. */
    suspend fun setUp(scopeId: String?, agentActorIds: List<String>) {
        gate.withLock {
            check(current()) { "Unlock an accessible workspace to set up goals." }
            check(_state.value.space is GoalsSpaceState.NeedsSetup) { "Goals are already set up." }
            api.createGoalsSpace(workspace, app, viewer, scopeId, agentActorIds)
            if (scopeId == null) ownerOnly = true
        }
        refresh()
    }

    /** Lets one agent contribute to the owner's goals, or stops it. Only the owner of the channel can change this. */
    suspend fun setAgentAccess(agent: studio.otherstuff.ambulando.core.transport.MemberRecord, enabled: Boolean) {
        gate.withLock {
            check(current()) { "Unlock an accessible workspace to change access." }
            val ready = _state.value.space as? GoalsSpaceState.Ready ?: error("Set up goals first.")
            check(!ready.ownerOnly) { "Agents cannot use the owner-only space." }
            api.setGoalsAgentAccess(workspace, app, ready.channel.id, agent, enabled)
        }
        refresh()
    }

    suspend fun create(goal: Goal): GoalDocument = gate.withLock {
        check(current()) { "Unlock an accessible workspace to save goals." }
        val ready = _state.value.space as? GoalsSpaceState.Ready ?: throw GoalError.Invalid("Set up goals before adding one.")
        val created = api.createGoal(workspace, app, ready.channel.id, goal)
        publish(created); created
    }

    /** Throws [GoalError.Conflict] / [GoalSaveFailed] with a readable message; the saved goal is unchanged in both cases. */
    suspend fun edit(fileId: String, vararg edits: GoalEdit): GoalDocument = gate.withLock {
        check(current()) { "Unlock an accessible workspace to save goals." }
        val saved = api.editGoal(workspace, app, fileId, edits.toList())
        check(current())
        publish(saved); saved
    }

    /**
     * Moves goal [fileId] to [toIndex] among [items] (one status group, in focus order) by saving [GoalFocus.planMove]'s
     * writes one at a time, top first. Each write is that goal's own expectation-checked edit against its current version.
     * The first refusal (reordered elsewhere, removed, denied) stops the move: later writes are not attempted and the plan
     * is never retried as a whole; the goals are reloaded and [GoalMoveIncomplete] says how many changes were saved.
     * Every partial result is still a valid order. Returns the number of writes saved.
     */
    suspend fun move(items: List<GoalDocument>, fileId: String, toIndex: Int): Int {
        val plan = GoalFocus.planMove(items, fileId, toIndex)
        plan.forEachIndexed { saved, write ->
            try { edit(write.fileId, write.edit) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                runCatching { refresh() }
                throw GoalMoveIncomplete(saved, plan.size, (if (e is GoalError || e is GoalSaveFailed) e.message else null) ?: userMessage(e))
            }
        }
        return plan.size
    }

    /** Re-reads one goal from Tower (e.g. after a conflict or an agent edit). */
    suspend fun reload(fileId: String): GoalDocument = gate.withLock {
        check(current())
        val ready=(_state.value.space as? GoalsSpaceState.Ready) ?: (api.goalsSpace(workspace,app,viewer,ownerOnly) as? GoalsSpaceState.Ready)
            ?: throw GoalError.Invalid("This goal is not in your Goals space.")
        api.readGoal(workspace, app, fileId).also {
            if(it.file.channelId!=ready.channel.id)throw GoalError.Invalid("This goal is not in your Goals space.")
            publish(it)
        }
    }

    private suspend fun publish(doc: GoalDocument) {
        check(current())
        _state.update { s -> s.copy(goals = (s.goals.filterNot { it.id == doc.id } + doc).sortedWith(order), loading = false, loaded = true, error = null) }
        local?.save(_state.value)
    }

    /** The shared focus order (status, chosen rank, then date and name), the same on every client. */
    private val order = GoalFocus.comparator

    private fun userMessage(e: Exception): String = when (e) {
        is GoalError -> e.message ?: "The goal could not be read."
        else -> ConnectionFailure.userMessage(e) ?: "Goals could not be loaded."
    }
}

/** A focus move stopped after [saved] of [total] rank changes; the order shown after reloading is valid. */
class GoalMoveIncomplete(val saved: Int, val total: Int, val reason: String) :
    Exception("Saved $saved of $total order change${if (total == 1) "" else "s"}. $reason")
