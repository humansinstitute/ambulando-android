package studio.otherstuff.ambulando.app.browser

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

/** Separately from actual picker open/cancel, a deterministic real content-tree result verifies delivery and paths. */
@RunWith(AndroidJUnit4::class)
class BrowserDirectoryUploadTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @Test fun selectedFolderReturnsActualBytesAndRelativePathsBeforeChangeHandler() {
        val context=instrumentation.targetContext
        val tree=android.provider.DocumentsContract.buildTreeDocumentUri(BrowserTreeFixtureProvider.AUTHORITY,"fixture")
        val registry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE,contract.createIntent(context,input).action)
                context.contentResolver.call(tree,"select",context.packageName,null)
                android.os.Handler(android.os.Looper.getMainLooper()).post{dispatchResult(code,android.app.Activity.RESULT_OK,android.content.Intent().setData(tree))}
            }
        }
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=registry}
        val fixture=BrowserHttpsFixture()
        fixture.respond={BrowserHttpsFixture.Response("""
            <meta name='viewport' content='width=device-width,initial-scale=1'><title>READY</title>
            <input type='file' webkitdirectory style='height:80px;width:100%' onchange="Promise.all(Array.from(this.files).map(async f=>({name:f.name,path:f.webkitRelativePath,text:await f.text()}))).then(rows=>{window.rows=rows;document.title='UPLOADED '+rows.length}).catch(e=>document.title='ERR '+e.message)">
        """.trimIndent())}
        lateinit var browser:BrowserModel
        compose.runOnUiThread{browser=BrowserModel(context);browser.open(fixture.base+"/")}
        compose.setContent{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){AmbulandoTheme{BrowserScreen(browser)}}}
        try {
            compose.waitUntil(15000){browser.selected?.title=="READY" && !browser.selected!!.loading}
            val density=context.resources.displayMetrics.density
            compose.onNodeWithTag("browser.page").performTouchInput{click(Offset(80f*density,40f*density))}
            compose.waitUntil(15000){browser.selected!!.title.startsWith("UPLOADED") || browser.selected!!.title.startsWith("ERR") || browser.selected!!.refusal!=null}
            assertEquals(browser.selected!!.refusal,"UPLOADED 2",browser.selected!!.title)
            var rows:String?=null
            compose.runOnUiThread{browser.selected!!.webView.evaluateJavascript("JSON.stringify(window.rows)"){rows=it}}
            compose.waitUntil(5000){rows!=null}
            assertTrue(rows.orEmpty(),rows!!.contains("fixture/root.txt"));assertTrue(rows.orEmpty(),rows!!.contains("fixture/sub/child.txt"))
            assertTrue(rows.orEmpty(),rows!!.contains("root-bytes"));assertTrue(rows.orEmpty(),rows!!.contains("child-bytes"))
        }finally{compose.runOnUiThread{browser.closeAll()};fixture.close()}
    }
}
