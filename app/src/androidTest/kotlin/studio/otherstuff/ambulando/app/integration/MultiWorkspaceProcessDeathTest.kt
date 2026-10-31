package studio.otherstuff.ambulando.app.integration

import android.content.Intent
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
import studio.otherstuff.ambulando.core.workspaces.WorkspaceColour

/**
 * The process-death check: phase 1 leaves distinct state in two workspaces, the driver force-stops the app, phase 2 runs in a
 * fresh process. Skipped unless the instrumentation argument `processDeath` is `phase1` or `phase2`.
 */
@RunWith(AndroidJUnit4::class)
class MultiWorkspaceProcessDeathTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(restoreNavigation: Boolean, block: (AppModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true)
            .putExtra(AppEnvironment.RESTORE_NAVIGATION_EXTRA, restoreNavigation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.entries?.size == 2 }
            block(app.activeModel!!)
        }
    }

    private fun AppModel.entry(label: String) = entries.first { it.workspace.label == label }
    private fun gate(phase: String) = org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("processDeath") == phase)

    /** Phase 1 of the process-death check: leave distinct state in two workspaces (run by the driver script, then the app is force-stopped). */
    @Test fun phase1LeaveDifferentSectionsAndDraftsInTwoWorkspaces() { gate("phase1"); launch(restoreNavigation = true) { model ->
        compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
        compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
        val studio = model.entry("Sample Studio"); val lab = model.entry("Sample Lab")
        compose.runOnIdle { model.openWorkspace(studio) }
        compose.waitUntil(20_000) { model.activeBoundaryId == studio.id && model.channels.any { it.id == SampleIds.GENERAL } }
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.TASKS }
        compose.waitUntil(10_000) { model.section == AppSection.TASKS }
        runBlocking { studio.runtime!!.store.writer.saveDraft(ChatTarget(studio.connection.id, SampleIds.STUDIO, SampleIds.GENERAL), Draft("studio draft", emptyList())) }
        compose.runOnIdle { model.openWorkspace(lab) }
        compose.waitUntil(20_000) { model.activeBoundaryId == lab.id }
        compose.runOnIdle { model.section = AppSection.DOCS }
        compose.waitUntil(10_000) { model.section == AppSection.DOCS }
        runBlocking { lab.runtime!!.store.writer.saveDraft(ChatTarget(lab.connection.id, SampleIds.LAB, SampleIds.GENERAL), Draft("lab draft", emptyList())) }
        // Let the debounced route state reach disk, then leave through a switch (which flushes the route state of the workspace left).
        compose.runOnIdle { model.openWorkspace(studio) }
        compose.waitUntil(20_000) { model.activeBoundaryId == studio.id }
        // Switching back puts Studio's own section back (saved when it was left), and Lab's was saved the same way.
        compose.waitUntil(20_000) { model.section == AppSection.TASKS }
        compose.waitUntil(10_000) { runBlocking { studio.runtime!!.store.reader.state("nav-stacks:${studio.connection.id}:${SampleIds.STUDIO}") }?.contains("\"section\":\"tasks\"") == true }
        compose.waitUntil(10_000) { runBlocking { lab.runtime!!.store.reader.state("nav-stacks:${lab.connection.id}:${SampleIds.LAB}") }?.contains("\"section\":\"docs\"") == true }
    } }

    /** Phase 2, in a fresh process: the same workspaces are enabled, the one in front and each one's section and draft are back. */
    @Test fun phase2AfterProcessDeathEverythingIsBackPerWorkspace() { gate("phase2"); launch(restoreNavigation = true) { model ->
        compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.activeEntry != null }
        val studio = model.entry("Sample Studio"); val lab = model.entry("Sample Lab")
        assertEquals(studio.id, model.activeBoundaryId)
        compose.waitUntil(20_000) { model.section == AppSection.TASKS }
        assertEquals("studio draft", runBlocking { studio.runtime!!.store.reader.draft(ChatTarget(studio.connection.id, SampleIds.STUDIO, SampleIds.GENERAL)) }?.text)
        assertEquals("lab draft", runBlocking { lab.runtime!!.store.reader.draft(ChatTarget(lab.connection.id, SampleIds.LAB, SampleIds.GENERAL)) }?.text)
        compose.runOnIdle { model.openWorkspace(lab) }
        compose.waitUntil(20_000) { model.activeBoundaryId == lab.id && model.section == AppSection.DOCS }
    } }
}
