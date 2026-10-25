package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.decodeAuthorization
import studio.otherstuff.ambulando.core.support.makeIdentity
import java.util.concurrent.TimeUnit

/**
 * The real OkHttp transport against a local server. A test-only interceptor routes the pinned HTTPS origin to the
 * local plain-HTTP server without changing the path, query, headers or body, so what the server records is exactly
 * what was signed.
 */
class OkHttpTransportTests {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun routed(followRedirects: Boolean = true): OkHttpClient {
        val route = Interceptor { chain ->
            val request = chain.request()
            val target = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(request.newBuilder().url(target).build())
        }
        // The base client asks for redirects; the transport policy must override it.
        return OkHttpClient.Builder().followRedirects(followRedirects).followSslRedirects(followRedirects).addInterceptor(route).build()
    }

    private fun record() = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test fun nip98PayloadIsTheSha256OfTheExactBytesSent() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body("{\"created\":true}").build())
        val client = TowerClient(TowerOrigin(Fixtures.ORIGIN), OkHttpTransport(base = routed()), makeIdentity())
        val body = jsonBody(jsonObjectOf("body" to "héllo ✓ \"quoted\"", "client_request_id" to "r1", "a" to listOf(1, 2.5)))
        val path = "/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}/channels/${Fixtures.CHANNEL_1}/messages"
        val reply = client.request(method = "POST", path = path, query = listOf("cursor" to "a b+c"), appNpub = Fixtures.APP_NPUB, jsonBody = body)
        assertEquals("{\"created\":true}", String(reply))

        val recorded = record()
        val sent = recorded.body!!.toByteArray()
        val auth = decodeAuthorization(HttpRequest("POST", "", mapOf("Authorization" to recorded.headers["Authorization"]!!)))
        assertTrue(SignatureVerifier.verify(auth))
        assertEquals(listOf("payload", Sha256Hex.of(sent)), auth.tags[2])
        assertTrue(sent.contentEquals(body))
        assertEquals(listOf("method", "POST"), auth.tags[1])
        val signedUrl = auth.tags[0][1]
        assertTrue(signedUrl.startsWith("https://tower.example.com/"))
        assertEquals("the signed URL is the URL sent", signedUrl.substringAfter("tower.example.com"), recorded.target)
        assertEquals(Fixtures.APP_NPUB, recorded.headers["x-flightdeck-pg-app-npub"])
        assertEquals("application/json", recorded.headers["Content-Type"])
        assertNull("no cookies", recorded.headers["Cookie"])
    }

    @Test fun redirectsAreRefusedAndNeverFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/api/v4/elsewhere").build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val client = TowerClient(TowerOrigin(Fixtures.ORIGIN), OkHttpTransport(base = routed(followRedirects = true)), makeIdentity())
        expectError(TowerError.Http(302, null, "workspace discovery")) { client.request(path = "/api/v4/flightdeck-pg/workspaces") }
        assertEquals("the redirect target was never requested", 1, server.requestCount)
    }

    @Test fun unsignedObjectStorePutCarriesNoAuthorization() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        val client = TowerClient(TowerOrigin(Fixtures.ORIGIN), OkHttpTransport(base = routed()), makeIdentity())
        client.putUnsigned("https://objects.example.com/bucket/obj?X-Amz-Signature=abc", "bytes".toByteArray(), "text/plain")
        val recorded = record()
        assertEquals("PUT", recorded.method)
        assertNull(recorded.headers["Authorization"])
        assertNull(recorded.headers["Cookie"])
        assertEquals("text/plain", recorded.headers["Content-Type"])
        assertEquals("bytes", recorded.body!!.utf8())
    }

    @Test fun eventStreamDeliversFramesThroughTheBoundedBuffer() = runBlocking {
        val stream = "event: connected\ndata: {\"cursor\":\"a\"}\n\n: heartbeat\n\nid: 7\nevent: flightdeck_pg.event\ndata: line1\ndata: line2\n\n"
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream").chunkedBody(stream, 7).build())
        val identity = makeIdentity()
        val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), OkHttpTransport(base = routed()), identity))
        val request = api.streamRequest(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "c1")
        assertEquals("text/event-stream", request.header("Accept"))
        val (head, chunks) = OkHttpEventStreamTransport(base = routed()).open(request)
        assertEquals(200, head.status)
        val parser = SSEParser()
        val frames = ArrayList<SSEFrame>()
        while (true) frames += parser.feed(chunks.next() ?: break)
        chunks.cancel()
        assertEquals(
            listOf(SSEFrame("connected", "{\"cursor\":\"a\"}"), SSEFrame("flightdeck_pg.event", "line1\nline2", "7")),
            frames,
        )
        assertEquals(1, parser.takeComments())
        val recorded = record()
        assertEquals("/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}/events/stream?cursor=c1", recorded.target)
        assertTrue(recorded.headers["Authorization"]!!.startsWith("Nostr "))
    }
}
