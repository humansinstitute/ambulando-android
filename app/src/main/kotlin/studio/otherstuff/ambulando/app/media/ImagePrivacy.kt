package studio.otherstuff.ambulando.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import studio.otherstuff.ambulando.core.media.PngChunks
import java.io.ByteArrayOutputStream

/**
 * Photo preparation for chat and document attachments: only rendered, orientation-corrected pixels go into a fresh
 * PNG, and every chunk other than pixel and colour data is dropped (no EXIF, GPS, camera or time metadata). The long
 * edge is capped so a camera photo stays a reasonable size. Mirrors the iPhone app's `ImagePrivacy.strippedPNG`.
 */
object ImagePrivacy {
    class Unreadable : Exception("The image could not be read.")

    const val MAX_PIXEL_SIZE = 4096

    fun strippedPng(data: ByteArray, maxPixelSize: Int = MAX_PIXEL_SIZE): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Unreadable()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixelSize) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw Unreadable()
        // Orientation is the only metadata read, and only to render the pixels upright.
        val orientation = runCatching { ExifInterface(data.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
            val longEdge = maxOf(decoded.width, decoded.height)
            if (longEdge > maxPixelSize) (maxPixelSize.toFloat() / longEdge).let { postScale(it, it) }
        }
        val upright = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val out = ByteArrayOutputStream()
        if (!upright.compress(Bitmap.CompressFormat.PNG, 100, out)) throw Unreadable()
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return PngChunks.keepPixelChunks(out.toByteArray())
    }
}
