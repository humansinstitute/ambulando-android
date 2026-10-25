package studio.otherstuff.ambulando.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.media.PngChunks
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImagePrivacyTest {
    @Test fun gpsAndCameraMetadataAreRemovedAndOrientationIsApplied() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "privacy-test.jpg")
        Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, file.outputStream())
        ExifInterface(file.absolutePath).apply {
            setLatLong(51.5, -0.12)
            setAttribute(ExifInterface.TAG_MAKE, "CameraMaker")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val source = file.readBytes()
        assertEquals(51.5, ExifInterface(source.inputStream()).latLong!![0], 0.001)
        val png = ImagePrivacy.strippedPng(source)
        val types = PngChunks.chunkTypes(png)
        assertFalse(types.any { it !in PngChunks.allowed })
        assertNull(ExifInterface(png.inputStream()).latLong)
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(20, decoded.width)
        assertEquals(40, decoded.height)
        file.delete()
    }
}
