package studio.otherstuff.ambulando.core.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.json.JsonFields
import java.util.Base64

// Browser favourites: what may be saved, duplicates, editing, ordering and the stored form. Same cases as the
// iPhone/Mac `BrowserFavouritesTests`. No web view.

class BrowserFavouritesTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private fun refused(reason: BrowserFavouriteException.Reason, label: String = reason.name, block: () -> Unit) {
        try { block(); fail("$label should be refused") } catch (e: BrowserFavouriteException) { assertEquals(label, reason, e.reason) }
    }

    @Test fun savingAcceptsHttpsPagesOnceAndDefaultsTheTitle() {
        val list = BrowserFavourites()
        val saved = list.add("  ", "https://Example.com/app/", "https://example.com/icon.png")
        assertEquals("example.com", saved.title)
        assertEquals("https://example.com/icon.png", saved.siteIcon)
        assertEquals("case, trailing slash and fragment are ignored", saved.id, list.favourite("https://example.com/app#top")?.id)
        assertNull(list.favourite("https://example.com/other"))
        assertNull("the query is part of the address", list.favourite("https://example.com/app?x=1"))
        refused(BrowserFavouriteException.Reason.DUPLICATE) { list.add("Again", "https://example.com:443/app") }
        for (address in listOf("http://example.com", "https://user:pw@example.com", "https://node.fips/", "about:blank", "javascript:alert(1)")) {
            refused(BrowserFavouriteException.Reason.ADDRESS, address) { list.add("x", address) }
        }
        val insecureIcon = list.add("B", "https://b.example", "http://b.example/i.png")
        assertNull("only HTTPS icons are kept", insecureIcon.siteIcon)
        assertEquals(2, list.items.size)
        assertEquals("a port other than 443 is a different site", 3, list.apply { add("", "https://example.com:8443/app") }.items.size)
    }

    @Test fun editingChecksTheAddressIconAndDuplicates() {
        val list = BrowserFavourites()
        val a = list.add("A", "https://a.example", "https://a.example/i.png")
        val b = list.add("B", "https://b.example")

        list.update(a.copy(title = " Alpha ", symbol = " 🚀 ", image = png))
        assertEquals("Alpha", list.items[0].title)
        assertEquals("🚀", list.items[0].symbol)
        assertArrayEquals(png, list.items[0].image)
        assertNotNull("same address keeps the site icon", list.items[0].siteIcon)

        list.update(list.items[0].copy(url = "https://alpha.example"))
        assertNull("a new address drops the old site's icon", list.items[0].siteIcon)

        refused(BrowserFavouriteException.Reason.DUPLICATE) { list.update(list.items[0].copy(url = b.url)) }
        refused(BrowserFavouriteException.Reason.ADDRESS) { list.update(list.items[0].copy(url = "http://alpha.example")) }
        refused(BrowserFavouriteException.Reason.SYMBOL) { list.update(list.items[0].copy(symbol = "ABCD")) }
        refused(BrowserFavouriteException.Reason.IMAGE) { list.update(list.items[0].copy(image = "not a png".toByteArray())) }
        refused(BrowserFavouriteException.Reason.IMAGE, "oversized") { list.update(list.items[0].copy(image = png + ByteArray(BrowserFavourites.MAX_IMAGE_BYTES))) }
        list.update(list.items[0].copy(symbol = "  "))
        assertNull("an empty text icon goes back to the picture or site icon", list.items[0].symbol)
        list.update(list.items[0].copy(symbol = "AB"))
        assertEquals("AB", list.items[0].symbol)
        list.update(list.items[0].copy(title = "x".repeat(200)))
        assertEquals(BrowserFavourites.MAX_TITLE_LENGTH, list.items[0].title.length)

        list.setSiteIcon("https://alpha.example/touch.png", a.id)
        assertEquals("https://alpha.example/touch.png", list.items[0].siteIcon)
        list.setSiteIcon("http://alpha.example/plain.png", a.id)
        assertEquals("an insecure icon does not replace it", "https://alpha.example/touch.png", list.items[0].siteIcon)
        list.remove(b.id)
        assertEquals(listOf(a.id), list.items.map { it.id })
    }

    @Test fun savingANewFavouriteWithItsIconIsAllOrNothing() {
        val list = BrowserFavourites()
        val draft = BrowserFavourite(title = "Docs", url = "https://docs.example", symbol = "📄")
        list.save(draft)
        assertEquals(listOf("Docs" to "📄"), list.items.map { it.title to it.symbol })
        refused(BrowserFavouriteException.Reason.SYMBOL) { list.save(BrowserFavourite(title = "Bad", url = "https://bad.example", symbol = "TOO LONG")) }
        assertEquals("a refused new favourite is not left half-saved", 1, list.items.size)
        list.save(list.items[0].copy(title = "Docs 2"))
        assertEquals(listOf("Docs 2"), list.items.map { it.title })
    }

    @Test fun reorderingAndTheStoredFormRoundTrip() {
        val list = BrowserFavourites()
        val ids = (1..4).map { list.add("S$it", "https://s$it.example").id }
        list.move(ids[3], index = 0)
        assertEquals(listOf("S4", "S1", "S2", "S3"), list.items.map { it.title })
        list.move(ids[3], index = 99)
        assertEquals(listOf("S1", "S2", "S3", "S4"), list.items.map { it.title })
        list.moveBy(ids[0], 1)
        assertEquals(listOf("S2", "S1", "S3", "S4"), list.items.map { it.title })
        list.moveBy(ids[1], -5)
        assertEquals("clamped to the start", listOf("S2", "S1", "S3", "S4"), list.items.map { it.title })

        list.update(list.items[0].copy(image = png, symbol = "★"))
        list.setSiteIcon("https://s2.example/icon.png", ids[1])
        val text = list.encoded()!!
        assertEquals(list.items, BrowserFavourites.decode(text).items)
        assertNull("an empty list removes the stored value", BrowserFavourites().encoded())
        assertTrue(BrowserFavourites.decode(null).items.isEmpty())
        assertTrue(BrowserFavourites.decode("not json").items.isEmpty())
    }

    @Test fun decodingDropsWhatTheBrowserWouldNotOpenOrShow() {
        fun row(id: String, title: String, url: String, extra: Map<String, String> = emptyMap()) =
            JsonObject(mapOf("id" to JsonPrimitive(id), "title" to JsonPrimitive(title), "url" to JsonPrimitive(url)) + extra.mapValues { JsonPrimitive(it.value) })
        val text = JsonFields.encode(JsonObject(mapOf("version" to JsonPrimitive(1), "items" to JsonArray(listOf(
            row("6a0e1c1e-0000-4000-8000-000000000001", "Good", "https://good.example", mapOf(
                "siteIcon" to "http://good.example/i.png", "symbol" to "TOOLONG",
                "image" to Base64.getEncoder().encodeToString("jpeg?".toByteArray()))),
            row("6a0e1c1e-0000-4000-8000-000000000002", "Dup", "https://GOOD.example/"),
            row("6a0e1c1e-0000-4000-8000-000000000003", "Plain", "http://plain.example"),
            row("not-a-uuid", "Bad id", "https://other.example"),
        )))))
        val decoded = BrowserFavourites.decode(text)
        assertEquals(listOf("Good"), decoded.items.map { it.title })
        assertNull(decoded.items[0].siteIcon)
        assertNull(decoded.items[0].symbol)
        assertNull(decoded.items[0].image)
        assertEquals("G", decoded.items[0].initial)
        assertEquals("E", BrowserFavourite(title = "", url = "https://example.com").initial)
    }

    @Test fun theListIsBounded() {
        val list = BrowserFavourites()
        repeat(BrowserFavourites.MAX_COUNT) { list.add("", "https://s$it.example") }
        refused(BrowserFavouriteException.Reason.FULL) { list.add("", "https://one-more.example") }
        assertEquals(BrowserFavourites.MAX_COUNT, BrowserFavourites.decode(list.encoded()).items.size)
    }

    @Test fun typedAddressesBecomeHttps() {
        assertEquals("https://example.com", BrowserFavourites.address("example.com"))
        assertEquals("https://example.com/a?b=1", BrowserFavourites.address(" https://example.com/a?b=1 "))
        assertNull(BrowserFavourites.address("http://example.com"))
        assertNull(BrowserFavourites.address("not an address"))
    }
}
