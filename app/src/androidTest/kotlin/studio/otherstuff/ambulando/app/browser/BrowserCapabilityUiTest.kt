package studio.otherstuff.ambulando.app.browser

import android.accessibilityservice.AccessibilityService
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

@RunWith(AndroidJUnit4::class)
class BrowserCapabilityUiTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @Test fun directoryInputOpensTreePickerAndCancelReturnsNullThenFullscreenExitsWithBack() {
        val fixture=BrowserHttpsFixture()
        fixture.respond={BrowserHttpsFixture.Response("""
            <meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>body{margin:0}input,button{display:block;width:100%;height:80px}</style><title>READY</title>
            <input id='folder' type='file' webkitdirectory onchange="document.title='FILES '+this.files.length">
            <button onclick="document.getElementById('full').requestFullscreen()">Fullscreen</button>
            <div id='full' style='background:navy;color:white'>Fullscreen fixture</div>
        """.trimIndent())}
        lateinit var browser:BrowserModel
        compose.runOnUiThread{browser=BrowserModel(instrumentation.targetContext);browser.open(fixture.base+"/")}
        compose.setContent{AmbulandoTheme{BrowserScreen(browser)}}
        try {
            compose.waitUntil(15000){browser.selected?.title=="READY" && !browser.selected!!.loading}
            val density=instrumentation.targetContext.resources.displayMetrics.density
            compose.onNodeWithTag("browser.page").performTouchInput{click(Offset(80f*density,40f*density))}
            compose.waitUntil(10000){browser.fileSelection!=null}
            compose.waitUntil(10000){instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
            assertTrue("webkitdirectory must use a tree picker",browser.fileSelection!!.directory)
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE,browser.fileSelection!!.intent(instrumentation.targetContext).action)
            repeat(8) {
                if (browser.fileSelection != null && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true) {
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    compose.waitForIdle()
                    Thread.sleep(250)
                }
            }
            compose.waitUntil(10000){browser.fileSelection==null}
            compose.onNodeWithTag("browser.page").performTouchInput{click(Offset(80f*density,120f*density))}
            compose.waitUntil(10000){browser.fullscreen!=null}
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(10000){browser.fullscreen==null}
            assertTrue(browser.presented)
        } finally{compose.runOnUiThread{browser.closeAll()};fixture.close()}
    }
    @Test fun missingExternalSignerReturnsToSameTabWithVisibleError() {
        lateinit var browser:BrowserModel
        compose.runOnUiThread{browser=BrowserModel(instrumentation.targetContext);browser.open(null);browser.load("nostrsigner:disposable-fixture",browser.selected!!)}
        compose.setContent{AmbulandoTheme{BrowserScreen(browser)}}
        val tab=browser.selected!!
        try {
            compose.onNodeWithText("Open nostrsigner:").performClick()
            compose.waitUntil(5000){tab.refusal!=null}
            assertTrue(tab.refusal.orEmpty().contains("No app"))
            assertSame(tab,browser.selected)
            assertNull(browser.pendingExternal)
        } finally{compose.runOnUiThread{browser.closeAll()}}
    }
}
