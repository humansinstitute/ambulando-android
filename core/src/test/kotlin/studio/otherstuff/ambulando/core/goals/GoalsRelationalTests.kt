package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.SampleSigners
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import studio.otherstuff.ambulando.core.store.*

class GoalsRelationalTests {
    private val id="11111111-1111-4111-8111-111111111111"
    private fun api(transport:HttpTransport)=TowerAPI(TowerClient(TowerOrigin("https://fixture.invalid"),transport,SampleSigners.primary))
    private fun response(value:JsonObject)=HttpResponse(200,value.toString().toByteArray(),mapOf("Content-Type" to "application/json"))
    private fun repository(api:TowerAPI,observed:Boolean=false)=GoalsRelational(api,TestKeys.NPUB,id,TestKeys.NPUB,TestKeys.NPUB,hadAuthority={observed})
    @Test fun nativeReadPostsUseReadReadinessAndOnlyExactKnownRoutes():Unit=runBlocking {
        val paths=mutableListOf<String>()
        val reads=object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse{paths+=request.url;return response(jsonObjectOf("version" to 1,"authority" to null))}}
        val writes=object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse=error("write gated")}
        val a=api(GoalsTransport(reads,writes));val repo=repository(a)
        assertEquals("legacy",repo.mode(id));assertTrue(paths.single().endsWith("/status"));a.client.request(method="POST",path="/api/v4/workspaces/${TestKeys.NPUB}/apps/${TestKeys.NPUB}/db/native-v1/$id/read",jsonBody="{}".toByteArray());assertTrue(paths.last().endsWith("/read"))
        assertThrows(IllegalStateException::class.java){runBlocking{a.client.request(method="POST",path="/api/v4/arbitrary/status")}}
        assertThrows(IllegalStateException::class.java){runBlocking{a.client.request(method="POST",path="/api/v4/workspaces/a/apps/b/db/native-v1/$id/batch")}}
    }
    @Test fun missingAuthorityAfterObservedRelationalNeverDowngrades():Unit=runBlocking {
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest)=response(jsonObjectOf("version" to 1,"authority" to null))})
        assertThrows(GoalSaveFailed::class.java){runBlocking{GoalsRelational(a,TestKeys.NPUB,id,TestKeys.NPUB,TestKeys.NPUB,hadAuthority={true},hadChannelAuthority={it==id}).mode(id)}}
    }
    @Test fun unavailableNamespaceAfterObservedAuthorityRetainsContentError():Unit=runBlocking {
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest)=HttpResponse(404,"{\"code\":\"namespace_not_available\"}".toByteArray())})
        assertThrows(GoalSaveFailed::class.java){runBlocking{repository(a,true).mode(id)}}
        assertEquals("legacy",repository(a,false).mode(id))
    }
    @Test fun observedPersonalAuthorityDoesNotMakeOrdinaryChannelAnOutage():Unit=runBlocking {
        val channels=mutableListOf<String>()
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse {
            val channel=Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject.string("channel_id")!!
            channels+=channel
            return response(jsonObjectOf("version" to 1,"authority" to if(channel==id) jsonObjectOf("mode" to "active") else null))
        }})
        val repo=repository(a,true)
        assertEquals("relational",repo.mode(id))
        assertEquals("legacy",repo.mode("22222222-2222-4222-8222-222222222222"))
        assertEquals(2,channels.size)
    }

    @Test fun channelAuthorityPersistsAcrossRepositoryRecreationAndNeverDowngradesItsChannel():Unit=runBlocking {
        var active=true;val remembered=mutableSetOf<String>()
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest)=response(jsonObjectOf("version" to 1,"authority" to if(active)jsonObjectOf("mode" to "active")else null))})
        fun repo()=GoalsRelational(a,TestKeys.NPUB,id,TestKeys.NPUB,TestKeys.NPUB,
            rememberChannelAuthority={remembered+=it},hadChannelAuthority={it in remembered})
        assertEquals("relational",repo().mode(id));active=false
        assertThrows(GoalSaveFailed::class.java){runBlocking{repo().mode(id)}}
        assertEquals("legacy",repo().mode("22222222-2222-4222-8222-222222222222"))
    }

    @Test fun namespaceDisappearanceAfterInMemoryObservationNeverDowngradesOtherChannels():Unit=runBlocking {
        var missing=false
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest)=if(missing)HttpResponse(404,"{}".toByteArray())else response(jsonObjectOf("version" to 1,"authority" to jsonObjectOf("mode" to "active")))})
        val repo=repository(a);assertEquals("relational",repo.mode(id));missing=true
        assertThrows(GoalSaveFailed::class.java){runBlocking{repo.mode("22222222-2222-4222-8222-222222222222")}}
    }
    @Test fun absentAuthorityIsNotLegacyWhenFencedOrMalformed():Unit=runBlocking {
        var reply=jsonObjectOf("version" to 1,"authority" to null,"fence" to jsonObjectOf("active" to true))
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest)=response(reply)})
        assertEquals("frozen",repository(a).mode(id))
        reply=jsonObjectOf("version" to 1)
        assertThrows(TowerError.Malformed::class.java){runBlocking{repository(a).mode(id)}}
        reply=jsonObjectOf("version" to 1,"authority" to "unexpected")
        assertThrows(TowerError.Malformed::class.java){runBlocking{repository(a).mode(id)}}
    }

    @Test fun snapshotRestartsAcrossTablesAndChecksFinalGeneration():Unit=runBlocking {
        var changed=false;var checks=0
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse{
            val body=Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
            if(body.string("table")=="goal_checkpoints" && !changed){changed=true;return HttpResponse(409,"{\"code\":\"snapshot_changed\"}".toByteArray())}
            if(flexibleInt(body["limit"])==1)checks++
            return response(jsonObjectOf("version" to 1,"generation" to if(changed)"2" else "1","rows" to JsonArray(emptyList()),"has_more" to false,"next_after" to null))
        }})
        val result=repository(a).snapshot(id);assertEquals("2",result.generation);assertEquals(1,checks);assertEquals(4,result.tables.size)
    }
    @Test fun normalizedRowsKeepStableFileMentionIdentityAndExtensions() {
        val repo=repository(api(object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse=error("no network")}))
        val scope=jsonObjectOf("workspace_id" to id,"channel_id" to id,"owner_npub" to TestKeys.NPUB,"goal_id" to id)
        val parent=JsonObject(scope+jsonObjectOf("id" to id,"revision" to 4,"name" to "SQL goal","description" to "Retained identity","status" to "active","created_at" to "2026-11-27T00:00:00Z","updated_at" to "2026-11-27T00:00:00Z","default_channel_id" to id,"extensions" to jsonObjectOf("future" to true).toString()))
        val cp=JsonObject(scope+jsonObjectOf("id" to "$id:cp:cp-text","checkpoint_id" to "cp-text","title" to "Step","position" to 0,"reached" to true,"extensions" to jsonObjectOf("future_cp" to "kept").toString()))
        val pin=JsonObject(scope+jsonObjectOf("id" to "$id:pin:file:$id","lane" to "pin","kind" to "file","record_id" to id,"position" to 0,"extensions" to jsonObjectOf("future_pin" to 9).toString()))
        val result=repo.documents(GoalsRelational.Snapshot("1",mapOf("goals_authority" to listOf(jsonObjectOf("contract_version" to 1,"schema_version" to "ambulando-goals-relational-1")),"goals" to listOf(parent),"goal_checkpoints" to listOf(cp),"goal_associations" to listOf(pin)))).single()
        assertEquals(id,result.id);assertEquals("sql:4",result.versionId);assertEquals("cp-text",result.goal.checkpoints.single().id)
        assertEquals(JsonPrimitive(true),result.goal.extra["future"]);assertEquals(JsonPrimitive("kept"),result.goal.checkpoints.single().extra["future_cp"])
        assertEquals(id,result.goal.defaultChannelId);assertEquals(GoalLinkType.FILE,result.goal.pinnedRefs.single().type);assertTrue(result.goal.links.isEmpty())
        assertTrue(GoalFiles.isGoal(result.file))
    }
    @Test fun committedCreateWithDroppedResponseReplaysExactDurableRequestAfterRelaunch():Unit=runBlocking {
        val directory=java.nio.file.Files.createTempDirectory("goals-pending-recovery").toFile()
        try {
            val receipts=hashMapOf<String,String>();var drop=true;var inserts=0
            val transport=object:HttpTransport {override suspend fun send(request:HttpRequest):HttpResponse {
                val text=request.body!!.toString(Charsets.UTF_8);val body=Json.parseToJsonElement(text).jsonObject
                val operation=body.string("operation_id")!!
                if(receipts.putIfAbsent(operation,text)==null)inserts++ else assertEquals(receipts[operation],text)
                if(drop){drop=false;throw java.io.IOException("response lost after commit")}
                return response(jsonObjectOf("version" to 1,"operation_id" to operation))
            }}
            fun repo(store:studio.otherstuff.ambulando.core.store.LocalStore)=GoalsRelational(api(transport),TestKeys.NPUB,id,TestKeys.NPUB,TestKeys.NPUB,
                loadPending={store.reader.state("pending")?.let{Json.parseToJsonElement(it).jsonObject}},savePending={store.writer.setState("pending",it?.toString())})
            studio.otherstuff.ambulando.core.store.LocalStore(directory).use { store ->
                assertThrows(java.io.IOException::class.java){runBlocking{repo(store).submit(id,listOf(jsonObjectOf("kind" to "insert","table" to "goals","id" to id,"data" to jsonObjectOf("name" to "Original draft"))))}}
                assertNotNull(store.reader.state("pending"))
            }
            studio.otherstuff.ambulando.core.store.LocalStore(directory).use { store ->
                assertThrows(GoalSaveFailed::class.java){runBlocking{repo(store).submit(id,listOf(jsonObjectOf("kind" to "insert","table" to "goals","id" to "different","data" to jsonObjectOf("name" to "Must not duplicate"))))}}
                assertNull(store.reader.state("pending"));assertEquals(1,inserts)
            }
        }finally{directory.deleteRecursively()}
    }

    @Test fun stagingBeforeFenceKeepsLegacyButFencedOrObservedNeverDowngrades():Unit=runBlocking {
        var fenced=false
        val a=api(object:HttpTransport {override suspend fun send(request:HttpRequest)=response(jsonObjectOf("version" to 1,"authority" to jsonObjectOf("mode" to "staging","revision" to "1"),"fence" to jsonObjectOf("active" to fenced,"revision" to "2")))})
        assertEquals("legacy",repository(a).mode(id));fenced=true;assertEquals("frozen",repository(a).mode(id));fenced=false
        assertEquals("frozen",GoalsRelational(a,TestKeys.NPUB,id,TestKeys.NPUB,TestKeys.NPUB,hadChannelAuthority={it==id}).mode(id))
    }
    @Test fun sharedIndependentMapperGoldenPreservesNumericLexemesDatesAndOrderedChildren() {
        val root=Json.parseToJsonElement(javaClass.getResource("/goals/relational-current-v1.json")!!.readText()).jsonObject
        val tables=root.getValue("tables").jsonObject.mapValues{(_,v)->v.jsonArray.map{it.jsonObject}}
        val repo=GoalsRelational(api(object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse=error("no network")}),TestKeys.NPUB,id,TestKeys.NPUB,"npub1fixture")
        val doc=repo.documents(GoalsRelational.Snapshot("12",tables)).single()
        assertEquals("2026-12-01",doc.goal.startDate);assertEquals(listOf("cp-string","cp-last"),doc.goal.checkpoints.map{it.id})
        assertEquals("9007199254740993",doc.goal.extra.getValue("future").jsonObject.getValue("integer").jsonPrimitive.content)
        assertEquals("0.123456789012345678901",doc.goal.extra.getValue("future").jsonObject.getValue("decimal").jsonPrimitive.content)
        assertEquals(GoalLinkType.CHAT,doc.goal.links.single().type);assertEquals(GoalLinkType.DOC,doc.goal.pinnedRefs.single().type)
        val newer=tables.toMutableMap();newer["goals_authority"]=listOf(jsonObjectOf("contract_version" to 1,"schema_version" to "future-2"))
        assertThrows(GoalError.Unsupported::class.java){repo.documents(GoalsRelational.Snapshot("13",newer))}
    }
    @Test fun relationalCacheSurvivesAuthorityOutageWithoutOverwritingRetainedFileHistory():Unit=runBlocking {
        val h=studio.otherstuff.ambulando.core.sample.SampleHarness();val w=studio.otherstuff.ambulando.core.sample.SampleIds.STUDIO
        val directory=java.nio.file.Files.createTempDirectory("goals-sql-cache").toFile()
        try{LocalStore(directory).use{store->
            val local=GoalsLocalContent(store,w,TestKeys.NPUB)
            val seed=GoalsController(h.api,w,h.app,TestKeys.NPUB,local){true};seed.refresh();seed.setUp(null,emptyList())
            val legacy=seed.create(Goal.create("Old source","Retained original"));val source=store.reader.file(w,legacy.id)!!
            val sql=legacy.copy(file=FileRecord.parse(JsonObject(legacy.file.raw+("goals_authority" to JsonPrimitive("relational")))),versionId="sql:7",goal=Goal.create("Current SQL content","Retained current cache"))
            local.save(seed.state.value.copy(goals=listOf(sql)))
            assertEquals(source.storageObjectId,store.reader.file(w,legacy.id)!!.storageObjectId)
            val transport=object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse=if(request.url.contains("/db/native-v1/"))HttpResponse(404,"{\"code\":\"namespace_not_available\"}".toByteArray())else h.tower.send(request)}
            val a=TowerAPI(TowerClient(h.client.origin,transport,SampleSigners.primary));a.goalsRelational=GoalsRelational(a,TestKeys.NPUB,w,h.app,TestKeys.NPUB,hadAuthority={true})
            val opened=GoalsController(a,w,h.app,TestKeys.NPUB,local){true};opened.refresh()
            assertEquals("Current SQL content",opened.state.value.goals.single().goal.name);assertNotNull(opened.state.value.error)
            assertEquals("sql:7",local.load()!!.goals.single().versionId)
        }}finally{directory.deleteRecursively()}
    }

    @Test fun explicitAuthorizedSqlReferenceUsesActualRowAclWithoutRequiringPersonalHome():Unit=runBlocking {
        val root=Json.parseToJsonElement(javaClass.getResource("/goals/relational-current-v1.json")!!.readText()).jsonObject
        val tables=root.getValue("tables").jsonObject
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse {
            check(request.url.contains("/db/native-v1/")){"Explicit row access must not enumerate a personal home"}
            val body=Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
            return if(request.url.endsWith("/read"))response(jsonObjectOf("version" to 1,"channel_id" to id,"row" to tables.getValue("goals").jsonArray.single()))
            else response(jsonObjectOf("version" to 1,"generation" to "12","rows" to tables.getValue(body.string("table")!!),"has_more" to false,"next_after" to null))
        }})
        assertEquals("Shared normalized goal",repository(a).read(id).goal.name)
    }
    @Test fun deniedPrimarySqlReferencePreserves403WithoutPersonalDiscoveryOrFileFallback():Unit=runBlocking {
        var calls=0
        val a=api(object:HttpTransport{override suspend fun send(request:HttpRequest):HttpResponse{calls++;return HttpResponse(403,"{\"code\":\"permission_denied\"}".toByteArray())}})
        val failure=assertThrows(TowerError.Http::class.java){runBlocking{repository(a).read(id)}}
        assertEquals(403,failure.status);assertEquals(1,calls)
    }

}
