package studio.otherstuff.ambulando.core.crypto

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.nostr.Hex
import studio.otherstuff.ambulando.core.nostr.NostrEvent
import studio.otherstuff.ambulando.core.nostr.NostrEventTemplate
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.NostrSerialization
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.nostr.SignatureVerifier
import studio.otherstuff.ambulando.core.support.MemorySecretStore
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.decodeAuthorization
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.HttpRequest
import java.io.File
import java.util.Base64

/** Expects [block] to throw exactly [expected]. */
internal suspend fun expectError(expected: Exception, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        assertEquals(expected, e)
        return
    }
    fail("expected $expected")
}

internal suspend fun expectFailure(block: suspend () -> Unit) {
    val failed = try {
        block()
        false
    } catch (e: Exception) {
        true
    }
    assertTrue("expected a failure", failed)
}

class Bech32Tests {
    @Test fun nip19Vectors() {
        assertEquals(TestKeys.NPUB, NostrKeys.npub(TestKeys.PUBKEY_HEX))
        assertEquals(TestKeys.PUBKEY_HEX, NostrKeys.publicKeyHex(TestKeys.NPUB))
        assertEquals(TestKeys.SECRET_HEX, NostrKeys.secretKey(TestKeys.NSEC)?.let { Hex.encode(it) })
        assertEquals(TestKeys.SECRET_HEX, NostrKeys.secretKey(TestKeys.NSEC.uppercase())?.let { Hex.encode(it) })
    }

    @Test fun rejectsInvalidSecrets() {
        val broken = TestKeys.NSEC.toCharArray().also { it[20] = if (it[20] == 'q') 'p' else 'q' }
        assertNull("checksum", NostrKeys.secretKey(String(broken)))
        assertNull("wrong hrp", NostrKeys.secretKey(TestKeys.NPUB))
        assertNull("mixed case", NostrKeys.secretKey("nsec1" + TestKeys.NSEC.drop(5).uppercase()))
        assertNull("zero scalar", NostrKeys.secretKey(Bech32.encode("nsec", ByteArray(32))))
        val order = Hex.decode("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")!!
        assertNull("scalar == n", NostrKeys.secretKey(Bech32.encode("nsec", order)))
        assertNull("scalar > n", NostrKeys.secretKey(Bech32.encode("nsec", ByteArray(32) { 0xff.toByte() })))
        assertNull("short", NostrKeys.secretKey(Bech32.encode("nsec", ByteArray(31) { 1 })))
        assertFalse(NostrKeys.isValidNpub("npub1notvalid"))
    }
}

class EventSerializationTests {
    companion object {
        /** Fixture produced by nostr-tools `finalizeEvent` with the NIP-19 test key. */
        const val JS_ID = "e57e2c6cbc63655a061e15b3177d395ef70ae5e0f2eeeaaaf9e9e73665e2014f"
        const val JS_SIG = "385240f679db5544ac3ea8304e4b8955c9262049ee9c744f13ef8bb15b37ccb0b71c9ec926be0503e0e21b3270086b000f8b72127ceb7248771de016205a6b27"
        val TEMPLATE = NostrEventTemplate(
            33358,
            listOf(listOf("protocol", "flightdeck_pg_message_instruction"), listOf("body_sha256", "abc"), listOf("thread_id", "t/1")),
            "Hello \"world\"\\ \n\t\u0001 é ✓ / </script>", 1_760_000_000,
        )
    }

    @Test fun eventIdMatchesNostrTools() {
        assertEquals(JS_ID, NostrSerialization.eventId(TestKeys.PUBKEY_HEX, TEMPLATE))
    }

    @Test fun verifiesNostrToolsSignature() {
        val event = NostrEvent(JS_ID, TestKeys.PUBKEY_HEX, 1_760_000_000, 33358, TEMPLATE.tags, TEMPLATE.content, JS_SIG)
        assertTrue(SignatureVerifier.verify(event))
        assertFalse(SignatureVerifier.verify(event.copy(content = event.content + "!")))
    }

    @Test fun quoteMatchesJSONStringifyRules() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u001fé/\"", NostrSerialization.quote("a\"b\\c\n\r\t\b\u000C\u001Fé/"))
    }
}

class IdentityVaultTests {
    @Test fun importUnlockSignLockLifecycle() = runBlocking {
        val dir = tempDir()
        val secrets = MemorySecretStore()
        val service = IdentityService(File(dir, "vault.json"), secrets)
        assertEquals(IdentityState.None, service.state())
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), service.importIdentity(TestKeys.NSEC, TestKeys.PIN))

        val event = service.sign(NostrEventTemplate(1, emptyList(), "hi"))
        assertEquals(TestKeys.PUBKEY_HEX, event.pubkey)
        assertTrue(SignatureVerifier.verify(event))

        assertEquals(IdentityState.Locked(TestKeys.NPUB), service.lock())
        expectError(IdentityError.Locked) { service.sign(NostrEventTemplate(1, emptyList(), "x")) }
        expectError(IdentityError.InvalidVault) { service.unlock("999999") }
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), service.unlock(TestKeys.PIN))

        // A fresh service on the same files (app relaunch) starts locked and knows the public identity only.
        val relaunched = IdentityService(File(dir, "vault.json"), secrets)
        assertEquals(IdentityState.Locked(TestKeys.NPUB), relaunched.state())
        assertEquals(1, secrets.writes)
    }

    @Test fun envelopeNeverContainsSecretMaterialAndKeepsTheIOSFormat() = runBlocking {
        val dir = tempDir()
        makeIdentity(directory = dir)
        val disk = File(dir, "vault.json").readText()
        assertFalse(disk.contains(TestKeys.NSEC))
        assertFalse(disk.contains(TestKeys.SECRET_HEX))
        assertFalse(disk.contains(Base64.getEncoder().encodeToString(Hex.decode(TestKeys.SECRET_HEX)!!)))
        assertTrue(disk.contains(TestKeys.PUBKEY_HEX))
        val envelope = IdentityVault.decodeEnvelope(disk.toByteArray())!!
        assertEquals(1, envelope.version)
        assertEquals(210_000, envelope.iterations)
        assertEquals(16, Base64.getDecoder().decode(envelope.salt).size)
        assertEquals("nonce + 32-byte scalar + tag", 12 + 32 + 16, Base64.getDecoder().decode(envelope.sealed).size)
        assertEquals(TestKeys.PUBKEY_HEX, File(dir, "vault.pub").readText())
    }

    @Test fun refusesOverwriteBadPINAndBadNsec() = runBlocking {
        val service = IdentityService(File(tempDir(), "vault.json"), MemorySecretStore())
        expectError(IdentityError.InvalidPIN) { service.importIdentity(TestKeys.NSEC, "12") }
        expectError(IdentityError.InvalidNsec) { service.importIdentity("nsec1bad", TestKeys.PIN) }
        service.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        expectError(IdentityError.Exists) { service.importIdentity(TestKeys.otherNsec(), TestKeys.PIN) }
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), service.state())
        service.remove()
        assertEquals(IdentityState.None, service.state())
    }

    @Test fun signerBinding() = runBlocking {
        val service = makeIdentity()
        service.requireSigner(TestKeys.NPUB)
        expectError(IdentityError.SignerMismatch) { service.requireSigner(NostrKeys.npub("ab".repeat(32))!!) }
        service.lock()
        expectError(IdentityError.Locked) { service.requireSigner(TestKeys.NPUB) }
    }

    @Test fun tamperedEnvelopeIsInvalidAndKept() = runBlocking {
        val dir = tempDir()
        val secrets = MemorySecretStore()
        makeIdentity(unlocked = false, directory = dir, secrets = secrets)
        val file = File(dir, "vault.json")
        val envelope = IdentityVault.decodeEnvelope(file.readBytes())!!
        val sealed = Base64.getDecoder().decode(envelope.sealed).also { it[20] = (it[20].toInt() xor 1).toByte() }
        val tampered = """{"version":1,"iterations":210000,"salt":"${envelope.salt}","sealed":"${Base64.getEncoder().encodeToString(sealed)}","publicKeyHex":"${envelope.publicKeyHex}"}"""
        file.writeText(tampered)
        val service = IdentityService(file, secrets)
        expectError(IdentityError.InvalidVault) { service.unlock(TestKeys.PIN) }
        assertEquals(tampered, file.readText())
    }
}

class SigningContractTests {
    @Test fun nip98TagsAndExactBodyHash() = runBlocking {
        val identity = makeIdentity()
        val url = "https://tower.example.com/api/v4/flightdeck-pg/workspaces?limit=200"
        val body = """{"b":1,"a":"x"}""".toByteArray()
        val header = NIP98.authorization(url, "post", body, identity)
        val event = decodeAuthorization(HttpRequest("POST", url, mapOf("Authorization" to header)))
        assertEquals(27235, event.kind)
        assertEquals("", event.content)
        assertEquals(listOf(listOf("u", url), listOf("method", "POST"), listOf("payload", Sha256Hex.of(body))), event.tags)
        assertTrue(SignatureVerifier.verify(event))
        assertTrue(kotlin.math.abs(event.createdAt - System.currentTimeMillis() / 1000) < 5)
        assertEquals("no payload tag without a body", 2, NIP98.tags(url, "GET", null).size)
    }

    @Test fun messageInstructionSignatureMatchesWebContract() = runBlocking {
        val identity = makeIdentity()
        val signed = MessageInstructionSignature.sign(body = "Ship it", workspaceId = "w", channelId = "c", threadId = "t", signer = identity)
        assertEquals(33358, signed.event.kind)
        assertEquals("Ship it", signed.event.content)
        assertEquals(
            listOf(
                listOf("protocol", "flightdeck_pg_message_instruction"), listOf("body_sha256", Sha256Hex.of("Ship it")),
                listOf("workspace_id", "w"), listOf("channel_id", "c"), listOf("thread_id", "t"),
            ),
            signed.event.tags,
        )
        assertEquals(TestKeys.NPUB, signed.signerNpub)
        assertTrue(SignatureVerifier.verify(signed.event))
        val o = signed.requestObject
        assertEquals(1, o["version"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(Sha256Hex.of("Ship it")), o["body_sha256"])
        assertEquals(listOf("version", "signer_npub", "body_sha256", "nostr_event"), o.keys.toList())
        assertEquals(signed.event.toJsonObject(), o["nostr_event"])
        assertEquals(
            listOf(listOf("message_id", "m"), listOf("revision", "2")),
            MessageInstructionSignature.tags("x", "w", "c", messageId = "m", revision = 2).takeLast(2),
        )
    }
}

/** Stay unlocked on a trusted device: lock, remove and disconnect end it, and a failed delete never lets it resume. */
class SessionContinuityTests {
    private fun service(dir: File, secrets: MemorySecretStore, session: studio.otherstuff.ambulando.core.support.MemorySessionStore) =
        IdentityService(File(dir, "vault.json"), secrets, sessionStore = session)

    @Test fun unlockedSessionResumesAfterRelaunchUntilManualLock() = runBlocking {
        val dir = tempDir()
        val secrets = MemorySecretStore()
        val session = studio.otherstuff.ambulando.core.support.MemorySessionStore()
        service(dir, secrets, session).importIdentity(TestKeys.NSEC, TestKeys.PIN)
        val second = service(dir, secrets, session)
        assertTrue(second.resumeSession())
        assertEquals(IdentityState.Unlocked(TestKeys.NPUB), second.state())
        // Manual lock: the next launch needs the PIN, even if deleting the platform item fails.
        session.failDeletes = 1
        second.lock()
        assertTrue("the item survived the failed delete", session.stored)
        val third = service(dir, secrets, session)
        assertFalse("never resumes after a manual lock", third.resumeSession())
        assertFalse("the resume attempt finished the deletion", session.stored)
        expectFailure { third.unlock("000000") }
        assertEquals(IdentityState.Locked(TestKeys.NPUB), third.state())
        third.unlock(TestKeys.PIN)
        assertTrue("a PIN unlock starts a new session", service(dir, secrets, session).resumeSession())
    }

    @Test fun endSessionAndRemoveRevoke() = runBlocking {
        val dir = tempDir()
        val secrets = MemorySecretStore()
        val session = studio.otherstuff.ambulando.core.support.MemorySessionStore()
        val identity = service(dir, secrets, session)
        identity.importIdentity(TestKeys.NSEC, TestKeys.PIN)
        identity.endSession()
        val relaunched = service(dir, secrets, session)
        assertFalse(relaunched.resumeSession())
        relaunched.unlock(TestKeys.PIN)
        relaunched.remove()
        assertFalse(session.stored)
    }

    @Test fun sessionOfAnotherKeyIsRefused() = runBlocking {
        val dir = tempDir()
        val secrets = MemorySecretStore()
        val session = studio.otherstuff.ambulando.core.support.MemorySessionStore()
        service(dir, secrets, session).importIdentity(TestKeys.NSEC, TestKeys.PIN)
        session.write(SessionKey(1, TestKeys.PUBKEY_HEX, ByteArray(32) { 0x11 }))
        assertFalse("a stored key that does not match the vault is never used", service(dir, secrets, session).resumeSession())
    }

    @Test fun eachUnlockSignsWithFreshEvents() = runBlocking {
        val identity = makeIdentity()
        val a = identity.sign(NostrEventTemplate(1, emptyList(), "same", 1))
        val b = identity.sign(NostrEventTemplate(1, emptyList(), "same", 1))
        assertEquals(a.id, b.id)
        assertNotEquals("BIP-340 auxiliary randomness differs per signature", a.sig, b.sig)
    }
}
