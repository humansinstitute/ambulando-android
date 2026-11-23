package studio.otherstuff.ambulando.app.web

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.surfaces.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared real cartridge in the native opaque WebView; synthetic aggregates, never live storage evidence. */
@RunWith(AndroidJUnit4::class)
class WorkspaceStorageWebHostTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private fun page(w: WebView,js: String): String {
        val done=CountDownLatch(1);var result=""
        i.runOnMainSync {w.evaluateJavascript(js) {result=org.json.JSONTokener(it).nextValue().toString();done.countDown()}}
        check(done.await(5,TimeUnit.SECONDS));return result
    }
    private fun awaitPage(w: WebView,js: String,expected: String) {
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end) {if(page(w,js)==expected)return;Thread.sleep(50)}
        assertEquals(expected,page(w,js))
    }
    @Test fun sharedCartridgeMetricsUnavailableDeniedAndCodeOnlyUpdate() {
        val assets=i.context.assets
        val fixture=GenericJson.decode(assets.open("napplets/workspace-report-storage-v1.json").use {it.readBytes()})
        val original=assets.open("napplets/workspace-storage-cartridge.html").bufferedReader().use {it.readText()}
        var html=original
        val value=fixture.getValue("value").jsonObject
        val binding=GenericNapplet.parse(html,fixture.getValue("metadata").jsonObject)!!
        assertTrue(binding.storage)
        repeat(2) { iteration ->
            val requests=CopyOnWriteArrayList<JsonObject>()
            lateinit var host: GenericNappletWebHost
            i.runOnMainSync {host=GenericNappletWebHost.create(i.targetContext,html,binding,{requests+=it;true},{}).getOrThrow()}
            try {
                val end=System.currentTimeMillis()+20000
                while(requests.isEmpty() && System.currentTimeMillis()<end) Thread.sleep(50)
                assertTrue(requests.isNotEmpty())
                // The unchanged cartridge chooses 30d; switch to the shared all-time golden snapshot.
                val first=requests.last()
                i.runOnMainSync {host.deliver(GenericNapplet.error(host.session,GenericNapplet.text(first,"requestId"),"unavailable"))}
                awaitPage(host.view,"document.getElementById('range').disabled.toString()","false")
                page(host.view,"document.getElementById('range').value='all';document.getElementById('range').dispatchEvent(new Event('change'))")
                while(requests.size<2 && System.currentTimeMillis()<end) Thread.sleep(50)
                val request=requests.last();assertEquals("all",GenericNapplet.text(request,"range"))
                var delivered=value
                if(iteration==1) {
                    val storage=value.getValue("storage").jsonObject
                    val metric=GenericJson.decode("""{"id":"future_metric","value":123,"unit":"bytes","method":"future-method","status":"estimated"}""")
                    val groups=storage.getValue("groups").jsonArray.toMutableList()
                    groups+=buildJsonObject {put("id","future_group");put("metrics",buildJsonArray {add(metric)})}
                    delivered=JsonObject(value+("storage" to JsonObject(storage+("groups" to JsonArray(groups)))))
                }
                WorkspaceStorage.model(delivered,WorkspaceReport.text(value,"workspace_id"),"all")
                val response=buildJsonObject {
                    put("type","wingman-napplet-result");put("version",2);put("session",host.session);put("requestId",request.getValue("requestId"));put("status","ready");put("canWrite",false);put("privacy","viewer-channels");put("value",delivered)
                }
                i.runOnMainSync {host.deliver(response)}
                awaitPage(host.view,"document.getElementById('storage').hidden.toString()","false")
                assertTrue(page(host.view,"document.getElementById('storage-groups').textContent").contains("18,668"))
                assertTrue(page(host.view,"document.getElementById('storage-groups').textContent").contains("40"))
                assertTrue(page(host.view,"document.getElementById('storage-groups').textContent").contains("Unavailable"))
                if(iteration==1) {assertTrue(page(host.view,"document.getElementById('storage-groups').textContent").contains("123"));assertEquals("Updated cartridge",page(host.view,"document.title"))}
                assertEquals("undefined|undefined",page(host.view,"typeof window.nostr+'|'+typeof window.Android"))
                i.runOnMainSync {assertTrue(host.view.settings.blockNetworkLoads);assertFalse(host.view.settings.domStorageEnabled);assertFalse(host.view.settings.allowFileAccess)}
                i.runOnMainSync {host.deliver(GenericNapplet.error(host.session,GenericNapplet.text(request,"requestId"),"forbidden"))}
                awaitPage(host.view,"document.getElementById('storage').hidden.toString()","true")
                assertEquals("",page(host.view,"document.getElementById('storage-groups').textContent"))
                assertTrue(page(host.view,"document.getElementById('status').textContent").contains("management permission"))
            } finally {i.runOnMainSync {host.destroy()}}
            html=original.replace("<title>Workspace Report</title>","<title>Updated cartridge</title>")
        }
    }
}
