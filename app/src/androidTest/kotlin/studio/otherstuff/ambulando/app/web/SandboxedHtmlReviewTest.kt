package studio.otherstuff.ambulando.app.web

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.surfaces.HtmlCommentAnchor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android WebView instrumentation exercising DOM target picking, strict highlight matching, and detached targets. */
@RunWith(AndroidJUnit4::class)
class SandboxedHtmlReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun commentModePicksTargetMatchesUnchangedAndDetachesChangedOrTextlessTargets() {
        val threadHero = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
        val threadImg = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
        val v1Html = """
            <main>
              <h1 id="hero">Version One Headline</h1>
              <img data-asset="banner.png" alt="Banner v1" width="40" height="40">
            </main>
        """.trimIndent()
        val picked = AtomicReference<HtmlCommentAnchor?>(null)
        val pickedLatch = CountDownLatch(1)
        val v1Matches = AtomicReference<Map<String, Boolean>?>(null)
        val v1MatchLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(1)
        var commentMode = true

        val bridgeV1 = HtmlReviewBridge(
            isActive = { true },
            isCommentModeActive = { commentMode },
            onTargetSelected = { anchor -> picked.set(anchor); pickedLatch.countDown() },
            onAnchorMatches = { map -> v1Matches.set(map); v1MatchLatch.countDown() },
            onBadgeTapped = {},
        )

        lateinit var viewV1: WebView
        instrumentation.runOnMainSync {
            viewV1 = createSandboxedHtmlView(
                context = instrumentation.targetContext,
                html = v1Html,
                reviewBridge = bridgeV1,
                onPageReady = { readyLatch.countDown() },
            ).getOrThrow()
        }
        assertTrue("v1 WebView should load", readyLatch.await(15, TimeUnit.SECONDS))

        // Enable comment mode and tap #hero in the real DOM.
        instrumentation.runOnMainSync {
            viewV1.evaluateJavascript("window.__ambSetCommentMode(true);document.getElementById('hero').click();", null)
        }
        assertTrue("Target tap should invoke bridge", pickedLatch.await(10, TimeUnit.SECONDS))
        val heroAnchor = requireNotNull(picked.get())
        assertEquals("hero", heroAnchor.elementId)
        assertEquals("h1", heroAnchor.tagName)
        assertEquals("Version One Headline", heroAnchor.textQuote)

        // Sync hero + image anchors on v1: both must match cleanly.
        val imgAnchor = HtmlCommentAnchor(
            kind = "html_element",
            selector = "main > img",
            domPath = listOf(0, 1),
            tagName = "img",
            attrFingerprint = "data-asset=banner.png|alt=Banner v1",
        )
        val specsJson = buildJsonArray {
            add(buildJsonObject { put("threadId", threadHero); put("resolved", false); put("replyCount", 0); put("anchor", heroAnchor.toJson()) })
            add(buildJsonObject { put("threadId", threadImg); put("resolved", false); put("replyCount", 0); put("anchor", imgAnchor.toJson()) })
        }.toString()
        instrumentation.runOnMainSync {
            viewV1.evaluateJavascript("window.__ambSyncAnchors($specsJson);", null)
        }
        assertTrue("v1 anchor match report should arrive", v1MatchLatch.await(10, TimeUnit.SECONDS))
        assertEquals(true, v1Matches.get()?.get(threadHero))
        assertEquals(true, v1Matches.get()?.get(threadImg))
        instrumentation.runOnMainSync { viewV1.destroy() }

        // Render v2 where #hero text changed to "V" (single char) and <img> changed asset/alt: BOTH must report detached (false), never guessed!
        val v2Html = """
            <main>
              <h1 id="hero">V</h1>
              <img data-asset="banner-v2.png" alt="Banner v2" width="40" height="40">
            </main>
        """.trimIndent()
        val v2Matches = AtomicReference<Map<String, Boolean>?>(null)
        val v2MatchLatch = CountDownLatch(1)
        val v2Ready = CountDownLatch(1)
        val bridgeV2 = HtmlReviewBridge(
            isActive = { true },
            isCommentModeActive = { false },
            onTargetSelected = {},
            onAnchorMatches = { map -> v2Matches.set(map); v2MatchLatch.countDown() },
            onBadgeTapped = {},
        )
        lateinit var viewV2: WebView
        instrumentation.runOnMainSync {
            viewV2 = createSandboxedHtmlView(
                context = instrumentation.targetContext,
                html = v2Html,
                reviewBridge = bridgeV2,
                onPageReady = { v2Ready.countDown() },
            ).getOrThrow()
        }
        assertTrue("v2 WebView should load", v2Ready.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            viewV2.evaluateJavascript("window.__ambSyncAnchors($specsJson);", null)
        }
        assertTrue("v2 anchor match report should arrive", v2MatchLatch.await(10, TimeUnit.SECONDS))
        assertEquals(false, v2Matches.get()?.get(threadHero))
        assertEquals(false, v2Matches.get()?.get(threadImg))

        // Verify sandbox still has no window.nostr even with review overlay active.
        val nostrType = AtomicReference<String?>(null)
        val nostrLatch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            viewV2.evaluateJavascript("typeof window.nostr") { value -> nostrType.set(value); nostrLatch.countDown() }
        }
        assertTrue(nostrLatch.await(5, TimeUnit.SECONDS))
        assertEquals("\"undefined\"", nostrType.get())
        instrumentation.runOnMainSync { viewV2.destroy() }
    }
}
