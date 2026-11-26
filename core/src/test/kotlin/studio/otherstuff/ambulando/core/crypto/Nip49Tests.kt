package studio.otherstuff.ambulando.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.nostr.Hex
import java.security.SecureRandom

/** The vector and rules are those of nostr-protocol/nips 49.md. */
class Nip49Tests {
    private val vector = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
    private val vectorKey = "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"
    private val secret = Hex.decode(vectorKey)!!
    private fun expect(error: Nip49Error, block: () -> Unit) { try { block() } catch (e: Nip49Error) { assertEquals(error, e); return }; fail("expected $error") }
    private fun payload(text: String) = Bech32.decode(text)!!.data

    @Test fun decryptsTheOfficialPublishedVector() {
        val d = Nip49.decrypt(vector, "nostr")
        assertEquals(vectorKey, Hex.encode(d.secret)); assertEquals(16, d.logN); assertEquals("the published vector's security byte is 0x00", Nip49.KeySecurity.INSECURE, d.keySecurity)
        assertEquals(91, payload(vector).size)
    }

    @Test fun passwordIsNfkcNormalised() {
        val decomposed = "ÅΩẛ̣"; val composed = "ÅΩṩ"
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0x85.toByte(), 0xCE.toByte(), 0xA9.toByte(), 0xE1.toByte(), 0xB9.toByte(), 0xA9.toByte()), Nip49.passwordBytes(decomposed))
        val sealed = Nip49.encrypt(secret, decomposed, logN = 12)
        assertArrayEquals(secret, Nip49.decrypt(sealed, composed).secret)
    }

    @Test fun roundTripIsStrictlyNinetyOneBytesWithFreshSaltAndNonce() {
        val a = Nip49.encrypt(secret, "correct horse", logN = 12, keySecurity = Nip49.KeySecurity.SECURE)
        val b = Nip49.encrypt(secret, "correct horse", logN = 12, keySecurity = Nip49.KeySecurity.SECURE)
        assertNotEquals(a, b)
        val pa = payload(a); val pb = payload(b)
        assertEquals(91, pa.size); assertEquals(2, pa[0].toInt()); assertEquals(12, pa[1].toInt()); assertEquals(1, pa[42].toInt())
        assertNotEquals(pa.copyOfRange(2, 18).toList(), pb.copyOfRange(2, 18).toList())   // salt
        assertNotEquals(pa.copyOfRange(18, 42).toList(), pb.copyOfRange(18, 42).toList()) // nonce
        assertTrue(a.startsWith("ncryptsec1"))
        val d = Nip49.decrypt(a, "correct horse"); assertArrayEquals(secret, d.secret); assertEquals(Nip49.KeySecurity.SECURE, d.keySecurity)
    }

    @Test fun wrongPasswordAndEveryTamperedByteFailIndistinguishably() {
        val text = Nip49.encrypt(secret, "pw", logN = 10)
        expect(Nip49Error.DecryptionFailed) { Nip49.decrypt(text, "pw2") }
        val p = payload(text)
        // Bytes 0 (version) and 1 (cost) fail their own checks; every other byte must break authentication.
        for (i in 2 until p.size) {
            val t = p.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            try { Nip49.decryptPayload(t, "pw"); fail("byte $i accepted") }
            catch (e: Nip49Error) { assertTrue("byte $i: $e", e == Nip49Error.DecryptionFailed || e == Nip49Error.Malformed) }
        }
    }

    @Test fun keySecurityByteIsAuthenticatedAsAssociatedData() {
        val p = payload(Nip49.encrypt(secret, "pw", logN = 10, keySecurity = Nip49.KeySecurity.INSECURE))
        p[42] = 1
        expect(Nip49Error.DecryptionFailed) { Nip49.decryptPayload(p, "pw") }
        p[42] = 7
        expect(Nip49Error.Malformed) { Nip49.decryptPayload(p, "pw") }
    }

    @Test fun malformedWrongVersionAndWrongHrpAreRefused() {
        val good = payload(Nip49.encrypt(secret, "pw", logN = 10))
        expect(Nip49Error.Malformed) { Nip49.decrypt("nsec1notthis", "pw") }
        expect(Nip49Error.Malformed) { Nip49.decrypt("", "pw") }
        expect(Nip49Error.Malformed) { Nip49.decrypt(Bech32.encode("ncryptsec", good.copyOf(90)), "pw") }
        expect(Nip49Error.Malformed) { Nip49.decrypt(Bech32.encode("ncryptsec", good + 0), "pw") }
        expect(Nip49Error.Malformed) { Nip49.decrypt(Bech32.encode("nsec", good), "pw") }
        expect(Nip49Error.UnsupportedVersion) { Nip49.decryptPayload(good.copyOf().also { it[0] = 1 }, "pw") }
        expect(Nip49Error.UnsupportedVersion) { Nip49.decryptPayload(good.copyOf().also { it[0] = 3 }, "pw") }
    }

    @Test fun costIsBoundedBeforeScryptRunsSoACraftedBackupCannotExhaustMemory() {
        val good = payload(Nip49.encrypt(secret, "pw", logN = 10))
        val started = System.nanoTime()
        for (cost in listOf(0, 9, 19, 22, 31, 64, 255)) expect(Nip49Error.CostOutOfBounds) { Nip49.decryptPayload(good.copyOf().also { it[1] = cost.toByte() }, "pw") }
        // 19 needs 512 MiB and is above the default ceiling of 18; raising the ceiling is explicit and itself capped at 20.
        expect(Nip49Error.CostOutOfBounds) { Nip49.decryptPayload(good.copyOf().also { it[1] = 21 }, "pw", maxLogN = 99) }
        assertTrue("rejections must not run scrypt", (System.nanoTime() - started) < 2_000_000_000L)
        try { Nip49.encrypt(secret, "pw", logN = 21); fail() } catch (_: IllegalArgumentException) {}
        try { Nip49.encrypt(secret, "pw", logN = 9); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun oversizedTextIsRejectedBeforeTrimmingOrPasswordWork() {
        // A valid key hidden behind oversized whitespace must not bypass the raw input bound.
        expect(Nip49Error.Malformed) { Nip49.decrypt(" ".repeat(1_000_000) + vector, "") }
        expect(Nip49Error.Malformed) { Nip49.decrypt("ncryptsec1" + "q".repeat(1_000_000), "") }
    }

    @Test fun invalidScalarsAndEmptyPasswordsAreRefused() {
        for (bad in listOf(ByteArray(32), ByteArray(32) { 0xff.toByte() }, ByteArray(31) { 1 })) {
            try { Nip49.encrypt(bad, "pw", logN = 10); fail("encrypted an invalid key") } catch (_: IllegalArgumentException) {}
        }
        expect(Nip49Error.InvalidPassword) { Nip49.encrypt(secret, "", logN = 10) }
        expect(Nip49Error.InvalidPassword) { Nip49.decrypt(vector, "") }
    }

    @Test fun anAuthenticPayloadHoldingAnInvalidScalarIsRefused() {
        // Build a perfectly authentic backup around the zero scalar using the same primitives, to prove the post-decrypt check.
        val random = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { bytes.fill(7) } }
        val forged = forgeWithPlaintext(ByteArray(32), "pw", random)
        expect(Nip49Error.InvalidKey) { Nip49.decryptPayload(forged, "pw") }
    }

    private fun forgeWithPlaintext(plain: ByteArray, password: String, random: SecureRandom): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes); val nonce = ByteArray(24).also(random::nextBytes)
        val key = org.bouncycastle.crypto.generators.SCrypt.generate(password.toByteArray(), salt, 1 shl 10, 8, 1, 32)
        val aead = org.bouncycastle.crypto.modes.XChaCha20Poly1305()
        aead.init(true, org.bouncycastle.crypto.params.AEADParameters(org.bouncycastle.crypto.params.KeyParameter(key), 128, nonce, byteArrayOf(2)))
        val out = ByteArray(aead.getOutputSize(32)); val n = aead.processBytes(plain, 0, 32, out, 0); aead.doFinal(out, n)
        return byteArrayOf(2, 10) + salt + nonce + byteArrayOf(2) + out
    }

    @Test fun secretsDoNotAppearInToStringOrErrors() {
        val d = Nip49.decrypt(vector, "nostr")
        assertTrue(!d.toString().contains(vectorKey))
        try { Nip49.decrypt(vector, "bad") } catch (e: Nip49Error) { assertTrue(!(e.message ?: "").contains("nostr")) }
    }
}
