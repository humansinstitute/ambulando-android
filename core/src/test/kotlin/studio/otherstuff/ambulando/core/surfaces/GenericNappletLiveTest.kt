package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.SampleSigners
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit opt-in core/API smoke using an authorized host broker, never an installed-native-login claim. */
class GenericNappletLiveTest {
    @Test fun brokerBackedExistingFilesCanonicalCASAndCodeUpdate()=runBlocking {
        val configPath=System.getenv("GENERIC_NAPPLET_LIVE_CONFIG")
        assumeTrue("Requires explicit ignored live-test configuration and current session capability broker",!configPath.isNullOrEmpty())
        val config=Json.parseToJsonElement(File(configPath!!).readText()).jsonObject
        val origin=GenericNapplet.text(config,"baseUrl"); val workspace=GenericNapplet.text(config,"workspaceId")
        val app=GenericNapplet.text(config,"appNpub"); val viewer=GenericNapplet.text(config,"viewer")
        val channel=GenericNapplet.text(config,"channelId"); val evidence=File(GenericNapplet.text(config,"evidenceDirectory"))
        require(GenericNapplet.uuid.matches(workspace) && GenericNapplet.uuid.matches(channel) && evidence.isDirectory)
        val cli=GenericNapplet.text(config,"brokerCli"); val network=OkHttpTransport()
        var requests=0
        val preparedUploads=mutableSetOf<String>()
        val transport=object: HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if(request.header("Authorization")==null) {
                    // WorkAPI already validated the signed preparation's presigned upload through TransferPolicy.
                    require(request.method=="PUT" && java.net.URI(request.url).scheme=="https" && request.url in preparedUploads)
                    return network.send(request)
                }
                require(request.url.startsWith(origin+"/api/v4/"))
                val body=request.body?.let { File.createTempFile("generic-live-body-",".json",evidence).also { f -> f.writeBytes(it) } }
                try {
                    val args=mutableListOf("bun",cli,"nip98","--url",request.url,"--method",request.method)
                    body?.let { args+=listOf("--body-file",it.absolutePath) }
                    val process=ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); error("Broker signing deadline") }
                    require(process.exitValue()==0) { "Broker signing unavailable" }
                    val output=process.inputStream.bufferedReader().readText()
                    val signed=Json.parseToJsonElement(output).jsonObject
                    val authorization=GenericNapplet.text(signed,"authorization")
                    val event=Json.parseToJsonElement(java.util.Base64.getDecoder().decode(authorization.removePrefix("Nostr ")).toString(Charsets.UTF_8)).jsonObject
                    require(studio.otherstuff.ambulando.core.nostr.NostrKeys.npub(GenericNapplet.text(event,"pubkey"))==viewer)
                    requests++
                    val response=network.send(request.withHeader("Authorization",authorization))
                    if(request.method=="POST" && java.net.URI(request.url).path.endsWith("/storage/prepare") && response.status in 200..299) {
                        val prepared=Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                        prepared["upload_url"]?.jsonPrimitive?.contentOrNull?.let { preparedUploads+=it }
                    }
                    return response
                } finally { body?.delete() }
            }
        }
        // The test transport replaces this synthetic signature with the broker signature over the exact request.
        val api=TowerAPI(TowerClient(TowerOrigin(origin),transport,SampleSigners.primary))
        val root=TowerAPI.base(workspace)
        suspend fun file(name: String,bytes: ByteArray,mime: String,meta: JsonObject): FileRecord {
            val objectId=api.store(workspace,app,bytes,mime,name,null) { }
            return FileRecord.parse(Json.parseToJsonElement(api.client.request(method="POST",path="$root/channels/$channel/files",appNpub=app,jsonBody=jsonBody(buildJsonObject { put("storage_object_id",objectId); put("display_name",name); put("metadata",meta) })).toString(Charsets.UTF_8)).jsonObject.getValue("file").jsonObject)
        }
        val id="native-smoke-"+UUID.randomUUID(); val name="r"+UUID.randomUUID().toString().replace("-", "").take(20)
        val data=file("Native generic smoke data.json","{}".toByteArray(),"application/json",JsonObject(emptyMap()))
        val manifest=buildJsonObject { put("version",2); put("app_id",id); put("title","Native generic API smoke"); put("icon","🌱"); put("bindings",buildJsonObject { put(name,buildJsonObject { put("capability","private-json-file"); put("version",1); put("operations",JsonArray(listOf(JsonPrimitive("read"),JsonPrimitive("save")))) }) }) }
        val meta=buildJsonObject { put("napplet",buildJsonObject { put("version",2); put("app_id",id); put("bindings",buildJsonObject { put(name,buildJsonObject { put("file_id",data.id) }) }) }) }
        val html="<script type=\"application/json\" id=\"wingman-napplet-manifest\">$manifest</script><h1>Original</h1>"
        val code=file("Native generic API smoke.html",html.toByteArray(),"text/html",meta)
        val binding=GenericNapplet.parse(html,meta)!!
        val proof=linkedMapOf<String,JsonElement>("evidence" to JsonPrimitive("real broker-backed Kotlin API smoke; not installed-native authentication"),"appId" to JsonPrimitive(id),"codeFile" to JsonPrimitive(code.id),"dataFile" to JsonPrimitive(data.id))
        fun checkpoint(stage: String) { proof["stage"]=JsonPrimitive(stage); File(evidence,"generic-live-native-core-checkpoints.json").writeText(JsonObject(proof).toString()) }
        checkpoint("created")
        suspend fun host(source: String=html): GenericNappletTransport {
            val version=api.genericNappletHead(workspace,app,code.id).currentVersionId!!
            return GenericNappletTransport(api,workspace,app,viewer,code.id,version,source,meta,binding,{true}) { error("Not aggregate") }
        }
        val runtime=host(); val initial=runtime.read(); assertTrue(initial.canWrite)
        val value=GenericJson.decode("{\"zero\":0,\"answered\":false,\"unanswered\":null}")
        // Honor the host broker's per-minute quota between user operations; never retry a mutation.
        delay(61000)
        val saved=runtime.save(initial.version,value); assertArrayEquals(GenericJson.canonical(value).toByteArray(),saved.bytes)
        proof["savedVersion"]=JsonPrimitive(saved.version); proof["canonicalSha256"]=JsonPrimitive(studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(saved.bytes)); checkpoint("canonicalCASConfirmed")
        delay(61000)
        assertEquals(value,host().read().value); checkpoint("reopenConfirmed")
        val before=api.fileVersions(workspace,app,data.id)
        val version=api.genericNappletHead(workspace,app,code.id).currentVersionId!!
        val updated=html.replace("Original","Updated")
        val objectId=api.store(workspace,app,updated.toByteArray(),"text/html","native-smoke.html",null) { }
        assertTrue(api.addFileVersion(workspace,app,code.id,version,objectId,UUID.randomUUID().toString()) is VersionOutcome.Saved)
        assertTrue(runCatching { runtime.authorize() }.isFailure)
        assertEquals(value,host(updated).read().value); assertEquals(before,api.fileVersions(workspace,app,data.id)); checkpoint("codeUpdatePreservedDataHistory")
        delay(61000)
        val aggregateId="native-totals-"+UUID.randomUUID()
        val aggregateManifest=buildJsonObject { put("version",2); put("app_id",aggregateId); put("title","Native aggregate API smoke"); put("icon","📊"); put("bindings",buildJsonObject { put("totals",buildJsonObject { put("capability","workspace-aggregate"); put("version",1); put("operations",JsonArray(listOf(JsonPrimitive("read")))) }) }) }
        val aggregateMeta=buildJsonObject { put("napplet",buildJsonObject { put("version",2); put("app_id",aggregateId); put("bindings",buildJsonObject { put("totals",buildJsonObject { put("workspace_id",workspace) }) }) }) }
        val aggregateHtml="<script type=\"application/json\" id=\"wingman-napplet-manifest\">$aggregateManifest</script><h1>Totals</h1>"
        val aggregateCode=file("Native aggregate API smoke.html",aggregateHtml.toByteArray(),"text/html",aggregateMeta)
        val aggregateVersion=api.genericNappletHead(workspace,app,aggregateCode.id).currentVersionId!!
        val aggregate=GenericNappletTransport(api,workspace,app,viewer,aggregateCode.id,aggregateVersion,aggregateHtml,aggregateMeta,GenericNapplet.parse(aggregateHtml,aggregateMeta)!!,{true}) { range ->
            val response=Json.parseToJsonElement(api.client.request(path="$root/workspace-report",query=listOf("range" to range,"top_n" to "10"),appNpub=app).toString(Charsets.UTF_8)).jsonObject
            WorkspaceReport.response(response,config.getValue("identity").jsonObject,workspace,range)
        }.aggregate("30d")
        WorkspaceReport.model(aggregate,workspace,"30d")
        File(evidence,"generic-live-native-core.json").writeText(buildJsonObject {
            put("evidence","real NStack broker-backed Kotlin core/API smoke; not installed native authentication")
            put("appId",id); put("codeFile",code.id); put("dataFile",data.id); put("savedVersion",saved.version)
            put("canonicalSha256",studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(saved.bytes)); put("requests",requests)
            put("codeUpdatePreservedDataHistory",true); put("aggregateCodeFile",aggregateCode.id); put("liveAggregateRange","30d"); put("aggregateValidated",true)
        }.toString())
    }
}
