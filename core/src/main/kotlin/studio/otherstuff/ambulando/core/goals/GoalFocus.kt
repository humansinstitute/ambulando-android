package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Focus order: which goals Home shows first. A goal may carry `focus_rank`, a fractional-index key of 1–64 characters
 * from [DIGITS] (ASCII order) not ending in the lowest digit. Keys compare by code unit and a key exists strictly between
 * any two distinct keys, so a move usually rewrites one goal through the ordinary expectation-checked edit.
 *
 * The field stays among the goal's unknown fields ([Goal.extra]), so clients that predate it keep it byte for byte;
 * anything else stored there (wrong type, bad character, too long) reads as unranked and is kept until a valid rank
 * replaces it. Same functions as the Mac app's `GoalFocus` and `scripts/goals-lib.mjs`; the shared fixture
 * `goals/focus-order-v1.json` pins them (docs/goals.md, "Focus order").
 */
object GoalFocus {
    const val FIELD = "focus_rank"
    const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    const val MAX_LENGTH = 64

    fun isValid(value: String): Boolean = value.length in 1..MAX_LENGTH && value.last() != DIGITS[0] && value.all { it in DIGITS }

    /** The valid rank of [goal], or null (unranked). */
    fun rank(goal: Goal): String? = (goal.extra[FIELD] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isValid)

    /** [goal] with [value] as its rank (null removes the field). An existing field keeps its position; a new one goes last. */
    internal fun setting(goal: Goal, value: String?): Goal {
        val fields = LinkedHashMap(goal.extra)
        if (value == null) fields.remove(FIELD) else fields[FIELD] = JsonPrimitive(value)
        return goal.copy(extra = JsonObject(fields))
    }

    /**
     * A key strictly between [a] and [b] (null: no bound). Port of the midpoint of David Greenspan's "Implementing
     * Fractional Indexing" (MIT `fractional-indexing`), without its integer header so every key is a plain fraction.
     */
    fun keyBetween(a: String?, b: String?): String {
        val lo = a ?: ""
        if (lo.isNotEmpty() && !isValid(lo)) throw GoalError.Invalid("A focus rank is invalid.")
        if (b != null && !isValid(b)) throw GoalError.Invalid("A focus rank is invalid.")
        if (b != null && lo >= b) throw GoalError.Invalid("Focus ranks are out of order.")
        return midpoint(lo, b)
    }

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            var n = 0
            while (n < b.length && (if (n < a.length) a[n] else DIGITS[0]) == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.drop(n))
        }
        val da = a.firstOrNull()?.let(DIGITS::indexOf) ?: 0
        val db = b?.firstOrNull()?.let(DIGITS::indexOf) ?: DIGITS.length
        if (db - da > 1) return DIGITS[(da + db + 1) / 2].toString()
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[da] + midpoint(a.drop(1), null)
    }

    /** [n] evenly spaced short keys, in order: the rebalance used when no key fits (ties, or a key over [MAX_LENGTH]). */
    fun evenKeys(n: Int): List<String> {
        val base = DIGITS.length
        var width = 1; var span = base.toLong()
        while (span - 1 < n) { width++; span *= base }
        return (0 until n).map { j ->
            var v = (j + 1) * span / (n + 1)
            val key = StringBuilder()
            repeat(width) { key.insert(0, DIGITS[(v % base).toInt()]); v /= base }
            key.toString().trimEnd(DIGITS[0])
        }
    }

    /**
     * The one goal order on every client: status (active, completed, archived); then ranked goals by rank, before
     * unranked ones; unranked by target date (none last), then name with only A–Z folded; equal keys by file id. Kotlin
     * string comparison is by UTF-16 code unit, as on the Mac and in JS.
     */
    val comparator: Comparator<GoalDocument> = Comparator { a, b -> compare(a.id, a.goal, b.id, b.goal) }

    fun order(goals: List<GoalDocument>): List<GoalDocument> = goals.sortedWith(comparator)

    internal fun compare(aId: String, a: Goal, bId: String, b: Goal): Int {
        if (a.status.ordinal != b.status.ordinal) return a.status.ordinal.compareTo(b.status.ordinal)
        val ra = rank(a); val rb = rank(b)
        if ((ra == null) != (rb == null)) return if (ra == null) 1 else -1
        if (ra != null && rb != null && ra != rb) return ra.compareTo(rb)
        if (ra == null) {
            val ta = a.targetDate ?: "9999-12-31"; val tb = b.targetDate ?: "9999-12-31"
            if (ta != tb) return ta.compareTo(tb)
            val na = asciiFold(a.name); val nb = asciiFold(b.name)
            if (na != nb) return na.compareTo(nb)
        }
        return aId.compareTo(bId)
    }

    private fun asciiFold(s: String) = String(CharArray(s.length) { i -> s[i].let { if (it in 'A'..'Z') it + 32 else it } })

    /** One expectation-checked rank change. */
    data class Write(val fileId: String, val expected: String?, val value: String) {
        val edit: GoalEdit get() = GoalEdit.SetFocusRank(expected, value)
    }

    /**
     * The rank writes that move goal [fileId] to [toIndex] in [items] (one status group, already in focus order), top first.
     * Usually one write: a key between the new neighbours. Ranked goals sort before unranked ones, so placing a goal below
     * an unranked goal first ranks the unranked goals above it, in their current order. Neighbours with equal keys (two
     * devices picked the same key) or a key over [MAX_LENGTH] rebalance: every goal from the top through the last ranked
     * one (or the moved goal) gets [evenKeys], writing only the keys that change.
     */
    fun planMove(items: List<GoalDocument>, fileId: String, toIndex: Int): List<Write> {
        val movingIndex = items.indexOfFirst { it.id == fileId }.takeIf { it >= 0 } ?: throw GoalError.Conflict("That goal was removed elsewhere.")
        val next = items.toMutableList()
        val moving = next.removeAt(movingIndex)
        val at = toIndex.coerceIn(0, next.size)
        next.add(at, moving)
        if (next.map { it.id } == items.map { it.id }) return emptyList()
        val ranks = next.map { rank(it.goal) }
        val writes = ArrayList<Write>()
        var lo: String? = null
        var fits = true
        for (n in 0..at) {
            val r = ranks[n]
            if (n < at && r != null) { lo = r; continue }
            val hi = if (n == at && n + 1 < next.size) ranks[n + 1] else null
            if (hi != null && lo != null && lo >= hi) { fits = false; break }
            val key = keyBetween(lo, hi)
            if (key.length > MAX_LENGTH) { fits = false; break }
            writes += Write(next[n].id, r, key)
            lo = key
        }
        if (fits) return writes
        val last = maxOf(at, ranks.indexOfLast { it != null })
        val keys = evenKeys(last + 1)
        return (0..last).mapNotNull { n -> if (ranks[n] == keys[n]) null else Write(next[n].id, ranks[n], keys[n]) }
    }
}
