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
 * Several workspaces enabled at once, on one Tower and on two Towers that serve the same workspace ids, against the in-process
 * synthetic Towers of sample data. Nothing here is evidence of live Tower or physical-phone behaviour.
 */
@RunWith(AndroidJUnit4::class)
class MultiWorkspaceFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(block: (AppModel, ActivityScenario<MainActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.entries?.size == 2 }
            val model = app.activeModel!!
            try { block(model, scenario) } finally { restore(model) }
        }
    }

    private fun AppModel.entry(label: String, host: String? = null) =
        entries.first { it.workspace.label == label && (host == null || it.connection.origin.contains(host)) }

    private fun enableAll(model: AppModel) {
        compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
        compose.waitUntil(30_000) { model.enabledEntries.size == model.entries.size && model.enabledEntries.all { it.runtime?.canSend == true } }
    }

    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/mw-$name.png").close()
    }

    /** The synthetic Towers and the stored sample data outlive a test inside one run, so every test puts back what it changed. */
    private fun restore(model: AppModel) {
        model.sampleTower!!.offline = false
        model.sampleTower!!.revokedWorkspaces = emptySet()
        runBlocking { model.connections.filter { it.origin.contains("second.sample") }.forEach { model.disconnect(it) } }
        compose.runOnIdle { model.retryConnection() }
        compose.waitUntil(30_000) { model.entries.size == 2 && model.entries.none { it.workspace.accessRemoved } }
        compose.runOnIdle { model.entries.forEach { model.setWorkspaceEnabled(it, true) } }
        compose.waitUntil(30_000) { model.enabledEntries.size == 2 && model.enabledEntries.all { it.runtime?.canSend == true } }
    }

    private fun openSidebar() {
        compose.onNodeWithTag("context.bar").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("sidebar").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun twoEnabledWorkspacesStackWithSharedUnreadAndSwitchWithoutStoppingEachOther() = launch { model, _ ->
        enableAll(model)
        val studio = model.entry("Sample Studio"); val lab = model.entry("Sample Lab")
        assertNotEquals(studio.runtime!!.store.path, lab.runtime!!.store.path)
        compose.runOnIdle { model.openWorkspace(studio) }
        compose.waitUntil(20_000) { model.activeBoundaryId == studio.id && model.channels.any { it.id == SampleIds.GENERAL } }
        compose.runOnIdle { model.setWorkspaceColour(lab, WorkspaceColour.PURPLE) }
        openSidebar()
        compose.onNodeWithTag("workspace.collapse.Sample Studio").assertExists()
        compose.onNodeWithTag("workspace.collapse.Sample Lab").assertExists()
        compose.onNodeWithTag("workspace.home.Sample Lab").assertExists()
        shot("stacked-sidebar")
        // Open a channel of the other workspace from the stacked column: that workspace comes to the front, the first keeps running.
        val labChannel = lab.nav.groups.flatMap { it.channels }.first()
        compose.onNodeWithTag("workspace.channel.Sample Lab.${lab.nav.label(labChannel)}").performClick()
        compose.waitUntil(20_000) { model.activeBoundaryId == lab.id && model.context.channelId == labChannel.id }
        assertTrue(studio.runtime!!.canSend && studio.runtime!!.activity.isOpen)
        assertEquals(setOf(studio.id, lab.id), model.enabledEntries.map { it.id }.toSet())
        // The destination is always visible where something is composed, and the header carries the workspace colour.
        compose.runOnIdle { model.section = AppSection.CHAT }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("composer.workspace").assertExists()
        compose.onNodeWithText("Working in Sample Lab").assertIsDisplayed()
        shot("working-in-lab")
    }

    @Test fun aSendGoesOnlyToItsOwnWorkspaceAndPendingWorkSurvivesPauseAndReEnable() = launch { model, _ ->
        enableAll(model)
        val studio = model.entry("Sample Studio"); val lab = model.entry("Sample Lab")
        compose.runOnIdle { model.openWorkspace(studio) }
        compose.waitUntil(20_000) { model.activeBoundaryId == studio.id && model.channels.any { it.id == SampleIds.GENERAL } }
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.CHAT }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val body = "Only in Studio ${System.nanoTime()}"
        compose.onNodeWithTag("chat.composer").performTextReplacement(body)
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(20_000) { runBlocking { studio.runtime!!.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == body } } }
        assertTrue(runBlocking { lab.runtime!!.store.reader.latestMessages(SampleIds.LAB, SampleIds.GENERAL, 500).none { it.body == body } })

        // Offline: a message queued in Studio stays pending, Studio is paused, then re-enabled: it is sent exactly once.
        val sample = model.sampleTower!!
        sample.offline = true
        val pending = "Queued offline ${System.nanoTime()}"
        compose.onNodeWithTag("chat.composer").performTextReplacement(pending)
        compose.onNodeWithTag("chat.send").performClick()
        compose.waitUntil(20_000) { runBlocking { studio.runtime!!.store.reader.outbox(studio.connection.id, studio.connection.signerNpub).any { it.body == pending } } }
        val runtimeBefore = studio.runtime!!
        compose.runOnIdle { model.setWorkspaceEnabled(studio, false) }
        compose.waitUntil(20_000) { !studio.enabled && model.activeBoundaryId == lab.id }
        assertFalse(runtimeBefore.canSend)
        assertEquals(1, runBlocking { runtimeBefore.store.reader.outbox(studio.connection.id, studio.connection.signerNpub).size })
        sample.offline = false
        compose.runOnIdle { model.setWorkspaceEnabled(studio, true) }
        compose.waitUntil(30_000) { studio.runtime?.canSend == true }
        compose.waitUntil(30_000) { runBlocking { studio.runtime!!.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).count { it.body == pending } == 1 } }
        compose.waitUntil(30_000) { runBlocking { studio.runtime!!.store.reader.outbox(studio.connection.id, studio.connection.signerNpub).isEmpty() } }
    }

    @Test fun aSecondTowerWithTheSameWorkspaceIdsIsAddedThroughTheChooserAndStaysIsolated() = launch { model, _ ->
        compose.runOnIdle { model.setWorkspaceEnabled(model.entry("Sample Studio"), true) }
        compose.waitUntil(30_000) { model.entry("Sample Studio").runtime?.canSend == true }
        openSidebar()
        compose.onNodeWithTag("sidebar.workspace").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace.selection").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("workspace.addTower").performClick()
        compose.onNodeWithTag("connect.origin").performTextReplacement("https://second.sample.invalid")
        compose.onNodeWithTag("connect.check").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("connect.go").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("connect.go").performClick()
        compose.waitUntil(30_000) { model.connections.size == 2 && model.entries.size == 4 }
        shot("chooser-two-towers")
        // Same workspace id on both Towers: distinct boundaries, distinct partitions, both enabled.
        val a = model.entry("Sample Studio", "sample.tower"); val b = model.entry("Sample Studio", "second.sample")
        assertEquals(a.workspace.id, b.workspace.id)
        assertNotEquals(a.id, b.id)
        compose.runOnIdle { model.setWorkspaceEnabled(b, true) }
        compose.waitUntil(30_000) { a.runtime?.canSend == true && b.runtime?.canSend == true }
        assertNotEquals(a.runtime!!.store.path, b.runtime!!.store.path)
        // A write composed in the second Tower's workspace is stored and sent there only.
        compose.runOnIdle { model.openWorkspace(b) }
        compose.waitUntil(20_000) { model.activeBoundaryId == b.id && model.channels.any { it.id == SampleIds.GENERAL } }
        compose.runOnIdle { val c = model.channels.first { it.id == SampleIds.GENERAL }; model.selectContext(c.scopeId, c.id); model.section = AppSection.CHAT }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val body = "For the second Tower ${System.nanoTime()}"
        compose.onNodeWithTag("chat.composer").performTextReplacement(body)
        compose.onNodeWithTag("chat.send").performClick()
        try {
            compose.waitUntil(20_000) { runBlocking { b.runtime!!.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == body } } }
        } catch (failure: Throwable) {
            val rt = b.runtime!!
            throw AssertionError("Not stored in B. outbox=${runBlocking { rt.store.reader.outbox(b.connection.id, b.connection.signerNpub) }.map { it.state to it.lastError }} " +
                "canSend=${rt.canSend} validated=${rt.validated} active=${model.activeBoundaryId == b.id} notice=${model.notice} workspace=${model.workspace?.label}@${model.connection?.origin} " +
                "aSaw=${runBlocking { a.runtime!!.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).any { it.body == body } }}", failure)
        }
        assertTrue(runBlocking { a.runtime!!.store.reader.latestMessages(SampleIds.STUDIO, SampleIds.GENERAL, 500).none { it.body == body } })
        assertTrue(model.secondSampleTower!!.messageBodies(SampleIds.STUDIO).contains(body))
        assertFalse(model.sampleTower!!.messageBodies(SampleIds.STUDIO).contains(body))
    }

    @Test fun lostAccessLabelsTheWorkspaceKeepsItsContentAndClosesItsWritesWhileOthersWork() = launch { model, _ ->
        enableAll(model)
        val studio = model.entry("Sample Studio"); val lab = model.entry("Sample Lab")
        compose.runOnIdle { model.openWorkspace(studio) }
        compose.waitUntil(20_000) { model.activeBoundaryId == studio.id }
        val before = runBlocking { lab.runtime!!.store.reader.latestMessages(SampleIds.LAB, SampleIds.GENERAL, 500).size }
        model.sampleTower!!.revokedWorkspaces = setOf(SampleIds.LAB)
        compose.runOnIdle { model.retryConnection() }
        compose.waitUntil(30_000) { lab.workspace.accessRemoved && lab.runtime?.canSend == false }
        openSidebar()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace.lost.Sample Lab").fetchSemanticsNodes().isNotEmpty() }
        shot("access-lost")
        assertEquals(before, runBlocking { lab.runtime!!.store.reader.latestMessages(SampleIds.LAB, SampleIds.GENERAL, 500).size })
        assertTrue(studio.runtime!!.canSend)
    }
}
