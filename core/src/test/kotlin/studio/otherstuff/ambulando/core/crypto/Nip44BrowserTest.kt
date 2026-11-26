package studio.otherstuff.ambulando.core.crypto

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.*
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.TestKeys
import java.util.Base64

class Nip44BrowserTest {
    private fun hex(s: String) = Hex.decode(s)!!
    private val vectors = WireJson.parseToJsonElement(javaClass.getResource("/fixtures/nip44.vectors.json")!!.readText()).jsonObject["v2"]!!.jsonObject

    @Test fun officialPeerEncryptionVectorsMatchByteForByteInBothDirections() {
        for (value in vectors["valid"]!!.jsonObject["encrypt_decrypt"]!!.jsonArray) {
            val v = value.jsonObject
            fun str(k: String) = v[k]!!.jsonPrimitive.content
            val a = hex(str("sec1")); val b = hex(str("sec2"))
            val payload = Nip44Self.encrypt(a, str("plaintext"), NostrKeys.xOnlyPublicKey(b), hex(str("nonce")))
            assertEquals(str("payload"), payload)
            assertEquals(str("plaintext"), Nip44Self.decrypt(b, payload, NostrKeys.xOnlyPublicKey(a)))
        }
    }

    @Test fun officialConversationInvalidAndLongMessageVectors() {
        val valid = vectors["valid"]!!.jsonObject
        for (pair in valid["calc_padded_len"]!!.jsonArray) {
            assertEquals(pair.jsonArray[1].jsonPrimitive.int, Nip44Self.paddedLength(pair.jsonArray[0].jsonPrimitive.int))
        }
        val messages = valid["get_message_keys"]!!.jsonObject
        for (value in messages["keys"]!!.jsonArray) {
            val v = value.jsonObject
            val keys = Nip44Self.messageKeys(hex(messages["conversation_key"]!!.jsonPrimitive.content), hex(v["nonce"]!!.jsonPrimitive.content))
            assertEquals(v["chacha_key"]!!.jsonPrimitive.content, Hex.encode(keys.copyOfRange(0, 32)))
            assertEquals(v["chacha_nonce"]!!.jsonPrimitive.content, Hex.encode(keys.copyOfRange(32, 44)))
            assertEquals(v["hmac_key"]!!.jsonPrimitive.content, Hex.encode(keys.copyOfRange(44, 76)))
        }
        for (value in valid["get_conversation_key"]!!.jsonArray) {
            val v = value.jsonObject
            assertEquals(v["conversation_key"]!!.jsonPrimitive.content, Hex.encode(Nip44Self.conversationKey(hex(v["sec1"]!!.jsonPrimitive.content), hex(v["pub2"]!!.jsonPrimitive.content))))
        }
        for (value in valid["encrypt_decrypt_long_msg"]!!.jsonArray) {
            val v = value.jsonObject
            fun str(k: String) = v[k]!!.jsonPrimitive.content
            val text = str("pattern").repeat(v["repeat"]!!.jsonPrimitive.int)
            val key = hex(str("conversation_key"))
            val payload = Nip44Self.encryptPayload(key, text, hex(str("nonce")))
            assertEquals(str("payload_sha256"), Sha256Hex.of(payload.toByteArray()))
            assertEquals(text, Nip44Self.decryptPayload(key, payload))
        }
        val invalid = vectors["invalid"]!!.jsonObject
        for (value in invalid["decrypt"]!!.jsonArray) {
            val v = value.jsonObject
            assertTrue(v["note"].toString(), runCatching { Nip44Self.decryptPayload(hex(v["conversation_key"]!!.jsonPrimitive.content), v["payload"]!!.jsonPrimitive.content) }.isFailure)
        }
        for (value in invalid["get_conversation_key"]!!.jsonArray) {
            val v = value.jsonObject
            assertTrue(runCatching { Nip44Self.conversationKey(hex(v["sec1"]!!.jsonPrimitive.content), hex(v["pub2"]!!.jsonPrimitive.content)) }.isFailure)
        }
    }

    @Test fun selfPeerUnicodeBoundariesAndTampering() {
        val a = ByteArray(32) { 1 }; val b = ByteArray(32) { 2 }
        for (plain in listOf("hello 🌏", "x".repeat(32), "x".repeat(33), "x".repeat(65535))) {
            val own = Nip44Self.encrypt(a, plain)
            assertEquals(plain, Nip44Self.decrypt(a, own))
            val peer = Nip44Self.encrypt(a, plain, NostrKeys.xOnlyPublicKey(b))
            assertEquals(plain, Nip44Self.decrypt(b, peer, NostrKeys.xOnlyPublicKey(a)))
            assertNotEquals(peer, Nip44Self.encrypt(a, plain, NostrKeys.xOnlyPublicKey(b)))
            val damaged = Base64.getDecoder().decode(peer).also { it[34] = (it[34].toInt() xor 1).toByte() }
            assertTrue(runCatching { Nip44Self.decrypt(b, Base64.getEncoder().encodeToString(damaged), NostrKeys.xOnlyPublicKey(a)) }.isFailure)
            assertTrue(runCatching { Nip44Self.decrypt(a, peer) }.isFailure)
        }
        for (plain in listOf("", "x".repeat(65536), "🌏".repeat(16384), "\uD800")) assertTrue(runCatching { Nip44Self.encrypt(a, plain) }.isFailure)
        for (payload in listOf("#".repeat(132), "A".repeat(131), "A".repeat(87473))) assertTrue(runCatching { Nip44Self.decrypt(a, payload) }.isFailure)
        assertTrue(runCatching { Nip44Self.encrypt(a, "hello", ByteArray(32) { -1 }) }.isFailure)
    }

    @Test fun authenticatedMalformedUtf8IsRejected() {
        val key = ByteArray(32) { 3 }; val nonce = ByteArray(32) { 4 }
        val keys = Nip44Self.messageKeys(key, nonce)
        val padded = ByteArray(34).also { it[1] = 1; it[2] = 0xff.toByte() }
        val engine = org.bouncycastle.crypto.engines.ChaCha7539Engine()
        engine.init(true, org.bouncycastle.crypto.params.ParametersWithIV(org.bouncycastle.crypto.params.KeyParameter(keys.copyOfRange(0, 32)), keys.copyOfRange(32, 44)))
        val encrypted = ByteArray(34).also { engine.processBytes(padded, 0, 34, it, 0) }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(keys.copyOfRange(44, 76), "HmacSHA256")) }.doFinal(nonce + encrypted)
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + encrypted + mac)
        assertTrue(runCatching { Nip44Self.decryptPayload(key, payload) }.exceptionOrNull() is java.nio.charset.CharacterCodingException)
    }

    @Test fun vaultRequiresUnlockedExpectedAccountAndKeepsPrivateIndexCompatible() = runBlocking {
        val identity = makeIdentity()
        val own = identity.publicKeyHex()
        val text = "[[\"service\",\"https://private.example\"]]"
        val old = identity.encryptPrivateIndex(text)
        assertEquals(text, identity.nip44(own, own, old, true))
        val fresh = identity.nip44(own, own, text, false)
        assertEquals(text, identity.decryptPrivateIndex(fresh))
        assertTrue(runCatching { identity.nip44("0".repeat(64), own, text, false) }.isFailure)
        val event = studio.otherstuff.ambulando.core.nostr.NostrEventTemplate(1, emptyList(), "fixture", 1)
        assertEquals(own, identity.signForBrowser(own, event).pubkey)
        assertTrue(runCatching { identity.signForBrowser("0".repeat(64), event) }.isFailure)
        identity.lock()
        assertTrue(runCatching { identity.nip44(own, own, fresh, true) }.isFailure)
        assertTrue(runCatching { identity.signForBrowser(own, event) }.isFailure)
    }
}
