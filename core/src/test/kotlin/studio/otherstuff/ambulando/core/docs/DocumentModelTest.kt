package studio.otherstuff.ambulando.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Document bodies: lossless JSON, the Markdown serializer port (checked against the web client's own output), the
 * body representations, and structural edits that must keep marks, ids and unknown content.
 */
class DocumentModelTest {
    companion object {
        fun golden(): JsonValue {
            val bytes = DocumentModelTest::class.java.getResourceAsStream("/docs/prosemirror-golden.json")!!.use { it.readBytes() }
            return JsonValue.parse(bytes)
        }

        fun str(s: String) = JsonValue.Str(s)
        fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(pairs.toList())
        fun arr(vararg items: JsonValue) = JsonValue.Arr(items.toList())
    }

    @Test fun jsonValueRoundTripKeepsOrderNumbersAndEscapes() {
        val text = """{"z":1,"a":[1.50,-0,2e10,true,null],"s":"line\nquote\" é 🎉 \/","nested":{"b":{},"a":[]}}"""
        val value = JsonValue.parse(text)
        assertEquals(listOf("z", "a", "s", "nested"), value.objectValue?.map { it.first })
        assertEquals(JsonValue.Num("1.50"), value["a"]?.arrayValue?.first())
        assertEquals("line\nquote\" é 🎉 /", value["s"]?.stringValue)
        val reparsed = JsonValue.parse(value.serialized())
        assertEquals(value, reparsed)
        assertTrue(value.serializedString().startsWith("""{"z":1,"a":[1.50,-0,2e10,true,null]"""))
        assertThrows(JsonValue.ParseError::class.java) { JsonValue.parse("""{"a":1,}""") }
        assertThrows(JsonValue.ParseError::class.java) { JsonValue.parse("\"\\udc00\"") }
        assertThrows(JsonValue.ParseError::class.java) { JsonValue.parse("[1] x") }
    }

    @Test fun jsonValueReencodesUntouchedBytesIdenticallyAndHandlesEdges() {
        // Compact input written the way JSON.stringify writes it survives byte for byte, including duplicate keys.
        val compact = """{"k":[1E+2,0.000,-12.5e-3],"dup":1,"dup":2,"u":"\u0001 tab\t 😀","deep":{"x":{"y":[[],{}]}}}"""
        assertEquals(compact, JsonValue.parse(compact).serializedString())
        assertEquals(JsonValue.Num("2"), JsonValue.parse(compact)["dup"])
        // Surrogate pairs decode to one scalar; escapes are re-written in JSON.stringify style.
        assertEquals("😀", JsonValue.parse("\"\\ud83d\\ude00\"").stringValue)
        assertEquals("\"\\u0001\"", JsonValue.Str("\u0001").serializedString())
        // Malformed input.
        for (bad in listOf("", "01x", "1.", "-", "1e", "\"open", "[1,", "{\"a\" 1}", "tru", "\"\\x\"", "\"\\ud83d\"", "\"a\nb\"")) {
            assertThrows(bad, JsonValue.ParseError::class.java) { JsonValue.parse(bad) }
        }
        val tooDeep = "[".repeat(600) + "]".repeat(600)
        assertEquals(JsonValue.ParseError.Kind.DEPTH, assertThrows(JsonValue.ParseError::class.java) { JsonValue.parse(tooDeep) }.kind)
        // Setting replaces in place, appends new keys, and removes with null.
        val o = JsonValue.parse("""{"a":1,"b":2}""")
        assertEquals("""{"a":3,"b":2}""", o.setting("a", JsonValue.int(3)).serializedString())
        assertEquals("""{"a":1,"b":2,"c":true}""", o.setting("c", JsonValue.Bool(true)).serializedString())
        assertEquals("""{"b":2}""", o.setting("a", null).serializedString())
        assertEquals(3, JsonValue.Num("3.0").intValue)
        assertNull(JsonValue.Num("3.5").intValue)
    }

    /** Every case's Markdown and content blocks equal what the web serializer produced. */
    @Test fun serializerMatchesWebGolden() {
        val cases = golden().objectValue!!
        assertTrue(cases.size >= 20)
        for ((name, value) in cases) {
            val state = value["state"]!!
            val fields = ProseMirrorMarkdown.contentModelFields(state).toMap()
            assertEquals("content for $name", value["content"]?.stringValue, fields["content"]?.stringValue)
            val blocks = fields["content_blocks"]?.arrayValue ?: emptyList()
            val expected = value["blocks"]?.arrayValue ?: emptyList()
            assertEquals("block count for $name", expected.size, blocks.size)
            for ((got, want) in blocks.zip(expected)) {
                assertEquals("block text for $name", want["text"], got["text"])
                assertEquals("block type for $name", want["type"], got["type"])
                val id = want["id"]?.stringValue
                if (id != null && !id.startsWith("pm_")) assertEquals("block id for $name", id, got["id"]?.stringValue)
                assertEquals(got["id"], got["attrs"]?.get("pmNodeId"))
            }
        }
    }

    // Body representations

    private fun envelope(content: List<JsonValue>, extra: List<Pair<String, JsonValue>> = emptyList()): ByteArray {
        val state = obj("type" to str("doc"), "content" to JsonValue.Arr(content))
        val model = ProseMirrorMarkdown.contentModelFields(state) + ("references" to arr(str("keep-me")))
        val root = JsonValue.Obj(listOf("format" to str(DocumentBody.ENVELOPE_FORMAT), "content_model" to JsonValue.Obj(model)) + extra)
        return root.serialized()
    }

    private fun para(id: String?, content: List<JsonValue>): JsonValue {
        val pairs = mutableListOf<Pair<String, JsonValue>>("type" to str("paragraph"))
        if (id != null) pairs.add("attrs" to obj("fdBlockId" to str(id)))
        pairs.add("content" to JsonValue.Arr(content))
        return JsonValue.Obj(pairs)
    }

    private fun text(s: String, marks: List<String> = emptyList()): JsonValue {
        val pairs = mutableListOf<Pair<String, JsonValue>>("type" to str("text"), "text" to str(s))
        if (marks.isNotEmpty()) pairs.add("marks" to JsonValue.Arr(marks.map { obj("type" to str(it)) }))
        return JsonValue.Obj(pairs)
    }

    @Test fun envelopeEditPreservesUnknownFieldsMarksAndIds() {
        val custom = obj("type" to str("customWidget"), "attrs" to obj("fdBlockId" to str("w1"), "n" to JsonValue.Num("1.50")))
        val bytes = envelope(listOf(para("b1", listOf(text("Hello "), text("bold", listOf("bold")), text(" world"))), custom),
            extra = listOf("future_field" to obj("x" to JsonValue.Num("2.0"))))
        val body = DocumentBody(bytes, DocumentBody.ENVELOPE_CONTENT_TYPE)
        assertEquals(DocumentBody.Representation.ENVELOPE, body.representation)
        assertEquals(DocumentBody.Editing.ProseMirror, body.editing)
        val state = body.editorState!!
        val blocks = DocumentEditor.blocks(state)
        assertEquals(listOf("Hello bold world"), blocks.map { it.text })
        assertTrue(blocks[0].rich)

        // Edit inside the plain tail: the bold run is untouched.
        val edited = DocumentEditor.setText(state, blocks[0].path, "Hello bold wide world!")
        val saved = body.encoded(editorState = edited)
        assertEquals(DocumentBody.ENVELOPE_CONTENT_TYPE, saved.contentType)
        val reread = DocumentBody(saved.bytes, saved.contentType)
        val root = reread.root!!
        assertEquals("unknown envelope field kept", JsonValue.Num("2.0"), root["future_field"]?.get("x"))
        assertEquals("unknown model field kept", arr(str("keep-me")), reread.contentModel?.get("references"))
        assertEquals("Hello **bold** wide world\\!", reread.markdown)
        val nodes = reread.editorState?.get("content")?.arrayValue!!
        assertEquals("block id kept for comment anchors", str("b1"), nodes[0]["attrs"]?.get("fdBlockId"))
        assertEquals("unknown node kept byte-for-byte", custom, nodes[1])
        assertEquals(text("bold", listOf("bold")), nodes[0]["content"]?.arrayValue?.get(1))
        assertEquals(ProseMirrorMarkdown.markdown(edited), reread.contentModel?.get("content")?.stringValue)
        assertEquals(str(ProseMirrorMarkdown.CONTENT_FORMAT), reread.contentModel?.get("content_format"))
        // Bytes outside the regenerated fields are identical: the custom node's original lexemes are kept.
        assertTrue(saved.bytes.toString(Charsets.UTF_8).contains(""""future_field":{"x":2.0}"""))
        assertTrue(saved.bytes.toString(Charsets.UTF_8).contains(""""n":1.50"""))
    }

    @Test fun typedTextInheritsMarksAndDoesNotExtendLinks() {
        val link = obj("type" to str("text"), "text" to str("site"),
            "marks" to arr(obj("type" to str("link"), "attrs" to obj("href" to str("https://example.com")))))
        val state = obj("type" to str("doc"), "content" to arr(para("b1", listOf(text("go "), text("bold", listOf("bold")), text(" to "), link))))
        val path = DocumentEditor.blocks(state)[0].path
        // Typing at the end of "bold" continues bold.
        var next = DocumentEditor.setText(state, path, "go bolder to site")
        var inline = DocumentEditor.node(next, path)?.get("content")?.arrayValue!!
        assertEquals(text("bolder", listOf("bold")), inline[1])
        // Typing after the link does not extend the link.
        next = DocumentEditor.setText(state, path, "go bold to site now")
        inline = DocumentEditor.node(next, path)?.get("content")?.arrayValue!!
        assertEquals(text(" now"), inline.last())
        assertEquals(link, inline[3])
        // Replacing a selection inside the bold run keeps bold.
        next = DocumentEditor.setText(state, path, "go BOLD to site")
        inline = DocumentEditor.node(next, path)?.get("content")?.arrayValue!!
        assertEquals(text("BOLD", listOf("bold")), inline[1])
        // A typed newline becomes a hard break; deleting everything leaves an empty paragraph without content.
        next = DocumentEditor.setText(state, path, "go bold to site\nmore")
        inline = DocumentEditor.node(next, path)?.get("content")?.arrayValue!!
        assertEquals(str("hardBreak"), inline[inline.size - 2]["type"])
        next = DocumentEditor.setText(state, path, "")
        assertNull(DocumentEditor.node(next, path)?.get("content"))
        assertEquals(str("b1"), DocumentEditor.node(next, path)?.get("attrs")?.get("fdBlockId"))
    }

    @Test fun editsCountUnicodeScalarsNotUtf16Units() {
        val state = obj("type" to str("doc"), "content" to arr(para("b1", listOf(text("a😀"), text("b", listOf("bold"))))))
        val next = DocumentEditor.setText(state, listOf(0), "a😀😀b")
        val inline = DocumentEditor.node(next, listOf(0))?.get("content")?.arrayValue!!
        assertEquals(listOf(text("a😀😀"), text("b", listOf("bold"))), inline)
    }

    @Test fun inlineObjectsSurviveUnlessDeleted() {
        val wiki = obj("type" to str("fdWikiLink"), "attrs" to obj("documentId" to str("d1"), "title" to str("Plan")))
        val state = obj("type" to str("doc"), "content" to arr(para("b1", listOf(text("See "), wiki, text(" now")))))
        val block = DocumentEditor.blocks(state)[0]
        assertEquals("See \uFFFC now", block.text)
        var next = DocumentEditor.setText(state, block.path, "See \uFFFC later")
        assertEquals(wiki, DocumentEditor.node(next, block.path)?.get("content")?.arrayValue?.get(1))
        next = DocumentEditor.setText(state, block.path, "See  later")
        assertFalse((DocumentEditor.node(next, block.path)?.get("content")?.arrayValue ?: emptyList()).contains(wiki))
    }

    @Test fun nestedBlocksTasksAndStructureEdits() {
        fun item(checked: Boolean, label: String) =
            obj("type" to str("taskItem"), "attrs" to obj("checked" to JsonValue.Bool(checked)), "content" to arr(para(null, listOf(text(label)))))
        val list = obj("type" to str("taskList"), "attrs" to obj("fdBlockId" to str("l1")), "content" to arr(item(false, "todo"), item(true, "done")))
        val state = obj("type" to str("doc"), "content" to arr(list))
        val blocks = DocumentEditor.blocks(state)
        assertEquals(listOf("todo", "done"), blocks.map { it.text })
        assertEquals(listOf(false, true), blocks.map { it.taskChecked })
        assertEquals(listOf(0, 0, 0), blocks[0].path)
        val toggled = DocumentEditor.toggleTask(state, blocks[0].path)
        assertEquals("- [x] todo\n- [x] done", ProseMirrorMarkdown.markdown(toggled))
        // Inserting after a nested paragraph stays inside the item and gets no top-level id.
        val inserted = DocumentEditor.insertParagraph(state, blocks[0].path, "note")
        val itemContent = DocumentEditor.node(inserted, listOf(0, 0))?.get("content")?.arrayValue!!
        assertEquals(2, itemContent.size)
        assertNull(itemContent[1]["attrs"])
        // Removing the only paragraph of an item removes the empty item; removing everything leaves one paragraph.
        val removed = DocumentEditor.removeBlock(state, blocks[0].path)
        assertEquals(listOf("done"), DocumentEditor.blocks(removed).map { it.text })
        val empty = DocumentEditor.removeBlock(removed, DocumentEditor.blocks(removed)[0].path)
        assertEquals(1, empty["content"]?.arrayValue?.size)
        assertEquals(str("paragraph"), empty["content"]?.arrayValue?.first()?.get("type"))
        assertNotNull(empty["content"]?.arrayValue?.first()?.get("attrs")?.get("fdBlockId")?.stringValue)
        assertThrows(DocumentEditError::class.java) { DocumentEditor.setText(state, listOf(5), "x") }
    }

    @Test fun rawMarkdownStaysMarkdown() {
        val body = DocumentBody("# Notes\n\nPlain *md*".toByteArray(), "text/markdown; charset=utf-8")
        assertEquals(DocumentBody.Representation.RAW_MARKDOWN, body.representation)
        assertEquals(DocumentBody.Editing.Markdown, body.editing)
        val saved = body.encoded(markdown = "# Notes\n\nEdited")
        assertEquals("# Notes\n\nEdited", saved.bytes.toString(Charsets.UTF_8))
        assertEquals("text/markdown; charset=utf-8", saved.contentType)
        assertThrows(DocumentEditError::class.java) { body.encoded(editorState = JsonValue.Obj(emptyList())) }
        // Bytes that are not UTF-8 are not Markdown.
        assertFalse(DocumentBody(byteArrayOf(0xC3.toByte(), 0x28), "text/markdown").isEditable)
    }

    @Test fun legacyMarkdownModelKeepsShapeAndClearsStaleBlocks() {
        val legacy = """{"content":"old","content_blocks":[{"id":"x","text":"old"}],"title_hint":"t"}"""
        val body = DocumentBody(legacy.toByteArray(), "application/json")
        assertEquals(DocumentBody.Representation.LEGACY_MODEL, body.representation)
        assertEquals(DocumentBody.Editing.MarkdownModel, body.editing)
        val root = JsonValue.parse(body.encoded(markdown = "new text").bytes)
        assertEquals(str("new text"), root["content"])
        assertEquals(arr(), root["content_blocks"])
        assertEquals(str("t"), root["title_hint"])
        assertNull("a legacy body is not silently converted", root["format"])
        val wrapped = DocumentBody("""{"content_model":{"content":"w"},"x":1}""".toByteArray(), null)
        assertEquals(DocumentBody.Representation.LEGACY_WRAPPED, wrapped.representation)
        assertEquals("""{"content_model":{"content":"v"},"x":1}""", wrapped.encoded(markdown = "v").bytes.toString(Charsets.UTF_8))
    }

    @Test fun unknownFormatIsReadOnly() {
        val pdf = DocumentBody(byteArrayOf(0x25, 0x50, 0x44, 0x46), "application/pdf")
        assertFalse(pdf.isEditable)
        assertThrows(DocumentEditError::class.java) { pdf.encoded(markdown = "x") }
        val otherJson = DocumentBody("""{"format":"document_content_v9","content_model":{"foo":1}}""".toByteArray(), "application/json")
        assertFalse(otherJson.isEditable)
    }

    @Test fun newDocumentIsWebEnvelope() {
        val created = DocumentBody.newDocument("First para\n\nSecond")
        val body = DocumentBody(created.bytes, created.contentType)
        assertEquals(DocumentBody.Representation.ENVELOPE, body.representation)
        assertEquals("First para\n\nSecond", body.markdown)
        assertEquals(str("prosemirror_json_v1"), body.contentModel?.get("editor_state_format"))
    }

    @Test fun renderKeepsUnknownBlocksVisibleAndReadsMarkdown() {
        val custom = obj("type" to str("fdCalloutWidget"), "content" to arr(text("note")))
        val bytes = envelope(listOf(para("b1", listOf(text("Hi "), text("there", listOf("bold")))), custom))
        val blocks = DocumentRender.blocks(DocumentBody(bytes, DocumentBody.ENVELOPE_CONTENT_TYPE))
        val runs = (blocks[0] as RenderBlock.Paragraph).runs
        assertEquals(listOf("Hi ", "there"), runs.map { it.text })
        assertTrue(runs[1].bold)
        assertEquals(RenderBlock.Preserved("fdCalloutWidget", "note"), blocks[1])

        val md = DocumentRender.markdownBlocks("# Title\n\nSome **bold** text\n\n- [x] done\n- [ ] todo\n\n```\ncode\n```")
        assertEquals(4, md.size)
        assertEquals(1, (md[0] as RenderBlock.Heading).level)
        val list = md[2] as RenderBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(listOf(true, false), list.items.map { it.checked })
        assertEquals("code", (md[3] as RenderBlock.Code).text)
        assertTrue((md[1] as RenderBlock.Paragraph).runs.any { it.bold && it.text == "bold" })
    }

    /**
     * The editor page's document comes back through this guard before it is drafted: kept stand-ins or node types
     * the schema and the stored document do not have are refused; the stored document's own unknown types are fine.
     */
    @Test fun editedDocumentIntegrityGuard() {
        val custom = obj("type" to str("customWidget"))
        val original = obj("type" to str("doc"), "content" to arr(para("b1", listOf(text("Hi"))), custom))
        DocumentBody.checkEdited(original, original)
        val image = obj("type" to str("fdStorageImage"), "attrs" to obj("objectId" to str("x")))
        DocumentBody.checkEdited(obj("type" to str("doc"), "content" to arr(image)), original)
        val kept = obj("type" to str("fdKeptBlock"), "attrs" to obj("json" to str("{}")))
        assertThrows(DocumentEditError::class.java) { DocumentBody.checkEdited(obj("type" to str("doc"), "content" to arr(kept)), original) }
        val invented = obj("type" to str("somethingElse"))
        assertThrows(DocumentEditError::class.java) { DocumentBody.checkEdited(obj("type" to str("doc"), "content" to arr(invented)), original) }
        assertThrows(DocumentEditError::class.java) { DocumentBody.checkEdited(obj("type" to str("paragraph")), original) }
    }
}
