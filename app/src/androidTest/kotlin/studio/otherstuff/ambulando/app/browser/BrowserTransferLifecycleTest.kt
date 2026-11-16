package studio.otherstuff.ambulando.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Races are driven through the actual renderer bridge, not by directly mutating transfer state. */
@RunWith(AndroidJUnit4::class)
class BrowserTransferLifecycleTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(condition:()->Boolean){repeat(200){var ready=false;instrumentation.runOnMainSync{ready=condition()};if(ready)return;Thread.sleep(25)};error("Transfer state did not settle")}
    @Test fun competingStartCannotReplaceExportAndNavigationCancelsOldDocument() {
        val fixture=BrowserHttpsFixture()
        fixture.respond={BrowserHttpsFixture.Response("<title>READY</title>")}
        lateinit var browser:BrowserModel
        instrumentation.runOnMainSync{browser=BrowserModel(instrumentation.targetContext);browser.open(fixture.base+"/")}
        fun js(script:String){instrumentation.runOnMainSync{browser.selected!!.webView.evaluateJavascript(script,null)}}
        try {
            waitFor{browser.selected?.title=="READY" && !browser.selected!!.loading}
            js("ambulandoFiles.postMessage(JSON.stringify({id:900,start:true,filename:'first.bin'}));ambulandoFiles.postMessage(JSON.stringify({id:901,start:true,filename:'second.bin'}));true")
            waitFor{browser.selected!!.refusal?.contains("current download") == true}
            js("ambulandoFiles.postMessage(JSON.stringify({id:902,chunk:btoa('first-export')}));ambulandoFiles.postMessage(JSON.stringify({id:903,end:true}));true")
            waitFor{browser.download!=null}
            assertEquals("first.bin",browser.download!!.filename)
            assertEquals("first-export",browser.download!!.file!!.readText())
            val file=browser.download!!.file!!
            instrumentation.runOnMainSync{browser.load(fixture.base+"/new",browser.selected!!)}
            waitFor{browser.selected!!.url?.endsWith("/new")==true && !browser.selected!!.loading}
            assertFalse(file.exists());assertNull(browser.download)
            js("ambulandoFiles.postMessage(JSON.stringify({id:904,start:true,filename:'stale.bin'}));location.href='/next';true")
            waitFor{browser.selected!!.url?.endsWith("/next")==true && !browser.selected!!.loading}
            Thread.sleep(100)
            assertNull(browser.download)
            val parts=instrumentation.targetContext.cacheDir.listFiles().orEmpty().filter{it.name.startsWith("browser-export-")}
            assertTrue("No stale generated spool: $parts",parts.isEmpty())
        }finally{instrumentation.runOnMainSync{browser.closeAll()};fixture.close()}
    }
}
