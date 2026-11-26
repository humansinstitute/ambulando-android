package studio.otherstuff.ambulando.core.goals

/**
 * One user or agent change to a goal, expressed against the values the editor was looking at. A save that loses a
 * compare-and-swap race re-applies its edits to the newest version: an edit whose expectation no longer holds throws
 * [GoalError.Conflict] instead of overwriting someone else's change. Edits already in effect are accepted (idempotent).
 */
sealed class GoalEdit {
    abstract fun apply(goal: Goal): Goal

    data class SetName(val expected: String, val value: String) : GoalEdit() {
        override fun apply(goal: Goal) = goal.setting("name", goal.name, expected, value.trim()) { copy(name = it) }
    }

    data class SetDescription(val expected: String, val value: String) : GoalEdit() {
        override fun apply(goal: Goal) = goal.setting("description", goal.description, expected, value.trim()) { copy(description = it) }
    }

    data class SetStartDate(val expected: String?, val value: String?) : GoalEdit() {
        override fun apply(goal: Goal) = goal.setting("start date", goal.startDate, expected, value?.trim()?.ifEmpty { null }) { copy(startDate = it) }
    }

    data class SetTargetDate(val expected: String?, val value: String?) : GoalEdit() {
        override fun apply(goal: Goal) = goal.setting("target date", goal.targetDate, expected, value?.trim()?.ifEmpty { null }) { copy(targetDate = it) }
    }

    /** Completing is explicit and reversible; archiving keeps every checkpoint and link, and linked work is never touched. */
    data class SetStatus(val expected: GoalStatus, val value: GoalStatus) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            if (goal.status == value) return goal
            if (goal.status != expected) throw GoalError.Conflict("This goal was ${goal.status.wire} elsewhere.")
            return goal.copy(status = value).validated()
        }
    }

    /** Appended, or placed after [afterId] when that checkpoint still exists. */
    data class AddCheckpoint(val checkpoint: GoalCheckpoint, val afterId: String? = null) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            goal.requireEditable()
            if (goal.checkpoints.any { it.id == checkpoint.id }) return goal
            val at = goal.checkpoints.indexOfFirst { it.id == afterId }.let { if (it < 0) goal.checkpoints.size else it + 1 }
            return goal.copy(checkpoints = goal.checkpoints.toMutableList().apply { add(at, checkpoint) }).validated()
        }
    }

    /** Replaces title, date and description; [reached] is never changed here. */
    data class UpdateCheckpoint(val id: String, val expected: GoalCheckpoint, val value: GoalCheckpoint) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            goal.requireEditable()
            val current = goal.checkpoints.firstOrNull { it.id == id } ?: throw GoalError.Conflict("That checkpoint was removed elsewhere.")
            fun GoalCheckpoint.body() = Triple(title, date, description)
            val next = current.copy(title = value.title.trim(), date = value.date?.trim()?.ifEmpty { null }, description = value.description?.trim()?.ifEmpty { null })
            if (current.body() == next.body()) return goal
            if (current.body() != expected.body()) throw GoalError.Conflict("“${current.title}” was changed elsewhere.")
            return goal.copy(checkpoints = goal.checkpoints.map { if (it.id == id) next else it }).validated()
        }
    }

    data class SetReached(val id: String, val expected: Boolean, val reached: Boolean) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            goal.requireEditable()
            val current = goal.checkpoints.firstOrNull { it.id == id } ?: throw GoalError.Conflict("That checkpoint was removed elsewhere.")
            if (current.reached == reached) return goal
            if (current.reached != expected) throw GoalError.Conflict("“${current.title}” was changed elsewhere.")
            return goal.copy(checkpoints = goal.checkpoints.map { if (it.id == id) it.copy(reached = reached) else it })
        }
    }

    data class RemoveCheckpoint(val id: String) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            goal.requireEditable()
            return goal.copy(checkpoints = goal.checkpoints.filterNot { it.id == id })
        }
    }

    /** Moves a checkpoint to [toIndex] among the checkpoints that remain (clamped). */
    data class MoveCheckpoint(val id: String, val toIndex: Int) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            goal.requireEditable()
            val item = goal.checkpoints.firstOrNull { it.id == id } ?: throw GoalError.Conflict("That checkpoint was removed elsewhere.")
            val rest = goal.checkpoints.filterNot { it.id == id }
            return goal.copy(checkpoints = rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), item) })
        }
    }

    data class Link(val link: GoalLink) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            goal.requireEditable()
            if (goal.links.any { it.type == link.type && it.recordId == link.recordId }) return goal
            return goal.copy(links = goal.links + link).validated()
        }
    }

    /** Removes only the reference; the source record, its location and its access are untouched. */
    data class Unlink(val type: GoalLinkType, val recordId: String) : GoalEdit() {
        override fun apply(goal: Goal): Goal {
            goal.requireEditable()
            return goal.copy(links = goal.links.filterNot { it.type == type && it.recordId == recordId })
        }
    }

    companion object {
        fun applyAll(goal: Goal, edits: List<GoalEdit>): Goal = edits.fold(goal) { g, e -> e.apply(g) }
    }
}

private fun Goal.requireEditable() {
    if (status == GoalStatus.ARCHIVED) throw GoalError.Conflict("This goal is archived. Restore it to change it.")
}

private fun <T> Goal.setting(label: String, current: T, expected: T, next: T, write: Goal.(T) -> Goal): Goal {
    requireEditable()
    if (current == next) return this
    if (current != expected) throw GoalError.Conflict("The $label was changed elsewhere.")
    return write(next).validated()
}
