package studio.otherstuff.ambulando.core.crypto

import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Bounded NIP-44 v2 self-encryption for private locators, owned by IdentityService's unlocked-vault mutex. */
internal object Nip44Self {
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    private fun keys(secret: ByteArray, nonce: ByteArray): ByteArray {
        val peer = NostrKeys.xOnlyPublicKey(secret)
        val point = Secp256k1.pubKeyTweakMul(byteArrayOf(2) + peer, secret)
        val shared = Secp256k1.pubKeyCompress(point).copyOfRange(1, 33)
        val conversation = hmac("nip44-v2".toByteArray(), shared)
        val expanded = ByteArray(76)
        var last = ByteArray(0)
        var offset = 0
        var counter = 1
        try {
            while (offset < expanded.size) {
                val next = hmac(conversation, last + nonce + byteArrayOf(counter++.toByte()))
                last.fill(0); last = next
                val n = minOf(last.size, expanded.size - offset)
                last.copyInto(expanded, offset, 0, n); offset += n
            }
            return expanded
        } finally { shared.fill(0); conversation.fill(0); last.fill(0); peer.fill(0); point.fill(0) }
    }
    private fun paddedLength(length: Int): Int {
        if (length <= 32) return 32
        val nextPower = Integer.highestOneBit(length - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((length - 1) / chunk + 1)
    }
    fun encrypt(secret: ByteArray, plaintext: String): String {
        val plain = plaintext.toByteArray(Charsets.UTF_8)
        require(plain.size in 1..65535) { "Private workspace index is too large" }
        val padded = ByteArray(2 + paddedLength(plain.size))
        padded[0] = (plain.size ushr 8).toByte(); padded[1] = plain.size.toByte(); plain.copyInto(padded, 2)
        val nonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val expanded = keys(secret, nonce)
        val macKey = expanded.copyOfRange(44,76)
        try {
            val ciphertext = chacha(expanded.copyOfRange(0,32), expanded.copyOfRange(32,44), padded)
            return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + ciphertext + hmac(macKey, nonce + ciphertext))
        } finally { plain.fill(0); padded.fill(0); expanded.fill(0); macKey.fill(0) }
    }
    fun decrypt(secret: ByteArray, payload: String): String {
        require(payload.length in 132..131072) { "Invalid private index size" }
        val data = Base64.getDecoder().decode(payload)
        require(data.size >= 99 && data[0] == 2.toByte()) { "Unsupported private index encryption" }
        val nonce = data.copyOfRange(1, 33)
        val expanded = keys(secret, nonce)
        val macKey = expanded.copyOfRange(44,76)
        try {
            val ciphertext = data.copyOfRange(33, data.size - 32)
            require(MessageDigest.isEqual(hmac(macKey, nonce + ciphertext), data.copyOfRange(data.size - 32, data.size))) { "Private index authentication failed" }
            val padded = chacha(expanded.copyOfRange(0,32), expanded.copyOfRange(32,44), ciphertext)
            try {
                require(padded.size >= 34)
                val length = ((padded[0].toInt() and 255) shl 8) or (padded[1].toInt() and 255)
                require(length in 1..65535 && length + 2 <= padded.size && padded.size == paddedLength(length) + 2) { "Invalid private index padding" }
                return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(padded, 2, length)).toString()
            } finally { padded.fill(0) }
        } finally { expanded.fill(0); macKey.fill(0) }
    }
    /** RFC 8439 counter-zero stream from the same pinned library used by Deets/Alexandria. */
    private fun chacha(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        try {
            val engine = ChaCha7539Engine()
            engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
            return ByteArray(input.size).also { engine.processBytes(input,0,input.size,it,0) }
        } finally { key.fill(0); nonce.fill(0) }
    }
}
