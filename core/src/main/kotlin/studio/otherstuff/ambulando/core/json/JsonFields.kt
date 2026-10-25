package studio.otherstuff.ambulando.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.nostr.NostrSerialization

/**
 * Loose, Foundation-like reads over `JsonElement` for Tower payloads and page/editor messages. Each accessor answers
 * only for the JSON type it names (a string is never read as a number unless the accessor says so), so records parse
 * the same way as the `JSONSerialization` casts they were ported from.
 */
object JsonFields {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Lenient parser for stored and received payloads (never used to produce canonical bytes). */
    val parser: Json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun isUuid(value: String?): Boolean = value != null && uuid.matches(value)

    /** A UUID string lower-cased, or null for anything else. */
    fun lowerUuid(value: String?): String? = if (isUuid(value)) value!!.lowercase() else null

    fun lowerUuid(element: JsonElement?): String? = lowerUuid(string(element))

    fun isNull(element: JsonElement?): Boolean = element == null || element is JsonNull

    /** The value of a JSON string (never a number or boolean). */
    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON string trimmed of surrounding whitespace, or null when it is empty. */
    fun nonEmpty(element: JsonElement?): String? = string(element)?.trim()?.takeIf { it.isNotEmpty() }

    fun isBool(element: JsonElement?): Boolean {
        val p = element as? JsonPrimitive ?: return false
        return !p.isString && (p.content == "true" || p.content == "false")
    }

    /** `true` only for the JSON literal `true`. */
    fun isTrue(element: JsonElement?): Boolean = isBool(element) && (element as JsonPrimitive).content == "true"

    fun bool(element: JsonElement?): Boolean? = if (isBool(element)) (element as JsonPrimitive).content == "true" else null

    /** A JSON number (never a boolean or string), finite. */
    fun finite(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull || isBool(p)) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** A JSON number, or a string holding one (Tower BIGINT / NUMERIC columns arrive as either). */
    fun number(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull || isBool(p)) return null
        return p.content.toDoubleOrNull()
    }

    /** Tower BIGINT columns can arrive as JSON strings or numbers. Fractions are truncated. */
    fun flexibleInt(element: JsonElement?): Int? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull || isBool(p)) return null
        if (p.isString) return p.content.toIntOrNull()
        p.content.toLongOrNull()?.let { return it.toInt() }
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
    }

    /** A whole JSON number (never a boolean) within the exactly representable range (±2^53 − 1). */
    fun safeInt(element: JsonElement?): Long? {
        val d = finite(element) ?: return null
        if (d != Math.rint(d) || kotlin.math.abs(d) > 9_007_199_254_740_991.0) return null
        return d.toLong()
    }

    fun obj(element: JsonElement?): JsonObject? = element as? JsonObject

    fun array(element: JsonElement?): JsonArray? = element as? JsonArray

    fun parse(bytes: ByteArray): JsonElement? = runCatching { parser.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()

    fun parse(text: String): JsonElement? = runCatching { parser.parseToJsonElement(text) }.getOrNull()

    fun parseObject(bytes: ByteArray): JsonObject? = parse(bytes) as? JsonObject

    fun parseObject(text: String): JsonObject? = parse(text) as? JsonObject

    /** Compact JSON text. With `sortKeys`, object keys are written in code-unit order at every level. */
    fun encode(element: JsonElement, sortKeys: Boolean = true): String = StringBuilder().also { write(it, element, sortKeys) }.toString()

    fun encodeBytes(element: JsonElement, sortKeys: Boolean = true): ByteArray = encode(element, sortKeys).toByteArray(Charsets.UTF_8)

    private fun write(out: StringBuilder, element: JsonElement, sortKeys: Boolean) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                val keys = if (sortKeys) element.keys.sorted() else element.keys.toList()
                keys.forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    out.append(NostrSerialization.quote(key)).append(':')
                    write(out, element.getValue(key), sortKeys)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, item -> if (i > 0) out.append(','); write(out, item, sortKeys) }
                out.append(']')
            }
            is JsonPrimitive -> if (element.isString) out.append(NostrSerialization.quote(element.content)) else out.append(element.content)
        }
    }

    /**
     * Value equality as `NSObject.isEqual` sees decoded JSON: numbers compare by value (`1` equals `1.0`, `false`
     * equals `0`), objects and arrays structurally.
     */
    fun same(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a is JsonPrimitive && b is JsonPrimitive) {
            if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
            val x = numeric(a) ?: return a.content == b.content
            val y = numeric(b) ?: return false
            return x == y
        }
        if (a is JsonArray && b is JsonArray) return a.size == b.size && a.indices.all { same(a[it], b[it]) }
        if (a is JsonObject && b is JsonObject) return a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        return false
    }

    private fun numeric(p: JsonPrimitive): Double? = when (p.content) {
        "true" -> 1.0
        "false" -> 0.0
        else -> p.content.toDoubleOrNull()
    }

    /** Character count in Unicode code points (closer to Swift `String.count` than UTF-16 length). */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /** The first `n` code points of `text`. */
    fun prefix(text: String, n: Int): String {
        if (n <= 0) return ""
        if (length(text) <= n) return text
        return text.substring(0, text.offsetByCodePoints(0, n))
    }
}
