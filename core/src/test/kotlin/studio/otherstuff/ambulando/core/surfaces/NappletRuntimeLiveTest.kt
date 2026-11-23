package studio.otherstuff.ambulando.core.surfaces

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.SampleSigners
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicitly opted-in exact-byte broker-forwarded Kotlin API proof. No native login/store claim.
 * Only newly generated own disposable app Files/records in the authorized workspace/channel are mutated.
 */
class NappletRuntimeLiveTest {
    @Test fun ownDisposableAppsImmutableBundleCASHistoryUpdateAndRetainedReconnect(): Unit=runBlocking {
        val path=System.getenv("NAPPLET_RUNTIME_LIVE_CONFIG")
        assumeTrue("Requires explicit authorized ignored live configuration and active broker",!path.isNullOrEmpty())
        val config=GenericJson.decode(File(path!!).readBytes())
        val origin=NappletRuntimeProtocol.text(config,"baseUrl"); val workspace=NappletRuntimeProtocol.text(config,"workspaceId")
        val app=NappletRuntimeProtocol.text(config,"appNpub"); val viewer=NappletRuntimeProtocol.text(config,"viewer");val channel=NappletRuntimeProtocol.text(config,"channelId")
        val directory=File(NappletRuntimeProtocol.text(config,"evidenceDirectory"));require(directory.isDirectory && NostrKeys.isValidNpub(viewer))
        require(java.net.URI(origin).scheme=="https" && GenericNapplet.uuid.matches(workspace) && GenericNapplet.uuid.matches(channel))
        val cli=NappletRuntimeProtocol.text(config,"brokerCli");val network=OkHttpTransport();var requests=0
        val uploads=mutableSetOf<String>(); val storageURLs=mutableSetOf<String>(); val operations=mutableListOf<JsonObject>()
        val wire=object: HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if(request.header("Authorization")==null) {require(request.method=="PUT" && request.url in uploads && java.net.URI(request.url).scheme=="https");return network.send(request)}
                require(request.url.startsWith(origin+TowerAPI.base(workspace)+"/") || request.url in storageURLs)
                // Deliberate pacing; never retry a failed signing/mutation request or relax the broker's quota.
                delay(3500)
                val body=request.body?.let {data->File.createTempFile("v3-body-",".json",directory).also {it.writeBytes(data)}}
                try {
                    val args=mutableListOf("bun",cli,"nip98","--url",request.url,"--method",request.method);body?.let {args+=listOf("--body-file",it.absolutePath)}
                    val process=ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();error("Broker deadline")};require(process.exitValue()==0){"Broker unavailable or quota denied; request not replayed"}
                    val signed=GenericJson.decode(process.inputStream.bufferedReader().readText());val authorization=NappletRuntimeProtocol.text(signed,"authorization")
                    val event=GenericJson.decode(java.util.Base64.getDecoder().decode(authorization.removePrefix("Nostr ")))
                    require(NostrKeys.npub(NappletRuntimeProtocol.text(event,"pubkey"))==viewer)
                    requests++;val response=network.send(request.withHeader("Authorization",authorization))
                    operations+=buildJsonObject {put("method",request.method);put("path",java.net.URI(request.url).path);put("status",response.status)}
                    if(request.method=="POST" && java.net.URI(request.url).path.endsWith("/storage/prepare") && response.status in 200..299) {
                        val p=GenericJson.decode(response.body);p["upload_url"]?.jsonPrimitive?.contentOrNull?.let {uploads+=it}
                        val objectId=NappletRuntimeProtocol.text(p,"object_id");require(GenericNapplet.uuid.matches(objectId))
                        storageURLs+=listOf("", "/complete", "/content").map {origin+"/api/v4/storage/"+objectId+it}
                    }
                    return response
                } finally {body?.delete()}
            }
        }
        // Public synthetic test signer is replaced by the authorized broker over exact URL/method/body bytes.
        val client=TowerClient(TowerOrigin(origin),wire,SampleSigners.primary);val ordinary=TowerAPI(client);val runtime=NappletRuntimeAPI(client,workspace,app)
        runtime.installations() // authorization preflight; no mutation before this passes
        val template=requireNotNull(javaClass.getResourceAsStream("/napplet-runtime-v3/cartridge.html")).bufferedReader().use {it.readText()}
        suspend fun source(id: String,version: String): Pair<JsonObject,JsonObject> {
            val html=template.replace("field-notes",id).replace("1.0.0",version);val content=html.toByteArray();val manifest=NappletRuntimeProtocol.parse(html)
            val stored=ordinary.store(workspace,app,content,"text/html","$id.html",null){}
            val file=GenericJson.decode(client.request(method="POST",path=TowerAPI.base(workspace)+"/channels/$channel/files",appNpub=app,jsonBody=jsonBody(buildJsonObject {put("storage_object_id",stored);put("display_name","Disposable Android v3 API proof.html");put("metadata",buildJsonObject {put("html_artifact",true);put("napplet",buildJsonObject {put("version",3);put("napplet_id",id)})})}))).getValue("file").jsonObject
            val fileId=NappletRuntimeProtocol.text(file,"id");val head=ordinary.genericNappletHead(workspace,app,fileId)
            return manifest to buildJsonObject {put("file_id",fileId);put("file_version_id",requireNotNull(head.currentVersionId));put("sha256_hex",Sha256Hex.of(content))}
        }
        val proof=mutableListOf<JsonObject>()
        fun checkpoint(stage: String)=File(directory,"napplet-runtime-live.json").writeText(buildJsonObject {put("evidence","real broker-forwarded Kotlin API; not installed native login or store proof");put("stage",stage);put("requests",requests);put("apps",JsonArray(proof));put("operations",JsonArray(operations))}.toString())
        repeat(2) { index ->
            val id="android-proof-"+UUID.randomUUID().toString().replace("-", "").take(16)
            val (manifest,bundle)=source(id,"1.0.0");val initial=runtime.install(manifest,bundle,viewer,viewer,null)
            initial.requireAudience(viewer);assertEquals(JsonArray(listOf(JsonPrimitive(viewer))),initial.raw["audience_npubs"])
            assertEquals(manifest,NappletRuntimeProtocol.parse(runtime.bundle(initial)))
            val collection=runtime.createCollection(id,"entries",manifest.getValue("collections").jsonObject.getValue("entries").jsonObject)
            assertEquals(JsonPrimitive(viewer),collection["owner_npub"]);assertEquals(JsonArray(emptyList()),collection["read_npubs"])
            val cid=NappletRuntimeProtocol.text(collection,"id")
            val request=buildJsonObject {put("record_id","note");put("base_revision",0);put("value",buildJsonObject {put("text","retained-$index");put("zero",0);put("done",false)})}
            val saved=runtime.records(id,cid,"create",request,JsonPrimitive(1));assertEquals(saved,runtime.records(id,cid,"get",request,JsonPrimitive(1)))
            val before=runtime.history(id,cid,"note")
            var active=initial
            if(index==0) {
                val (updatedManifest,updatedBundle)=source(id,"1.0.1")
                active=runtime.release(initial,updatedBundle,updatedManifest);assertTrue(active.raw["pending_release"] in listOf(null,JsonNull))
                assertTrue(NappletRuntimeProtocol.permits(initial.manifest,active.manifest));assertEquals(updatedManifest,NappletRuntimeProtocol.parse(runtime.bundle(active)))
                assertEquals(saved,runtime.records(id,cid,"get",request,JsonPrimitive(1)));assertEquals(before,runtime.history(id,cid,"note"))
            }
            assertTrue(runCatching {runtime.records(id,cid,"create",request,JsonPrimitive(1))}.exceptionOrNull() is NappletRuntimeAPI.Conflict)
            val removed=runtime.remove(active,false);assertTrue(removed.removed)
            val metadata=runtime.installation(id);assertEquals(removed.revision,metadata.revision)
            val reinstalled=runtime.install(active.manifest,active.bundle,viewer,viewer,metadata)
            assertEquals(removed.revision+1,reinstalled.revision);assertEquals(saved,runtime.records(id,cid,"get",request,JsonPrimitive(1)));assertEquals(before,runtime.history(id,cid,"note"))
            proof+=buildJsonObject {put("id",id);put("collection",cid);put("bundle",reinstalled.bundle);put("installation_revision",reinstalled.revision);put("record",saved.getValue("record"));put("history",before);put("keep_reinstall",true);put("code_update",index==0)}
            checkpoint("app-${index+1}-passed")
        }
        assertNotEquals(proof[0]["collection"],proof[1]["collection"]);checkpoint("passed")
    }
}
