package studio.otherstuff.ambulando.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiLinksTest {
    private val target = "0a1b2c3d-0000-4000-8000-0000000000aa"

    private fun markdown(text: String) = DocumentBody(text.toByteArray(), "text/markdown")

    private fun rich(vararg nodes: String) = DocumentBody(
        """{"format":"document_content_v1","content_model":{"content":"x","editor_state":{"type":"doc","content":[${nodes.joinToString(",")}]}}}""".toByteArray(),
        "application/json",
    )

    @Test fun addressesNameADocumentByStableId() {
        assertEquals(target, WikiLinks.targetId("wiki:$target"))
        assertEquals(target, WikiLinks.targetId("WIKI:${target.uppercase()}"))
        assertNull(WikiLinks.targetId("wiki:not-an-id"))
        assertNull(WikiLinks.targetId("https://example.test/$target"))
        assertNull(WikiLinks.targetId(null))
    }

    @Test fun markdownLinksCountOutsideCodeOnly() {
        assertTrue(WikiLinks.links(markdown("See [Plan](wiki:$target) today"), target))
        assertFalse(WikiLinks.links(markdown("See `[Plan](wiki:$target)` today"), target))
        assertFalse(WikiLinks.links(markdown("```\n[Plan](wiki:$target)\n```"), target))
        assertFalse(WikiLinks.links(markdown("A [[Plan]] title-only reference"), target))
        assertFalse(WikiLinks.links(markdown("[Other](wiki:0a1b2c3d-0000-4000-8000-0000000000bb)"), target))
    }

    @Test fun richDocumentsLinkThroughNodesAndMarks() {
        val node = """{"type":"paragraph","content":[{"type":"fdWikiLink","attrs":{"documentId":"$target","title":"Plan"}}]}"""
        val mark = """{"type":"paragraph","content":[{"type":"text","text":"Plan","marks":[{"type":"link","attrs":{"href":"wiki:$target"}}]}]}"""
        val code = """{"type":"codeBlock","content":[{"type":"text","text":"[Plan](wiki:$target)","marks":[{"type":"link","attrs":{"href":"wiki:$target"}}]}]}"""
        assertTrue(WikiLinks.links(rich(node), target))
        assertTrue(WikiLinks.links(rich(mark), target))
        assertFalse(WikiLinks.links(rich(code), target))
        assertFalse(WikiLinks.links(rich("""{"type":"paragraph","content":[{"type":"text","text":"none"}]}"""), target))
    }
}
