package studio.otherstuff.ambulando.core.docs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Bytes to upload for a body, with the content type they are stored under. */
class EncodedBody(val bytes: ByteArray, val contentType: String) {
    override fun equals(other: Any?): Boolean =
        other is EncodedBody && bytes.contentEquals(other.bytes) && contentType == other.contentType

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + contentType.hashCode()
}

/**
 * A document body as stored in Tower (`GET /docs/:d/body`), decoded the way the web client reads it (stored content
 * with legacy models allowed, plus raw Markdown bodies), and re-encoded in the **same representation** it came from.
 * Nothing outside the fields a native edit touches is rewritten.
 */
class DocumentBody private constructor(
    val representation: Representation?,
    val contentType: String,
    /** Parsed root (JSON representations only). */
    val root: JsonValue?,
    /** Raw Markdown text (raw representation only). */
    val markdownText: String?,
    val editing: Editing,
) {
    enum class Representation {
        /** `{format: "document_content_v1", content_model: {...}}` (current web format). */
        ENVELOPE,
        /** `{content_model: {...}}` without a format discriminator (legacy). */
        LEGACY_WRAPPED,
        /** The content model itself at the root (legacy). */
        LEGACY_MODEL,
        /** Plain Markdown bytes. */
        RAW_MARKDOWN,
    }

    sealed class Editing {
        /** ProseMirror `editor_state` is authoritative; edits change it in place. */
        data object ProseMirror : Editing()
        /** A content model with Markdown `content` only (no editor state). */
        data object MarkdownModel : Editing()
        /** Raw Markdown bytes. */
        data object Markdown : Editing()
        /** Not a format this app can edit without loss; shown read-only. */
        data class ReadOnly(val reason: String) : Editing()
    }

    /** Content model object (JSON representations). */
    val contentModel: JsonValue?
        get() {
            val root = root ?: return null
            return if (representation == Representation.LEGACY_MODEL) root else root["content_model"]
        }

    /** The ProseMirror document, when it is authoritative. */
    val editorState: JsonValue?
        get() = if (editing == Editing.ProseMirror) contentModel?.get("editor_state") else null

    /** Markdown text for display, search and Markdown editing. */
    val markdown: String
        get() {
            markdownText?.let { return it }
            editorState?.let { return ProseMirrorMarkdown.markdown(it) }
            return contentModel?.get("content")?.stringValue ?: ""
        }

    val isEditable: Boolean get() = editing !is Editing.ReadOnly

    // Encode (same representation)

    /**
     * Bytes for a body whose ProseMirror document became `state`. Derived fields are regenerated with the web
     * serializer; unknown model and envelope keys keep their positions and values.
     */
    fun encoded(editorState: JsonValue): EncodedBody {
        val model = contentModel
        if (editing != Editing.ProseMirror || model == null) throw DocumentEditError(DocumentEditError.Reason.UNSUPPORTED)
        var next: JsonValue = model
        for ((key, value) in ProseMirrorMarkdown.contentModelFields(editorState)) next = next.setting(key, value)
        return EncodedBody(replacingModel(next).serialized(), outgoingContentType)
    }

    /** Bytes for a Markdown edit (Markdown model or raw Markdown). */
    fun encoded(markdown: String): EncodedBody = when (editing) {
        Editing.Markdown -> EncodedBody(markdown.toByteArray(Charsets.UTF_8), contentType.ifEmpty { "text/markdown" })
        Editing.MarkdownModel -> {
            val model = contentModel ?: throw DocumentEditError(DocumentEditError.Reason.UNSUPPORTED)
            var next = model.setting("content", JsonValue.Str(markdown))
            // Blocks derived from the previous Markdown would describe text that no longer exists. The web client
            // re-derives them from `content` when it opens a model without an editor state.
            if (next["content_blocks"] != null) next = next.setting("content_blocks", JsonValue.Arr(emptyList()))
            EncodedBody(replacingModel(next).serialized(), outgoingContentType)
        }
        else -> throw DocumentEditError(DocumentEditError.Reason.UNSUPPORTED)
    }

    private val outgoingContentType: String
        get() = if (representation == Representation.ENVELOPE) ENVELOPE_CONTENT_TYPE else contentType.ifEmpty { ENVELOPE_CONTENT_TYPE }

    private fun replacingModel(model: JsonValue): JsonValue {
        val root = root ?: return model
        return if (representation == Representation.LEGACY_MODEL) model else root.setting("content_model", model)
    }

    override fun equals(other: Any?): Boolean = other is DocumentBody && representation == other.representation &&
        contentType == other.contentType && root == other.root && markdownText == other.markdownText && editing == other.editing

    override fun hashCode(): Int = listOf(representation, contentType, root, markdownText, editing).hashCode()

    companion object {
        const val ENVELOPE_FORMAT = "document_content_v1"
        const val ENVELOPE_CONTENT_TYPE = "application/vnd.wingman.flightdeck.document-content+json"

        internal fun isModel(value: JsonValue?): Boolean {
            if (value == null || !value.isObject) return false
            return value["content"]?.stringValue != null || value["editor_state"]?.get("type")?.stringValue == "doc"
        }

        /** Decodes stored bytes. Never throws: anything unrecognised becomes a read-only body. */
        operator fun invoke(bytes: ByteArray, contentType: String?): DocumentBody {
            val type = (contentType ?: "").lowercase()
            val parsed = JsonValue.parseOrNull(bytes)
            if (parsed != null) {
                if (parsed["format"]?.stringValue == ENVELOPE_FORMAT && isModel(parsed["content_model"])) {
                    return fromJson(parsed, Representation.ENVELOPE, contentType)
                }
                if (parsed.isObject && parsed["format"] == null && isModel(parsed["content_model"])) {
                    return fromJson(parsed, Representation.LEGACY_WRAPPED, contentType)
                }
                if (isModel(parsed)) return fromJson(parsed, Representation.LEGACY_MODEL, contentType)
            }
            if (type.contains("markdown") || type.startsWith("text/plain")) {
                val text = strictUtf8(bytes)
                if (text != null) return DocumentBody(Representation.RAW_MARKDOWN, contentType ?: "", null, text, Editing.Markdown)
            }
            return DocumentBody(null, contentType ?: "", null, null, Editing.ReadOnly(
                if (type.isEmpty()) "This document's stored format is not recognised."
                else "This document is stored as $type, which this app can only show, not edit."))
        }

        private fun fromJson(root: JsonValue, representation: Representation, contentType: String?): DocumentBody {
            val model = if (representation == Representation.LEGACY_MODEL) root else root["content_model"]
            val editing = when {
                model?.get("editor_state")?.get("type")?.stringValue == "doc" -> Editing.ProseMirror
                model?.get("content")?.stringValue != null -> Editing.MarkdownModel
                else -> Editing.ReadOnly("This document's content model is not recognised.")
            }
            return DocumentBody(representation, contentType ?: "", root, null, editing)
        }

        private fun strictUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }

        /** Node types of the web document schema (the web editor's extension set). */
        val schemaNodeTypes: Set<String> = setOf(
            "doc", "text", "paragraph", "heading", "blockquote", "codeBlock", "horizontalRule", "hardBreak",
            "bulletList", "orderedList", "listItem", "taskList", "taskItem", "table", "tableRow", "tableHeader", "tableCell",
            "image", "fdStorageImage", "fdStorageFile", "fdUploadPlaceholder", "fdWikiLink",
        )

        /**
         * Checks a ProseMirror document coming back from the editor page before it is drafted or saved: a `doc` whose
         * content is a list of nodes, none of the page's kept-content stand-ins left, and no node type that is neither in
         * the web schema nor already in the stored document (`original`). Anything else means content was being rewritten.
         */
        fun checkEdited(state: JsonValue, original: JsonValue?) {
            if (state["type"]?.stringValue != "doc" || state["content"]?.arrayValue == null) {
                throw DocumentEditError(DocumentEditError.Reason.INTEGRITY)
            }
            val allowed = schemaNodeTypes.toMutableSet()
            if (original != null) allowed.addAll(nodeTypes(original))
            val found = nodeTypes(state)
            if (found.any { it == "fdKeptBlock" || it == "fdKeptInline" } || !allowed.containsAll(found)) {
                throw DocumentEditError(DocumentEditError.Reason.INTEGRITY)
            }
        }

        internal fun nodeTypes(node: JsonValue): Set<String> {
            val out = mutableSetOf<String>()
            fun walk(n: JsonValue) {
                n["type"]?.stringValue?.let { out.add(it) }
                for (child in n["content"]?.arrayValue ?: emptyList()) walk(child)
            }
            walk(node)
            return out
        }

        /** A new document body in the current web format, built from plain paragraphs. */
        fun newDocument(text: String): EncodedBody {
            val paragraphs = text.split("\n\n").map { TextSupport.trimNewlines(it) }.filter { it.isNotEmpty() }
            val nodes = paragraphs.map { DocumentEditor.paragraph(it, topLevel = true) }
            val state = JsonValue.obj("type" to JsonValue.Str("doc"),
                "content" to JsonValue.Arr(nodes.ifEmpty { listOf(DocumentEditor.paragraph("", topLevel = true)) }))
            val model = JsonValue.Obj(ProseMirrorMarkdown.contentModelFields(state))
            val root = JsonValue.obj("format" to JsonValue.Str(ENVELOPE_FORMAT), "content_model" to model)
            return EncodedBody(root.serialized(), ENVELOPE_CONTENT_TYPE)
        }
    }
}

/** Why a document edit was refused; the message is shown to the user. */
class DocumentEditError(val reason: Reason) : Exception(reason.description), UserFacingError {
    override val userMessage: String get() = reason.description

    enum class Reason(val description: String) {
        UNSUPPORTED("This document cannot be edited in this app without losing content."),
        INVALID_PATH("That part of the document changed; reopen the editor."),
        INTEGRITY("The editor returned a document that would lose content, so it was not kept. Your last good draft is unchanged."),
    }

    override fun equals(other: Any?): Boolean = other is DocumentEditError && other.reason == reason
    override fun hashCode(): Int = reason.hashCode()
}

/** One editable text block (paragraph, heading or code block) anywhere in the ProseMirror tree. */
data class EditableBlock(
    /** Indices through `content` arrays from the document root. */
    val path: List<Int>,
    val type: String,
    val level: Int?,
    /** Nearest container kind (list item, task item, quote, table cell) for display. */
    val container: String?,
    val taskChecked: Boolean?,
    /** Inline content as text: hard breaks are `\n`; other inline nodes (images, wiki links, files) are U+FFFC. */
    val text: String,
    /** True when the block has formatting or inline objects that the plain-text field shows only approximately. */
    val rich: Boolean,
) {
    val id: String get() = path.joinToString(".")
}

/**
 * Structural edits on a ProseMirror document. Every operation rebuilds only the touched node; marks, attributes
 * (including block ids that comment anchors use) and nodes the app does not understand are carried unchanged.
 */
object DocumentEditor {
    const val OBJECT_REPLACEMENT: Char = '￼'
    private val textBlockTypes = setOf("paragraph", "heading", "codeBlock")

    fun paragraph(text: String, topLevel: Boolean): JsonValue {
        val pairs = mutableListOf<Pair<String, JsonValue>>("type" to JsonValue.Str("paragraph"))
        if (topLevel) pairs.add("attrs" to JsonValue.obj("fdBlockId" to JsonValue.Str(ProseMirrorMarkdown.newBlockId())))
        val inline = inlineNodes(text, marks = emptyList(), code = false)
        if (inline.isNotEmpty()) pairs.add("content" to JsonValue.Arr(inline))
        return JsonValue.Obj(pairs)
    }

    /** Editable text blocks in document order. */
    fun blocks(state: JsonValue): List<EditableBlock> {
        val out = mutableListOf<EditableBlock>()
        fun walk(node: JsonValue, path: List<Int>, container: String?, checked: Boolean?) {
            val type = node["type"]?.stringValue ?: ""
            if (type in textBlockTypes) {
                val content = node["content"]?.arrayValue ?: emptyList()
                val rich = content.any { child ->
                    val t = child["type"]?.stringValue
                    (t == "text" && (child["marks"]?.arrayValue ?: emptyList()).isNotEmpty()) || (t != "text" && t != "hardBreak")
                }
                out.add(EditableBlock(path, type, if (type == "heading") node["attrs"]?.get("level")?.intValue ?: 1 else null,
                    container, checked, plainText(content), rich))
                return
            }
            val nextContainer = when (type) {
                "listItem" -> "listItem"
                "taskItem" -> "taskItem"
                "blockquote" -> "blockquote"
                "tableCell", "tableHeader" -> "tableCell"
                else -> container
            }
            val nextChecked = if (type == "taskItem") ProseMirrorMarkdown.truthy(node["attrs"]?.get("checked")) else checked
            (node["content"]?.arrayValue ?: emptyList()).forEachIndexed { index, child -> walk(child, path + index, nextContainer, nextChecked) }
        }
        (state["content"]?.arrayValue ?: emptyList()).forEachIndexed { index, child -> walk(child, listOf(index), null, null) }
        return out
    }

    internal fun plainText(content: List<JsonValue>): String = content.joinToString("") { node ->
        when (node["type"]?.stringValue) {
            "text" -> node["text"]?.stringValue ?: ""
            "hardBreak" -> "\n"
            else -> OBJECT_REPLACEMENT.toString()
        }
    }

    // Path access

    fun node(root: JsonValue, path: List<Int>): JsonValue? {
        var current = root
        for (index in path) {
            val children = current["content"]?.arrayValue ?: return null
            if (index !in children.indices) return null
            current = children[index]
        }
        return current
    }

    internal fun replacing(root: JsonValue, path: List<Int>, transform: (JsonValue) -> List<JsonValue>): JsonValue {
        val first = path.firstOrNull() ?: throw DocumentEditError(DocumentEditError.Reason.INVALID_PATH)
        val children = root["content"]?.arrayValue?.toMutableList() ?: throw DocumentEditError(DocumentEditError.Reason.INVALID_PATH)
        if (first !in children.indices) throw DocumentEditError(DocumentEditError.Reason.INVALID_PATH)
        if (path.size == 1) {
            val replacement = transform(children[first])
            children.removeAt(first)
            children.addAll(first, replacement)
        } else {
            children[first] = replacing(children[first], path.drop(1), transform)
        }
        return root.setting("content", JsonValue.Arr(children))
    }

    // Text edits

    /**
     * Replaces the text of the block at `path`. Only the changed span is rebuilt: text before and after keeps its
     * marks; inserted text takes the marks of the text it follows (links and mentions are not extended past their end).
     */
    fun setText(state: JsonValue, path: List<Int>, text: String): JsonValue = replacing(state, path) { node ->
        val type = node["type"]?.stringValue ?: ""
        if (type !in textBlockTypes) throw DocumentEditError(DocumentEditError.Reason.INVALID_PATH)
        val content = node["content"]?.arrayValue ?: emptyList()
        val rebuilt = edit(content, text, code = type == "codeBlock")
        listOf(node.setting("content", if (rebuilt.isEmpty()) null else JsonValue.Arr(rebuilt)))
    }

    private class Segment(val node: JsonValue, val scalars: IntArray, val isText: Boolean)

    private fun segments(content: List<JsonValue>): List<Segment> = content.map { node ->
        when (node["type"]?.stringValue) {
            "text" -> Segment(node, TextSupport.scalars(node["text"]?.stringValue ?: ""), true)
            "hardBreak" -> Segment(node, intArrayOf('\n'.code), false)
            else -> Segment(node, intArrayOf(OBJECT_REPLACEMENT.code), false)
        }
    }

    internal fun edit(content: List<JsonValue>, newText: String, code: Boolean): List<JsonValue> {
        val segs = segments(content)
        val old = segs.flatMap { it.scalars.asList() }
        val new = TextSupport.scalars(newText)
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
        val deleteEnd = old.size - suffix
        val inserted = TextSupport.fromScalars(new, prefix, new.size - suffix)
        if (prefix == old.size && suffix == 0 && inserted.isEmpty()) return content

        // Locate the segment holding a scalar index in the old text.
        val bounds = mutableListOf<IntArray>()
        var offset = 0
        for (seg in segs) { bounds.add(intArrayOf(offset, offset + seg.scalars.size)); offset += seg.scalars.size }
        fun segment(at: Int): Int? = bounds.indexOfFirst { it[0] <= at && at < it[1] }.takeIf { it >= 0 }

        // Marks for inserted text, as a rich editor would choose them: replacement text takes the marks of what it
        // replaces; otherwise it continues the text before it (not extending a link or mention past its end);
        // otherwise it takes the text after it (minus link and mention).
        val unextendable = setOf("link", "fdMention")
        fun marks(i: Int): List<JsonValue> = segs[i].node["marks"]?.arrayValue ?: emptyList()
        var inherited: List<JsonValue> = emptyList()
        val replaced = if (deleteEnd > prefix) segment(prefix) else null
        val before = if (prefix > 0) segment(prefix - 1) else null
        val after = segment(deleteEnd)
        if (replaced != null && segs[replaced].isText) {
            inherited = marks(replaced)
        } else if (before != null && segs[before].isText) {
            inherited = marks(before)
            if (bounds[before][1] == prefix) inherited = inherited.filter { (it["type"]?.stringValue ?: "") !in unextendable }
        } else if (after != null && segs[after].isText) {
            inherited = marks(after).filter { (it["type"]?.stringValue ?: "") !in unextendable }
        }

        val head = mutableListOf<JsonValue>()
        val tail = mutableListOf<JsonValue>()
        segs.forEachIndexed { i, seg ->
            val (start, end) = bounds[i].let { it[0] to it[1] }
            if (end <= prefix) { head.add(seg.node); return@forEachIndexed }
            if (start >= deleteEnd) { tail.add(seg.node); return@forEachIndexed }
            // Overlaps the edited span. An object or hard break is one scalar wide, so it lies wholly inside the
            // span and is removed; a text node keeps the parts outside it, with its marks.
            if (!seg.isText) return@forEachIndexed
            if (start < prefix) head.add(seg.node.setting("text", JsonValue.Str(TextSupport.fromScalars(seg.scalars, 0, prefix - start))))
            if (end > deleteEnd) tail.add(seg.node.setting("text", JsonValue.Str(TextSupport.fromScalars(seg.scalars, deleteEnd - start, seg.scalars.size))))
        }
        val middle = inlineNodes(inserted, inherited, code)
        return merged(head + middle + tail)
    }

    private val storageMarkdown = Regex("""(!?)\[([^\]\n]*)\]\(storage://([A-Fa-f0-9-]{36})\)""")

    /**
     * Inline nodes for typed text: newlines become hard breaks (literal in code blocks); U+FFFC cannot create an
     * object and is dropped.
     */
    internal fun inlineNodes(text: String, marks: List<JsonValue>, code: Boolean): List<JsonValue> {
        val cleaned = text.replace(OBJECT_REPLACEMENT.toString(), "")
        fun textNode(s: String): JsonValue {
            val pairs = mutableListOf<Pair<String, JsonValue>>("type" to JsonValue.Str("text"), "text" to JsonValue.Str(s))
            if (marks.isNotEmpty()) pairs.add("marks" to JsonValue.Arr(marks))
            return JsonValue.Obj(pairs)
        }
        if (code) return if (cleaned.isEmpty()) emptyList() else listOf(textNode(cleaned))
        val out = mutableListOf<JsonValue>()
        cleaned.split("\n").forEachIndexed { index, line ->
            if (index > 0) out.add(JsonValue.obj("type" to JsonValue.Str("hardBreak")))
            if (line.isEmpty()) return@forEachIndexed
            // Newly inserted storage Markdown is a real inline object in ProseMirror, preserving surrounding marks
            // and every untouched/unknown node. Code blocks retain literal source text.
            var cursor = 0
            for (match in storageMarkdown.findAll(line)) {
                val id = match.groupValues[3]
                if (!TextSupport.isUuid(id)) continue
                if (match.range.first > cursor) out.add(textNode(line.substring(cursor, match.range.first)))
                val image = match.groupValues[1] == "!"
                out.add(JsonValue.obj(
                    "type" to JsonValue.Str(if (image) "fdStorageImage" else "fdStorageFile"),
                    "attrs" to JsonValue.obj("objectId" to JsonValue.Str(id), (if (image) "alt" else "label") to JsonValue.Str(match.groupValues[2])),
                ))
                cursor = match.range.last + 1
            }
            if (cursor < line.length) out.add(textNode(line.substring(cursor)))
        }
        return out
    }

    /** Joins adjacent text nodes with identical marks and drops empty ones, as ProseMirror normalises. */
    internal fun merged(nodes: List<JsonValue>): List<JsonValue> {
        val out = mutableListOf<JsonValue>()
        for (node in nodes) {
            if (node["type"]?.stringValue == "text") {
                val text = node["text"]?.stringValue ?: ""
                if (text.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null && last["type"]?.stringValue == "text" &&
                    (last["marks"]?.arrayValue ?: emptyList<JsonValue>()) == (node["marks"]?.arrayValue ?: emptyList<JsonValue>())) {
                    out[out.size - 1] = last.setting("text", JsonValue.Str((last["text"]?.stringValue ?: "") + text))
                    continue
                }
            }
            out.add(node)
        }
        return out
    }

    // Structure edits

    /** Inserts an empty (or given) paragraph after the block at `path`, in the same container. */
    fun insertParagraph(state: JsonValue, after: List<Int>, text: String = ""): JsonValue =
        replacing(state, after) { node -> listOf(node, paragraph(text, topLevel = after.size == 1)) }

    /** Appends a top-level paragraph at the end of the document. */
    fun appendParagraph(state: JsonValue, text: String = ""): JsonValue {
        val children = (state["content"]?.arrayValue ?: emptyList()) + paragraph(text, topLevel = true)
        return state.setting("content", JsonValue.Arr(children))
    }

    /** Removes a block. A container left with no blocks is removed with it, so the result stays schema-valid. */
    fun removeBlock(state: JsonValue, path: List<Int>): JsonValue {
        if (node(state, path) == null) throw DocumentEditError(DocumentEditError.Reason.INVALID_PATH)
        var result = replacing(state, path) { emptyList() }
        var parentPath = path.dropLast(1)
        while (parentPath.isNotEmpty()) {
            val parent = node(result, parentPath) ?: break
            if ((parent["content"]?.arrayValue ?: emptyList()).isNotEmpty()) break
            result = replacing(result, parentPath) { emptyList() }
            parentPath = parentPath.dropLast(1)
        }
        if ((result["content"]?.arrayValue ?: emptyList()).isEmpty()) result = appendParagraph(result)
        return result
    }

    /** Toggles the `checked` attribute of the task item that contains `path`. */
    fun toggleTask(state: JsonValue, containing: List<Int>): JsonValue {
        for (length in containing.size downTo 1) {
            val candidate = containing.take(length)
            val item = node(state, candidate)
            if (item != null && item["type"]?.stringValue == "taskItem") {
                return replacing(state, candidate) { found ->
                    val attrs = if (found["attrs"]?.isObject == true) found["attrs"]!! else JsonValue.Obj(emptyList())
                    listOf(found.setting("attrs", attrs.setting("checked", JsonValue.Bool(!ProseMirrorMarkdown.truthy(attrs["checked"])))))
                }
            }
        }
        throw DocumentEditError(DocumentEditError.Reason.INVALID_PATH)
    }
}
