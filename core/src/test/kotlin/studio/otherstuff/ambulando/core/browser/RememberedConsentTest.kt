package studio.otherstuff.ambulando.core.browser

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.*
import java.util.UUID

class RememberedConsentTest {
    private val secret = ByteArray(32) { 1 }
    private val own = NostrKeys.publicKeyHex(secret)
    private val other = NostrKeys.publicKeyHex(ByteArray(32) { 2 })
    private val context = NIP07Context(UUID.randomUUID(), 1, "https://sample.example")
    private fun params(method: String) = if (method == "signEvent") buildJsonObject { put("kind", 1) }
        else buildJsonObject { put("pubkey", own); put("text", if (method.endsWith("decrypt")) "x".repeat(132) else "fixture") }

    @Test fun persistedGrantsAreIndependentAndRevocable() = runBlocking {
        var saved = "[]"
        var account = own
        fun broker() = NIP07Broker(false, mapOf(context.origin to NIP07SitePermission.ALLOWED), { account }, { NostrKeys.sign(it, secret) },
            cipher = { _, _, _, _ -> "result" }, grants = NIP07Grants.decode(saved), persistGrants = { saved = NIP07Grants.encode(it) })
        var b = broker()
        for (method in listOf("nip44.encrypt", "nip44.decrypt", "signEvent")) {
            // Connection and other operations never imply this operation.
            val request = async { b.handle(method, params(method), context) { true } }
            yield(); assertNotNull(method, b.current)
            b.resolve(b.current!!.id, NIP07Decision.Allow(true))
            assertTrue(request.await() is NIP07Result.Success)
        }
        assertEquals(3, NIP07Grants.decode(saved).size)
        b = broker() // Process restart/reload from serialized app state.
        for (method in listOf("nip44.encrypt", "nip44.decrypt", "signEvent")) {
            assertTrue(b.handle(method, params(method), context.copy(documentId = 2)) { true } is NIP07Result.Success)
            assertNull(b.current)
        }
        suspend fun deniedRequest(method: String, ctx: NIP07Context = context, p: JsonElement = params(method)) {
            val r = async { b.handle(method, p, ctx) { true } }
            yield(); assertNotNull(b.current)
            b.resolve(b.current!!.id, NIP07Decision.Deny(false))
            assertEquals(NIP07Result.Failure(NIP07Error.Denied), r.await())
        }
        deniedRequest("nip44.encrypt", context.copy(origin = "https://sample.example:8443"))
        deniedRequest("nip44.encrypt", context.copy(origin = "https://sub.sample.example"))
        deniedRequest("signEvent", p = buildJsonObject { put("kind", 2) })
        account = other
        deniedRequest("nip44.encrypt")
        account = own
        b.revoke(NIP07Grant(context.origin, own, "encrypt"))
        assertEquals(2, NIP07Grants.decode(saved).size)
        deniedRequest("nip44.encrypt")
        assertTrue(b.handle("nip44.decrypt", params("nip44.decrypt"), context) { true } is NIP07Result.Success)
        b.forget(context.origin)
        assertEquals(emptySet<NIP07Grant>(), NIP07Grants.decode(saved))
        b = broker()
        deniedRequest("nip44.decrypt")
    }

    @Test fun allowOnceAndDenyNeverPersist() = runBlocking {
        var writes = 0
        val b = NIP07Broker(false, publicKey = { own }, signer = { NostrKeys.sign(it, secret) }, cipher = { _, _, _, _ -> "result" }, persistGrants = { writes++ })
        repeat(3) {
            val r = async { b.handle("nip44.encrypt", params("nip44.encrypt"), context) { true } }
            yield(); assertNotNull(b.current)
            b.resolve(b.current!!.id, if (it == 1) NIP07Decision.Deny(false) else NIP07Decision.Allow(false))
            assertEquals(it != 1, r.await() is NIP07Result.Success)
        }
        assertEquals(0, writes)
    }

    @Test fun lateAlwaysCannotRecreateRevokedOrChangedAccountGrant() = runBlocking {
        for (change in listOf("forget", "lock", "account", "navigate", "close")) {
            var account = own
            var saved = emptySet<NIP07Grant>()
            val b = NIP07Broker(false, publicKey = { account }, signer = { error("must not sign") }, cipher = { _, _, _, _ -> error("must not encrypt") }, persistGrants = { saved = it })
            val r = async { b.handle("nip44.encrypt", params("nip44.encrypt"), context) { true } }
            yield(); val id = b.current!!.id
            when (change) {
                "forget" -> b.forget(context.origin)
                "lock" -> { b.setLocked(true); b.setLocked(false) }
                "account" -> account = other
                "navigate" -> b.documentChanged(context.tabId, 2)
                "close" -> b.tabClosed(context.tabId)
            }
            b.resolve(id, NIP07Decision.Allow(true))
            assertTrue(change, r.await() is NIP07Result.Failure)
            assertTrue(change, saved.isEmpty())
        }
    }

    @Test fun rememberedSigningStillDiscardsRevokedInFlightResult() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val grant = NIP07Grant(context.origin, own, "sign:1")
        val b = NIP07Broker(false, publicKey = { own }, signer = { entered.complete(Unit); release.await(); NostrKeys.sign(it, secret) }, grants = setOf(grant))
        val r = async { b.handle("signEvent", params("signEvent"), context) { true } }
        entered.await(); b.forget(context.origin); release.complete(Unit)
        assertEquals(NIP07Result.Failure(NIP07Error.Denied), r.await())
    }

    @Test fun anotherTabsNavigationDoesNotCancelRememberedCrypto() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val b = NIP07Broker(false, publicKey = { own }, signer = { NostrKeys.sign(it, secret) },
            cipher = { _, _, _, _ -> entered.complete(Unit); release.await(); "result" },
            grants = setOf(NIP07Grant(context.origin, own, "encrypt")))
        val r = async { b.handle("nip44.encrypt", params("nip44.encrypt"), context) { true } }
        entered.await()
        b.documentChanged(UUID.randomUUID(), 2)
        b.tabClosed(UUID.randomUUID())
        release.complete(Unit)
        assertTrue(r.await() is NIP07Result.Success)
    }

    @Test fun persistenceFailureDoesNotRetainCapability() = runBlocking {
        val b = NIP07Broker(false, publicKey = { own }, signer = { NostrKeys.sign(it, secret) },
            cipher = { _, _, _, _ -> error("must not encrypt") }, persistGrants = { error("disk failure") })
        val r = async { b.handle("nip44.encrypt", params("nip44.encrypt"), context) { true } }
        yield(); b.resolve(b.current!!.id, NIP07Decision.Allow(true))
        assertTrue(r.await() is NIP07Result.Failure)
        assertTrue(b.grants.isEmpty())
    }

    @Test fun malformedStoreCannotBroadenAuthority() {
        assertTrue(NIP07Grants.decode("broken").isEmpty())
        for (op in listOf("*", "sign:*", "sign:01", "sign:65536", "connected")) {
            assertTrue(NIP07Grants.decode(NIP07Grants.encode(setOf(NIP07Grant(context.origin, own, op)))).isEmpty())
        }
        assertTrue(NIP07Grants.decode(NIP07Grants.encode(setOf(NIP07Grant("http://sample.example", own, "encrypt")))).isEmpty())
    }
}
