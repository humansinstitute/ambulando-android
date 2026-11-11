package studio.otherstuff.ambulando.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FilePreviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun file(name: String) = File(context.cacheDir, "AmbulandoFiles/preview-tests/$name").also { it.parentFile!!.mkdirs() }
    @Test fun imageStillPreviewsLocallyWithoutLaunchingAnotherApp() {
        val image = file("photo.png")
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            compose.setContent { AmbulandoTheme { FilePreview(image, attachmentMimeType(image.name, "application/octet-stream")) {} } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("photo.png").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("photo.png").assertExists()
            assertNull(shadowOf(context).nextStartedActivity)
        } finally { image.delete() }
    }
    @Test fun pdfOnlyOpensExternalChooserOnExplicitOpenAndKeepsMimeAndReadGrant() {
        val pdf = file("guide.pdf").apply { writeText("%PDF-1.4\n") }
        try {
            compose.setContent { AmbulandoTheme { FilePreview(pdf, attachmentMimeType(pdf.name, null)) {} } }
            compose.onNodeWithText("Open in…").assertExists()
            assertNull(shadowOf(context).nextStartedActivity)
            compose.onNodeWithText("Open in…").performClick()
            val chooser = shadowOf(context).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION") val viewer = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_VIEW, viewer.action)
            assertEquals("application/pdf", viewer.type)
            assertEquals("content", viewer.data!!.scheme)
            assertTrue(viewer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally { pdf.delete() }
    }
}
