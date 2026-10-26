package studio.otherstuff.ambulando.core.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.string

/**
 * The additive `text_attachment` marker of shared text attachment contract v1 (`metadata.attachments[]`). It carries
 * counts only, never text.
 */
@Serializable
data class TextAttachmentInfo(val v: Int = 1, val source: String = SOURCE_PASTE, val chars: Int, val lines: Int) {
    val requestObject: JsonObject get() = jsonObjectOf("v" to v, "source" to source, "chars" to chars, "lines" to lines)

    companion object {
        const val SOURCE_PASTE = "paste"
        const val SOURCE_EDIT = "edit"

        fun of(text: CharSequence, source: String): TextAttachmentInfo =
            TextAttachmentInfo(1, source, PastedTextPolicy.codePoints(text), TextAttachments.lineCount(text))

        fun from(attachment: JsonObject): TextAttachmentInfo? {
            val o = attachment.obj("text_attachment") ?: return null
            if (flexibleLong(o["v"]) != 1L) return null
            return TextAttachmentInfo(
                1, o.string("source") ?: SOURCE_PASTE,
                (flexibleLong(o["chars"]) ?: 0).toInt().coerceAtLeast(0), (flexibleLong(o["lines"]) ?: 0).toInt().coerceAtLeast(0),
            )
        }
    }
}

/** Pure rules for text attachments (a port of the web client's `text-attachments.js`). */
object TextAttachments {
    const val CONTENT_TYPE = "text/plain; charset=utf-8"
    const val MAX_NAME = 120
    /** Largest text the editor will open or stage: matches the 4 MB log the feature must handle plus headroom. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Lines as `\n`, `\r\n` and lone `\r` terminate them; empty text has none. */
    fun lineCount(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') lines++
            else if (c == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') i++
                lines++
            }
            i++
        }
        return lines
    }

    /** Exact UTF-8 bytes: no BOM, CRLF kept. Lone surrogates become the replacement character like any encoder. */
    fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** Decoded strictly-enough for display; invalid sequences become U+FFFD. */
    fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** Control characters, slashes and brackets replaced by spaces, collapsed, bounded; an extension is added when missing. */
    fun normalizeName(name: String, fallback: String = "${PastedTextPolicy.BASE_NAME}.${PastedTextPolicy.EXTENSION}"): String {
        val cleaned = buildString {
            for (ch in name) append(if (ch.code < 0x20 || ch.code == 0x7f || ch == '/' || ch == '\\' || ch == '[' || ch == ']') ' ' else ch)
        }.replace(Regex("""\s+"""), " ").trim().let { if (it.length > MAX_NAME) safeTake(it, MAX_NAME) else it }
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return fallback
        return if (Regex("""\.[A-Za-z0-9]{1,8}$""").containsMatchIn(cleaned)) cleaned else "$cleaned.${PastedTextPolicy.EXTENSION}"
    }

    private fun safeTake(text: String, units: Int): String {
        var end = units
        if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        return text.substring(0, end)
    }

    /** First non-blank line of the text, bounded in code points, for compact cards. Reads at most the first 4096 units. */
    fun firstLinePreview(text: CharSequence, maxChars: Int = 120): String {
        val head = if (text.length > 4096) text.subSequence(0, 4096) else text
        var start = 0
        while (start <= head.length) {
            var end = start
            while (end < head.length && head[end] != '\n' && head[end] != '\r') end++
            val line = head.subSequence(start, end).toString().trim()
            if (line.isNotEmpty()) {
                val points = line.codePointCount(0, line.length)
                if (points <= maxChars) return line
                return line.substring(0, line.offsetByCodePoints(0, maxChars)) + "…"
            }
            if (end >= head.length) break
            start = if (head[end] == '\r' && end + 1 < head.length && head[end + 1] == '\n') end + 2 else end + 1
        }
        return ""
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.${if (bytes < 10240) 1 else 0}f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Whether a sent attachment is a text attachment (marker present, or a plain-text file as the web decides). */
    fun isText(kind: String, contentType: String?, info: TextAttachmentInfo?): Boolean =
        info != null || (kind == "file" && contentType?.lowercase()?.startsWith("text/plain") == true)
}

/**
 * Splits large text into bounded chunks and joins them back. The editor and reader work on one chunk at a time, so
 * a multi-megabyte log or a single huge line never reaches one text widget. Chunk boundaries carry no meaning: they
 * prefer a line end but never split a surrogate pair or a CRLF pair, and `join(split(t)) == t` always holds.
 */
object TextChunks {
    const val DEFAULT_UNITS = 12_000

    fun split(text: String, maxUnits: Int = DEFAULT_UNITS): List<String> {
        require(maxUnits >= 4)
        if (text.length <= maxUnits) return listOf(text)
        val out = ArrayList<String>(text.length / maxUnits + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + maxUnits)
            if (end < text.length) {
                // Prefer to end just after a line terminator within the last quarter of the chunk.
                val floor = start + maxUnits * 3 / 4
                var cut = end
                while (cut > floor && text[cut - 1] != '\n') cut--
                if (cut > floor) end = cut else end = safeBoundary(text, end, start)
            }
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    /** Moves [end] back off a surrogate pair or between `\r` and `\n`. */
    private fun safeBoundary(text: String, end: Int, start: Int): Int {
        var e = end
        if (e < text.length && e > start) {
            if (Character.isHighSurrogate(text[e - 1]) && Character.isLowSurrogate(text[e])) e--
            else if (text[e - 1] == '\r' && text[e] == '\n') e--
        }
        return if (e <= start) end else e
    }

    fun join(chunks: List<String>): String {
        if (chunks.size == 1) return chunks[0]
        val sb = StringBuilder(chunks.sumOf { it.length })
        for (c in chunks) sb.append(c)
        return sb.toString()
    }
}
