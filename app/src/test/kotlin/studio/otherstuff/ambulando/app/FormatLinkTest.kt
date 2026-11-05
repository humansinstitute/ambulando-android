package studio.otherstuff.ambulando.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatLinkTest {
    @Test fun aBareAddressGetsHttpsAndOnlyWebAndMailLinksAreAccepted() {
        assertEquals("https://example.org/a", normalizeLink(" example.org/a "))
        assertEquals("http://example.org", normalizeLink("http://example.org"))
        assertEquals("mailto:me@example.org", normalizeLink("mailto:me@example.org"))
        assertNull(normalizeLink("javascript:alert(1)"))
        assertNull(normalizeLink("data:text/html,x"))
        assertNull(normalizeLink("https://"))
        assertNull(normalizeLink("   "))
    }
}
