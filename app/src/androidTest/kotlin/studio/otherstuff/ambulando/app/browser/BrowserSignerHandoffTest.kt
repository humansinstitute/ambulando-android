package studio.otherstuff.ambulando.app.browser

import android.view.accessibility.AccessibilityNodeInfo
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
class BrowserSignerHandoffTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun find(n:AccessibilityNodeInfo?,text:String):AccessibilityNodeInfo?{n?:return null;if(n.text?.toString()==text)return n;for(i in 0 until n.childCount)find(n.getChild(i),text)?.let{return it};return null}
    @Test fun confirmedSignerLaunchReturnAndCancelPreserveOriginatingTab() {

        val fixture=BrowserHttpsFixture()
        lateinit var browser:BrowserModel
        compose.runOnUiThread{browser=BrowserModel(instrumentation.targetContext);browser.open(fixture.base+"/")}
        compose.setContent{AmbulandoTheme{BrowserScreen(browser)}}
        try{
            compose.waitUntil(15000){browser.selected?.title=="READY" && !browser.selected!!.loading}
            val tab=browser.selected!!
            for(label in listOf("Return to browser","Cancel signer")) {
                compose.runOnUiThread{browser.load("nostrconnect:disposable-fixture",tab)}
                compose.onNodeWithText("Open nostrconnect:").performClick()
                compose.waitUntil(10000){find(instrumentation.uiAutomation.rootInActiveWindow,label)!=null}
                find(instrumentation.uiAutomation.rootInActiveWindow,label)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                compose.waitUntil(10000){instrumentation.uiAutomation.rootInActiveWindow?.packageName==instrumentation.targetContext.packageName}
                assertSame(tab,browser.selected);assertEquals(fixture.base+"/",tab.url);assertNull(browser.pendingExternal)
            }
            compose.runOnUiThread{browser.load("nostrconnect:disposable-fixture",tab);browser.open(null)}
            assertNull(browser.pendingExternal)
        }finally{compose.runOnUiThread{browser.closeAll()};fixture.close()}
    }
}
