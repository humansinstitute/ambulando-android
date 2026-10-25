package studio.otherstuff.ambulando.core.docs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAnchorTest {
    @Test fun utf16PositionsNestedBlocksAndVersionRelocation() {
        val state = JsonValue.parse("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"A😀B"}]},{"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"quoted words"}]}]}]}""")
        val spans = DocumentAnchors.spans(state)
        assertEquals(listOf(1, 8), spans.map { it.start })
        assertEquals(listOf(1, 0), spans[1].path)
        val anchor = spans[0].anchor(TextRange(1, 2), version = 4)!!
        assertEquals("😀", anchor.quote)
        assertEquals(2, anchor.from)
        assertEquals(4, anchor.to)
        val bytes = Json.encodeToString(JsonObject.serializer(), anchor.metadata)
        val decoded = DocumentAnchor.fromMetadata(Json.parseToJsonElement(bytes) as JsonObject)!!
        assertEquals(anchor, decoded)
        val changed = DocumentEditor.setText(state, listOf(0), "Before A😀B")
        val resolved = DocumentAnchors.resolve(decoded, DocumentAnchors.spans(changed))!!
        assertEquals("version changes require exact quote verification/unique relocation", TextRange(8, 2), resolved.range)
        val removed = DocumentEditor.setText(state, listOf(0), "deleted")
        assertNull(DocumentAnchors.resolve(decoded, DocumentAnchors.spans(removed)))
    }

    @Test fun ambiguousQuoteIsOrphanedAndUnknownNodesRemainUnchanged() {
        val state = JsonValue.parse("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"same same"}]},{"type":"futureWidget","attrs":{"keep":"yes"}}]}""")
        assertNull(DocumentAnchors.resolve(DocumentAnchor("same", 100, 104), DocumentAnchors.spans(state)))
        DocumentAnchors.spans(state)
        assertEquals("yes", state["content"]?.arrayValue?.last()?.get("attrs")?.get("keep")?.stringValue)
    }

    @Test fun insertedStorageImageBecomesRealNodeWithoutLosingMarksOrUnknownContent() {
        val state = JsonValue.parse("""{"type":"doc","content":[{"type":"paragraph","attrs":{"custom":true},"content":[{"type":"text","text":"Before After","marks":[{"type":"bold"}]}]},{"type":"futureWidget","attrs":{"keep":"yes"}}]}""")
        val id = "00000000-0000-4000-8000-000000000001"
        val edited = DocumentEditor.setText(state, listOf(0), "Before ![pasted](storage://$id)After")
        val paragraph = edited["content"]?.arrayValue?.first()!!
        val content = paragraph["content"]?.arrayValue!!
        assertTrue(content.any { it["type"]?.stringValue == "fdStorageImage" && it["attrs"]?.get("objectId")?.stringValue == id })
        assertEquals(state["content"]?.arrayValue?.first()?.get("content")?.arrayValue?.first()?.get("marks"), content.first()["marks"])
        assertEquals(state["content"]?.arrayValue?.first()?.get("attrs"), paragraph["attrs"])
        assertEquals(state["content"]?.arrayValue?.last(), edited["content"]?.arrayValue?.last())
    }

    @Test fun anchorMetadataRoundTripsThroughStoredCommentJson() {
        val anchor = DocumentAnchor("this", 8, 12, version = 1, blockId = "b1")
        val raw = """{"id":"00000000-0000-4000-8000-000000000009","body":"Anchored comment","metadata":${anchor.metadata}}"""
        assertEquals(anchor, DocumentAnchor.fromCommentRaw(raw.toByteArray()))
        assertEquals("this", DocumentAnchor.anchorQuote(raw.toByteArray()))
        assertEquals(8, anchor.metadata["anchor_start_offset"]!!.jsonPrimitive.int)
        assertNull(DocumentAnchor.fromCommentRaw("""{"metadata":{"anchor_quote":"x","anchor_start_offset":"1","anchor_end_offset":2}}""".toByteArray()))
        assertNull(DocumentAnchor.fromCommentRaw("not json".toByteArray()))
        assertNull(DocumentAnchor.fromMetadata(Json.parseToJsonElement("""{"anchor_quote":"","anchor_start_offset":1,"anchor_end_offset":2}""") as JsonObject))
    }

    @Test fun markdownSelectionUsesLineQuoteRatherThanFakePMOffsets() {
        val text = "# Heading\n\nA **formatted** paragraph."
        val spans = DocumentAnchors.markdownSpans(text, DocumentRender.markdownBlocks(text))
        val paragraph = spans.last()
        assertEquals(3, paragraph.line)
        val anchor = paragraph.anchor(TextRange(2, 9), version = 2)!!
        assertEquals("formatted", anchor.quote)
        assertNull(anchor.metadata["anchor_start_offset"])
        assertEquals(3, anchor.metadata["anchor_line_number"]!!.jsonPrimitive.int)
        val data = Json.encodeToString(JsonObject.serializer(), anchor.metadata)
        val decoded = DocumentAnchor.fromMetadata(Json.parseToJsonElement(data) as JsonObject)!!
        assertEquals(TextRange(2, 9), DocumentAnchors.resolve(decoded, spans)?.range)
    }
}
