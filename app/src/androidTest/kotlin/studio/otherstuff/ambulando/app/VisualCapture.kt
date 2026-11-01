package studio.otherstuff.ambulando.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/**
 * Renders Chat and the Deck cards from the synthetic sample data and saves screenshots for the iOS-versus-Android visual review.
 * Not an assertion test: it only captures. Appearance, window size and font scale are set from outside (`adb shell cmd uimode`,
 * `wm size`, `settings put system font_scale`); the run's label arrives as the instrumentation argument `visualTag`. Images go to the
 * device's `Download/ambulando-visual/` as `<tag>-<name>.png` (pull with adb).
 */
@RunWith(AndroidJUnit4::class)
class VisualCapture {
    @get:Rule val compose = createEmptyComposeRule()

    private fun shoot(tag: String, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val resolver = instrumentation.targetContext.contentResolver
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            // Downloads survive the uninstall Gradle runs after a test, unlike the app's own files.
            val file = "$tag-$name.png"
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(file))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/ambulando-visual")
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun captureChatAndDeck() {
        val args = InstrumentationRegistry.getArguments()
        val tag = args.getString("visualTag") ?: "untagged"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.messages")).fetchSemanticsNodes().isNotEmpty() }
            shoot(tag, "chat-general")
            compose.runOnIdle { model.section = AppSection.DECK }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.track")).fetchSemanticsNodes().isNotEmpty() }
            for (card in listOf("hello", "inbox", "pinned", "canvas", "recent")) {
                if (compose.onAllNodes(hasTestTag("deck.page.$card")).fetchSemanticsNodes().isEmpty()) continue
                compose.onNodeWithTag("deck.page.$card").performClick()
                shoot(tag, "deck-$card")
            }
            // New thread: the floating button, then the composer (channel preselected from the context), then with text typed.
            compose.onNodeWithTag("deck.page.inbox").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("deck.fab.new")).fetchSemanticsNodes().isNotEmpty() }
            shoot(tag, "deck-inbox-fab")
            compose.onNodeWithTag("deck.fab.new").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            shoot(tag, "newthread-empty")
            compose.onNodeWithTag("chat.composer").performTextInput("Draft the launch checklist and share it with the team before Friday so everyone can comment.")
            shoot(tag, "newthread-typed")
            compose.onNodeWithTag("deck.compose.cancel").performClick()
            // Chat composer at rest (its two-line starting height and tools).
            compose.runOnIdle { model.section = AppSection.CHAT }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            shoot(tag, "chat-composer")
        }
    }
}
