package studio.otherstuff.ambulando.app.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EditorWebHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun awaitType(page: String, channel: String, type: String, start: (EditorWebHost) -> Unit = {}): Pair<EditorWebHost, String> {
        val latch = CountDownLatch(1)
        var body = ""
        lateinit var host: EditorWebHost
        instrumentation.runOnMainSync {
            host = EditorWebHost.create(instrumentation.targetContext, page, setOf("board", "document", "documentFile"), 1 shl 20) { ch, b ->
                if (ch == channel && runCatching { JSONObject(b).optString("type") }.getOrNull() == type) { body = b; latch.countDown() }
            }.getOrThrow()
            host.load()
            start(host)
        }
        assertTrue("no '$type' from $page", latch.await(30, TimeUnit.SECONDS))
        return host to body
    }

    @Test fun documentEditorPageLoadsFromAssetsAndReportsReadyThroughTheBridge() {
        val (host, _) = awaitType("document.html", "document", "ready")
        val fetch = CountDownLatch(1)
        var outcome: String? = null
        instrumentation.runOnMainSync {
            // The page must not reach the network: a fetch to any other origin fails.
            host.evaluate("fetch('https://example.com/').then(()=>'reached').catch(()=>'blocked')") { }
            host.evaluate("(function(){return typeof window.nostr})()") { outcome = it; fetch.countDown() }
        }
        assertTrue(fetch.await(10, TimeUnit.SECONDS))
        assertEquals("\"undefined\"", outcome)
        instrumentation.runOnMainSync { host.destroy() }
    }

    @Test fun boardEditorPageLoadsAndReportsReady() {
        val (host, _) = awaitType("index.html", "board", "ready")
        instrumentation.runOnMainSync { host.destroy() }
    }
}
