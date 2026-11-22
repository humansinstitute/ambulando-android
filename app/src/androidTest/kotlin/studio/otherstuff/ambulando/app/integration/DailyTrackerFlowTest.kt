package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Full native UI with signed in-process synthetic NStack. No member storage. */
@RunWith(AndroidJUnit4::class)
class DailyTrackerFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun createTrustDefineSaveCloseAndReopenFromFiles() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window
            scenario.onActivity { window = it.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.section = AppSection.DOCS }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("artefacts.createTracker")).fetchSemanticsNodes().isNotEmpty() }
            val started = android.os.SystemClock.elapsedRealtime()
            compose.onNodeWithTag("artefacts.createTracker").performClick()
            compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.trust").performClick()
            var web: WebView? = null
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            android.util.Log.i("FileNappletPerf", "native empty create/trust/open ms=" + (android.os.SystemClock.elapsedRealtime() - started))
            assertTrue(web!!.height > 300)
            assertEquals("false", page(web!!, "document.documentElement.scrollWidth>innerWidth"))
            page(web!!, "document.getElementById('manage').click();document.querySelector('input[placeholder]').value='Boolean test';Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Add metric').click()")
            assertEquals("1", page(web!!, "document.querySelectorAll('#content .metric').length-1"))
            page(web!!, "document.getElementById('checkin').click();Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').click();document.getElementById('save').click()")
            compose.waitUntil(20_000) { page(web!!, "document.getElementById('status').textContent")?.startsWith("Saved in NStack") == true }
            // Native Back cannot silently discard an unsaved edit.
            page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Yes').click()")
            compose.onNodeWithTag("napplet.close").performClick()
            compose.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').click();document.getElementById('save').click()")
            compose.waitUntil(20_000) { page(web!!, "document.getElementById('status').textContent")?.startsWith("Saved in NStack") == true }
            // Save is durable in synthetic File storage; use the actual File route again after destroying the page.
            compose.onNodeWithTag("napplet.close").performClick()
            compose.runOnIdle { model.selectContext(null, null); model.section = AppSection.FILES }
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Daily tracker.html")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Daily tracker.html").onFirst().performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            compose.onNodeWithTag("napplet.close").assertIsDisplayed()
            // DOM readiness may precede Chromium/Compose presentation; require actual drawn content.
            var image: android.graphics.Bitmap? = null
            compose.waitUntil(10_000) {
                image = instrumentation.uiAutomation.takeScreenshot()
                var drawn = 0
                val bitmap = image!!
                for (x in 0 until bitmap.width step 4) for (y in 150 until bitmap.height - 200 step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) < 180 && android.graphics.Color.green(pixel) < 180 && android.graphics.Color.blue(pixel) < 180) drawn++
                }
                drawn > 200
            }
            java.io.File(context.getExternalFilesDir(null), "daily-tracker-synthetic.png").outputStream().use { image!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun find(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun page(web: WebView, script: String): String? {
        val latch = java.util.concurrent.CountDownLatch(1); var value: String? = null
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { raw -> value = raw?.let { org.json.JSONTokener(it).nextValue()?.toString() }; latch.countDown() } }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); return value
    }
}
