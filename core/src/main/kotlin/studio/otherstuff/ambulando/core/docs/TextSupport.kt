package studio.otherstuff.ambulando.core.docs

import java.text.BreakIterator

/** A range of UTF-16 code units (the unit of text view selections and of ProseMirror text positions). */
data class TextRange(val location: Int, val length: Int) {
    val end: Int get() = location + length
}

/** Unicode-aware string helpers shared by the document and chat text code. */
internal object TextSupport {
    /** Unicode scalars (code points) of a string. */
    fun scalars(value: String): IntArray = value.codePoints().toArray()

    fun fromScalars(scalars: IntArray, from: Int = 0, to: Int = scalars.size): String =
        if (from >= to) "" else String(scalars, from, to - from)

    fun fromScalars(scalars: List<Int>): String = fromScalars(scalars.toIntArray())

    /** Number of user-perceived characters (extended grapheme clusters). */
    fun graphemeCount(value: String): Int {
        if (value.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(value)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    /** The first `count` user-perceived characters. */
    fun graphemePrefix(value: String, count: Int): String {
        if (count <= 0) return ""
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(value)
        var end = 0
        repeat(count) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return value
            end = next
        }
        return value.substring(0, end)
    }

    /** Foundation `.whitespaces`: space separators (Zs) and tab. */
    fun isWhitespace(c: Char): Boolean = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** Foundation `.newlines`. */
    fun isNewline(c: Char): Boolean = c in "\n\u000B\u000C\r\u0085  "

    fun trimWhitespaces(value: String): String = value.trim { isWhitespace(it) }

    fun trimNewlines(value: String): String = value.trim { isNewline(it) }

    /** Foundation `.whitespacesAndNewlines`. */
    fun trimWhitespacesAndNewlines(value: String): String = value.trim { isWhitespace(it) || isNewline(it) }

    fun isBlankText(value: String): Boolean = trimWhitespacesAndNewlines(value).isEmpty()

    private val uuidPattern = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /** The canonical `8-4-4-4-12` hexadecimal form that `UUID(uuidString:)` accepts. */
    fun isUuid(value: String): Boolean = uuidPattern.matches(value)
}
