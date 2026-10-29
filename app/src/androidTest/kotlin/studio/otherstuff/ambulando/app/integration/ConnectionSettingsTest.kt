package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*

@RunWith(AndroidJUnit4::class)
class ConnectionSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun settingsStatusRetryAndExplicitDiagnosticsControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspace != null }
            val model = app.activeModel!!
            scenario.onActivity { activity ->
                activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply {
                    setContent { MaterialTheme { NativeSettings(model) {} } }
                })
            }
            compose.onNodeWithText("Status: Connected").assertIsDisplayed()
            compose.onNodeWithText("Retry", useUnmergedTree = true).assertIsEnabled().performClick()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val chooser = instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_CHOOSER), null, true)
            try {
                compose.onNodeWithText("Export diagnostics").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(5000) { chooser.hits == 1 }
                val file = java.io.File(context.cacheDir, "diagnostics/connection.txt")
                assertTrue(file.isFile)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                assertTrue(text.contains("build ${BuildConfig.VERSION_CODE}")); assertTrue(text.contains("UTC"))
                assertFalse(text.contains(model.connection!!.signerNpub))
                assertFalse(text.contains(model.workspace!!.id))
            } finally { instrumentation.removeMonitor(chooser) }
            runBlocking { kotlinx.coroutines.withTimeout(20_000) { model.engine.waitUntilIdle() } }
            compose.onNodeWithText("Clear diagnostics").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { model.diagnostics.export("test", 4, 36).lines().none { " | " in it } } }
        }
    }
}
