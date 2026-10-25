package studio.otherstuff.ambulando.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import studio.otherstuff.ambulando.core.media.PngChunks
import studio.otherstuff.ambulando.core.profiles.AvatarCachePolicy
import studio.otherstuff.ambulando.core.profiles.AvatarError
import studio.otherstuff.ambulando.core.profiles.AvatarURLPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Fetches avatar bytes. Production uses [OkHttpAvatarTransport]; tests and sample-data mode inject their own. */
fun interface AvatarTransport {
    /** The body of [url] (at most [maxBytes]) and its content type. Follows only policy-checked redirects, no credentials. */
    suspend fun fetch(url: URI, maxBytes: Int): Pair<ByteArray, String?>
}

/**
 * Avatar requests go to whatever host a profile names, so they carry nothing that identifies the user beyond the request
 * itself: no Authorization header, no cookies (neither sent nor stored) and no HTTP cache. Redirects are followed at most
 * three times and only to an address the avatar URL policy accepts (Blossom servers redirect every picture to their CDN).
 */
class OkHttpAvatarTransport(timeoutSeconds: Long = 15) : AvatarTransport {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .cache(null)
        .callTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: URI, maxBytes: Int): Pair<ByteArray, String?> = withContext(Dispatchers.IO) {
        var target = url
        var redirects = 0
        while (true) {
            val request = Request.Builder().url(target.toString()).get().header("Accept", "image/*").build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    redirects += 1
                    val location = response.header("Location")?.let { runCatching { target.resolve(it).toString() }.getOrNull() }
                    target = location?.let { AvatarCachePolicy.followRedirect(it, redirects) } ?: throw AvatarError.HttpStatus(response.code)
                    return@use
                }
                if (response.code != 200) throw AvatarError.HttpStatus(response.code)
                val body = response.body
                if (body.contentLength() > maxBytes) throw AvatarError.TooLarge
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > maxBytes) throw AvatarError.TooLarge
                    }
                }
                return@withContext out.toByteArray() to response.header("Content-Type")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
}

/**
 * Downloaded avatars, stored on disk as small re-encoded PNG thumbnails named by the SHA-256 of their URL (see
 * [AvatarCachePolicy]). Downloads run off the main thread, at most three at once and one per URL; a failed URL is not
 * asked for again for a while. Decoded thumbnails are kept in a small memory cache so a row draws a known avatar at
 * once; anything else shows initials while it loads.
 */
class AvatarStore(root: File, private val transport: AvatarTransport, private val now: () -> Instant = { Instant.now() }) {
    val directory: File = File(root, AvatarCachePolicy.CACHE_VERSION)
    private val mutex = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<File?>>()
    private val failedUntil = HashMap<String, Instant>()
    private val permits = Semaphore(AvatarCachePolicy.MAX_CONCURRENT)
    private val memory = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Off when the user turned off public profiles: nothing new is downloaded; cached thumbnails are still shown. */
    @Volatile var enabled: Boolean = true
        private set

    init {
        root.listFiles()?.filter { it.name in AvatarCachePolicy.staleEntries(root.list()?.toList() ?: emptyList()) }?.forEach { it.deleteRecursively() }
        directory.mkdirs()
    }

    suspend fun setEnabled(on: Boolean) = mutex.withLock {
        enabled = on
        if (!on) {
            inFlight.values.forEach { it.complete(null) }
            inFlight.clear()
        }
    }

    fun cachedFile(url: URI): File? = File(directory, AvatarCachePolicy.fileName(url)).takeIf { it.exists() }

    /** A decoded thumbnail already in memory (synchronous, for the first frame). */
    fun memoryImage(url: String): ImageBitmap? = memory.get(url)

    /** The decoded thumbnail for [url], downloading it if needed; null when refused, recently failed or unusable. */
    suspend fun image(url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        val uri = AvatarURLPolicy.validate(url) ?: return null
        val file = thumbnail(uri) ?: return null
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) } ?: return null
        return bitmap.asImageBitmap().also { memory.put(url, it) }
    }

    suspend fun thumbnail(url: URI): File? {
        cachedFile(url)?.let { return it }
        val key = url.toString()
        val (deferred, owner) = mutex.withLock {
            if (!enabled) return null
            failedUntil[key]?.let { if (it.isAfter(now())) return null }
            inFlight[key]?.let { return@withLock it to false }
            val made = CompletableDeferred<File?>()
            inFlight[key] = made
            made to true
        }
        if (owner) {
            val result = try {
                download(url)
            } catch (c: CancellationException) {
                deferred.complete(null)
                mutex.withLock { inFlight.remove(key) }
                throw c
            }
            mutex.withLock { if (inFlight[key] === deferred) inFlight.remove(key) }
            deferred.complete(result)
        }
        return deferred.await()
    }

    private suspend fun download(url: URI): File? = permits.withPermit {
        if (!enabled) return@withPermit null
        try {
            val (data, type) = transport.fetch(url, AvatarCachePolicy.MAX_BYTES)
            if (!AvatarCachePolicy.acceptsContentType(type)) throw AvatarError.NotAnImage
            // Turned off while the bytes were arriving: nothing is decoded or stored.
            if (!enabled) return@withPermit null
            val png = withContext(Dispatchers.Default) { thumbnailPng(data) }
            val file = File(directory, AvatarCachePolicy.fileName(url))
            withContext(Dispatchers.IO) {
                val tmp = File(directory, file.name + ".tmp")
                tmp.writeBytes(png)
                tmp.renameTo(file)
                prune()
            }
            file
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            mutex.withLock {
                failedUntil[url.toString()] = AvatarCachePolicy.retryAfter(e, now())
                if (failedUntil.size > 500) failedUntil.entries.removeIf { !it.value.isAfter(now()) }
            }
            null
        }
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.name.endsWith(".png") } ?: return
        val doomed = AvatarCachePolicy.filesToPrune(files.map { AvatarCachePolicy.CachedFile(it.name, Instant.ofEpochMilli(it.lastModified()), it.length()) })
        doomed.forEach { File(directory, it).delete() }
    }

    /** Removes every saved picture (disconnect). */
    fun clear() {
        memory.evictAll()
        directory.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /**
         * Validates and shrinks downloaded bytes: a decodable still image of at most 40 MP, decoded at a reduced sample
         * size (never the full bitmap when large) to ≤ 192 px and re-encoded as PNG with pixel chunks only.
         */
        fun thumbnailPng(data: ByteArray, maxPixels: Int = AvatarCachePolicy.THUMBNAIL_PIXELS): ByteArray {
            if (data.isEmpty() || data.size > AvatarCachePolicy.MAX_BYTES) throw AvatarError.TooLarge
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AvatarError.NotAnImage
            if (bounds.outWidth.toLong() * bounds.outHeight > AvatarCachePolicy.MAX_SOURCE_PIXELS) throw AvatarError.TooLarge
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw AvatarError.Undecodable
            val long = maxOf(decoded.width, decoded.height)
            val scaled = if (long > maxPixels) {
                val f = maxPixels.toFloat() / long
                Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * f).toInt()), maxOf(1, (decoded.height * f).toInt()), true)
            } else decoded
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            return PngChunks.keepPixelChunks(out.toByteArray())
        }
    }
}
