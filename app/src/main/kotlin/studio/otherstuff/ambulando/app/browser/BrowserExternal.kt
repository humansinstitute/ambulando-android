package studio.otherstuff.ambulando.app.browser

import android.content.Intent
import android.net.Uri

/** Reconstruct a browsable VIEW intent; never forward page-supplied components, flags, extras or fallback URLs. */
object BrowserExternal {
    private val signerSchemes = setOf("nostrconnect", "nostrsigner")
    fun intent(url: String): Intent {
        val uri = if (url.startsWith("intent:", ignoreCase = true)) {
            val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            require(parsed.component == null && parsed.selector == null && parsed.action in setOf(null, Intent.ACTION_VIEW)) { "Unsafe signer intent." }
            val data = parsed.data ?: error("Signer intent has no address.")
            require(data.scheme in signerSchemes) { "Only Nostr signer intents are supported." }
            data
        } else Uri.parse(url)
        require(uri.scheme in studio.otherstuff.ambulando.core.browser.BrowserURLPolicy.externalSchemes) { "Unsupported external address." }
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}

class BrowserExternalRequest(val tab: BrowserTab, val url: String) {
    private val document = tab.documentId
    private val origin = tab.origin
    val current: Boolean get() = tab.documentId == document && tab.origin == origin
}
