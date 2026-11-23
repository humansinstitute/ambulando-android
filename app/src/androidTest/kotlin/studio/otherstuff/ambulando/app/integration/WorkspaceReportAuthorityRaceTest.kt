package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual report bridge: one read waits for the original frame's authority gate; denial cancels it. */
@RunWith(AndroidJUnit4::class)
class WorkspaceReportAuthorityRaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun reportReadWaitsForDelayedAuthorityAndDenialNeverQueries() {
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG)
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            lateinit var window: android.view.Window
            scenario.onActivity { window = it.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.STUDIO && model.active?.canSend == true }
            val runtime = model.active!!; val w = runtime.workspace; val c = runtime.connection; val tower = model.sampleTower!!
            val api = model.fileNappletAPI(c)
            val html = context.assets.open("napplets/workspace-report-v1.html").bufferedReader().use { it.readText() }
            val code = runBlocking { api.createWorkspaceReport(w.id,w.appNpub,c.signerNpub,html).code }
            tower.workspaceReportFixture = { workspace, range -> SampleWorkspaceReport.value(workspace,range,w.raw.getValue("identity").jsonObject) }
            val queries = AtomicInteger(); val writes = AtomicInteger()
            tower.requestGate = { if (URI(it.url).path.endsWith("/workspace-report")) queries.incrementAndGet(); if (it.method != "GET") writes.incrementAndGet() }
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            compose.onNodeWithTag("apps.workspaceReport").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("report.trust").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("report.trust").performClick()
            fun web(): WebView? { var result: WebView? = null; instrumentation.runOnMainSync { result = find(window.decorView) }; return result }
            fun loaded() = compose.waitUntil(20_000) { web()?.let { page(it,"document.getElementById('report')?.hidden") == "false" } == true }
            loaded(); assertEquals(1,queries.get()); assertEquals(0,writes.get())
            var original = web()!!; var nonce = page(original,"nappletSession")
            for (mode in listOf("allow","replace","deny")) {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val held = AtomicBoolean()
                tower.requestGate = { request ->
                    if (request.method != "GET") writes.incrementAndGet()
                    if (URI(request.url).path.endsWith("/workspace-report")) queries.incrementAndGet()
                    if (URI(request.url).path.endsWith("/files/${code.id}") && held.compareAndSet(false,true)) {
                        entered.complete(Unit); withContext(NonCancellable) { release.await(); returned.complete(Unit) }
                        if (mode == "deny") throw TowerError.Http(403,"forbidden",null)
                    }
                }
                runBlocking { runtime.store.writer.touch(setOf(ChangeKey(StoreTable.FILES,w.id))) }
                compose.waitUntil(20_000) { entered.isCompleted }
                val before = queries.get()
                assertEquals(nonce,page(original,"nappletSession"))
                page(original,"document.getElementById('refresh').click()")
                compose.waitUntil(5_000) { page(original,"document.getElementById('refresh').disabled") == "true" }
                assertEquals("no aggregate query while authority is held",before,queries.get())
                if (mode == "replace") {
                    compose.onNodeWithTag("report.close").performClick()
                    compose.waitUntil(20_000) { compose.onAllNodesWithTag("apps.workspaceReport").fetchSemanticsNodes().isNotEmpty() }
                    release.complete(Unit)
                    compose.waitUntil(20_000) { returned.isCompleted }
                    assertEquals("old-frame queued read never queries after close",before,queries.get())
                    tower.requestGate = { if (URI(it.url).path.endsWith("/workspace-report")) queries.incrementAndGet(); if (it.method != "GET") writes.incrementAndGet() }
                    compose.onNodeWithTag("apps.workspaceReport").performClick()
                    loaded()
                    original = web()!!
                    val next = page(original,"nappletSession")
                    assertNotEquals("reopen mounts a different frame",nonce,next)
                    nonce = next
                    assertEquals("only the new frame queries",before + 1,queries.get())
                } else {
                release.complete(Unit)
                if (mode == "deny") {
                    compose.waitUntil(20_000) { compose.onAllNodesWithTag("report.error").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals("denied queued read never reaches the query",before,queries.get())
                    compose.onAllNodesWithTag("report.host").assertCountEquals(0)
                } else {
                    loaded(); assertEquals("one queued read",before + 1,queries.get())
                    assertEquals(nonce,page(original,"nappletSession"))
                }
                }
                assertEquals(0,writes.get())
            }
            tower.requestGate = null
        }
    }
    private fun find(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun page(web: WebView, script: String): String? {
        val latch = CountDownLatch(1); var value: String? = null
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { raw -> value = raw?.let { org.json.JSONTokener(it).nextValue()?.toString() }; latch.countDown() } }
        assertTrue(latch.await(5,TimeUnit.SECONDS)); return value
    }
}
