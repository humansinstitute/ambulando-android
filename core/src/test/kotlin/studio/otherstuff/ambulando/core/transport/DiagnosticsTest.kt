package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class DiagnosticsTest {
    @Test fun structuralRoutesRedactEvenKnownWordsInIdSlotsAndEncodedSlugsAndQueryNames() {
        for (secret in listOf("alice", "tasks", "npub1secret", "sk_live_secret", "%61lice", "%2Ftasks", "Alice", "é")) {
            val route = DiagnosticsRedactor.endpoint("GET", "https://user.example.com/api/v4/flightdeck-pg/workspaces/$secret/tasks/$secret/comments?password=secret&user_alice=secret&limit=999999&cursor=secret&%6cimit=secret")
            assertEquals("GET /api/v4/flightdeck-pg/workspaces/:id/tasks/:id/comments??,?,?,cursor,limit", route)
        }
        assertEquals("OTHER /unknown", DiagnosticsRedactor.endpoint("GET\nsecret", "https://secret.example.com/secret"))
    }
    @Test fun noSecretHeadersBodiesErrorMessagesOrUnconstrainedCorrelationsReachExport() {
        val log = DiagnosticsLog(2)
        val request = HttpRequest("GET", "https://secret.example.com/api/v4/flightdeck-pg/workspaces/alice/tasks/bob?cursor=secret", mapOf("Authorization" to "secret", "x-secret" to "secret"), "secret body".toByteArray())
        val response = HttpResponse(503, "secret response".toByteArray(), mapOf("x-request-id" to "npub1secret", "x-correlation-id" to "sk_live_secret", "cf-ray" to "abc-LHR", "Authorization" to "secret"))
        log.request(request, 42, response, SocketTimeoutException("url secret"))
        log.retry("retry"); log.retry("gave-up")
        val exported = log.export("0.1.2", 4, 36)
        assertFalse(exported.contains("secret")); assertFalse(exported.contains("alice")); assertFalse(exported.contains("status=503"))
        assertTrue(exported.contains("gave-up")); assertTrue(exported.contains("UTC"))
        log.request(request, 42, response, SocketTimeoutException("secret"))
        val latest = log.export("0.1.2", 4, 36)
        assertTrue(latest.contains("status=503")); assertTrue(latest.contains("error=timeout")); assertFalse(latest.contains("secret"))
        assertNull(DiagnosticsRedactor.correlation(response))
        assertEquals("cf-ray=0123456789abcdef-LHR", DiagnosticsRedactor.correlation(HttpResponse(200, byteArrayOf(), mapOf("cf-ray" to "0123456789abcdef-LHR"))))
    }
    @Test fun failuresSurviveRestartBoundedAndClearPersists() {
        val file = java.io.File(studio.otherstuff.ambulando.core.support.tempDir(), "diagnostics.json")
        val log = DiagnosticsLog(2, file)
        log.retry("retry"); log.retry("recovered"); log.retry("gave-up")
        val restored = DiagnosticsLog(2, file).export("0.1.2", 4, 36)
        assertTrue(restored.contains("recovered")); assertTrue(restored.contains("gave-up"))
        assertEquals(2, restored.lines().count { " | " in it })
        DiagnosticsLog(2, file).clear()
        assertEquals(0, DiagnosticsLog(2, file).export("0.1.2", 4, 36).lines().count { " | " in it })
    }
    @Test fun oversizedOrTamperedPersistedEntriesAreNotExported() {
        val file = java.io.File(studio.otherstuff.ambulando.core.support.tempDir(), "diagnostics.json")
        file.writeText("""[{"at":"2026-01-01T00:00:00Z","endpoint":"GET /secret/alice","error":"secret"}]""")
        assertFalse(DiagnosticsLog(file = file).export("0.1.2", 4, 36).contains("secret"))
        file.writeText("x".repeat(512_001))
        assertEquals(0, DiagnosticsLog(file = file).export("0.1.2", 4, 36).lines().count { " | " in it })
    }
    @Test fun realAuthResponseProvesReachabilityAndTlsIsNotTransient() = runBlocking {
        var failing = true
        val transport = ObservedTransport(object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if (failing) throw SocketTimeoutException()
                return HttpResponse(401, byteArrayOf())
            }
        }, DiagnosticsLog())
        val request = HttpRequest("GET", "https://tower.example.com/api/v4/flightdeck-pg/workspaces")
        runCatching { transport.send(request) }
        assertTrue(transport.failing.value)
        failing = false; transport.send(request)
        assertFalse(transport.failing.value)
        assertFalse(ConnectionFailure.isTransient(SSLHandshakeException("certificate")))
    }
}
