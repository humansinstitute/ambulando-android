package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Post-build IDs and common web cartridge bytes, native Apps/consent/WebView/Sync/projection.
 * Signed synthetic Tower only. This is not live API, release-certificate or store-upgrade proof.
 */
@RunWith(AndroidJUnit4::class)
class NappletRuntimeFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Test fun twoPostbuildAppsSaveReopenAutomaticUpdateHistoryAndRemoval() {
        val context=instrumentation.targetContext;check(BuildConfig.DEBUG)
        val cachePhase=InstrumentationRegistry.getArguments().getString("runtimeCachePhase")
        if(cachePhase=="relaunch") { readCachedAfterRestart();return }
        val hostFile=java.io.File(context.applicationInfo.sourceDir)
        val hostBefore=studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(hostFile.readBytes())
        if((context.applicationContext as AmbulandoApplication).activeModel==null) {
            val directory=AppEnvironment.Sample(context).baseDir;check(directory.parentFile==context.cacheDir);check(!directory.exists() || directory.deleteRecursively())
        }
        val template=instrumentation.context.assets.open("napplet-runtime-v3/cartridge.html").bufferedReader().use {it.readText()}
        val intent=Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {scenario ->
            val application=context.applicationContext as AmbulandoApplication
            compose.waitUntil(20000) {application.activeModel?.workspaces?.any {it.id==SampleIds.STUDIO}==true}
            val model=application.activeModel!!
            captureOnFailure(compose,model,"runtime-v3") {
                compose.runOnIdle {model.selectWorkspace(model.workspaces.first {it.id==SampleIds.STUDIO})}
                compose.waitUntil(20000) {model.workspace?.id==SampleIds.STUDIO && model.channels.any {it.id==SampleIds.GENERAL} && model.activeEntry?.runtime?.writeActivity?.isOpen==true}
                val c=model.connection!!;val w=model.workspace!!;val api=model.fileNappletAPI(c)
                check(model.environment.isSample)
                val fixture=NappletRuntimeFixture(w.id,w.appNpub);model.sampleTower!!.nappletRuntimeFixture=fixture
                val channel=runBlocking {api.createDailyTracker(w.id,w.appNpub,c.signerNpub,"<h1>Fixture bootstrap</h1>").channel}
                data class Cartridge(val id: String,val html: String,val file: FileRecord)
                fun cartridge(): Cartridge=runBlocking {
                    val id="unknown-"+UUID.randomUUID();val html=template.replace("field-notes",id)
                    val stored=api.store(w.id,w.appNpub,html.toByteArray(),"text/html","Runtime.html",null){}
                    val file=FileRecord.parse(Json.parseToJsonElement(api.client.request(method="POST",path=TowerAPI.base(w.id)+"/channels/${channel.id}/files",appNpub=w.appNpub,jsonBody=jsonBody(buildJsonObject {put("storage_object_id",stored);put("display_name","Runtime.html");put("metadata",JsonObject(emptyMap()))})).toString(Charsets.UTF_8)).jsonObject.getValue("file").jsonObject)
                    val version=api.genericNappletHead(w.id,w.appNpub,file.id).currentVersionId!!
                    fixture.registerSource(html,file.id,version);Cartridge(id,html,file)
                }
                val a=cartridge();val b=cartridge()
                fun install(app: Cartridge) {
                    compose.runOnIdle {model.section=AppSection.TOOLBOX}
                    compose.onNodeWithTag("runtime.add").performClick();compose.onNodeWithTag("runtime.file").performTextReplacement(app.file.id)
                    instrumentation.runOnMainSync {
                        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                            androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                        }
                    }
                    compose.onNodeWithText("Install and review").performClick()
                    compose.waitUntil(20000) {compose.onAllNodesWithTag("runtime.trust").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithTag("runtime.trust").performClick()
                }
                fun reopen(app: Cartridge) {
                    compose.waitUntil(20000) {compose.onAllNodesWithTag("runtime.app.${app.id}").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithTag("runtime.app.${app.id}").performClick()
                }
                fun web(): WebView? {var result: WebView?=null;instrumentation.runOnMainSync {result=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {find(it)}};return result}
                fun loaded(text: String?=null) {
                    compose.waitUntil(25000) {
                        val view=web()
                        view!=null && compose.onAllNodesWithTag("runtime.host").fetchSemanticsNodes().isNotEmpty() &&
                            page(view,"document.querySelector('#save')?.disabled")=="false" && (text==null || page(view,"document.querySelector('textarea')?.value")==text)
                    }
                }
                fun save(text: String) {val view=web()!!;page(view,"document.querySelector('textarea').value=${JsonPrimitive(text)};document.querySelector('textarea').dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#save').click()");loaded(text)}
                install(a);loaded("");save("First saved note")
                val runtimeAPI=NappletRuntimeAPI(api.client,w.id,w.appNpub)
                fun collection(id: String)=runBlocking {runtimeAPI.collections(id).getValue("collections").jsonArray.single().jsonObject}
                val ac=collection(a.id);val aid=NappletRuntimeProtocol.text(ac,"id")
                val before=fixture.history(aid,"note");assertEquals(1,before.size)
                compose.onNodeWithTag("runtime.close").performClick()
                val release=CompletableDeferred<Unit>(); model.sampleTower!!.requestGate={release.await()}
                val cachedStart=android.os.SystemClock.elapsedRealtime()
                reopen(a);loaded("First saved note");compose.onAllNodesWithTag("runtime.trust").assertCountEquals(0)
                val cachedMillis=android.os.SystemClock.elapsedRealtime()-cachedStart
                assertFalse(release.isCompleted)
                context.getExternalFilesDir(null)!!.resolve("cached-runtime-timing.json").writeText(buildJsonObject {put("evidence","synthetic native v3 code and collection data");put("usableMillis",cachedMillis);put("network","all requests gated");put("versionCode",BuildConfig.VERSION_CODE)}.toString())
                model.sampleTower!!.requestGate=null;release.complete(Unit)
                if(cachePhase=="warm") {
                    context.getExternalFilesDir(null)!!.resolve("cached-runtime-id.json").writeText(buildJsonObject {put("id",a.id)}.toString())
                    return@captureOnFailure
                }
                val originalNonce=page(web()!!,"nappletSession")
                page(web()!!,"document.querySelector('textarea').value='Unsaved draft';document.querySelector('textarea').dispatchEvent(new Event('input',{bubbles:true}))")
                fixture.publish(a.html.replace("1.0.0","1.0.1"),c.signerNpub)
                compose.onNodeWithText("Check for update").performClick()
                compose.waitUntil(15000) {compose.onAllNodesWithText("An app update is ready. Save or discard your unsaved changes before loading it.").fetchSemanticsNodes().isNotEmpty()}
                assertEquals(originalNonce,page(web()!!,"nappletSession"));assertEquals("Unsaved draft",page(web()!!,"document.querySelector('textarea').value"))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                loaded("Unsaved draft");assertEquals(originalNonce,page(web()!!,"nappletSession"))
                save("Unsaved draft")
                compose.waitUntil(25000) {web()?.let {page(it,"document.querySelector('p')?.textContent")=="Bundle 1.0.1"}==true}
                loaded("Unsaved draft");assertNotEquals(originalNonce,page(web()!!,"nappletSession"));compose.onAllNodesWithTag("runtime.trust").assertCountEquals(0)
                val after=fixture.history(aid,"note");assertEquals(2,after.size);assertEquals(before.single(),after.first())
                compose.onNodeWithTag("runtime.close").performClick();install(b);loaded("");save("Second independent note")
                val bc=collection(b.id);val bid=NappletRuntimeProtocol.text(bc,"id");assertNotEquals(aid,bid);assertEquals(after,fixture.history(aid,"note"))
                compose.onNodeWithTag("runtime.forget").performClick();compose.onNodeWithTag("runtime.trust").assertIsDisplayed();compose.onNodeWithTag("runtime.trust").performClick();loaded("Second independent note")
                compose.onNodeWithTag("runtime.close").performClick();reopen(a);loaded("Unsaved draft")
                compose.onNodeWithTag("runtime.access").performClick();compose.onNodeWithTag("runtime.removeKeep").performScrollTo().performClick();compose.onNodeWithTag("runtime.confirmRemove").performClick()
                compose.waitUntil(15000) {compose.onAllNodesWithTag("runtime.close").fetchSemanticsNodes().isEmpty()}
                assertEquals(after,fixture.history(aid,"note"))
                install(a);loaded("Unsaved draft");assertEquals(after,fixture.history(aid,"note"));assertEquals(aid,NappletRuntimeProtocol.text(collection(a.id),"id"))
                instrumentation.uiAutomation.takeScreenshot().let {bitmap -> context.getExternalFilesDir(null)!!.resolve("napplet-runtime-v3-retained.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
                compose.onNodeWithTag("runtime.close").performClick();reopen(b);loaded("Second independent note")
                compose.onNodeWithTag("runtime.access").performClick();compose.onNodeWithTag("runtime.removeDelete").performScrollTo().performClick()
                compose.onNodeWithTag("runtime.deleteId").performTextInput(b.id);instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("runtime.confirmRemove").performClick()
                compose.waitUntil(15000) {compose.onAllNodesWithTag("runtime.close").fetchSemanticsNodes().isEmpty()};assertTrue(fixture.history(bid,"note").isEmpty())
                reopen(a);loaded("Unsaved draft")
                // Revocation destroys already-rendered private state, not just future writes.
                fixture.deny=true;compose.onNodeWithText("Check for update").performClick()
                compose.waitUntil(15000) {compose.onAllNodesWithTag("runtime.host").fetchSemanticsNodes().isEmpty()};assertNull(web())
                fixture.deny=false;compose.onNodeWithTag("runtime.close").performClick();reopen(a);loaded("Unsaved draft")
                compose.runOnIdle {model.selectWorkspace(model.workspaces.first {it.id==SampleIds.LAB})}
                compose.waitUntil(15000) {compose.onAllNodesWithTag("runtime.host").fetchSemanticsNodes().isEmpty()};assertNull(web())
                val hostAfter=studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(hostFile.readBytes());assertEquals(hostBefore,hostAfter)
                val evidence=buildJsonObject {put("host_apk_sha256_before",hostBefore);put("host_apk_sha256_after",hostAfter);put("evidence","synthetic Android native v3 flow; not live/store proof");put("firstApp",a.id);put("secondApp",b.id);put("firstCollection",aid);put("versionCode",BuildConfig.VERSION_CODE);put("twoPostBuildApps",true);put("dirtyUpdateDeferred",true);put("backgroundDraftPreserved",true);put("sameGrantCodeUpdateWithoutConsent",true);put("historyRetained",true);put("keepReinstall",true);put("deleteDataAndHistory",true);put("revocationClearsFrame",true);put("workspaceSwitchClearsFrame",true)}
                context.getExternalFilesDir(null)!!.resolve("napplet-runtime-v3.json").writeText(evidence.toString())
            }
        }
    }
    private fun readCachedAfterRestart() {
        val context=instrumentation.targetContext
        val id=Json.parseToJsonElement(context.getExternalFilesDir(null)!!.resolve("cached-runtime-id.json").readText()).jsonObject.getValue("id").jsonPrimitive.content
        val application=context.applicationContext as AmbulandoApplication
        lateinit var model: AppModel
        instrumentation.runOnMainSync {model=application.model(AppEnvironment.Sample(context));model.sampleTower!!.offline=true}
        val started=android.os.SystemClock.elapsedRealtime()
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            compose.waitUntil(30000) {model.workspaces.any {it.id==SampleIds.STUDIO}}
            compose.runOnIdle {model.selectWorkspace(model.workspaces.first {it.id==SampleIds.STUDIO})}
            compose.waitUntil(30000) {model.workspace?.id==SampleIds.STUDIO && model.active?.workspace?.id==SampleIds.STUDIO}
            compose.runOnIdle {model.section=AppSection.TOOLBOX}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("runtime.app.$id").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("runtime.app.$id").performClick()
            var web: WebView?=null
            compose.waitUntil(15000) {
                instrumentation.runOnMainSync {web=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {find(it)}}
                web!=null && page(web!!,"document.querySelector('textarea')?.value")=="First saved note"
            }
            compose.onAllNodesWithTag("runtime.trust").assertCountEquals(0)
            context.getExternalFilesDir(null)!!.resolve("cached-runtime-relaunch.json").writeText(buildJsonObject {
                put("evidence","synthetic native v3 after actual process stop");put("launchToUsableMillis",android.os.SystemClock.elapsedRealtime()-started)
                put("network","offline before activity launch");put("versionCode",BuildConfig.VERSION_CODE)
            }.toString())
        }
    }
    private fun find(view: android.view.View): WebView? {if(view is WebView)return view.takeIf {it.isAttachedToWindow && it.isShown && it.visibility==android.view.View.VISIBLE};if(view is android.view.ViewGroup)for(n in 0 until view.childCount)find(view.getChildAt(n))?.let{return it};return null}
    private fun page(web: WebView,script: String): String? {
        val done=CountDownLatch(1);var result: String?=null
        instrumentation.runOnMainSync {
            if(!web.isAttachedToWindow || !web.isShown) done.countDown()
            else web.evaluateJavascript(script){result=runCatching {org.json.JSONTokener(it).nextValue().toString()}.getOrNull();done.countDown()}
        }
        if(!done.await(5,TimeUnit.SECONDS)) return null // A replaced frame has no evaluation callback; polling must inspect the new visible frame.
        return result
    }
}
