package studio.otherstuff.ambulando.core.docs

import java.util.UUID

/**
 * Port of the web editor's ProseMirror-to-Flight-Deck serializer: derives the Markdown `content` and the
 * compatibility `content_blocks` from a ProseMirror `editor_state`, exactly as the web editor does on save.
 * The ProseMirror JSON stays authoritative; this only regenerates the derived fields after a native edit.
 * Strings are handled as Unicode scalars, matching JavaScript's code-point regular expressions.
 */
object ProseMirrorMarkdown {
    const val CONTENT_FORMAT = "flightdeck_prosemirror_v1"
    const val EDITOR_STATE_FORMAT = "prosemirror_json_v1"
    const val EDITOR_STATE_VERSION = 1

    private val escapable: Set<Int> = "\\`*_{}[]()#+-.!|>&".codePoints().toArray().toSet()

    internal fun escapeText(value: String): String {
        val out = StringBuilder()
        value.codePoints().forEach { scalar ->
            if (scalar in escapable) out.append('\\')
            out.appendCodePoint(scalar)
        }
        return out.toString()
    }

    /** JavaScript `\s`. */
    internal fun isJSWhitespace(s: Int): Boolean = when (s) {
        in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        else -> false
    }

    /** `\p{L}` or `\p{N}`. */
    internal fun isLetterOrNumber(s: Int): Boolean = when (Character.getType(s).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
        Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private fun reference(s: Int): String = "&#$s;"

    internal fun trimEndJS(value: String): String {
        val scalars = TextSupport.scalars(value)
        var end = scalars.size
        while (end > 0 && isJSWhitespace(scalars[end - 1])) end--
        return TextSupport.fromScalars(scalars, 0, end)
    }

    internal fun trimJS(value: String): String {
        val scalars = TextSupport.scalars(value)
        var end = scalars.size
        while (end > 0 && isJSWhitespace(scalars[end - 1])) end--
        var start = 0
        while (start < end && isJSWhitespace(scalars[start])) start++
        return TextSupport.fromScalars(scalars, start, end)
    }

    internal data class MarkTextOptions(
        val atStart: Boolean = false,
        val atEnd: Boolean = false,
        val encodeFirst: Boolean = false,
        val encodeLast: Boolean = false,
    )

    private fun referenceScalars(s: Int): List<Int> = reference(s).codePoints().toArray().toList()

    internal fun markText(text: String, marks: List<JsonValue>, o: MarkTextOptions): String {
        // escapeText, then tab/CR/LF as character references.
        val escaped = StringBuilder()
        escapeText(text).codePoints().forEach { scalar ->
            if (scalar == '\t'.code || scalar == '\r'.code || scalar == '\n'.code) escaped.append(reference(scalar))
            else escaped.appendCodePoint(scalar)
        }
        var scalars: List<Int> = escaped.codePoints().toArray().toList()
        if (o.encodeFirst && scalars.isNotEmpty() && isLetterOrNumber(scalars.first())) {
            scalars = referenceScalars(scalars.first()) + scalars.drop(1)
        }
        if (o.encodeLast && scalars.isNotEmpty() && isLetterOrNumber(scalars.last())) {
            scalars = scalars.dropLast(1) + referenceScalars(scalars.last())
        }
        if (marks.isNotEmpty() || o.atStart) {
            var lead = 0
            while (lead < scalars.size && isJSWhitespace(scalars[lead])) lead++
            if (lead > 0) scalars = scalars.subList(0, lead).flatMap { referenceScalars(it) } + scalars.subList(lead, scalars.size)
        }
        if (marks.isNotEmpty() || o.atEnd) {
            var tail = scalars.size
            while (tail > 0 && isJSWhitespace(scalars[tail - 1])) tail--
            if (tail < scalars.size) scalars = scalars.subList(0, tail) + scalars.subList(tail, scalars.size).flatMap { referenceScalars(it) }
        }
        var out = TextSupport.fromScalars(scalars)
        for (mark in marks) {
            val attrs = mark["attrs"]
            when (mark["type"]?.stringValue) {
                "bold" -> out = "**$out**"
                "italic" -> out = "_${out}_"
                "strike" -> out = "~~$out~~"
                "underline" -> out = "<u>$out</u>"
                "code" -> out = "`" + text.replace("`", "\\`") + "`"
                "link" -> out = "[$out](${attrs?.get("href")?.stringValue ?: ""})"
                "fdMention" -> {
                    val label = nonEmpty(attrs?.get("label")) ?: text
                    out = "@[$label](mention:${nonEmpty(attrs?.get("mentionType")) ?: "record"}:${nonEmpty(attrs?.get("mentionId")) ?: ""})"
                }
                else -> {}
            }
        }
        return out
    }

    /** JavaScript truthiness for an optional string attribute (`attrs?.x || fallback`). */
    internal fun nonEmpty(value: JsonValue?): String? = when (value) {
        null -> null
        is JsonValue.Str -> value.value.ifEmpty { null }
        is JsonValue.Num -> if (value.lexeme.toDoubleOrNull() == 0.0) null else value.lexeme
        is JsonValue.Bool -> if (value.value) "true" else null
        JsonValue.Null -> null
        else -> value.serializedString()
    }

    fun truthy(value: JsonValue?): Boolean = when (value) {
        null, JsonValue.Null -> false
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> (value.lexeme.toDoubleOrNull() ?: 0.0) != 0.0
        is JsonValue.Str -> value.value.isNotEmpty()
        else -> true
    }

    private val markOrder = listOf("code", "bold", "italic", "strike", "link", "fdMention", "underline")

    internal fun sortedMarks(marks: List<JsonValue>): List<JsonValue> {
        // Stable sort by rank; unknown marks rank -1 (first), as `indexOf` does.
        fun rank(m: JsonValue) = markOrder.indexOf(m["type"]?.stringValue ?: "")
        return marks.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    private fun marksKey(marks: List<JsonValue>): String = JsonValue.Arr(marks).serializedString()

    private class Run(val node: JsonValue, val type: String, var text: String, val marks: List<JsonValue>)

    fun inlineMarkdown(nodes: List<JsonValue>): String {
        val runs = mutableListOf<Run>()
        for (source in nodes) {
            val type = source["type"]?.stringValue ?: ""
            val marks = source["marks"]?.arrayValue ?: emptyList()
            val node = Run(source, type, source["text"]?.stringValue ?: "", if (type == "text") sortedMarks(marks) else marks)
            val previous = runs.lastOrNull()
            if (type == "text" && previous != null && previous.type == "text" && marksKey(previous.marks) == marksKey(node.marks)) {
                previous.text += node.text
            } else {
                runs.add(node)
            }
        }
        val rendered = runs.mapIndexed { index, run ->
            when (run.type) {
                "text" -> markText(run.text, run.marks, MarkTextOptions(
                    atStart = index == 0 || runs[index - 1].type == "hardBreak",
                    atEnd = index == runs.size - 1 || runs[index + 1].type == "hardBreak",
                    encodeFirst = index > 0 && runs[index - 1].marks.isNotEmpty(),
                    encodeLast = index < runs.size - 1 && runs[index + 1].marks.isNotEmpty(),
                ))
                "fdWikiLink" -> wikiSource(run.node["attrs"])
                "hardBreak" -> "<br>"
                "fdStorageImage", "image" -> {
                    val attrs = run.node["attrs"]
                    val objectId = nonEmpty(attrs?.get("objectId"))
                    val src = if (run.type == "fdStorageImage" && objectId != null) "storage://$objectId"
                        else nonEmpty(attrs?.get("src")) ?: (objectId?.let { "storage://$it" } ?: "")
                    "![${escapeText(nonEmpty(attrs?.get("alt")) ?: "")}]($src)"
                }
                "fdStorageFile" -> {
                    val attrs = run.node["attrs"]
                    val src = nonEmpty(attrs?.get("src")) ?: ""
                    val label = nonEmpty(attrs?.get("label")) ?: nonEmpty(attrs?.get("title")) ?: src.ifEmpty { null } ?: "File"
                    "[${escapeText(label)}]($src)"
                }
                else -> inlineMarkdown(run.node["content"]?.arrayValue ?: emptyList())
            }
        }.toMutableList()
        // Shared outer marks stay open across adjacent runs.
        val delimiters = mapOf("bold" to ("**" to "**"), "italic" to ("_" to "_"), "strike" to ("~~" to "~~"), "underline" to ("<u>" to "</u>"))
        for (index in 1 until runs.size) {
            if (runs[index - 1].type != "text" || runs[index].type != "text") continue
            val left = runs[index - 1].marks.reversed()
            val right = runs[index].marks.reversed()
            var opening = ""
            var closing = ""
            for (m in 0 until minOf(left.size, right.size)) {
                val pair = delimiters[left[m]["type"]?.stringValue ?: ""] ?: break
                if (left[m] != right[m]) break
                opening += pair.first
                closing = pair.second + closing
            }
            if (opening.isNotEmpty()) {
                // Delimiters are ASCII: trim scalars, never grapheme clusters (a combining mark may follow).
                val l = TextSupport.scalars(rendered[index - 1])
                rendered[index - 1] = TextSupport.fromScalars(l, 0, maxOf(0, l.size - closing.length))
                val r = TextSupport.scalars(rendered[index])
                rendered[index] = TextSupport.fromScalars(r, minOf(opening.length, r.size), r.size)
            }
        }
        return rendered.joinToString("")
    }

    /** `encodeURIComponent`. */
    internal fun encodeURIComponent(value: String): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF)
            if (c < 0x80 && allowed.indexOf(c.toChar()) >= 0) out.append(c.toChar())
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
        }
        return out.toString()
    }

    /** The web client's `wikiSource`. */
    internal fun wikiSource(attrs: JsonValue?): String {
        val label = nonEmpty(attrs?.get("title")) ?: "Untitled document"
        val documentId = nonEmpty(attrs?.get("documentId"))
        if (documentId != null) return "[${escapeText(label)}](wiki:${encodeURIComponent(documentId)})"
        return "[[$label]]"
    }

    private fun indent(value: String, spaces: Int): String {
        val prefix = " ".repeat(spaces)
        return value.split("\n").joinToString("\n") { prefix + it }
    }

    private fun listMarkdown(node: JsonValue, ordered: Boolean): String {
        val type = node["type"]?.stringValue
        val start = node["attrs"]?.get("start")?.let { if (truthy(it)) it.intValue else null } ?: 1
        return (node["content"]?.arrayValue ?: emptyList()).mapIndexed { index, item ->
            val marker = if (type == "taskList") "- [${if (truthy(item["attrs"]?.get("checked"))) "x" else " "}]"
                else if (ordered) "${index + start}." else "-"
            val width = if (type == "taskList") 2 else marker.length + 1
            val body = blockNodesMarkdown(item["content"]?.arrayValue ?: emptyList())
            val lines = body.split("\n")
            val first = lines.first()
            val rest = lines.drop(1)
            "$marker $first" + if (rest.isEmpty()) "" else "\n" + indent(rest.joinToString("\n"), width)
        }.joinToString("\n")
    }

    private fun tableMarkdown(node: JsonValue): String {
        val rows = node["content"]?.arrayValue ?: emptyList()
        if (rows.isEmpty()) return ""
        val cells = rows.map { row ->
            (row["content"]?.arrayValue ?: emptyList()).map { cell ->
                inlineMarkdown(cell["content"]?.arrayValue?.firstOrNull()?.get("content")?.arrayValue ?: emptyList())
            }
        }
        val width = maxOf(cells.maxOfOrNull { it.size } ?: 1, 1)
        fun normalize(row: List<String>) = (0 until width).map { if (it < row.size) row[it] else "" }
        val header = normalize(cells[0])
        val lines = mutableListOf("| ${header.joinToString(" | ")} |", "| ${header.joinToString(" | ") { "---" }} |")
        for (row in cells.drop(1)) lines.add("| ${normalize(row).joinToString(" | ")} |")
        return lines.joinToString("\n")
    }

    fun blockMarkdown(node: JsonValue): String {
        val content = node["content"]?.arrayValue ?: emptyList()
        return when (node["type"]?.stringValue) {
            "heading" -> {
                val level = node["attrs"]?.get("level")?.let { if (truthy(it)) it.intValue else null } ?: 1
                trimEndJS("${"#".repeat(maxOf(level, 0))} ${inlineMarkdown(content)}")
            }
            "paragraph" -> inlineMarkdown(content)
            "codeBlock" -> {
                val language = nonEmpty(node["attrs"]?.get("language")) ?: ""
                val text = content.joinToString("") { it["text"]?.stringValue ?: "" }
                "```$language\n$text\n```"
            }
            "blockquote" -> blockNodesMarkdown(content).split("\n").joinToString("\n") { trimEndJS("> $it") }
            "horizontalRule" -> "---"
            "bulletList", "taskList" -> listMarkdown(node, ordered = false)
            "orderedList" -> listMarkdown(node, ordered = true)
            "table" -> tableMarkdown(node)
            "fdStorageImage", "image", "fdStorageFile" -> inlineMarkdown(listOf(node))
            "fdUploadPlaceholder" -> ""
            else -> inlineMarkdown(content)
        }
    }

    private fun blockNodesMarkdown(nodes: List<JsonValue>): String =
        nodes.map { blockMarkdown(it) }.filter { trimJS(it).isNotEmpty() }.joinToString("\n\n")

    private fun compatibilityBlockType(node: JsonValue): String = when (val type = node["type"]?.stringValue) {
        "heading" -> "heading"
        "paragraph" -> "paragraph"
        "codeBlock" -> "code"
        "bulletList", "orderedList", "taskList" -> "list"
        null -> "markdown"
        else -> type.ifEmpty { "markdown" }
    }

    fun newBlockId(): String = "pm_" + UUID.randomUUID().toString().lowercase()

    private fun blockId(node: JsonValue): String {
        val attrs = node["attrs"]
        val raw = nonEmpty(attrs?.get("fdBlockId")) ?: nonEmpty(attrs?.get("id")) ?: ""
        val trimmed = trimJS(raw)
        return trimmed.ifEmpty { newBlockId() }
    }

    /** Markdown for the whole document. */
    fun markdown(state: JsonValue): String = blockNodesMarkdown(state["content"]?.arrayValue ?: emptyList())

    /** The six content-model fields the web editor writes, in its key order. */
    fun contentModelFields(editorState: JsonValue): List<Pair<String, JsonValue>> {
        val state = if (editorState["type"]?.stringValue == "doc") editorState
            else JsonValue.obj("type" to JsonValue.Str("doc"), "content" to JsonValue.Arr(emptyList()))
        val nodes = state["content"]?.arrayValue ?: emptyList()
        val blocks: List<JsonValue> = nodes.mapNotNull { node ->
            val id = blockId(node)
            val raw = blockMarkdown(node)
            if (trimJS(raw).isEmpty()) return@mapNotNull null
            var attrs = if (node["attrs"]?.isObject == true) node["attrs"]!! else JsonValue.Obj(emptyList())
            attrs = attrs.setting("pmNodeId", JsonValue.Str(id))
            JsonValue.obj("id" to JsonValue.Str(id), "type" to JsonValue.Str(compatibilityBlockType(node)),
                "text" to JsonValue.Str(raw), "attrs" to attrs)
        }
        return listOf(
            "content" to JsonValue.Str(blockNodesMarkdown(nodes)),
            "content_format" to JsonValue.Str(CONTENT_FORMAT),
            "content_blocks" to JsonValue.Arr(blocks),
            "editor_state" to state,
            "editor_state_format" to JsonValue.Str(EDITOR_STATE_FORMAT),
            "editor_state_version" to JsonValue.int(EDITOR_STATE_VERSION),
        )
    }
}
