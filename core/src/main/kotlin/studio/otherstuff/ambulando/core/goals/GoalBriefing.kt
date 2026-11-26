package studio.otherstuff.ambulando.core.goals

/**
 * Home's Daily Briefing, from saved goals only. Nothing is inferred, scheduled or written in an agent's voice: each fact
 * restates a date or checkpoint the owner (or an agent they allowed) saved. Agent-written briefings do not exist yet.
 * Same semantics as the Mac app's `GoalBriefing`: another day lists only what is dated that day, and reached marks are
 * the status now (past days are not recorded).
 */
object GoalBriefing {
    sealed class Fact {
        /** The first unreached checkpoint of a goal in focus (today only). */
        data class Next(val goal: String, val checkpoint: String, val date: String?) : Fact()
        /** A checkpoint dated on the day; [reached] is its status now. */
        data class Dated(val goal: String, val checkpoint: String, val reached: Boolean) : Fact()
        /** An unreached checkpoint whose date has passed (today only). */
        data class PastDate(val goal: String, val checkpoint: String, val date: String) : Fact()
        data class TargetDate(val goal: String) : Fact()
        data class StartDate(val goal: String) : Fact()
    }

    /** Facts for [day] (`yyyy-MM-dd`) from active goals in focus order; the first [focus] goals give "next". */
    fun facts(goals: List<GoalDocument>, day: String, today: String, focus: Int = 3): List<Fact> {
        val active = GoalFocus.order(goals.filter { it.goal.status == GoalStatus.ACTIVE })
        val out = ArrayList<Fact>()
        if (day == today) for (doc in active.take(focus)) {
            val id = doc.goal.nextCheckpointId ?: continue
            doc.goal.checkpoints.firstOrNull { it.id == id }?.let { out += Fact.Next(doc.goal.name, it.title, it.date) }
        }
        for (doc in active) {
            val g = doc.goal
            if (g.startDate == day) out += Fact.StartDate(g.name)
            if (g.targetDate == day) out += Fact.TargetDate(g.name)
            for (cp in g.checkpoints) {
                val date = cp.date
                if (date == day) out += Fact.Dated(g.name, cp.title, cp.reached)
                else if (day == today && !cp.reached && date != null && date < day) out += Fact.PastDate(g.name, cp.title, date)
            }
        }
        return out
    }
}
