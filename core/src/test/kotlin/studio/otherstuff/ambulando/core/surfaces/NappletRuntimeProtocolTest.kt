package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NappletRuntimeProtocolTest {
    private fun fixture(name: String)=requireNotNull(javaClass.getResourceAsStream("/napplet-runtime-v3/$name.json")).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun manifest()=NappletRuntimeProtocol.manifest(GenericJson.decode(fixture("manifest")))
    private val session="0123456789abcdef0123456789abcdef"
    private fun reject(block: ()->Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun frozenManifestAndAllFrameOperations() {
        val m=manifest(); assertNull(NappletRuntimeProtocol.support(m))
        assertEquals(m,NappletRuntimeProtocol.manifest(GenericJson.decode(fixture("manifest").replace("\"version\": 3","\"version\": 3e0").replace("\"schema_version\": 1","\"schema_version\": 1.0"))))
        assertEquals(m,NappletRuntimeProtocol.parse("<script id='wingman-napplet-manifest' type='application/json'>$m</script>"))
        Json.parseToJsonElement(fixture("requests")).jsonArray.forEach { assertEquals(it,NappletRuntimeProtocol.request(it.toString(),session,m)) }
    }
    @Test fun strictEnvelopeAndBounds() {
        val m=manifest(); val requests=Json.parseToJsonElement(fixture("requests")).jsonArray
        requests.forEach { value -> val request=value.jsonObject
            reject { NappletRuntimeProtocol.request(JsonObject(request+("owner_npub" to JsonPrimitive("forged"))).toString(),session,m) }
            reject { NappletRuntimeProtocol.request(request.toString(),"fedcba9876543210fedcba9876543210",m) }
        }
        reject { NappletRuntimeProtocol.request(requests[0].toString().replace("50","101"),session,m) }
        reject { NappletRuntimeProtocol.request(requests[2].toString().replace("\"base_revision\":0","\"base_revision\":1"),session,m) }
        reject { NappletRuntimeProtocol.request(requests[2].toString().replace("\"count\":0","\"count\":-0"),session,m) }
        reject { NappletRuntimeProtocol.request(requests[2].toString().replace("\"count\":0","\"__proto__\":{}"),session,m) }
        reject { NappletRuntimeProtocol.parse("<script id='wingman-napplet-manifest'>$m</script><script id='wingman-napplet-manifest'>$m</script>") }
        reject { NappletRuntimeProtocol.manifest(JsonObject(m+("publisher_npub" to JsonPrimitive("forged")))) }
    }
    @Test fun accessSubsetAndUnsupportedCapabilities() {
        val m=manifest(); val presentation=JsonObject(m+mapOf("title" to JsonPrimitive("Updated"),"bundle_version" to JsonPrimitive("9")))
        assertTrue(NappletRuntimeProtocol.permits(m,presentation))
        val c=m.getValue("collections").jsonObject
        val read=JsonObject(c.getValue("entries").jsonObject+("operations" to buildJsonArray { add("get") }))
        val subset=JsonObject(m+("collections" to buildJsonObject { put("entries",read) }))
        assertTrue(NappletRuntimeProtocol.permits(m,subset)); assertFalse(NappletRuntimeProtocol.permits(subset,m))
        for(field in listOf("scope","schema_version")) {
            val changed=JsonObject(read+(field to if(field=="scope") JsonPrimitive("team") else JsonPrimitive(2)))
            assertFalse(NappletRuntimeProtocol.permits(m,JsonObject(m+("collections" to buildJsonObject {put("entries",changed)}))))
        }
        val capability=buildJsonObject { put("version",1); put("resources",buildJsonArray {add("relay")}); put("operations",buildJsonArray {add("decrypt")}) }
        val unsupported=JsonObject(m+("capabilities" to buildJsonObject {put("nostr-dm",capability)}))
        assertNotNull(NappletRuntimeProtocol.support(unsupported)); assertFalse(NappletRuntimeProtocol.permits(m,unsupported))
    }
    @Test fun integrityAndPublisherIdentityApprovals() {
        val m=manifest(); val html="<script id='wingman-napplet-manifest'>$m</script>"; val bytes=html.toByteArray()
        fun installation(publisher: String=studio.otherstuff.ambulando.core.support.TestKeys.NPUB)=NappletRuntimeInstallation(buildJsonObject {
            put("workspace_id","workspace");put("app_npub","npub1app");put("napplet_id","field-notes");put("publisher_npub",publisher);put("owner_npub",studio.otherstuff.ambulando.core.support.TestKeys.NPUB)
            put("manifest",m);put("revision",1);put("removed",false);put("audience_npubs",buildJsonArray {add(studio.otherstuff.ambulando.core.support.TestKeys.NPUB)})
            put("bundle",buildJsonObject {put("file_id","11111111-1111-1111-1111-111111111111");put("file_version_id","22222222-2222-2222-2222-222222222222");put("sha256_hex",studio.otherstuff.ambulando.core.nostr.Sha256Hex.of(bytes))})
        }).validate("workspace","npub1app")
        val i=installation(); assertEquals(html,i.verify(bytes)); reject {i.verify((html+" ").toByteArray())}
        val context=buildJsonObject {put("baseUrl","https://example.test");put("tower","tower");put("service","service");put("workspaceId","workspace");put("appNpub","npub1app");put("viewer",studio.otherstuff.ambulando.core.support.TestKeys.NPUB)}
        val dir=Files.createTempDirectory("napplet-approval-test").toFile()
        try {
            val approvals=NappletRuntimeApprovals(dir,context); assertFalse(approvals.approved(i)); assertTrue(approvals.remember(i)); assertTrue(approvals.approved(i)); assertFalse(approvals.approved(installation(studio.otherstuff.ambulando.core.sample.SampleSigners.otherNpub)))
            assertFalse(NappletRuntimeApprovals(dir,JsonObject(context+("viewer" to JsonPrimitive(studio.otherstuff.ambulando.core.sample.SampleSigners.otherNpub)))).approved(i))
            assertTrue(approvals.forget(i)); assertFalse(approvals.approved(i))
        } finally { dir.deleteRecursively() }
    }
}
