package studio.otherstuff.ambulando.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in read-only public network smoke; no account or signature grants. */
@RunWith(AndroidJUnit4::class)
class BrowserLiveNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun publicHttpsSitesAndCrossOriginServerRedirect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBrowserNavigation") == "true")
        lateinit var browser: BrowserModel
        instrumentation.runOnMainSync { browser = BrowserModel(instrumentation.targetContext) }
        try {
            for (url in listOf("https://example.com/", "https://github.com/", "https://primal.net/", "https://httpbingo.org/redirect-to?url=https%3A%2F%2Fexample.com%2F")) {
                lateinit var tab: BrowserTab
                instrumentation.runOnMainSync { browser.open(url); tab = browser.selected!! }
                val end = System.currentTimeMillis() + 45_000
                var finished = false
                while (System.currentTimeMillis() < end) {
                    instrumentation.runOnMainSync { finished = tab.refusal != null || (!tab.loading && tab.url != null && tab.title.isNotEmpty()) }
                    if (finished) break
                    Thread.sleep(100)
                }
                instrumentation.runOnMainSync {
                    android.util.Log.i("BrowserLive", "$url -> ${tab.url}; title=${tab.title}; error=${tab.refusal}; ua=${tab.webView.settings.userAgentString}")
                    assertTrue("Timed out: $url", finished)
                    assertNull("$url: ${tab.refusal}", tab.refusal)
                    assertTrue(tab.url.orEmpty().startsWith("https://"))
                    if (url.contains("redirect-to")) assertEquals("https://example.com/", tab.url)
                }
            }
        } finally { instrumentation.runOnMainSync { browser.closeAll() } }
    }
}
