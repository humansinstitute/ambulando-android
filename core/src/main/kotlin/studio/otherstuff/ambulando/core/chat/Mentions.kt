package studio.otherstuff.ambulando.core.chat

import studio.otherstuff.ambulando.core.docs.TextSupport

enum class MentionType(val raw: String) {
    AGENT("agent"), PERSON("person");

    companion object {
        fun fromRaw(raw: String): MentionType? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One mention identity. The visible body token and the structured `mentions` entry are both derived from this value,
 * so they can never disagree (as in the web composer).
 */
class Mention(val type: MentionType, val npub: String, label: String) {
    val label: String = cleanLabel(label, fallback = npub)

    /** `@[Label](mention:type:npub)` — the canonical stored form. */
    val token: String get() = "@[$label](mention:${type.raw}:$npub)"

    /** What the user sees and edits in the composer. */
    val visible: String get() = "@$label"

    /** Request entry for `POST …/messages` `mentions`. */
    val requestObject: Map<String, String> get() = mapOf("type" to type.raw, "npub" to npub, "label" to label)

    override fun equals(other: Any?): Boolean = other is Mention && type == other.type && npub == other.npub && label == other.label
    override fun hashCode(): Int = (type.hashCode() * 31 + npub.hashCode()) * 31 + label.hashCode()
    override fun toString(): String = "Mention(${type.raw}, $npub, $label)"

    companion object {
        private val delimiters = Regex("[\\[\\]()\\n\\r]")

        /** Labels cannot contain the token delimiters; Tower caps label length, so keep it short. */
        internal fun cleanLabel(label: String, fallback: String): String {
            val cleaned = label.replace(delimiters, " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            return TextSupport.graphemePrefix(cleaned.ifEmpty { fallback }, 80)
        }
    }
}

/** A rendered piece of a message body. */
sealed class BodySegment {
    data class Text(val text: String) : BodySegment()
    data class MentionSegment(val mention: Mention) : BodySegment()
    /** A record reference such as `@[Plan](mention:document:<id>)`; rendered as a label, not followed. */
    data class Reference(val label: String, val kind: String, val id: String) : BodySegment()
}

/** The wire body and the structured mentions it carries. */
data class EncodedMentions(val body: String, val mentions: List<Mention>)

/** Composer text with the mentions it shows. */
data class DecodedMentions(val text: String, val mentions: List<Mention>)

object MentionCodec {
    // Matches the web's mention token pattern (any record kind), non-greedy label.
    private val pattern = Regex("@\\[([^\\]]+)\\]\\(mention:([A-Za-z_][A-Za-z0-9_-]*):([^)\\s]+)\\)")

    fun segments(body: String): List<BodySegment> {
        val out = mutableListOf<BodySegment>()
        var cursor = 0
        for (match in pattern.findAll(body)) {
            if (match.range.first > cursor) out.add(BodySegment.Text(body.substring(cursor, match.range.first)))
            val (label, kind, id) = match.destructured
            val type = MentionType.fromRaw(kind)
            out.add(if (type != null) BodySegment.MentionSegment(Mention(type, id, label)) else BodySegment.Reference(label, kind, id))
            cursor = match.range.last + 1
        }
        if (cursor < body.length) out.add(BodySegment.Text(body.substring(cursor)))
        return out
    }

    /** Plain-text preview with tokens shown as `@Label`. */
    fun plain(body: String): String = segments(body).joinToString("") {
        when (it) {
            is BodySegment.Text -> it.text
            is BodySegment.MentionSegment -> it.mention.visible
            is BodySegment.Reference -> it.label
        }
    }

    /**
     * Converts composer text (visible `@Label` tokens) plus the mentions the user picked into the wire body and the
     * structured list. A picked mention whose visible text was edited away is dropped, never sent invisibly.
     */
    fun encode(text: String, selected: List<Mention>): EncodedMentions {
        var body = text
        val used = mutableListOf<Mention>()
        val seen = mutableSetOf<String>()
        // Longest labels first so "@Ann Lee" is not consumed by "@Ann".
        for (mention in selected.sortedByDescending { TextSupport.graphemeCount(it.visible) }) {
            if (mention.npub in seen) continue
            val range = visibleRange(mention, body) ?: continue
            body = body.replaceRange(range, mention.token)
            seen.add(mention.npub)
            used.add(mention)
        }
        val order = selected.map { it.npub }
        return EncodedMentions(body, used.sortedBy { order.indexOf(it.npub).coerceAtLeast(0) })
    }

    /** Finds `@Label` not already inside a canonical token and not followed by more name characters. */
    internal fun visibleRange(mention: Mention, body: String): IntRange? {
        val visible = mention.visible
        var from = 0
        while (true) {
            val start = body.indexOf(visible, from)
            if (start < 0) return null
            val end = start + visible.length
            val insideToken = start > 0 && body[start - 1] == '['
            val next = if (end < body.length) body.codePointAt(end) else ' '.code
            if (!insideToken && !(Character.isLetter(next) || isNumberLike(next)) && !body.startsWith("](mention:", end)) {
                return start until end
            }
            from = end
        }
    }

    /** Swift `Character.isNumber`: any numeric general category. */
    private fun isNumberLike(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    /** Composer text for a stored body (used to edit a failed send): tokens become `@Label` and are re-selected. */
    fun decode(body: String): DecodedMentions {
        val mentions = mutableListOf<Mention>()
        val text = segments(body).joinToString("") { segment ->
            when (segment) {
                is BodySegment.Text -> segment.text
                is BodySegment.MentionSegment -> {
                    if (segment.mention !in mentions) mentions.add(segment.mention)
                    segment.mention.visible
                }
                is BodySegment.Reference -> "@[${segment.label}](mention:${segment.kind}:${segment.id})"
            }
        }
        return DecodedMentions(text, mentions)
    }

    private fun isWhitespace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    /** The `@query` being typed at the end of the composer, if any (drives the picker). */
    fun activeQuery(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0) {
            val before = text[at - 1]
            if (!(isWhitespace(before) || before == '(')) return null
        }
        val query = text.substring(at + 1)
        if (TextSupport.graphemeCount(query) > 40 || query.any { TextSupport.isNewline(it) } || query.count { it == ' ' } > 2) return null
        return query
    }

    /** Replaces the trailing `@query` with the mention's visible text and a trailing space. */
    fun complete(text: String, mention: Mention): String {
        if (activeQuery(text) == null) return text + mention.visible + " "
        return text.substring(0, text.lastIndexOf('@')) + mention.visible + " "
    }
}
