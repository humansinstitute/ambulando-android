package studio.otherstuff.ambulando.core.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityError
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.StubTransport
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.decodeAuthorization
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.queryOf

internal suspend fun expectError(expected: Exception, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        assertEquals(expected, e)
        return
    }
    fail("expected $expected")
}

internal fun expectThrows(expected: Exception, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        assertEquals(expected, e)
        return
    }
    fail("expected $expected")
}

class TowerOriginTests {
    @Test fun acceptsAndNormalisesHTTPSOrigins() {
        assertEquals("https://tower.example.com", TowerOrigin("https://Tower.Example.com/").toString())
        assertEquals("https://tower.example.com", TowerOrigin(" https://tower.example.com:443 ").toString())
        assertEquals("https://tower.example.com:8443", TowerOrigin("https://tower.example.com:8443").toString())
        assertEquals(TowerOrigin("https://tower.example.com"), TowerOrigin("HTTPS://TOWER.EXAMPLE.COM"))
    }

    @Test fun rejectsUnsafeOrigins() {
        for (value in listOf(
            "http://tower.example.com", "https://u:p@tower.example.com", "https://tower.example.com/app", "https://tower.example.com?x=1",
            "https://tower.example.com#f", "tower.example.com", "ftp://tower.example.com", "", "https://tower.example.com?",
        )) {
            expectThrows(TowerError.UnsafeOrigin) { TowerOrigin(value) }
        }
    }

    @Test fun refusesFIPSWithoutFallback() {
        expectThrows(TowerError.FipsNotSupported) { TowerOrigin("http://npub1abc.fips:8080") }
        expectThrows(TowerError.FipsNotSupported) { TowerOrigin("https://npub1abc.FIPS") }
        assertTrue(TowerError.FipsNotSupported.message!!.startsWith("This Android app connects over HTTPS only."))
    }

    @Test fun pathsAreConfinedToTheAPI() {
        val origin = TowerOrigin("https://tower.example.com")
        assertEquals(
            "https://tower.example.com/api/v4/flightdeck-pg/workspaces?limit=200",
            origin.url("/api/v4/flightdeck-pg/workspaces", listOf("limit" to "200")),
        )
        assertEquals("https://tower.example.com/.well-known/nstack.json", origin.url("/.well-known/nstack.json"))
        for (path in listOf("/admin", "/api/v4/../admin", "//evil.example.com/api/v4/x", "/api/v4/x?y=1", "/api/v4/x#y", "https://evil.example.com/api/v4/x", "/.well-known/other")) {
            expectThrows(TowerError.UnsafePath) { origin.url(path) }
        }
    }

    @Test fun queryValuesAreEncodedOnce() {
        val url = TowerOrigin("https://tower.example.com").url("/api/v4/x", listOf("cursor" to "a b&c=d"))
        assertEquals("a b&c=d", queryOf(url)["cursor"])
    }
}

class TowerClientTests {
    private val origin = TowerOrigin(Fixtures.ORIGIN)

    @Test fun signedRequestCarriesExactURLBodyHashAndAppHeader() = runBlocking {
        val identity = makeIdentity()
        val transport = StubTransport { 200 to mapOf("ok" to true) }
        val client = TowerClient(origin, transport, identity)
        val body = Fixtures.json(mapOf("body" to "hello", "client_request_id" to "r1"))
        client.request(
            method = "POST", path = "/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}/channels/${Fixtures.CHANNEL_1}/messages",
            appNpub = Fixtures.APP_NPUB, jsonBody = body,
        )
        client.request(path = "/api/v4/flightdeck-pg/workspaces", query = listOf("cursor" to "abc_-"))
        val (post, get) = transport.recorded
        val postAuth = decodeAuthorization(post)
        val getAuth = decodeAuthorization(get)
        assertTrue(body.contentEquals(post.body))
        assertEquals(listOf(listOf("u", post.url), listOf("method", "POST"), listOf("payload", Sha256Hex.of(body))), postAuth.tags)
        assertEquals(Fixtures.APP_NPUB, post.header("x-flightdeck-pg-app-npub"))
        assertEquals("application/json", post.header("Content-Type"))
        assertEquals("application/json", post.header("Accept"))
        assertEquals(listOf(listOf("u", "https://tower.example.com/api/v4/flightdeck-pg/workspaces?cursor=abc_-"), listOf("method", "GET")), getAuth.tags)
        assertNull(get.body)
        assertNull(get.header("x-flightdeck-pg-app-npub"))
        assertTrue(SignatureVerifier.verify(postAuth) && SignatureVerifier.verify(getAuth))
        assertNotEquals("fresh event per request", postAuth.id, getAuth.id)
    }

    @Test fun lockedIdentityCannotSign() = runBlocking {
        val identity = makeIdentity(unlocked = false)
        val transport = StubTransport { 200 to emptyMap<String, Any>() }
        val client = TowerClient(origin, transport, identity)
        expectError(IdentityError.Locked) { client.request(path = "/api/v4/flightdeck-pg/workspaces") }
        expectError(IdentityError.Locked) { TowerClient(origin, transport, null).request(path = "/api/v4/flightdeck-pg/workspaces") }
        assertTrue("nothing sent unsigned", transport.recorded.isEmpty())
    }

    @Test fun httpErrorsCarryTowerCodeAndStage() = runBlocking {
        val identity = makeIdentity()
        val client = TowerClient(origin, StubTransport { 403 to mapOf("code" to "forbidden") }, identity)
        expectError(TowerError.Http(403, "forbidden", "workspace discovery")) { client.request(path = "/api/v4/flightdeck-pg/workspaces") }
        val fallback = TowerClient(origin, StubTransport { 500 to mapOf("error" to "boom\nline") }, identity)
        try {
            fallback.request(path = "/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}/members")
            fail()
        } catch (e: TowerError.Http) {
            assertEquals(TowerError.Http(500, "boom\nline", "member directory"), e)
            assertEquals("Tower returned HTTP 500 during member directory (boom line).", e.message)
        }
    }

    @Test fun exchangeReturnsAnyStatus() = runBlocking {
        val client = TowerClient(origin, StubTransport { 409 to mapOf("code" to "conflict") }, makeIdentity())
        val reply = client.exchange("PATCH", "/api/v4/flightdeck-pg/workspaces/${Fixtures.WORKSPACE_ID}/channels/${Fixtures.CHANNEL_1}", Fixtures.APP_NPUB, "{}".toByteArray())
        assertEquals(409, reply.status)
        assertEquals("{\"code\":\"conflict\"}", String(reply.body))
    }

    @Test fun descriptorIsFetchedUnsigned() = runBlocking {
        val transport = StubTransport { 200 to Fixtures.descriptor(Fixtures.ORIGIN) }
        val descriptor = TowerAPI(TowerClient(origin, transport, null)).descriptor()
        assertEquals("available", descriptor.tower?.availability?.status)
        assertNull(transport.recorded.single().header("Authorization"))
        expectError(TowerError.UnsafePath) { TowerClient(origin, transport, null).getPublic("/api/v4/flightdeck-pg/workspaces") }
    }

    @Test fun listFollowsCursorsAndReportsTruncation() = runBlocking {
        val identity = makeIdentity()
        val transport = StubTransport { request ->
            when (queryOf(request.url)["cursor"]) {
                null -> 200 to mapOf("scopes" to listOf(Fixtures.scope(Fixtures.SCOPE_A, name = "A")), "next_cursor" to "p2")
                else -> 200 to mapOf("scopes" to listOf(Fixtures.scope(Fixtures.SCOPE_B, name = "B")), "next_cursor" to null)
            }
        }
        val api = TowerAPI(TowerClient(origin, transport, identity))
        assertEquals(listOf("A", "B"), api.scopes(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB).map { it.name })
        assertEquals(2, transport.recorded.size)
        assertEquals("200", queryOf(transport.recorded[0].url)["limit"])

        val full = mapOf("scopes" to (0 until 200).map { Fixtures.scope(Fixtures.newId(), name = "S$it") }, "next_cursor" to null)
        transport.setHandler { 200 to full }
        expectError(TowerError.ListLimitReached("scopes")) { api.scopes(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB) }

        transport.setHandler { 200 to mapOf("scopes" to emptyList<Any>(), "has_more" to true) }
        expectError(TowerError.ListLimitReached("scopes")) { api.scopes(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB) }

        transport.setHandler { 200 to mapOf("scopes" to emptyList<Any>(), "next_cursor" to "same") }
        expectError(TowerError.Malformed("scopes cursor")) { api.scopes(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB) }

        transport.setHandler { 200 to mapOf("scopes" to listOf(1, 2)) }
        expectError(TowerError.Malformed("scopes")) { api.scopes(Fixtures.WORKSPACE_ID, Fixtures.APP_NPUB) }
    }

    @Test fun rejectsNonUUIDPathComponents() = runBlocking {
        val api = TowerAPI(TowerClient(origin, StubTransport { 200 to emptyMap<String, Any>() }, makeIdentity()))
        expectError(TowerError.UnsafePath) { api.scopes("../admin", Fixtures.APP_NPUB) }
        expectError(TowerError.UnsafePath) { api.channels(Fixtures.WORKSPACE_ID, "x/../y", Fixtures.APP_NPUB) }
    }

    @Test fun workspaceDiscoveryParsesAndKeepsRawRows() = runBlocking {
        val row = Fixtures.workspace(Fixtures.WORKSPACE_ID.uppercase(), "Studio") + ("unknown_field" to mapOf("nested" to listOf(1.50, "x")))
        val transport = StubTransport { 200 to mapOf("workspaces" to listOf(row, mapOf("identity" to mapOf("workspace_id" to "nope"))), "next_cursor" to null) }
        val api = TowerAPI(TowerClient(origin, transport, makeIdentity()))
        expectError(TowerError.Malformed("workspace")) { api.workspaces() }
        transport.setHandler { 200 to mapOf("workspaces" to listOf(row), "next_cursor" to null) }
        val workspace = api.workspaces().single()
        assertEquals(Fixtures.WORKSPACE_ID, workspace.id)
        assertEquals("member", workspace.role)
        assertEquals(Fixtures.obj(row), workspace.raw)
        val text = String(Fixtures.json(row)).replace("1.5", "1.50e0")
        transport.setHandler { 200 to "{\"workspaces\":[$text],\"next_cursor\":null}".toByteArray() }
        assertTrue("number lexemes survive", api.workspaces().single().raw.toString().contains("1.50e0"))
        assertNull(transport.recorded.last().header("x-flightdeck-pg-app-npub"))
    }
}

class DescriptorTests {
    private val origin = TowerOrigin(Fixtures.ORIGIN)

    @Test fun parsesMatchingHTTPSDescriptor() {
        val descriptor = NStackDescriptor.parse(Fixtures.json(Fixtures.descriptor(Fixtures.ORIGIN)), origin)
        assertEquals("available", descriptor.tower?.availability?.status)
        assertEquals(TestKeys.NPUB, descriptor.stack?.towerNpub)
        assertFalse(descriptor.advertisesFIPS)
        assertTrue(NStackDescriptor.parse(Fixtures.json(Fixtures.descriptor(Fixtures.ORIGIN, fips = true)), origin).advertisesFIPS)
    }

    @Test fun rejectsMismatchedOrUnsupportedDescriptors() {
        expectThrows(TowerError.Descriptor("Tower endpoint https://other.example.com differs from https://tower.example.com")) {
            NStackDescriptor.parse(Fixtures.json(Fixtures.descriptor("https://other.example.com")), origin)
        }
        expectThrows(TowerError.Descriptor("Tower endpoint is not an HTTPS origin")) {
            NStackDescriptor.parse(Fixtures.json(Fixtures.descriptor("http://tower.example.com")), origin)
        }
        expectThrows(TowerError.Descriptor("Tower does not advertise NIP-98")) {
            NStackDescriptor.parse(Fixtures.json(Fixtures.descriptor(Fixtures.ORIGIN, protocols = listOf("https"))), origin)
        }
        expectThrows(TowerError.Descriptor("unsupported schema org.wingman.nstack.services v2")) {
            NStackDescriptor.parse(Fixtures.json(Fixtures.descriptor(Fixtures.ORIGIN) + ("schema_version" to 2)), origin)
        }
        expectThrows(TowerError.Descriptor("unreadable JSON")) { NStackDescriptor.parse("<html>".toByteArray(), origin) }
    }
}
