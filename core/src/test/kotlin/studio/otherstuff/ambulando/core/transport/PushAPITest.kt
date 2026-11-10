package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.support.*

class PushAPITest {
    private val workspace = WorkspaceRecord(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB, "Test", null, null, null, null, JsonObject(emptyMap()))
    private val installation = "00000000-0000-4000-8000-000000000099"
    @Test fun canonicalRegistrationIsSignedOnSavedTowerWithPrivateMaterialOnlyInRequest() = runBlocking {
        val transport = StubTransport { request ->
            when {
                pathOf(request.url).endsWith("/descriptor") -> 200 to mapOf("notifications" to mapOf("transport_status" to mapOf("unifiedpush" to mapOf("ready" to true, "vapid_public_key" to "public-test-vapid"))))
                request.method == "DELETE" -> 404 to mapOf("code" to "installation_not_found")
                else -> 200 to mapOf("delivery" to mapOf("ready" to false, "reason" to "permission_not_granted"), "device" to mapOf("lease_expires_at" to "2026-11-17T00:00:00.000Z"))
            }
        }
        val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), transport, makeIdentity()))
        assertEquals("public-test-vapid", api.pushReadiness(workspace).vapidPublicKey)
        val response = api.registerPush(workspace, installation, "https://push.example/capability", "test-p256dh", "test-auth", false, "test")
        assertFalse(response.ready)
        assertEquals("permission_not_granted", response.reason)
        val puts = transport.recorded.filter { it.method == "PUT" }
        assertEquals(2, puts.size)
        val setup = parseJsonObject(puts[0].body!!, "installation")
        assertEquals("android", setup.string("platform")); assertEquals("denied", setup.string("permission_state"))
        val body = parseJsonObject(puts[1].body!!, "transport")
        assertEquals("https://push.example/capability", body.string("endpoint"))
        assertEquals("test-auth", body.obj("keys")?.string("auth"))
        assertEquals("generic", body.string("preview"))
        assertTrue(puts[1].url.endsWith("/installations/$installation/transports/unifiedpush"))
        assertTrue(transport.recorded.all { it.url.startsWith(Fixtures.ORIGIN) && it.header("Authorization") != null && it.header("x-flightdeck-pg-app-npub") == Fixtures.APP_NPUB })
        assertFalse(response.toString().contains("capability")); assertFalse(response.toString().contains("test-auth"))
        api.revokePush(workspace, installation) // 404 is idempotent revocation.
    }
    @Test fun unsupportedServerIsNotReady() = runBlocking {
        val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), StubTransport { 200 to emptyMap<String, Any>() }, makeIdentity()))
        assertEquals(PushReadiness(false, "transport_not_implemented"), api.pushReadiness(workspace))
    }
}
