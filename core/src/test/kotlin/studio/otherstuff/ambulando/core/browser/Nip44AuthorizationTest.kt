package studio.otherstuff.ambulando.core.browser

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.*
import java.util.UUID

class Nip44AuthorizationTest {
    private val secret = ByteArray(32) { 1 }
    private val own = NostrKeys.publicKeyHex(secret)
    private val context = NIP07Context(UUID.randomUUID(), 1, "https://sample.example")
    private val params = buildJsonObject { put("pubkey", own); put("text", "private fixture") }

    @Test fun malformedInputNeverPrompts() {
        for (p in listOf(JsonNull, JsonPrimitive("x"), buildJsonObject { put("pubkey", "bad"); put("text", "a") }, buildJsonObject { put("pubkey", own); put("text", "") }, buildJsonObject { put("pubkey", own); put("text", 123) })) {
            assertTrue(NIP07Method.parse("nip44.encrypt", p, 1) is NIP07Result.Failure)
        }
    }

    @Test fun everyOperationNeedsConsentAndDeniedSitesNeverInvokeCrypto() = runBlocking {
        var calls = 0
        val broker = NIP07Broker(false, mapOf(context.origin to NIP07SitePermission.ALLOWED), { own }, { NostrKeys.sign(it, secret) }, cipher = { _, _, _, _ -> calls++; "encrypted" })
        repeat(2) {
            val request = async { broker.handle("nip44.encrypt", params, context) { true } }
            yield()
            assertTrue(broker.current!!.kind is NIP07Prompt.Kind.Cipher)
            broker.resolve(broker.current!!.id, if (it == 0) NIP07Decision.Allow(false) else NIP07Decision.Deny(false))
            assertEquals(it == 0, request.await() is NIP07Result.Success)
        }
        assertEquals(1, calls)
        val denied = NIP07Broker(false, mapOf(context.origin to NIP07SitePermission.DENIED), { own }, { NostrKeys.sign(it, secret) }, cipher = { _, _, _, _ -> error("must not run") })
        assertEquals(NIP07Result.Failure(NIP07Error.Denied), denied.handle("nip44.encrypt", params, context) { true })
        assertNull(denied.current)
    }

    @Test fun lockNavigationRevocationAndAccountChangeDiscardInFlightResults() = runBlocking {
        for (change in listOf("lock", "lock-unlock", "navigation", "forget", "reset", "account")) {
            var key = own
            var current = true
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val broker = NIP07Broker(false, publicKey = { key }, signer = { NostrKeys.sign(it, secret) }, cipher = { _, _, _, _ -> entered.complete(Unit); release.await(); "must not escape" })
            val request = async { broker.handle("nip44.encrypt", params, context) { current } }
            yield()
            broker.resolve(broker.current!!.id, NIP07Decision.Allow(false))
            entered.await()
            when (change) {
                "lock" -> broker.setLocked(true)
                "lock-unlock" -> { broker.setLocked(true); broker.setLocked(false) }
                "navigation" -> { current = false; broker.documentChanged(context.tabId, 2) }
                "forget" -> broker.forget(context.origin)
                "reset" -> broker.reset()
                "account" -> key = NostrKeys.publicKeyHex(ByteArray(32) { 2 })
            }
            release.complete(Unit)
            assertTrue(change, request.await() is NIP07Result.Failure)
        }
    }

    @Test fun denyThenReconnectCannotReviveAnInFlightCipher() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val broker = NIP07Broker(false, publicKey = { own }, signer = { NostrKeys.sign(it, secret) }, cipher = { _, _, _, _ -> entered.complete(Unit); release.await(); "discard" })
        val cipher = async { broker.handle("nip44.encrypt", params, context) { true } }
        yield(); broker.resolve(broker.current!!.id, NIP07Decision.Allow(false)); entered.await()
        val connect = async { broker.handle("getPublicKey", null, context) { true } }
        yield(); broker.resolve(broker.current!!.id, NIP07Decision.Deny(true)); connect.await()
        // Forget and reconnect after the denial; the earlier approval remains invalid.
        broker.forget(context.origin)
        val reconnect = async { broker.handle("getPublicKey", null, context) { true } }
        yield(); broker.resolve(broker.current!!.id, NIP07Decision.Allow(true)); reconnect.await()
        release.complete(Unit)
        assertTrue(cipher.await() is NIP07Result.Failure)
    }

    @Test fun cancellationBeforeApprovalNeverUsesCipher() = runBlocking {
        for (change in listOf("deny", "lock", "navigation", "forget", "close", "account")) {
            var current = true; var key = own
            val broker = NIP07Broker(false, publicKey = { key }, signer = { NostrKeys.sign(it, secret) }, cipher = { _, _, _, _ -> error("must not run") })
            val request = async { broker.handle("nip44.encrypt", params, context) { current } }
            yield()
            when (change) {
                "deny" -> broker.resolve(broker.current!!.id, NIP07Decision.Deny(false))
                "lock" -> broker.setLocked(true)
                "navigation" -> { current = false; broker.documentChanged(context.tabId, 2) }
                "forget" -> broker.forget(context.origin)
                "close" -> broker.tabClosed(context.tabId)
                "account" -> { key = "0".repeat(64); broker.resolve(broker.current!!.id, NIP07Decision.Allow(false)) }
            }
            assertTrue(change, request.await() is NIP07Result.Failure)
        }
    }
}
