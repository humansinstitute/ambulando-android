package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native approval -> SyncService -> signed synthetic API -> real shared cartridge; no live workspace mutation. */
@RunWith(AndroidJUnit4::class)
class WorkspaceStorageFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    @Test fun adminOptInConsentAndPeriodicRevocationClearRetainedTotals() {
        val context=i.targetContext;check(BuildConfig.DEBUG)
        if((context.applicationContext as AmbulandoApplication).activeModel==null) {
            val sample=AppEnvironment.Sample(context).baseDir;check(sample.parentFile==context.cacheDir);check(!sample.exists() || sample.deleteRecursively())
        }
        val intent=Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window;scenario.onActivity {window=it.window}
            val app=context.applicationContext as AmbulandoApplication
            compose.waitUntil(20000) {app.activeModel?.workspaces?.any {it.id==SampleIds.STUDIO}==true}
            val model=app.activeModel!!
            captureOnFailure(compose,model,"workspace-storage") {
                compose.runOnIdle {model.selectWorkspace(model.workspaces.first {it.id==SampleIds.STUDIO})}
                compose.waitUntil(20000) {model.channels.any {it.id==SampleIds.GENERAL} && model.activeEntry?.runtime?.writeActivity?.isOpen==true}
                val c=model.connection!!;val w=model.workspace!!;val api=model.fileNappletAPI(c)
                val tower=model.sampleTower!!
                val html=i.context.assets.open("napplets/workspace-storage-cartridge.html").bufferedReader().use {it.readText()}
                val golden=GenericJson.decode(i.context.assets.open("napplets/workspace-report-storage-v1.json").use {it.readBytes()})
                val storage=golden.getValue("value").jsonObject.getValue("storage").jsonObject
                val meta=buildJsonObject {put("html_artifact",true);put("napplet",buildJsonObject {put("version",2);put("app_id","workspace-report-generic");put("bindings",buildJsonObject {put("summary",buildJsonObject {put("workspace_id",w.id)})})})}
                val file=runBlocking {
                    val channel=api.createDailyTracker(w.id,w.appNpub,c.signerNpub,"<h1>Fixture bootstrap</h1>").channel
                    val objectId=api.store(w.id,w.appNpub,html.toByteArray(),"text/html","Storage report.html",null) {}
                    FileRecord.parse(GenericJson.decode(api.client.request(method="POST",path=TowerAPI.base(w.id)+"/channels/${channel.id}/files",appNpub=w.appNpub,jsonBody=jsonBody(buildJsonObject {put("storage_object_id",objectId);put("display_name","Storage report.html");put("metadata",meta)}))).getValue("file").jsonObject)
                }
                var denied=false;var reads=0;var writes=0
                tower.workspaceReportFixture={workspace,range ->
                    val base=SampleWorkspaceReport.value(workspace,range,w.raw.getValue("identity").jsonObject)
                    JsonObject(base+("storage" to JsonObject(storage+mapOf("workspace_id" to JsonPrimitive(workspace),"as_of" to base.getValue("as_of")))))
                }
                tower.requestGate={request ->
                    if(request.method!="GET") writes++
                    if(java.net.URI(request.url).path.endsWith("/workspace-report")) {
                        reads++;check(java.net.URI(request.url).rawQuery.split('&').contains("storage=workspace"));check(request.header("Authorization")!=null)
                        if(denied) throw TowerError.Http(403,"forbidden",null)
                    }
                }
                try {
                    compose.runOnIdle {model.section=AppSection.TOOLBOX}
                    compose.onNodeWithTag("apps.addNapplet").performClick()
                    compose.onNodeWithTag("apps.nappletLink").performTextReplacement(file.id)
                    i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    compose.onNodeWithText("Add and open").performClick()
                    compose.waitUntil(20000) {compose.onAllNodes(hasTestTag("generic.trust")).fetchSemanticsNodes().isNotEmpty()}
                    assertEquals(0,reads)
                    compose.onNode(hasText("including retained data from private scopes",substring=true)).assertExists()
                    compose.onNodeWithTag("generic.trust").performClick()
                    var web: WebView?=null
                    compose.waitUntil(25000) {i.runOnMainSync {web=find(window.decorView)};web!=null && page(web!!,"document.getElementById('storage')?.hidden")=="false"}
                    assertTrue(page(web!!,"document.getElementById('storage-groups').textContent").contains("18,668"))
                    // Adding the personal File pin is explicit setup; report reads must remain write-free.
                    writes=0
                    val before=reads;denied=true
                    compose.mainClock.advanceTimeBy(31000)
                    compose.waitUntil(40000) {page(web!!,"document.getElementById('status').textContent").contains("management permission")}
                    assertTrue(reads>before);assertEquals("true",page(web!!,"document.getElementById('storage').hidden"));assertEquals("",page(web!!,"document.getElementById('storage-groups').textContent"))
                    assertEquals(0,writes)
                    i.uiAutomation.takeScreenshot().let {bitmap -> context.getExternalFilesDir(null)!!.resolve("workspace-storage-denied.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
                } finally {tower.requestGate=null;tower.workspaceReportFixture=null}
            }
        }
    }
    private fun find(v: android.view.View): WebView? {if(v is WebView)return v;if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))?.let {return it};return null}
    private fun page(w: WebView,js: String): String {
        val done=CountDownLatch(1);var value=""
        i.runOnMainSync {w.evaluateJavascript(js) {value=org.json.JSONTokener(it).nextValue().toString();done.countDown()}}
        check(done.await(5,TimeUnit.SECONDS));return value
    }
}
