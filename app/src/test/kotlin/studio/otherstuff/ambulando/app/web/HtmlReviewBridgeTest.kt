package studio.otherstuff.ambulando.app.web

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HtmlReviewBridgeTest {
    @Test
    fun rejectsOversizedOrMalformedBridgePayloads() {
        val oversized = "{\"kind\":\"html_element\",\"text_quote\":\"" + "a".repeat(HtmlReviewBridge.MAX_ANCHOR_JSON_CHARS + 10) + "\"}"
        assertNull(HtmlReviewBridge.parseBoundedAnchor(oversized))
        assertNull(HtmlReviewBridge.parseBoundedAnchor("not json"))

        val valid = HtmlReviewBridge.parseBoundedAnchor("""{"kind":"html_element","element_id":"hero","selector":"#hero","tag_name":"h1","text_quote":"Hello","attr_fingerprint":"aria-label=Hero"}""")
        assertNotNull(valid)
        assertEquals("hero", valid?.elementId)
        assertEquals("aria-label=Hero", valid?.attrFingerprint)

        assertNull(HtmlReviewBridge.parseBoundedThreadId("not-a-uuid"))
        val uuid = UUID.randomUUID().toString()
        assertEquals(uuid.lowercase(), HtmlReviewBridge.parseBoundedThreadId("  $uuid  "))

        val oversizedMatches = "{\"" + uuid + "\":true," + (1..600).joinToString(",") { "\"k$it\":true" } + "}"
        assertNull(HtmlReviewBridge.parseBoundedMatches(oversizedMatches))
        val parsedMatches = HtmlReviewBridge.parseBoundedMatches("""{"$uuid":true,"bad-key":true}""")
        assertEquals(mapOf(uuid.lowercase() to true), parsedMatches)
    }

    @Test
    fun reviewScriptRequiresStrictNormalizedQuoteAndFingerprintForTextlessTargets() {
        val doc = HtmlArtefactDocument.build("<h1 id=\"hero\">Hello</h1><img data-asset=\"a.png\" alt=\"Logo\">", enableReviewOverlay = true)
        assertTrue(doc.contains("strictElementMatches"))
        assertTrue(doc.contains("attrFingerprintFor"))
        assertTrue(doc.contains("if(!quote&&!expectedFp&&!a.element_id)return false"))
        // Sandbox must never inject credentials, signing, or network allowances.
        assertTrue(doc.contains("connect-src 'none'"))
        assertFalse(doc.contains("window.nostr"))
    }
}
