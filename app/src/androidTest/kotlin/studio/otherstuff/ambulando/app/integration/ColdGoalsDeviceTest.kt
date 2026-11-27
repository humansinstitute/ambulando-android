package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.download
import studio.otherstuff.ambulando.core.chat.members
import studio.otherstuff.ambulando.core.transport.*
import kotlinx.serialization.json.*

/** Cold upgrade: only canonical channel/File rows and the pre-94 downloaded-file cache exist. */
@RunWith(AndroidJUnit4::class)
class ColdGoalsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun canonicalGoalsWhileWritesPrepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as AmbulandoApplication
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("coldPhase") ?: "fresh"
        val mode = args.getString("coldMode") ?: "online"
        val release = CompletableDeferred<Unit>()
        check(BuildConfig.DEBUG)
        check(application.activeModel == null)
        val sample = AppEnvironment.Sample(context).baseDir
        check(sample.parentFile == context.cacheDir)
        if (phase != "upgrade") check(!sample.exists() || sample.deleteRecursively())
        lateinit var model: AppModel
        instrumentation.runOnMainSync { model = application.model(AppEnvironment.Sample(context)); model.section = AppSection.TASKS }
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Prepare runtime before launching any UI: no Home controller may warm or retain setup state.
        run {

            compose.waitUntil(30000) { model.workspaces.any { it.id == SampleIds.STUDIO } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }); model.section = AppSection.TASKS }
            compose.waitUntil(30000) { model.active?.workspace?.id == SampleIds.STUDIO && model.active?.writeActivity?.isOpen == true }
            compose.runOnIdle { model.section = AppSection.TASKS }
            val c = model.connection!!; val w = model.workspace!!; val runtime = model.active!!
            val snapshotKey = if (phase == "seed93") "unused-pre94-snapshot" else "goals.content.v1:${c.signerNpub}:${w.id}:${studio.otherstuff.ambulando.core.surfaces.localContentOwner(c,w)}"
            if (phase != "upgrade") runBlocking {
                // Use the real pre-94 controller/API without the new Goals snapshot writer.
                val seed = GoalsController(model.fileNappletAPI(c),w.id,w.appNpub,c.signerNpub) { true }
                model.sampleTower!!.administration.viewer = studio.otherstuff.ambulando.core.sample.SampleViewer.ADMIN
                seed.refresh()
                val api = model.fileNappletAPI(c)
                val channel = api.createGoalsSpace(w.id,w.appNpub,c.signerNpub,(seed.state.value.space as GoalsSpaceState.NeedsSetup).scopes.first { it.id != SampleIds.SCOPE_OPS }.id,emptyList())
                val owner = api.members(w.id,w.appNpub).single { it.npub == c.signerNpub }
                api.client.request(method="POST",path=TowerAPI.base(w.id)+"/channels/${channel.id}/grants",appNpub=w.appNpub,jsonBody=jsonBody(buildJsonObject { put("principal_type","actor");put("principal_id",owner.actorId);put("access_level","manage") }))
                seed.refresh()
                val doc = seed.create(Goal.create("Cold canonical goal","Upgrade content"))
                val checkedChannel = (seed.state.value.space as GoalsSpaceState.Ready).channel
                model.store.writer.upsertChannels(w.id,checkedChannel.scopeId,listOf(checkedChannel),complete=false)
                model.store.writer.cacheVerifiedArtefact(doc.file) { true }
                model.sync.download(c,w,doc.file,model.fileCache())
                model.engine.waitUntilIdle()
                assertNotNull(model.fileCache().cached(doc.file))
                model.store.writer.setState(snapshotKey,null)
                assertNull("No 94-specific snapshot may be seeded",model.store.reader.state(snapshotKey))
            }
            runBlocking { assertNull("Upgrade must start without a Goals snapshot",model.store.reader.state(snapshotKey)) }
            if (phase == "seed" || phase == "seed93") return
            if (mode == "firstHydration") model.fileCache().purge(w.id)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            val runtime = model.active!!
            runBlocking { runtime.awaitStarted() }
            compose.runOnIdle {
                runtime.closeGates()
                if (mode != "firstHydration") runtime.activity.resume()
                if (mode == "canonical") model.sampleTower!!.requestGate = { release.await() }
                model.section = AppSection.DECK
            }
            assertFalse(runtime.writeActivity.isOpen)
            compose.waitUntil(10000) { compose.onAllNodesWithTag("deck.inbox.goals").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("deck.inbox.goals").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("goals.title").fetchSemanticsNodes().isNotEmpty() }
            val exact = "This workspace is still confirming your access. Try again in a moment."
            val red = InstrumentationRegistry.getArguments().getString("expectOriginalFailure") == "true"
            if (red) {
                compose.waitUntil(10000) { compose.onAllNodesWithText(exact).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("goals.new").assertIsNotEnabled()
                compose.onNodeWithText("Cold canonical goal").assertDoesNotExist()
                shot("cold-goals-red")
                throw AssertionError("Original94 symptom reproduced: WritesNotReady; blank native Goals; New goal disabled; canonical records and downloaded bytes present; no Goals snapshot")
            }
            if (mode == "firstHydration") {
                compose.onNodeWithText(exact).assertDoesNotExist()
                compose.onNodeWithText("Cold canonical goal").assertDoesNotExist()
                runtime.activity.resume() // no navigation, Retry or store-change notification
            }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Cold canonical goal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(exact).assertDoesNotExist()
            compose.onNodeWithText("Cold canonical goal").assertIsDisplayed()
            shot("cold-goals-$mode-$phase-green")
            assertFalse("Rendering must not open the write gate",runtime.writeActivity.isOpen)
            model.sampleTower!!.requestGate = null; release.complete(Unit)
        }
    }
    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bmp ->
            instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            bmp.recycle()
        }
    }
}
