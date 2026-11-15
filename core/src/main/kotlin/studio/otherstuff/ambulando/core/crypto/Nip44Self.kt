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

/** Bounded NIP-44 v2 using pinned secp256k1 and Bouncy Castle primitives, under the unlocked-vault mutex. */
internal object Nip44Self {
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    internal fun conversationKey(secret: ByteArray, peer: ByteArray): ByteArray {
        require(peer.size == 32)
        val point = Secp256k1.pubKeyTweakMul(byteArrayOf(2) + peer, secret)
        val shared = Secp256k1.pubKeyCompress(point).copyOfRange(1, 33)
        try { return hmac("nip44-v2".toByteArray(), shared) }
        finally { shared.fill(0); point.fill(0) }
    }
    internal fun messageKeys(conversation: ByteArray, nonce: ByteArray): ByteArray {
        require(conversation.size == 32 && nonce.size == 32)
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
        } finally { last.fill(0) }
    }
    internal fun paddedLength(length: Int): Int {
        if (length <= 32) return 32
        val nextPower = Integer.highestOneBit(length - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((length - 1) / chunk + 1)
    }
    fun encrypt(secret: ByteArray, plaintext: String, peer: ByteArray = NostrKeys.xOnlyPublicKey(secret), nonce: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }): String {
        val conversation = conversationKey(secret, peer)
        try { return encryptPayload(conversation, plaintext, nonce) } finally { conversation.fill(0) }
    }
    internal fun encryptPayload(conversation: ByteArray, plaintext: String, nonce: ByteArray): String {
        require(conversation.size == 32 && nonce.size == 32)
        val encoded = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(plaintext))
        val plain = ByteArray(encoded.remaining()).also { encoded.get(it) }
        require(plain.size in 1..65535) { "NIP-44 plaintext must contain 1–65535 UTF-8 bytes" }
        val padded = ByteArray(2 + paddedLength(plain.size))
        padded[0] = (plain.size ushr 8).toByte(); padded[1] = plain.size.toByte(); plain.copyInto(padded, 2)
        val expanded = messageKeys(conversation, nonce)
        val macKey = expanded.copyOfRange(44,76)
        try {
            val ciphertext = chacha(expanded.copyOfRange(0,32), expanded.copyOfRange(32,44), padded)
            return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + ciphertext + hmac(macKey, nonce + ciphertext))
        } finally { plain.fill(0); padded.fill(0); expanded.fill(0); macKey.fill(0) }
    }
    fun decrypt(secret: ByteArray, payload: String, peer: ByteArray = NostrKeys.xOnlyPublicKey(secret)): String {
        val conversation = conversationKey(secret, peer)
        try { return decryptPayload(conversation, payload) } finally { conversation.fill(0) }
    }
    internal fun decryptPayload(conversation: ByteArray, payload: String): String {
        require(conversation.size == 32)
        require(payload.length in 132..87472) { "Invalid private index size" }
        val data = Base64.getDecoder().decode(payload)
        require(data.size >= 99 && data[0] == 2.toByte()) { "Unsupported private index encryption" }
        val nonce = data.copyOfRange(1, 33)
        val expanded = messageKeys(conversation, nonce)
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
