package studio.otherstuff.ambulando.core.sync

import studio.otherstuff.ambulando.core.store.Tombstone
import studio.otherstuff.ambulando.core.transport.TowerEvent

/**
 * Local work derived from a batch of Tower events ([TowerEvent], parsed in `core.transport` together with the SSE
 * parser). Deletions are applied directly (tombstones); everything else becomes a keyed, coalescing refresh, so a burst
 * of edits to one record costs one request.
 */
class EventPlan {
    /** One refresh a batch asks for. Value semantics: equal refreshes coalesce. */
    sealed class Refresh {
        data class Message(val channelId: String, val messageId: String) : Refresh()
        data class Thread(val channelId: String, val threadId: String) : Refresh()
        data class ChannelMessages(val channelId: String) : Refresh()
        data class Task(val id: String) : Refresh()
        data class ChannelTasks(val channelId: String) : Refresh()
        data class Doc(val id: String) : Refresh()
        data class ChannelDocs(val channelId: String) : Refresh()
        data class ChannelFiles(val channelId: String) : Refresh()
        data class Reactions(val channelId: String, val messageId: String) : Refresh()
        data object Inbox : Refresh()
        /** Scopes and channels (complete lists flag what the actor can no longer see). */
        data object Structure : Refresh()
        /** Member directory and personal scope order. */
        data object Directory : Refresh()
        /** Membership or grants changed: re-run discovery (a workspace no longer listed is flagged locally). */
        data object Access : Refresh()
        /** The visible canvas list (definitions, order, visibility). */
        data object Canvases : Refresh()
        /** One canvas's snapshot (only refetched when it is stored on this phone). */
        data class Canvas(val id: String) : Refresh()
        /** A scope's Context Tree components, and one component's references. */
        data class ContextTree(val scopeId: String) : Refresh()
        data class ContextReferences(val scopeId: String, val componentId: String) : Refresh()
        /** The viewer's personal pins (actor-only events). */
        data object Pins : Refresh()
        /** Installed agents, and Tower's materialised agent activity of one channel. */
        data object Agents : Refresh()
        data class AgentActivity(val channelId: String) : Refresh()
        /** The viewer's Toolbox (personal WApps). */
        data object Toolbox : Refresh()
    }

    val refreshes: MutableSet<Refresh> = LinkedHashSet()
    val tombstones: MutableSet<Tombstone> = LinkedHashSet()
    /** Highest reported `row_version` per direct record refresh; a local row already at that version is not fetched. */
    val versions: MutableMap<Refresh, Long> = HashMap()
    /** Events of types this client does not render (workrooms, jobs, reports …); counted, not fetched. */
    var ignored = 0
    /** More events than [TARGETED_LIMIT]: per-record fetches were folded into per-channel list catch-ups. */
    var folded = false

    /** Adds a direct refresh. `null` (a change to a child, such as a comment) disables the version skip for it. */
    internal fun insert(refresh: Refresh, version: Long?) {
        val known = refresh in refreshes
        refreshes.add(refresh)
        if (version != null && (!known || versions[refresh] != null)) versions[refresh] = maxOf(versions[refresh] ?: version, version)
        else versions.remove(refresh)
    }

    companion object {
        /**
         * Above this many events in one batch, targeted fetches are replaced by channel list catch-ups (as the web client
         * does for a replay burst).
         */
        const val TARGETED_LIMIT = 100

        internal val ACCESS_TYPES = setOf("workspace_member", "group", "channel_grant", "scope_access", "org_data", "permission_grant")
        internal val DIRECTORY_TYPES = setOf("actor", "workspace_member_profile", "scope_order")

        fun make(events: List<TowerEvent>, workspaceId: String): EventPlan {
            val plan = EventPlan()
            val fold = events.size > TARGETED_LIMIT
            plan.folded = fold
            for (e in events) {
                if (e.workspaceId != workspaceId) { plan.ignored += 1; continue }
                val deleted = e.operation == "deleted"
                val channel = e.channelId
                when (e.entityType) {
                    "message" -> {
                        val id = e.entityId
                        if (id == null || channel == null) { plan.ignored += 1; continue }
                        when {
                            deleted -> plan.tombstones.add(Tombstone.Message(id))
                            fold -> plan.refreshes.add(Refresh.ChannelMessages(channel))
                            else -> plan.insert(Refresh.Message(channel, id), e.entityRowVersion)
                        }
                    }
                    "thread" -> {
                        val id = e.entityId
                        if (id == null || channel == null) { plan.ignored += 1; continue }
                        if (deleted) plan.tombstones.add(Tombstone.Thread(id))
                        else plan.refreshes.add(if (fold) Refresh.ChannelMessages(channel) else Refresh.Thread(channel, id))
                    }
                    "task" -> {
                        val id = e.entityId ?: run { plan.ignored += 1; null } ?: continue
                        when {
                            deleted -> plan.tombstones.add(Tombstone.Task(id))
                            fold && channel != null -> plan.refreshes.add(Refresh.ChannelTasks(channel))
                            else -> plan.insert(Refresh.Task(id), e.entityRowVersion)
                        }
                    }
                    "task_comment", "task_assignment", "task_watcher" -> {
                        if (deleted && e.entityType == "task_comment" && e.entityId != null) plan.tombstones.add(Tombstone.Comment(e.entityId!!))
                        val task = e.parentId
                        if (task != null && !fold) plan.insert(Refresh.Task(task), null)
                        else if (channel != null) plan.refreshes.add(Refresh.ChannelTasks(channel))
                    }
                    "doc" -> {
                        val id = e.entityId ?: run { plan.ignored += 1; null } ?: continue
                        when {
                            deleted -> plan.tombstones.add(Tombstone.Doc(id))
                            channel != null && fold -> plan.refreshes.add(Refresh.ChannelDocs(channel))
                            else -> plan.insert(Refresh.Doc(id), e.entityRowVersion)
                        }
                    }
                    "doc_comment" -> {
                        if (deleted && e.entityId != null) plan.tombstones.add(Tombstone.Comment(e.entityId!!))
                        val doc = e.parentId
                        if (doc != null && !fold) plan.insert(Refresh.Doc(doc), null)
                        else if (channel != null) plan.refreshes.add(Refresh.ChannelDocs(channel))
                    }
                    "file", "file_folder" -> {
                        if (deleted && e.entityType == "file" && e.entityId != null) plan.tombstones.add(Tombstone.File(e.entityId!!))
                        if (channel != null) plan.refreshes.add(Refresh.ChannelFiles(channel))
                    }
                    "reaction" -> {
                        if (deleted && e.entityId != null) plan.tombstones.add(Tombstone.Reaction(e.entityId!!))
                        val target = e.parentId
                        if (target != null && channel != null && !fold) plan.refreshes.add(Refresh.Reactions(channel, target))
                    }
                    "resource_view_state" -> plan.refreshes.add(Refresh.Inbox)
                    "scope", "channel" -> plan.refreshes.add(Refresh.Structure)
                    "canvas", "canvas_order" -> {
                        plan.refreshes.add(Refresh.Canvases)
                        val id = e.canvasId ?: (if (e.entityType == "canvas") e.entityId else null)
                        if (id != null && !deleted) plan.refreshes.add(Refresh.Canvas(id))
                    }
                    "canvas_naplet", "canvas_element" -> {
                        val canvas = e.canvasId
                        plan.refreshes.add(if (canvas != null) Refresh.Canvas(canvas) else Refresh.Canvases)
                    }
                    "context_component", "context_reference" -> {
                        val scope = e.scopeId
                        if (scope == null) { plan.ignored += 1; continue }
                        plan.refreshes.add(Refresh.ContextTree(scope))
                        val component = e.componentId
                        if (e.entityType == "context_reference" && component != null) plan.refreshes.add(Refresh.ContextReferences(scope, component))
                    }
                    "personal_pin", "thread_pin" -> plan.refreshes.add(Refresh.Pins)
                    "agent_activity" -> if (channel != null) plan.refreshes.add(Refresh.AgentActivity(channel)) else plan.ignored += 1
                    "workspace_agent", "autopilot_connection" -> plan.refreshes.add(Refresh.Agents)
                    "personal_wapp" -> plan.refreshes.add(Refresh.Toolbox)
                    else -> when (e.entityType) {
                        in ACCESS_TYPES -> plan.refreshes.addAll(listOf(Refresh.Access, Refresh.Structure, Refresh.Directory))
                        in DIRECTORY_TYPES -> plan.refreshes.add(Refresh.Directory)
                        else -> plan.ignored += 1
                    }
                }
            }
            // A deleted record is not fetched again in the same batch.
            for (t in plan.tombstones) {
                when (t) {
                    is Tombstone.Task -> plan.refreshes.remove(Refresh.Task(t.id))
                    is Tombstone.Doc -> plan.refreshes.remove(Refresh.Doc(t.id))
                    is Tombstone.Message -> plan.refreshes.removeAll { it is Refresh.Message && it.messageId == t.id }
                    is Tombstone.Thread -> plan.refreshes.removeAll { it is Refresh.Thread && it.threadId == t.id }
                    else -> Unit
                }
            }
            plan.versions.keys.retainAll(plan.refreshes)
            return plan
        }
    }
}
