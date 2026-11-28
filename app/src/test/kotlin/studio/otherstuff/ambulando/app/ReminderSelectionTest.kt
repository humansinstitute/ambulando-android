package studio.otherstuff.ambulando.app

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.ui.tasks.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

class ReminderSelectionTest {
    private val member = MemberRecord("w", "human", "me", "human", "Me")
    private val scope = ScopeRecord("w", "s", "Scope", updatedAt = "")
    private val channel = ChannelRecord("w", "c", "s", "Team", updatedAt = "")
    private val self = channel.copy(id = "self", kind = "dm", raw = jsonObjectOf("participant_npubs" to listOf("me")))
    private fun row(id: String, state: String = "ready", assigned: String? = "me", channelId: String = "c", raw: kotlinx.serialization.json.JsonObject = jsonObjectOf()) =
        TaskRow.of(TaskRecord("w", id, "s", channelId, title = id, description = null, state = state, priority = "sand", assignedToNpub = assigned, rowVersion = 1, createdAt = "", updatedAt = "", raw = raw), emptyList())
    private fun pick(rows: List<TaskRow>, channels: List<ChannelRecord> = listOf(channel, self), scopes: List<ScopeRecord> = listOf(scope), members: List<MemberRecord> = listOf(member)) =
        ReminderSelection.select("w", "me", members, scopes, channels, rows)

    @Test fun exactStatusesAssigneesAndSelfFirstWithDedupe() {
        val rows = listOf(row("self", channelId = "self"), row("new", "new"), row("progress", "in_progress"), row("ready"), row("other", assigned = "other"), row("none", assigned = null)) +
            listOf("backlog", "review", "blocked", "done", "archive", "archived", "unknown").map { row(it, it) }
        val actual = pick(rows + rows)
        assertEquals(listOf("self"), actual.self.map { it.id })
        assertEquals(listOf("new", "progress", "ready"), actual.others.map { it.id })
    }
    @Test fun boundariesRevocationsDeletionAndAgentsNeverLeakRows() {
        val task = row("task")
        assertTrue(pick(listOf(task.copy(task = task.task.copy(workspaceId = "other")))).others.isEmpty())
        assertTrue(pick(listOf(task.copy(task = task.task.copy(accessRemoved = true)))).others.isEmpty())
        assertTrue(pick(listOf(task), channels = listOf(channel.copy(accessRemoved = true))).others.isEmpty())
        assertTrue(pick(listOf(task), scopes = listOf(scope.copy(accessRemoved = true))).others.isEmpty())
        for (flag in listOf(jsonObjectOf("deleted_at" to "now"), jsonObjectOf("record_state" to "deleted"), jsonObjectOf("is_archived" to true), jsonObjectOf("deleted" to true), jsonObjectOf("record_state" to "revoked"))) assertTrue(pick(listOf(row("deleted", raw = flag))).others.isEmpty())
        assertNull(pick(listOf(task), members = listOf(member.copy(kind = "agent"))).viewer)
        assertNull(pick(listOf(task), members = listOf(member.copy(npub = "owner"))).viewer)
    }
    @Test fun rawMultiAssignmentIsIncludedOnceButPendingUnassignmentWinsAndFailureRollsBack() {
        val row = row("multi", assigned = "other", raw = jsonObjectOf("assignments" to listOf(mapOf("workspace_id" to "w", "actor_npub" to "me"), mapOf("workspace_id" to "w", "actor_npub" to "me"))))
        assertEquals(1, pick(listOf(row, row)).others.size)
        fun write(state: WriteState) = WriteItem("write", "c", "origin", "me", "w", "app", WriteKind.TASK_ASSIGN, "multi", "group", jsonObjectOf("assigned_to_npub" to "").toString().toByteArray(), null, state, 1, null, null, "Failed", "")
        assertTrue(pick(listOf(TaskRow.of(row.task, listOf(write(WriteState.QUEUED))))).others.isEmpty())
        assertEquals(1, pick(listOf(TaskRow.of(row.task, listOf(write(WriteState.FAILED))))).others.size)
    }
    @Test fun secondaryAssignmentResolvesActorIdThroughVerifiedMemberOnly() {
        assertEquals(1, pick(listOf(row("secondary", assigned = "other", raw = jsonObjectOf("assignments" to listOf(mapOf("workspace_id" to "w", "task_id" to "secondary", "actor_id" to member.actorId)))))).others.size)
        assertTrue(pick(listOf(row("foreign", assigned = "other", raw = jsonObjectOf("assignments" to listOf(mapOf("workspace_id" to "elsewhere", "actor_id" to member.actorId)))))).others.isEmpty())
    }

}
