package studio.otherstuff.ambulando.app.media

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.media.PngChunks
import studio.otherstuff.ambulando.core.profiles.AvatarError
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AvatarStoreTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "avatar-test").apply { deleteRecursively() }
    private val png = ByteArrayOutputStream().also { Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    @Test fun downloadsOnceShrinksAndBacksOffFailures(): Unit = runBlocking {
        val fetches = AtomicInteger()
        val store = AvatarStore(dir, AvatarTransport { _, _ -> fetches.incrementAndGet(); png to "image/png" })
        val image = store.image("https://pictures.example.com/a.png")
        assertNotNull(image)
        assertTrue(maxOf(image!!.width, image.height) <= 192)
        val file = store.cachedFile(java.net.URI("https://pictures.example.com/a.png"))!!
        assertTrue(PngChunks.chunkTypes(file.readBytes()).all { it in PngChunks.allowed })
        store.image("https://pictures.example.com/a.png")
        assertEquals("served from cache", 1, fetches.get())
        // Refused addresses are never fetched.
        assertNull(store.image("http://pictures.example.com/b.png"))
        assertNull(store.image("https://192.168.1.2/c.png"))
        assertEquals(1, fetches.get())

        val failing = AtomicInteger()
        val broken = AvatarStore(File(dir, "b"), AvatarTransport { _, _ -> failing.incrementAndGet(); throw AvatarError.NotAnImage })
        assertNull(broken.image("https://pictures.example.com/x.png"))
        assertNull(broken.image("https://pictures.example.com/x.png"))
        assertEquals("a failed URL is not asked for again", 1, failing.get())

        val off = AvatarStore(File(dir, "c"), AvatarTransport { _, _ -> fetches.incrementAndGet(); png to "image/png" })
        off.setEnabled(false)
        assertNull(off.image("https://pictures.example.com/y.png"))
        assertEquals("turned off: nothing new is downloaded", 1, fetches.get())
        dir.deleteRecursively()
    }
}
