package studio.otherstuff.ambulando.app.fips

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.OkHttpTransport
import studio.otherstuff.ambulando.core.transport.OkHttpEventStreamTransport
import studio.otherstuff.ambulando.core.transport.TowerError

@RunWith(RobolectricTestRunner::class)
class SharedFipsTransportTest {
    private val peer = NostrKeys.npub("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
    private val request = HttpRequest("GET", "http://$peer.fips:4443/api/v4/test")
    @Test fun unenrolledProviderNeverGetsNetworkOrHttpsFallback() = runBlocking {
        val transport = SharedFipsTransport(RuntimeEnvironment.getApplication())
        try {
            try { transport.send(request); fail("Expected missing enrollment") }
            catch (e: java.io.IOException) { assertTrue(e.message!!.contains("certificate")); assertTrue(e.message!!.contains("No HTTPS fallback")) }
            try { transport.open(request); fail("Expected missing enrollment for SSE") }
            catch (e: java.io.IOException) { assertTrue(e.message!!.contains("certificate")) }
        } finally { transport.close() }
    }
    @Test fun ordinaryBackgroundTransportsCannotBypassScopedGrant() = runBlocking {
        try { OkHttpTransport().send(request); fail("Expected scoped capability requirement") }
        catch (e: TowerError) { assertEquals(TowerError.FipsNotSupported, e) }
        try { OkHttpEventStreamTransport().open(request); fail("Expected scoped capability requirement") }
        catch (e: TowerError) { assertEquals(TowerError.FipsNotSupported, e) }
    }
    @Test fun normalizedFipsAliasesNeverReachOrdinaryDnsOrNetwork() = runBlocking {
        var dnsCalls = 0
        var socketCalls = 0
        val base = okhttp3.OkHttpClient.Builder().socketFactory(object : javax.net.SocketFactory() {
            override fun createSocket(): java.net.Socket { socketCalls++; error("ordinary socket must not run") }
            override fun createSocket(h: String, p: Int) = createSocket()
            override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int) = createSocket()
            override fun createSocket(h: java.net.InetAddress, p: Int) = createSocket()
            override fun createSocket(h: java.net.InetAddress, p: Int, l: java.net.InetAddress, lp: Int) = createSocket()
        }).dns { dnsCalls++; throw java.net.UnknownHostException("ordinary DNS must not run") }.build()
        SharedFipsTransport(RuntimeEnvironment.getApplication(), ordinaryBase = base).use { scoped ->
            for (host in listOf("$peer.FIPS", "$peer.FiPs", peer.uppercase() + ".fips", "$peer.fips.", "$peer.%66ips", "%6e${peer.drop(1)}.fips", "$peer．fips", "$peer.ｆｉｐｓ")) {
                val malformed = HttpRequest("GET", "http://$host:4443/api/v4/test")
                assertTrue(studio.otherstuff.ambulando.core.transport.FipsUrlPolicy.isProtected(malformed.url.toHttpUrl()))
                assertFalse(studio.otherstuff.ambulando.app.browser.BrowserFilePolicy.https("https://$host:4443/file"))
                try { studio.otherstuff.ambulando.app.media.OkHttpAvatarTransport(base = base).fetch(java.net.URI("https://$host:4443/file"), 1024); fail("avatar must reject") } catch (_: TowerError) {}
                try { scoped.send(malformed); fail("Expected noncanonical FIPS rejection") } catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("noncanonical")) }
                try { scoped.open(malformed); fail("Expected noncanonical SSE rejection") } catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("noncanonical")) }
                try { OkHttpTransport(base = base).send(malformed); fail("Expected default transport refusal") } catch (e: TowerError) { assertEquals(TowerError.FipsNotSupported, e) }
                try { OkHttpEventStreamTransport(base = base).open(malformed); fail("Expected default SSE refusal") } catch (e: TowerError) { assertEquals(TowerError.FipsNotSupported, e) }
            }
        }
        assertEquals(0, dnsCalls)
        assertEquals(0, socketCalls)
    }

    @Test fun releaseRosterContainsOnlyTheTwoIndependentlyEnrolledIdentities() {
        val entries = RuntimeEnvironment.getApplication().resources.getStringArray(studio.otherstuff.ambulando.app.R.array.fips_shared_providers)
        assertEquals(setOf(
            "com.wingmanbefree.wingman_app|6c0da09b9e2375d657645f197419be7b8227a7434e0225512fc760cedf383c8c",
            "com.wingmanbefree.wingman_app.nightly|5edec981796544b29118b53d424ed5067b54cbb0b871d5c37b1cc9ccbc807be0"
        ), entries.toSet())
        assertEquals(2, entries.size)
    }

}
