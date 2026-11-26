package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.tasks

/** Full native Goals experience against the in-process synthetic NStack: no member data, no network. */
@RunWith(AndroidJUnit4::class)
class GoalsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun freshSyntheticFixture() {
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG)
        if ((context.applicationContext as AmbulandoApplication).activeModel != null) return
        val sample = AppEnvironment.Sample(context).baseDir
        check(sample.parentFile == context.cacheDir)
        check(!sample.exists() || sample.deleteRecursively())
    }

    private fun waitTag(tag: String, ms: Long = 30_000) = try {
        compose.waitUntil(ms) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: ComposeTimeoutException) { android.util.Log.e("GoalsFlow", "MISSING $tag"); compose.onAllNodes(isRoot()).printToLog("GoalsFlow"); throw e }
    /** Brings an off-screen row of the goal's lazy list into composition before it is used. */
    private fun reveal(tag: String) {
        if (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() && compose.onAllNodes(hasTestTag("goal.detail")).fetchSemanticsNodes().isNotEmpty())
            runCatching { compose.onNodeWithTag("goal.detail").performScrollToNode(hasTestTag(tag)) }
    }
    private fun tap(tag: String) {
        // The goal's rows are lazily composed: compose the target, scroll it fully into view, and only then touch it.
        reveal(tag); waitTag(tag)
        if (compose.onAllNodes(hasTestTag("goal.detail")).fetchSemanticsNodes().isNotEmpty())
            runCatching { compose.onNodeWithTag("goal.detail").performScrollToNode(hasTestTag(tag)) }
        val node = compose.onNodeWithTag(tag); runCatching { node.performScrollTo() }
        compose.waitForIdle(); node.assertIsDisplayed().performClick()
    }
    /** Rows of the goal's lazy list are disposed once scrolled away: bring the row back before asserting on it. */
    private fun assertDisabled(tag: String) { reveal(tag); waitTag(tag); compose.onNodeWithTag(tag).assertIsNotEnabled() }
    private fun type(tag: String, text: String) { waitTag(tag); compose.onNodeWithTag(tag).performTextReplacement(text) }
    private fun gone(tag: String) = compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
    private fun shot(name: String) = instrumentation.uiAutomation.takeScreenshot().let { bmp ->
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun createEditCheckpointsLinkAndLifecycle() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { w -> w.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { w -> w.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { c -> c.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.section = AppSection.DECK }

            // Entry from Home (Deck); first use asks where the private space lives.
            tap("deck.inbox.goals"); waitTag("goals.setup"); shot("goals-setup")
            compose.onAllNodesWithTag("goals.setup.ownerOnly").assertCountEquals(0)   // the fallback is offered only after a refusal
            // The synthetic Tower refuses channel creation in Ops: the refusal is explained and the disclosed fallback appears.
            tap("goals.setup.scope.${SampleIds.SCOPE_OPS}"); tap("goals.setup.create"); waitTag("goals.setup.error")
            waitTag("goals.setup.ownerOnly"); shot("goals-setup-refused")
            tap("goals.setup.ownerOnly")
            waitTag("goals.ownerOnly"); compose.onNodeWithTag("goals.new").assertIsEnabled()

            // Create with validation.
            tap("goals.new")
            type("goal.input.name", "Ship Ambulando"); type("goal.input.description", " ")
            tap("goal.input.save"); waitTag("goal.input.error")
            type("goal.input.description", "Use it every day")
            tap("goal.input.target"); tap("goal.input.target.ok")
            tap("goal.input.save")
            waitTag("goal.detail"); compose.onNodeWithTag("goal.name").assertTextEquals("Ship Ambulando")
            compose.onNodeWithTag("goal.description").assertTextEquals("Use it every day")

            // Timeline: add, reach, reorder, edit.
            for (title in listOf("Agree first version", "Use daily", "Ready for users")) {
                tap("goal.addCheckpoint"); type("checkpoint.input.title", title); tap("checkpoint.input.save")
                compose.waitUntil(30_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNodeWithTag("goal.progress").assertTextEquals("0 of 3 checkpoints reached")
            compose.onAllNodesWithTag("goal.reach")[0].performClick()
            compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("goal.progress").assertTextEquals("1 of 3 checkpoints reached") }.isSuccess }
            compose.onAllNodesWithTag("goal.checkpoint.menu")[2].performScrollTo().performClick()
            tap("goal.checkpoint.up")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("goal.checkpoint.title")[1].fetchSemanticsNode().config.toString().contains("Ready for users") }
            shot("goal-detail")

            // Associated work across channels: link an existing task, then unlink without deleting it.
            tap("goal.link"); waitTag("goal.picker")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("goal.picker.item").fetchSemanticsNodes().isNotEmpty() }
            val picked = compose.onAllNodesWithTag("goal.picker.item")[0]
            picked.performClick()
            gone("goal.picker")
            waitTag("goal.link.task"); shot("goal-linked")
            val tasksBefore = runBlockingTaskCount(model)
            tap("goal.unlink"); gone("goal.link.task")
            assertEquals("Unlinking never deletes the source", tasksBefore, runBlockingTaskCount(model))

            // Complete, reopen, archive (read-only), restore.
            tap("goal.complete"); tap("goal.confirm"); waitTag("goal.reopen")
            tap("goal.reopen"); waitTag("goal.complete")
            tap("goal.archive"); tap("goal.confirm"); waitTag("goal.restore")
            assertDisabled("goal.edit")
            assertDisabled("goal.addCheckpoint")
            tap("goal.restore"); waitTag("goal.archive")

            // Reopen from the list: everything persisted in Tower.
            androidx.test.espresso.Espresso.pressBack()
            waitTag("goals.card"); compose.onNodeWithTag("goals.card").performClick()
            waitTag("goal.detail"); compose.onNodeWithTag("goal.progress").assertTextEquals("1 of 3 checkpoints reached")
        }
    }

    private fun runBlockingTaskCount(model: AppModel) = kotlinx.coroutines.runBlocking { model.store.reader.tasks(SampleIds.STUDIO, null, null, false, 500).size }
}
