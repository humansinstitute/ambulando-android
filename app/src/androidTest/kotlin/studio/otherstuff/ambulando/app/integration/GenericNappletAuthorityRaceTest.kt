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
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real native owner/bridge with a deliberately delayed signed authority request. Synthetic storage only. */
@RunWith(AndroidJUnit4::class)
class GenericNappletAuthorityRaceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    @Test fun firstReadWaitsForHealthyAuthorityAndDenialDestroysQueuedRead() {
        val context=i.targetContext; check(BuildConfig.DEBUG)
        if((context.applicationContext as AmbulandoApplication).activeModel==null) {
            val dir=AppEnvironment.Sample(context).baseDir; check(dir.parentFile==context.cacheDir); check(!dir.exists() || dir.deleteRecursively())
        }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            lateinit var window: android.view.Window; scenario.onActivity { window=it.window }
            val application=context.applicationContext as AmbulandoApplication
            compose.waitUntil(20000) { application.activeModel?.workspaces?.any { it.id==SampleIds.STUDIO }==true }
            val model=application.activeModel!!
            captureOnFailure(compose,model,"generic-authority-race") {
                compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id==SampleIds.STUDIO }) }
                compose.waitUntil(20000) { model.channels.any { it.id==SampleIds.GENERAL } && model.activeEntry?.runtime?.writeActivity?.isOpen==true }
                val c=model.connection!!; val w=model.workspace!!; val api=model.fileNappletAPI(c); val tower=model.sampleTower!!
                val channel=runBlocking { api.createDailyTracker(w.id,w.appNpub,c.signerNpub,"<h1>Fixture channel</h1>").channel }
                fun create(name: String,source: String,type: String,meta: JsonObject): FileRecord=runBlocking {
                    val objectId=api.store(w.id,w.appNpub,source.toByteArray(),type,name,null) { }
                    FileRecord.parse(Json.parseToJsonElement(api.client.request(method="POST",path=TowerAPI.base(w.id)+"/channels/${channel.id}/files",appNpub=w.appNpub,jsonBody=jsonBody(buildJsonObject { put("storage_object_id",objectId); put("display_name",name); put("metadata",meta) })).toString(Charsets.UTF_8)).jsonObject.getValue("file").jsonObject)
                }
                val data=create("Race data.json","{}","application/json",JsonObject(emptyMap()))
                val base=runBlocking { api.genericNappletHead(w.id,w.appNpub,data.id).currentVersionId!! }
                val id="race-"+UUID.randomUUID()
                val manifest=buildJsonObject { put("version",2); put("app_id",id); put("title","Authority race"); put("icon","🌱"); put("bindings",buildJsonObject { put("records",buildJsonObject { put("capability","private-json-file"); put("version",1); put("operations",JsonArray(listOf(JsonPrimitive("read"),JsonPrimitive("save")))) }) }) }
                val meta=buildJsonObject { put("napplet",buildJsonObject { put("version",2); put("app_id",id); put("bindings",buildJsonObject { put("records",buildJsonObject { put("file_id",data.id) }) }) }) }
                val html="""<script type="application/json" id="wingman-napplet-manifest">$manifest</script><p id="state">waiting</p><script>
                    let count=0;window.answers=[];const send=(id,operation,extra={})=>parent.postMessage({type:'wingman-napplet-data',version:2,session:nappletSession,requestId:id,operation,binding:'records',...extra},'*');
                    window.requestRead=id=>send(id,'read');window.requestSave=id=>send(id,'save',{baseVersionId:'$base',value:{mustNotBeQueued:true}});
                    addEventListener('message',e=>{if(e.source!==parent||e.data.type!=='wingman-napplet-result')return;answers.push(e.data.requestId+'|'+e.data.status);if(e.data.status==='ready')document.getElementById('state').textContent=e.data.requestId+'|'+(++count)});
                </script>"""
                val code=create("Authority race.html",html,"text/html",meta)
                runBlocking { model.store.writer.upsertFiles(w.id,listOf(code)) }
                compose.runOnIdle { model.section=AppSection.TOOLBOX }
                compose.onNodeWithTag("apps.addNapplet").performClick()
                compose.onNodeWithText("Authority race.html").performClick()
                compose.onNodeWithText("Add and open").assertIsDisplayed().performClick()
                compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("generic.trust")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("generic.trust").performClick()
                var web: WebView?=null
                compose.waitUntil(15000) { i.runOnMainSync { web=find(window.decorView) }; web!=null && page(web!!,"typeof requestRead")=="function" }
                fun triggerFocusCheck() {
                    lateinit var dialog: android.app.Dialog
                    scenario.onActivity { activity ->
                        dialog=android.app.Dialog(activity).apply { setContentView(android.widget.TextView(activity).apply { text="Authority check" }); show() }
                    }
                    compose.waitUntil(5000) {
                        var focused=false; i.runOnMainSync { focused=dialog.window?.decorView?.hasWindowFocus()==true }; focused
                    }
                    i.runOnMainSync { dialog.dismiss() }
                }
                val dataReads=AtomicInteger(); val writes=AtomicInteger(); val entered=CountDownLatch(1); val release=CountDownLatch(1); val arm=AtomicBoolean(true)
                fun count(r: HttpRequest) {
                    val path=URI(r.url).path
                    if(r.method=="GET" && path.contains("/files/${data.id}/versions/") && path.endsWith("/object")) dataReads.incrementAndGet()
                    if(r.method=="POST" && path.endsWith("/files/${data.id}/versions")) writes.incrementAndGet()
                }
                try {
                    tower.requestGate={ r -> count(r); if(r.method=="GET" && URI(r.url).path==TowerAPI.base(w.id)+"/files/${code.id}" && arm.compareAndSet(true,false)) { entered.countDown(); check(release.await(15,TimeUnit.SECONDS)) } }
                    triggerFocusCheck()
                    assertTrue("Authority check entered",entered.await(5,TimeUnit.SECONDS))
                    page(web!!,"requestSave('blocked');requestRead('delayed');requestRead('second')")
                    compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("generic.close").assertIsNotEnabled() }.isSuccess }
                    release.countDown()
                    compose.waitUntil(15000) { page(web!!,"document.getElementById('state').textContent")=="delayed|1" }
                    assertEquals("delayed|ready",page(web!!,"answers.join(',')")); assertEquals(0,writes.get())
                    page(web!!,"requestRead('delayed')"); Thread.sleep(200); assertEquals("1",page(web!!,"answers.length"))
                    page(web!!,"requestRead('second')"); compose.waitUntil(15000) { page(web!!,"document.getElementById('state').textContent")=="second|2" }
                    page(web!!,"requestRead('blocked')"); compose.waitUntil(15000) { page(web!!,"document.getElementById('state').textContent")=="blocked|3" }
                    val before=dataReads.get(); val denyEntered=CountDownLatch(1); val denyRelease=CountDownLatch(1); val denyArm=AtomicBoolean(true)
                    try {
                        tower.requestGate={ r -> count(r); if(r.method=="GET" && URI(r.url).path==TowerAPI.base(w.id)+"/files/${code.id}" && denyArm.compareAndSet(true,false)) { denyEntered.countDown(); check(denyRelease.await(15,TimeUnit.SECONDS)) } }
                        triggerFocusCheck()
                        assertTrue(denyEntered.await(5,TimeUnit.SECONDS)); page(web!!,"requestRead('denied')")
                        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("generic.close").assertIsNotEnabled() }.isSuccess }
                        tower.revokedWorkspaces=setOf(w.id); denyRelease.countDown()
                        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("generic.host")).fetchSemanticsNodes().isEmpty() }
                        i.runOnMainSync { assertNull(find(window.decorView)) }
                        assertEquals(before,dataReads.get()); assertEquals(0,writes.get())
                    } finally { denyRelease.countDown(); tower.revokedWorkspaces=emptySet() }
                } finally { release.countDown(); tower.requestGate=null }
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
