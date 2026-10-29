package studio.otherstuff.ambulando.core.chat

import studio.otherstuff.ambulando.core.docs.TextSupport

/**
 * What an `@` reference names. People and agents are identified by npub and are the only types sent in the structured
 * `mentions` list (routing and notification); every other type is a record reference by id that lives only in the body
 * as `@[Label](mention:<type>:<id>)`, as on the web. A chat thread is `chat:<channelId>#<rootMessageId>` (the web's
 * copied thread reference).
 */
enum class MentionType(val raw: String, val isActor: Boolean = false) {
    AGENT("agent", true), PERSON("person", true),
    DOC("doc"), TASK("task"), CHAT("chat"), CHANNEL("channel"), SCOPE("scope");

    companion object {
        /** The type of a token kind, with the web's aliases (`document` is `doc`; `thread`/`message` are `chat`). */
        fun fromRaw(raw: String): MentionType? = when (raw) {
            "document", "docs" -> DOC
            "thread", "message", "chat_message" -> CHAT
            else -> entries.firstOrNull { it.raw == raw }
        }
    }
}

/**
 * One mention identity. The visible body token and the structured `mentions` entry are both derived from this value,
 * so they can never disagree (as in the web composer). [npub] is the npub of a person or agent and the record id of
 * any other type.
 */
class Mention(val type: MentionType, val npub: String, label: String) {
    /** The token's id: an npub for people and agents, otherwise the record id. */
    val id: String get() = npub

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
            // Stored record references keep their exact kind (a body is never rewritten); only actors are mentions.
            out.add(if (type != null && type.isActor) BodySegment.MentionSegment(Mention(type, id, label)) else BodySegment.Reference(label, kind, id))
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
        // Only people and agents are structured mentions; record references stay in the body.
        return EncodedMentions(body, used.filter { it.type.isActor }.sortedBy { order.indexOf(it.npub).coerceAtLeast(0) })
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

    /** Every place the mention's visible `@Label` stands as a mention in composer text (what [encode] would replace). */
    fun visibleRanges(mention: Mention, text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from < text.length) {
            val range = visibleRange(mention, text.substring(from)) ?: break
            out.add((range.first + from)..(range.last + from))
            from += range.last + 1
        }
        return out
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

    /** An `@query` being typed: the `@` position and the text after it up to the caret. */
    data class ActiveQuery(val start: Int, val query: String)

    /**
     * The `@query` ending at [caret], if any: the `@` starts the text or follows whitespace or `(`, and the query has no
     * newline. Spaces are allowed (names have them), within the same bounds as [activeQuery].
     */
    fun activeQuery(text: String, caret: Int): ActiveQuery? {
        val end = caret.coerceIn(0, text.length)
        val query = activeQuery(text.substring(0, end)) ?: return null
        return ActiveQuery(end - query.length - 1, query)
    }

    /** Replaces the `@query` ending at [caret] with the mention's visible text and a space; returns the text and caret. */
    fun complete(text: String, caret: Int, mention: Mention): Pair<String, Int> {
        val end = caret.coerceIn(0, text.length)
        val active = activeQuery(text, end)
        val start = active?.start ?: end
        val inserted = mention.visible + " "
        val rest = text.substring(end).let { if (it.startsWith(" ")) it.substring(1) else it }
        return (text.substring(0, start) + inserted + rest) to (start + inserted.length)
    }

    /** Replaces the trailing `@query` with the mention's visible text and a trailing space. */
    fun complete(text: String, mention: Mention): String {
        if (activeQuery(text) == null) return text + mention.visible + " "
        return text.substring(0, text.lastIndexOf('@')) + mention.visible + " "
    }
}
