package studio.otherstuff.ambulando.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.json.JsonFields

/** Builds JSON from plain Kotlin values (maps, lists, strings, numbers, booleans, null) for test fixtures. */
fun j(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to j(it.value) })
    is List<*> -> JsonArray(value.map(::j))
    is Array<*> -> JsonArray(value.map(::j))
    else -> error("unsupported fixture value $value")
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*pairs).mapValues { j(it.value) })

fun bytes(vararg pairs: Pair<String, Any?>): ByteArray = JsonFields.encodeBytes(obj(*pairs))

fun bytes(element: JsonElement): ByteArray = JsonFields.encodeBytes(element)

/** Synthetic fixture ids (never real records). */
object Ids {
    fun uuid(n: Int, group: String = "0000"): String = "00000000-$group-4000-8000-%012d".format(n)
    val workspace = uuid(1, "0001")
    val workspace2 = uuid(2, "0001")
    val scopeA = uuid(1, "0002")
    val scopeB = uuid(2, "0002")
    val channel1 = uuid(1, "0003")
    val channel2 = uuid(2, "0003")
    val channel3 = uuid(3, "0003")
}

/** The public NIP-19 test vector key (no real identity). */
object TestKeys {
    const val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    const val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
}
