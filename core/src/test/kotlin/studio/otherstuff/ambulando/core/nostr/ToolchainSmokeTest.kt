package studio.otherstuff.ambulando.core.nostr

import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public NIP-19 specification test vector only; never a real identity. */
class ToolchainSmokeTest {
    private val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"

    @Test fun nip19VectorDerivesItsPublicKeyAndSchnorrVerifies() {
        val secret = Bech32.decode(nsec)!!.also { assertEquals("nsec", it.hrp) }.data
        val xonly = Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33)
        assertEquals(npub, Bech32.encode("npub", xonly))
        val msg = ByteArray(32) { it.toByte() }
        val sig = Secp256k1.signSchnorr(msg, secret, null)
        assertTrue(Secp256k1.verifySchnorr(sig, msg, xonly))
        assertArrayEquals(xonly, Bech32.decode(npub)!!.data)
    }
}

class NostrFoundationTest {
    private val secret = NostrKeys.secretKey("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5")!!

    @Test fun signedEventVerifiesAndTamperingFails() {
        val event = NostrKeys.sign(NostrEventTemplate(1, listOf(listOf("t", "x\"\n")), "héllo\u0001", 1_700_000_000), secret)
        assertTrue(SignatureVerifier.verify(event))
        org.junit.Assert.assertFalse(SignatureVerifier.verify(event.copy(content = "other")))
    }

    @Test fun serializationMatchesJsonStringify() {
        val t = NostrEventTemplate(1, listOf(listOf("a", "b")), "q\"\\\u0007", 5)
        assertEquals("""[0,"pk",5,1,[["a","b"]],"q\"\\\u0007"]""", String(NostrSerialization.commitment("pk", t)))
    }
}
