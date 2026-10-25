package studio.otherstuff.ambulando.core.nostr

/** BIP-173 bech32 (not bech32m), as used by NIP-19 `npub`/`nsec`. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    data class Decoded(val hrp: String, val data: ByteArray)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1).also { out ->
            hrp.forEachIndexed { i, c -> out[i] = c.code ushr 5; out[i + hrp.length + 1] = c.code and 31 }
            out[hrp.length] = 0
        }

    fun encode(hrp: String, bytes: ByteArray): String {
        val data = convertBits(bytes.map { it.toInt() and 0xff }, 8, 5, true)
            ?: error("convertBits failed")
        val values = hrpExpand(hrp) + data.toIntArray() + IntArray(6)
        val mod = polymod(values) xor 1
        val checksum = (0 until 6).map { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** Returns null for any malformed input, including mixed case. */
    fun decode(input: String): Decoded? {
        if (input.length < 8 || input.length > 5000) return null
        if (input.any { it.code < 33 || it.code > 126 }) return null
        val lower = input.lowercase()
        if (input != lower && input != input.uppercase()) return null
        val pos = lower.lastIndexOf('1')
        if (pos < 1 || pos + 7 > lower.length) return null
        val hrp = lower.substring(0, pos)
        val values = lower.substring(pos + 1).map { c -> CHARSET.indexOf(c).also { if (it < 0) return null } }
        if (polymod(hrpExpand(hrp) + values.toIntArray()) != 1) return null
        val bytes = convertBits(values.dropLast(6), 5, 8, false) ?: return null
        return Decoded(hrp, ByteArray(bytes.size) { bytes[it].toByte() })
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (value in data) {
            if (value < 0 || value ushr from != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out
    }
}
