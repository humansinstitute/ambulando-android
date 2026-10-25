package studio.otherstuff.ambulando.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

class PngChunksTest {
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val td = type.toByteArray() + data
        out.write(td)
        val crc = CRC32().apply { update(td) }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    @Test fun dropsExifTextAndTimeChunksAndKeepsADecodableImage() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        // Insert metadata chunks after IHDR (8 + 25 bytes).
        val withMeta = png.copyOfRange(0, 33) + chunk("eXIf", "GPS 51.5N".toByteArray()) + chunk("tEXt", "Author\u0000x".toByteArray()) +
            chunk("tIME", ByteArray(7)) + png.copyOfRange(33, png.size)
        val clean = PngChunks.keepPixelChunks(withMeta)
        val types = PngChunks.chunkTypes(clean)
        assertFalse(types.any { it in setOf("eXIf", "tEXt", "tIME") })
        assertEquals(listOf("IHDR", "IDAT", "IEND"), types.distinct())
        assertEquals(4, ImageIO.read(clean.inputStream()).width)
    }

    @Test(expected = PngChunks.InvalidPng::class) fun rejectsTruncatedInput() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        PngChunks.keepPixelChunks(png.copyOf(png.size - 3))
    }
}
