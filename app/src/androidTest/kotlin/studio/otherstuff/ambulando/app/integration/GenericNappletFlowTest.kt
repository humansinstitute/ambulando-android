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
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Two IDs/bindings generated after APK build, with one unchanged runtime. Synthetic NStack only. */
@RunWith(AndroidJUnit4::class)
class GenericNappletFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    @Test fun installTwoUnknownAppsSaveReopenAndSameFileUpdate() {
        val context=i.targetContext; check(BuildConfig.DEBUG)
        if((context.applicationContext as AmbulandoApplication).activeModel==null) {
            val sample=AppEnvironment.Sample(context).baseDir; check(sample.parentFile==context.cacheDir); check(!sample.exists() || sample.deleteRecursively())
        }
        val intent=Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window; scenario.onActivity { window=it.window }
            val application=context.applicationContext as AmbulandoApplication
            compose.waitUntil(20000) { application.activeModel?.workspaces?.any { it.id==SampleIds.STUDIO }==true }
            val model=application.activeModel!!
            captureOnFailure(compose,model,"generic-apps") {
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id==SampleIds.STUDIO }) }
            compose.waitUntil(20000) { model.channels.any { it.id==SampleIds.GENERAL } && model.activeEntry?.runtime?.writeActivity?.isOpen==true }
            val c=model.connection!!; val w=model.workspace!!; val api=model.fileNappletAPI(c)
            val channel=runBlocking { api.createDailyTracker(w.id,w.appNpub,c.signerNpub,"<h1>Fixture channel bootstrap</h1>").channel }
            suspend fun create(name: String,bytes: ByteArray,mime: String,metadata: JsonObject): FileRecord {
                val objectId=api.store(w.id,w.appNpub,bytes,mime,name,null) { }
                return FileRecord.parse(Json.parseToJsonElement(api.client.request(method="POST",path=TowerAPI.base(w.id)+"/channels/${channel.id}/files",appNpub=w.appNpub,jsonBody=jsonBody(buildJsonObject { put("storage_object_id",objectId); put("display_name",name); put("metadata",metadata) })).toString(Charsets.UTF_8)).jsonObject.getValue("file").jsonObject)
            }
            data class Bundle(val file: FileRecord,val data: FileRecord,val html: String,val id: String)
            fun newBundle(): Bundle=runBlocking {
                val id="unknown-"+UUID.randomUUID(); val binding="b"+UUID.randomUUID().toString().replace("-", "").take(20)
                val data=create("Data.json","{}".toByteArray(),"application/json",JsonObject(emptyMap()))
                val manifest=buildJsonObject { put("version",2); put("app_id",id); put("title",id); put("icon","🌱"); put("bindings",buildJsonObject { put(binding,buildJsonObject { put("capability","private-json-file"); put("version",1); put("operations",JsonArray(listOf(JsonPrimitive("read"),JsonPrimitive("save")))) }) }) }
                val meta=buildJsonObject { put("napplet",buildJsonObject { put("version",2); put("app_id",id); put("bindings",buildJsonObject { put(binding,buildJsonObject { put("file_id",data.id) }) }) }) }
                val html="""<script type="application/json" id="wingman-napplet-manifest">$manifest</script><h1 id="ui">Original</h1><p id="state">Loading</p><button id="save">Save zero and false</button><input id="draft"><script>
                    let base;let n=0;const send=(operation,extra={})=>parent.postMessage({type:'wingman-napplet-data',version:2,session:nappletSession,requestId:'r'+(++n),operation,binding:'$binding',...extra},'*');
                    addEventListener('message',e=>{if(e.source!==parent||e.data.type!=='wingman-napplet-result')return;const d=e.data;if(d.status==='ready'){base=d.baseVersionId;document.getElementById('state').textContent=JSON.stringify(d.value)}else document.getElementById('state').textContent=d.code});
                    document.getElementById('save').onclick=()=>send('save',{baseVersionId:base,value:{zero:0,answered:false}});
                    document.getElementById('draft').oninput=()=>parent.postMessage({type:'wingman-napplet-data',version:2,session:nappletSession,requestId:'d'+(++n),operation:'dirty',dirty:true},'*');send('read');
                </script>"""
                Bundle(create("Unknown.html",html.toByteArray(),"text/html",meta),data,html,id)
            }
            val a=newBundle(); val b=newBundle()
            fun open(bundle: Bundle) {
                compose.runOnIdle { model.section=AppSection.TOOLBOX }
                compose.onNodeWithTag("apps.addNapplet").performClick()
                compose.onNodeWithTag("apps.nappletLink").performTextReplacement(bundle.file.id)
                i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.onNodeWithText("Add and open").assertIsDisplayed().performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("apps.nappletLink")).fetchSemanticsNodes().isEmpty() }
            }
            fun reopen(bundle: Bundle) {
                compose.runOnIdle { model.section=AppSection.TOOLBOX }
                val row=hasTestTag("apps.generic") and hasText("🌱 "+bundle.id)
                compose.waitUntil(15000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(row).performClick()
            }
            var web: WebView?=null
            fun loaded(expected: String) { compose.waitUntil(25000) {
                if(compose.onAllNodes(hasTestTag("generic.host")).fetchSemanticsNodes().isEmpty()) false
                else { i.runOnMainSync { web=find(window.decorView) }; web!=null && page(web!!,"document.getElementById('state')?.textContent")==expected }
            } }
            open(a); compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("generic.trust")).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag("generic.trust").performClick(); loaded("{}")
            page(web!!,"document.getElementById('save').click()"); loaded("{\"answered\":false,\"zero\":0}")
            val saved=runBlocking { api.fileVersions(w.id,w.appNpub,a.data.id) }
            compose.onNodeWithTag("generic.close").performClick(); reopen(a); loaded("{\"answered\":false,\"zero\":0}"); compose.onAllNodesWithTag("generic.trust").assertCountEquals(0)
            // A healthy authority check must preserve the current document's draft.
            page(web!!,"document.getElementById('draft').value='keep';document.getElementById('draft').dispatchEvent(new Event('input'))")
            val nonce=page(web!!,"nappletSession"); Thread.sleep(31000)
            assertEquals("keep",page(web!!,"document.getElementById('draft').value")); assertEquals(nonce,page(web!!,"nappletSession"))
            page(web!!,"parent.postMessage({type:'wingman-napplet-data',version:2,session:nappletSession,requestId:'clean',operation:'dirty',dirty:false},'*')")
            val old=runBlocking { api.fileVersions(w.id,w.appNpub,a.file.id).currentVersionId!! }
            runBlocking { val objectId=api.store(w.id,w.appNpub,a.html.replace("Original","Updated").toByteArray(),"text/html","updated.html",null) { }; assertTrue(api.addFileVersion(w.id,w.appNpub,a.file.id,old,objectId,UUID.randomUUID().toString()) is VersionOutcome.Saved) }
            compose.onNodeWithTag("generic.update").performClick(); compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("generic.trust")).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag("generic.trust").performClick(); loaded("{\"answered\":false,\"zero\":0}")
            assertEquals("Updated",page(web!!,"document.getElementById('ui').textContent")); assertEquals(saved,runBlocking { api.fileVersions(w.id,w.appNpub,a.data.id) })
            compose.onNodeWithTag("generic.close").performClick(); reopen(a); loaded("{\"answered\":false,\"zero\":0}"); compose.onAllNodesWithTag("generic.trust").assertCountEquals(0)
            compose.onNodeWithTag("generic.close").performClick(); open(b); compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("generic.trust")).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag("generic.trust").performClick(); loaded("{}")
            page(web!!,"document.getElementById('save').click()"); loaded("{\"answered\":false,\"zero\":0}")
            compose.onNodeWithTag("generic.forget").performClick(); compose.onNodeWithTag("generic.trust").assertIsDisplayed()
            compose.onNodeWithTag("generic.trust").performClick(); loaded("{\"answered\":false,\"zero\":0}")
            val proof=buildJsonObject { put("firstApp",a.id); put("secondApp",b.id); put("firstFile",a.file.id); put("secondFile",b.file.id); put("firstData",a.data.id); put("secondData",b.data.id); put("versionCode",BuildConfig.VERSION_CODE); put("evidence","synthetic native flow"); put("twoAppsSaveReopenUpdateHistory",true) }
            val evidenceFile=context.getExternalFilesDir(null)!!.resolve("generic-unknown-apps.json")
            evidenceFile.writeText(JsonObject(proof+("backgroundResume" to JsonPrimitive("pending"))).toString())
            i.uiAutomation.takeScreenshot().let { bitmap -> context.getExternalFilesDir(null)!!.resolve("generic-loaded-before-resume.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            val priorNonce=page(web!!,"nappletSession"); scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); loaded("{\"answered\":false,\"zero\":0}"); assertNotEquals(priorNonce,page(web!!,"nappletSession"))
            evidenceFile.writeText(JsonObject(proof+("backgroundResume" to JsonPrimitive("passed"))).toString())
            i.uiAutomation.takeScreenshot().let { bitmap -> context.getExternalFilesDir(null)!!.resolve("generic-loaded.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            }
        }
    }
    private fun find(v: android.view.View): WebView? { if(v is WebView) return v; if(v is android.view.ViewGroup) for(n in 0 until v.childCount) find(v.getChildAt(n))?.let { return it }; return null }
    private fun page(w: WebView,js: String): String? {
        val done=CountDownLatch(1); var value: String?=null
        i.runOnMainSync { w.evaluateJavascript(js) { value=runCatching { org.json.JSONTokener(it).nextValue().toString() }.getOrNull(); done.countDown() } }
        check(done.await(5,TimeUnit.SECONDS)); return value
    }
}
