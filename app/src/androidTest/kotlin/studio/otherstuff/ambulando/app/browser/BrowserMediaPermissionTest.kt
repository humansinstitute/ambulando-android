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

/** Actual Chromium camera request, Android permission contract, native consent and revocation. */
@RunWith(AndroidJUnit4::class)
class BrowserMediaPermissionTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @Test fun cameraNeedsPageConsentAndRevocationEndsTracksByReloadingDocument() {
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.CAMERA)
        val fixture=BrowserHttpsFixture()
        fixture.respond={BrowserHttpsFixture.Response("""
          <meta name='viewport' content='width=device-width,initial-scale=1'><title>READY</title>
          <button style='width:100%;height:80px' onclick="document.title='REQUESTING';navigator.mediaDevices.getUserMedia({video:true}).then(s=>{window.stream=s;document.title='CAMERA'}).catch(e=>document.title='DENIED '+e.name)">Camera</button>
        """.trimIndent())}
        lateinit var browser:BrowserModel
        compose.runOnUiThread{browser=BrowserModel(context);browser.open(fixture.base+"/")}
        compose.setContent{AmbulandoTheme{BrowserScreen(browser)}}
        val density=context.resources.displayMetrics.density
        fun tap(){compose.onNodeWithTag("browser.page").performTouchInput{click(Offset(80f*density,40f*density))}}
        try {
            compose.waitUntil(15000){browser.selected?.title=="READY" && !browser.selected!!.loading}
            tap();compose.waitUntil(10000){browser.permission!=null}
            assertEquals(fixture.base,browser.permission!!.origin)
            compose.onNodeWithText("Deny").performClick()
            compose.waitUntil(10000){browser.selected!!.title.startsWith("DENIED")}
            tap();compose.waitUntil(10000){browser.permission!=null}
            compose.onNodeWithText("Allow",useUnmergedTree=true).performClick()
            compose.waitUntil(15000){browser.selected!!.title=="CAMERA" || browser.selected!!.title.startsWith("DENIED")}
            assertEquals("CAMERA",browser.selected!!.title)
            val doc=browser.selected!!.documentId
            compose.runOnUiThread{browser.revokePagePermissions(browser.selected!!)}
            compose.waitUntil(15000){browser.selected!!.documentId>doc && browser.selected!!.title=="READY" && !browser.selected!!.loading}
            assertNull(browser.permission)
            tap();compose.waitUntil(10000){browser.permission!=null}
            compose.runOnUiThread{browser.updateLocked(true)}
            compose.waitUntil(10000){browser.permission==null && browser.selected!!.title.startsWith("DENIED")}
        } finally{compose.runOnUiThread{browser.closeAll()};fixture.close()}
    }
}
