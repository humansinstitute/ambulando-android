package studio.otherstuff.ambulando.app.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/** Actual Chromium windows and trusted touch input, using deterministic HTTPS-origin HTML. */
@RunWith(AndroidJUnit4::class)
class BrowserNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var fixture: BrowserHttpsFixture
    private val parentPage = """
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <body style="margin:0">
                <button style="width:100%;height:80px" onclick="const p=window.open('about:blank'); if(p){p.document.write('<title>WRITTEN POPUP</title><body>Popup content');p.document.close();}">Blank popup</button>
                <a style="display:block;height:80px" href="/redirect?q={value}|other">Navigate</a>
                <a style="display:block;height:80px" target="_blank" href="/child">Link popup</a>
                <button style="width:100%;height:80px" onclick="window.open('/child')">Script popup</button>
                <form method="post" target="_blank" action="/posted"><input name="token" value="preserved" type="hidden"><button style="width:100%;height:80px">POST popup</button></form>
                <script>document.title='READY';window.addEventListener('message',e=>{if(e.origin===location.origin)document.title='MESSAGE '+e.data});</script>
    """.trimIndent()
    private fun start(): BrowserModel {
        fixture = BrowserHttpsFixture()
        fixture.respond = { request ->
            when {
                request.target.startsWith("/redirect") -> BrowserHttpsFixture.Response(status = 302, headers = mapOf("Location" to "/landed", "Set-Cookie" to "browserfixture=continuous; Path=/; Secure; HttpOnly; SameSite=Lax"))
                request.target == "/landed" -> BrowserHttpsFixture.Response("<title>LANDED ${request.headers["cookie"]}</title>")
                request.target == "/child" -> BrowserHttpsFixture.Response("<title>CHILD ${request.headers["cookie"]}</title><script>if(window.opener)window.opener.postMessage('child',location.origin)</script>")
                request.target == "/posted" -> BrowserHttpsFixture.Response("<title>${request.method} ${request.body}</title>")
                request.target == "/failure" -> BrowserHttpsFixture.Response("<title>SERVER FAILURE</title>", status = 503)
                request.target == "/subresource" -> BrowserHttpsFixture.Response("<title>SUBRESOURCE</title><iframe src='/failure'></iframe><img src='/failure'>")
                else -> BrowserHttpsFixture.Response(parentPage)
            }
        }
        lateinit var browser: BrowserModel
        compose.runOnUiThread {
            browser = BrowserModel(instrumentation.targetContext)
            browser.open(fixture.base + "/")
        }
        compose.setContent { AmbulandoTheme { BrowserScreen(browser) } }
        compose.waitUntil(20_000) { browser.selected?.title == "READY" }
        return browser
    }
    private fun tap(y: Float) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("browser.page").performTouchInput { click(Offset(40f * density, y * density)) }
    }
    @Test fun openerWrittenBlankPopupIsVisibleAndCloseReturnsToOpener() {
        val browser = start()
        try {
            val parent = browser.selected!!
            tap(40f)
            compose.waitUntil(10_000) { browser.tabs.size == 2 }
            var contents = ""
            compose.runOnUiThread {
                browser.selected!!.webView.evaluateJavascript("JSON.stringify({body:document.body?.innerText,opener:!!window.opener,nostr:!!window.nostr})") { contents = it }
            }
            compose.waitUntil(5_000) { contents.isNotEmpty() }
            assertTrue(contents, contents.contains("Popup content"))
            assertTrue(contents, contents.contains("opener\\\":true"))
            assertTrue(contents, contents.contains("nostr\\\":false"))
            assertNull(browser.selected!!.origin)
            compose.onNodeWithTag("browser.page").assertExists()
            compose.runOnUiThread { browser.selected!!.webView.evaluateJavascript("window.close()", null) }
            compose.waitUntil(10_000) { browser.tabs.size == 1 }
            assertSame(parent, browser.selected)
        } finally { compose.runOnUiThread { browser.closeAll() }; fixture.close() }
    }
    @Test fun browserValidQueryNavigationIsNotSilentlyCancelled() {
        val browser = start()
        try {
            tap(120f)
            compose.waitUntil(10_000) { browser.selected?.title?.startsWith("LANDED browserfixture=continuous") == true }
            assertNull(browser.selected?.refusal)
            assertEquals(fixture.base, browser.selected?.origin)
            compose.runOnUiThread { browser.back(browser.selected!!) }
            compose.waitUntil(10_000) { browser.selected?.title == "READY" }
            compose.runOnUiThread { browser.forward(browser.selected!!) }
            compose.waitUntil(10_000) { browser.selected?.title?.startsWith("LANDED browserfixture=continuous") == true }
        } finally { compose.runOnUiThread { browser.closeAll() }; fixture.close() }
    }
    @Test fun linkScriptAndPostPopupsKeepSessionAndOpener() {
        val browser = start()
        try {
            val parent = browser.selected!!
            compose.runOnUiThread { browser.load(fixture.base + "/redirect", parent) }
            compose.waitUntil(15_000) { parent.title.startsWith("LANDED browserfixture=continuous") }
            compose.runOnUiThread { browser.load(fixture.base + "/", parent) }
            compose.waitUntil(15_000) { parent.title == "READY" }
            for (y in listOf(200f, 280f, 360f)) {
                tap(y)
                compose.waitUntil(15_000) { browser.tabs.size == 2 && browser.selected?.loading == false && browser.selected?.title?.let { it.startsWith("CHILD") || it.startsWith("POST") } == true }
                val child = browser.selected!!
                if (y == 360f) assertEquals("POST token=preserved", child.title) else assertTrue(child.title, child.title.contains("browserfixture=continuous"))
                assertEquals(fixture.base, child.origin)
                if (y == 280f) assertEquals("MESSAGE child", parent.title)
                compose.runOnUiThread { child.webView.evaluateJavascript("window.close()", null) }
                compose.waitUntil(10_000) { browser.tabs.size == 1 }
                assertSame(parent, browser.selected)
            }
        } finally { compose.runOnUiThread { browser.closeAll() }; fixture.close() }
    }
    @Test fun mainFrameFailuresAreVisibleButSubresourceFailuresDoNotReplacePage() {
        val browser = start()
        try {
            val tab = browser.selected!!
            compose.runOnUiThread { browser.load(fixture.base + "/failure", tab) }
            compose.waitUntil(15_000) { tab.refusal?.contains("HTTP 503") == true }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("browser.refusal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("browser.refusal").assertExists()
            compose.runOnUiThread { browser.load(fixture.base + "/subresource", tab) }
            compose.waitUntil(15_000) { tab.title == "SUBRESOURCE" && !tab.loading }
            assertNull(tab.refusal)
            compose.runOnUiThread { browser.load("https://127.0.0.1:${java.net.URI(fixture.base).port}/", tab) }
            compose.waitUntil(15_000) { tab.refusal?.contains("secure connection") == true || tab.refusal?.contains("Could not load") == true }
            assertNull(tab.origin)
            assertFalse(tab.loading)
        } finally { compose.runOnUiThread { browser.closeAll() }; fixture.close() }
    }
    @Test fun popupAtTabLimitDoesNotDestroyOpenerOrExistingTabs() {
        val browser = start()
        try {
            val parent = browser.selected!!
            compose.runOnUiThread {
                repeat(BrowserModel.MAX_TABS - 1) { browser.open(null) }
                browser.select(parent)
            }
            val original = browser.tabs.toList()
            tap(280f)
            compose.waitUntil(10_000) { parent.refusal?.contains("Close a browser tab") == true }
            assertEquals(original, browser.tabs.toList())
            assertSame(parent, browser.selected)
        } finally { compose.runOnUiThread { browser.closeAll() }; fixture.close() }
    }

}
