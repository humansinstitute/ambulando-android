package studio.otherstuff.ambulando.core.goals

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date-only presentation: no time zone is involved, so a date never shifts between devices. */
object GoalFormat {
    fun date(value: String?, locale: Locale = Locale.getDefault()): String? =
        value?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)) }.getOrNull() }

    /** Timeline dates drop the year when it matches [today]'s. */
    fun short(value: String?, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String? = value?.let {
        runCatching {
            val d = LocalDate.parse(it)
            d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy", locale))
        }.getOrNull()
    }

    fun progress(goal: Goal): String = when (goal.checkpoints.size) {
        0 -> "No checkpoints yet"
        else -> "${goal.reachedCount} of ${goal.checkpoints.size} checkpoint${if (goal.checkpoints.size == 1) "" else "s"} reached"
    }
}
