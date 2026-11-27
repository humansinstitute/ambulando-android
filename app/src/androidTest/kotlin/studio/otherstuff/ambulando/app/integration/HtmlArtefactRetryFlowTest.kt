package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.surfaces.*
import java.net.URI

/** Exercises the mounted HTML reader's own failure (after metadata), not the outer File-loading fallback. */
@RunWith(AndroidJUnit4::class)
class HtmlArtefactRetryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val capture = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"catchup103-$name.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG,100,it) }
        capture.recycle()
    }

    @Test fun failedHtmlReadShowsUnavailableThenRetryLoadsTheSameRevision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.active?.canSend == true && model.workspace?.id == SampleIds.STUDIO }
            val tower = model.sampleTower!!
            val source = """<h1>Saved report</h1><img id="chart" data-asset="chart.png"><script>setInterval(()=>{const i=document.getElementById('chart');if(i.complete&&i.naturalWidth>0)document.title='image:'+i.naturalWidth+'x'+i.naturalHeight},50)</script>"""
            val fileId = tower.work.seedFile(SampleIds.GENERAL,SampleIds.SCOPE_AMBULANDO,"HTML retry.html",source.toByteArray(),"text/html")
            val bitmap = Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888)
            val random = java.util.Random(103)
            bitmap.setPixels(IntArray(768*768) { random.nextInt() or (0xff shl 24) },0,768,0,0,768,768)
            val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            bitmap.recycle()
            assertTrue("Use a real PNG large enough to exceed the source-only cache limit",image.size > 1024*1024)
            val imageId = tower.work.seedFile(SampleIds.GENERAL,SampleIds.SCOPE_AMBULANDO,"chart.png",image,"image/png")
            val w = model.workspace!!
            val api = model.fileNappletAPI(model.connection!!)
            // seedFile creates an ordinary object; explicitly establish immutable lineage before testing revisions.
            val initialFile = runBlocking { api.artefactFile(w.id,w.appNpub,fileId) }
            val initialImage = runBlocking { api.artefactFile(w.id,w.appNpub,imageId) }
            assertTrue(tower.work.externalFileVersion(fileId,requireNotNull(initialFile.storageObjectId)))
            assertTrue(tower.work.externalFileVersion(imageId,requireNotNull(initialImage.storageObjectId)))
            val file = runBlocking { api.artefactFile(w.id,w.appNpub,fileId) }
            val version = runBlocking { api.fileVersions(w.id,w.appNpub,fileId).currentVersionId!! }
            val imageVersion = runBlocking { api.fileVersions(w.id,w.appNpub,imageId).currentVersionId!! }
            lateinit var decorate: suspend (HttpRequest) -> HttpResponse?
            decorate = { request ->
                if (!URI(request.url).path.endsWith("/files/$fileId/html")) null else {
                    // Delegate through the ordinary sample route first: its NIP-98 and membership checks still run.
                    tower.nativeRelationalFixture = null
                    val response = try { tower.send(request) } finally { tower.nativeRelationalFixture = decorate }
                    check(response.status == 200)
                    val root = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
                    val content = root.getValue("content").jsonObject
                    val asset = buildJsonObject {
                        put("status","ready"); put("name","chart.png"); put("file_id",imageId); put("version_id",imageVersion)
                        put("content_type","image/png"); put("size_bytes",image.size); put("sha256",Sha256Hex.of(image))
                    }
                    HttpResponse(200,JsonObject(root+("content" to JsonObject(content+("assets" to JsonArray(listOf(asset)))))).toString().toByteArray())
                }
            }
            tower.nativeRelationalFixture = decorate
            val tab = ThreadArtefactTab("file",fileId,file.displayName)
            tower.requestGate = { request -> if (URI(request.url).path.endsWith("/files/$fileId/html")) throw IllegalArgumentException("Fixture HTML read failed") }
            val services = AppServices(null,{}, {}, {}, { _, _ -> },unlocked = true,sample = true)
            scenario.onActivity { activity -> activity.setContent { AmbulandoTheme {
                CompositionLocalProvider(LocalThreadArtefactTab provides tab,LocalAppServices provides services) { HtmlFileArtefact(model,file) }
            } } }
            try {
                compose.waitUntil(30_000) { tab.unavailable != null }
                assertEquals("Revision unavailable",tab.versionLabel)
                assertNull(tab.selectedRevision)
                screenshot("unavailable")
                compose.onNodeWithTag("artefacts.retry").assertIsDisplayed()
                tower.requestGate = null
                compose.onNodeWithTag("artefacts.retry").performClick()
                compose.waitUntil(30_000) { tab.unavailable == null && tab.versionLabel == "v1" && tab.selectedRevision == version }
                compose.onNodeWithTag("artefacts.retry").assertDoesNotExist()
                fun webView(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { webView(view.getChildAt(it)) } else null
                compose.waitUntil(30_000) {
                    var rendered = false
                    scenario.onActivity { rendered = webView(it.window.decorView)?.title == "image:768x768" }
                    rendered
                }
                compose.onNodeWithTag("naplet.html").assertIsDisplayed()
                screenshot("private-image-ready")
                val reference = runBlocking { requireNotNull(tab.prepare).invoke() }
                assertEquals(version,reference.revision)
                val cached = runBlocking { LocalNappletContent(model.active!!.store,w,localContentOwner(model.connection!!,w)) { true }.code(fileId) }
                assertEquals("The real reader caches source bytes, never the rendered image loader",source,cached?.html)
                assertTrue(runBlocking { requireNotNull(tab.validate).invoke(reference) })
                assertEquals(version,runBlocking { api.fileVersions(w.id,w.appNpub,fileId).currentVersionId })
            } finally { tower.requestGate = null; tower.nativeRelationalFixture = null }
        }
    }
}
