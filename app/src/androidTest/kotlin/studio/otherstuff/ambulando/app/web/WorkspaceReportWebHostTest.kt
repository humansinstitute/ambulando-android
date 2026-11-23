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
class WorkspaceReportWebHostTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sameBundleEnvelopeRunsWithNoNetworkStorageOrSigner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val received = CountDownLatch(1)
        lateinit var host: WorkspaceReportWebHost
        instrumentation.runOnMainSync {
            host = WorkspaceReportWebHost.create(instrumentation.targetContext, """<script>
              addEventListener('message',e=>{if(e.source===parent&&e.data.status==='ready') document.title='reply|'+e.data.value;});
              parent.postMessage({type:'wingman-napplet-data',version:1,session:window.nappletSession,requestId:'r1',operation:'read',binding:'report',range:'30d'},'*');
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
    @Test fun frozenPackagedReportRendersWhitelistedAggregate() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var host: WorkspaceReportWebHost
        var stopped=false
        instrumentation.runOnMainSync {
            val bytes=instrumentation.targetContext.assets.open("napplets/workspace-report-v1.html").use { it.readBytes() }
            assertEquals(16019,bytes.size)
            assertEquals("9c0fba639750c304902db0351e0bba4a3e50400e250585819e697e56817ec42d",studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(bytes))
            host=WorkspaceReportWebHost.create(instrumentation.targetContext,bytes.toString(Charsets.UTF_8),onMessage={ request -> host.view.post {
                host.deliver(buildJsonObject {
                    put("type","wingman-napplet-result"); put("version",1); put("session",host.session); put("requestId",request.getValue("requestId"))
                    put("status","ready"); put("canWrite",false); put("privacy","viewer-channels")
                    put("value",studio.otherstuff.ambulando.core.sample.SampleWorkspaceReport.value(studio.otherstuff.ambulando.core.sample.SampleIds.STUDIO,request.getValue("range").jsonPrimitive.content))
                })
            } },onStopped={ stopped=true }).getOrThrow()
        }
        compose.setContent { AndroidView(factory={host.view}) }
        val end=System.currentTimeMillis()+15000
        var result=""
        while(System.currentTimeMillis()<end && result!="42|false|false") {
            val done=CountDownLatch(1)
            instrumentation.runOnMainSync { host.view.evaluateJavascript("document.getElementById('messages')?.textContent+'|'+document.getElementById('report')?.hidden+'|'+(document.documentElement.scrollWidth>innerWidth)") { result=org.json.JSONTokener(it).nextValue().toString(); done.countDown() } }
            assertTrue(done.await(5,TimeUnit.SECONDS)); Thread.sleep(50)
        }
        assertFalse("Sandbox stopped",stopped)
        assertEquals("42|false|false",result)
        val uuidDone=CountDownLatch(1); var ids=""
        instrumentation.runOnMainSync { host.view.evaluateJavascript("crypto.randomUUID()+'|'+crypto.randomUUID()") { ids=org.json.JSONTokener(it).nextValue().toString(); uuidDone.countDown() } }
        assertTrue(uuidDone.await(5,TimeUnit.SECONDS))
        val pair=ids.split('|'); assertEquals(2,pair.size); assertNotEquals(pair[0],pair[1])
        pair.forEach { assertTrue(it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) }
        instrumentation.runOnMainSync { host.destroy() }
    }
    @Test fun envelopeRejectsReplayNonceRangeWritesAndSubframe() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val received=java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        lateinit var host: WorkspaceReportWebHost
        instrumentation.runOnMainSync {
            host=WorkspaceReportWebHost.create(instrumentation.targetContext,"""<script>
                const base={type:'wingman-napplet-data',version:1,session:nappletSession,requestId:'one',operation:'read',binding:'report',range:'30d'};
                parent.postMessage(base,'*'); parent.postMessage(base,'*');
                parent.postMessage({...base,requestId:'save',operation:'save'},'*');
                parent.postMessage({...base,requestId:'nonce',session:'wrong'},'*');
                parent.postMessage({...base,requestId:'range',range:'custom'},'*');
                parent.postMessage({...base,requestId:'url',url:'https://example.com'},'*');
                const f=document.createElement('iframe');document.documentElement.append(f);
                f.contentWindow.postMessage(base,'*');
                setTimeout(()=>parent.postMessage({...base,requestId:'two',range:'all'},'*'),200);
            </script>""",onMessage={ received.add(it) },onStopped={}).getOrThrow()
        }
        val end=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<end && received.size<2) Thread.sleep(50)
        assertEquals(listOf("one","two"),received.map { it.getValue("requestId").jsonPrimitive.content })
        instrumentation.runOnMainSync { host.destroy() }
    }
}
