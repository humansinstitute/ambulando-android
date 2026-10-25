package studio.otherstuff.ambulando.core.transport

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed class TransferError(message: String) : Exception(message, null, false, false) {
    data object UnsafeURL : TransferError("Refused a transfer URL that is not a presigned HTTPS object-store address.")
    data class Failed(val status: Int?, val reason: String?) : TransferError(
        "The transfer failed" + (status?.let { " (HTTP $it)" } ?: "") + (reason?.let { ": $it" } ?: "") + ".",
    )
    data object Lost : TransferError("The system no longer has this transfer; it will be started again.")
}

/**
 * Policy for transfers to and from presigned object-store URLs (the pure part of the iOS background transfer
 * coordinator; the platform scheduler that keeps transfers running while the app is suspended lives in the app).
 *
 * Only **unsigned** requests to a host other than the Tower origin are accepted: the NIP-98 identity, cookies and any
 * Authorization header never reach an object store, and redirects are refused by the transport. Tower's own signed
 * storage routes stay in-process.
 */
object TransferPolicy {
    /** Accepts only presigned HTTPS URLs on a host other than the pinned Tower host, outside `/api/v4/`. */
    fun validate(url: String, towerHost: String?): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: throw TransferError.UnsafeURL
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null ||
            parsed.host.isEmpty() || parsed.host == towerHost?.lowercase() || decodedPath(parsed).startsWith("/api/v4/")
        ) throw TransferError.UnsafeURL
        return parsed
    }

    /** An unsigned request: no Authorization header, no cookies (the transport has no cookie jar). */
    fun unsignedRequest(url: String, method: String, contentType: String?, body: ByteArray? = null): HttpRequest =
        HttpRequest(method, url, contentType?.let { mapOf("Content-Type" to it) } ?: emptyMap(), body)

    internal fun decodedPath(url: HttpUrl): String = url.pathSegments.joinToString("/", prefix = "/")
}

/**
 * Out-of-process transfer seam for presigned object-store URLs (an Android scheduler that survives the process). Each
 * transfer has a stable [key] (the storage object id for uploads) so a transfer that finished while the app was not
 * running is not repeated. Implementations must send exactly [TransferPolicy.unsignedRequest] and validate with
 * [TransferPolicy.validate].
 */
interface ObjectTransfers {
    suspend fun upload(key: String, url: String, bytes: ByteArray, contentType: String, towerHost: String?)
}
