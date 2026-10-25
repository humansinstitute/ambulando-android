package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp SSE transport: redirects refused, no cookies or cache, a 90 s idle (read) timeout and no overall call
 * timeout, and a bounded receive buffer per connection ([bufferLimit]). One daemon reader thread per connection moves
 * body bytes into the buffer; if the consumer falls behind, the buffer overflows, the connection is cancelled and the
 * stream reconnects from the stored cursor.
 */
class OkHttpEventStreamTransport(
    private val bufferLimit: Int = DEFAULT_BUFFER_LIMIT,
    base: OkHttpClient = OkHttpClient(),
) : EventStreamTransport {
    val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    override suspend fun open(request: HttpRequest): Pair<HttpResponseHead, EventChunks> {
        val call = client.newCall(OkHttpTransport.toOkHttp(request))
        val response: Response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
        val head = HttpResponseHead(response.code, OkHttpTransport.headersOf(response))
        val buffer = BoundedChunkBuffer(bufferLimit)
        thread(isDaemon = true, name = "event-stream-reader") {
            try {
                response.body.source().use { source ->
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = source.read(chunk, 0, chunk.size)
                        if (n < 0) break
                        if (n == 0) continue
                        if (!buffer.append(chunk.copyOf(n))) {
                            call.cancel()   // overflow or consumer gone: fail the stream, reconnect from the stored cursor
                            return@thread
                        }
                    }
                }
                buffer.finish()
            } catch (e: IOException) {
                buffer.finish(e)
            } finally {
                response.close()
            }
        }
        val chunks = object : EventChunks {
            override suspend fun next(): ByteArray? = try {
                buffer.next()
            } catch (e: kotlinx.coroutines.CancellationException) {
                call.cancel()
                buffer.cancelled()
                throw e
            }

            override fun cancel() {
                call.cancel()
                buffer.cancelled()
            }
        }
        return head to chunks
    }

    companion object {
        const val DEFAULT_BUFFER_LIMIT = 2 * 1024 * 1024
    }
}
