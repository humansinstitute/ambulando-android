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

@RunWith(AndroidJUnit4::class)
class BrowserCaptureTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun node(root:AccessibilityNodeInfo?,label:String):AccessibilityNodeInfo? {
        root?:return null
        if(root.contentDescription?.toString()?.equals(label,true)==true || root.text?.toString()?.equals(label,true)==true || root.viewIdResourceName?.endsWith(":id/$label")==true)return root
        for(i in 0 until root.childCount)node(root.getChild(i),label)?.let{return it}
        return null
    }
    private fun tap(vararg labels:String) {
        repeat(200){for(label in labels){var found=node(instrumentation.uiAutomation.rootInActiveWindow,label);if(found!=null){while(found!=null && !found.isClickable)found=found.parent;if(found?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return}};Thread.sleep(50)}
        error("Capture UI missing ${labels.toList()}")
    }
    @Test fun cameraCaptureReturnsImageBytesAndTemporaryFileIsDeletedOnExit() {
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.CAMERA)
        val fixture=BrowserHttpsFixture()
        fixture.respond={BrowserHttpsFixture.Response("""
          <meta name='viewport' content='width=device-width,initial-scale=1'><title>READY</title>
          <input type='file' accept='image/*' capture='environment' style='width:100%;height:80px' onchange="this.files[0].arrayBuffer().then(b=>{const a=new Uint8Array(b);document.title='CAPTURE '+a.length+' '+a[0]+' '+a[1]})">
        """.trimIndent())}
        lateinit var browser:BrowserModel
        compose.runOnUiThread{browser=BrowserModel(context);browser.open(fixture.base+"/")}
        compose.setContent{AmbulandoTheme{BrowserScreen(browser)}}
        try {
            compose.waitUntil(15000){browser.selected?.title=="READY" && !browser.selected!!.loading}
            val density=context.resources.displayMetrics.density
            compose.onNodeWithTag("browser.page").performTouchInput{click(Offset(80f*density,40f*density))}
            compose.waitUntil(10000){browser.fileSelection?.captureUri!=null}
            val capture=browser.fileSelection!!.captureUri!!
            // The AOSP camera asks for its own permission on first launch on a disposable AVD.
            repeat(20){val allow=node(instrumentation.uiAutomation.rootInActiveWindow,"While using the app");if(allow!=null)allow.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(100)}
            tap("Shutter","Take photo","shutter_button")
            tap("Done","review_done","Review done")
            compose.waitUntil(20000){browser.selected!!.title.startsWith("CAPTURE ")}
            val result=browser.selected!!.title.split(' ')
            assertTrue(result.toString(),result[1].toInt()>1000)
            assertEquals("255",result[2]);assertEquals("216",result[3])
            assertTrue(context.contentResolver.openInputStream(capture)!!.use{it.read()>=0})
            compose.runOnUiThread{browser.hide()}
            assertTrue(runCatching{context.contentResolver.openInputStream(capture)!!.use{it.read()}}.isFailure)
        }finally{compose.runOnUiThread{browser.closeAll()};fixture.close()}
    }
}
