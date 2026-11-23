package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.webkit.WebView
import kotlinx.serialization.json.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*

/** Full native UI with signed in-process synthetic NStack. No member storage. */
@RunWith(AndroidJUnit4::class)
class WorkspaceReportFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun freshSyntheticFixture() {
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG)
        // The application intentionally keeps its model across Activity recreation. Never delete a live fixture.
        if ((context.applicationContext as AmbulandoApplication).activeModel != null) return
        val sample = AppEnvironment.Sample(context).baseDir
        check(sample.parentFile == context.cacheDir)
        check(!sample.exists() || sample.deleteRecursively())
    }
    @Test fun appsPrivateSetupRememberedReadonlyFixtureAndUnavailable() {
        val context=instrumentation.targetContext
        val intent=Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window
            scenario.onActivity { window=it.window }
            val app=context.applicationContext as AmbulandoApplication
            compose.waitUntil(20000) { app.activeModel?.workspaces?.any { it.id==SampleIds.STUDIO }==true }
            val model=app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id==SampleIds.STUDIO }) }
            compose.waitUntil(20000) { model.channels.any { it.id==SampleIds.GENERAL } && model.activeEntry?.runtime?.writeActivity?.isOpen == true }
            val tower=model.sampleTower!!
            var reads=0; var writes=0
            tower.requestGate={ request ->
                if(request.url.contains("/workspace-report")) reads++
                if(request.method!="GET") writes++
            }
            compose.runOnIdle { model.section=AppSection.TOOLBOX }
            compose.onNodeWithTag("apps.dailyMetrics").assertIsDisplayed()
            compose.onNodeWithTag("apps.workspaceReport").performClick()
            compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("apps.setupReport")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("apps.setupReport").performClick()
            compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("report.trust")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0,reads)
            val c=model.connection!!; val w=model.workspace!!; val api=model.fileNappletAPI(c)
            val files=kotlinx.coroutines.runBlocking { api.workspaceReportInstances(w.id,w.appNpub,c.signerNpub) }
            assertEquals(1,files.size)
            val code=files.single()
            val codeVersions=kotlinx.coroutines.runBlocking { api.fileVersions(w.id,w.appNpub,code.id) }
            assertEquals(1,codeVersions.versions.size)
            writes=0
            compose.onNodeWithTag("report.trust").performClick()
            var web: WebView?=null
            fun loaded() { compose.waitUntil(20000) { instrumentation.runOnMainSync { web=find(window.decorView) }; web!=null && runCatching { page(web!!,"document.getElementById('report')?.hidden") }.getOrNull()=="false" } }
            compose.waitUntil(20000) { instrumentation.runOnMainSync { web=find(window.decorView) }; web!=null && page(web!!,"document.getElementById('status')?.textContent")?.contains("unavailable")==true }
            assertEquals("true",page(web!!,"document.getElementById('report').hidden"))
            val identity=w.raw.getValue("identity").jsonObject
            tower.workspaceReportFixture={ workspace,range -> studio.otherstuff.ambulando.core.sample.SampleWorkspaceReport.value(workspace,range,identity) }
            page(web!!,"document.getElementById('refresh').click()")
            loaded()
            assertEquals("42",page(web!!,"document.getElementById('messages').textContent"))
            assertEquals("9",page(web!!,"document.getElementById('conversations').textContent"))
            assertEquals("12",page(web!!,"document.getElementById('tasks').textContent"))
            assertEquals("7",page(web!!,"document.getElementById('open').textContent"))
            assertEquals("2",page(web!!,"document.getElementById('archived').textContent"))
            assertEquals("false",page(web!!,"document.documentElement.scrollWidth>innerWidth"))
            capture("workspace-report-loaded.png",web!!,context)
            // Only range crosses the read-only envelope; no save/write primitive is admitted.
            val before=reads
            page(web!!,"parent.postMessage({type:'wingman-napplet-data',version:1,session:nappletSession,requestId:'unsafe',operation:'save',binding:'report',range:'30d'},'*')")
            Thread.sleep(150); assertEquals(before,reads)
            page(web!!,"document.getElementById('range').value='7d';document.getElementById('range').dispatchEvent(new Event('change'))")
            loaded(); assertEquals("7d",page(web!!,"document.getElementById('range').value"))
            compose.onNodeWithTag("report.close").performClick()
            compose.onNodeWithTag("apps.workspaceReport").performClick()
            loaded(); compose.onAllNodesWithTag("report.trust").assertCountEquals(0)
            assertEquals(1,kotlinx.coroutines.runBlocking { api.workspaceReportInstances(w.id,w.appNpub,c.signerNpub).size })
            // Background destroys aggregates; foreground reconstructs only after fresh authority.
            val nonce=page(web!!,"nappletSession")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            loaded(); assertNotEquals(nonce,page(web!!,"nappletSession"))
            tower.workspaceReportFixture={ workspace,range -> JsonObject(studio.otherstuff.ambulando.core.sample.SampleWorkspaceReport.value(workspace,range,identity)+("complete" to JsonPrimitive(false))) }
            page(web!!,"document.getElementById('refresh').click()")
            compose.waitUntil(20000) { page(web!!,"document.getElementById('status').textContent")?.contains("unavailable")==true }
            assertEquals("true",page(web!!,"document.getElementById('report').hidden"))
            tower.workspaceReportFixture={ workspace,range -> studio.otherstuff.ambulando.core.sample.SampleWorkspaceReport.value(workspace,range,identity) }
            page(web!!,"document.getElementById('refresh').click()")
            loaded()
            compose.onNodeWithTag("report.forget").performClick()
            compose.onNodeWithTag("report.trust").assertIsDisplayed()
            assertEquals(0,writes)
            assertEquals(codeVersions,kotlinx.coroutines.runBlocking { api.fileVersions(w.id,w.appNpub,code.id) })
            compose.onNodeWithTag("report.trust").performClick()
            loaded()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            tower.revokedWorkspaces=setOf(SampleIds.STUDIO)
            try {
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("report.host")).fetchSemanticsNodes().isEmpty() }
                instrumentation.runOnMainSync { assertNull(find(window.decorView)) }
            } finally { tower.revokedWorkspaces=emptySet() }
            tower.requestGate=null; tower.workspaceReportFixture=null
        }
    }
    private fun capture(name: String, web: WebView, context: android.content.Context) {
        val drawn = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { drawn.countDown() }
        }) }
        assertTrue(drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun find(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun page(web: WebView, script: String): String? {
        val latch = java.util.concurrent.CountDownLatch(1); var value: String? = null
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { raw -> value = raw?.let { org.json.JSONTokener(it).nextValue()?.toString() }; latch.countDown() } }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); return value
    }
}
