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
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.upsertFiles

/** Synthetic identity and disposable app data only; never signs into a user's workspace. */
@RunWith(AndroidJUnit4::class)
class GoalMentionsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun browseCompleteSendAndOpenPersonalGoal() {
        check(BuildConfig.DEBUG)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = AppEnvironment.Sample(context).baseDir
        check(sample.parentFile == context.cacheDir)
        if ((context.applicationContext as AmbulandoApplication).activeModel == null && sample.exists()) check(sample.deleteRecursively())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.workspace?.id == SampleIds.STUDIO && model.identityState is IdentityState.Unlocked &&
                model.channels.any { it.id == SampleIds.GENERAL } && model.activeEntry?.runtime?.writeActivity?.isOpen == true }
            val controller = model.goalsController()!!
            val goal = runBlocking {
                controller.refresh()
                if (controller.state.value.space is GoalsSpaceState.NeedsSetup) controller.setUp(null, emptyList())
                controller.create(Goal.create("Training sign up onboarding to Ambulando", "Join training"))
            }
            val candidates = runBlocking { model.mentionCandidates("goal:") }
            assertTrue(candidates.any { it.mention.id == goal.id })
            assertEquals(goal.id, runBlocking { model.mentionCandidates("tRaInInG sign up") }.first().mention.id)
            // An authorized goal File outside the personal home must not leak back through ordinary File search.
            val outside = runBlocking {
                val w = model.workspace!!
                model.towerAPI(model.connection!!).createGoal(w.id, w.appNpub, SampleIds.GENERAL,
                    Goal.create("Training sign up outside personal home", "Another channel's goal")).also {
                    model.store.writer.upsertFiles(w.id, listOf(it.file))
                }
            }
            assertFalse(runBlocking { model.mentionCandidates("training sign up") }.any { it.mention.id == outside.id })
            assertFalse(runBlocking { model.mentionCandidates("") }.any { it.mention.id == outside.id })
            assertTrue(runBlocking { model.mentionCandidates("file:training sign up") }.any { it.mention.id == outside.id })
            compose.runOnIdle {
                val channel = model.channels.first { it.id == SampleIds.GENERAL }
                model.section = AppSection.CHAT; model.selectContext(channel.scopeId, channel.id)
            }
            waitTag("chat.inbox.new")
            compose.onNodeWithTag("chat.inbox.new").performClick()
            waitTag("chat.composer")
            compose.onNodeWithTag("chat.composer").performTextInput("@goal:")
            waitTag("chat.mention.file.${goal.id}")
            compose.onNodeWithTag("chat.composer").performTextReplacement("@Training sign up")
            waitTag("chat.mention.file.${goal.id}")
            compose.onNodeWithTag("chat.mention.file.${goal.id}").performClick()
            compose.onNodeWithTag("chat.composer").assertTextEquals("@Training sign up onboarding to Ambulando ")
            compose.onNodeWithContentDescription("Send").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(goal.goal.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(goal.goal.name) and hasAnyAncestor(hasTestTag("chat.messages")), useUnmergedTree = true).performTouchInput { click(center) }
            waitTag("goal.name")
            compose.onNodeWithTag("goal.name").assertTextEquals(goal.goal.name)
        }
    }
}
