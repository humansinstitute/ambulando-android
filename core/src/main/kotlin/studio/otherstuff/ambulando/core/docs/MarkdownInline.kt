package studio.otherstuff.ambulando.core.docs

/**
 * Inline Markdown (CommonMark inlines plus GFM strikethrough), standing in for the platform Markdown parser the
 * iPhone app uses in its "inline only, preserving whitespace" mode: newlines stay in the text, block syntax is not
 * interpreted, and the result is a list of maximal styled spans (adjacent spans with identical styles are merged,
 * as attributed-string runs are).
 *
 * Supported: backslash escapes, entity and numeric character references, code spans, `*`/`_` emphasis and strong
 * emphasis (CommonMark delimiter rules, including the rule of three), `~`/`~~` strikethrough, inline links and
 * images with optional titles, and `<scheme:…>` / `<email>` autolinks. Reference-style links (which need block-level
 * definitions) and raw inline HTML (other than what callers strip) stay literal text.
 */
internal object MarkdownInline {
    data class Span(
        val text: String,
        val strong: Boolean = false,
        val emphasis: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
        val image: String? = null,
    )

    fun parse(source: String): List<Span> {
        val nodes = Parser(source).run()
        val spans = mutableListOf<Span>()
        flatten(nodes, Span(""), spans)
        // Merge adjacent spans with identical attributes.
        val out = mutableListOf<Span>()
        for (span in spans) {
            if (span.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + span.text)
            else out.add(span)
        }
        return out
    }

    private fun flatten(nodes: List<Node>, style: Span, out: MutableList<Span>) {
        for (node in nodes) {
            when (node) {
                is Node.Text -> out.add(style.copy(text = node.text))
                is Node.Code -> out.add(style.copy(text = node.text, code = true))
                is Node.Container -> when (node.kind) {
                    Kind.EMPHASIS -> flatten(node.children, style.copy(emphasis = true), out)
                    Kind.STRONG -> flatten(node.children, style.copy(strong = true), out)
                    Kind.STRIKE -> flatten(node.children, style.copy(strike = true), out)
                    Kind.LINK -> flatten(node.children, style.copy(link = node.destination), out)
                    // An image is one run: its alt text, carrying the image source.
                    Kind.IMAGE -> out.add(style.copy(text = plain(node.children), image = node.destination))
                }
            }
        }
    }

    private fun plain(nodes: List<Node>): String = nodes.joinToString("") { node ->
        when (node) {
            is Node.Text -> node.text
            is Node.Code -> node.text
            is Node.Container -> plain(node.children)
        }
    }

    enum class Kind { EMPHASIS, STRONG, STRIKE, LINK, IMAGE }

    sealed class Node {
        class Text(var text: String) : Node()
        class Code(val text: String) : Node()
        class Container(val kind: Kind, val destination: String?, val children: List<Node>) : Node()
    }

    private class Delimiter(
        val node: Node.Text,
        val char: Char,
        var count: Int,
        val originalCount: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
    )

    private class Bracket(val node: Node.Text, val image: Boolean, val delimiterBottom: Int, var active: Boolean = true)

    private class Parser(val src: String) {
        val nodes = mutableListOf<Node>()
        val delimiters = mutableListOf<Delimiter>()
        val brackets = mutableListOf<Bracket>()
        var pos = 0
        val text = StringBuilder()

        fun flushText() {
            if (text.isEmpty()) return
            val last = nodes.lastOrNull()
            // Keep delimiter and bracket text nodes separate; plain text can merge.
            if (last is Node.Text && delimiters.none { it.node === last } && brackets.none { it.node === last }) last.text += text
            else nodes.add(Node.Text(text.toString()))
            text.clear()
        }

        fun run(): List<Node> {
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c == '\\' -> escape()
                    c == '`' -> codeSpan()
                    c == '*' || c == '_' || c == '~' -> delimiterRun(c)
                    c == '[' -> { flushText(); val n = Node.Text("["); nodes.add(n); brackets.add(Bracket(n, false, delimiters.size)); pos++ }
                    c == '!' && src.getOrNull(pos + 1) == '[' -> {
                        flushText(); val n = Node.Text("!["); nodes.add(n); brackets.add(Bracket(n, true, delimiters.size)); pos += 2
                    }
                    c == ']' -> closeBracket()
                    c == '<' -> autolink()
                    c == '&' -> entity()
                    else -> { text.append(c); pos++ }
                }
            }
            flushText()
            processEmphasis(0)
            return nodes
        }

        fun escape() {
            val next = src.getOrNull(pos + 1)
            when {
                next == '\n' -> { text.append('\n'); pos += 2 }   // hard line break
                next != null && isAsciiPunctuation(next) -> { text.append(next); pos += 2 }
                else -> { text.append('\\'); pos++ }
            }
        }

        fun codeSpan() {
            val start = pos
            while (pos < src.length && src[pos] == '`') pos++
            val ticks = pos - start
            var search = pos
            while (search < src.length) {
                val open = src.indexOf('`', search)
                if (open < 0) break
                var end = open
                while (end < src.length && src[end] == '`') end++
                if (end - open == ticks) {
                    var content = src.substring(pos, open).replace('\n', ' ')
                    if (content.length >= 2 && content.startsWith(" ") && content.endsWith(" ") && content.any { it != ' ' }) {
                        content = content.substring(1, content.length - 1)
                    }
                    flushText()
                    nodes.add(Node.Code(content))
                    pos = end
                    return
                }
                search = end
            }
            // No closing run: the backticks are literal.
            text.append(src, start, pos)
        }

        fun delimiterRun(c: Char) {
            val start = pos
            while (pos < src.length && src[pos] == c) pos++
            val count = pos - start
            if (c == '~' && count > 2) { text.append(src, start, pos); return }
            val before = if (start == 0) '\n'.code else src.codePointBefore(start)
            val after = if (pos >= src.length) '\n'.code else src.codePointAt(pos)
            val beforeSpace = isUnicodeWhitespace(before)
            val afterSpace = isUnicodeWhitespace(after)
            val beforePunct = isPunctuation(before)
            val afterPunct = isPunctuation(after)
            val leftFlanking = !afterSpace && (!afterPunct || beforeSpace || beforePunct)
            val rightFlanking = !beforeSpace && (!beforePunct || afterSpace || afterPunct)
            val canOpen: Boolean
            val canClose: Boolean
            if (c == '_') {
                canOpen = leftFlanking && (!rightFlanking || beforePunct)
                canClose = rightFlanking && (!leftFlanking || afterPunct)
            } else {
                canOpen = leftFlanking
                canClose = rightFlanking
            }
            flushText()
            val node = Node.Text(src.substring(start, pos))
            nodes.add(node)
            if (canOpen || canClose) delimiters.add(Delimiter(node, c, count, count, canOpen, canClose))
        }

        fun closeBracket() {
            pos++
            val opener = brackets.lastOrNull()
            if (opener == null) { text.append(']'); return }
            if (!opener.active) { brackets.removeAt(brackets.size - 1); text.append(']'); return }
            val destination = inlineLinkTail()
            if (destination == null) {
                brackets.removeAt(brackets.size - 1)
                text.append(']')
                return
            }
            flushText()
            processEmphasis(opener.delimiterBottom)
            val openerIndex = nodes.indexOfFirst { it === opener.node }
            val children = nodes.subList(openerIndex + 1, nodes.size).toList()
            while (nodes.size > openerIndex) nodes.removeAt(nodes.size - 1)
            nodes.add(Node.Container(if (opener.image) Kind.IMAGE else Kind.LINK, destination, children))
            brackets.removeAt(brackets.size - 1)
            // Links cannot contain links: earlier link openers become literal.
            if (!opener.image) brackets.forEach { if (!it.image) it.active = false }
        }

        /** Parses `(destination "title")` right after `]`; returns the destination or null (position unchanged). */
        fun inlineLinkTail(): String? {
            if (src.getOrNull(pos) != '(') return null
            var p = pos + 1
            p = skipLinkSpace(p)
            val destination: String
            if (src.getOrNull(p) == '<') {
                val end = StringBuilder()
                var q = p + 1
                while (q < src.length && src[q] != '>' && src[q] != '\n' && src[q] != '<') {
                    if (src[q] == '\\' && q + 1 < src.length && isAsciiPunctuation(src[q + 1])) { end.append(src[q + 1]); q += 2; continue }
                    end.append(src[q]); q++
                }
                if (src.getOrNull(q) != '>') return null
                destination = end.toString()
                p = q + 1
            } else {
                val out = StringBuilder()
                var depth = 0
                var q = p
                while (q < src.length) {
                    val ch = src[q]
                    if (ch == '\\' && q + 1 < src.length && isAsciiPunctuation(src[q + 1])) { out.append(src[q + 1]); q += 2; continue }
                    if (ch.code <= 0x20) break
                    if (ch == '(') depth++
                    if (ch == ')') { if (depth == 0) break; depth-- }
                    out.append(ch); q++
                }
                if (depth != 0) return null
                destination = out.toString()
                p = q
            }
            val beforeTitle = p
            p = skipLinkSpace(p)
            val closeTitle = when (src.getOrNull(p)) { '"' -> '"'; '\'' -> '\''; '(' -> ')'; else -> null }
            if (closeTitle != null && p > beforeTitle) {
                var q = p + 1
                while (q < src.length && src[q] != closeTitle) { if (src[q] == '\\') q++; q++ }
                if (q >= src.length) return null
                p = skipLinkSpace(q + 1)
            }
            if (src.getOrNull(p) != ')') return null
            pos = p + 1
            return decodeEntities(destination)
        }

        fun skipLinkSpace(start: Int): Int {
            var p = start
            var newlines = 0
            while (p < src.length && (src[p] == ' ' || src[p] == '\t' || src[p] == '\n')) {
                if (src[p] == '\n' && ++newlines > 1) break
                p++
            }
            return p
        }

        fun autolink() {
            val end = src.indexOf('>', pos + 1)
            if (end > pos + 1) {
                val inner = src.substring(pos + 1, end)
                val uri = Regex("^[A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>\\x00-\\x20]*$")
                val email = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
                val destination = when {
                    uri.matches(inner) -> inner
                    email.matches(inner) -> "mailto:$inner"
                    else -> null
                }
                if (destination != null) {
                    flushText()
                    nodes.add(Node.Container(Kind.LINK, destination, listOf(Node.Text(inner))))
                    pos = end + 1
                    return
                }
            }
            text.append('<'); pos++
        }

        fun entity() {
            val match = ENTITY.matchAt(src, pos)
            val decoded = match?.let { decodeEntity(it.value) }
            if (match != null && decoded != null) { text.append(decoded); pos = match.range.last + 1 } else { text.append('&'); pos++ }
        }

        /** CommonMark `process_emphasis` over the delimiters above `bottom`. */
        fun processEmphasis(bottom: Int) {
            val openersBottom = mutableMapOf<String, Delimiter?>()
            var ci = bottom
            while (ci < delimiters.size) {
                val closer = delimiters[ci]
                if (!closer.canClose) { ci++; continue }
                val key = "${closer.char}${closer.canOpen}${closer.originalCount % 3}"
                val floor = openersBottom[key]
                var oi = ci - 1
                var found = -1
                while (oi >= bottom && delimiters[oi] !== floor) {
                    val opener = delimiters[oi]
                    if (opener.char == closer.char && opener.canOpen) {
                        val matches = if (closer.char == '~') opener.count == closer.count
                            else !((opener.canClose || closer.canOpen) && (opener.originalCount + closer.originalCount) % 3 == 0 &&
                                !(opener.originalCount % 3 == 0 && closer.originalCount % 3 == 0))
                        if (matches) { found = oi; break }
                    }
                    oi--
                }
                if (found < 0) {
                    openersBottom[key] = if (ci - 1 >= bottom) delimiters[ci - 1] else null
                    if (!closer.canOpen) delimiters.removeAt(ci) else ci++
                    continue
                }
                val opener = delimiters[found]
                val use: Int
                val kind: Kind
                if (closer.char == '~') { use = closer.count; kind = Kind.STRIKE }
                else if (closer.count >= 2 && opener.count >= 2) { use = 2; kind = Kind.STRONG }
                else { use = 1; kind = Kind.EMPHASIS }
                opener.count -= use
                closer.count -= use
                opener.node.text = opener.node.text.dropLast(use)
                closer.node.text = closer.node.text.drop(use)
                // Wrap the nodes between opener and closer.
                val start = nodes.indexOfFirst { it === opener.node }
                val end = nodes.indexOfFirst { it === closer.node }
                val children = nodes.subList(start + 1, end).toList()
                repeat(end - start - 1) { nodes.removeAt(start + 1) }
                nodes.add(start + 1, Node.Container(kind, null, children))
                // Delimiters between them are spent.
                repeat(ci - found - 1) { delimiters.removeAt(found + 1) }
                ci = found + 1
                if (opener.count == 0) {
                    nodes.remove(opener.node)
                    delimiters.removeAt(found)
                    ci--
                }
                if (closer.count == 0) {
                    nodes.remove(closer.node)
                    delimiters.removeAt(ci)
                }
            }
            while (delimiters.size > bottom) delimiters.removeAt(delimiters.size - 1)
        }
    }

    private val ENTITY = Regex("&(?:#[xX][0-9A-Fa-f]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,31});")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "copy" to "©",
        "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•",
        "middot" to "·", "times" to "×", "divide" to "÷", "deg" to "°", "euro" to "€",
        "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
        "laquo" to "«", "raquo" to "»", "larr" to "←", "rarr" to "→", "uarr" to "↑",
        "darr" to "↓", "check" to "✓", "tab" to "\t", "newline" to "\n",
    )

    /** Decodes one `&…;` reference, or null when it is not a known reference (it then stays literal). */
    fun decodeEntity(entity: String): String? {
        val body = entity.substring(1, entity.length - 1)
        if (body.startsWith("#")) {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) body.substring(2).toIntOrNull(16) else body.substring(1).toIntOrNull()
            code ?: return null
            // Invalid or zero code points become U+FFFD, as CommonMark specifies.
            val valid = code in 1..0x10FFFF && code !in 0xD800..0xDFFF
            return String(Character.toChars(if (valid) code else 0xFFFD))
        }
        return NAMED_ENTITIES[body]
    }

    fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        return ENTITY.replace(value) { decodeEntity(it.value) ?: it.value }
    }

    fun isAsciiPunctuation(c: Char): Boolean = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** Unicode punctuation for flanking rules: general categories P and S (CommonMark 0.31). */
    fun isPunctuation(cp: Int): Boolean {
        if (cp < 0x80) return isAsciiPunctuation(cp.toChar())
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
            else -> false
        }
    }

    fun isUnicodeWhitespace(cp: Int): Boolean =
        cp == 0x09 || cp == 0x0A || cp == 0x0C || cp == 0x0D || cp == 0x20 || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()
}
