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
class DailyTrackerFlowTest {
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
    @Test fun appsSetupSaveAdoptAndReopenFromFiles() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window
            scenario.onActivity { window = it.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.dailyMetrics")).fetchSemanticsNodes().isNotEmpty() }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                java.io.File(context.getExternalFilesDir(null), "daily-metrics-apps.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val started = android.os.SystemClock.elapsedRealtime()
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.setupTracker")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("apps.setupTracker").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.trust").performClick()
            var web: WebView? = null
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
            android.util.Log.i("FileNappletPerf", "native empty create/trust/open ms=" + (android.os.SystemClock.elapsedRealtime() - started))
            val c = model.connection!!; val w = model.workspace!!
            val api = model.fileNappletAPI(c)
            val initialIds = kotlinx.coroutines.runBlocking { api.dailyMetricsInstances(w.id, w.appNpub, c.signerNpub).map { it.id } }
            assertEquals(1, initialIds.size)
            assertTrue(web!!.height > 300)
            assertEquals("false", page(web!!, "document.documentElement.scrollWidth>innerWidth"))
            page(web!!, "document.getElementById('manage').click();document.querySelector('input[placeholder]').value='Boolean test';Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Add metric').click()")
            assertEquals("1", page(web!!, "document.querySelectorAll('#content .metric').length-1"))
            page(web!!, "document.getElementById('checkin').click();Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').click();document.getElementById('save').click()")
            compose.waitUntil(20_000) { page(web!!, "document.getElementById('status').textContent")?.startsWith("Saved in NStack") == true }
            // Native Back cannot silently discard an unsaved edit.
            page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Yes').click()")
            val session = page(web!!, "window.nappletSession")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(20_000) { page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Yes').getAttribute('aria-pressed')") == "true" }
            assertEquals("Ordinary app switching keeps the same document", session, page(web!!, "window.nappletSession"))
            compose.onNodeWithTag("napplet.close").performClick()
            compose.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').click();document.getElementById('save').click()")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            Thread.sleep(150) // The explicit pending save may complete; it must not acquire a newer base or disappear.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertEquals(session, page(web!!, "window.nappletSession"))
            compose.waitUntil(20_000) { page(web!!, "document.getElementById('status').textContent")?.startsWith("Saved in NStack") == true }
            // Save is durable in synthetic File storage; use the actual File route again after destroying the page.
            compose.onNodeWithTag("napplet.close").performClick()
            // Apps must adopt the same code/data and saved answer without a second setup.
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.onAllNodesWithTag("apps.setupTracker").assertCountEquals(0)
            compose.onAllNodesWithTag("artefacts.editSource").assertCountEquals(0)
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            compose.onNodeWithTag("napplet.close").performClick()
            compose.runOnIdle { model.selectContext(null, null); model.section = AppSection.FILES }
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Daily tracker.html")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Daily tracker.html").onFirst().performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            compose.onNodeWithTag("napplet.close").assertIsDisplayed()
            assertEquals(initialIds, kotlinx.coroutines.runBlocking { api.dailyMetricsInstances(w.id, w.appNpub, c.signerNpub).map { it.id } })
            // Forget is explicit, protects dirty edits, and requires fresh consent without deleting answers.
            page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Yes').click()")
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Unsaved")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.forget").performClick()
            compose.onNodeWithText("Discard changes and forget approval?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Yes').getAttribute('aria-pressed')"))
            compose.onNodeWithTag("napplet.forget").performClick()
            compose.onNodeWithText("Discard and forget").performClick()
            compose.onNodeWithTag("napplet.trust").assertIsDisplayed()
            compose.onAllNodesWithTag("napplet.host").assertCountEquals(0)
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            // DOM readiness may precede Chromium/Compose presentation; require actual drawn content.
            var image: android.graphics.Bitmap? = null
            compose.waitUntil(10_000) {
                image = instrumentation.uiAutomation.takeScreenshot()
                var drawn = 0
                val bitmap = image!!
                for (x in 0 until bitmap.width step 4) for (y in 150 until bitmap.height - 200 step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) < 180 && android.graphics.Color.green(pixel) < 180 && android.graphics.Color.blue(pixel) < 180) drawn++
                }
                drawn > 200
            }
            java.io.File(context.getExternalFilesDir(null), "daily-tracker-synthetic.png").outputStream().use { image!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            // Recreate the actual Activity: approval survives loss of all Compose state and the old WebView.
            scenario.recreate()
            scenario.onActivity { window = it.window }
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.dailyMetrics")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            // Denied local persistence permits explicit session consent, never silent consent after reopening.
            compose.onNodeWithTag("napplet.forget").performClick()
            compose.onNodeWithTag("napplet.close").performClick()
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            val approvalDirectory = java.io.File(model.environment.baseDir, "napplet-approvals")
            assertTrue(approvalDirectory.deleteRecursively())
            approvalDirectory.writeText("Synthetic unavailable receipt directory")
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onNodeWithText("Approval could not be remembered on this device. It applies only to this open session.").assertIsDisplayed()
            compose.onNodeWithTag("napplet.close").performClick()
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(approvalDirectory.delete())
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onNodeWithTag("napplet.close").performClick()
            // A new immutable code version with identical bytes retains consent; changed bytes require approval.
            val originalHtml = context.assets.open("napplets/daily-tracker-v1.html").bufferedReader().use { it.readText() }
            fun replaceCode(source: String) = kotlinx.coroutines.runBlocking {
                val list = api.fileVersions(w.id, w.appNpub, initialIds.single())
                val objectId = api.store(w.id, w.appNpub, source.toByteArray(), "text/html", "tracker.html", null) { }
                assertTrue(api.addFileVersion(w.id, w.appNpub, initialIds.single(), list.currentVersionId!!, objectId, java.util.UUID.randomUUID().toString()) is studio.otherstuff.ambulando.core.transport.VersionOutcome.Saved)
            }
            replaceCode(originalHtml)
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
            compose.onNodeWithTag("napplet.close").performClick()
            replaceCode(originalHtml + "<!-- Synthetic reviewed code update -->")
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("napplet.host").assertCountEquals(0)
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            // Multiple existing trackers require a chooser; selecting the original preserves its answers.
            val html = context.assets.open("napplets/daily-tracker-v1.html").bufferedReader().use { it.readText() }
            val second = kotlinx.coroutines.runBlocking { api.createDailyTracker(w.id, w.appNpub, c.signerNpub, html) }
            compose.onNodeWithTag("napplet.close").performClick()
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.chooseTracker")).fetchSemanticsNodes().size == 2 }
            compose.onAllNodesWithTag("apps.setupTracker").assertCountEquals(0)
            fun chooseOriginal() {
                compose.onNode(hasTestTag("apps.chooseTracker") and hasText(initialIds.single().take(8), substring = true)).performClick()
            }
            val originalCode = kotlinx.coroutines.runBlocking { api.artefactFile(w.id, w.appNpub, initialIds.single()) }
            val originalMetadata = originalCode.raw.getValue("metadata").jsonObject
            val originalDataId = FileNapplet.binding(originalMetadata, nappletManifest(originalHtml)!!)
            // Same code File with a different exact data binding must ask again, before any WebView exists.
            val otherBinding = originalMetadata.toString().replace(originalDataId, second.data.id)
            assertTrue(model.sampleTower!!.work.externalFilePolicy(originalCode.id, Json.parseToJsonElement(otherBinding).jsonObject))
            chooseOriginal()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("napplet.host").assertCountEquals(0)
            // Cancel leaves no new receipt. Restore original binding and verify effective access is also scoped.
            compose.onNodeWithTag("napplet.close").performClick()
            assertTrue(model.sampleTower!!.work.externalFilePolicy(originalCode.id, originalMetadata))
            assertTrue(model.sampleTower!!.work.externalFilePolicy(originalDataId, canWrite = false))
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.chooseTracker")).fetchSemanticsNodes().size == 2 }
            chooseOriginal()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("napplet.host").assertCountEquals(0)
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            // Broadening permissions also stops an active host; read-only approval cannot silently become write approval.
            val beforeEscalation = kotlinx.coroutines.runBlocking { api.fileVersions(w.id, w.appNpub, originalDataId) }
            val unchangedValue = kotlinx.coroutines.runBlocking { api.fileVersionBytes(w.id, w.appNpub, originalDataId, beforeEscalation.versions.first { it.id == beforeEscalation.currentVersionId }) }.toString(Charsets.UTF_8)
            assertTrue(model.sampleTower!!.work.externalFilePolicy(originalDataId, canWrite = true, notify = false))
            // Craft a valid bridge save immediately: host consent, not template controls, must prevent escalation.
            page(web!!, "parent.postMessage({type:'wingman-napplet-data',version:1,session:window.nappletSession,requestId:'escalation',operation:'save',binding:'tracker',baseVersionId:'${beforeEscalation.currentVersionId}',value:$unchangedValue},'*')")
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.host")).fetchSemanticsNodes().isEmpty() }
            assertEquals(beforeEscalation.currentVersionId, kotlinx.coroutines.runBlocking { api.fileVersions(w.id, w.appNpub, originalDataId).currentVersionId })
            compose.onNodeWithTag("napplet.close").performClick()
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.chooseTracker")).fetchSemanticsNodes().size == 2 }
            chooseOriginal()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            assertEquals(setOf(initialIds.single(), second.code.id), kotlinx.coroutines.runBlocking { api.dailyMetricsInstances(w.id, w.appNpub, c.signerNpub).map { it.id }.toSet() })
            compose.onNodeWithTag("napplet.close").performClick()
            val invalid = kotlinx.coroutines.runBlocking { api.createDailyTracker(w.id, w.appNpub, c.signerNpub, "<h1>No valid manifest</h1>") }
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.chooseTracker")).fetchSemanticsNodes().size == 3 }
            compose.onNode(hasTestTag("apps.chooseTracker") and hasText(invalid.code.id.take(8), substring = true)).performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("This tracker is invalid or unavailable. No replacement was created.")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("apps.setupTracker").assertCountEquals(0)
            compose.onAllNodesWithTag("artefacts.editSource").assertCountEquals(0)
            compose.onAllNodesWithTag("napplet.host").assertCountEquals(0)
            assertEquals(3, kotlinx.coroutines.runBlocking { api.dailyMetricsInstances(w.id, w.appNpub, c.signerNpub).size })
            // Leave the invalid app using the normal route Back, then re-open the original for revocation testing.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.chooseTracker")).fetchSemanticsNodes().size == 3 }
            compose.onNode(hasTestTag("apps.chooseTracker") and hasText(initialIds.single().take(8), substring = true)).performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            model.sampleTower!!.revokedWorkspaces = setOf(SampleIds.STUDIO)
            try {
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.host")).fetchSemanticsNodes().isEmpty() }
                instrumentation.runOnMainSync { assertNull("Denied return removes the WebView", find(window.decorView)) }
            } finally { model.sampleTower!!.revokedWorkspaces = emptySet() }

        }
    }
    @Test fun explicitOriginalCodeUpdatePreservesSavedDataAndRequiresNewTrust() {
        val context = instrumentation.targetContext
        val original = instrumentation.context.assets.open("napplets/daily-tracker-original.html").bufferedReader().use { it.readText() }
        assertTrue(FileNappletUpdate.recognized(original))
        val replacement = context.assets.open("napplets/daily-tracker-v1.html").bufferedReader().use { it.readText() }
        assertFalse("Frozen replacement must be installed before this test", FileNappletUpdate.recognized(replacement))
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window
            scenario.onActivity { window = it.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.waitUntil(20_000) { model.activeEntry?.runtime?.writeActivity?.isOpen == true }
            val c = model.connection!!; val w = model.workspace!!; val api = model.fileNappletAPI(c)
            val created = kotlinx.coroutines.runBlocking { api.createDailyTracker(w.id, w.appNpub, c.signerNpub, original) }
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.dailyMetrics")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.chooseTracker") or hasTestTag("napplet.trust") or hasTestTag("napplet.host")).fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodes(hasTestTag("apps.chooseTracker")).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(hasTestTag("apps.chooseTracker") and hasText(created.code.id.take(8), substring = true)).performClick()
            }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.trust").performClick()
            var web: WebView? = null
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.update")).fetchSemanticsNodes().isNotEmpty() }
            val saveReached = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseSave = kotlinx.coroutines.CompletableDeferred<Unit>()
            model.sampleTower!!.requestGate = { request ->
                if (request.method == "POST" && java.net.URI(request.url).path.endsWith("/files/${created.data.id}/versions")) {
                    saveReached.complete(Unit); releaseSave.await()
                }
            }
            try {
                page(web!!, "document.getElementById('manage').click();document.querySelector('input[placeholder]').value='Synthetic update test';Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Add metric').click();document.getElementById('checkin').click();Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').click();document.getElementById('save').click()")
                compose.waitUntil(10_000) { saveReached.isCompleted }
                compose.onNodeWithTag("napplet.update").assertIsNotEnabled()
                compose.onNodeWithTag("napplet.close").assertIsNotEnabled()
            } finally { releaseSave.complete(Unit); model.sampleTower!!.requestGate = null }
            compose.waitUntil(20_000) { page(web!!, "document.getElementById('status').textContent")?.startsWith("Saved in NStack") == true }
            capture("daily-metrics-before-update.png", web!!, context)
            val dataBefore = kotlinx.coroutines.runBlocking { api.fileVersions(w.id, w.appNpub, created.data.id) }
            val bytesBefore = kotlinx.coroutines.runBlocking { api.fileVersionBytes(w.id, w.appNpub, created.data.id, dataBefore.versions.last()) }
            page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Yes').click()")
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Unsaved")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("napplet.update") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.update").performClick()
            compose.onNodeWithText("Discard changes and update app?").assertIsDisplayed()
            compose.onNodeWithText("Keep current app").performClick()
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Yes').getAttribute('aria-pressed')"))
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("napplet.update") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.update").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("napplet.confirmUpdate")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("napplet.confirmUpdate").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("napplet.trust")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("napplet.host").assertCountEquals(0)
            assertEquals(dataBefore, kotlinx.coroutines.runBlocking { api.fileVersions(w.id, w.appNpub, created.data.id) })
            assertArrayEquals(bytesBefore, kotlinx.coroutines.runBlocking { api.fileVersionBytes(w.id, w.appNpub, created.data.id, dataBefore.versions.last()) })
            val updated = kotlinx.coroutines.runBlocking { api.artefactFile(w.id, w.appNpub, created.code.id) }
            assertEquals(created.code.raw["metadata"], updated.raw["metadata"])
            val codeVersions = kotlinx.coroutines.runBlocking { api.fileVersions(w.id, w.appNpub, created.code.id) }
            assertEquals(2, codeVersions.versions.size)
            assertEquals(replacement, kotlinx.coroutines.runBlocking { api.fileVersionBytes(w.id, w.appNpub, created.code.id, codeVersions.versions.last()) }.toString(Charsets.UTF_8))
            compose.onNodeWithTag("napplet.trust").performClick()
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            assertEquals("true", page(web!!, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='No').getAttribute('aria-pressed')"))
            compose.onAllNodesWithTag("napplet.update").assertCountEquals(0)
            assertEquals("false", page(web!!, "document.documentElement.scrollWidth>innerWidth"))
            capture("daily-metrics-updated.png", web!!, context)
            page(web!!, "document.getElementById('manage').click();document.querySelector('input[placeholder]').value='Synthetic number';document.querySelector('select').value='number';Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Add metric').click();document.getElementById('checkin').click()")
            val typing = page(web!!, "(()=>{const n=document.querySelector('input[type=number]');n.focus();const times=[];for(const v of ['1','12','123','1234','12345','123456','1234567','12345678','123456789','1234567890','1234567890.2','1234567890.25']){const t=performance.now();n.value=v;n.dispatchEvent(new Event('input'));times.push(performance.now()-t)}return JSON.stringify({maxMs:Math.max(...times),sameNode:n===document.querySelector('input[type=number]'),focus:document.activeElement===n,inputmode:n.inputMode,value:n.value,width:innerWidth,overflow:document.documentElement.scrollWidth>innerWidth})})()")!!
            android.util.Log.i("FileNappletPerf", "updated native typing " + typing)
            val typed = Json.parseToJsonElement(typing).jsonObject
            assertEquals(JsonPrimitive(true), typed["sameNode"]); assertEquals(JsonPrimitive(true), typed["focus"])
            assertEquals(JsonPrimitive("decimal"), typed["inputmode"]); assertEquals(JsonPrimitive(false), typed["overflow"])
            page(web!!, "document.querySelector('input[type=number]').blur();document.querySelector('input[type=number]').value='0';document.querySelector('input[type=number]').dispatchEvent(new Event('input'));document.getElementById('save').click()")
            compose.waitUntil(20_000) { page(web!!, "document.getElementById('status').textContent")?.startsWith("Saved in NStack") == true }
            compose.onNodeWithTag("napplet.close").performClick()
            compose.onNodeWithTag("apps.dailyMetrics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("apps.chooseTracker") or hasTestTag("napplet.trust") or hasTestTag("napplet.host")).fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodes(hasTestTag("apps.chooseTracker")).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(hasTestTag("apps.chooseTracker") and hasText(created.code.id.take(8), substring = true)).performClick()
            }
            compose.waitUntil(20_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null && page(web!!, "document.getElementById('controls')?.hidden") == "false" }
            compose.onAllNodesWithTag("napplet.trust").assertCountEquals(0)
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
