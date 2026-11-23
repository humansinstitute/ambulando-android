package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
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
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Run on a fresh disposable sample installation. Stops before rendering; legacy WebView resume is separate. */
@RunWith(AndroidJUnit4::class)
class WorkspaceReportBootstrapTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun captureEvidence(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "android71-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun freshSyntheticFixture() {
        check(BuildConfig.DEBUG)
        val app = context.applicationContext as AmbulandoApplication
        check(app.activeModel == null) { "Use a fresh disposable sample process for bootstrap regression." }
        val sample = AppEnvironment.Sample(context).baseDir
        check(sample.parentFile == context.cacheDir)
        check(!sample.exists() || sample.deleteRecursively())
    }

    private fun awaitTag(tag: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun absent(tag: String) = compose.onAllNodesWithTag(tag).assertCountEquals(0)
    private fun awaitSignal(signal: CompletableDeferred<Unit>, label: String) {
        // Keep advancing Compose frames after clicks; runBlocking here leaves navigation uncomposed.
        try { compose.waitUntil(20_000) { signal.isCompleted } } catch (e: androidx.compose.ui.test.ComposeTimeoutException) { captureEvidence("bootstrap-signal-timeout")
            val model = (context.applicationContext as AmbulandoApplication).activeModel
            throw AssertionError("Missing bootstrap signal: $label; readiness=${model?.active?.readinessSnapshot()}; requests=${model?.sampleTower?.work?.requestLog?.takeLast(8)}", e) }
    }

    @Test fun completeEmptyRecheckReadinessFailuresAndStalePreopenResults() {
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.active?.canSend == true && model.workspace?.id == SampleIds.STUDIO }
            val runtime = model.active!!
            val tower = model.sampleTower!!
            val c = runtime.connection; val w = runtime.workspace
            val api = model.fileNappletAPI(c)
            val writes = AtomicInteger()
            val metadataReads = AtomicInteger()
            tower.requestGate = { if (it.method != "GET") writes.incrementAndGet() }
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            compose.onNodeWithTag("apps.workspaceReport").performClick()
            awaitTag("apps.setupReport")
            assertEquals("empty discovery is read only", 0, writes.get())
            // A concurrent creator wins after the initial empty read. Explicit setup must recheck and adopt it.
            val html = context.assets.open("napplets/workspace-report-v1.html").bufferedReader().use { it.readText() }
            val code = runBlocking { api.createWorkspaceReport(w.id, w.appNpub, c.signerNpub, html).code }
            writes.set(0)
            compose.onNodeWithTag("apps.setupReport").performClick()
            awaitTag("report.trust")
            assertEquals("fresh setup recheck adopts without create/upload", 0, writes.get())
            fun closeReport() { compose.onNodeWithTag("report.close").performClick() }
            fun launchReport() { compose.onNodeWithTag("apps.workspaceReport").performClick() }
            fun isMetadata(request: HttpRequest) = URI(request.url).path.endsWith("/files/${code.id}")
            closeReport()

            // A failed validation is visible, never mistaken for an empty report list.
            runBlocking { runtime.pause() }
            tower.offline = true
            runBlocking { runtime.start(foreground = false); runtime.awaitStarted() }
            launchReport(); awaitTag("apps.reportValidationError"); captureEvidence("report-validation-error")
            absent("apps.setupReport"); absent("apps.reportError"); absent("report.trust")
            tower.offline = false

            // Hold membership validation while the same screen remains open. Readiness must trigger exactly one metadata open.
            val validating = CompletableDeferred<Unit>(); val validated = CompletableDeferred<Unit>()
            tower.requestGate = { request ->
                if (request.method != "GET") writes.incrementAndGet()
                if (URI(request.url).path == "/api/v4/flightdeck-pg/workspaces") { validating.complete(Unit); validated.await() }
                if (isMetadata(request)) metadataReads.incrementAndGet()
            }
            model.diagnostics.clear()
            compose.onNodeWithText("Retry validation").performClick()
            awaitSignal(validating,"membership validation started"); awaitTag("apps.reportWaiting"); captureEvidence("report-validation-waiting")
            assertEquals(0, metadataReads.get()); absent("apps.setupReport"); absent("apps.reportError")
            validated.complete(Unit)
            awaitTag("report.trust")
            val bootstrapMetadata = model.diagnostics.export("test", 1, 36).lineSequence().count { it.contains("report=METADATA | cause=OK") }
            // HtmlFileArtefact and host consent perform their own required authority reads.
            assertEquals("one bootstrap metadata open per readiness transition", 1, bootstrapMetadata)
            assertEquals(0, writes.get()); captureEvidence("report-ready-after-retry")
            closeReport()

            // Discovery finds a known report, but its fresh metadata fails. No replacement or setup is allowed.
            tower.requestGate = { request ->
                if (request.method != "GET") writes.incrementAndGet()
                if (isMetadata(request)) throw TowerError.Http(403, "https://private.invalid?secret=hidden", "hidden")
            }
            launchReport(); awaitTag("apps.reportError")
            compose.onNodeWithText("This report could not be opened. No replacement was created. Retry to check it again.").assertIsDisplayed()
            absent("apps.setupReport"); absent("report.trust"); assertEquals(0, writes.get()); captureEvidence("report-metadata-error")
            val exported = model.diagnostics.export("test", 1, 36)
            assertTrue(exported.contains("report=METADATA | cause=HTTP")); assertFalse(exported.contains("hidden"))
            tower.requestGate = { if (it.method != "GET") writes.incrementAndGet() }
            compose.onNodeWithText("Retry").performClick(); awaitTag("report.trust"); closeReport()

            // Simulate an uncooperative transport returning either success or ordinary error after pause.
            for (lateFailure in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
                tower.requestGate = { request ->
                    if (request.method != "GET") writes.incrementAndGet()
                    if (isMetadata(request)) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        returned.complete(Unit)
                        if (lateFailure) throw IllegalStateException("private late failure")
                    }
                }
                launchReport(); awaitSignal(entered,"lateFailure=$lateFailure metadata started")
                runBlocking { runtime.pause() }
                awaitTag("apps.reportWaiting")
                release.complete(Unit); awaitSignal(returned,"late metadata returned")
                compose.waitForIdle()
                absent("report.trust"); absent("apps.reportError"); absent("apps.setupReport")
                tower.requestGate = { if (it.method != "GET") writes.incrementAndGet() }
                runBlocking { runtime.start(foreground = false) }
                awaitTag("report.trust"); closeReport()
            }

            // A different workspace cannot receive the original workspace's suspended open.
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
            tower.requestGate = { request ->
                if (request.method != "GET") writes.incrementAndGet()
                if (isMetadata(request)) {
                    entered.complete(Unit); withContext(NonCancellable) { release.await() }; returned.complete(Unit)
                }
            }
            launchReport(); awaitSignal(entered,"workspace-switch metadata started")
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.LAB }) }
            compose.waitUntil(20_000) { model.workspace?.id == SampleIds.LAB }
            release.complete(Unit); awaitSignal(returned,"late metadata returned"); compose.waitForIdle()
            absent("report.trust"); absent("apps.reportError")
            assertEquals(0, writes.get())
            tower.requestGate = null

            // An uncertain explicit setup stays latched through retry and same-boundary readiness changes.
            compose.waitUntil(20_000) { model.active?.canSend == true }
            val labRuntime = model.active!!
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            launchReport(); awaitTag("apps.setupReport")
            val uncertainWrites = AtomicInteger()
            tower.requestGate = { request ->
                if (request.method != "GET") { uncertainWrites.incrementAndGet(); throw IllegalStateException("uncertain create") }
            }
            compose.onNodeWithTag("apps.setupReport").performClick(); awaitTag("apps.reportError")
            assertEquals(1, uncertainWrites.get())
            compose.onNodeWithText("Retry").performClick(); awaitTag("apps.setupReport")
            compose.onNodeWithTag("apps.setupReport").assertIsNotEnabled()
            runBlocking { labRuntime.pause(); labRuntime.start(foreground = false) }
            awaitTag("apps.setupReport")
            compose.onNodeWithTag("apps.setupReport").assertIsNotEnabled()
            assertEquals("uncertain setup cannot be retried automatically", 1, uncertainWrites.get())
            tower.requestGate = null

            // Relocking while metadata is suspended must also suppress its late error and finally.
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.active === runtime && runtime.canSend }
            compose.runOnIdle { model.section = AppSection.TOOLBOX }
            val locking = CompletableDeferred<Unit>(); val unlockResponse = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
            tower.requestGate = { request ->
                if (isMetadata(request)) {
                    locking.complete(Unit); withContext(NonCancellable) { unlockResponse.await() }
                    finished.complete(Unit); throw IllegalStateException("private relock error")
                }
            }
            launchReport(); awaitSignal(locking,"relock metadata started")
            compose.runOnIdle { model.lock() }
            compose.waitUntil(20_000) { !runtime.canSend }
            unlockResponse.complete(Unit); awaitSignal(finished,"relock response finished")
            compose.waitUntil(20_000) { model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Locked }
            absent("report.trust"); absent("apps.reportError"); absent("apps.setupReport")
            tower.requestGate = null
        }
    }
}
