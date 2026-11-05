package studio.otherstuff.ambulando.app.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.media.ImagePrivacy
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.browser.BrowserFavourites
import studio.otherstuff.ambulando.core.browser.NIP07SitePersistence
import java.io.ByteArrayOutputStream

/**
 * The browser's new-tab favourites on a real WebView and Compose screen: save the open page from the ••• menu, see it on
 * the start page, reopen it from a fresh browser (as after an app restart), edit its name and icon, arrange and remove.
 * Sample HTTPS page only (no network); favourites kept in memory standing in for the app-state store.
 */
@RunWith(AndroidJUnit4::class)
class BrowserFavouritesUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val page = "<html><head><title>Sample Site</title><link rel=\"apple-touch-icon\" href=\"https://sample.example/touch.png\"></head><body>Hello</body></html>"

    private class Memory : BrowserFavouritesPersistence {
        @Volatile var text: String? = null
        override suspend fun load() = text
        override suspend fun save(text: String?) { this.text = text }
    }

    private fun browser(memory: Memory): BrowserModel {
        lateinit var made: BrowserModel
        instrumentation.runOnMainSync {
            made = BrowserModel(instrumentation.targetContext)
            made.configure(false, emptyMap(), { "" }, { error("no signing in this test") }, NIP07SitePersistence { })
            made.samplePage = "https://sample.example/" to page
        }
        runBlocking { made.configureFavourites(memory) }
        return made
    }

    @Test fun saveReopenEditArrangeAndRemove() {
        val memory = Memory()
        val browser = browser(memory)
        instrumentation.runOnMainSync { browser.open("https://sample.example/") }
        compose.setContent { AmbulandoTheme { BrowserScreen(browser) } }
        compose.waitUntil(20_000) { browser.selected?.title == "Sample Site" && browser.selected?.loading == false }

        // Save the open page from the ••• menu; the page's own icon is found after saving.
        compose.onNodeWithTag("browser.menu").performClick()
        compose.onNodeWithText("Add to favourites").performClick()
        compose.waitUntil(10_000) { browser.favourites.firstOrNull()?.siteIcon != null }
        assertEquals("Sample Site", browser.favourites.single().title)
        assertEquals("https://sample.example/touch.png", browser.favourites.single().siteIcon)
        compose.onNodeWithTag("browser.menu").performClick()
        compose.onNodeWithText("Remove from favourites").assertIsDisplayed()
        compose.onNodeWithTag("browser.menu").performClick() // closes the menu again
        compose.waitForIdle()

        // A new tab is the start page: the voxel-beach background and the saved site.
        instrumentation.runOnMainSync { browser.open(null) }
        compose.onNodeWithTag("browser.start").assertIsDisplayed()
        compose.onNodeWithTag("browser.start.background").assertIsDisplayed()
        compose.onNodeWithText("Sample Site").assertIsDisplayed()

        // Reopen: a browser created later (as after the app restarts) reads the same list.
        runBlocking { browser.favouritesSaved() }
        assertNotNull(memory.text)
        val reopened = browser(memory)
        assertEquals(browser.favourites, reopened.favourites)
        instrumentation.runOnMainSync { reopened.closeAll() }

        // Edit the name and give it an emoji icon.
        compose.onNodeWithTag("browser.favourite").performTouchInput { longClick() }
        compose.onNodeWithTag("browser.favourite.edit").performClick()
        compose.onNodeWithTag("favourite.name").performTextReplacement("Sample")
        compose.onNodeWithTag("favourite.symbol").performTextReplacement("🚀")
        compose.onNodeWithTag("favourite.save").performClick()
        compose.waitUntil(5_000) { browser.favourites.single().title == "Sample" }
        assertEquals("🚀", browser.favourites.single().symbol)
        compose.onNodeWithTag("favourite.icon.symbol", useUnmergedTree = true).assertIsDisplayed()

        // A refused edit says why and keeps the sheet open.
        compose.onNodeWithTag("browser.favourites.add").performClick()
        compose.onNodeWithTag("favourite.address").performTextReplacement("http://plain.example")
        compose.onNodeWithTag("favourite.save").performClick()
        compose.onNodeWithTag("favourite.error").assertIsDisplayed()
        // Add a second favourite by address, with a picture icon scaled to 256 pixels.
        compose.onNodeWithTag("favourite.address").performTextReplacement("docs.example/start")
        compose.onNodeWithTag("favourite.name").performTextReplacement("Docs")
        compose.onNodeWithTag("favourite.save").performClick()
        compose.waitUntil(5_000) { browser.favourites.size == 2 }
        assertEquals("https://docs.example/start", browser.favourites[1].url)
        val icon = ImagePrivacy.strippedPng(photo(1200, 600), BrowserFavourites.ICON_PIXELS)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(icon, 0, icon.size, it) }
        assertEquals(256, bounds.outWidth); assertEquals(128, bounds.outHeight)
        instrumentation.runOnMainSync { browser.saveFavourite(browser.favourites[1].copy(image = icon)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("favourite.icon.picture", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        // Arrange: move Docs first, then remove it.
        compose.onNodeWithTag("browser.favourites.edit").performClick()
        compose.onAllNodesWithTag("browser.favourites.up")[1].performClick()
        compose.waitUntil(5_000) { browser.favourites.first().title == "Docs" }
        compose.onAllNodesWithTag("browser.favourites.delete")[0].performClick()
        compose.waitUntil(5_000) { browser.favourites.map { it.title } == listOf("Sample") }
        compose.onNodeWithTag("browser.favourites.done").performClick()

        // Open the favourite from the start page into this tab.
        val startTab = browser.selected!!
        compose.onNodeWithTag("browser.favourite").performClick()
        compose.waitUntil(20_000) { startTab.title == "Sample Site" && !startTab.loading }
        assertTrue(browser.tabs.size == 2)

        // Remove from the menu; the stored list empties.
        compose.onNodeWithTag("browser.menu").performClick()
        compose.onNodeWithText("Remove from favourites").performClick()
        compose.waitUntil(5_000) { browser.favourites.isEmpty() }
        runBlocking { browser.favouritesSaved() }
        assertNull("an empty list removes the stored value", memory.text)
        instrumentation.runOnMainSync { browser.closeAll() }
    }

    private fun photo(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 128, 0)) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
