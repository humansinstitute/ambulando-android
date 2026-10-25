package studio.otherstuff.ambulando.core.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Small, strict accessors over raw Tower JSON. They mirror the Swift `as? String` / `as? [[String: Any]]` casts: a
// value of the wrong JSON type reads as absent, and a list with any element of the wrong type reads as absent.

private val UUID_PATTERN = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

/** True for a canonical 8-4-4-4-12 hex UUID (either case), the only form ever placed in a request path. */
fun isUUID(value: String?): Boolean = value != null && UUID_PATTERN.matches(value)

/** The lower-cased UUID, or null for anything that is not a UUID. */
fun lowerUUID(value: String?): String? = if (isUUID(value)) value!!.lowercase() else null

/** String content of a JSON string; null for any other JSON type. */
fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.string(key: String): String? = this[key].stringOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A JSON boolean (strings and numbers are not booleans). */
fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    return when (p.content) { "true" -> true; "false" -> false; else -> null }
}

/** Array of objects; null when absent, not an array, or any element is not an object. */
fun JsonObject.objects(key: String): List<JsonObject>? {
    val array = this[key] as? JsonArray ?: return null
    return array.map { it as? JsonObject ?: return null }
}

/** Array of strings; null when absent, not an array, or any element is not a string. */
fun JsonObject.strings(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    return array.map { it.stringOrNull() ?: return null }
}

/** Tower BIGINT columns can arrive as JSON strings or numbers. Fractional numbers are truncated. */
fun flexibleLong(value: JsonElement?): Long? {
    val p = value as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return p.content.toLongOrNull()
    if (p.content == "true" || p.content == "false") return null
    return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.takeIf { !it.isNaN() }?.toLong()
}

fun flexibleInt(value: JsonElement?): Int? = flexibleLong(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/** A JSON number (or numeric string) as a double. */
fun flexibleDouble(value: JsonElement?): Double? {
    val p = value as? JsonPrimitive ?: return null
    if (p is JsonNull || p.content == "true" || p.content == "false") return null
    return p.content.toDoubleOrNull()
}

/** True when the key is absent or JSON null. */
fun JsonObject.isNullOrAbsent(key: String): Boolean = this[key] == null || this[key] is JsonNull

/** Strict JSON parser for Tower responses (unknown keys in typed decodes are ignored). */
val TowerJson: Json = Json { ignoreUnknownKeys = true }

/** Parses a response body that must be a JSON object; anything else is [TowerError.Malformed] for [what]. */
fun parseJsonObject(bytes: ByteArray, what: String): JsonObject =
    runCatching { TowerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        ?: throw TowerError.Malformed(what)

/** Like [parseJsonObject] but null instead of throwing (bodies of error replies). */
fun parseJsonObjectOrNull(bytes: ByteArray): JsonObject? =
    runCatching { TowerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()

/** A copy with object keys sorted recursively (the request bodies use sorted keys, as on iOS). */
fun sortedJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sortedJson(it.value) })
    is JsonArray -> JsonArray(element.map { sortedJson(it) })
    else -> element
}

/** Compact UTF-8 bytes of a request body with sorted keys. These exact bytes are sent and hashed for NIP-98. */
fun jsonBody(body: JsonObject): ByteArray = sortedJson(body).toString().toByteArray(Charsets.UTF_8)

/** Builds a JSON object from Kotlin values (String, Number, Boolean, null, JsonElement, Map, List). */
fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associateTo(LinkedHashMap()) { it.first to toJsonElement(it.second) })

fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to toJsonElement(it.value) })
    is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.simpleName}")
}
