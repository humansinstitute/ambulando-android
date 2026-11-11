package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
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
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.refreshFiles

/** Synthetic Tower + real Android DocumentsUI on a dedicated emulator, never a personal account. */
@RunWith(AndroidJUnit4::class)
class FileSaveFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString()?.equals(text, ignoreCase = true) == true && root.isClickable) return root
        for (index in 0 until root.childCount) find(root.getChild(index), text)?.let { return it }
        return null
    }
    @Test fun apkAndBinarySaveThroughDocumentsUiWithExactBytesAndCancelPreservesPreview() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.FILES }
            val bytes = ByteArray(19_001) { it.toByte() }
            for (extension in listOf("apk", "bin")) {
                val name = "ambulando-save-${System.currentTimeMillis()}.$extension"
                val id = model.sampleTower!!.work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, name, bytes, "application/octet-stream")
                runBlocking { model.sync.refreshFiles(model.connection!!, model.workspace!!, force = true) }
                compose.waitUntil(20_000) { runBlocking { model.store.reader.file(SampleIds.STUDIO, id) != null } }
                compose.onNodeWithText(name).performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("files.save").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("files.save").performClick()
                compose.waitUntil(10_000) { find(instrumentation.uiAutomation.rootInActiveWindow, "Save") != null }
                // Back returns without fetching or opening the APK.
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { stream -> stream.readBytes() }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Save cancelled.").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Save cancelled.").assertExists()
                assertNull(model.fileCache().cached(runBlocking { model.store.reader.file(SampleIds.STUDIO, id) }!!))
                compose.onNodeWithTag("files.save").performClick()
                compose.waitUntil(10_000) { find(instrumentation.uiAutomation.rootInActiveWindow, "Save") != null }
                find(instrumentation.uiAutomation.rootInActiveWindow, "Save")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Saved $name.").fetchSemanticsNodes().isNotEmpty() }
                val actual = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name")).use { stream -> stream.readBytes() }
                assertArrayEquals("DocumentsUI preserves binary bytes and filename", bytes, actual)
                compose.onNodeWithTag("files.preview").assertExists()
                compose.onNodeWithTag("files.share").assertExists()
                compose.onNodeWithTag("files.preview").performClick()
                compose.onNodeWithText("Open in…").assertExists()
                compose.onNodeWithText("Done").performClick()
                instrumentation.uiAutomation.executeShellCommand("rm /sdcard/Download/$name").close()
                compose.onNodeWithTag("nav.back").performClick()
            }
        }
    }
}
