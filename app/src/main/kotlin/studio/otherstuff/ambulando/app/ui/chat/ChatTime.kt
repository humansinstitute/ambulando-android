package studio.otherstuff.ambulando.app.ui.chat

import android.text.format.DateFormat
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Message times as the chat rows show them. Labels are memoised per calendar day (rows ask on every layout pass). */
object ChatTime {
    /** The clock and zone labels are computed against (tests pin it). */
    @Volatile var clock: Clock = Clock.systemDefaultZone()
        set(value) { field = value; synchronized(labels) { labels.clear() } }

    private val labels = HashMap<String, String>()
    private var labelsDay = 0L

    fun dayStamp(): Long = LocalDate.now(clock).toEpochDay()

    /** The instant of a Tower timestamp (with or without fractional seconds or an offset). */
    fun date(iso: String): Instant? =
        runCatching { Instant.parse(iso) }.getOrNull() ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

    /** The time today, otherwise day, month and time. */
    fun label(iso: String): String {
        val day = dayStamp()
        synchronized(labels) {
            if (labelsDay != day) { labels.clear(); labelsDay = day }
            labels[iso]?.let { return it }
        }
        val made = compute(iso)
        synchronized(labels) {
            if (labels.size > 5000) labels.clear()
            labels[iso] = made
        }
        return made
    }

    private fun compute(iso: String): String {
        val instant = date(iso) ?: return ""
        val at = ZonedDateTime.ofInstant(instant, clock.zone)
        val locale = Locale.getDefault()
        if (at.toLocalDate() == LocalDate.now(clock)) return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMjmm"), locale).format(at)
    }
}

/** A calendar day in the user's time zone, used to place date separators in chat. */
data class ChatDay(val year: Int, val month: Int, val day: Int) {
    val date: LocalDate get() = LocalDate.of(year, month, day)

    /** "Today", "Yesterday", a weekday within the last week, otherwise the date (with the year if not this year). */
    val label: String
        get() {
            val today = LocalDate.now(ChatTime.clock)
            val date = runCatching { date }.getOrNull() ?: return ""
            if (date == today) return "Today"
            if (date == today.minusDays(1)) return "Yesterday"
            val days = ChronoUnit.DAYS.between(date, today)
            val locale = Locale.getDefault()
            if (days in 1..6) return DateTimeFormatter.ofPattern("EEEE", locale).format(date)
            val skeleton = if (date.year == today.year) "EEEdMMM" else "dMMMy"
            return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
        }

    companion object {
        fun of(date: LocalDate) = ChatDay(date.year, date.monthValue, date.dayOfMonth)
        fun of(iso: String): ChatDay? = ChatTime.date(iso)?.let { of(ZonedDateTime.ofInstant(it, ChatTime.clock.zone).toLocalDate()) }
        val today: ChatDay get() = of(LocalDate.now(ChatTime.clock))

        /**
         * Which rows start a new day (by row id) and the day of the last row. A separator is a label above the first
         * message of each day, never a line between messages.
         */
        fun <Row> breaks(rows: List<Row>, id: (Row) -> String, time: (Row) -> String): Pair<Map<String, ChatDay>, ChatDay?> {
            val starts = HashMap<String, ChatDay>()
            var previous: ChatDay? = null
            for (row in rows) {
                val day = of(time(row)) ?: continue
                if (day != previous) starts[id(row)] = day
                previous = day
            }
            return starts to previous
        }
    }
}
