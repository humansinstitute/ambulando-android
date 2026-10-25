package studio.otherstuff.ambulando.core.docs

/**
 * Markdown → display blocks for chat messages and Markdown document bodies, following what the web client renders
 * with `marked` (GFM, `breaks: true`): ATX and setext headings, paragraphs whose single newlines are line breaks,
 * fenced and indented code, nested block quotes, nested ordered/unordered/task lists, pipe tables, rules and images.
 * Inline styles come from [MarkdownInline]; mention tokens (`@[Label](mention:type:id)`) and `storage://` references
 * become typed runs so they keep their identity and route.
 *
 * Pure and thread-safe: screens run it off the main thread and cache the result. It only reads the text; stored
 * bodies are never rewritten from these blocks.
 */
object MarkdownBlocks {
    /** Nesting beyond this (quotes in lists in quotes …) is shown as text rather than recursing further. */
    internal const val MAX_DEPTH = 16

    fun parse(text: String): List<RenderBlock> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        return blocks(normalized.split("\n").map { expandLeadingTabs(it) }, depth = 0)
    }

    /** True when a message body has nothing Markdown, a mention or a link could change, so it can be shown verbatim. */
    fun isPlainText(text: String): Boolean {
        for (c in text) {
            when (c) {
                '*', '_', '`', '#', '>', '|', '[', ']', '~', '!', '<', '\\', '@' -> return false
                else -> {}
            }
        }
        if (text.contains("://") || text.contains("mailto:")) return false
        for (line in text.split("\n").filter { it.isNotEmpty() }) {
            val trimmed = line.trimStart(' ', '\t')
            if (line.startsWith("    ") || line.startsWith("\t")) return false
            if (trimmed.startsWith("- ") || trimmed.startsWith("+ ") || trimmed == "-" || trimmed.startsWith("---") || trimmed.startsWith("===")) return false
            val digits = trimmed.takeWhile { it in '0'..'9' }
            if (digits.isNotEmpty() && digits.length <= 9) {
                val next = trimmed.getOrNull(digits.length)
                if (next == '.' || next == ')') return false
            }
        }
        return true
    }

    // Blocks

    internal fun blocks(lines: List<String>, depth: Int): List<RenderBlock> {
        if (depth > MAX_DEPTH) {
            val text = TextSupport.trimWhitespacesAndNewlines(lines.joinToString("\n"))
            return if (text.isEmpty()) emptyList() else listOf(RenderBlock.Paragraph(listOf(InlineRun(text))))
        }
        val out = mutableListOf<RenderBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isEmpty()) return
            out.addAll(paragraphBlocks(paragraph))
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isBlank(line)) { flush(); i++; continue }
            val indent = indentation(line)

            if (indent >= 4) {
                if (paragraph.isNotEmpty()) { paragraph.add(trimLeading(line)); i++; continue }   // lazy continuation
                val code = mutableListOf<String>()
                while (i < lines.size && (isBlank(lines[i]) || indentation(lines[i]) >= 4)) {
                    code.add(stripIndent(lines[i], 4)); i++
                }
                while (code.isNotEmpty() && isBlank(code.last())) code.removeAt(code.size - 1)
                out.add(RenderBlock.Code("", code.joinToString("\n")))
                continue
            }
            val fence = fenceOpen(line)
            if (fence != null) {
                flush()
                i++
                val code = mutableListOf<String>()
                while (i < lines.size) {
                    if (isFenceClose(lines[i], fence)) { i++; break }
                    code.add(stripIndent(lines[i], fence.indent)); i++
                }
                out.add(RenderBlock.Code(fence.info, code.joinToString("\n")))
                continue
            }
            val heading = atxHeading(line)
            if (heading != null) {
                flush()
                out.add(RenderBlock.Heading(heading.first, inline(heading.second)))
                i++; continue
            }
            if (paragraph.isNotEmpty()) {
                val level = setextLevel(line)
                if (level != null) {
                    out.add(RenderBlock.Heading(level, inline(paragraph.joinToString("\n"))))
                    paragraph.clear()
                    i++; continue
                }
            }
            if (isRule(line)) { flush(); out.add(RenderBlock.Rule); i++; continue }
            if (quoteContent(line) != null) {
                flush()
                val inner = mutableListOf<String>()
                while (i < lines.size) {
                    val content = quoteContent(lines[i])
                    if (content != null) { inner.add(content); i++; continue }
                    // Lazy continuation of a quoted paragraph.
                    val last = inner.lastOrNull()
                    if (!isBlank(lines[i]) && last != null && !isBlank(last) && !startsBlock(lines[i])) { inner.add(lines[i]); i++; continue }
                    break
                }
                out.add(RenderBlock.Quote(blocks(inner, depth + 1)))
                continue
            }
            if (i + 1 < lines.size) {
                val table = table(lines, i)
                if (table != null) {
                    flush()
                    out.add(RenderBlock.Table(table.first))
                    i = table.second
                    continue
                }
            }
            val marker = listMarker(line)
            if (marker != null && (paragraph.isEmpty() || (marker.content.isNotEmpty() && (!marker.ordered || marker.start == 1)))) {
                flush()
                val list = list(lines, i, marker, depth)
                out.add(list.first)
                i = list.second
                continue
            }
            paragraph.add(trimLeading(line))
            i++
        }
        flush()
        return out
    }

    /** A paragraph; a paragraph made only of images becomes image blocks (as the web shows them full width). */
    internal fun paragraphBlocks(lines: List<String>): List<RenderBlock> {
        val text = lines.joinToString("\n") { trimTrailing(it) }
        val runs = inline(text)
        val images = runs.filter { it.image != null }
        if (images.isNotEmpty() && runs.all { it.image != null || TextSupport.isBlankText(it.text) }) {
            return images.map { RenderBlock.Image(it.text, it.image ?: "") }
        }
        return listOf(RenderBlock.Paragraph(runs))
    }

    // Lists

    internal data class ListMarker(
        val ordered: Boolean,
        /** `-`, `*`, `+` for bullets; `.` or `)` for ordered lists. A different character starts a new list. */
        val delimiter: Char,
        val start: Int,
        val contentIndent: Int,
        val content: String,
    )

    internal fun listMarker(line: String): ListMarker? {
        val indent = indentation(line)
        if (indent > 3) return null
        val rest = line.substring(indent)
        val first = rest.firstOrNull() ?: return null
        var ordered = false
        var delimiter = first
        var start = 1
        var markerWidth = 1
        if (first == '-' || first == '*' || first == '+') {
            // nothing more
        } else if (first in '0'..'9') {
            val digits = rest.takeWhile { it in '0'..'9' }
            val d = rest.getOrNull(digits.length)
            if (digits.length > 9 || (d != '.' && d != ')')) return null
            ordered = true; delimiter = d!!; start = digits.toIntOrNull() ?: 1; markerWidth = digits.length + 1
        } else {
            return null
        }
        val after = rest.substring(markerWidth)
        if (after.isEmpty() || after.all { it == ' ' }) {
            return ListMarker(ordered, delimiter, start, indent + markerWidth + 1, "")
        }
        val spaces = after.takeWhile { it == ' ' }.length
        if (spaces < 1) return null
        // Five or more spaces: the content is indented code; the item content starts after one space.
        val used = if (spaces >= 5) 1 else spaces
        return ListMarker(ordered, delimiter, start, indent + markerWidth + used, after.substring(used))
    }

    internal fun list(lines: List<String>, index: Int, first: ListMarker, depth: Int): Pair<RenderBlock, Int> {
        val items = mutableListOf<RenderBlock.ListItem>()
        var marker = first
        var itemLines = mutableListOf(first.content)
        var i = index + 1
        fun finish() {
            val content = itemLines.toMutableList()
            var checked: Boolean? = null
            val task = content.firstOrNull()?.let { taskPrefix(it) }
            if (task != null) {
                checked = task.first
                content[0] = task.second
            }
            items.add(RenderBlock.ListItem(checked, blocks(content, depth + 1)))
        }
        while (i < lines.size) {
            val line = lines[i]
            if (isBlank(line)) { itemLines.add(""); i++; continue }
            val indent = indentation(line)
            if (indent >= marker.contentIndent) { itemLines.add(stripIndent(line, marker.contentIndent)); i++; continue }
            val next = listMarker(line)
            if (next != null && next.ordered == first.ordered && next.delimiter == first.delimiter && !isRule(line)) {
                finish()
                marker = next
                itemLines = mutableListOf(next.content)
                i++
                continue
            }
            val last = itemLines.lastOrNull()
            if (last != null && !isBlank(last) && !startsBlock(line)) { itemLines.add(trimLeading(line)); i++; continue }
            break
        }
        finish()
        return RenderBlock.ListBlock(first.ordered, first.start, items) to i
    }

    internal fun taskPrefix(line: String): Pair<Boolean, String>? {
        for ((prefix, checked) in listOf("[ ]" to false, "[x]" to true, "[X]" to true)) {
            if (!line.startsWith(prefix)) continue
            val rest = line.substring(prefix.length)
            if (rest.isNotEmpty() && rest[0] != ' ') return null
            return checked to rest.drop(1)
        }
        return null
    }

    // Tables

    internal fun table(lines: List<String>, index: Int): Pair<List<List<List<InlineRun>>>, Int>? {
        val header = lines[index]
        if (!header.contains("|") || indentation(header) > 3 || !isAlignmentRow(lines[index + 1])) return null
        val headerCells = cells(header)
        if (headerCells.size != cells(lines[index + 1]).size) return null
        val width = headerCells.size
        fun normalized(row: List<String>): List<List<InlineRun>> {
            val cells = row.take(width).toMutableList()
            while (cells.size < width) cells.add("")
            return cells.map { inline(it) }
        }
        val rows = mutableListOf(normalized(headerCells))
        var i = index + 2
        while (i < lines.size && !isBlank(lines[i]) && lines[i].contains("|") &&
            quoteContent(lines[i]) == null && fenceOpen(lines[i]) == null && atxHeading(lines[i]) == null) {
            rows.add(normalized(cells(lines[i])))
            i++
        }
        return rows to i
    }

    internal fun isAlignmentRow(line: String): Boolean {
        val parts = cells(line)
        if (parts.isEmpty() || !line.contains("-") || !(line.contains("|") || parts.size > 1)) return false
        return parts.all { part ->
            var cell = part
            if (cell.startsWith(":")) cell = cell.drop(1)
            if (cell.endsWith(":")) cell = cell.dropLast(1)
            cell.isNotEmpty() && cell.all { it == '-' }
        }
    }

    /** Cells of a pipe-table row; `\|` and pipes inside code spans stay in the cell. */
    internal fun cells(line: String): List<String> {
        var text = TextSupport.trimWhitespaces(line)
        if (text.startsWith("|")) text = text.drop(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        var escaped = false
        for (ch in text) {
            if (escaped) {
                if (ch == '|') current.append('|') else current.append('\\').append(ch)
                escaped = false
                continue
            }
            if (ch == '\\') { escaped = true; continue }
            if (ch == '`') inCode = !inCode
            if (ch == '|' && !inCode) { out.add(TextSupport.trimWhitespaces(current.toString())); current.clear(); continue }
            current.append(ch)
        }
        if (escaped) current.append('\\')
        out.add(TextSupport.trimWhitespaces(current.toString()))
        return out
    }

    // Line kinds

    internal data class Fence(val character: Char, val count: Int, val indent: Int, val info: String)

    internal fun fenceOpen(line: String): Fence? {
        val indent = indentation(line)
        if (indent > 3) return null
        val rest = line.substring(indent)
        val ch = rest.firstOrNull() ?: return null
        if (ch != '`' && ch != '~') return null
        val count = rest.takeWhile { it == ch }.length
        if (count < 3) return null
        val info = TextSupport.trimWhitespaces(rest.substring(count))
        if (ch == '`' && info.contains('`')) return null
        return Fence(ch, count, indent, info.split(' ').firstOrNull { it.isNotEmpty() } ?: "")
    }

    internal fun isFenceClose(line: String, fence: Fence): Boolean {
        val indent = indentation(line)
        if (indent > 3) return false
        val rest = line.substring(indent)
        val count = rest.takeWhile { it == fence.character }.length
        return count >= fence.count && rest.substring(count).all { it == ' ' }
    }

    private val closingSequence = Regex("(^|\\s)#+$")

    internal fun atxHeading(line: String): Pair<Int, String>? {
        val indent = indentation(line)
        if (indent > 3) return null
        val rest = line.substring(indent)
        val hashes = rest.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        val after = rest.substring(hashes)
        if (after.isNotEmpty() && after[0] != ' ') return null
        var text = TextSupport.trimWhitespaces(after)
        // Optional closing sequence: " ###".
        val match = closingSequence.find(text)
        if (match != null) text = TextSupport.trimWhitespaces(text.substring(0, match.range.first))
        return hashes to text
    }

    internal fun setextLevel(line: String): Int? {
        if (indentation(line) > 3) return null
        val text = TextSupport.trimWhitespaces(line)
        if (text.isNotEmpty() && text.all { it == '=' }) return 1
        if (text.length >= 2 && text.all { it == '-' }) return 2
        return null
    }

    internal fun isRule(line: String): Boolean {
        if (indentation(line) > 3) return false
        val chars = line.filter { it != ' ' && it != '\t' }
        if (chars.length < 3) return false
        val first = chars[0]
        if (first != '-' && first != '*' && first != '_') return false
        return chars.all { it == first }
    }

    internal fun quoteContent(line: String): String? {
        val indent = indentation(line)
        if (indent > 3) return null
        val rest = line.substring(indent)
        if (rest.firstOrNull() != '>') return null
        val after = rest.substring(1)
        return if (after.startsWith(" ")) after.substring(1) else after
    }

    /** Lines that end a paragraph (or a lazy continuation) by starting another block. */
    internal fun startsBlock(line: String): Boolean {
        if (isBlank(line) || fenceOpen(line) != null || atxHeading(line) != null || isRule(line) || quoteContent(line) != null) return true
        val marker = listMarker(line)
        return marker != null && marker.content.isNotEmpty() && (!marker.ordered || marker.start == 1)
    }

    internal fun isBlank(line: String): Boolean = line.all { it == ' ' || it == '\t' }

    internal fun indentation(line: String): Int = line.takeWhile { it == ' ' }.length

    internal fun stripIndent(line: String, count: Int): String = line.substring(minOf(count, indentation(line)))

    internal fun trimLeading(line: String): String = line.trimStart(' ', '\t')

    internal fun trimTrailing(line: String): String = line.trimEnd(' ', '\t')

    /** Tabs in leading whitespace count as four columns; tabs elsewhere (including inside code) are kept. */
    internal fun expandLeadingTabs(line: String): String {
        if (!(line.startsWith("\t") || (line.startsWith(" ") && line.contains('\t')))) return line
        val out = StringBuilder()
        var index = 0
        while (index < line.length && (line[index] == ' ' || line[index] == '\t')) {
            if (line[index] == '\t') repeat(4 - out.length % 4) { out.append(' ') } else out.append(' ')
            index++
        }
        return out.append(line, index, line.length).toString()
    }

    // Inline

    private val breakTag = Regex("<br\\s*/?\\s*>", RegexOption.IGNORE_CASE)
    private val bareURL = Regex("https?://[^\\s<>]+[^\\s<>.,;:!?\"')\\]]")

    /**
     * Inline runs. Line breaks are kept (the web renders single newlines as breaks). Mentions keep their kind and id;
     * `storage://` links and images keep their object reference; bare web addresses become links.
     */
    fun inline(text: String): List<InlineRun> {
        val source = breakTag.replace(text, "\n")
        val out = mutableListOf<InlineRun>()
        for (span in MarkdownInline.parse(source)) {
            var item = InlineRun(text = span.text, bold = span.strong, italic = span.emphasis, code = span.code, strike = span.strike)
            val image = span.image
            val link = span.link
            if (image != null) {
                item = item.copy(image = image, `object` = true)
            } else if (link != null) {
                if (link.lowercase().startsWith("mention:")) {
                    val parts = link.substring("mention:".length).split(":", limit = 2)
                    item = item.copy(mention = true, mentionKind = parts.firstOrNull()?.ifEmpty { null } ?: "unknown",
                        mentionId = if (parts.size > 1) parts[1] else "")
                    // `@[Label](mention:…)`: the `@` belongs to the mention, shown on people and agents only.
                    val last = out.lastOrNull()
                    val hadAt = last != null && !last.code && last.mentionId == null && last.text.endsWith("@")
                    if (hadAt) {
                        val previous = out.removeAt(out.size - 1)
                        val trimmed = previous.text.dropLast(1)
                        if (trimmed.isNotEmpty()) out.add(previous.copy(text = trimmed))
                    }
                    val before = out.lastOrNull()
                    val continuing = before != null && before.mentionId == item.mentionId && before.mentionKind == item.mentionKind
                    if (!continuing && (item.mentionKind == "person" || item.mentionKind == "agent")) item = item.copy(text = "@" + item.text)
                } else if (link.lowercase().startsWith("storage://")) {
                    item = item.copy(`object` = true, link = link)
                } else {
                    item = item.copy(link = link)
                }
            }
            out.add(item)
        }
        return autolinked(out)
    }

    /** Splits plain runs around bare http(s) addresses (GFM autolinks). */
    internal fun autolinked(runs: List<InlineRun>): List<InlineRun> {
        val out = mutableListOf<InlineRun>()
        for (run in runs) {
            if (run.link != null || run.code || run.mention || run.image != null || !run.text.contains("://")) { out.add(run); continue }
            var cursor = 0
            for (match in bareURL.findAll(run.text)) {
                if (match.range.first > cursor) out.add(run.copy(text = run.text.substring(cursor, match.range.first)))
                out.add(run.copy(text = match.value, link = match.value))
                cursor = match.range.last + 1
            }
            if (cursor == 0) { out.add(run); continue }
            if (cursor < run.text.length) out.add(run.copy(text = run.text.substring(cursor)))
        }
        return out
    }
}
