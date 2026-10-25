package studio.otherstuff.ambulando.app.media

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounds SAF input before decoding or staging, including on Android versions before readNBytes. */
fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        if (count == 0) continue
        output.write(buffer, 0, count)
        require(output.size() <= limit) { "Choose a file smaller than ${limit / (1024 * 1024)} MB" }
    }
}
