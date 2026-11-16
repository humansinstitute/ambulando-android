package studio.otherstuff.ambulando.core.transport

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.*
import java.util.Base64

class WorkspaceConnectionTest {
    private val fixture = WireJson.parseToJsonElement(javaClass.getResource("/workspace-connection-v3.json")!!.readText()).jsonObject
    private val now = fixture.getValue("now").jsonPrimitive.long
    private val envelope = fixture.getValue("envelope").jsonPrimitive.content
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) {} }
    @Test fun towerBytesAndProofInteroperate() {
        val qr = WorkspaceConnection.parse(envelope, now)
        assertEquals("11111111-2222-3333-4444-555555555555", qr.workspaceId)
        assertEquals("fips", qr.preferred)
        assertEquals("https://tower.example", qr.origin("https").toString())
        assertTrue(qr.origin().isFips)
        val proof = WireJson.decodeFromString<NostrEvent>(fixture.getValue("proof").toString())
        val nonce = fixture.getValue("nonce").jsonPrimitive.content
        val url = fixture.getValue("request_url").jsonPrimitive.content
        fixture.getValue("rejected_envelopes").jsonArray.forEach { value -> rejects { WorkspaceConnection.parse(value.jsonPrimitive.content, now) } }
        fixture.getValue("rejected_proofs").jsonArray.forEach { value -> rejects { qr.verifyProof(WireJson.decodeFromString<NostrEvent>(value.toString()), nonce, url, now) } }
        qr.verifyProof(proof, nonce, url, now)
        rejects { qr.verifyProof(proof, "cd".repeat(32), url, now) }
        rejects { qr.verifyProof(proof, nonce, url + "x", now) }
        rejects { qr.verifyProof(proof, nonce, url, now + 60) }
        rejects { qr.verifyProof(proof.copy(sig = "00".repeat(64)), nonce, url, now) }
    }
    @Test fun malformedNoncanonicalAndUnknownInputsFailClosed() {
        rejects { WorkspaceConnection.parse(envelope + "=", now) }
        rejects { WorkspaceConnection.parse("nstack:workspace:v2:abc", now) }
        rejects { WorkspaceConnection.parse(WorkspaceConnection.PREFIX + "a".repeat(8192), now) }
        val raw = String(Base64.getUrlDecoder().decode(envelope.removePrefix(WorkspaceConnection.PREFIX)))
        fun encode(s: String) = WorkspaceConnection.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        rejects { WorkspaceConnection.parse(encode(raw.replaceFirst("{", "{ ")), now) }
        rejects { WorkspaceConnection.parse(encode(raw.replaceFirst("{", "{\"kind\":30383,")), now) }
        rejects { WorkspaceConnection.parse(encode(raw.replaceFirst("{", "{\"extra\":true,")), now) }
        rejects { WorkspaceConnection.parse(encode(raw.replace("\"sig\":\"", "\"sig\":\"00")), now) }
    }
    @Test fun deniedChallengeUsesOwnIdentityAndNeverCreatesAnything() = kotlinx.coroutines.runBlocking {
        val qr = WorkspaceConnection.parse(envelope, now)
        val identity = studio.otherstuff.ambulando.core.support.makeIdentity()
        val transport = studio.otherstuff.ambulando.core.support.StubTransport { 403 to mapOf("code" to "forbidden") }
        val api = TowerAPI(TowerClient(qr.origin("https"), transport, identity))
        try { api.connectionWorkspace(qr); fail("Expected denial") } catch (e: TowerError.Http) { assertEquals(403, e.status) }
        val request = transport.recorded.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.contains("/" + qr.workspaceId + "/connection-proof?nonce="))
        assertNull(request.header("x-flightdeck-pg-app-npub"))
        val auth = studio.otherstuff.ambulando.core.support.decodeAuthorization(request)
        assertEquals(studio.otherstuff.ambulando.core.support.TestKeys.PUBKEY_HEX, auth.pubkey)
        assertTrue(auth.tags.contains(listOf("u", request.url)))
    }

}
