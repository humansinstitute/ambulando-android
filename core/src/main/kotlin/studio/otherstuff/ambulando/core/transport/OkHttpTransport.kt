package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp transport with the client policy every Tower and object-store request needs, applied on top of [base]:
 * redirects refused (a 3xx comes back as the response, so a signed request is never replayed to another URL), no cookie
 * jar, no response cache, a [timeoutSeconds] connect/read/write timeout and at most four connections per host.
 *
 * [base] exists so tests can add interceptors; production passes nothing.
 */
class OkHttpTransport(timeoutSeconds: Long = 45, base: OkHttpClient = OkHttpClient()) : HttpTransport {
    val client: OkHttpClient = base.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
        .build()

    override suspend fun send(request: HttpRequest): HttpResponse {
        val call = client.newCall(toOkHttp(request))
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { HttpResponse(it.code, it.body.bytes(), headersOf(it)) } }
                    result.fold({ continuation.resume(it) }, { if (continuation.isActive) continuation.resumeWithException(it) })
                }
            })
        }
    }

    companion object {
        /** Converts a request without changing its URL or body bytes (the NIP-98 event covers both). */
        fun toOkHttp(request: HttpRequest): Request {
            val builder = Request.Builder().url(request.url)
            var contentType: String? = null
            for ((name, value) in request.headers) {
                if (name.equals("Content-Type", ignoreCase = true)) contentType = value else builder.header(name, value)
            }
            val method = request.method.uppercase()
            val body = when {
                request.body != null -> request.body.toRequestBody(contentType?.toMediaTypeOrNull())
                method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            return builder.method(method, body).build()
        }

        internal fun headersOf(response: Response): Map<String, String> =
            response.headers.names().associateWith { response.headers[it] ?: "" }
    }
}
