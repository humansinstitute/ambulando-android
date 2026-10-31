package studio.otherstuff.ambulando.core.docs

import studio.otherstuff.ambulando.core.transport.DocRecord
import java.net.URLDecoder

/**
 * What a wiki reference points at. [documentId] is the stable binding (`[Title](wiki:<document-id>)` or an `fdWikiLink`
 * node with `documentId`); a title-only reference (`[[Title]]`) has none until a target is chosen or created.
 */
data class WikiTarget(
    val documentId: String? = null,
    val title: String,
    /**
     * For a title-only reference: which of the document's same-title title-only references this is, in reading order. It lets
     * "create this page" or "choose this page" change exactly the reference that was opened. Zero for a bound link.
     */
    val occurrence: Int = 0,
    /**
     * How many same-title title-only references the document had where this one was read; a page requested for this
     * reference is only linked back if that number is still true.
     */
    val count: Int = 0,
)

/** How a reference resolves against the documents saved on this device. */
sealed class WikiResolution {
    /** A readable document: opens at once from the cache, whichever scope or channel it is in now. */
    data class Available(val doc: DocRecord) : WikiResolution()

    /** A bound id that is not among the readable documents saved here. Still opened by id (Tower decides access); never replaced by a title match or a new page. */
    data class Unavailable(val documentId: String) : WikiResolution()

    /** A title-only reference with no match in the source channel: offered for creation. */
    data class Unresolved(val title: String) : WikiResolution()

    /** A title-only reference several pages answer to: the person picks one, nothing is chosen silently. */
    data class Ambiguous(val title: String, val matches: List<DocRecord>) : WikiResolution()
}

/**
 * Document links as the web and the iPhone/Mac apps write them: `[Title](wiki:<document-id>)` in Markdown and an
 * `fdWikiLink` node `{documentId, title}` in a rich document. The id is the identity; a link grants no access, and a link
 * to a page this device has not saved is still opened by id so Tower decides. Matches inside code are plain text.
 */
object WikiLinks {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val markdownLink = Regex("\\]\\(\\s*wiki:([^)\\s]+)\\s*\\)")
    private val fencedCode = Regex("(?s)(```|~~~).*?(\\1|\\z)")
    private val inlineCode = Regex("`[^`\\n]*`")

    /** `[[Title]]` outside code, as the web editor's picker plugin reads it. */
    private val literal = Regex("\\[\\[([^\\]\\n]+)\\]\\]")

    /** The document id a `wiki:<id>` address names, or null for any other address. */
    fun targetId(address: String?): String? {
        if (address == null || !address.startsWith("wiki:", ignoreCase = true)) return null
        val raw = address.substring(5).trim()
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return decoded.lowercase().takeIf { uuid.matches(it) }
    }

    /** Whether the body links to [target] outside code. */
    fun links(body: DocumentBody, target: String): Boolean {
        val id = target.lowercase()
        val state = body.editorState
        if (state != null) return richLinks(state, id)
        val withoutCode = body.markdown.replace(fencedCode, " ").replace(inlineCode, " ")
        return markdownLink.findAll(withoutCode).any { targetId("wiki:" + it.groupValues[1]) == id }
    }

    private fun richLinks(node: JsonValue, id: String): Boolean {
        // A code block's text is not a link; its descendants are plain text.
        if (node["type"]?.stringValue == "codeBlock") return false
        if (node["type"]?.stringValue == "fdWikiLink" && node["attrs"]?.get("documentId")?.stringValue?.lowercase() == id) return true
        val marked = node["marks"]?.arrayValue?.any {
            it["type"]?.stringValue == "link" && targetId(it["attrs"]?.get("href")?.stringValue) == id &&
                node["marks"]?.arrayValue?.none { m -> m["type"]?.stringValue == "code" } == true
        } == true
        return marked || (node["content"]?.arrayValue?.any { richLinks(it, id) } == true)
    }

    // MARK: Syntax in rendered text

    /**
     * Splits literal `[[Title]]` out of plain text runs. Code, links, mentions and objects are left exactly as they are, and
     * runs that already carry a wiki target are not touched again.
     */
    fun expandLiterals(runs: List<InlineRun>): List<InlineRun> {
        val out = mutableListOf<InlineRun>()
        for (run in runs) {
            if (run.wiki != null || run.code || run.link != null || run.mention || run.`object` || run.image != null || !run.text.contains("[[")) {
                out.add(run); continue
            }
            var cursor = 0
            for (match in literal.findAll(run.text)) {
                val title = match.groupValues[1].trim()
                if (title.isEmpty()) continue
                if (match.range.first > cursor) out.add(run.copy(text = run.text.substring(cursor, match.range.first)))
                out.add(run.copy(text = title, wiki = WikiTarget(title = title)))
                cursor = match.range.last + 1
            }
            if (cursor == 0) { out.add(run); continue }
            if (cursor < run.text.length) out.add(run.copy(text = run.text.substring(cursor)))
        }
        return out
    }

    /** Numbers each title-only reference among the same-title title-only references in reading order. */
    fun numbered(blocks: List<RenderBlock>): List<RenderBlock> {
        val counts = mutableMapOf<String, Int>()
        val totals = mutableMapOf<String, Int>()
        fun tally(list: List<InlineRun>) { for (run in list) run.wiki?.takeIf { it.documentId == null }?.let { totals.merge(normalized(it.title), 1, Int::plus) } }
        fun count(blocks: List<RenderBlock>) {
            for (block in blocks) when (block) {
                is RenderBlock.Heading -> tally(block.runs)
                is RenderBlock.Paragraph -> tally(block.runs)
                is RenderBlock.Quote -> count(block.blocks)
                is RenderBlock.ListBlock -> block.items.forEach { count(it.blocks) }
                is RenderBlock.Table -> block.rows.forEach { row -> row.forEach { tally(it) } }
                else -> {}
            }
        }
        count(blocks)
        fun runs(list: List<InlineRun>): List<InlineRun> = list.map { run ->
            val wiki = run.wiki
            if (wiki == null || wiki.documentId != null) return@map run
            val key = normalized(wiki.title)
            val numbered = wiki.copy(occurrence = counts[key] ?: 0, count = totals[key] ?: 0)
            counts[key] = (counts[key] ?: 0) + 1
            run.copy(wiki = numbered)
        }
        fun walk(blocks: List<RenderBlock>): List<RenderBlock> = blocks.map { block ->
            when (block) {
                is RenderBlock.Heading -> block.copy(runs = runs(block.runs))
                is RenderBlock.Paragraph -> block.copy(runs = runs(block.runs))
                is RenderBlock.Quote -> RenderBlock.Quote(walk(block.blocks))
                is RenderBlock.ListBlock -> block.copy(items = block.items.map { it.copy(blocks = walk(it.blocks)) })
                is RenderBlock.Table -> RenderBlock.Table(block.rows.map { row -> row.map { runs(it) } })
                else -> block
            }
        }
        return walk(blocks)
    }

    /** Shows each bound link under its page's current title (after a rename), leaving the stored text alone. A link to an unsaved page keeps the title it was written with. */
    fun retitled(blocks: List<RenderBlock>, docs: List<DocRecord>): List<RenderBlock> {
        val titles = docs.filter { !it.archived }.associate { it.id to it.title }
        fun runs(list: List<InlineRun>): List<InlineRun> = list.map { run ->
            val id = run.wiki?.documentId
            val current = id?.let { titles[it] }
            if (current != null && current != run.text) run.copy(text = current) else run
        }
        fun walk(blocks: List<RenderBlock>): List<RenderBlock> = blocks.map { block ->
            when (block) {
                is RenderBlock.Heading -> block.copy(runs = runs(block.runs))
                is RenderBlock.Paragraph -> block.copy(runs = runs(block.runs))
                is RenderBlock.Quote -> RenderBlock.Quote(walk(block.blocks))
                is RenderBlock.ListBlock -> block.copy(items = block.items.map { it.copy(blocks = walk(it.blocks)) })
                is RenderBlock.Table -> RenderBlock.Table(block.rows.map { row -> row.map { runs(it) } })
                else -> block
            }
        }
        return walk(blocks)
    }

    // MARK: Resolution

    fun normalized(title: String): String = title.trim().lowercase()

    /**
     * Resolves one reference. Ids are looked up by id alone, whatever channel or scope the document is in now; a title is
     * matched only among the source channel's pages (default local matching).
     */
    fun resolve(target: WikiTarget, docs: List<DocRecord>, sourceChannelId: String?): WikiResolution {
        target.documentId?.let { id ->
            docs.firstOrNull { it.id == id && !it.archived }?.let { return WikiResolution.Available(it) }
            return WikiResolution.Unavailable(id)
        }
        val wanted = normalized(target.title)
        val local = docs.filter { !it.archived && it.channelId == sourceChannelId && normalized(it.title) == wanted }
        return when (local.size) {
            0 -> WikiResolution.Unresolved(target.title)
            1 -> WikiResolution.Available(local[0])
            else -> WikiResolution.Ambiguous(target.title, local)
        }
    }

    /**
     * Picker search over readable documents saved here. By default only the source channel's pages; [everywhere] adds the other
     * channels and scopes. Same-title pages are all returned so the person chooses; the current document is not offered.
     */
    fun search(query: String, docs: List<DocRecord>, sourceChannelId: String?, excluding: String? = null, everywhere: Boolean, limit: Int = 30): List<DocRecord> {
        val q = normalized(query)
        val pool = docs.filter { doc ->
            !doc.archived && doc.id != excluding && (everywhere || doc.channelId == sourceChannelId) && (q.isEmpty() || doc.title.lowercase().contains(q))
        }
        val ranked = pool.sortedWith { a, b ->
            val aLocal = a.channelId == sourceChannelId
            val bLocal = b.channelId == sourceChannelId
            if (aLocal != bLocal) return@sortedWith if (aLocal) -1 else 1
            val aExact = normalized(a.title) == q
            val bExact = normalized(b.title) == q
            if (aExact != bExact) return@sortedWith if (aExact) -1 else 1
            val byTitle = a.title.compareTo(b.title, ignoreCase = true)
            if (byTitle != 0) byTitle else a.id.compareTo(b.id)
        }
        return ranked.take(limit)
    }

    /** Every wiki reference in a body: rich nodes, canonical Markdown links and literal `[[Title]]`, never from code. */
    fun references(body: DocumentBody): List<WikiTarget> {
        val out = mutableListOf<WikiTarget>()
        fun collect(blocks: List<RenderBlock>) {
            fun runs(list: List<InlineRun>) { list.forEach { run -> run.wiki?.let { out.add(it) } } }
            for (block in blocks) when (block) {
                is RenderBlock.Heading -> runs(block.runs)
                is RenderBlock.Paragraph -> runs(block.runs)
                is RenderBlock.Quote -> collect(block.blocks)
                is RenderBlock.ListBlock -> block.items.forEach { collect(it.blocks) }
                is RenderBlock.Table -> block.rows.forEach { row -> row.forEach { runs(it) } }
                else -> {}
            }
        }
        collect(DocumentRender.blocks(body))
        return out
    }

    // MARK: Binding a created or chosen page into its source

    /**
     * Binds unresolved references titled [title] in a rich document to [documentId]. Nothing else changes; code, bound links and
     * other titles are left untouched. [occurrence] limits the change to the Nth matching reference in document order (the one
     * that was opened); null binds every match. Returns null when nothing needed binding.
     */
    fun binding(title: String, documentId: String, inEditorState: JsonValue, occurrence: Int? = null): JsonValue? {
        val wanted = normalized(title)
        var changed = false
        var seen = 0
        fun wants(): Boolean { val here = seen; seen++; return occurrence == null || occurrence == here }
        val plainMarks = setOf("bold", "italic", "strike", "underline")
        /** A text node holding literal `[[Title]]` becomes text, a bound node, text; its marks carry onto the text parts. */
        fun expand(node: JsonValue): List<JsonValue> {
            val text = node["text"]?.stringValue
            if (node["type"]?.stringValue != "text" || text == null || !text.contains("[[") ||
                !(node["marks"]?.arrayValue ?: emptyList()).all { (it["type"]?.stringValue ?: "") in plainMarks }) return listOf(node)
            val parts = mutableListOf<JsonValue>()
            var cursor = 0
            for (range in literalRanges(text, emptyList())) {
                val name = text.substring(range.first + 2, range.last + 1 - 2).trim()
                if (normalized(name) != wanted || !wants()) continue
                if (range.first > cursor) parts.add(node.setting("text", JsonValue.Str(text.substring(cursor, range.first))))
                parts.add(JsonValue.obj("type" to JsonValue.Str("fdWikiLink"),
                    "attrs" to JsonValue.obj("documentId" to JsonValue.Str(documentId), "title" to JsonValue.Str(name))))
                cursor = range.last + 1
                changed = true
            }
            if (cursor == 0) return listOf(node)
            if (cursor < text.length) parts.add(node.setting("text", JsonValue.Str(text.substring(cursor))))
            return parts
        }
        fun visit(node: JsonValue, inCode: Boolean): JsonValue {
            val type = node["type"]?.stringValue
            if (type == "fdWikiLink" && node["attrs"]?.get("documentId")?.stringValue.isNullOrEmpty() &&
                normalized(node["attrs"]?.get("title")?.stringValue ?: "") == wanted) {
                if (!wants()) return node
                changed = true
                return node.setting("attrs", (node["attrs"] ?: JsonValue.Obj(emptyList())).setting("documentId", JsonValue.Str(documentId)))
            }
            val content = node["content"]?.arrayValue ?: return node
            val code = inCode || type == "codeBlock"
            val next = if (code) content else content.flatMap { child -> expand(visit(child, code)) }
            return if (next == content) node else node.setting("content", JsonValue.Arr(next))
        }
        val result = visit(inEditorState, false)
        return if (changed) result else null
    }

    /** Markdown equivalent: `[[Title]]` outside code becomes `[Title](wiki:<id>)`; bound links stay as they are. */
    fun binding(title: String, documentId: String, inMarkdown: String, occurrence: Int? = null): String? {
        val wanted = normalized(title)
        val out = StringBuilder()
        var cursor = 0
        var seen = 0
        for (range in literalRanges(inMarkdown, markdownCodeRanges(inMarkdown))) {
            val name = inMarkdown.substring(range.first + 2, range.last + 1 - 2).trim()
            if (normalized(name) != wanted) continue
            val here = seen
            seen++
            if (occurrence != null && occurrence != here) continue
            out.append(inMarkdown, cursor, range.first)
            out.append(ProseMirrorMarkdown.wikiSource(JsonValue.obj("title" to JsonValue.Str(name), "documentId" to JsonValue.Str(documentId))))
            cursor = range.last + 1
        }
        if (cursor == 0) return null
        return out.append(inMarkdown, cursor, inMarkdown.length).toString()
    }

    /** `[[Title]]` ranges outside [mask], skipping an escaped `\[[` and a match that is itself link text (`[[X]](…)`). */
    internal fun literalRanges(text: String, mask: List<IntRange>): List<IntRange> =
        literal.findAll(text).map { it.range }.filter { range ->
            if (mask.any { it.first <= range.last && range.first <= it.last }) return@filter false
            if (range.first > 0 && text[range.first - 1] == '\\') return@filter false
            val end = range.last + 1
            if (end + 1 < text.length && text.substring(end, end + 2) == "](") return@filter false
            true
        }.toList()

    /**
     * Where Markdown treats text as code: fenced blocks (any fence length, backtick or tilde, closed only by a fence at least as
     * long and of the same kind, or the end of the text), lines indented four columns or more, and inline code spans (a backtick
     * run closed by a run of the same length). Indented lines are skipped even inside list continuations, which errs on the safe
     * side: such a reference stays literal rather than risk rewriting code.
     */
    internal fun markdownCodeRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        val inlineSpans = mutableListOf<IntArray>() // regions scanned for inline code: start, end (exclusive)
        var fence: Triple<Char, Int, Int>? = null // char, length, start
        var location = 0
        fun add(start: Int, endExclusive: Int) { if (endExclusive > start) ranges.add(start until endExclusive) }
        while (location <= text.length) {
            val newline = if (location <= text.length) text.indexOf('\n', location) else -1
            val lineEnd = if (newline >= 0) newline else text.length
            val line = text.substring(location, lineEnd)
            val next = minOf(lineEnd + 1, text.length + 1)
            val indent = line.takeWhile { it == ' ' }.length
            val body = line.drop(indent)
            val open = fence
            if (open != null) {
                val run = body.takeWhile { it == open.first }.length
                if (indent <= 3 && run >= open.second && body.drop(run).all { it == ' ' || it == '\t' }) {
                    add(open.third, minOf(next, text.length))
                    fence = null
                }
            } else if (indent <= 3 && body.isNotEmpty() && (body[0] == '`' || body[0] == '~')) {
                val first = body[0]
                val run = body.takeWhile { it == first }.length
                val info = body.drop(run)
                if (run >= 3 && (first == '~' || !info.contains('`'))) fence = Triple(first, run, location)
                else inlineSpans.add(intArrayOf(location, lineEnd))
            } else if (indent >= 4 || line.startsWith("\t")) {
                add(location, lineEnd)
            } else {
                inlineSpans.add(intArrayOf(location, lineEnd))
            }
            if (lineEnd >= text.length) break
            location = next
        }
        fence?.let { add(it.third, text.length) }
        // Inline code spans may continue across lines of one paragraph, so scan each run of consecutive prose lines together.
        val merged = mutableListOf<IntArray>()
        for (span in inlineSpans) {
            val last = merged.lastOrNull()
            if (last != null && last[1] + 1 == span[0]) last[1] = span[1] else merged.add(intArrayOf(span[0], span[1]))
        }
        for (region in merged) {
            var i = region[0]
            while (i < region[1]) {
                if (text[i] != '`') { i++; continue }
                if (i > region[0] && text[i - 1] == '\\') { i++; continue }
                var run = 0
                while (i + run < region[1] && text[i + run] == '`') run++
                var j = i + run
                var close: Int? = null
                while (j < region[1]) {
                    if (text[j] != '`') { j++; continue }
                    var n = 0
                    while (j + n < region[1] && text[j + n] == '`') n++
                    if (n == run) { close = j + n; break }
                    j += n
                }
                if (close != null) { add(i, close); i = close } else i += run
            }
        }
        return ranges
    }
}
