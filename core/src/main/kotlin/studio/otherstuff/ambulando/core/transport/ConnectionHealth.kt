package studio.otherstuff.ambulando.core.transport

import java.net.*
import java.io.EOFException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConnectionFailure {
    fun isTransient(error: Throwable): Boolean = when (error) {
        is CancellationException, is SSLException -> false
        is TowerError.Http -> error.status in setOf(502, 503, 504)
        is SocketTimeoutException, is ConnectException, is UnknownHostException, is NoRouteToHostException,
        is SocketException, is EOFException -> true
        else -> false
    }
    /** Normal screens never expose raw transport descriptions; auth and validation remain actionable. */
    fun userMessage(error: Throwable): String? = when {
        isTransient(error) -> null
        error is SSLException -> "Secure connection failed. Check the Tower certificate."
        error is java.io.IOException -> "The network request could not be completed."
        else -> error.message ?: "Operation failed"
    }
    fun code(error: Throwable): String = when (error) {
        is CancellationException -> "cancelled"
        is SSLException -> "tls"
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "dns"
        is SocketException, is EOFException -> "connection"
        is TowerError.Http -> "http-${error.status}"
        else -> "error"
    }
}

/** Observes Tower API traffic only; never records object-store URLs or request/response bodies. */
class ObservedTransport(private val base: HttpTransport, val log: DiagnosticsLog) : HttpTransport {
    private val _failing = MutableStateFlow(false)
    val failing = _failing.asStateFlow()
    override suspend fun send(request: HttpRequest): HttpResponse {
        val path = runCatching { URI(request.url).rawPath }.getOrNull()
        if (path?.startsWith("/api/v4/") != true && path != TowerClient.DESCRIPTOR_PATH) return base.send(request)
        val started = System.nanoTime()
        try {
            val response = base.send(request)
            _failing.value = response.status in setOf(502, 503, 504)
            log.request(request, (System.nanoTime() - started) / 1_000_000, response, null)
            return response
        } catch (error: Throwable) {
            if (ConnectionFailure.isTransient(error)) _failing.value = true
            log.request(request, (System.nanoTime() - started) / 1_000_000, null, error)
            throw error
        }
    }
}
