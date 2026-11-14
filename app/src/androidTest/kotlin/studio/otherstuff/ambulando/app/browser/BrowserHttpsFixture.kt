package studio.otherstuff.ambulando.app.browser

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Loopback HTTPS with a PUBLIC DISPOSABLE key; only debug localhost trusts its certificate. No TLS bypass. */
internal class BrowserHttpsFixture : AutoCloseable {
    data class Request(val method: String, val target: String, val headers: Map<String, String>, val body: String)
    data class Response(val body: String = "", val status: Int = 200, val headers: Map<String, String> = emptyMap())
    @Volatile var respond: (Request) -> Response = { Response("<title>READY</title>") }
    private val workers = Executors.newCachedThreadPool()
    private val server: SSLServerSocket
    val base: String get() = "https://localhost:${server.localPort}"
    init {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cert = instrumentation.targetContext.resources.openRawResource(studio.otherstuff.ambulando.app.R.raw.browser_localhost_test_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keyBytes = instrumentation.context.assets.open("browser-localhost-disposable-key.txt").use { Base64.decode(it.readBytes(), Base64.DEFAULT) }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("disposable-test-only", key, charArrayOf(), arrayOf(cert))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, charArrayOf()) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server = tls.serverSocketFactory.createServerSocket(0, 20, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        workers.submit {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                workers.submit {
                    runCatching {
                        socket.use {
                            it.soTimeout = 10_000
                            val reader = it.inputStream.bufferedReader()
                            val first = reader.readLine()?.split(' ') ?: return@use
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                            var read = 0
                            while (read < body.size) { val n = reader.read(body, read, body.size - read); if (n < 0) break; read += n }
                            val response = respond(Request(first[0], first[1], headers, String(body)))
                            val bytes = response.body.toByteArray()
                            val head = buildString {
                                append("HTTP/1.1 ${response.status} Fixture\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                                response.headers.forEach { (k,v) -> append("$k: $v\r\n") }
                                append("\r\n")
                            }
                            it.outputStream.write(head.toByteArray()); it.outputStream.write(bytes); it.outputStream.flush()
                        }
                    }
                }
            }
        }
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
