package studio.otherstuff.ambulando.core.transport

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI

/** Classify with the sending parser before inspecting canonical spelling. Never route aliases through ordinary I/O. */
object FipsUrlPolicy {
    fun isProtected(url: HttpUrl): Boolean = url.host.lowercase().trimEnd('.').endsWith(".fips")

    fun endpoint(url: String): FipsPeerEndpoint? {
        val parsed = url.toHttpUrl()
        if (!isProtected(parsed)) return null
        val raw = runCatching { URI(url) }.getOrNull() ?: throw TowerError.FipsNotSupported
        if (raw.scheme != "http" || raw.rawUserInfo != null || raw.host != parsed.host ||
            raw.host != raw.host?.lowercase() || parsed.host.endsWith('.') || raw.rawFragment != null
        ) throw TowerError.FipsNotSupported
        return runCatching { FipsPeerEndpoint(parsed.host.removeSuffix(".fips"), parsed.host, parsed.port) }
            .getOrElse { throw TowerError.FipsNotSupported }
    }
}
