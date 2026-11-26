package studio.otherstuff.ambulando.app

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import studio.otherstuff.ambulando.app.ui.work.WorkSheet
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Actual Android window geometry, synthetic data only. Run on a dedicated emulator. */
@RunWith(AndroidJUnit4::class)
class SettingsInsetsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun workspaceBackClearsSystemBars() {
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.entries?.size == 2 }
            val model = app.activeModel!!
            model.sampleTower!!.administration.viewer = studio.otherstuff.ambulando.core.sample.SampleViewer.ADMIN
            compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
            compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            fun show(label: String, content: @Composable () -> Unit) {
                scenario.onActivity { activity -> activity.setContent { AmbulandoTheme(model.appearance) { key(label) { content() } } } }
                compose.waitForIdle()
            }
            show("workspace") {
                WorkspaceManagement(model, ManagementTarget(AdminRoute.Section(AdminSection.WORKSPACE))) {}
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("admin.workspacePage").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val runLabel = InstrumentationRegistry.getArguments().getString("insetsLabel") ?: "workspace"
            fun capture(label: String) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "screencap -p /sdcard/Download/insets-$runLabel-$label.png")).use { it.readBytes() }
            }
            fun screenBounds(tag: String): androidx.compose.ui.geometry.Rect {
                val position = IntArray(2)
                instrumentation.runOnMainSync {
                    // Dialog boundsInWindow are relative to their own fitted window, not the Activity.
                    android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                        .getLocationOnScreen(position)
                }
                return compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.translate(position[0].toFloat(), position[1].toFloat())
            }
            fun assertSafe(tag: String, label: String, maxGapDp: Int = 24) {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                var top = 0
                var bottom = 0
                var left = 0
                var right = 0
                var density = 1f
                scenario.onActivity {
                    val decor = it.window.decorView
                    val safe = ViewCompat.getRootWindowInsets(decor)!!.getInsets(
                        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    top = safe.top; bottom = decor.height - safe.bottom
                    left = safe.left; right = decor.width - safe.right
                    density = it.resources.displayMetrics.density
                }
                val bounds = screenBounds(tag)
                capture(label)
                println("SETTINGS_INSETS $runLabel/$label safe=[$left,$top,$right,$bottom] control=$bounds density=$density")
                assertTrue("$label overlaps status/cutout: $bounds safeTop=$top", bounds.top >= top)
                assertTrue("$label double top padding: $bounds safeTop=$top", bounds.top <= top + maxGapDp * density)
                assertTrue("$label overlaps side cutout: $bounds safe=[$left,$right]", bounds.left >= left && bounds.right <= right)
                assertTrue("$label overlaps navigation: $bounds safeBottom=$bottom", bounds.bottom <= bottom)
                if (label == "sheet") {
                    val body = screenBounds("insets.content")
                    assertTrue("sheet must meet navigation boundary without double padding: $body bottom=$bottom", kotlin.math.abs(body.bottom - bottom) <= 3)
                }
            }
            assertSafe("admin.back", "workspace")
            // Every section replaces the shell; Scopes has its own root rather than AdminPage.
            for (section in AdminSection.entries) {
                show(section.name) { WorkspaceManagement(model, ManagementTarget(AdminRoute.Section(section))) {} }
                assertSafe("admin.back", section.name)
            }
            show("opening") { WorkspaceManagement(model, ManagementTarget(AdminRoute.Home, "not-the-front-boundary")) {} }
            assertSafe("admin.back", "opening")
            // Navigate through the real configuration route so nested inset ownership is exercised.
            show("scope-setup-entry") { WorkspaceManagement(model, ManagementTarget(AdminRoute.Section(AdminSection.SCOPES))) {} }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("admin.scopeSetup.open").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("admin.scopeSetup.open").performScrollTo().performClick()
            assertSafe("admin.back", "scope-setup")
            show("profile") { WorkspaceManagement(model, ManagementTarget(AdminRoute.Profile)) {} }
            assertSafe("admin.back", "profile")
            compose.onNodeWithTag("admin.field.name").performClick()
            compose.waitUntil(10_000) {
                var visible = false
                scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                visible
            }
            Thread.sleep(500) // platform IME animation
            compose.onNodeWithTag("admin.field.name").performTextReplacement("Inset keyboard check")
            compose.onNodeWithTag("admin.field.name").performScrollTo().assertIsDisplayed()
            var imeTop = 0
            scenario.onActivity { imeTop = it.window.decorView.height - ViewCompat.getRootWindowInsets(it.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom }
            assertTrue("profile field covered by keyboard", compose.onNodeWithTag("admin.field.name").fetchSemanticsNode().boundsInWindow.bottom <= imeTop)
            capture("profile-keyboard")
            Espresso.closeSoftKeyboard()
            show("settings") { NativeSettings(model) {} }
            assertSafe("settings.done", "settings", 16)
            show("sheet") { WorkSheet("Configuration", {}, "Save", confirmTag = "insets.save") {
                val value = remember { mutableStateOf("") }
                Column(Modifier.fillMaxSize().testTag("insets.content").verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value.value, { value.value = it }, Modifier.testTag("insets.field"))
                }
            } }
            assertSafe("sheet.cancel", "sheet", 16)
            compose.onNodeWithTag("insets.field").performClick()
            compose.waitUntil(10_000) {
                var visible = false
                instrumentation.runOnMainSync {
                    val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                    visible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            Thread.sleep(500)
            compose.onNodeWithTag("insets.field").performTextReplacement("Keyboard in configuration sheet")
            compose.onNodeWithTag("insets.field").assertIsDisplayed()
            instrumentation.runOnMainSync {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                val location = IntArray(2); root.getLocationOnScreen(location)
                imeTop = location[1] + root.height - ViewCompat.getRootWindowInsets(root)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
            val contentBottom = screenBounds("insets.content").bottom
            assertTrue("sheet must meet keyboard without double padding: bottom=$contentBottom imeTop=$imeTop", kotlin.math.abs(contentBottom - imeTop) <= 3)
            capture("sheet-keyboard")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("sheet.cancel").performClick()
        }
    }
}
