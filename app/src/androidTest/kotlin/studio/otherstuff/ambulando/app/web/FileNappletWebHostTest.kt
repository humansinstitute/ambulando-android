package studio.otherstuff.ambulando.app.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import studio.otherstuff.ambulando.core.surfaces.FileNapplet
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FileNappletWebHostTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sameBundleEnvelopeRunsWithNoNetworkStorageOrSigner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val received = CountDownLatch(1)
        lateinit var host: FileNappletWebHost
        instrumentation.runOnMainSync {
            host = FileNappletWebHost.create(instrumentation.targetContext, """<script>
              addEventListener('message',e=>{if(e.source===parent&&e.data.status==='ready') document.title='reply|'+e.data.value;});
              parent.postMessage({type:'wingman-napplet-data',version:1,session:window.nappletSession,requestId:'r1',operation:'read',binding:'tracker'},'*');
              fetch('https://example.com').then(()=>document.title='network-breach').catch(()=>{});
              if(typeof window.nostr!=='undefined') document.title='signer-breach';
            </script>""", onMessage = { received.countDown() }, onStopped = {}).getOrThrow()
        }
        assertTrue("Native envelope received", received.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { host.deliver(buildJsonObject { put("status", "ready"); put("value", "ok") }) }
        val end = System.currentTimeMillis() + 15000
        var title = ""
        while (System.currentTimeMillis() < end && title != "reply|ok") {
            instrumentation.runOnMainSync { title = host.view.title.orEmpty() }; Thread.sleep(100)
        }
        assertEquals("reply|ok", title)
        instrumentation.runOnMainSync {
            assertFalse(host.view.settings.domStorageEnabled)
            assertTrue(host.view.settings.blockNetworkLoads)
            host.destroy()
        }
    }
    @Test fun packagedTrackerEditsFalseZeroUnansweredAndHistoryOnPhoneViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val a = "10000000-0000-4000-8000-000000000001"
        val b = "10000000-0000-4000-8000-000000000002"
        val value = Json.parseToJsonElement("""{"format":"daily-tracker-v1","metrics":[{"id":"$a","label":"Boolean","type":"boolean","unit":"","archived":false},{"id":"$b","label":"Number","type":"number","unit":"","archived":false}],"days":{}}""").jsonObject
        var savedValue: JsonObject? = null
        lateinit var host: FileNappletWebHost
        val started = android.os.SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            val html = instrumentation.targetContext.assets.open("napplets/daily-tracker-v1.html").bufferedReader().use { it.readText() }
            host = FileNappletWebHost.create(instrumentation.targetContext, html, onMessage = { request ->
                when (request["operation"]?.jsonPrimitive?.content) {
                    "read" -> host.view.post {
                        host.deliver(buildJsonObject {
                            put("type", "wingman-napplet-result"); put("version", 1); put("session", host.session); put("requestId", request.getValue("requestId")); put("status", "ready"); put("baseVersionId", a); put("value", value); put("canWrite", true); put("privacy", "self-channel")
                        })
                    }
                    "dirty" -> if (request["dirty"] == JsonPrimitive(false)) ready.countDown()
                    "save" -> { savedValue = FileNapplet.model(request.getValue("value").jsonObject); saved.countDown() }
                }
            }, onStopped = {}).getOrThrow()
        }
        compose.setContent { AndroidView(factory = { host.view }) }
        assertTrue(ready.await(15, TimeUnit.SECONDS))
        android.util.Log.i("FileNappletPerf", "synthetic packaged tracker ready ms=" + (android.os.SystemClock.elapsedRealtime() - started))
        instrumentation.runOnMainSync {
            host.view.evaluateJavascript("document.getElementById('date').value='2024-12-31';document.getElementById('date').dispatchEvent(new Event('change'));Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').click();const n=document.querySelector('input[type=number]');n.value='0';n.dispatchEvent(new Event('input'));document.getElementById('save').click()", null)
        }
        assertTrue(saved.await(15, TimeUnit.SECONDS))
        val day = savedValue!!.getValue("days").jsonObject.getValue("2024-12-31").jsonObject
        assertEquals(JsonPrimitive(false), day[a]); assertEquals(JsonPrimitive(0), day[b])
        val queried = CountDownLatch(1); var capabilities = ""
        instrumentation.runOnMainSync { host.view.evaluateJavascript("JSON.stringify({uuid:typeof DailyTrackerModel.uuid,width:innerWidth,overflow:document.documentElement.scrollWidth>innerWidth,nostr:typeof window.nostr})") { capabilities = it; queried.countDown() } }
        assertTrue(queried.await(5, TimeUnit.SECONDS))
        android.util.Log.i("FileNappletPerf", capabilities)
        assertTrue("UUID needed for adding metrics: " + capabilities, capabilities.contains("function"))
        assertTrue(capabilities.contains("undefined"))
        instrumentation.runOnMainSync { host.destroy() }
    }

}
