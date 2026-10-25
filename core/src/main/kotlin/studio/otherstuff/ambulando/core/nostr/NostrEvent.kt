package studio.otherstuff.ambulando.core.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** A signed NIP-01 event. Field names match the wire format. */
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** JSON object for embedding in a request body, using the canonical field set. */
    fun toJsonObject(): JsonObject = WireJson.encodeToJsonElement(this).jsonObject

    fun toJson(): String = WireJson.encodeToString(serializer(), this)
}

data class NostrEventTemplate(
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val createdAt: Long = System.currentTimeMillis() / 1000,
)

/** Signs event templates with the unlocked identity. Implementations never reveal the secret key. */
fun interface EventSigner {
    suspend fun sign(template: NostrEventTemplate): NostrEvent
}

/** Compact JSON used for every request body and stored canonical record. */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * NIP-01 event serialization, written out explicitly so the id is byte-identical to
 * `JSON.stringify([0,pubkey,created_at,kind,tags,content])` used by nostr-tools.
 */
object NostrSerialization {
    fun commitment(pubkey: String, template: NostrEventTemplate): ByteArray {
        val out = StringBuilder("[0,")
        out.append(quote(pubkey)).append(',').append(template.createdAt).append(',').append(template.kind).append(",[")
        template.tags.joinTo(out, ",") { tag -> tag.joinToString(",", "[", "]") { quote(it) } }
        out.append("],").append(quote(template.content)).append(']')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun eventId(pubkey: String, template: NostrEventTemplate): String = Sha256Hex.of(commitment(pubkey, template))

    /** JSON string escaping per ECMAScript JSON.stringify (which NIP-01 specifies). */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c.code < 0x20) out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }
}

object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = DIGITS[(b.toInt() ushr 4) and 0xf]
            out[i * 2 + 1] = DIGITS[b.toInt() and 0xf]
        }
        return String(out)
    }

    /** Lowercase or uppercase hex of even length; null otherwise. */
    fun decode(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

object Sha256Hex {
    fun digest(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun of(data: ByteArray): String = Hex.encode(digest(data))
    fun of(string: String): String = of(string.toByteArray(Charsets.UTF_8))

    /** Streams a file through SHA-256 in 1 MB reads (large downloads are never loaded whole). */
    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return Hex.encode(md.digest())
    }
}
