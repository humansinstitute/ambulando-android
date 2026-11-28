package studio.otherstuff.ambulando.app.ui.tasks

import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import kotlinx.serialization.json.*

/** A projection of existing task records, never a second task collection. */
data class ReminderList(val self: List<TaskRow> = emptyList(), val others: List<TaskRow> = emptyList(), val viewer: String? = null)

object ReminderSelection {
    fun select(workspace: String, signer: String, members: List<MemberRecord>, scopes: List<ScopeRecord>, channels: List<ChannelRecord>, tasks: List<TaskRow>): ReminderList {
        // Native requests use the unlocked account directly. A delegated agent is not its human owner.
        val human = members.singleOrNull { it.workspaceId == workspace && it.npub == signer && it.kind == "human" } ?: return ReminderList()
        val openScopes = scopes.filter { it.workspaceId == workspace && !it.accessRemoved && visible(it.raw) }.map { it.id }.toSet()
        val openChannels = channels.filter { it.workspaceId == workspace && it.scopeId in openScopes && !it.accessRemoved && visible(it.raw) }.associateBy { it.id }
        val rows = tasks.filter { row ->
            val task = row.task
            task.workspaceId == workspace && !task.accessRemoved && visible(task.raw) && task.channelId in openChannels &&
                row.state in setOf("new", "ready", "in_progress") && assigned(row, human)
        }.distinctBy { it.id }
        val (self, others) = rows.partition { Reminders.isSelf(openChannels.getValue(it.task.channelId), workspace, human.npub) }
        return ReminderList(self, others, human.npub)
    }

    private fun visible(raw: JsonObject): Boolean = Reminders.active(raw) && raw.bool("deleted") != true && raw.string("record_state") !in setOf("revoked", "removed")

    private fun assigned(row: TaskRow, human: MemberRecord): Boolean {
        val viewer = human.npub
        // The existing single-assignee editor atomically replaces active assignments; pending writes override raw rows.
        if (row.pending.any { it.isPending && it.kind == WriteKind.TASK_ASSIGN }) return row.assignee == viewer
        val assignments = row.task.raw["assignments"] as? JsonArray
        return row.assignee == viewer || assignments.orEmpty().any {
            val a = it as? JsonObject
            a != null && (a.string("actor_npub") == viewer || (a.string("actor_npub") == null && a.string("actor_id") == human.actorId)) &&
                a.string("workspace_id") == row.task.workspaceId && (a.string("task_id")?.let { id -> id == row.id } != false) && visible(a)
        }
    }
}
