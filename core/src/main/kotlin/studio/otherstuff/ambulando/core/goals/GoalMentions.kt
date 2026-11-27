package studio.otherstuff.ambulando.core.goals

import studio.otherstuff.ambulando.core.chat.Mention
import studio.otherstuff.ambulando.core.chat.MentionType
import studio.otherstuff.ambulando.core.store.MentionCandidate

/** A discovery prefix only. Goals retain the ordinary File reference understood by installed clients. */
object GoalMentions {
    fun isQuery(query: String): Boolean = query.trimStart().substringBefore(':').equals("goal", ignoreCase = true) && ':' in query

    /** Input must come from the current viewer's verified GoalsSpace, never a workspace-wide File list. */
    fun candidates(goals: List<GoalDocument>, query: String): List<MentionCandidate> {
        val needle = (if (isQuery(query)) query.substringAfter(':') else query).trim()
        return goals.filter { !it.file.accessRemoved && GoalFiles.isGoal(it.file) && it.goal.name.contains(needle, ignoreCase = true) }
            .distinctBy { it.id }
            .sortedWith(compareBy({ !it.goal.name.equals(needle, ignoreCase = true) }, { !it.goal.name.startsWith(needle, ignoreCase = true) }, { it.goal.name.lowercase() }, { it.id }))
            .map { MentionCandidate(Mention(MentionType.FILE, it.id, it.goal.name), "Personal goal", badge = "GOAL") }
    }
}
