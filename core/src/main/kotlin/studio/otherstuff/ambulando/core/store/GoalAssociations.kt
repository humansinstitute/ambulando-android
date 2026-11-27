package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string

/** Exact metadata filter over synchronized native records, without the general list's unrelated-record limit. */
suspend fun StoreReader.associatedGoalLinks(workspace: String, goalId: String, explicit: List<GoalLink>): List<ResolvedGoalLink> {
    val automatic = read {
        listOf("threads" to GoalLinkType.CHAT, "docs" to GoalLinkType.DOC, "files" to GoalLinkType.FILE).flatMap { (table, type) ->
            query("""SELECT r.id,r.channel_id,r.raw FROM $table r
                JOIN channels c ON c.workspace_id=r.workspace_id AND c.id=r.channel_id
                JOIN scopes s ON s.workspace_id=c.workspace_id AND s.id=c.scope_id
                WHERE r.workspace_id=? AND r.access_removed=0 AND r.archived=0
                AND c.access_removed=0 AND s.access_removed=0
                AND json_extract(c.raw,'$.archived_at') IS NULL AND json_extract(c.raw,'$.deleted_at') IS NULL
                AND COALESCE(json_extract(c.raw,'$.record_state'),'active')!='deleted'
                AND json_extract(s.raw,'$.archived_at') IS NULL AND json_extract(s.raw,'$.deleted_at') IS NULL
                AND COALESCE(json_extract(s.raw,'$.record_state'),'active')!='deleted'
                AND json_extract(r.raw,'$.metadata.ambulando_goal.goal_file_id')=?
                ORDER BY r.updated_at DESC,r.id LIMIT 500""", workspace, goalId) { row ->
                val raw = parseRaw(row.text(2))
                val valid = runCatching { GoalAssociation.read(raw.obj("metadata") ?: return@query null) == goalId }.getOrDefault(false)
                if (!valid || raw.string("deleted_at") != null || raw.string("record_state") == "deleted") null
                else GoalLink(type, row.text(0)!!, row.text(1))
            }.filterNotNull()
        }
    }
    val automaticIds = automatic.map { it.type to it.recordId }.toSet()
    return resolveGoalLinks(workspace, (explicit + automatic).distinctBy { it.type to it.recordId })
        .map { it.copy(automatic = (it.link.type to it.link.recordId) in automaticIds) }
}
