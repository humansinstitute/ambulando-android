package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OwnedDispatcherTest {
    @Test fun revocationCancelsActualHttpCallWhileDnsIsBlockedBeforeAnySocket() = blockedDns(false)
    @Test fun revocationCancelsActualSseCallWhileDnsIsBlockedBeforeAnySocket() = blockedDns(true)

    private fun blockedDns(stream: Boolean) = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val actual = AtomicReference<Call>(); val dnsCalls = AtomicInteger(); val socketCalls = AtomicInteger()
        val owner = Dispatcher().apply { maxRequestsPerHost = 4 }
        val base = OkHttpClient.Builder().dispatcher(owner).proxy(Proxy.NO_PROXY)
            .eventListener(object : EventListener() { override fun callStart(call: Call) { actual.set(call) } })
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket { socketCalls.incrementAndGet(); error("No socket expected") }
                override fun createSocket(h: String, p: Int) = createSocket()
                override fun createSocket(h: String, p: Int, a: java.net.InetAddress, l: Int) = createSocket()
                override fun createSocket(a: java.net.InetAddress, p: Int) = createSocket()
                override fun createSocket(a: java.net.InetAddress, p: Int, l: java.net.InetAddress, q: Int) = createSocket()
            })
            .dns {
                dnsCalls.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "DNS latch timed out" }
                throw UnknownHostException("synthetic")
            }.build()
        val http = OkHttpTransport(base = base, allowFips = true, preserveDispatcher = true)
        val sse = OkHttpEventStreamTransport(base = base, allowFips = true)
        assertSame(owner, http.client.dispatcher)
        assertSame(owner, sse.client.dispatcher)
        assertNotSame(owner, OkHttpTransport(base = base).client.dispatcher)
        assertEquals(4, OkHttpTransport(base = base).client.dispatcher.maxRequestsPerHost)
        val request = HttpRequest(if (stream) "GET" else "POST", "http://synthetic.invalid:4443/test", body = if (stream) null else byteArrayOf(1))
        val job = launch(Dispatchers.Default) { runCatching { if (stream) sse.open(request) else http.send(request) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertEquals(1, owner.runningCallsCount())
            assertEquals(0, socketCalls.get())
            owner.cancelAll() // Same boundary used by Lease.close, BEFORE releasing DNS.
            assertTrue("The actual call must already be cancelled", actual.get().isCanceled())
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            withTimeout(5_000) { job.join() }
            owner.cancelAll(); owner.executorService.shutdown(); base.connectionPool.evictAll()
        }
        assertEquals("No automatic replay", 1, dnsCalls.get())
        assertEquals(0, socketCalls.get())
    }
}
