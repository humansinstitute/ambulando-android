package studio.otherstuff.ambulando.core.profiles

import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import java.net.URI
import java.time.Duration
import java.time.Instant

/** Why an avatar download was not used. */
sealed class AvatarError : Exception() {
    data object RefusedURL : AvatarError()
    data class HttpStatus(val status: Int) : AvatarError()
    data object NotAnImage : AvatarError()
    data object TooLarge : AvatarError()
    data object Undecodable : AvatarError()
}

/**
 * The platform-independent rules of the avatar cache. Downloaded avatars are stored on disk as small re-encoded PNG
 * thumbnails named by the SHA-256 of their URL, inside one directory per cache version; a changed profile picture
 * is a different URL and so a different file, and old files age out of the bounded cache.
 *
 * Decoding and re-encoding the image (and the download itself) are platform work done by the app module; this object
 * holds the limits, names, redirect rule, retry policy and pruning choice they follow.
 *
 * Avatar requests go to whatever host a profile names, so they carry nothing that identifies the user to it beyond
 * the request itself: no Authorization header, no cookies (neither sent nor stored) and no shared HTTP cache.
 */
object AvatarCachePolicy {
    /** Largest download accepted (bytes, 5 MiB). The pixel bound and the thumbnail decode keep memory small. */
    const val MAX_BYTES = 5 * 1024 * 1024
    /** Largest source image accepted (pixels); bigger images are refused before decoding. */
    const val MAX_SOURCE_PIXELS = 40_000_000
    const val THUMBNAIL_PIXELS = 192
    const val MAX_FILES = 400
    const val MAX_DISK_BYTES = 16 * 1024 * 1024
    const val MAX_CONCURRENT = 3
    /** Redirects followed per request, each only to an address [AvatarURLPolicy] accepts. */
    const val MAX_REDIRECTS = 3
    /** Bumped when the stored thumbnail format changes; directories of other versions are removed. */
    const val CACHE_VERSION = "v1"
    /** Image problems are permanent for a URL for this long; network trouble (and 429/503) is retried sooner. */
    val PERMANENT_FAILURE_BACKOFF: Duration = Duration.ofHours(6)
    val TRANSIENT_FAILURE_BACKOFF: Duration = Duration.ofMinutes(5)
    internal const val MAX_FAILURE_ENTRIES = 500

    /** Thumbnail file name for an avatar URL. */
    fun fileName(url: URI): String = Sha256Hex.of(url.toString()) + ".png"

    /** Entries of the cache root other than the current version directory, which are removed on open. */
    fun staleEntries(rootEntries: List<String>): List<String> = rootEntries.filter { it != CACHE_VERSION }

    /** The next request of a redirect chain, or null to stop (which then fails as a non-200 answer). */
    fun followRedirect(location: String, redirectNumber: Int): URI? =
        if (redirectNumber > MAX_REDIRECTS) null else AvatarURLPolicy.validate(location)

    /** Whether a response content type may be an image (a missing type is decided by the decoder). */
    fun acceptsContentType(contentType: String?): Boolean = contentType == null || contentType.lowercase().startsWith("image/")

    /** When a failed URL may be asked for again. */
    fun retryAfter(error: Throwable, now: Instant): Instant {
        val permanent = (error as? AvatarError)?.let { it != AvatarError.HttpStatus(429) && it != AvatarError.HttpStatus(503) } ?: false
        return now.plus(if (permanent) PERMANENT_FAILURE_BACKOFF else TRANSIENT_FAILURE_BACKOFF)
    }

    /** One cached file as the pruner sees it. */
    data class CachedFile(val name: String, val modifiedAt: Instant, val sizeBytes: Long)

    /** Files to delete, oldest first, so the cache stays within [MAX_FILES] and [MAX_DISK_BYTES]. */
    fun filesToPrune(files: List<CachedFile>, maxFiles: Int = MAX_FILES, maxDiskBytes: Long = MAX_DISK_BYTES.toLong()): List<String> {
        var total = files.sumOf { it.sizeBytes }
        var count = files.size
        if (count <= maxFiles && total <= maxDiskBytes) return emptyList()
        val out = ArrayList<String>()
        for (file in files.sortedBy { it.modifiedAt }) {
            if (count <= maxFiles && total <= maxDiskBytes) break
            out.add(file.name)
            total -= file.sizeBytes
            count -= 1
        }
        return out
    }
}
