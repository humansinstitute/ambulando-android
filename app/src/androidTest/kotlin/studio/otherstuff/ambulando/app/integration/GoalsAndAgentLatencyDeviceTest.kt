package studio.otherstuff.ambulando.app.integration

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.chat.*
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.*
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.core.goals.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GoalsAndAgentLatencyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private class Memory : VaultSecretStore {
        private var data: ByteArray? = null
        override fun read() = data?.copyOf()
        override fun write(bytes: ByteArray) { data = bytes.copyOf() }
    }
    @Test fun workspaceSqlGoalsAndLocalAgentSelection() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"goals-agent-device-${java.util.UUID.randomUUID()}").also{it.mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getCacheDir()=File(root,"cache").also{it.mkdirs()}
        }
        val identity=IdentityService(File(root,"vault"),Memory())
        val npub=runBlocking{identity.importIdentity(Bech32.encode("nsec",ByteArray(32){29}),"123456").npub!!}
        val tower=SampleTower(File(root,"tower")).also{it.prepare(npub)}
        val release=CompletableDeferred<Unit>(); var blocked=false; var started=false
        var home:String?=null;var sqlTables:Map<String,List<JsonObject>>?=null
        val sqlId="11111111-1111-4111-8111-111111111111"
        val transport=object:HttpTransport {
            override suspend fun send(request:HttpRequest):HttpResponse {
                if(sqlTables!=null && request.url.contains("/db/native-v1/")) {
                    val body=Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
                    fun reply(value:JsonObject)=HttpResponse(200,value.toString().toByteArray())
                    if(request.url.endsWith("/status"))return reply(jsonObjectOf("version" to 1,"authority" to if(body.string("channel_id")==home)jsonObjectOf("mode" to "active")else null))
                    if(request.url.endsWith("/read")) {
                        if(body.string("id")!=sqlId)return HttpResponse(404,"{\"code\":\"row_not_found\"}".toByteArray())
                        return reply(jsonObjectOf("version" to 1,"channel_id" to home,"row" to sqlTables!!.getValue("goals").single()))
                    }
                    return reply(jsonObjectOf("version" to 1,"generation" to "9","rows" to JsonArray(sqlTables!!.getValue(body.string("table")!!)),"has_more" to false))
                }
                if(blocked && request.url.substringBefore('?').endsWith("/files")) { started=true; release.await() }
                return tower.send(request)
            }
        }
        lateinit var model:AppModel
        compose.runOnUiThread { model=AppModel(AppEnvironment.Sample(context),context,AppSessionDependencies(identity,transport,tower.events),restoreInteractiveIdentity=false) }
        try {
            compose.waitUntil(30000){model.active?.validated==true}
            compose.runOnIdle{model.selectWorkspace(model.workspaces.first{it.id==SampleIds.STUDIO})}
            compose.waitUntil(10000){model.workspace?.id==SampleIds.STUDIO && model.active?.validated==true}
            runBlocking{model.sharedEngine.waitUntilIdle()}
            val personal=model.goalsController()!!
            val shared=runBlocking {
                personal.refresh();if(personal.state.value.space is GoalsSpaceState.NeedsSetup)personal.setUp(null,emptyList())
                home=(personal.state.value.space as GoalsSpaceState.Ready).channel.id
                val w=model.workspace!!
                model.fileNappletAPI(model.connection!!).createGoal(w.id,w.appNpub,SampleIds.GENERAL,Goal.create("Shared channel goal","Preserved legacy"))
            }
            val rootJson=Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("goals/relational-current-v1.json").bufferedReader().use{it.readText()}).jsonObject
            sqlTables=rootJson.getValue("tables").jsonObject.mapValues{(_,v)->v.jsonArray.map{entry->JsonObject(entry.jsonObject.toMutableMap().apply{
                put("workspace_id",JsonPrimitive(SampleIds.STUDIO));put("channel_id",JsonPrimitive(home!!));put("owner_npub",JsonPrimitive(npub))
            })}}
            runBlocking { personal.refresh() }
            Assert.assertNull(personal.state.value.error)
            Assert.assertEquals(listOf(sqlId),personal.state.value.goals.map{it.id})
            val all=model.goalsController(true)!!
            runBlocking { all.refresh() }
            Assert.assertNull(all.state.value.error)
            Assert.assertEquals(setOf(sqlId,shared.id),all.state.value.goals.map{it.id}.toSet())
            val showGoals=mutableStateOf(true)
            var picked:studio.otherstuff.ambulando.core.chat.Mention?=null
            // The real workspace agent directory is already local before typing.
            val agent=runBlocking{model.mentionCandidates("agent:")}.first()
            val text=mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
            val mentions=mutableStateOf(emptyList<studio.otherstuff.ambulando.core.chat.Mention>())
            var start=System.nanoTime()
            compose.setContent {
                CompositionLocalProvider(LocalAppServices provides AppServices(null,{}, {}, {}, {_,_->},true,true)) {
                AmbulandoTheme {
                    if(showGoals.value)GoalsScreen(model) else studio.otherstuff.ambulando.app.ui.work.MentionTextEntry(
                        text.value,{text.value=it},mentions.value,{mentions.value=it;picked=it.singleOrNull()},
                        model::mentionCandidates,"Message",lookupBoundary=model.activeBoundaryId,localLookup=model::localMentionCandidates)
                }
                }
            }
            compose.waitUntil(10000){compose.onAllNodesWithText("Shared channel goal").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Shared normalized goal").assertIsDisplayed()
            compose.onNodeWithTag("goals.error").assertDoesNotExist()
            val evidence=File(base.filesDir,"goals109-evidence").also{it.mkdirs()}
            fun screenshot(name:String)=File(evidence,name).outputStream().use{out->InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)}
            screenshot("workspace-goals.png")
            // Fresh query/boundary ensures discovery has not already warmed this lookup.
            compose.runOnIdle{
                blocked=true;start=System.nanoTime();showGoals.value=false
                val query="@"+agent.mention.label
                text.value=androidx.compose.ui.text.input.TextFieldValue(query,androidx.compose.ui.text.TextRange(query.length))
            }
            compose.waitUntil(1000){compose.onAllNodesWithText(agent.mention.label).fetchSemanticsNodes().isNotEmpty()}
            val firstRowMs=(System.nanoTime()-start)/1_000_000
            compose.waitUntil(5000){started}
            compose.onNodeWithText(agent.mention.label).assertIsDisplayed()
            println("AGENT_MENTION elapsedMs="+firstRowMs+" discoveryBlocked="+started)
            Assert.assertFalse(release.isCompleted)
            screenshot("agent-local-while-blocked.png")
            compose.onNodeWithText(agent.mention.label).performClick()
            Assert.assertEquals(agent.mention,picked)
            Assert.assertEquals(agent.mention.token,studio.otherstuff.ambulando.core.chat.MentionCodec.encode(text.value.text.trim(),mentions.value).body)
        } finally {
            blocked=false;release.complete(Unit)
            compose.runOnUiThread{(AppModel::class.java.getDeclaredField("viewModelScope").apply{isAccessible=true}.get(model) as CoroutineScope).cancel()}
            runBlocking{model.runtimes.close()};model.sharedEngine.close();model.catalog.close()
            root.deleteRecursively()
        }
    }
}
