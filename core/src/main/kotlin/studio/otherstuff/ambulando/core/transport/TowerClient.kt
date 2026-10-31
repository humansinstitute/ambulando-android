package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.crypto.NIP98
import studio.otherstuff.ambulando.core.nostr.EventSigner
import java.net.URI

/** Tower failures. Compared by value; they carry no stack trace (they are expected outcomes, not bugs). */
sealed class TowerError(message: String) : Exception(message, null, false, false) {
    data object UnsafeOrigin : TowerError("Use an HTTPS origin without credentials, path, query or fragment.")
    data object FipsNotSupported : TowerError(
        "This Android app connects over HTTPS only. FIPS (.fips) endpoints need the Wingman app shell; nothing was sent over another transport.",
    )
    data object UnsafePath : TowerError("Refused a request outside the Tower API.")
    data class Http(val status: Int, val code: String?, val stage: String?) : TowerError(
        "Tower returned HTTP $status" + (stage?.let { " during $it" } ?: "") + (code?.let { " (${TowerClient.summary(it)})" } ?: "") + ".",
    )
    data class Malformed(val what: String) : TowerError("Tower returned an unexpected $what response.")
    data class ListLimitReached(val what: String) : TowerError(
        "Tower returned a full page of $what without a continuation cursor; the list may be incomplete.",
    )
    data class Descriptor(val reason: String) : TowerError("nStack descriptor rejected: $reason.")
}

/**
 * Validated HTTPS Tower origin, normalised to `https://host[:port]` (lower-case host, no default port). FIPS hosts are
 * refused explicitly: the user selected HTTPS, and this client never falls back across a transport boundary.
 */
class TowerOrigin(value: String) {
    /** Lower-case host. */
    val host: String

    /** Explicit non-default port, or null for 443. */
    val port: Int?

    /** `https://host[:port]` without a trailing slash. */
    val url: String

    init {
        val trimmed = value.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw TowerError.UnsafeOrigin
        val rawHost = uri.host
        if (rawHost.isNullOrEmpty()) throw TowerError.UnsafeOrigin
        if (rawHost.lowercase().endsWith(".fips")) throw TowerError.FipsNotSupported
        val path = uri.rawPath ?: ""
        if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            !(path.isEmpty() || path == "/")
        ) throw TowerError.UnsafeOrigin
        host = rawHost.lowercase()
        port = uri.port.takeIf { it >= 0 && it != 443 }
        url = "https://" + host + (port?.let { ":$it" } ?: "")
        if (url.toHttpUrlOrNull() == null) throw TowerError.UnsafeOrigin
    }

    companion object {
        /**
         * The only two non-Tower paths a signed request may name: managed-app activation against the HTTPS Autopilot origin the
         * person typed and confirmed (catalog read, then processing of one saved install intent). Exact matches, never a prefix.
         */
        val AUTOPILOT_ACTIVATION_PATHS = setOf("/api/wapps/activation-catalog", "/api/wapps/install-intents/process")
    }

    /** Absolute URL for an exact API path plus optional query items (encoded once, here). */
    fun url(path: String, query: List<Pair<String, String>> = emptyList()): String {
        if (!(path.startsWith("/api/v4/") || path == "/.well-known/nstack.json" || path in AUTOPILOT_ACTIVATION_PATHS) || path.contains("..") || path.contains("?") ||
            path.contains("#")
        ) throw TowerError.UnsafePath
        val builder = HttpUrl.Builder().scheme("https").host(host).port(port ?: 443)
        runCatching { builder.encodedPath(path) }.getOrElse { throw TowerError.UnsafePath }
        for ((name, value) in query) builder.addQueryParameter(name, value)
        val result = runCatching { builder.build() }.getOrNull() ?: throw TowerError.UnsafePath
        if (result.host != host || result.scheme != "https") throw TowerError.UnsafePath
        return result.toString()
    }

    override fun toString(): String = url
    override fun equals(other: Any?): Boolean = other is TowerOrigin && other.url == url
    override fun hashCode(): Int = url.hashCode()
}

/** One HTTP request as the transport sends it: the exact URL and body bytes that were signed. */
class HttpRequest(
    val method: String,
    val url: String,
    /** Header names as set; look up case-insensitively with [header]. */
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun withHeader(name: String, value: String): HttpRequest =
        HttpRequest(method, url, headers.filterKeys { !it.equals(name, ignoreCase = true) } + (name to value), body)

    override fun toString(): String = "HttpRequest($method $url)"
}

class HttpResponse(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * Minimal transport seam: production uses [OkHttpTransport]; tests inject a recorder. Implementations never follow
 * redirects (a 3xx is returned as the response), keep no cookies and no cache.
 */
interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Status and body of a signed request, for callers that classify failures themselves. */
class Exchange(val status: Int, val body: ByteArray)

/** Signed Tower API client. Each request gets a fresh NIP-98 event over the exact URL and body bytes sent. */
class TowerClient(val origin: TowerOrigin, val transport: HttpTransport, val signer: EventSigner?) {
    companion object {
        const val APP_NPUB_HEADER = "x-flightdeck-pg-app-npub"
        const val DESCRIPTOR_PATH = "/.well-known/nstack.json"

        /** One-line, length-bounded form of a Tower error code for messages. */
        fun summary(detail: String): String {
            val spaced = detail.map { c ->
                val type = Character.getType(c)
                if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) ' ' else c
            }.joinToString("")
            val line = spaced.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            return if (line.length > 80) line.take(80) + "…" else line
        }

        fun stage(path: String): String? = when {
            path == DESCRIPTOR_PATH -> "service discovery"
            path.endsWith("/flightdeck-pg/workspaces") -> "workspace discovery"
            path.endsWith("/scopes") -> "scope list"
            path.endsWith("/channels") -> "channel list"
            path.endsWith("/messages") -> "messages"
            path.endsWith("/threads") || path.contains("/threads/") -> "threads"
            path.endsWith("/members") -> "member directory"
            path.endsWith("/scope-order") -> "scope order"
            else -> null
        }

        const val LIST_PAGE_LIMIT = 200
    }

    /** Unsigned GET for public metadata (the nStack descriptor only). */
    suspend fun getPublic(path: String): ByteArray {
        if (path != DESCRIPTOR_PATH) throw TowerError.UnsafePath
        val request = HttpRequest("GET", origin.url(path), mapOf("Accept" to "application/json"))
        return perform(request, path)
    }

    suspend fun request(
        method: String = "GET", path: String, query: List<Pair<String, String>> = emptyList(), appNpub: String? = null,
        jsonBody: ByteArray? = null,
    ): ByteArray = perform(signedRequest(method, path, query, appNpub, jsonBody), path)

    /**
     * Signed request that returns any HTTP status with its body, for callers that classify failures themselves
     * (the outbox). Every call signs a fresh NIP-98 event over exactly [jsonBody].
     */
    suspend fun exchange(
        method: String, path: String, appNpub: String?, jsonBody: ByteArray?, query: List<Pair<String, String>> = emptyList(),
    ): Exchange {
        val request = signedRequest(method, path, query, appNpub, jsonBody)
        currentCoroutineContext().ensureActive()
        val response = transport.send(request)
        currentCoroutineContext().ensureActive()
        return Exchange(response.status, response.body)
    }

    /** A signed request that the caller sends itself (the event stream). Same NIP-98 rules as [request]. */
    suspend fun signed(method: String, path: String, query: List<Pair<String, String>>, appNpub: String?, jsonBody: ByteArray?): HttpRequest =
        signedRequest(method, path, query, appNpub, jsonBody)

    private suspend fun signedRequest(
        method: String, path: String, query: List<Pair<String, String>>, appNpub: String?, jsonBody: ByteArray?,
    ): HttpRequest {
        val signer = signer ?: throw IdentityError.Locked
        currentCoroutineContext().ensureActive()
        val url = origin.url(path, query)
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = NIP98.authorization(url, method, jsonBody, signer)
        headers["Accept"] = "application/json"
        if (appNpub != null) headers[APP_NPUB_HEADER] = appNpub
        if (jsonBody != null) headers["Content-Type"] = "application/json"
        return HttpRequest(method.uppercase(), url, headers, jsonBody)
    }

    private suspend fun perform(request: HttpRequest, path: String): ByteArray {
        currentCoroutineContext().ensureActive()
        val response = transport.send(request)
        currentCoroutineContext().ensureActive()
        if (response.status !in 200..299) {
            val failure = parseJsonObjectOrNull(response.body)
            throw TowerError.Http(response.status, failure?.string("code") ?: failure?.string("error"), stage(path))
        }
        return response.body
    }

    /**
     * Reads every page of a list route. Tower list routes cap `limit` at 200 and some return `next_cursor: null`
     * always, so a full page without a cursor is reported, not silently truncated.
     */
    suspend fun list(path: String, appNpub: String?, key: String): List<JsonObject> {
        val rows = ArrayList<JsonObject>()
        var cursor: String? = null
        val seen = HashSet<String>()
        do {
            val query = mutableListOf("limit" to LIST_PAGE_LIMIT.toString())
            cursor?.let { query.add("cursor" to it) }
            val data = request(path = path, query = query, appNpub = appNpub)
            val o = parseJsonObject(data, key)
            val page = o.objects(key) ?: throw TowerError.Malformed(key)
            rows.addAll(page)
            cursor = o.string("next_cursor")
            if (cursor != null) {
                if (cursor.isEmpty() || !seen.add(cursor)) throw TowerError.Malformed("$key cursor")
            } else if (o.bool("has_more") == true || page.size >= LIST_PAGE_LIMIT) {
                throw TowerError.ListLimitReached(key)
            }
        } while (cursor != null)
        return rows
    }
}
