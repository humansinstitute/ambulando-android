package studio.otherstuff.ambulando.core.discovery

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.*
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.support.*
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.transport.*
import java.io.File
import java.util.Base64

class TowerIndexDiscoveryTests {
    private val fixture = WireJson.parseToJsonElement(checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/workspace-self-index-v1.json")).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    private val events = fixture["events"]!!.jsonArray.map { WireJson.decodeFromJsonElement(NostrEvent.serializer(), it) }
    private fun service(): IdentityService = IdentityService(File(tempDir(), "vault.json"), MemorySecretStore())
    private suspend fun unlocked(): IdentityService = service().also {
        it.importIdentity(Bech32.encode("nsec", ByteArray(32).also { key -> key[31] = 1 }), TestKeys.PIN)
    }
    private suspend fun codec(identity: IdentityService) = PrivateWorkspaceIndex(identity.publicKeyHex(), identity::decryptPrivateIndex)

    @Test fun realNostrToolsFixtureCrossAppFlatNestedTowersAndTombstone() = runBlocking {
        val identity = unlocked(); val index = codec(identity)
        assertEquals(fixture["user_pubkey"]!!.jsonPrimitive.content, identity.publicKeyHex())
        assertEquals(setOf("https://one.example", "https://two.example"), index.active(events.take(2)).map { it.origin }.toSet())
        assertEquals(listOf("https://two.example"), index.active(events).map { it.origin })
        assertFalse(PrivateWorkspaceIndex.filter(identity.publicKeyHex()).containsKey("#app_pub"))
        identity.lock()
        assertEquals(IdentityError.Locked, runCatching { identity.decryptPrivateIndex(events.first().content) }.exceptionOrNull())
    }

    @Test fun backgroundReadCredentialCannotDecryptOrEncryptPrivateIndexes() = runBlocking {
        val file = File(tempDir(),"vault.json")
        val secrets = MemorySecretStore(); val background = MemoryBackgroundStore()
        val owner = IdentityService(file,secrets,backgroundStore = background)
        owner.importIdentity(Bech32.encode("nsec",ByteArray(32).also { it[31] = 1 }),TestKeys.PIN)
        owner.enableBackgroundRead(TowerOrigin("https://one.example"),listOf("11111111-1111-4111-8111-111111111111"))
        val reader = IdentityService(file,secrets,backgroundStore = background)
        assertTrue(reader.loadBackgroundRead())
        assertTrue(reader.state() is IdentityState.Locked)
        assertEquals(fixture["user_pubkey"]!!.jsonPrimitive.content,reader.publicKeyHex())
        assertEquals(IdentityError.Locked,runCatching { reader.decryptPrivateIndex(events.first().content) }.exceptionOrNull())
        assertEquals(IdentityError.Locked,runCatching { reader.encryptPrivateIndex("private locator") }.exceptionOrNull())
    }

    @Test fun newestAuthenticMalformedStateFailsClosedWithoutResurrectingOldLocator() = runBlocking {
        val identity = unlocked(); val index = codec(identity); val first = events.first()
        suspend fun signed(tags: List<List<String>> = first.tags, content: String = first.content, at: Long = first.createdAt + 1) = identity.sign(NostrEventTemplate(first.kind, tags, content, at))
        val duplicate = signed(tags = first.tags + listOf(listOf("p", first.pubkey)))
        val address = signed(tags = first.tags.map { if (it[0] == "d") listOf("d", "fd-self:bad") else it })
        val bytes = Base64.getDecoder().decode(first.content); bytes[35] = (bytes[35].toInt() xor 1).toByte()
        val tamper = signed(content = Base64.getEncoder().encodeToString(bytes))
        val future = signed(at = System.currentTimeMillis() / 1000 + 301)
        val duplicateAddress = signed(tags = first.tags + listOf(listOf("d", "fd-self:another")))
        val wrongProtocol = signed(tags = first.tags.map { if (it[0] == "protocol") listOf("protocol", "wrong") else it })
        for (bad in listOf(duplicate, duplicateAddress, wrongProtocol, tamper, future)) {
            assertTrue(index.active(listOf(first, bad)).isEmpty())
        }
        val duplicateOther = signed(tags = first.tags + listOf(listOf("d",events[1].tags.first { it[0] == "d" }[1])))
        assertEquals(listOf("https://two.example"),index.active(listOf(first,events[1],duplicateOther)).map { it.origin })
        assertEquals(1, index.active(listOf(first, address, first.copy(sig = "00".repeat(64)))).size)
    }

    private inner class FakeTowerTransport(val locators: List<PrivateWorkspaceLocator>) : HttpTransport {
        val requests = ArrayList<HttpRequest>()
        var revoked: String? = null
        var stale: String? = null
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val uri = java.net.URI(request.url)
            val origin = "${uri.scheme}://${uri.authority}"
            val locator = locators.first { it.origin == origin }
            fun obj(vararg p: Pair<String, Any?>) = buildJsonObject { for ((k,v) in p) put(k, when (v) { is JsonElement -> v; null -> JsonNull; else -> JsonPrimitive(v.toString()) }) }
            fun response(body: JsonObject) = HttpResponse(200, body.toString().toByteArray())
            fun identity(id: String = locator.workspaceId) = obj("workspace_id" to id, "app_npub" to locator.appNpub, "tower_service_npub" to locator.towerNpub,
                "workspace_service_npub" to if (stale == origin) locator.ownerNpub else locator.serviceNpub, "workspace_owner_npub" to locator.ownerNpub)
            if (uri.path == "/.well-known/nstack.json") return response(buildJsonObject {
                put("schema", "org.wingman.nstack.services"); put("schema_version",1)
                put("stack", obj("tower_npub" to locator.towerNpub, "name" to origin))
                put("services", JsonArray(listOf(obj("id" to "tower", "kind" to "tower", "endpoint" to origin, "protocols" to JsonArray(listOf(JsonPrimitive("nip-98")))))))
            })
            val auth = WireJson.decodeFromString(NostrEvent.serializer(), String(Base64.getDecoder().decode(request.header("Authorization")!!.removePrefix("Nostr "))))
            assertTrue(SignatureVerifier.verify(auth))
            assertEquals(request.url, auth.tags.first { it[0] == "u" }[1])
            assertEquals("GET", auth.tags.first { it[0] == "method" }[1])
            if (uri.path.endsWith("/me")) {
                assertEquals(locator.appNpub, request.header("x-flightdeck-pg-app-npub"))
                if (origin == revoked) return HttpResponse(403,"{}".toByteArray())
                return response(obj("identity" to identity()))
            }
            if (uri.path == TowerAPI.WORKSPACES_PATH) {
                val second = uri.query?.contains("cursor=next") == true
                val id = if (second) "33333333-3333-4333-8333-333333333333" else locator.workspaceId
                return response(obj("workspaces" to JsonArray(listOf(obj("identity" to identity(id), "label" to if (second) "No index" else "Indexed"))), "next_cursor" to if (second) null else "next"))
            }
            error("Unexpected route")
        }
    }

    @Test fun publicOnlyNativeNamespaceDerivationAndDurableExactEventPublicationRetry() = runBlocking {
        val identity = unlocked(); val index = codec(identity)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest((NativeIndexNamespace.LABEL + ":1").toByteArray())
        assertEquals(NativeIndexNamespace.PUBKEY,Hex.encode(digest))
        assertEquals(NativeIndexNamespace.NPUB,NostrKeys.npub(NativeIndexNamespace.PUBKEY))
        assertTrue(runCatching { fr.acinq.secp256k1.Secp256k1.pubkeyParse(byteArrayOf(2) + digest) }.isSuccess)
        val zero = java.security.MessageDigest.getInstance("SHA-256").digest((NativeIndexNamespace.LABEL + ":0").toByteArray())
        assertTrue(runCatching { fr.acinq.secp256k1.Secp256k1.pubkeyParse(byteArrayOf(2) + zero) }.isFailure)
        val locator = index.active(events.take(1)).single()
        val transport = FakeTowerTransport(listOf(locator))
        val sent = ArrayList<NostrEvent>()
        var ack = false; var readback = true
        val relay = object : PrivateIndexRelay {
            override suspend fun query(url: String, filter: JsonObject): List<NostrEvent> = if (readback) sent.takeLast(1) else emptyList()
            override suspend fun publish(url: String,event: NostrEvent): Boolean { sent += event; return ack }
        }
        val publisher = NativeWorkspacePublisher(identity,transport,relay,listOf("wss://fixture.example"))
        val dir = tempDir()
        var store = studio.otherstuff.ambulando.core.store.LocalStore(dir)
        val boundary = studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary(locator.origin,NostrKeys.npub(identity.publicKeyHex())!!,locator.appNpub,locator.workspaceId)
        assertTrue(runCatching { NativeIndexOutbox(store,publisher).send(locator,boundary) }.isFailure)
        val stored = store.reader.state(NativeIndexOutbox.pendingKey(boundary.id))!!
        assertFalse(stored.contains(locator.origin)) // ciphertext and the five public tags only.
        val pending = WireJson.decodeFromString(NostrEvent.serializer(),stored)
        assertEquals(NativeIndexNamespace.PUBKEY,pending.tags.single { it[0] == "app_pub" }[1])
        assertEquals(locator,index.decode(pending).locator)
        assertNotEquals(locator.appNpub,NativeIndexNamespace.NPUB) // Originating namespace never replaces the locator header.
        store.close(); store = studio.otherstuff.ambulando.core.store.LocalStore(dir)
        ack = true; readback = false
        assertTrue(runCatching { NativeIndexOutbox(store,publisher).send(locator,boundary) }.isFailure)
        assertEquals(stored,store.reader.state(NativeIndexOutbox.pendingKey(boundary.id)))
        readback = true
        NativeIndexOutbox(store,publisher).send(locator,boundary)
        assertNull(store.reader.state(NativeIndexOutbox.pendingKey(boundary.id)))
        assertEquals(1,sent.map { it.id }.toSet().size)
        val other = boundary.copy(origin = "https://another.example")
        assertNull(store.reader.state(NativeIndexOutbox.publishedKey(other.id)))
        assertTrue(runCatching { NativeIndexOutbox(store,publisher).send(locator,other) }.isFailure)
        val wrongSigner = boundary.copy(signerNpub = TestKeys.NPUB)
        assertEquals(IdentityError.SignerMismatch,runCatching { NativeIndexOutbox(store,publisher).send(locator,wrongSigner) }.exceptionOrNull())
        store.close()
    }

    @Test fun verifiedTwoTowersExplicitSelectedTowerListsAllPagesAndStaleRevokedIsolation() = runBlocking {
        val identity = unlocked(); val index = codec(identity)
        val locators = index.active(events.take(2))
        val transport = FakeTowerTransport(locators)
        var relayEvents = events.take(2)
        val relay = PrivateIndexRelay { _, filter -> assertFalse(filter.containsKey("#app_pub")); relayEvents }
        val discovery = TowerIndexDiscovery(identity, transport, relay, listOf("wss://fixture.example"))
        val found = discovery.discover()
        assertEquals(2, found.towers.size)
        assertFalse(transport.requests.any { java.net.URI(it.url).path == TowerAPI.WORKSPACES_PATH })
        val chosen = found.towers.first { it.origin == "https://two.example" }
        val list = TowerAPI(TowerClient(TowerOrigin(chosen.origin), transport, identity)).workspaces()
        assertEquals(2, list.size)
        assertEquals("No index", list.last().label)
        assertTrue(transport.requests.filter { java.net.URI(it.url).path == TowerAPI.WORKSPACES_PATH }.all { it.url.startsWith(chosen.origin) })
        transport.stale = "https://one.example"
        assertEquals(listOf("https://two.example"), discovery.discover().towers.map { it.origin })
        transport.revoked = "https://two.example"
        assertTrue(discovery.discover().towers.isEmpty())
        transport.stale = null; transport.revoked = null; relayEvents = events
        assertEquals(listOf("https://two.example"), discovery.discover().towers.map { it.origin })
    }
}
