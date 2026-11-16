package studio.otherstuff.ambulando.app.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.browser.NIP07Decision
import studio.otherstuff.ambulando.core.browser.NIP07SitePersistence
import studio.otherstuff.ambulando.core.nostr.NostrKeys

/** Explicit opt-in: uses only the public NIP-19 fixture identity on the real public login page. */
@RunWith(AndroidJUnit4::class)
class BrowserGitWorkshopTest {
    @get:Rule val compose = createComposeRule()
    private fun js(browser: BrowserModel, script: String): String {
        var result: String? = null
        compose.runOnUiThread { browser.selected!!.webView.evaluateJavascript(script) { result = it } }
        compose.waitUntil(20000) { result != null }
        return result!!
    }
    @Test fun realLoginModalHasVisibleChoicesAndNativeProvider() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveGitWorkshop") == "true")
        val site = InstrumentationRegistry.getArguments().getString("gitWorkshopUrl")
        assumeTrue(site?.startsWith("https://") == true)
        val secret = NostrKeys.secretKey("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5")!!
        lateinit var browser: BrowserModel
        compose.runOnUiThread {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            browser = BrowserModel(InstrumentationRegistry.getInstrumentation().targetContext)
            browser.configure(false, emptyMap(), { NostrKeys.publicKeyHex(secret) }, { NostrKeys.sign(it, secret) }, NIP07SitePersistence {})
            browser.clearBrowsingData()
            browser.open(site!!)
        }
        compose.setContent { AmbulandoTheme { BrowserScreen(browser) } }
        try {
            compose.waitUntil(60000) { !browser.selected!!.loading && browser.selected!!.title.isNotEmpty() }
            assertNull(browser.selected!!.refusal)
            compose.waitUntil(30000) { js(browser, "!!Array.from(document.querySelectorAll('button')).find(b=>/log in/i.test(b.textContent))") == "true" }
            js(browser, "setTimeout(()=>Array.from(document.querySelectorAll('button')).find(b=>/log in/i.test(b.textContent)).click(),0);true")
            compose.waitUntil(10000) { js(browser, "!!document.querySelector('[role=dialog]')") == "true" }
            Thread.sleep(1000)
            val geometry = js(browser, """(()=>{const d=document.querySelector('[role=dialog]'),r=d.getBoundingClientRect(),s=getComputedStyle(d);return JSON.stringify({rect:r.toJSON(),height:s.height,maxHeight:s.maxHeight,opacity:s.opacity,transform:s.transform,innerHeight,viewport:visualViewport.height,text:d.innerText,ua:navigator.userAgent,css:Array.from(document.styleSheets).map(x=>x.href)})})()""")
            android.util.Log.i("GitWorkshopGeometry", geometry)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val evidence = context.getExternalFilesDir("browser-evidence")!!.apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
                java.io.File(evidence, "gitworkshop-modal.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertEquals(geometry, "true", js(browser, "(()=>{const r=document.querySelector('[role=dialog]').getBoundingClientRect();return r.height>150 && r.width>200 && r.top<innerHeight && r.bottom>0})()"))
            assertEquals("true", js(browser, "typeof window.nostr?.getPublicKey==='function'"))
            // Exercise the actual Extension login choice, rather than only a standalone provider call.
            js(browser, "setTimeout(()=>Array.from(document.querySelectorAll('[role=dialog] button')).find(b=>/log in with extension/i.test(b.textContent)).click(),0);true")
            compose.waitUntil(15000) { browser.prompt != null }
            compose.runOnUiThread { browser.answer(browser.prompt!!, NIP07Decision.Allow(false)) }
            compose.waitUntil(15000) { js(browser, "!document.querySelector('[role=dialog]')") == "true" }
            android.util.Log.i("GitWorkshopLogin", "Actual Extension login closed the modal using disposable NIP19 identity")
            // Ask through the actual site's injected provider; approve the disposable public identity.
            js(browser, "window.nostr.getPublicKey().then(k=>window.fixturePublicKey=k);true")
            compose.waitUntil(10000) { browser.prompt != null }
            compose.runOnUiThread { browser.answer(browser.prompt!!, NIP07Decision.Allow(false)) }
            compose.waitUntil(10000) { js(browser, "window.fixturePublicKey=== '${NostrKeys.publicKeyHex(secret)}'") == "true" }
        } finally { compose.runOnUiThread { browser.closeAll() } }
    }
}
