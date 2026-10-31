package studio.otherstuff.ambulando.core.docs

/** Styled run of inline text for native rendering. UI-free: the app maps it to its own text styles. */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val link: String? = null,
    val mention: Boolean = false,
    /** Mention or record reference identity (`mention:<kind>:<id>`), kept so the reader can route to it. */
    val mentionKind: String? = null,
    val mentionId: String? = null,
    /** Inline image source (`storage://<object id>` or a web address, which is never fetched). */
    val image: String? = null,
    /** An inline object shown as a label (wiki link, image, file). */
    val `object`: Boolean = false,
)

/**
 * A display block. Read-only rendering never alters the stored body; nodes this app does not render natively are
 * listed as [Preserved] so the reader says so instead of dropping them.
 */
sealed class RenderBlock {
    data class Heading(val level: Int, val runs: List<InlineRun>) : RenderBlock()
    data class Paragraph(val runs: List<InlineRun>) : RenderBlock()
    data class Code(val language: String, val text: String) : RenderBlock()
    data class Quote(val blocks: List<RenderBlock>) : RenderBlock()
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : RenderBlock()
    /** Rows of cells; each cell is a list of runs. The first row is the header. */
    data class Table(val rows: List<List<List<InlineRun>>>) : RenderBlock()
    data class Image(val alt: String, val source: String) : RenderBlock()
    data object Rule : RenderBlock()
    data class Preserved(val type: String, val text: String) : RenderBlock()

    data class ListItem(val checked: Boolean?, val blocks: List<RenderBlock>)
}

object DocumentRender {
    /** Blocks for any decoded body. */
    fun blocks(body: DocumentBody): List<RenderBlock> {
        val state = body.editorState
        if (state != null) return (state["content"]?.arrayValue ?: emptyList()).map { block(it) }
        return markdownBlocks(body.markdown)
    }

    // ProseMirror

    internal fun block(node: JsonValue): RenderBlock {
        val content = node["content"]?.arrayValue ?: emptyList()
        return when (val type = node["type"]?.stringValue ?: "") {
            "heading" -> RenderBlock.Heading(node["attrs"]?.get("level")?.intValue ?: 1, runs(content))
            "paragraph" -> {
                val inline = runs(content)
                // A paragraph holding only one image shows it as an image block, as the web does.
                val image = inline.singleOrNull()?.image
                if (image != null) RenderBlock.Image(inline[0].text, image) else RenderBlock.Paragraph(inline)
            }
            "codeBlock" -> RenderBlock.Code(node["attrs"]?.get("language")?.stringValue ?: "", content.joinToString("") { it["text"]?.stringValue ?: "" })
            "blockquote" -> RenderBlock.Quote(content.map { block(it) })
            "bulletList", "orderedList", "taskList" -> RenderBlock.ListBlock(
                ordered = type == "orderedList",
                start = node["attrs"]?.get("start")?.intValue ?: 1,
                items = content.map { item ->
                    RenderBlock.ListItem(
                        checked = if (type == "taskList") ProseMirrorMarkdown.truthy(item["attrs"]?.get("checked")) else null,
                        blocks = (item["content"]?.arrayValue ?: emptyList()).map { block(it) },
                    )
                },
            )
            "table" -> RenderBlock.Table(content.map { row ->
                (row["content"]?.arrayValue ?: emptyList()).map { cell ->
                    (cell["content"]?.arrayValue ?: emptyList()).flatMap { runs(it["content"]?.arrayValue ?: emptyList()) }
                }
            })
            "horizontalRule" -> RenderBlock.Rule
            "image", "fdStorageImage" -> {
                val attrs = node["attrs"]
                RenderBlock.Image(attrs?.get("alt")?.stringValue ?: "", imageSource(attrs))
            }
            else -> RenderBlock.Preserved(type.ifEmpty { "unknown" }, ProseMirrorMarkdown.inlineMarkdown(content))
        }
    }

    internal fun runs(nodes: List<JsonValue>): List<InlineRun> = nodes.map { node ->
        when (node["type"]?.stringValue) {
            "text" -> {
                var run = InlineRun(text = node["text"]?.stringValue ?: "")
                for (mark in node["marks"]?.arrayValue ?: emptyList()) {
                    run = when (mark["type"]?.stringValue) {
                        "bold" -> run.copy(bold = true)
                        "italic" -> run.copy(italic = true)
                        "code" -> run.copy(code = true)
                        "strike" -> run.copy(strike = true)
                        "underline" -> run.copy(underline = true)
                        "link" -> run.copy(link = mark["attrs"]?.get("href")?.stringValue)
                        "fdMention" -> run.copy(
                            mention = true,
                            mentionKind = mark["attrs"]?.get("mentionType")?.stringValue,
                            mentionId = mark["attrs"]?.get("mentionId")?.stringValue,
                        )
                        else -> run
                    }
                }
                run
            }
            "hardBreak" -> InlineRun(text = "\n")
            "fdWikiLink" -> InlineRun(text = node["attrs"]?.get("title")?.stringValue ?: "Linked document",
                link = node["attrs"]?.get("documentId")?.stringValue?.takeIf { it.isNotEmpty() }?.let { "wiki:$it" }, `object` = true)
            "fdStorageImage", "image" -> {
                val attrs = node["attrs"]
                InlineRun(text = attrs?.get("alt")?.stringValue ?: "", image = imageSource(attrs), `object` = true)
            }
            "fdStorageFile" -> InlineRun(text = node["attrs"]?.get("label")?.stringValue ?: "File", `object` = true)
            else -> {
                val text = ProseMirrorMarkdown.inlineMarkdown(node["content"]?.arrayValue ?: emptyList())
                InlineRun(text = text.ifEmpty { node["type"]?.stringValue ?: "" }, `object` = true)
            }
        }
    }

    /** `storage://<objectId>` for stored images (matching the web serializer), else the node's `src`. */
    internal fun imageSource(attrs: JsonValue?): String =
        attrs?.get("objectId")?.stringValue?.let { "storage://$it" } ?: attrs?.get("src")?.stringValue ?: ""

    // Markdown bodies

    /** Blocks for a Markdown body or message (see [MarkdownBlocks]). */
    fun markdownBlocks(text: String): List<RenderBlock> = MarkdownBlocks.parse(text)

    internal fun inline(text: String): List<InlineRun> = MarkdownBlocks.inline(text)
}
