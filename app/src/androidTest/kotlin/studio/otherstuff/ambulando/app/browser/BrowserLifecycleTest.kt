package studio.otherstuff.ambulando.app.browser

import android.content.Intent
import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/** Real renderer lifecycle tests: outstanding page requests cannot cross tab/document boundaries. */
@RunWith(AndroidJUnit4::class)
class BrowserLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private var fixture: BrowserHttpsFixture? = null
    @After fun cleanupFixture() { fixture?.close() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun js(browser: BrowserModel, script: String): String {
        var result: String? = null
        compose.runOnUiThread { browser.selected!!.webView.evaluateJavascript(script) { result = it } }
        compose.waitUntil(5000) { result != null }
        return result!!
    }
    private fun browser(): BrowserModel {
        lateinit var browser: BrowserModel
        compose.runOnUiThread {
            context.getSharedPreferences("browser-tabs-v1",0).edit().clear().commit()
            browser = BrowserModel(context)
            val server = BrowserHttpsFixture().also { fixture = it }
            server.respond = { BrowserHttpsFixture.Response("""<meta name='viewport' content='width=device-width,initial-scale=1'><title>READY</title><button style='width:100%;height:80px' onclick="window.answer=prompt('Name','initial')">Prompt</button>""") }
            browser.open(server.base + "/")
        }
        compose.setContent { AmbulandoTheme { BrowserScreen(browser) } }
        compose.waitUntil(15000) { browser.selected?.title == "READY" && !browser.selected!!.loading }
        return browser
    }
    @Test fun promptCompletesOnceAndSwitchCancelsOutstandingDialog() {
        val browser = browser()
        try {
            compose.onNodeWithTag("browser.page").performTouchInput { click(Offset(100f,100f)) }
            compose.waitUntil(5000) { browser.dialog != null }
            val prompt = browser.dialog!!
            assertEquals("initial", prompt.defaultValue)
            compose.runOnUiThread { prompt.reply(true,"chosen"); prompt.reply(false); browser.dialog=null }
            compose.waitUntil(5000) { js(browser,"window.answer") == "\"chosen\"" }
            js(browser,"setTimeout(()=>window.cancelled=confirm('Continue?'),0);true")
            compose.waitUntil(5000) { browser.dialog != null }
            val parent = browser.selected!!
            compose.runOnUiThread { browser.open(null); browser.select(parent) }
            assertNull(browser.dialog)
            compose.waitUntil(5000) { js(browser,"window.cancelled") == "false" }
        } finally { compose.runOnUiThread { browser.closeAll() } }
    }
    @Test fun permissionAndFullscreenCancelOnHideNavigationAndLock() {
        val browser = browser()
        try {
            var grants=0; var denials=0
            val request = object: PermissionRequest() {
                override fun getOrigin() = Uri.parse(browser.selected!!.origin)
                override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE)
                override fun grant(resources: Array<String>) { grants++ }
                override fun deny() { denials++ }
            }
            var exits=0
            compose.runOnUiThread {
                browser.selected!!.webView.webChromeClient!!.onPermissionRequest(request)
                assertNotNull(browser.permission)
                browser.selected!!.webView.webChromeClient!!.onShowCustomView(android.view.View(context), WebChromeClient.CustomViewCallback { exits++ })
                assertNotNull(browser.fullscreen)
                browser.hide(); browser.hide(); browser.exitFullscreen()
                assertNull(browser.permission); assertNull(browser.fullscreen)
            }
            assertEquals(0,grants); assertEquals(1,denials); assertEquals(1,exits)
        } finally { compose.runOnUiThread { browser.closeAll() } }
    }
    @Test fun externalSignerIntentDropsExtrasFlagsPackageAndRefusesComponents() {
        val intent = BrowserExternal.intent("intent:abc#Intent;scheme=nostrsigner;package=com.example.signer;S.browser_fallback_url=https%3A%2F%2Fevil.example;end")
        assertEquals("nostrsigner:abc",intent.dataString)
        assertEquals(Intent.ACTION_VIEW,intent.action)
        assertNull(intent.component); assertNull(intent.`package`); assertNull(intent.extras)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(runCatching { BrowserExternal.intent("intent:abc#Intent;scheme=nostrsigner;component=com.evil/.Hidden;end") }.isFailure)
        assertTrue(runCatching { BrowserExternal.intent("intent:abc#Intent;scheme=file;end") }.isFailure)
    }
}
