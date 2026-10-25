package studio.otherstuff.ambulando.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Bech32

/** Markdown → display blocks for chat messages and Markdown document bodies (web parity: GFM with line breaks). */
class MarkdownRenderTest {
    private fun text(runs: List<InlineRun>) = runs.joinToString("") { it.text }

    /** Synthetic identities (fixed bytes, not real keys). */
    private val memberNpub = Bech32.encode("npub", ByteArray(32) { 0x11 })
    private val agentNpub = Bech32.encode("npub", ByteArray(32) { 0x22 })
    private val imageId = "00000000-0000-4000-8000-000000000001"
    private val docId = "00000000-0000-4000-8000-000000000002"

    @Test fun headingsParagraphBreaksAndInlineStyles() {
        val blocks = MarkdownBlocks.parse("# Title #\n\nFirst line with **bold**, _italic_, ~~gone~~ and `code`\nsecond line\n\nSub\n---")
        assertEquals(3, blocks.size)
        val title = blocks[0] as RenderBlock.Heading
        val runs = (blocks[1] as RenderBlock.Paragraph).runs
        val sub = blocks[2] as RenderBlock.Heading
        assertEquals(1, title.level)
        assertEquals(2, sub.level)
        assertEquals("Title", text(title.runs))
        assertEquals("Sub", text(sub.runs))
        assertTrue("single newline kept as a line break", text(runs).contains("\nsecond line"))
        assertTrue(runs.any { it.bold && it.text == "bold" })
        assertTrue(runs.any { it.italic && it.text == "italic" })
        assertTrue(runs.any { it.strike && it.text == "gone" })
        assertTrue(runs.any { it.code && it.text == "code" })
    }

    @Test fun fencesKeepMarkdownLookingTextVerbatim() {
        val source = "```swift\nlet a = \"# not a heading\"\n- not a list\n| a | b |\n```\n~~~\n**raw**\n~~~~\nafter"
        val blocks = MarkdownBlocks.parse(source)
        assertEquals(3, blocks.size)
        assertEquals(RenderBlock.Code("swift", "let a = \"# not a heading\"\n- not a list\n| a | b |"), blocks[0])
        assertEquals(RenderBlock.Code("", "**raw**"), blocks[1])
        assertEquals("after", text((blocks[2] as RenderBlock.Paragraph).runs))
        // An unclosed fence runs to the end; indented code also works.
        assertEquals(listOf(RenderBlock.Code("", "open")), MarkdownBlocks.parse("```\nopen"))
        assertEquals(listOf(RenderBlock.Code("", "indented\n  more")), MarkdownBlocks.parse("    indented\n      more"))
    }

    @Test fun nestedListsStartNumbersAndTasks() {
        val source = """
            3. three
            4. four
               - nested *one*
               - nested two
                 1. deep
            5. five

            - [ ] todo
            - [x] done
        """.trimIndent()
        val blocks = MarkdownBlocks.parse(source)
        assertEquals(2, blocks.size)
        val list = blocks[0] as RenderBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(3, list.items.size)
        assertEquals(2, list.items[1].blocks.size)
        val nested = list.items[1].blocks[1] as RenderBlock.ListBlock
        assertFalse(nested.ordered)
        assertEquals(2, nested.items.size)
        val deep = nested.items[1].blocks.last() as RenderBlock.ListBlock
        assertTrue(deep.ordered)
        assertEquals(1, deep.start)
        assertEquals(1, deep.items.size)
        val first = (nested.items[0].blocks[0] as RenderBlock.Paragraph).runs
        assertTrue(first.any { it.italic && it.text == "one" })
        val tasks = blocks[1] as RenderBlock.ListBlock
        assertFalse(tasks.ordered)
        assertEquals(listOf(false, true), tasks.items.map { it.checked })
        assertEquals("todo", text((tasks.items[0].blocks[0] as RenderBlock.Paragraph).runs))
    }

    @Test fun listInterruptsParagraphAndLazyContinuation() {
        val blocks = MarkdownBlocks.parse("Steps:\n1. open\ncontinued\n2. close\n\nIn 2024. we shipped")
        assertEquals("$blocks", 3, blocks.size)
        val items = (blocks[1] as RenderBlock.ListBlock).items
        assertEquals("open\ncontinued", text((items[0].blocks[0] as RenderBlock.Paragraph).runs))
    }

    @Test fun multiLineAndNestedQuotes() {
        val blocks = MarkdownBlocks.parse("> quoted **one**\nlazy\n>\n> > inner\n> - item")
        assertEquals("$blocks", 1, blocks.size)
        val inner = (blocks[0] as RenderBlock.Quote).blocks
        assertEquals("$inner", 3, inner.size)
        assertEquals("quoted one\nlazy", text((inner[0] as RenderBlock.Paragraph).runs))
        assertEquals(1, (inner[1] as RenderBlock.Quote).blocks.size)
        assertTrue(inner[2] is RenderBlock.ListBlock)
    }

    @Test fun pipeTablesWithAlignmentEscapesAndCode() {
        val source = "| Name | Value |\n|:-----|------:|\n| a \\| b | `x|y` |\n| only |\n\nafter"
        val blocks = MarkdownBlocks.parse(source)
        assertEquals(2, blocks.size)
        val rows = (blocks[0] as RenderBlock.Table).rows
        assertEquals(3, rows.size)
        assertEquals(listOf(listOf("Name", "Value"), listOf("a | b", "x|y"), listOf("only", "")), rows.map { row -> row.map { text(it) } })
        assertTrue(rows[1][1].first().code)
        // A paragraph followed by a rule is a heading, not a table.
        assertEquals(2, (MarkdownBlocks.parse("plain\n---").first() as RenderBlock.Heading).level)
    }

    @Test fun rulesAndImages() {
        val blocks = MarkdownBlocks.parse("---\n\n![Diagram](storage://$imageId)\n\n![remote](https://example.com/a.png) and text")
        assertEquals(3, blocks.size)
        assertEquals(RenderBlock.Rule, blocks[0])
        assertEquals(RenderBlock.Image("Diagram", "storage://$imageId"), blocks[1])
        assertEquals("https://example.com/a.png", (blocks[2] as RenderBlock.Paragraph).runs.first().image)
    }

    @Test fun mentionsKeepIdentityInsideEmphasisListsAndTables() {
        val source = "Hi **@[Member A](mention:person:$memberNpub)**, see @[Plan](mention:document:$docId)\n\n" +
            "- ask @[Agent](mention:agent:$agentNpub)\n\n| who |\n| --- |\n| @[Member A](mention:person:$memberNpub) |\n\n`@[Raw](mention:person:x)`"
        val blocks = MarkdownBlocks.parse(source)
        assertEquals("$blocks", 4, blocks.size)
        val runs = (blocks[0] as RenderBlock.Paragraph).runs
        val member = runs.first { it.mentionId == memberNpub }
        assertEquals("@Member A", member.text)
        assertEquals("person", member.mentionKind)
        assertTrue(member.bold)
        assertTrue(member.mention)
        assertNull(member.link)
        val plan = runs.first { it.mentionId == docId }
        assertEquals("record references show their label without @", "Plan", plan.text)
        assertEquals("document", plan.mentionKind)
        assertFalse(text(runs).contains("@@"))
        assertFalse(text(runs).contains("@Plan"))
        val item = ((blocks[1] as RenderBlock.ListBlock).items[0].blocks[0] as RenderBlock.Paragraph).runs
        assertEquals("agent", item.last().mentionKind)
        assertEquals("ask @Agent", text(item))
        val rows = (blocks[2] as RenderBlock.Table).rows
        assertEquals(memberNpub, rows[1][0].first().mentionId)
        val code = (blocks[3] as RenderBlock.Paragraph).runs
        assertEquals(true, code.first().code)
        assertNull("tokens inside code spans stay literal", code.first().mentionId)
    }

    @Test fun autolinksAndBreakTags() {
        val runs = MarkdownBlocks.inline("see https://example.com/path. then<br>next [docs](https://example.com/d) and [file](storage://abc)")
        assertTrue(runs.any { it.link == "https://example.com/path" && it.text == "https://example.com/path" })
        assertTrue(text(runs).contains("then\nnext"))
        assertTrue(runs.any { it.link == "https://example.com/d" && it.text == "docs" })
        assertTrue(runs.any { it.link == "storage://abc" && it.`object` })
    }

    @Test fun inlineEmphasisFollowsCommonMarkRules() {
        fun styled(source: String) = MarkdownBlocks.inline(source).map { Triple(it.text, it.bold, it.italic) }
        assertEquals(listOf(Triple("both", true, true)), styled("***both***"))
        assertEquals(listOf(Triple("snake_case_name", false, false)), styled("snake_case_name"))
        assertEquals(listOf(Triple("a * b * c", false, false)), styled("a * b * c"))
        assertEquals(listOf(Triple("x", false, true), Triple("y", true, true), Triple("z", false, true)), styled("*x**y**z*"))
        assertEquals(listOf(Triple("*literal*", false, false)), styled("\\*literal\\*"))
        assertEquals(listOf(Triple("a & b < c", false, false)), styled("a &amp; b &lt; c"))
        assertEquals(listOf(Triple("tab\there", false, false)), styled("tab&#9;here"))
        assertEquals("[not a link]", text(MarkdownBlocks.inline("[not a link]")))
        val link = MarkdownBlocks.inline("<https://example.com/x>").single()
        assertEquals("https://example.com/x", link.link)
        val titled = MarkdownBlocks.inline("[t](https://example.com \"Title\")").single()
        assertEquals("https://example.com", titled.link)
        assertEquals(listOf("a ", "b"), MarkdownBlocks.inline("a ~b~").map { it.text })
        assertTrue(MarkdownBlocks.inline("a ~b~").last().strike)
        assertEquals("a ` b", MarkdownBlocks.inline("`` a ` b ``").single().text)
    }

    @Test fun plainTextDetection() {
        assertTrue(MarkdownBlocks.isPlainText("Sounds good, see you at 3pm.\nThanks"))
        for (rich in listOf("**bold**", "# h", "- item", "1. one", "a `b`", "@[A](mention:person:x)", "https://example.com", "> q", "a | b", "    code")) {
            assertFalse(rich, MarkdownBlocks.isPlainText(rich))
        }
    }

    @Test fun pathologicalInputStaysBounded() {
        val deep = ">".repeat(400) + " deep"
        assertFalse(MarkdownBlocks.parse(deep).isEmpty())
        val nested = (0 until 200).joinToString("\n") { "  ".repeat(it) + "- x" }
        assertFalse(MarkdownBlocks.parse(nested).isEmpty())
        assertEquals(emptyList<RenderBlock>(), MarkdownBlocks.parse(""))
        assertEquals(emptyList<RenderBlock>(), MarkdownBlocks.parse("\n\n  \n"))
        // Long runs of delimiters and brackets finish.
        assertFalse(MarkdownBlocks.parse("*_".repeat(2000) + "[".repeat(2000)).isEmpty())
    }

    /** A message body as Tower stores it (mention tokens written by the web composer, agent reply with list and code). */
    @Test fun towerShapedMessageBody() {
        val raw = """{"id":"00000000-0000-4000-8000-000000000003","body":"@[Agent](mention:agent:$agentNpub) status?\n\n**Build 5** is ready:\n\n- Full-screen unlock\n- Channel catch-up\n\n```sh\n./gradlew test\n```","sender_npub":"$memberNpub","row_version":1}"""
        val body = JsonValue.parse(raw)["body"]!!.stringValue!!
        assertFalse(MarkdownBlocks.isPlainText(body))
        val blocks = MarkdownBlocks.parse(body)
        assertEquals("$blocks", 4, blocks.size)
        assertEquals("agent", (blocks[0] as RenderBlock.Paragraph).runs.first().mentionKind)
        assertEquals(2, (blocks[2] as RenderBlock.ListBlock).items.size)
        assertEquals(RenderBlock.Code("sh", "./gradlew test"), blocks[3])
    }

    /** `document_content_v1` with a Markdown content model (no editor state) renders through the same parser. */
    @Test fun markdownModelDocumentBody() {
        val body = """{"format":"document_content_v1","content_model":{"content":"# Plan\n\n| Step | Owner |\n| --- | --- |\n| Build | @[Agent](mention:agent:$agentNpub) |\n\n![Board](storage://$imageId)","content_blocks":[]}}"""
        val decoded = DocumentBody(body.toByteArray(), DocumentBody.ENVELOPE_CONTENT_TYPE)
        assertEquals(DocumentBody.Editing.MarkdownModel, decoded.editing)
        val blocks = DocumentRender.blocks(decoded)
        assertEquals("$blocks", 3, blocks.size)
        assertEquals("agent", (blocks[1] as RenderBlock.Table).rows[1][1].first().mentionKind)
        assertEquals(RenderBlock.Image("Board", "storage://$imageId"), blocks[2])
    }

    /** ProseMirror bodies: stored images keep their object id; fdMention marks keep kind and id; nothing is rewritten. */
    @Test fun proseMirrorImagesAndMentions() {
        val second = "00000000-0000-4000-8000-000000000004"
        val body = """{"format":"document_content_v1","content_model":{"content":"x","editor_state":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"fdStorageImage","attrs":{"objectId":"$imageId","alt":"Plan","src":"storage://$imageId"}}]},{"type":"paragraph","content":[{"type":"text","text":"Ask "},{"type":"text","text":"Member A","marks":[{"type":"fdMention","attrs":{"label":"Member A","mentionType":"person","mentionId":"$memberNpub"}}]},{"type":"fdStorageImage","attrs":{"objectId":"$second","alt":"inline"}}]}]}}}"""
        val bytes = body.toByteArray()
        val decoded = DocumentBody(bytes, DocumentBody.ENVELOPE_CONTENT_TYPE)
        val blocks = DocumentRender.blocks(decoded)
        assertEquals(RenderBlock.Image("Plan", "storage://$imageId"), blocks.first())
        val runs = (blocks[1] as RenderBlock.Paragraph).runs
        assertEquals(memberNpub, runs[1].mentionId)
        assertEquals("person", runs[1].mentionKind)
        assertEquals("storage://$second", runs[2].image)
        assertEquals("reading never rewrites the body", body, decoded.root?.serializedString())
    }

    /** Every Markdown string the web serializer produced for the golden ProseMirror cases parses into blocks. */
    @Test fun webGoldenMarkdownParses() {
        for ((name, value) in DocumentModelTest.golden().objectValue ?: emptyList()) {
            val markdown = value["content"]?.stringValue ?: ""
            val blocks = MarkdownBlocks.parse(markdown)
            if (markdown.isNotBlank()) assertFalse(name, blocks.isEmpty())
        }
    }
}
