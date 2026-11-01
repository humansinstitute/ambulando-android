package studio.otherstuff.ambulando.app.integration

import android.util.Log
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import studio.otherstuff.ambulando.app.AppModel
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import java.io.File

/**
 * Runs a flow-test body and, if it fails, records what the app and the window system held at that moment before the failure is
 * rethrown: the merged and unmerged semantics tree, the focused window, the sync engine's running/queued work and every task's stored
 * relationship (completeness, restriction, children). Captured for the flows that fail intermittently (a tap that opens no dialog,
 * a subtask count that does not settle, a touch the framework cannot inject), where the failing assertion alone does not say which
 * side was out of step. Written to the app's external files `failure-capture/` and to logcat tag `AmbCapture`; it changes no
 * behaviour of the test and never masks the original failure.
 */
internal fun <T> captureOnFailure(compose: ComposeTestRule, model: AppModel, label: String, block: () -> T): T {
    try { return block() } catch (failure: Throwable) {
        runCatching { record(compose, model, label, failure) }
        throw failure
    }
}

private fun section(title: String, body: () -> String): String =
    "=== $title ===\n" + runCatching(body).getOrElse { "unavailable: ${it::class.simpleName}: ${it.message}" } + "\n"

private fun record(compose: ComposeTestRule, model: AppModel, label: String, failure: Throwable) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val shell = { command: String ->
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        java.io.FileInputStream(out.fileDescriptor).bufferedReader().use { it.readText() }
    }
    val report = buildString {
        append("failure: ${failure::class.qualifiedName}: ${failure.message}\n")
        append(section("windows and focus") {
            shell("dumpsys window").lineSequence()
                .filter { Regex("mCurrentFocus|mFocusedApp|mFocusedWindow|mInputMethodTarget|Window #|isOnScreen|mHasSurface").containsMatchIn(it) }
                .joinToString("\n")
        })
        append(section("sync engine") { val s = model.engine.status.value; "running=${s.running}\nqueued=${s.queued}\nerrors=${s.errors}" })
        append(section("task relationships (stored)") {
            val reader = model.store.reader
            val lines = runBlocking {
                reader.tasks(SampleIds.STUDIO, includeArchived = true).map { task ->
                    "${task.id} \"${task.title}\" children=${reader.directSubtasks(SampleIds.STUDIO, task.id).size} " +
                        "relationship=${reader.taskRelationship(SampleIds.STUDIO, task.id)}"
                }
            }
            lines.joinToString("\n")
        })
        append(section("semantics (merged)") { compose.onRoot().printToString(Int.MAX_VALUE) })
        append(section("semantics (unmerged)") { compose.onRoot(useUnmergedTree = true).printToString(Int.MAX_VALUE) })
    }
    val name = "$label-${System.currentTimeMillis()}.txt"
    runCatching {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "failure-capture").apply { mkdirs() }
        File(dir, name).writeText(report)
    }
    report.chunked(3500).forEachIndexed { i, part -> Log.e("AmbCapture", "$name [$i] $part") }
}
