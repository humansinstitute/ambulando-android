package studio.otherstuff.ambulando.core.media

/**
 * PNG chunk filtering for privacy-stripped photos: only pixel and colour-rendering chunks are kept, so no EXIF,
 * text, time or GPS-bearing metadata can survive re-encoding (matching the iPhone app's `ImagePrivacy`).
 */
object PngChunks {
    class InvalidPng : Exception("Not a well-formed PNG")

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val allowed = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "iCCP", "sRGB", "gAMA", "cHRM")

    fun keepPixelChunks(encoded: ByteArray): ByteArray {
        if (encoded.size <= 8 || !encoded.copyOfRange(0, 8).contentEquals(SIGNATURE)) throw InvalidPng()
        val out = java.io.ByteArrayOutputStream(encoded.size)
        out.write(encoded, 0, 8)
        var offset = 8
        while (offset + 12 <= encoded.size) {
            val length = readLength(encoded, offset)
            if (length < 0 || length > encoded.size - offset - 12) throw InvalidPng()
            val type = String(encoded, offset + 4, 4, Charsets.US_ASCII)
            if (type in allowed) out.write(encoded, offset, length + 12)
            offset += length + 12
        }
        if (offset != encoded.size) throw InvalidPng()
        return out.toByteArray()
    }

    /** Chunk types present in [data] (tests use this to prove metadata is gone). */
    fun chunkTypes(data: ByteArray): List<String> {
        val types = ArrayList<String>()
        var offset = 8
        while (offset + 12 <= data.size) {
            val length = readLength(data, offset)
            if (length < 0) break
            types.add(String(data, offset + 4, 4, Charsets.US_ASCII))
            offset += length + 12
        }
        return types
    }

    private fun readLength(b: ByteArray, at: Int): Int {
        val v = ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
        return if (v > Int.MAX_VALUE) -1 else v.toInt()
    }
}
