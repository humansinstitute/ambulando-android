package studio.otherstuff.ambulando.app

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class FileSaveTest {
    @Test fun apkSaveUsesDocumentPickerAndOriginalNameEvenWithWrongMime() {
        val name = "Ambulando 0.8.7.APK"
        val mime = attachmentMimeType(name, "application/zip")
        assertEquals("application/vnd.android.package-archive", mime)
        val intent = saveFileIntent(name, mime)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(setOf(Intent.CATEGORY_OPENABLE), intent.categories)
        assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(mime, intent.type)
        assertNull(intent.data)
    }
    @Test fun mimePreservesPreviewTypesAndInfersGenericPdf() {
        assertEquals("image/png", attachmentMimeType("image", "image/png"))
        assertEquals("application/pdf", attachmentMimeType("Guide.pdf", "application/octet-stream"))
        assertEquals("application/pdf", attachmentMimeType("Guide", "application/pdf; charset=binary"))
        assertEquals("application/octet-stream", attachmentMimeType("firmware.unknownextension", null))
    }
    @Test fun apkAndGenericBinarySaveEveryByteAndCloseDestination() {
        for (name in listOf("installer.apk", "firmware.bin")) {
            val file = Files.createTempDirectory("save-file").resolve(name).toFile()
            try {
                val bytes = ByteArray(190_001) { (it % 256).toByte() }
                file.writeBytes(bytes)
                var closed = false
                val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
                copySavedFile(file) { output }
                assertArrayEquals(bytes, output.toByteArray())
                assertTrue(closed)
                assertArrayEquals(bytes, file.readBytes())
            } finally { file.parentFile.deleteRecursively() }
        }
    }
    @Test fun nullAndFailingDestinationsAreReportedAndClosed() {
        val file = Files.createTempFile("save", ".bin").toFile().apply { writeBytes(byteArrayOf(0, -1, 42)) }
        try {
            assertThrows(IllegalStateException::class.java) { copySavedFile(file) { null } }
            var closed = false
            assertThrows(IOException::class.java) { copySavedFile(file) { object : OutputStream() {
                override fun write(b: Int) { throw IOException("full") }
                override fun close() { closed = true }
            } } }
            assertTrue(closed)
        } finally { file.delete() }
    }
}
