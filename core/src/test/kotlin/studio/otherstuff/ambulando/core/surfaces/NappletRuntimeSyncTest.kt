package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

/** Real SyncEngine, identity signing and local projection; explicitly synthetic authority, no live API claim. */
class NappletRuntimeSyncTest {
    private val session="0123456789abcdef0123456789abcdef"
    private fun html(id: String,version: String="1.0.0")=requireNotNull(javaClass.getResourceAsStream("/napplet-runtime-v3/cartridge.html")).bufferedReader().use {it.readText()}.replace("field-notes",id).replace("1.0.0",version)
    private class Harness(val fixture: NappletRuntimeFixture,val runtime: NappletRuntimeSync,val sync: WorkspaceSync,val engine: SyncEngine,val store: LocalStore,val requests: MutableList<HttpRequest>,val connection: ConnectionRecord,val workspace: WorkspaceRecord,var current: Boolean=true,var channelOverride: JsonObject?=null)
    private suspend fun harness(): Harness {
        val tower=SampleTower(); tower.prepare(TestKeys.NPUB)
        val fixture=NappletRuntimeFixture(SampleIds.STUDIO,SampleIds.APP); val requests=mutableListOf<HttpRequest>(); val uploads=mutableSetOf<String>(); lateinit var h: Harness
        val wire=object: HttpTransport {override suspend fun send(request: HttpRequest): HttpResponse {
            requests+=request
            if(request.header("Authorization")==null) {
                require(request.method=="PUT" && java.net.URI(request.url).host==SampleHosts.OBJECTS && request.url in uploads)
                return tower.send(request)
            }
            val auth=tower.auth.verify(request); require(auth is SampleAuth.Result.Valid)
            h.channelOverride?.let { channel -> if(request.method=="GET" && java.net.URI(request.url).path==TowerAPI.base(SampleIds.STUDIO)+"/channels/"+NappletRuntimeProtocol.text(channel,"id")) return HttpResponse(200,buildJsonObject{put("channel",channel)}.toString().toByteArray()) }
            val response=fixture.handle(request,auth.npub) ?: tower.send(request)
            if(request.method=="POST" && java.net.URI(request.url).path.endsWith("/storage/prepare") && response.status in 200..299)
                uploads+=NappletRuntimeProtocol.text(GenericJson.decode(response.body),"upload_url")
            return response
        }}
        val store=LocalStore(tempDir()); val engine=SyncEngine(); val identity=makeIdentity()
        val sync=WorkspaceSync(store,engine,identity,wire)
        val connection=ConnectionRecord.create(tower.origin,TestKeys.NPUB,SampleIds.APP)
        val workspace=TowerAPI(TowerClient(tower.origin,tower,identity)).workspaces().first {it.id==SampleIds.STUDIO}
        val runtime=NappletRuntimeSync(sync,connection,workspace) {h.current}
        h=Harness(fixture,runtime,sync,engine,store,requests,connection,workspace);return h
    }
    private suspend fun Harness.close(){runtime.clear();engine.cancelAll();engine.close();store.close()}
    private fun request(operation: String,revision: Long=0,value: JsonObject=buildJsonObject {put("text","zero false");put("zero",0);put("false",false)}): JsonObject=buildJsonObject {
        put("type","wingman-napplet-data");put("version",3);put("session",session);put("requestId",UUID.randomUUID().toString());put("operation",operation);put("collection","entries")
        if(operation=="list"){put("limit",1);put("cursor",JsonNull)}else{put("record_id","note");if(operation!="get"){put("base_revision",revision);if(operation!="delete")put("value",value)}}
    }
    @Test fun durableBundleAndCollectionReadSurviveNewRuntimeAndDeniedNetwork(): Unit=runBlocking {
        val h=harness()
        try {
            val a=h.fixture.publish(html("cached-"+UUID.randomUUID()),TestKeys.NPUB)
            h.runtime.refresh(); h.runtime.open(a.id)
            val read=request("list")
            h.runtime.execute(a,read)
            h.runtime.execute(a,request("create"))
            val reopened=NappletRuntimeSync(h.sync,h.connection,h.workspace) { h.current }
            h.fixture.deny=true
            reopened.hydrate()
            assertEquals(a,reopened.state.value.installations.single())
            assertEquals(html(a.id),reopened.cachedOpen(a.id)!!.second)
            val cached=reopened.cachedRead(a,read)!!
            assertEquals("note",cached.getValue("records").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(1,cached.getValue("records").jsonArray.single().jsonObject.getValue("revision").jsonPrimitive.int)
            h.current=false
            assertTrue(runCatching {reopened.cachedOpen(a.id)}.isFailure)
            h.current=true
            reopened.forgetLocal(a.id)
            assertNull(reopened.cachedOpen(a.id))
            assertNull(NappletRuntimeSync(h.sync,h.connection.copy(id=UUID.randomUUID().toString()),h.workspace) {true}.cachedOpen(a.id))
            reopened.clear()
        } finally {h.close()}
    }
    @Test fun oldReleaseInvalidationCannotEvictNewVerifiedBundle(): Unit=runBlocking {
        val h=harness()
        try {
            val old=h.fixture.publish(html("versioned-"+UUID.randomUUID()),TestKeys.NPUB)
            h.runtime.refresh();h.runtime.open(old.id)
            val fresh=h.fixture.publish(html(old.id,"1.0.1"),TestKeys.NPUB)
            h.runtime.open(fresh.id)
            h.runtime.forgetLocal(old.id,old)
            assertEquals(fresh,h.runtime.cachedOpen(old.id)!!.first)
            assertTrue(h.runtime.cachedOpen(old.id)!!.second.contains("1.0.1"))
            h.runtime.forgetLocal(fresh.id,fresh)
            assertNull(h.runtime.cachedOpen(fresh.id))
        } finally {h.close()}
    }
    @Test fun twoUnknownAppsSaveReopenCodeUpdateHistoryKeepReinstallAndDelete(): Unit=runBlocking {
        val h=harness()
        try {
            val a=h.fixture.publish(html("app-"+UUID.randomUUID()),TestKeys.NPUB); val b=h.fixture.publish(html("app-"+UUID.randomUUID()),TestKeys.NPUB)
            h.runtime.refresh();assertEquals(setOf(a.id,b.id),h.runtime.state.value.installations.map {it.id}.toSet())
            assertEquals(a,h.runtime.open(a.id).first)
            val saved=h.runtime.execute(a,request("create"));val record=saved.getValue("record").jsonObject;val cid=NappletRuntimeProtocol.text(record,"collection_id")
            assertEquals(record,h.runtime.state.value.records.values.single());assertEquals(1,record.getValue("revision").jsonPrimitive.int)
            assertEquals(saved,h.runtime.execute(a,request("get")))
            assertEquals(0,h.runtime.execute(b,request("list")).getValue("records").jsonArray.size)
            val before=h.fixture.history(cid,"note");val update=h.fixture.publish(html(a.id,"1.0.1"),TestKeys.NPUB)
            val reopened=h.runtime.open(a.id).first;assertEquals(update,reopened)
            assertEquals(saved,h.runtime.execute(reopened,request("get")));assertEquals(before,h.fixture.history(cid,"note"))
            // A dirty compatible old frame may finish its save while automatic replacement is deferred.
            val updated=h.runtime.execute(a,request("update",1));assertEquals(2,updated.getValue("record").jsonObject.getValue("revision").jsonPrimitive.int)
            val history=h.fixture.history(cid,"note");assertEquals(2,history.size)
            assertTrue(runCatching {h.runtime.execute(reopened,request("update",1))}.exceptionOrNull() is NappletRuntimeAPI.Conflict)
            val removed=h.runtime.remove(reopened,false);assertTrue(removed.removed)
            val freshRuntime=NappletRuntimeSync(h.sync,h.connection,h.workspace){h.current}
            val reinstalled=freshRuntime.install(reopened.manifest,reopened.bundle,TestKeys.NPUB)
            assertEquals(removed.revision+1,reinstalled.revision)
            assertEquals(updated,freshRuntime.execute(reinstalled,request("get")));assertEquals(history,h.fixture.history(cid,"note"))
            freshRuntime.remove(reinstalled,true);assertEquals(emptyList<JsonObject>(),h.fixture.history(cid,"note"));freshRuntime.clear()
            assertTrue(h.requests.all {it.header("Authorization")!=null && it.header("x-flightdeck-pg-app-npub")==SampleIds.APP})
        } finally {h.close()}
    }
    @Test fun namespaceContextGrantIntegrityAndUnexpectedRevisionFailClosed(): Unit=runBlocking {
        val h=harness()
        try {
            val a=h.fixture.publish(html("app-"+UUID.randomUUID()),TestKeys.NPUB)
            h.fixture.corruptBundle=true;assertTrue(runCatching {h.runtime.open(a.id)}.isFailure);h.fixture.corruptBundle=false
            h.runtime.execute(a,request("list"))
            h.fixture.wrongCollectionNamespace=true;assertTrue(runCatching {h.runtime.authority(a)}.isFailure);h.fixture.wrongCollectionNamespace=false
            h.fixture.jumpRevision=true;assertTrue(runCatching {h.runtime.execute(a,request("create"))}.isFailure);assertTrue(h.runtime.state.value.records.isEmpty());h.fixture.jumpRevision=false
            h.current=false;val count=h.requests.size;assertTrue(runCatching {h.runtime.execute(a,request("get"))}.isFailure);assertEquals(count,h.requests.size);h.current=true
            h.fixture.deny=true;assertTrue(runCatching {h.runtime.authority(a)}.isFailure)
            h.runtime.clear();assertTrue(h.runtime.state.value.records.isEmpty());assertTrue(h.runtime.state.value.installations.isEmpty())
        } finally {h.close()}
    }
    @Test fun uncertainMutationIsNotRetriedOrAcknowledged(): Unit=runBlocking {
        val h=harness()
        try {
            val a=h.fixture.publish(html("app-"+UUID.randomUUID()),TestKeys.NPUB)
            h.fixture.loseMutationResponse=true;assertTrue(runCatching {h.runtime.execute(a,request("create"))}.isFailure)
            assertEquals(1,h.requests.count {it.method=="PUT"});assertTrue(h.runtime.state.value.records.isEmpty())
            h.fixture.loseMutationResponse=false
            assertEquals(1,h.runtime.execute(a,request("get")).getValue("record").jsonObject.getValue("revision").jsonPrimitive.int)
        } finally {h.close()}
    }
    @Test fun audienceIsSeparateFromPersonalDataAndExplicitReadWriteGrants(): Unit=runBlocking {
        val h=harness()
        try {
            val a=h.fixture.publish(html("app-"+UUID.randomUUID()),TestKeys.NPUB)
            val saved=h.runtime.execute(a,request("create"));val cid=NappletRuntimeProtocol.text(saved.getValue("record").jsonObject,"collection_id")
            val otherIdentity=studio.otherstuff.ambulando.core.crypto.IdentityService(java.io.File(tempDir(),"vault"),MemorySecretStore())
            otherIdentity.importIdentity(TestKeys.otherNsec(),TestKeys.PIN)
            val otherConnection=ConnectionRecord.create(TowerOrigin(h.connection.origin),SampleSigners.otherNpub,SampleIds.APP)
            val other=NappletRuntimeSync(WorkspaceSync(h.store,h.engine,otherIdentity,h.sync.transport),otherConnection,h.workspace){true}
            other.refresh();assertTrue(other.state.value.installations.isEmpty());assertTrue(runCatching{other.open(a.id)}.isFailure)
            val shared=h.runtime.audience(a,listOf(TestKeys.NPUB,SampleSigners.otherNpub))
            other.refresh();assertEquals(listOf(a.id),other.state.value.installations.map{it.id})
            assertTrue(other.execute(shared,request("list")).getValue("records").jsonArray.isEmpty())
            val primary=h.runtime.collections(a).single{it["id"]==JsonPrimitive(cid)}
            assertEquals(JsonArray(emptyList()),primary["read_npubs"]);assertEquals(JsonArray(emptyList()),primary["write_npubs"])
            val readOnly=h.runtime.grants(shared,primary,listOf(SampleSigners.otherNpub),emptyList())
            other.select(shared,"entries",cid);assertEquals(saved,other.execute(shared,request("get")))
            val count=h.requests.count{it.method=="PUT"};assertTrue(runCatching{other.execute(shared,request("update",1))}.isFailure);assertEquals(count,h.requests.count{it.method=="PUT"})
            val writable=h.runtime.grants(shared,readOnly,emptyList(),listOf(SampleSigners.otherNpub))
            assertTrue(runCatching{other.authority(shared)}.isFailure) // changed grant snapshot must remount
            other.select(shared,"entries",cid)
            assertEquals(2,other.execute(shared,request("update",1)).getValue("record").jsonObject.getValue("revision").jsonPrimitive.int)
            h.runtime.grants(shared,writable,emptyList(),emptyList());assertTrue(runCatching{other.authority(shared)}.isFailure)
            other.clear()
        } finally {h.close()}
    }
    @Test fun explicitCompatibleImportPreservesSourceAndAllHistoryAndRejectsOverwrite(): Unit=runBlocking {
        val h=harness()
        try {
            val a=h.fixture.publish(html("app-"+UUID.randomUUID()),TestKeys.NPUB)
            val ordinary=h.sync.api(h.connection)
            val channel=ordinary.createDailyTracker(h.workspace.id,h.workspace.appNpub,TestKeys.NPUB,"<h1>Fixture self conversation</h1>").channel
            h.channelOverride=channel.raw
            val value=GenericJson.decode("{\"zero\":0,\"done\":false,\"é\":1,\"e\\u0301\":2,\"rows\":[null]}")
            val bytes=GenericJson.canonical(value).toByteArray();val stored=ordinary.store(h.workspace.id,h.workspace.appNpub,bytes,"application/json","Legacy.json",null){}
            val file=GenericJson.decode(ordinary.client.request(method="POST",path=TowerAPI.base(h.workspace.id)+"/channels/${channel.id}/files",appNpub=h.workspace.appNpub,jsonBody=jsonBody(buildJsonObject{put("storage_object_id",stored);put("display_name","Legacy.json");put("metadata",JsonObject(emptyMap()))}))).getValue("file").jsonObject
            val fileId=NappletRuntimeProtocol.text(file,"id");val before=ordinary.fileVersions(h.workspace.id,h.workspace.appNpub,fileId)
            h.fixture.registerSource(bytes.toString(Charsets.UTF_8),fileId,requireNotNull(before.currentVersionId))
            val preview=h.runtime.previewImport(fileId);assertEquals(value,preview.value)
            assertTrue(runCatching{h.runtime.importFile(a,"entries",preview,false)}.isFailure)
            assertEquals(JsonPrimitive(true),h.runtime.importFile(a,"entries",preview,true)["source_preserved"])
            val importedRequest=JsonObject(request("get")+("record_id" to JsonPrimitive("legacy-snapshot")))
            val imported=h.runtime.execute(a,importedRequest).getValue("record").jsonObject
            assertEquals(value,imported["data"]);assertEquals(preview.source,imported["provenance"])
            assertTrue(runCatching{h.runtime.importFile(a,"entries",preview,true)}.exceptionOrNull() is NappletRuntimeAPI.Conflict)
            assertEquals(before,ordinary.fileVersions(h.workspace.id,h.workspace.appNpub,fileId))
            h.runtime.remove(a,true);assertEquals(before,ordinary.fileVersions(h.workspace.id,h.workspace.appNpub,fileId))
        } finally {h.close()}
    }

    @Test fun activeExpandedReleaseLetsApprovedOldFrameSaveButNeverUseNewAccess(): Unit=runBlocking {
        val h=harness()
        try {
            val id="app-"+UUID.randomUUID();val original=html(id);val a=h.fixture.publish(original,TestKeys.NPUB)
            h.runtime.execute(a,request("create"))
            val declarations=a.manifest.getValue("collections").jsonObject
            val expanded=JsonObject(a.manifest+mapOf("bundle_version" to JsonPrimitive("2.0.0"),"collections" to JsonObject(declarations+("extra" to declarations.getValue("entries")))))
            val pending=h.fixture.publish(original.replace(GenericJson.canonical(a.manifest),GenericJson.canonical(expanded)),TestKeys.NPUB)
            assertEquals(a.manifest,pending.manifest)
            val active=h.runtime.approveRelease(pending);assertEquals(expanded,active.manifest)
            assertFalse(NappletRuntimeProtocol.permits(a.manifest,active.manifest));assertTrue(NappletRuntimeProtocol.permits(active.manifest,a.manifest))
            assertEquals(2,h.runtime.execute(a,request("update",1)).getValue("record").jsonObject.getValue("revision").jsonPrimitive.int)
            val count=h.requests.size
            assertTrue(runCatching{h.runtime.execute(a,JsonObject(request("list")+("collection" to JsonPrimitive("extra"))))}.isFailure)
            assertEquals(count,h.requests.size)
        } finally {h.close()}
    }

    @Test fun sameWorkspaceUUIDWithChangedServiceLosesApprovalAndMissingIdentityFailsClosed(): Unit=runBlocking {
        val h=harness();val directory=tempDir()
        try {
            val a=h.fixture.publish(html("app-"+UUID.randomUUID()),TestKeys.NPUB)
            val context=NappletRuntimeContext.create(h.connection,h.workspace)
            val approvals=NappletRuntimeApprovals(directory,context);assertTrue(approvals.remember(a))
            val identity=h.workspace.raw.getValue("identity").jsonObject
            val changed=h.workspace.copy(raw=JsonObject(h.workspace.raw+("identity" to JsonObject(identity+("workspace_service_npub" to JsonPrimitive(SampleIds.RELAY))))))
            val next=NappletRuntimeContext.create(h.connection,changed);assertNotEquals(context,next);assertEquals(context["workspaceId"],next["workspaceId"])
            assertFalse(NappletRuntimeApprovals(directory,next).approved(a))
            val missing=h.workspace.copy(raw=JsonObject(h.workspace.raw+("identity" to JsonObject(identity-"workspace_service_npub"))))
            assertTrue(runCatching{NappletRuntimeContext.create(h.connection,missing)}.isFailure)
            val otherTower=ConnectionRecord.create(TowerOrigin(h.connection.origin),TestKeys.NPUB,SampleIds.BEA)
            assertTrue(runCatching{NappletRuntimeContext.create(otherTower,h.workspace)}.isFailure)
        } finally {h.close();directory.deleteRecursively()}
    }

}
