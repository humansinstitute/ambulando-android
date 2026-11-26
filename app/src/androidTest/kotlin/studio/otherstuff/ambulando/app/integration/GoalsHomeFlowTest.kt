package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.file
import java.time.LocalDate

/**
 * Home and the goal form, rendered on a device against the in-process synthetic NStack (no member data, no network).
 * Keys are real key events sent to the focused window. Screenshots go to the app's external files dir, prefixed with the
 * `shotPrefix` instrumentation argument (one run per screen/font configuration).
 */
@RunWith(AndroidJUnit4::class)
class GoalsHomeFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val prefix get() = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "default"

    @Before fun freshSyntheticFixture() {
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG)
        if ((context.applicationContext as AmbulandoApplication).activeModel != null) return
        val sample = AppEnvironment.Sample(context).baseDir
        check(sample.parentFile == context.cacheDir)
        check(!sample.exists() || sample.deleteRecursively())
    }

    private fun has(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String, ms: Long = 30_000) = try { compose.waitUntil(ms) { has(tag) } }
        catch (e: ComposeTimeoutException) { compose.onAllNodes(isRoot()).printToLog("GoalsHome"); throw AssertionError("missing $tag", e) }
    private fun gone(tag: String) = compose.waitUntil(30_000) { !has(tag) }
    private fun tap(tag: String) { waitTag(tag); compose.onNodeWithTag(tag).also { runCatching { it.performScrollTo() } }.performClick(); compose.waitForIdle() }
    private fun focused(tag: String) = compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""
    private fun shot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(400)
        instrumentation.uiAutomation.takeScreenshot().let { bmp ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$prefix-$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    /** A real key press (down and up) with modifier [meta], delivered to the focused window like a hardware keyboard. */
    private fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
        compose.waitForIdle()
    }
    private fun type(s: String) { instrumentation.sendStringSync(s); compose.waitForIdle() }
    private fun focusOrder(): List<String> = compose.onAllNodes(hasTestTag("home.goal.name"), useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }

    @Test fun homeGoalsFormKeyboardOrderBriefingLatestAndDeck() {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { w -> w.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { w -> w.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { c -> c.id == SampleIds.GENERAL } && model.identityState is IdentityState.Unlocked }
            // A fresh, unfiltered workspace opens on Home.
            compose.runOnIdle { assertTrue(model.context.isHome); assertEquals(AppSection.HOME, model.section) }
            waitTag("home.screen"); shot("00-fresh-launch-home")
            compose.runOnIdle { model.section = AppSection.TASKS }

            // Home is its own surface; Deck stays a separate section.
            section("home")
            compose.waitUntil(30_000) { model.section == AppSection.HOME && model.context.isHome }
            waitTag("home.screen"); waitTag("home.goals.title")
            shot("00-home-top")
            // On a phone the briefing and Latest follow the goals in one scrolling column.
            reveal("home.briefing"); waitTag("home.briefing")
            reveal("home.latest.title")
            compose.onNodeWithTag("home.goals").performScrollToIndex(0)
            compose.onNodeWithTag("section.home").assertIsSelected()
            assertFalse("Home is not Deck", has("deck.track"))
            shot("01-home-setup")
            section("deck"); waitTag("deck.track"); assertFalse(has("home.screen")); shot("02-deck-separate")
            section("home"); waitTag("home.screen")

            // First use: the private space (the synthetic Tower refuses Ops, so the disclosed owner-only fallback is used).
            tap("goals.setup.scope.${SampleIds.SCOPE_OPS}"); tap("goals.setup.create"); waitTag("goals.setup.ownerOnly")
            tap("goals.setup.ownerOnly"); compose.waitUntil(30_000) { has("home.goals.empty") || has("home.goals.title") }
            compose.onNodeWithTag("home.goals").performScrollToIndex(0); waitTag("home.goals.new")
            waitTag("home.goals.empty")
            reveal("home.briefing.empty")
            compose.onNodeWithTag("home.goals").performScrollToIndex(0)

            // The form: labels above, first field focused, real keys.
            tap("home.goals.new"); waitTag("goal.input.form")
            focused("goal.input.name")
            compose.onNodeWithTag("goal.input.save").assertIsNotEnabled()
            shot("03-form-open-keyboard")
            type("Ship Ambulando")
            key(KeyEvent.KEYCODE_ENTER)
            focused("goal.input.description")
            assertEquals("Enter in the name never saves or adds a line", "Ship Ambulando", text("goal.input.name"))
            type("Make it the place I use every day.")
            key(KeyEvent.KEYCODE_ENTER)
            type("Second line")
            assertEquals("Make it the place I use every day.\nSecond line", text("goal.input.description"))
            shot("04-form-typed-caret")
            key(KeyEvent.KEYCODE_TAB); focused("goal.input.start"); shot("05-form-tab-to-date")
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON); focused("goal.input.description")
            assertFalse("Tab is never typed", text("goal.input.description").contains('\t'))
            compose.onNodeWithTag("goal.input.save").assertIsDisplayed().assertIsEnabled()
            key(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            waitTag("goal.detail"); gone("goal.input.form")
            compose.runOnIdle { assertEquals("a goal opened from Home stays on Home's stack", AppSection.HOME, model.section) }
            androidx.test.espresso.Espresso.pressBack(); waitTag("home.screen")

            // Escape cancels without saving.
            tap("home.goals.new"); focused("goal.input.name"); type("Never saved")
            key(KeyEvent.KEYCODE_ESCAPE); gone("goal.input.form")
            assertTrue(compose.onAllNodesWithText("Never saved").fetchSemanticsNodes().isEmpty())

            // More goals with dates and checkpoints, saved through the same File API as the app.
            val c = model.connection!!; val w = model.workspace!!
            val today = LocalDate.now()
            val seeded = GoalsController(model.fileNappletAPI(c), w.id, w.appNpub, c.signerNpub) { true }
            runBlocking {
                seeded.refresh()
                val first = seeded.state.value.goals.single { it.goal.name == "Ship Ambulando" }
                seeded.edit(first.id, GoalEdit.SetStartDate(null, today.toString()), GoalEdit.SetTargetDate(null, today.plusDays(22).toString()),
                    GoalEdit.AddCheckpoint(GoalCheckpoint("agree", "Agree on the first version", today.toString(), reached = true)),
                    GoalEdit.AddCheckpoint(GoalCheckpoint("daily", "Use it daily on Android", today.toString())),
                    GoalEdit.AddCheckpoint(GoalCheckpoint("users", "Ready for the first users", today.plusDays(22).toString())))
                for ((i, name) in listOf("Write the onboarding guide", "Tidy the release process", "Learn the new tools", "Plan next quarter").withIndex())
                    seeded.create(Goal.create(name, "The description of $name.", today.plusDays(i + 1L).toString(), today.plusDays(40L + i).toString()))
            }
            // Saved outside Home's controller: a files sync tells Home to reload, as when an agent or another device saves.
            compose.runOnIdle { model.refresh() }
            // Calm by default: the three goals in focus and a peek at the fourth, then "Show all".
            compose.waitUntil(90_000) { focusOrder().size == 4 }
            compose.onNodeWithTag("home.goals").performScrollToIndex(0)
            shot("06-home-goals-briefing")
            compose.onNodeWithTag("home.goals").performScrollToNode(hasTestTag("home.goals.more"))
            assertEquals(4, focusOrder().size)
            compose.onNodeWithTag("home.goals.more").assertTextEquals("Show all 5 goals")
            shot("06b-home-goals-peek")
            tap("home.goals.more")
            compose.waitUntil(30_000) { focusOrder().size == 5 }
            val before = focusOrder()
            for ((i, node) in compose.onAllNodes(hasTestTagPrefix("home.goal.")).fetchSemanticsNodes().filter { it.config.getOrNull(SemanticsProperties.StateDescription) != null }.withIndex())
                assertEquals(i < 3, node.config[SemanticsProperties.StateDescription].startsWith("In focus"))
            compose.onNodeWithTag("home.goals").performScrollToNode(hasTestTag("home.goals.hint")); waitTag("home.goals.hint")
            compose.onNodeWithTag("home.goals").performScrollToNode(hasText(before[4]))
            shot("07-home-goals-overflow")

            // Choose focus: move the fifth goal to the top; it persists in Tower and survives leaving Home.
            compose.onAllNodesWithTag("home.goal.menu")[4].performScrollTo().performClick()
            tap("home.goal.top")
            compose.waitUntil(60_000) { focusOrder().firstOrNull() == before[4] }
            assertEquals(listOf(before[4]) + before.take(4), focusOrder())
            section("deck"); waitTag("deck.track"); section("home"); waitTag("home.screen")
            compose.waitUntil(30_000) { focusOrder().firstOrNull() == before[4] }
            runBlocking { seeded.refresh() }
            assertEquals("stored order", before[4], GoalFocus.order(seeded.state.value.goals).first().goal.name)
            compose.onNodeWithTag("home.goals").performScrollToIndex(0)
            shot("08-home-reordered")

            // A goal card opens the approved detail; Back returns Home.
            compose.onAllNodes(hasTestTagPrefix("home.goal.") and hasClickAction()).onFirst().performClick()
            waitTag("goal.detail"); shot("09-goal-detail"); androidx.test.espresso.Espresso.pressBack(); waitTag("home.screen")

            // A mention of a goal's File (as an agent would write) opens the goal, not its JSON.
            val shipId = runBlocking { seeded.refresh(); seeded.state.value.goals.first { it.goal.name == "Ship Ambulando" }.id }
            compose.waitUntil(30_000) { runBlocking { model.store.reader.file(w.id, shipId) } != null }
            compose.runOnIdle { model.pendingRecordOpen = "file" to shipId }
            waitTag("goal.detail"); shot("09b-goal-file-mention")
            androidx.test.espresso.Espresso.pressBack(); section("home"); waitTag("home.screen")

            // Daily Briefing: today's dated facts; another day shows only what is dated then.
            reveal("home.briefing")
            compose.onAllNodesWithTag("home.briefing.fact").assertAny(hasText("Use it daily on Android", substring = true))
            compose.onNodeWithTag("home.briefing.day").assertTextEquals("Today, ${GoalFormat.date(today.toString())}")
            compose.onNodeWithTag("home.briefing.next").assertIsNotEnabled()
            tap("home.briefing.prev"); compose.onNodeWithTag("home.briefing.day").assertTextEquals(GoalFormat.date(today.minusDays(1).toString())!!)
            shot("10-briefing-previous-day")
            tap("home.briefing.today"); compose.onNodeWithTag("home.briefing.day").assertTextEquals("Today, ${GoalFormat.date(today.toString())}")

            // Latest: at most five, one per conversation; opening one stays on Home's stack.
            reveal("home.latest.title")
            compose.waitUntil(30_000) { has("home.latest.row") || has("home.latest.empty") }
            if (has("home.latest.row")) reveal("home.latest.row")
            val rows = compose.onAllNodesWithTag("home.latest.row").fetchSemanticsNodes()
            assertTrue(rows.size in 0..5)
            shot("11-latest")
            if (rows.isNotEmpty()) {
                compose.onAllNodesWithTag("home.latest.row").onFirst().performClick()
                compose.waitUntil(30_000) { !has("home.screen") || model.section != AppSection.HOME }
                compose.runOnIdle { assertEquals(AppSection.HOME, model.section) }
                shot("12-latest-opened"); androidx.test.espresso.Espresso.pressBack(); waitTag("home.screen")
            }
            section("deck"); waitTag("deck.track")
            compose.runOnIdle { assertEquals(AppSection.DECK, model.section) }
        }
    }

    /** A goal card ("home.goal.<file id>"), not one of its parts. */
    /** Opens a section by its strip button (on a wide window the same section also sits in the sidebar list). */
    private fun section(key: String) {
        waitTag("section.$key")
        val node = compose.onAllNodesWithTag("section.$key")[0]
        runCatching { node.performClick() }.onFailure { node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
        compose.waitForIdle()
    }
    /** Scrolls [tag] into view: in the single phone column, or the side column of a wide window. */
    private fun reveal(tag: String) {
        val list = if (has("home.side")) "home.side" else "home.goals"
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)); waitTag(tag)
    }
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("goal card") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: ""
        tag.startsWith(prefix) && tag.length >= prefix.length + 36
    }
}
