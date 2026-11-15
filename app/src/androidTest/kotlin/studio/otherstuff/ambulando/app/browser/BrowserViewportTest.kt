package studio.otherstuff.ambulando.app.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/** Real WebView layout: loaded DOM alone does not establish that a page is visible. */
@RunWith(AndroidJUnit4::class)
class BrowserViewportTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun evaluate(browser: BrowserModel, script: String): String {
        var result: String? = null
        compose.runOnUiThread { browser.selected!!.webView.evaluateJavascript(script) { result = it } }
        compose.waitUntil(5_000) { result != null }
        return result!!
    }

    private fun awaitValue(browser: BrowserModel, script: String, expected: String = "true") {
        repeat(100) {
            if (evaluate(browser, script) == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, evaluate(browser, script))
    }

    @Test fun privateNestedFramesFillViewportAfterResizeTabSwitchAndHistory() {
        val fixture = BrowserHttpsFixture()
        fixture.respond = { request ->
            when (request.target) {
                "/" -> BrowserHttpsFixture.Response("""
                    <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>html,body{margin:0;height:100%}iframe{width:100%;height:90%;border:0}</style>
                    <title>VIEWPORT</title><header>Private dashboard</header><iframe id="frame" src="/private"></iframe>
                """.trimIndent(), headers = mapOf("Set-Cookie" to "viewport=allowed; Path=/; Secure; HttpOnly; SameSite=Lax"))
                "/private" -> if (request.headers["cookie"]?.contains("viewport=allowed") == true) BrowserHttpsFixture.Response("""
                    <!doctype html><style>html,body{margin:0;height:100%}iframe{height:80%;width:100%;border:0}</style>
                    <h1>Private content</h1><iframe id="nested" src="/inner"></iframe>
                """.trimIndent()) else BrowserHttpsFixture.Response("Sign in", status = 403)
                "/inner" -> BrowserHttpsFixture.Response("""
                    <!doctype html><script src="/inner.js"></script><h1>Nested content</h1>
                """.trimIndent())
                "/inner.js" -> BrowserHttpsFixture.Response("window.loaded=true;", headers = mapOf("Content-Type" to "application/javascript"))
                "/next" -> BrowserHttpsFixture.Response("<title>NEXT</title><h1>A normal page</h1>")
                else -> BrowserHttpsFixture.Response(status = 404)
            }
        }
        val landscape = mutableStateOf(false)
        lateinit var browser: BrowserModel
        compose.runOnUiThread { browser = BrowserModel(instrumentation.targetContext); browser.open(fixture.base + "/") }
        compose.setContent {
            AmbulandoTheme {
                Box(if (landscape.value) Modifier.width(340.dp).height(280.dp) else Modifier.fillMaxSize()) { BrowserScreen(browser) }
            }
        }
        val visible = """
            (()=>{const f=document.getElementById('frame'),w=f?.contentWindow,n=w?.document.getElementById('nested');
              return !!n && f.clientHeight>100 && n.clientHeight>50 && n.contentWindow.loaded===true
                && !w.nostr && !n.contentWindow.nostr && !!window.nostr;})()
        """.trimIndent()
        try {
            compose.waitUntil(15_000) { browser.selected?.title == "VIEWPORT" && !browser.selected!!.loading }
            compose.waitForIdle()
            assertEquals("The loaded private frame must occupy visible space", "true", evaluate(browser, visible))
            val originalHeight = evaluate(browser, "document.getElementById('frame').clientHeight").toInt()
            evaluate(browser, "document.getElementById('frame').contentWindow.localStorage.setItem('viewport-note','retained');true")
            compose.runOnUiThread { landscape.value = true }
            compose.waitForIdle()
            awaitValue(browser, visible)
            awaitValue(browser, "document.getElementById('frame').clientHeight < $originalHeight")
            val parent = browser.selected!!
            compose.runOnUiThread { browser.open(fixture.base + "/next") }
            compose.waitUntil(15_000) { browser.selected?.title == "NEXT" && !browser.selected!!.loading }
            compose.runOnUiThread { browser.close(browser.selected!!); browser.select(parent); landscape.value = false }
            compose.waitForIdle()
            awaitValue(browser, visible)
            compose.runOnUiThread { browser.load(fixture.base + "/next", parent) }
            compose.waitUntil(15_000) { parent.title == "NEXT" && !parent.loading }
            compose.runOnUiThread { browser.back(parent) }
            compose.waitUntil(15_000) { parent.title == "VIEWPORT" && !parent.loading }
            compose.waitForIdle()
            awaitValue(browser, visible)
            assertEquals("\"retained\"", evaluate(browser, "document.getElementById('frame').contentWindow.localStorage.getItem('viewport-note')"))
            evaluate(browser, "localStorage.removeItem('viewport-note');true")
            assertNull(parent.refusal)
        } finally {
            compose.runOnUiThread {
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile("browser").cookieManager
                    .setCookie(fixture.base, "viewport=; Max-Age=0; Path=/; Secure")
                browser.closeAll()
            }
            fixture.close()
        }
    }
}
