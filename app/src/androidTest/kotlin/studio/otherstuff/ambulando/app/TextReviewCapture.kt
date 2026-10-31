package studio.otherstuff.ambulando.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/** Review images contain only the explicitly isolated synthetic text-test fixtures. */
internal fun captureTextReview(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val resolver = instrumentation.targetContext.contentResolver
    instrumentation.waitForIdleSync()
    Thread.sleep(100) // Let the newly committed Compose frame reach the screenshot surface.
    instrumentation.uiAutomation.takeScreenshot()?.let { image ->
        // One image per name: MediaStore keeps "name (1).png", "name (2).png" and refuses a new copy after about 32 ("Failed to build
        // unique file"), which failed the text tests on an emulator that had run them that often.
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("$name%", "Download/ambulando-text-review%"),
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/ambulando-text-review")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
