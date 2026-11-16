package studio.otherstuff.ambulando.app.fips

import android.net.*
import android.os.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.*
import java.net.InetAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android lifecycle tests use a synthetic ready bundle, never an enrolled debug provider or real VPN. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [30])
class FipsLeaseLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val npub = NostrKeys.npub("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")!!
    private val peer = FipsPeerEndpoint(npub, "$npub.fips", 4443)
    private val network = ShadowNetwork.newInstance(872)
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)
    private class Lifecycle : Binder() {
        var recipient: IBinder.DeathRecipient? = null
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { this.recipient = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }
    private fun ready(binder: IBinder) = Bundle().apply {
        putInt("version", 1); putString("status", "ready"); putString("requestId", "consent"); putString("grant", "consent")
        putString("endpoint", peer.origin); putString("peerNpub", npub); putString("purpose", "nstack")
        putString("peerIpv6", InetAddress.getByAddress(peer.addressBytes).hostAddress); putByteArray("peerIpv6Bytes", peer.addressBytes)
        putLong("networkHandle", network.networkHandle); putString("generation", "runtime1"); putBinder("lifecycle", binder)
        putLong("expiresAt", System.currentTimeMillis() / 1000 + 60); putLong("expiresElapsedRealtime", SystemClock.elapsedRealtime() + 60_000)
    }
    @Suppress("UNCHECKED_CAST")
    private fun track(lease: SharedFipsTransport.Lease): Socket {
        val sockets = lease.javaClass.getDeclaredField("sockets").apply { isAccessible = true }.get(lease) as MutableSet<Socket>
        return Socket().also { sockets.add(it) }
    }
    private fun lease(t: SharedFipsTransport, binder: IBinder): SharedFipsTransport.Lease {
        Shadows.shadowOf(cm).setNetworkCapabilities(network, NetworkCapabilities().also { Shadows.shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN) })
        return t.Lease(Uri.parse("content://com.wingmanbefree.wingman_app.fips.shared"), "consent", peer, ready(binder)).also { it.start(); it.localCheck() }
    }
    @Test fun providerDeathClosesAllProtocolsAndRefusesFurtherOperations() {
        SharedFipsTransport(context).use { t ->
            val binder = Lifecycle(); val lease = lease(t, binder)
            val sockets = List(4) { track(lease) } // HTTP, SSE, upload and download share this socket registry.
            binder.recipient!!.binderDied()
            assertTrue(sockets.all { it.isClosed })
            assertTrue(runCatching { lease.localCheck() }.isFailure)
        }
    }
    @Test fun capturedNetworkLossAndCapabilityLossCloseSockets() {
        for (loss in listOf(true, false)) SharedFipsTransport(context).use { t ->
            val lease = lease(t, Lifecycle()); val socket = track(lease)
            val callback = Shadows.shadowOf(cm).networkCallbacks.single()
            if (loss) callback.onLost(network) else callback.onCapabilitiesChanged(network, NetworkCapabilities())
            assertTrue(socket.isClosed)
            assertTrue(runCatching { lease.localCheck() }.isFailure)
        }
    }
    @Test fun monotonicExpiryClosesSocketsWhileRenewalMonitorIsBlocked() {
        SharedFipsTransport(context).use { t ->
            val lease = lease(t, Lifecycle()); val socket = track(lease)
            val locked = CountDownLatch(1); val release = CountDownLatch(1)
            val worker = Thread { synchronized(lease) { locked.countDown(); release.await(5, TimeUnit.SECONDS) } }.apply { start() }
            try {
                assertTrue(locked.await(2, TimeUnit.SECONDS))
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
                assertTrue("expiry cannot wait for IPC/renewal lock", socket.isClosed)
            } finally { release.countDown(); worker.join(2000); lease.close() }
        }
    }
    @Test fun failedReadinessCheckCancelsExistingIo() = runBlocking {
        SharedFipsTransport(context).use { t ->
            val lease = lease(t, Lifecycle()); val socket = track(lease)
            assertTrue(runCatching { lease.renew() }.isFailure) // no trusted provider exists in this test
            assertTrue(socket.isClosed)
        }
    }
    @Test fun messengerRevocationClosesRegisteredLease() {
        SharedFipsTransport(context).use { t ->
            val lease = lease(t, Lifecycle()); val socket = track(lease)
            @Suppress("UNCHECKED_CAST")
            val leases = t.javaClass.getDeclaredField("leases").apply { isAccessible = true }.get(t) as MutableMap<String, SharedFipsTransport.Lease>
            leases[peer.origin] = lease
            val callback = t.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(t) as Messenger
            callback.send(Message.obtain().apply { what = 1; data = Bundle().apply {
                putInt("version", 1); putString("requestId", "consent"); putString("status", "revoked")
            } })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(socket.isClosed)
            assertTrue(leases.isEmpty())
        }
    }
    @Test fun denialRevocationUnknownVersionAndMalformedReadyNeverCreateLease() {
        SharedFipsTransport(context).use { t ->
            for (status in listOf("denied", "revoked", "unauthorized", "unavailable", "unknown")) {
                val reply = ready(Lifecycle()).apply { putString("status", status) }
                assertTrue(status, runCatching { t.Lease(Uri.EMPTY, "consent", peer, reply) }.isFailure)
            }
            assertTrue(runCatching { t.Lease(Uri.EMPTY, "consent", peer, ready(Lifecycle()).apply { putInt("version", 2) }) }.isFailure)
        }
    }

    @Test fun binderDeathBeforeStartLeavesNoLateNetworkCallback() {
        SharedFipsTransport(context).use { t ->
            Shadows.shadowOf(cm).setNetworkCapabilities(network, NetworkCapabilities().also { Shadows.shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN) })
            val binder = object : Binder() {
                override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { recipient.binderDied() }
                override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
            }
            val lease = t.Lease(Uri.parse("content://com.wingmanbefree.wingman_app.fips.shared"), "consent", peer, ready(binder))
            assertTrue(runCatching { lease.start() }.isFailure)
            assertTrue(Shadows.shadowOf(cm).networkCallbacks.isEmpty())
        }
    }

    @Test fun providerReplacementSynchronouslyClosesIoAndPermanentlyFencesOldClient() = runBlocking {
        SharedFipsTransport(context).use { t ->
            val stable = Uri.parse("content://com.wingmanbefree.wingman_app.fips.shared")
            val nightly = Uri.parse("content://com.wingmanbefree.wingman_app.nightly.fips.shared")
            t.bindProvider(stable)
            val lease = lease(t, Lifecycle()); val socket = track(lease)
            @Suppress("UNCHECKED_CAST")
            val leases = t.javaClass.getDeclaredField("leases").apply { isAccessible = true }.get(t) as MutableMap<String, SharedFipsTransport.Lease>
            leases[peer.origin] = lease
            assertTrue(runCatching { t.bindProvider(nightly) }.isFailure)
            assertTrue(socket.isClosed)
            assertTrue(leases.isEmpty())
            assertTrue(runCatching { t.bindProvider(stable) }.isFailure)
            assertEquals(stable, t.bindProvider(stable, forRelease = true))
            assertTrue(runCatching { t.bindProvider(nightly, forRelease = true) }.isFailure)
            assertTrue(runCatching { t.send(HttpRequest("GET", peer.origin + "/test")) }.isFailure)
            assertTrue(runCatching { t.open(HttpRequest("GET", "https://example.com/test")) }.isFailure)
        }
    }
    @Test fun selectedPackageUpdateRevokesEvenWhenAuthorityStaysTheSame() {
        SharedFipsTransport(context).use { t ->
            t.bindProvider(Uri.parse("content://com.wingmanbefree.wingman_app.fips.shared"))
            val lease = lease(t, Lifecycle()); val socket = track(lease)
            @Suppress("UNCHECKED_CAST")
            val leases = t.javaClass.getDeclaredField("leases").apply { isAccessible = true }.get(t) as MutableMap<String, SharedFipsTransport.Lease>
            leases[peer.origin] = lease
            val receiver = t.javaClass.getDeclaredField("providerReceiver").apply { isAccessible = true }.get(t) as android.content.BroadcastReceiver
            receiver.onReceive(context, android.content.Intent(android.content.Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:com.wingmanbefree.wingman_app")))
            assertTrue(socket.isClosed)
            assertTrue(runCatching { t.bindProvider(Uri.parse("content://com.wingmanbefree.wingman_app.fips.shared")) }.isFailure)
        }
    }

    @Test fun providerDeathCancelsActualHttpAndSseCallsWhileDnsIsBlockedBeforeSocketCreation() = runBlocking {
        for (stream in listOf(false, true)) SharedFipsTransport(context).use { t ->
            val binder = Lifecycle(); val lease = lease(t, binder)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val actual = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
            val dnsCalls = java.util.concurrent.atomic.AtomicInteger()
            val base = lease.client.newBuilder().dns {
                dnsCalls.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw java.net.UnknownHostException("synthetic")
            }.eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { actual.set(call) }
            }).build()
            val http = OkHttpTransport(base = base, allowFips = true, preserveDispatcher = true)
            val sse = OkHttpEventStreamTransport(base = base, allowFips = true)
            assertSame(lease.client.dispatcher, http.client.dispatcher)
            assertSame(lease.client.dispatcher, sse.client.dispatcher)
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                runCatching {
                    val request = HttpRequest(if (stream) "GET" else "POST", peer.origin + "/test", body = if (stream) null else byteArrayOf(1))
                    if (stream) sse.open(request) else http.send(request)
                }
            }
            try {
                assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                binder.recipient!!.binderDied()
                assertTrue("Revocation must cancel the actual adapter call before DNS unblocks", actual.get().isCanceled())
                assertEquals(1L, release.count)
                assertTrue(runCatching { lease.localCheck() }.isFailure)
            } finally {
                release.countDown()
                kotlinx.coroutines.withTimeout(5_000) { job.join() }
                lease.close()
            }
            assertEquals("No automatic mutation replay", 1, dnsCalls.get())
        }
    }

    /** Loopback HTTP/1.1 fixture deliberately offers keep-alive; only the lease pool should prevent idle reuse. */
    private class KeepAliveServer : AutoCloseable {
        private val server = java.net.ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}"
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val continueStream = CountDownLatch(1)
        private val finishStream = CountDownLatch(1)
        private val accepting = kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet(); sockets.add(socket)
                kotlin.concurrent.thread(isDaemon = true) {
                    runCatching { socket.use {
                        val input = socket.getInputStream().buffered(); val output = socket.getOutputStream()
                        fun line(): String? {
                            val text = StringBuilder()
                            while (true) {
                                val b = input.read(); if (b < 0) return null
                                if (b == 10) return text.toString().trimEnd('\r')
                                text.append(b.toChar())
                            }
                        }
                        while (true) {
                            val request = line() ?: break
                            var size = 0
                            while (true) {
                                val header = line() ?: return@use
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                            }
                            repeat(size) { check(input.read() >= 0) }
                            val method = request.substringBefore(' '); methods.add(method)
                            if (method == "POST") break // Ambiguous mutation: received in full, response lost.
                            if (request.contains("/events ")) {
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                                fun chunk(text: String) { output.write((text.length.toString(16) + "\r\n" + text + "\r\n").toByteArray()); output.flush() }
                                chunk("data: ready\n\n")
                                if (continueStream.await(5, TimeUnit.SECONDS)) chunk("data: alive\n\n")
                                finishStream.await(5, TimeUnit.SECONDS)
                                break
                            }
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok".toByteArray()); output.flush()
                        }
                    } }
                }
            }
        }
        override fun close() {
            server.close(); continueStream.countDown(); finishStream.countDown()
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            accepting.join(2000)
        }
    }

    @Test fun leaseReadAndUploadUseFreshSocketsWithoutReplayWhileActiveStreamSurvives() = runBlocking {
        KeepAliveServer().use { server -> SharedFipsTransport(context).use { t ->
            val lease = lease(t, Lifecycle())
            // Synthetic local server tests the actual lease-owned pool; real scoped DNS/socket/pins are covered separately.
            val base = lease.client.newBuilder().socketFactory(javax.net.SocketFactory.getDefault())
                .dns(okhttp3.Dns.SYSTEM).eventListener(okhttp3.EventListener.NONE).build()
            val http = OkHttpTransport(base = base, allowFips = true, preserveDispatcher = true)
            val sse = OkHttpEventStreamTransport(base = base, allowFips = true)
            val (_, chunks) = sse.open(HttpRequest("GET", server.url + "/events"))
            try {
                assertTrue(kotlinx.coroutines.withTimeout(5000) { chunks.next() }!!.decodeToString().contains("ready"))
                assertEquals(200, http.send(HttpRequest("GET", server.url + "/read")).status)
                assertEquals(200, http.send(HttpRequest("PUT", server.url + "/upload", body = byteArrayOf(1,2,3))).status)
                assertEquals("SSE plus a fresh connection for read and upload", 3, server.accepted.get())
                assertTrue(runCatching { http.send(HttpRequest("POST", server.url + "/mutation", body = byteArrayOf(4))) }.isFailure)
                assertEquals(1, server.methods.count { it == "POST" })
                assertEquals("No transparent replay on another socket", 4, server.accepted.get())
                server.continueStream.countDown()
                assertTrue(kotlinx.coroutines.withTimeout(5000) { chunks.next() }!!.decodeToString().contains("alive"))
                val ordinary = OkHttpTransport()
                assertNotSame(lease.client.connectionPool, ordinary.client.connectionPool)
                val prior = server.accepted.get()
                ordinary.send(HttpRequest("GET", server.url + "/ordinary-one"))
                ordinary.send(HttpRequest("GET", server.url + "/ordinary-two"))
                assertEquals("Ordinary default pool still reuses its idle connection", prior + 1, server.accepted.get())
                ordinary.client.connectionPool.evictAll()
            } finally { chunks.cancel(); lease.close() }
        } }
    }

}
