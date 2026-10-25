package studio.otherstuff.ambulando.core.nostr

import fr.acinq.secp256k1.Secp256k1

/** NIP-19 key helpers and BIP-340 signing over libsecp256k1. */
object NostrKeys {
    fun npub(publicKeyHex: String): String? {
        val bytes = Hex.decode(publicKeyHex) ?: return null
        if (bytes.size != 32) return null
        return Bech32.encode("npub", bytes)
    }

    fun publicKeyHex(npub: String): String? {
        val decoded = Bech32.decode(npub) ?: return null
        if (decoded.hrp != "npub" || decoded.data.size != 32) return null
        return Hex.encode(decoded.data)
    }

    fun isValidNpub(value: String): Boolean = publicKeyHex(value) != null && value == value.lowercase()

    /**
     * Decodes an `nsec` (all lower or all upper case, as BIP-173 allows; mixed case and surrounding whitespace are
     * malformed) to a valid secp256k1 scalar in [1, n-1]; null for anything else.
     */
    fun secretKey(nsec: String): ByteArray? {
        val decoded = Bech32.decode(nsec) ?: return null
        if (decoded.hrp != "nsec" || decoded.data.size != 32) return null
        return decoded.data.takeIf { isValidSecret(it) }
    }

    fun isValidSecret(secret: ByteArray): Boolean =
        secret.size == 32 && runCatching { Secp256k1.secKeyVerify(secret) }.getOrDefault(false)

    /** 32-byte x-only public key for a valid secret. */
    fun xOnlyPublicKey(secret: ByteArray): ByteArray = Secp256k1.pubkeyCreate(secret).let { full ->
        // pubkeyCreate returns 65-byte uncompressed (0x04 || X || Y).
        full.copyOfRange(1, 33)
    }

    fun publicKeyHex(secret: ByteArray): String = Hex.encode(xOnlyPublicKey(secret))

    /** Signs a template with a raw secret. Callers keep the secret out of long-lived memory where possible. */
    fun sign(template: NostrEventTemplate, secret: ByteArray, auxRand: ByteArray? = null): NostrEvent {
        val pubkey = publicKeyHex(secret)
        val id = NostrSerialization.eventId(pubkey, template)
        val sig = Secp256k1.signSchnorr(Hex.decode(id)!!, secret, auxRand ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        return NostrEvent(id, pubkey, template.createdAt, template.kind, template.tags, template.content, Hex.encode(sig))
    }
}

/** NIP-01 id and BIP-340 signature verification. */
object SignatureVerifier {
    fun verify(event: NostrEvent): Boolean {
        val pub = Hex.decode(event.pubkey) ?: return false
        val sig = Hex.decode(event.sig) ?: return false
        val id = Hex.decode(event.id) ?: return false
        if (pub.size != 32 || sig.size != 64 || id.size != 32) return false
        val expected = NostrSerialization.eventId(event.pubkey, NostrEventTemplate(event.kind, event.tags, event.content, event.createdAt))
        if (expected != event.id.lowercase()) return false
        return runCatching { Secp256k1.verifySchnorr(sig, id, pub) }.getOrDefault(false)
    }
}
