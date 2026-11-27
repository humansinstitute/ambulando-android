package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.chat.members
import studio.otherstuff.ambulando.core.transport.*
import java.util.concurrent.atomic.AtomicInteger

/** Two invocations separated by adb force-stop on a task-owned device. Synthetic server, real native UI/storage. */
@RunWith(AndroidJUnit4::class)
class CachedGoalsDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun nativeCachedGoalsBeforeNetworkAndAfterProcessDeath() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val phase=InstrumentationRegistry.getArguments().getString("cachePhase") ?: "warm"
        check(BuildConfig.DEBUG)
        val application=context.applicationContext as AmbulandoApplication
        if(phase=="warm" && application.activeModel==null) {
            val sample=AppEnvironment.Sample(context).baseDir
            check(sample.parentFile==context.cacheDir); check(!sample.exists() || sample.deleteRecursively())
        }
        lateinit var model: AppModel
        instrumentation.runOnMainSync {
            model=application.model(AppEnvironment.Sample(context))
            if(phase=="relaunch") model.sampleTower!!.offline=true
        }
        val launched=SystemClock.elapsedRealtime()
        val intent=Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(30000) { model.workspaces.any { it.id==SampleIds.STUDIO } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id==SampleIds.STUDIO }); model.section=AppSection.TASKS }
            compose.waitUntil(30000) { model.workspace?.id==SampleIds.STUDIO && model.active?.workspace?.id==SampleIds.STUDIO && model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked }
            compose.runOnIdle { model.section=AppSection.TASKS }
            val requests=AtomicInteger(); val release=CompletableDeferred<Unit>()
            if(phase=="warm") {
                compose.waitUntil(30000) { model.activeEntry?.runtime?.writeActivity?.isOpen==true }
                model.sampleTower!!.administration.viewer=studio.otherstuff.ambulando.core.sample.SampleViewer.ADMIN
                val c=model.connection!!; val w=model.workspace!!
                runBlocking {
                    val saved=GoalsLocalContent(model.store,w.id,c.signerNpub,studio.otherstuff.ambulando.core.surfaces.localContentOwner(c,w))
                    val controller=GoalsController(model.fileNappletAPI(c),w.id,w.appNpub,c.signerNpub,saved) { true }
                    controller.refresh()
                    if(controller.state.value.space is GoalsSpaceState.NeedsSetup) {
                        val setup=controller.state.value.space as GoalsSpaceState.NeedsSetup
                        val api=model.fileNappletAPI(c)
                        val channel=api.createGoalsSpace(w.id,w.appNpub,c.signerNpub,setup.scopes.first {it.id!=SampleIds.SCOPE_OPS}.id,emptyList())
                        // The synthetic administration fixture does not add the creator grant automatically.
                        val owner=api.members(w.id,w.appNpub).single {it.npub==c.signerNpub}
                        api.client.request(method="POST",path=TowerAPI.base(w.id)+"/channels/${channel.id}/grants",appNpub=w.appNpub,jsonBody=jsonBody(buildJsonObject {put("principal_type","actor");put("principal_id",owner.actorId);put("access_level","manage")}))
                        controller.refresh()
                    }
                    val doc=controller.create(Goal.create("Cached native goal","Durable progress"))
                    controller.edit(doc.id,GoalEdit.AddCheckpoint(GoalCheckpoint("cached","Durable checkpoint")),GoalEdit.SetReached("cached",false,true))
                    val restored=saved.load()
                    assertNotNull("Written cache could not reload: ${saved.lastLoadIssue}",restored)
                }
                model.sampleTower!!.requestGate={ requests.incrementAndGet(); release.await() }
            }
            val opened=SystemClock.elapsedRealtime()
            compose.runOnIdle { model.section=AppSection.HOME }
            try { compose.waitUntil(10000) { compose.onAllNodesWithText("Cached native goal").fetchSemanticsNodes().isNotEmpty() } }
            catch(e: Throwable) {
                compose.onRoot(useUnmergedTree=true).printToLog("CacheProbe94")
                val c=model.connection!!; val w=model.workspace!!
                val local=GoalsLocalContent(model.store,w.id,c.signerNpub,studio.otherstuff.ambulando.core.surfaces.localContentOwner(c,w))
                val cached=runBlocking { local.load() }
                val token=runBlocking { local.token() }
                throw AssertionError("Cached UI missing; tokenLength=${token?.length}; reason=${local.lastLoadIssue}; local=${cached?.goals?.map {it.goal.name}}; section=${model.section}; boundary=${model.activeBoundaryId}; requests=${requests.get()}; identity=${model.identityState?.javaClass?.simpleName}",e)
            }
            val usable=SystemClock.elapsedRealtime()
            compose.onNodeWithText("Cached native goal").assertIsDisplayed()
            if(phase=="warm") {
                compose.waitUntil(10000) { requests.get()>0 }
                assertFalse(release.isCompleted)
            }
            val proof=buildJsonObject {
                put("evidence","synthetic native UI, durable app store");put("phase",phase);put("versionCode",BuildConfig.VERSION_CODE)
                put("navigationToUsableMillis",usable-opened);put("launchToUsableMillis",usable-launched)
                put("network",if(phase=="warm") "requests gated, no responses" else "offline before activity launch")
            }
            context.getExternalFilesDir(null)!!.resolve("cached-goals-$phase.json").writeText(proof.toString())
            instrumentation.uiAutomation.takeScreenshot().let { bitmap -> context.getExternalFilesDir(null)!!.resolve("cached-goals-$phase.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
            model.sampleTower!!.requestGate=null; release.complete(Unit)
        }
    }
}
