package studio.otherstuff.ambulando.app.browser

import android.view.accessibility.AccessibilityNodeInfo
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

/** Real Chromium + native DocumentsUI; only synthetic dedicated emulator content. */
@RunWith(AndroidJUnit4::class)
class BrowserFilesTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString()?.equals(text, ignoreCase = true) == true && root.isClickable) return root
        for (index in 0 until root.childCount) find(root.getChild(index), text)?.let { return it }
        return null
    }
    private fun browser(): BrowserModel {
        lateinit var browser: BrowserModel
        compose.runOnUiThread {
            browser = BrowserModel(instrumentation.targetContext)
            browser.samplePage = "https://sample.example/" to """
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <body style="margin:0"><input id="file" type="file" style="width:100%;height:80px" onchange="this.files[0].text().then(t=>document.title='UPLOAD '+t)">
                <a id="blob" download="ambulando-batch-blob.txt" style="display:block;height:80px">Save blob</a>
                <a href="data:text/plain;base64,ZGF0YS1maWxl" download="ambulando-batch-data.txt" style="display:block;height:80px">Save data</a>
                <a target="_blank" href="https://sample.example/child" style="display:block;height:80px">New window</a>
                <script>blob.href=URL.createObjectURL(new Blob(['blob-file'],{type:'text/plain'}));document.title='READY';</script>
            """.trimIndent()
            browser.open("https://sample.example/")
        }
        compose.setContent { AmbulandoTheme { BrowserScreen(browser) } }
        compose.waitUntil(20_000) { browser.selected?.title == "READY" }
        return browser
    }
    private fun tapPage(y: Float) {
        compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithTag("browser.page").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("browser.page").performTouchInput { click(Offset(60f * density, y * density)) }
    }
    @Test fun blobAndDataSaveThroughNativePickerAndPickerCancelReturnsToPage() {
        val browser = browser()
        try {
            tapPage(120f)
            compose.waitUntil(10_000) { find(instrumentation.uiAutomation.rootInActiveWindow, "Save") != null }
            assertNotNull(browser.download)
            find(instrumentation.uiAutomation.rootInActiveWindow, "Save")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitUntil(20_000) { browser.selected?.refusal?.startsWith("Saved ambulando-batch-blob") == true }
            tapPage(200f)
            compose.waitUntil(10_000) { find(instrumentation.uiAutomation.rootInActiveWindow, "Save") != null }
            find(instrumentation.uiAutomation.rootInActiveWindow, "Save")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitUntil(20_000) { browser.selected?.refusal?.startsWith("Saved ambulando-batch-data") == true }
            compose.runOnIdle { assertEquals("https://sample.example", browser.selected!!.origin) }
            tapPage(120f)
            compose.waitUntil(10_000) { find(instrumentation.uiAutomation.rootInActiveWindow, "Save") != null }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(10_000) { browser.download == null }
        } finally { compose.runOnIdle { browser.closeAll() } }
    }
    @Test fun oversizedPageFileFailsVisiblyWithoutOpeningNativeSavePicker() {
        val browser = browser()
        try {
            compose.runOnUiThread { browser.selected!!.webView.evaluateJavascript("blob.href=URL.createObjectURL(new Blob([new Uint8Array(16*1024*1024+1)]))", null) }
            tapPage(120f)
            compose.waitUntil(10_000) { browser.selected?.refusal?.contains("16 MB") == true }
            assertNull(browser.download)
        } finally { compose.runOnIdle { browser.closeAll() } }
    }

    /** Opt-in live HTTPS smoke; default offline suite does not depend on external site availability. */
    @Test fun liveHttpsDownloadHttpErrorAndInProgressCancellation() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveBrowserDownloads") == "true")
        val browser = browser()
        fun link(url: String, filename: String) {
            compose.runOnUiThread { browser.selected!!.refusal = null; browser.selected!!.webView.evaluateJavascript("blob.href=" + org.json.JSONObject.quote(url) + ";blob.download=" + org.json.JSONObject.quote(filename), null) }
            tapPage(120f)
            compose.waitUntil(10_000) { find(instrumentation.uiAutomation.rootInActiveWindow, "Save") != null }
            find(instrumentation.uiAutomation.rootInActiveWindow, "Save")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        try {
            link("https://example.com/", "ambulando-live-https.html")
            compose.waitUntil(45_000) { browser.selected?.refusal != null && browser.fileTransfer == null }
            assertTrue(browser.selected?.refusal.orEmpty(), browser.selected?.refusal?.startsWith("Saved") == true)
            link("https://example.com/ambulando-file-not-found", "ambulando-live-error.txt")
            compose.waitUntil(45_000) { browser.selected?.refusal != null && browser.fileTransfer == null }
            assertTrue(browser.selected?.refusal.orEmpty(), browser.selected?.refusal?.contains("HTTP 404") == true)
            link("https://httpbin.org/delay/10", "ambulando-live-cancel.txt")
            compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithTag("browser.download.cancel").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            compose.onNodeWithTag("browser.download.cancel").performClick()
            compose.waitUntil(15_000) { browser.fileTransfer == null }
            assertEquals("Download cancelled.", browser.selected?.refusal)
        } finally { compose.runOnIdle { browser.closeAll() } }
    }

    @Test fun actualFileInputCanCancelThenReadOnlyExplicitlySelectedContentUri() {
        val browser = browser()
        try {
            tapPage(35f)
            compose.waitUntil(10_000) { browser.fileSelection != null && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(10_000) { browser.fileSelection == null }
            tapPage(35f)
            compose.waitUntil(10_000) { browser.fileSelection != null && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            val file = java.io.File(instrumentation.targetContext.cacheDir, "AmbulandoFiles/batch-upload.txt").apply { parentFile!!.mkdirs(); writeText("selected-file") }
            val uri = androidx.core.content.FileProvider.getUriForFile(instrumentation.targetContext, "studio.otherstuff.ambulando.android.files", file)
            // Complete the native selection with one explicit granted URI; Chromium still performs the real file read.
            compose.runOnUiThread { browser.fileSelection!!.finish(arrayOf(uri)) }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(20_000) { browser.selected?.title == "UPLOAD selected-file" }
        } finally { compose.runOnIdle { browser.closeAll() } }
    }
}
