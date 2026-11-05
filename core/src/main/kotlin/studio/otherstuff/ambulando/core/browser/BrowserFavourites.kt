package studio.otherstuff.ambulando.core.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.json.JsonFields
import java.text.BreakIterator
import java.util.Base64
import java.util.UUID

/**
 * A site saved to the browser's new-tab page. The tile shows, in order of preference, the user's own picture, the
 * user's own short text (an emoji or initials), the icon the site advertised when it was saved, or the title's first
 * letter.
 */
data class BrowserFavourite(
    val id: UUID = UUID.randomUUID(),
    val title: String,
    val url: String,
    /** The site's own icon (`apple-touch-icon`, `icon`), HTTPS only, found when the page was saved. */
    val siteIcon: String? = null,
    /** The user's text icon: one to three characters, such as an emoji. */
    val symbol: String? = null,
    /** The user's picture icon: PNG bytes, already scaled down on the device. */
    val image: ByteArray? = null,
) {
    /** The letter shown when there is no icon. */
    val initial: String get() {
        val source = title.ifEmpty { parseUri(url)?.host.orEmpty() }
        return BrowserFavourites.firstCharacter(source)?.uppercase() ?: "?"
    }

    val host: String get() = parseUri(url)?.host ?: url

    override fun equals(other: Any?): Boolean = other is BrowserFavourite && id == other.id && title == other.title &&
        url == other.url && siteIcon == other.siteIcon && symbol == other.symbol &&
        (image?.contentEquals(other.image ?: return false) ?: (other.image == null))

    override fun hashCode(): Int = id.hashCode()
}

class BrowserFavouriteException(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val message: String) {
        ADDRESS("Enter an HTTPS address, for example example.com."),
        DUPLICATE("That address is already a favourite."),
        SYMBOL("Use up to three characters, such as an emoji."),
        IMAGE("That picture could not be used. Choose a PNG or JPEG photo."),
        FULL("There are already ${BrowserFavourites.MAX_COUNT} favourites. Remove one to add another."),
    }
}

/**
 * The ordered favourites list, stored on this device (app state under [STATE_KEY]). It is separate from website data
 * and from per-site Nostr permissions, so clearing either keeps the favourites. Same rules as the iPhone/Mac app.
 */
class BrowserFavourites(items: List<BrowserFavourite> = emptyList()) {
    companion object {
        const val STATE_KEY = "browser.favourites.v1"
        const val MAX_COUNT = 120
        const val MAX_TITLE_LENGTH = 80
        const val MAX_SYMBOL_LENGTH = 3
        /** Longer side of a picture icon after scaling. */
        const val ICON_PIXELS = 256
        /** A picture icon after scaling; a larger one is refused rather than stored. */
        const val MAX_IMAGE_BYTES = 512 * 1024
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** Same scheme, host, port, path and query; case-insensitive host; a trailing slash and the fragment are ignored. */
        fun canonical(url: String): String {
            val uri = parseUri(url) ?: return url
            val scheme = uri.scheme?.lowercase() ?: return url
            val host = uri.host?.lowercase() ?: return url
            val port = if (uri.port == 443 || uri.port < 0) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").removeSuffix("/")
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "$scheme://$host$port$path$query"
        }

        /** An address a favourite may hold: an HTTPS page the browser opens, typed or full. */
        fun address(text: String): String? = BrowserURLPolicy.typedAddress(text)?.toString()

        fun opens(url: String): Boolean {
            val uri = parseUri(url) ?: return false
            return uri.scheme?.lowercase() == "https" && BrowserURLPolicy.decide(uri, mainFrame = true) == BrowserURLPolicy.Decision.Load
        }

        fun httpsIcon(url: String?): String? = url?.takeIf { opens(it) }

        fun title(text: String, url: String): String {
            val trimmed = text.trim()
            return JsonFields.prefix(trimmed.ifEmpty { parseUri(url)?.host?.lowercase() ?: url }, MAX_TITLE_LENGTH)
        }

        fun isPNG(data: ByteArray): Boolean = data.size >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)

        /** User-perceived characters (an emoji with modifiers counts as one where the platform can tell). */
        fun characterCount(text: String): Int {
            val it = BreakIterator.getCharacterInstance().apply { setText(text) }
            var count = 0
            while (it.next() != BreakIterator.DONE) count++
            return count
        }

        internal fun firstCharacter(text: String): String? {
            if (text.isEmpty()) return null
            val it = BreakIterator.getCharacterInstance().apply { setText(text) }
            return text.substring(0, it.next())
        }

        /** The stored list; anything unreadable, unopenable or oversized is dropped rather than shown. */
        fun decode(text: String?): BrowserFavourites {
            val stored = text?.let { JsonFields.parseObject(it) } ?: return BrowserFavourites()
            val rows = JsonFields.array(stored["items"]) ?: return BrowserFavourites()
            val seen = HashSet<String>()
            val items = rows.take(MAX_COUNT).mapNotNull { row ->
                val o = row as? JsonObject ?: return@mapNotNull null
                val id = JsonFields.string(o["id"])?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@mapNotNull null
                val url = JsonFields.string(o["url"]) ?: return@mapNotNull null
                if (!opens(url) || !seen.add(canonical(url))) return@mapNotNull null
                val image = JsonFields.string(o["image"])?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                    ?.takeIf { isPNG(it) && it.size <= MAX_IMAGE_BYTES }
                val symbol = JsonFields.string(o["symbol"])?.takeIf { it.isNotEmpty() && characterCount(it) <= MAX_SYMBOL_LENGTH }
                BrowserFavourite(id, title(JsonFields.string(o["title"]) ?: "", url), url, httpsIcon(JsonFields.string(o["siteIcon"])), symbol, image)
            }
            return BrowserFavourites(items)
        }
    }

    var items: List<BrowserFavourite> = items
        private set

    fun favourite(url: String?): BrowserFavourite? {
        val key = canonical(url ?: return null)
        return items.firstOrNull { canonical(it.url) == key }
    }

    /** Saves a page at the end of the list. The title defaults to the host. */
    fun add(title: String, url: String, siteIcon: String? = null): BrowserFavourite {
        if (items.size >= MAX_COUNT) throw BrowserFavouriteException(BrowserFavouriteException.Reason.FULL)
        if (!opens(url)) throw BrowserFavouriteException(BrowserFavouriteException.Reason.ADDRESS)
        if (favourite(url) != null) throw BrowserFavouriteException(BrowserFavouriteException.Reason.DUPLICATE)
        val made = BrowserFavourite(title = title(title, url), url = url, siteIcon = httpsIcon(siteIcon))
        items = items + made
        return made
    }

    /** Replaces a favourite after checking it. A changed address drops the site icon found for the old one. */
    fun update(favourite: BrowserFavourite) {
        val index = items.indexOfFirst { it.id == favourite.id }.takeIf { it >= 0 } ?: return
        if (!opens(favourite.url)) throw BrowserFavouriteException(BrowserFavouriteException.Reason.ADDRESS)
        favourite(favourite.url)?.let { if (it.id != favourite.id) throw BrowserFavouriteException(BrowserFavouriteException.Reason.DUPLICATE) }
        val symbol = favourite.symbol?.trim().orEmpty()
        if (characterCount(symbol) > MAX_SYMBOL_LENGTH) throw BrowserFavouriteException(BrowserFavouriteException.Reason.SYMBOL)
        favourite.image?.let { if (!isPNG(it) || it.size > MAX_IMAGE_BYTES) throw BrowserFavouriteException(BrowserFavouriteException.Reason.IMAGE) }
        val sameAddress = canonical(favourite.url) == canonical(items[index].url)
        val next = favourite.copy(
            title = title(favourite.title, favourite.url),
            symbol = symbol.ifEmpty { null },
            siteIcon = if (sameAddress) httpsIcon(favourite.siteIcon) else null,
        )
        items = items.toMutableList().also { it[index] = next }
    }

    /** Adds a new favourite with its name, address and icon in one step, or updates an existing one. */
    fun save(favourite: BrowserFavourite) {
        if (items.any { it.id == favourite.id }) { update(favourite); return }
        val added = add(favourite.title, favourite.url)
        try { update(favourite.copy(id = added.id)) } catch (e: BrowserFavouriteException) { remove(added.id); throw e }
    }

    /** Records the icon a saved page advertised (found after saving, once the page answered). */
    fun setSiteIcon(icon: String?, id: UUID) {
        val index = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val https = httpsIcon(icon) ?: return
        items = items.toMutableList().also { it[index] = it[index].copy(siteIcon = https) }
    }

    /** Takes another list's items (a freshly loaded stored list). */
    fun replaceWith(other: BrowserFavourites) { items = other.items }

    fun remove(id: UUID) { items = items.filterNot { it.id == id } }

    /** Moves a favourite to `index` in the list as it is after removing it (clamped to the ends). */
    fun move(id: UUID, index: Int) {
        val from = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(index.coerceIn(0, list.size), item)
        items = list
    }

    /** Moves a favourite one place earlier (negative) or later (positive). */
    fun moveBy(id: UUID, by: Int) {
        val from = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        move(id, from + by)
    }

    /** Null when empty, so clearing the list removes the stored value. */
    fun encoded(): String? {
        if (items.isEmpty()) return null
        val rows = items.map { item ->
            buildJsonObject {
                put("id", item.id.toString())
                put("title", item.title)
                put("url", item.url)
                item.siteIcon?.let { put("siteIcon", it) }
                item.symbol?.let { put("symbol", it) }
                item.image?.let { put("image", Base64.getEncoder().encodeToString(it)) }
            }
        }
        return JsonFields.encode(JsonObject(mapOf("version" to JsonPrimitive(1), "items" to JsonArray(rows))))
    }
}
