package studio.otherstuff.ambulando.core.docs

import studio.otherstuff.ambulando.core.nostr.NostrSerialization
import java.io.ByteArrayOutputStream

/**
 * A JSON value that round-trips without loss: object key order is kept and numbers keep their exact source
 * lexeme (so `1`, `1.0` and `1e3` are written back as they were read). Document bodies are edited through this
 * type so fields the native editor does not understand survive a save unchanged.
 *
 * kotlinx-serialization is not used here because it cannot guarantee the original number lexemes or duplicate
 * keys survive a re-encode byte for byte.
 */
sealed class JsonValue {
    data object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    /** The number exactly as written in the source (or by [int]). */
    data class Num(val lexeme: String) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    /** Ordered key/value pairs; equality is order-sensitive, as in the web serializer's output. */
    data class Obj(val pairs: List<Pair<String, JsonValue>>) : JsonValue()

    // Access

    /** The last value for `key` (as `JSON.parse` keeps the last duplicate), or null. */
    operator fun get(key: String): JsonValue? = (this as? Obj)?.pairs?.lastOrNull { it.first == key }?.second

    val stringValue: String? get() = (this as? Str)?.value
    val arrayValue: List<JsonValue>? get() = (this as? Arr)?.items
    val objectValue: List<Pair<String, JsonValue>>? get() = (this as? Obj)?.pairs
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val isObject: Boolean get() = this is Obj

    /** Integer value of a number: exact integers, or doubles with no fractional part, within `Int` range. */
    val intValue: Int?
        get() {
            val n = (this as? Num)?.lexeme ?: return null
            n.toIntOrNull()?.let { return it }
            val d = n.toDoubleOrNull() ?: return null
            if (d.isNaN() || d.isInfinite() || Math.rint(d) != d) return null
            if (d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
            return d.toInt()
        }

    /** Returns a copy with `key` set (replacing an existing key in place, or appended when new); null removes it. */
    fun setting(key: String, value: JsonValue?): JsonValue {
        val obj = this as? Obj ?: return this
        val pairs = obj.pairs.toMutableList()
        val index = pairs.indexOfFirst { it.first == key }
        if (index >= 0) {
            if (value != null) pairs[index] = key to value else pairs.removeAt(index)
        } else if (value != null) {
            pairs.add(key to value)
        }
        return Obj(pairs)
    }

    // Serialize

    /** Compact JSON in the same escaping style as `JSON.stringify` (the web client's writer). */
    fun serialized(): ByteArray = serializedString().toByteArray(Charsets.UTF_8)

    fun serializedString(): String = StringBuilder().also { write(it) }.toString()

    private fun write(out: StringBuilder) {
        when (this) {
            Null -> out.append("null")
            is Bool -> out.append(if (value) "true" else "false")
            is Num -> out.append(lexeme)
            is Str -> out.append(NostrSerialization.quote(value))
            is Arr -> {
                out.append('[')
                items.forEachIndexed { i, item -> if (i > 0) out.append(','); item.write(out) }
                out.append(']')
            }
            is Obj -> {
                out.append('{')
                pairs.forEachIndexed { i, (key, value) ->
                    if (i > 0) out.append(',')
                    out.append(NostrSerialization.quote(key)).append(':')
                    value.write(out)
                }
                out.append('}')
            }
        }
    }

    /** Why parsing failed: an invalid byte at an offset, trailing content, or nesting deeper than the limit. */
    class ParseError(val kind: Kind, val offset: Int) : Exception("JSON ${kind.name.lowercase()} at $offset") {
        enum class Kind { INVALID, TRAILING, DEPTH }
    }

    companion object {
        fun int(value: Int): JsonValue = Num(value.toString())
        fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = Obj(pairs.toList())
        fun arr(vararg items: JsonValue): JsonValue = Arr(items.toList())
        fun str(value: String): JsonValue = Str(value)

        fun parse(data: ByteArray): JsonValue {
            val parser = Parser(data)
            parser.skipWhitespace()
            val value = parser.value(0)
            parser.skipWhitespace()
            if (parser.index != data.size) throw ParseError(ParseError.Kind.TRAILING, parser.index)
            return value
        }

        fun parse(text: String): JsonValue = parse(text.toByteArray(Charsets.UTF_8))

        /** Parses, or null when the bytes are not JSON. */
        fun parseOrNull(data: ByteArray): JsonValue? = try { parse(data) } catch (_: ParseError) { null }
    }

    private class Parser(val bytes: ByteArray) {
        var index = 0

        private fun at(i: Int): Int = bytes[i].toInt() and 0xFF
        private fun invalid(at: Int = index): Nothing = throw ParseError(ParseError.Kind.INVALID, at)

        fun skipWhitespace() {
            while (index < bytes.size) {
                val b = at(index)
                if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) index++ else break
            }
        }

        private fun expect(literal: String) {
            for (c in literal) {
                if (index >= bytes.size || at(index) != c.code) invalid()
                index++
            }
        }

        fun value(depth: Int): JsonValue {
            if (depth >= MAX_DEPTH) throw ParseError(ParseError.Kind.DEPTH, index)
            if (index >= bytes.size) invalid()
            return when (at(index).toChar()) {
                '{' -> {
                    index++
                    val pairs = mutableListOf<Pair<String, JsonValue>>()
                    skipWhitespace()
                    if (index < bytes.size && at(index) == '}'.code) { index++; return Obj(pairs) }
                    while (true) {
                        skipWhitespace()
                        val key = string()
                        skipWhitespace()
                        expect(":")
                        skipWhitespace()
                        pairs.add(key to value(depth + 1))
                        skipWhitespace()
                        if (index >= bytes.size) invalid()
                        if (at(index) == ','.code) { index++; continue }
                        if (at(index) == '}'.code) { index++; return Obj(pairs) }
                        invalid()
                    }
                    @Suppress("UNREACHABLE_CODE") invalid()
                }
                '[' -> {
                    index++
                    val items = mutableListOf<JsonValue>()
                    skipWhitespace()
                    if (index < bytes.size && at(index) == ']'.code) { index++; return Arr(items) }
                    while (true) {
                        skipWhitespace()
                        items.add(value(depth + 1))
                        skipWhitespace()
                        if (index >= bytes.size) invalid()
                        if (at(index) == ','.code) { index++; continue }
                        if (at(index) == ']'.code) { index++; return Arr(items) }
                        invalid()
                    }
                    @Suppress("UNREACHABLE_CODE") invalid()
                }
                '"' -> Str(string())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> Num(number())
            }
        }

        private fun digits(): Int {
            val start = index
            while (index < bytes.size && at(index) in 0x30..0x39) index++
            return index - start
        }

        fun number(): String {
            val start = index
            if (index < bytes.size && at(index) == '-'.code) index++
            if (digits() == 0) invalid(start)
            if (index < bytes.size && at(index) == '.'.code) { index++; if (digits() == 0) invalid() }
            if (index < bytes.size && (at(index) == 'e'.code || at(index) == 'E'.code)) {
                index++
                if (index < bytes.size && (at(index) == '+'.code || at(index) == '-'.code)) index++
                if (digits() == 0) invalid()
            }
            return String(bytes, start, index - start, Charsets.UTF_8)
        }

        fun string(): String {
            if (index >= bytes.size || at(index) != '"'.code) invalid()
            index++
            val out = ByteArrayOutputStream()
            while (index < bytes.size) {
                val byte = at(index)
                if (byte == '"'.code) { index++; return String(out.toByteArray(), Charsets.UTF_8) }
                if (byte < 0x20) invalid()
                if (byte != '\\'.code) { out.write(byte); index++; continue }
                index++
                if (index >= bytes.size) invalid()
                val escape = at(index)
                index++
                when (escape.toChar()) {
                    '"' -> out.write(0x22)
                    '\\' -> out.write(0x5C)
                    '/' -> out.write(0x2F)
                    'b' -> out.write(0x08)
                    'f' -> out.write(0x0C)
                    'n' -> out.write(0x0A)
                    'r' -> out.write(0x0D)
                    't' -> out.write(0x09)
                    'u' -> {
                        var scalar = hex4()
                        if (scalar in 0xD800..0xDBFF) {
                            // A surrogate pair encodes one scalar outside the BMP.
                            if (!(index + 1 < bytes.size && at(index) == '\\'.code && at(index + 1) == 'u'.code)) invalid()
                            index += 2
                            val low = hex4()
                            if (low !in 0xDC00..0xDFFF) invalid()
                            scalar = 0x10000 + ((scalar - 0xD800) shl 10) + (low - 0xDC00)
                        } else if (scalar in 0xDC00..0xDFFF) {
                            invalid()
                        }
                        out.write(String(Character.toChars(scalar)).toByteArray(Charsets.UTF_8))
                    }
                    else -> invalid()
                }
            }
            invalid()
        }

        private fun hex4(): Int {
            if (index + 4 > bytes.size) invalid()
            var value = 0
            for (i in 0 until 4) {
                val digit = Character.digit(at(index + i), 16)
                if (digit < 0) invalid()
                value = value * 16 + digit
            }
            index += 4
            return value
        }

        companion object { const val MAX_DEPTH = 512 }
    }
}
