package studio.otherstuff.ambulando.core.goals

import java.time.LocalDate

/**
 * What stops a goal or checkpoint form from being saved, said before saving so Save can be disabled with a reason.
 * Uses the same rules as [Goal.validated]; null when the values can be saved.
 */
object GoalForm {
    fun goalIssue(name: String, description: String, start: String?, target: String?): String? = when {
        name.isBlank() -> "Add a name."
        name.trim().length > Goal.MAX_NAME -> "The name is too long (${Goal.MAX_NAME} characters at most)."
        description.isBlank() -> "Add a description."
        description.trim().length > Goal.MAX_DESCRIPTION -> "The description is too long (${Goal.MAX_DESCRIPTION} characters at most)."
        targetBeforeStart(start, target) -> "The target date is before the start date."
        else -> null
    }

    fun checkpointIssue(title: String, description: String): String? = when {
        title.isBlank() -> "Add a title."
        title.trim().length > MAX_CHECKPOINT_TITLE -> "The title is too long ($MAX_CHECKPOINT_TITLE characters at most)."
        description.trim().length > MAX_CHECKPOINT_DESCRIPTION -> "The description is too long ($MAX_CHECKPOINT_DESCRIPTION characters at most)."
        else -> null
    }

    fun targetBeforeStart(start: String?, target: String?): Boolean {
        val s = start?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        val t = target?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return t.isBefore(s)
    }

    const val MAX_CHECKPOINT_TITLE = 120
    const val MAX_CHECKPOINT_DESCRIPTION = 500
}
