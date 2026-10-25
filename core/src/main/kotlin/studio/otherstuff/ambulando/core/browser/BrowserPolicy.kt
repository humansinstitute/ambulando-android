package studio.otherstuff.ambulando.core.browser

import java.net.URI

/** Parses an address the way `URLComponents(string:)` does: strictly, with null for anything that is not a URI. */
internal fun parseUri(text: String?): URI? = text?.let { runCatching { URI(it) }.getOrNull() }

/**
 * What the in-app browser does with an address. Pages are HTTPS only; a few schemes that belong to other apps (mail,
 * phone, messages, video calls, Lightning wallets, Nostr clients) are handed to the system after the user confirms;
 * everything else is refused. Subframes may also use the local schemes pages build themselves (`about:`, `data:`,
 * `blob:`).
 */
object BrowserURLPolicy {
    sealed interface Decision {
        data object Load : Decision
        data class External(val url: URI) : Decision
        data class Refuse(val reason: String) : Decision
    }

    val externalSchemes: Set<String> = setOf("mailto", "tel", "sms", "facetime", "facetime-audio", "lightning", "nostr")
    internal val frameSchemes: Set<String> = setOf("about", "data", "blob")
    internal val knownSchemes: Set<String> = externalSchemes + frameSchemes + setOf("http", "file", "javascript", "ftp", "ws", "wss", "storage")

    fun decide(url: URI, mainFrame: Boolean): Decision {
        val scheme = url.scheme?.lowercase() ?: ""
        if (scheme == "https") return httpsProblem(url)?.let { Decision.Refuse(it) } ?: Decision.Load
        // `about:blank` is where `window.open()` and empty frames start; it has no origin of its own to sign for.
        if (scheme == "about" && url.toString().drop("about:".length).lowercase() in setOf("blank", "srcdoc")) return Decision.Load
        if (!mainFrame && scheme in frameSchemes) return Decision.Load
        if (scheme in externalSchemes) return Decision.External(url)
        if (scheme == "http") return Decision.Refuse("Only secure (HTTPS) pages open in the Ambulando browser.")
        return Decision.Refuse("Ambulando does not open ${if (scheme.isEmpty()) "this" else "$scheme:"} addresses.")
    }

    /** Why an HTTPS address is not loaded, or null. */
    internal fun httpsProblem(url: URI): String? {
        val host = url.host
        if (host.isNullOrEmpty()) return "This address has no host."
        if (url.rawUserInfo != null) return "Addresses with a user name or password are not opened."
        if (host.lowercase().endsWith(".fips")) return "FIPS addresses are not available in the Android app."
        return null
    }

    /** `https://host[:port]` of a page that can use `window.nostr`, or null. */
    fun origin(url: URI?): String? {
        if (url == null || url.scheme?.lowercase() != "https" || httpsProblem(url) != null) return null
        val host = url.host?.lowercase() ?: return null
        return origin("https", host, if (url.port < 0) 0 else url.port)
    }

    /** The origin string for a web view's security-origin parts (port 0 means the scheme's default). */
    fun origin(scheme: String, host: String, port: Int): String? {
        if (scheme.lowercase() != "https" || host.isEmpty()) return null
        val base = "https://${host.lowercase()}"
        return if (port == 0 || port == 443) base else "$base:$port"
    }

    /**
     * What the address field opens: an HTTPS URL as typed, a bare host or path with `https://` added, or null (a plain
     * `http://` address, a refused scheme or text that is not an address).
     */
    fun typedAddress(text: String): URI? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains(" ")) return null
        val url = parseUri(trimmed)
        val scheme = url?.scheme?.lowercase()
        if (url != null && scheme != null) {
            if (scheme == "https") return if (decide(url, mainFrame = true) == Decision.Load) url else null
            // Any other scheme the browser knows is not a page to open from the address field. Text such as
            // "example.com:8443/x" parses with a "scheme" of "example.com" and is treated as a host below.
            if (scheme in knownSchemes) return null
        }
        if (!trimmed.contains(".")) return null
        val prefixed = parseUri("https://$trimmed") ?: return null
        return if (decide(prefixed, mainFrame = true) == Decision.Load) prefixed else null
    }
}

/**
 * Origin rules for publisher-owned web content (WApps, artifacts). HTTPS only, no embedded credentials, and the
 * content is confined to the origin it was opened for.
 */
object WebContentPolicy {
    fun launchURL(text: String?): URI? {
        val url = parseUri(text?.trim()) ?: return null
        val host = url.host
        if (url.scheme?.lowercase() != "https" || host.isNullOrEmpty() || url.rawUserInfo != null) return null
        if (host.lowercase().endsWith(".fips")) return null
        return url
    }

    /** `scheme://host[:port]` of a URL, lower-cased. */
    fun origin(url: URI): String? {
        val scheme = url.scheme?.lowercase() ?: return null
        val host = url.host?.lowercase() ?: return null
        return "$scheme://$host" + if (url.port >= 0) ":${url.port}" else ""
    }

    /** Whether a navigation may stay inside the labelled view opened for `origin`. */
    fun allows(url: URI, within: String): Boolean {
        if (url.scheme == "about") return url.toString() == "about:blank"
        return origin(url) == within
    }

    sealed interface Decision {
        /** Load inside the labelled view. */
        data object Allow : Decision
        /** Not loaded. `offerExternal` is the HTTPS URL the user may choose to open in the system browser. */
        data class Refuse(val offerExternal: URI?) : Decision
    }

    /**
     * The single navigation rule for publisher web content. It is applied to every navigation, including server
     * redirects (a redirect is a new navigation to its target) and links asking for a new window, which never open a
     * second view: a same-origin target loads in place, anything else is refused and only offered outside if HTTPS.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decide(url: URI?, within: String, newWindow: Boolean): Decision {
        if (url == null) return Decision.Refuse(null)
        if (allows(url, within)) return Decision.Allow
        return Decision.Refuse(launchURL(url.toString()))
    }
}
