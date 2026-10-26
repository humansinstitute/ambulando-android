package studio.otherstuff.ambulando.core.chat

/**
 * When a text paste becomes a `.txt` attachment, and what that attachment is called. Limits count Unicode code points
 * (not UTF-16 units); the text itself is never normalised, so CRLF and every code point reach the file as typed.
 */
object PastedTextPolicy {
    /** A single paste over this many code points becomes an attachment. */
    const val PASTE_THRESHOLD = 2500
    /** Direct typing and inline pastes stay within this many code points. */
    const val INLINE_LIMIT = 10000
    /** Safeguard on the UTF-8 size of the inline text. */
    const val INLINE_UTF8_BYTES = 40000
    const val MIME_TYPE = "text/plain"
    const val BASE_NAME = "Pasted text"
    const val EXTENSION = "txt"

    fun codePoints(text: CharSequence): Int = Character.codePointCount(text, 0, text.length)

    /** UTF-8 size without allocating the encoded bytes; lone surrogates count as the 3-byte replacement. */
    fun utf8Bytes(text: CharSequence): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            total += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return total
    }

    /** The inserted text exceeds the paste threshold, or would take the inline text past the code point or byte cap. */
    fun shouldAttach(inserted: CharSequence, remainingText: CharSequence): Boolean {
        if (inserted.isEmpty()) return false
        val insertedPoints = codePoints(inserted)
        if (insertedPoints > PASTE_THRESHOLD) return true
        if (codePoints(remainingText) + insertedPoints > INLINE_LIMIT) return true
        return utf8Bytes(remainingText) + utf8Bytes(inserted) > INLINE_UTF8_BYTES
    }

    /** True when typing/IME text of this size is within the direct-typing caps. */
    fun fitsInline(text: CharSequence): Boolean = codePoints(text) <= INLINE_LIMIT && utf8Bytes(text) <= INLINE_UTF8_BYTES

    /** Code points left before the cap, shown as a counter once the text nears it. */
    fun remaining(text: CharSequence): Int = INLINE_LIMIT - codePoints(text)
    fun showsCounter(text: CharSequence): Boolean = remaining(text) <= 500

    /** `Pasted text.txt`, `Pasted text 2.txt`, ... skipping names already in use (case-insensitive). */
    fun nextName(taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        var n = 1
        while (true) {
            val name = if (n == 1) "$BASE_NAME.$EXTENSION" else "$BASE_NAME $n.$EXTENSION"
            if (name.lowercase() !in used) return name
            n++
        }
    }

    /** A single contiguous insertion: `before` became `after` by inserting [inserted] at [start]. */
    data class Insertion(val start: Int, val inserted: String, val remaining: String)

    /**
     * Finds the one insertion that turned `before` into `after`, or null when the edit also removed or replaced text
     * (a replacement of a selection counts as an insertion over the removed range). Boundaries never split a surrogate pair.
     */
    fun insertion(before: String, after: String): Insertion? {
        if (after == before) return null
        var prefix = 0
        val max = minOf(before.length, after.length)
        while (prefix < max && before[prefix] == after[prefix]) prefix++
        if (prefix in 1 until after.length && Character.isHighSurrogate(after[prefix - 1]) && Character.isLowSurrogate(after[prefix])) prefix--
        var suffix = 0
        while (suffix < max - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        val cut = after.length - suffix
        if (suffix > 0 && cut > 0 && cut < after.length && Character.isHighSurrogate(after[cut - 1]) && Character.isLowSurrogate(after[cut])) suffix--
        val inserted = after.substring(prefix, after.length - suffix)
        if (inserted.isEmpty()) return null
        return Insertion(prefix, inserted, before.substring(0, prefix) + before.substring(before.length - suffix))
    }
}
