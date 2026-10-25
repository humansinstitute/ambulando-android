package studio.otherstuff.ambulando.core.docs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Web-compatible selection coordinates. Positions count UTF-16 units and ProseMirror node boundaries,
 * never rendered Markdown characters. Extra version metadata is advisory; quote verification is mandatory.
 */
data class DocumentAnchor(
    val quote: String,
    val from: Int,
    val to: Int,
    val version: Int? = null,
    val line: Int? = null,
    val blockId: String? = null,
) {
    /** Comment `metadata` fields for this anchor (line anchors replace the offsets with line numbers). */
    val metadata: JsonObject
        get() = buildJsonObject {
            put("anchor_quote", quote)
            if (line != null) {
                put("anchor_line_number", line)
                put("anchor_end_line_number", line)
            } else {
                put("anchor_start_offset", from)
                put("anchor_end_offset", to)
            }
            if (version != null) put("anchor_doc_row_version", version)
            if (blockId != null) put("anchor_block_id", blockId)
        }

    companion object {
        /** Reads an anchor from comment metadata; null when it has no usable quote and coordinates. */
        fun fromMetadata(metadata: JsonObject): DocumentAnchor? {
            val quote = metadata.string("anchor_quote")?.takeIf { it.isNotEmpty() } ?: return null
            val from = metadata.int("anchor_start_offset")
            val to = metadata.int("anchor_end_offset")
            val version = metadata.int("anchor_doc_row_version")
            if (from != null && to != null && from >= 0 && to > from) {
                return DocumentAnchor(quote, from, to, version, blockId = metadata.string("anchor_block_id"))
            }
            val line = metadata.int("anchor_line_number")
            if (line != null && line > 0) return DocumentAnchor(quote, 0, quote.length, version, line = line)
            return null
        }

        /** The anchor of a stored comment's canonical JSON (`metadata` object), if any. */
        fun fromCommentRaw(raw: ByteArray): DocumentAnchor? = commentMetadata(raw)?.let { fromMetadata(it) }

        /** The quoted text of a stored comment's anchor, if any. */
        fun anchorQuote(commentRaw: ByteArray): String? = commentMetadata(commentRaw)?.string("anchor_quote")

        private fun commentMetadata(raw: ByteArray): JsonObject? = try {
            (Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject)?.get("metadata") as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A JSON number with an integral value (as `as? Int` accepts from a decoded JSON object). */
        private fun JsonObject.int(key: String): Int? {
            val primitive = get(key) as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            val d = primitive.doubleOrNull ?: return null
            if (Math.rint(d) != d || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
            return d.toInt()
        }
    }
}

/** A block's text with the ProseMirror position (or source line) where it starts. */
data class DocumentTextSpan(
    val path: List<Int>,
    val start: Int,
    val text: String,
    val line: Int? = null,
    val blockId: String? = null,
) {
    /** The anchor for a selection inside this span (UTF-16 range); null for empty or whitespace-only selections. */
    fun anchor(range: TextRange, version: Int?): DocumentAnchor? {
        if (range.location < 0 || range.length <= 0 || range.end > text.length) return null
        val quote = text.substring(range.location, range.end)
        if (TextSupport.isBlankText(quote)) return null
        return DocumentAnchor(quote, start + range.location, start + range.end, version, line, blockId)
    }
}

/** A resolved anchor: the span it lies in and the UTF-16 range inside that span's text. */
data class ResolvedAnchor(val span: DocumentTextSpan, val range: TextRange)

object DocumentAnchors {
    fun spans(state: JsonValue): List<DocumentTextSpan> {
        val out = mutableListOf<DocumentTextSpan>()
        fun size(node: JsonValue): Int {
            if (node["type"]?.stringValue == "text") return (node["text"]?.stringValue ?: "").length
            val children = node["content"]?.arrayValue ?: return 1
            return children.fold(2) { acc, child -> acc + size(child) }
        }
        fun walk(node: JsonValue, position: Int, path: List<Int>) {
            val children = node["content"]?.arrayValue ?: emptyList()
            if ((node["type"]?.stringValue ?: "") in setOf("paragraph", "heading", "codeBlock")) {
                // Inline atoms take one PM position. U+FFFC occupies that position in the native text bridge.
                val text = children.joinToString("") { child ->
                    when (child["type"]?.stringValue) {
                        "text" -> child["text"]?.stringValue ?: ""
                        "hardBreak" -> "\n"
                        else -> "￼"
                    }
                }
                val rootBlock = path.firstOrNull()?.let { index -> state["content"]?.arrayValue?.getOrNull(index) }
                val blockId = rootBlock?.get("attrs")?.get("fdBlockId")?.stringValue ?: rootBlock?.get("attrs")?.get("id")?.stringValue
                out.add(DocumentTextSpan(path, position + 1, text, blockId = blockId))
            }
            var offset = if (node["type"]?.stringValue == "doc") 0 else position + 1
            children.forEachIndexed { i, child -> walk(child, offset, path + i); offset += size(child) }
        }
        walk(state, 0, emptyList())
        return out
    }

    /** Markdown readers use the web's line/quote contract, never pretend Markdown offsets are PM positions. */
    fun markdownSpans(markdown: String, blocks: List<RenderBlock>): List<DocumentTextSpan> {
        val lines = markdown.split("\n")
        var cursor = 0
        val out = mutableListOf<DocumentTextSpan>()
        fun add(text: String, path: List<Int>) {
            if (text.isEmpty()) return
            // Resolve a rendered block to its source line, including inline formatting. If uncertain, keep it selectable
            // through the ordinary renderer without manufacturing a source coordinate.
            val line = lines.indices.firstOrNull { index ->
                index >= cursor && (lines[index].contains(text) || DocumentRender.markdownBlocks(lines[index]).any { block ->
                    when (block) {
                        is RenderBlock.Paragraph -> block.runs.joinToString("") { it.text } == text
                        is RenderBlock.Heading -> block.runs.joinToString("") { it.text } == text
                        else -> false
                    }
                })
            } ?: return
            cursor = line + 1
            out.add(DocumentTextSpan(path, 0, text, line = line + 1))
        }
        fun walk(block: RenderBlock, path: List<Int>) {
            when (block) {
                is RenderBlock.Paragraph -> if (block.runs.none { it.image != null }) add(block.runs.joinToString("") { it.text }, path)
                is RenderBlock.Heading -> if (block.runs.none { it.image != null }) add(block.runs.joinToString("") { it.text }, path)
                is RenderBlock.Quote -> block.blocks.forEachIndexed { i, child -> walk(child, path + i) }
                is RenderBlock.ListBlock -> block.items.forEachIndexed { i, item ->
                    item.blocks.forEachIndexed { j, child -> walk(child, path + listOf(i, j)) }
                }
                else -> {}
            }
        }
        blocks.forEachIndexed { i, block -> walk(block, listOf(i)) }
        return out
    }

    /** As in the web: exact offsets first, then a unique quote relocation; ambiguous/deleted text stays orphaned. */
    fun resolve(anchor: DocumentAnchor, spans: List<DocumentTextSpan>): ResolvedAnchor? {
        val candidates = spans.filter { anchor.line == null || anchor.line == it.line }
        for (span in candidates) {
            val range = TextRange(anchor.from - span.start, anchor.to - anchor.from)
            if (anchor.line == null && range.location >= 0 && range.end <= span.text.length &&
                span.text.substring(range.location, range.end) == anchor.quote) return ResolvedAnchor(span, range)
        }
        val matches = mutableListOf<ResolvedAnchor>()
        for (span in candidates) {
            var from = 0
            while (from < span.text.length) {
                val found = span.text.indexOf(anchor.quote, from)
                if (found < 0) break
                matches.add(ResolvedAnchor(span, TextRange(found, anchor.quote.length)))
                if (matches.size > 1) return null
                from = found + 1
            }
        }
        return matches.firstOrNull()
    }
}
