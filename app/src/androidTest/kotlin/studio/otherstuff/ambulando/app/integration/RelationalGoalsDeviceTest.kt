package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** Real Android signer/controller/SQLite; a labelled native-v1 fixture replaces only network persistence. */
@RunWith(AndroidJUnit4::class)
class RelationalGoalsDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun nativeRelationalCreateEditConflictRetryAndOfflineRecovery() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(BuildConfig.DEBUG)
        val application=context.applicationContext as AmbulandoApplication
        if(application.activeModel==null){val source=AppEnvironment.Sample(context).baseDir;check(source.parentFile==context.cacheDir);source.deleteRecursively()}
        lateinit var model:AppModel
        instrumentation.runOnMainSync{model=application.model(AppEnvironment.Sample(context))}
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            fun waitFor(test:()->Boolean){val deadline=SystemClock.elapsedRealtime()+30000;while(!test()){check(SystemClock.elapsedRealtime()<deadline){"Native fixture readiness timed out"};SystemClock.sleep(50)}}
            waitFor{model.workspaces.any{it.id==SampleIds.STUDIO}}
            instrumentation.runOnMainSync{model.selectWorkspace(model.workspaces.first{it.id==SampleIds.STUDIO});model.section=AppSection.TASKS}
            waitFor{model.active?.workspace?.id==SampleIds.STUDIO&&model.activeEntry?.runtime?.writeActivity?.isOpen==true}
            runBlocking {
                val c=model.connection!!;val w=model.workspace!!;val originalApi=model.fileNappletAPI(c)
                val seed=GoalsController(originalApi,w.id,w.appNpub,c.signerNpub){true};seed.refresh()
                if(seed.state.value.space is GoalsSpaceState.NeedsSetup)seed.setUp(null,emptyList())
                val legacy=seed.create(Goal.create("Native legacy identity","Original retained File"));val channel=legacy.file.channelId
                val scope=jsonObjectOf("workspace_id" to w.id,"channel_id" to channel,"owner_npub" to c.signerNpub)
                val tables=listOf("goals_authority","goals","goal_checkpoints","goal_associations","goal_history","goal_checkpoint_history","goal_association_history").associateWith{mutableListOf<JsonObject>()}.toMutableMap()
                tables.getValue("goals_authority")+=JsonObject(scope+jsonObjectOf("id" to channel,"revision" to "1","contract_version" to 1,"schema_version" to "ambulando-goals-relational-1"))
                tables.getValue("goals")+=JsonObject(scope+jsonObjectOf("id" to legacy.id,"revision" to "1","name" to legacy.goal.name,"description" to legacy.goal.description,"status" to "active","extensions" to "{}","present_fields" to JsonArray(emptyList()),"created_at" to "2026-11-27T00:00:00Z","updated_at" to "2026-11-27T00:00:00Z"))
                var generation=1;var offline=false;var conflict=false;var drop=false;val receipts=hashMapOf<String,Pair<String,JsonObject>>()
                val transport=object:HttpTransport {override suspend fun send(request:HttpRequest):HttpResponse {
                    if(offline)throw java.io.IOException("fixture offline")
                    if(!request.url.contains("/db/native-v1/"))return model.sampleTower!!.send(request)
                    check(model.sampleTower!!.auth.verify(request) is SampleAuth.Result.Valid){"Native request signature invalid"}
                    val body=Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
                    fun reply(value:JsonObject,status:Int=200)=HttpResponse(status,value.toString().toByteArray())
                    return when(request.url.substringAfterLast('/')) {
                        "status"->reply(jsonObjectOf("version" to 1,"authority" to jsonObjectOf("mode" to "active","revision" to "1"),"fence" to jsonObjectOf("active" to true,"revision" to "1")))
                        "read"->{val row=tables.getValue("goals").firstOrNull{it.string("id")==body.string("id")};if(row==null)reply(jsonObjectOf("code" to "row_not_found"),404)else reply(jsonObjectOf("version" to 1,"generation" to generation.toString(),"channel_id" to channel,"row" to row))}
                        "query"->{if(body.string("generation")?.let{it!=generation.toString()}==true)reply(jsonObjectOf("code" to "snapshot_changed"),409)else reply(jsonObjectOf("version" to 1,"generation" to generation.toString(),"rows" to JsonArray(tables.getValue(body.string("table")!!)),"has_more" to false,"next_after" to null))}
                        "batch"->{val op=body.string("operation_id")!!;val text=body.toString();val previous=receipts[op]
                            if(previous!=null){assertEquals(previous.first,text);reply(previous.second)}else if(conflict){conflict=false;val row=tables.getValue("goals").first{it.string("id")==legacy.id};tables["goals"]=tables.getValue("goals").map{if(it===row)JsonObject(row+jsonObjectOf("description" to "Independent agent edit","revision" to (flexibleLong(row["revision"])!!+1).toString()))else it}.toMutableList();generation++;reply(jsonObjectOf("code" to "row_conflict"),409)}else{
                                val next=tables.mapValues{(_,v)->v.toMutableList()}.toMutableMap()
                                for(value in body.getValue("operations").jsonArray){val o=value.jsonObject;val values=next.getValue(o.string("table")!!);val id=o.string("id")!!;val at=values.indexOfFirst{it.string("id")==id}
                                    when(o.string("kind")){
                                        "insert"->{check(at<0);values+=JsonObject(scope+o.getValue("data").jsonObject+jsonObjectOf("id" to id,"revision" to "1"))}
                                        "update"->{check(flexibleLong(values[at]["revision"])==flexibleLong(o["expected_revision"]));values[at]=JsonObject(values[at]+o.getValue("data").jsonObject+jsonObjectOf("revision" to (flexibleLong(values[at]["revision"])!!+1).toString()))}
                                        "delete"->{check(flexibleLong(values[at]["revision"])==flexibleLong(o["expected_revision"]));values.removeAt(at)}
                                    }
                                }
                                tables.clear();tables.putAll(next);generation++;val saved=jsonObjectOf("version" to 1,"operation_id" to op,"generation" to generation.toString());receipts[op]=text to saved
                                if(drop){drop=false;throw java.io.IOException("committed response lost")};reply(saved)
                            }}
                        else->reply(jsonObjectOf("code" to "unknown_operation"),404)
                    }
                }}
                model.sampleTower!!.nativeRelationalFixture={ request -> if(request.url.contains("/db/native-v1/")) transport.send(request) else null }
                val referenceController=model.goalsController()!!;referenceController.refresh()
                val api=model.fileNappletAPI(c,GoalsTransport(transport,transport));val local=GoalsLocalContent(model.store,w.id,c.signerNpub,"device-relational-fixture")
                var controller=GoalsController(api,w.id,w.appNpub,c.signerNpub,local){true};controller.refresh()
                assertEquals(legacy.id,controller.state.value.goals.single().id)
                conflict=true
                controller.edit(legacy.id,GoalEdit.SetName(legacy.goal.name,"Native SQL goal"),GoalEdit.AddCheckpoint(GoalCheckpoint("cp-first","First")),GoalEdit.AddCheckpoint(GoalCheckpoint("cp-last","Last")))
                val changed=controller.edit(legacy.id,GoalEdit.SetReached("cp-first",false,true),GoalEdit.MoveCheckpoint("cp-last",0),GoalEdit.SetDefaultChannel(null,SampleIds.GENERAL))
                assertEquals("Independent agent edit",changed.goal.description);assertEquals(listOf("cp-last","cp-first"),changed.goal.checkpoints.map{it.id});assertEquals(1,changed.goal.reachedCount)
                val task=model.store.reader.tasks(w.id).first()
                val linked=controller.edit(legacy.id,GoalEdit.Link(GoalLink(GoalLinkType.TASK,task.id)))
                assertEquals(task.id,linked.goal.links.single().recordId)
                val doc=originalApi.channelDocs(w.id,w.appNpub,SampleIds.GENERAL).items.first()
                assertEquals(doc.id,controller.edit(legacy.id,GoalEdit.Pin(GoalLink(GoalLinkType.DOC,doc.id))).goal.pinnedRefs.single().recordId)
                assertThrows(GoalError.Conflict::class.java){runBlocking{controller.edit(legacy.id,GoalEdit.SetName("stale name","Must not overwrite"))}}
                drop=true;assertThrows(java.io.IOException::class.java){runBlocking{controller.create(Goal.create("Recovered create","Retained draft"))}}
                controller=GoalsController(model.fileNappletAPI(c,GoalsTransport(transport,transport)),w.id,w.appNpub,c.signerNpub,local){true}
                controller.refresh()
                assertThrows(GoalSaveFailed::class.java){runBlocking{controller.create(Goal.create("Do not duplicate","Different intent"))}}
                controller.refresh();assertEquals(2,controller.state.value.goals.size);assertEquals(1,controller.state.value.goals.count{it.goal.name=="Recovered create"})
                val sqlCreated=controller.state.value.goals.single{it.goal.name=="Recovered create"}
                assertNull(model.store.reader.file(w.id,sqlCreated.id));assertEquals(sqlCreated.id,controller.reload(sqlCreated.id).id)
                assertTrue(referenceController.state.value.loaded);assertNull(referenceController.goal(sqlCreated.id))
                instrumentation.runOnMainSync{model.pendingRecordOpen="file" to sqlCreated.id}
                compose.waitUntil(30000){compose.onAllNodesWithTag("goal.name").fetchSemanticsNodes().any{it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString{t->t.text}=="Recovered create"}}
                compose.onNodeWithTag("goal.name").assertTextEquals("Recovered create")
                assertNull(model.store.reader.file(w.id,sqlCreated.id))
                offline=true;val reopened=GoalsController(api,w.id,w.appNpub,c.signerNpub,local){true};reopened.refresh();assertEquals(2,reopened.state.value.goals.size);assertNotNull(reopened.state.value.error)
                assertEquals(legacy.versionId,originalApi.readLegacyGoal(w.id,w.appNpub,legacy.id).versionId)
                context.getExternalFilesDir(null)!!.resolve("relational-goals${BuildConfig.VERSION_CODE}.json").writeText(jsonObjectOf("versionCode" to BuildConfig.VERSION_CODE,"fixture" to "synthetic native-v1; real Android signer/controller/SQLite","goals" to 2,"retainedFileId" to legacy.id,"historyRows" to tables.getValue("goal_history").size,"offlineCache" to true,"receiptRecovery" to true,"coldReferenceNativeView" to true).toString())
                model.sampleTower!!.nativeRelationalFixture=null
            }
        }
    }
}
