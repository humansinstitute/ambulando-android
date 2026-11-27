package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.chat.threads
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.artefactFile
import studio.otherstuff.ambulando.core.surfaces.boardVersions
import studio.otherstuff.ambulando.core.transport.*

/** Native navigation and durable metadata over synthetic NStack; never opens a member workspace. */
@RunWith(AndroidJUnit4::class)
class GoalDiscussionsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) = try { compose.waitUntil(30_000) { has(tag) } }
        catch (e: ComposeTimeoutException) { missing(tag, e) }
    private fun missing(tag: String, cause: Throwable): Nothing {
        runCatching { capture("failure-${tag.replace('.', '-')}") }
        runCatching {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            roots.fetchSemanticsNodes().indices.forEach { roots[it].printToLog("GoalDiscussions", maxDepth = 12) }
        }
        compose.runOnIdle {
            val model = (instrumentation.targetContext.applicationContext as AmbulandoApplication).activeModel
            android.util.Log.d("GoalDiscussions", "Missing $tag; picker candidates: ${model?.let(::goalDiscussionChannels)?.map { it.id to it.name }}; scroll issue: ${cause.message}")
        }
        throw AssertionError("Missing $tag", cause)
    }
    private fun gone(tag: String) = compose.waitUntil(30_000) { !has(tag) }
    private fun reveal(tag: String) {
        // A modal's row is not a descendant of the Goal screen behind it. Scrolling that screen cannot reveal it.
        if (!has("goal.channelPicker") && has("goal.detail")) runCatching { compose.onNodeWithTag("goal.detail").performScrollToNode(hasTestTag(tag)) }
    }
    private fun tap(tag: String) {
        compose.waitForIdle()
        val pickerRow = tag.startsWith("goal.channelPicker.") && tag !in setOf(
            "goal.channelPicker.channels", "goal.channelPicker.save", "goal.channelPicker.unavailable", "goal.channelPicker.error")
        if (pickerRow) {
            waitTag("goal.channelPicker.channels")
            // A newly opened bottom sheet can compose the LazyColumn before it has scrollable bounds. Retry the
            // actual scroll after layout, rather than swallowing one early failure then waiting on an uncomposed row.
            var scrollIssue: Throwable? = null
            try {
                compose.waitUntil(10_000) {
                    if (has(tag)) true else {
                        runCatching { compose.onNodeWithTag("goal.channelPicker.channels").performScrollToNode(hasTestTag(tag)) }
                            .onFailure { scrollIssue = it }
                        has(tag)
                    }
                }
            } catch (e: ComposeTimeoutException) { missing(tag, scrollIssue ?: e) }
        }
        reveal(tag); waitTag(tag)
        compose.onNodeWithTag(tag).also { runCatching { it.performScrollTo() } }.assertIsDisplayed().performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("goal-discussions-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun unsetPickerShowsAccessibleDesignChannel() {
        check(BuildConfig.DEBUG)
        val context = instrumentation.targetContext
        val app = context.applicationContext as AmbulandoApplication
        if (app.activeModel == null) {
            val sample = AppEnvironment.Sample(context).baseDir
            check(sample.parentFile == context.cacheDir)
            check(!sample.exists() || sample.deleteRecursively())
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle {
                if (model.workspace?.id != SampleIds.STUDIO) model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO })
            }
            compose.waitUntil(30_000) {
                model.workspace?.id == SampleIds.STUDIO && model.workspace?.accessRemoved == false &&
                    (model.identityState as? IdentityState.Unlocked)?.npub == model.connection?.signerNpub &&
                    model.channels.any { it.id == SampleIds.DESIGN } && model.activeEntry?.runtime?.writeActivity?.isOpen == true
            }
            val created = CompletableDeferred<GoalDocument>()
            compose.runOnIdle {
                model.act {
                    try {
                        val controller = model.goalsController()!!
                        controller.refresh()
                        if (controller.state.value.space is GoalsSpaceState.NeedsSetup) controller.setUp(null, emptyList())
                        created.complete(controller.create(Goal.create("Focused unset picker", "Synthetic destination selection check")))
                    } catch (e: Exception) { created.completeExceptionally(e) }
                }
            }
            val goal = runBlocking { withTimeout(45_000) { created.await() } }
            compose.runOnIdle { model.section = AppSection.HOME }
            waitTag("home.goals")
            compose.onNodeWithTag("home.goals").performScrollToNode(hasTestTag("home.goal.${goal.id}"))
            tap("home.goal.${goal.id}"); waitTag("goal.detail")
            tap("goal.discuss"); waitTag("goal.channelPicker")
            compose.waitForIdle()
            capture("focused-unset-picker-before-selection")
            val candidates = compose.runOnIdle { goalDiscussionChannels(model).map { it.id to it.name } }
            val designBounds = compose.onAllNodesWithTag("goal.channelPicker.${SampleIds.DESIGN}", useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot }
            android.util.Log.d("GoalDiscussions", "Focused picker candidates=$candidates; Design bounds=$designBounds")
            compose.onNodeWithTag("goal.channelPicker", useUnmergedTree = true).printToLog("GoalDiscussions", maxDepth = 12)
            assertTrue("Design must be an accessible candidate", candidates.any { it.first == SampleIds.DESIGN })
            compose.onNodeWithTag("goal.channelPicker.save").assertIsNotEnabled()
            tap("goal.channelPicker.${SampleIds.DESIGN}")
            compose.onNodeWithTag("goal.channelPicker.save").assertIsEnabled()
            capture("focused-unset-picker-design-selected")
            val workspace = model.workspace!!
            val api = model.towerAPI(model.connection!!)
            fun goalChats(channel: String) = runBlocking {
                api.threads(workspace.id, workspace.appNpub, channel).items.filter {
                    GoalAssociation.read(it.raw.obj("metadata") ?: JsonObject(emptyMap())) == goal.id
                }
            }
            tap("goal.channelPicker.save")
            waitTag("chat.composer")
            compose.waitUntil(30_000) { goalChats(SampleIds.DESIGN).size == 1 }
            val designChat = goalChats(SampleIds.DESIGN).single()
            assertEquals(SampleIds.DESIGN, designChat.channelId)
            assertEquals(goal.id, GoalAssociation.read(designChat.raw.obj("metadata")!!))
            assertNull("an empty goal discussion adds no visible message boilerplate", designChat.sourceMessageId)
            assertNull("a one-off choice is not a saved default", runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal.defaultChannelId })
            capture("focused-design-native-chat")
            tap("nav.back"); waitTag("goal.detail")

            val unavailable = "99999999-9999-4999-8999-999999999999"
            val changed = CompletableDeferred<Unit>()
            compose.runOnIdle {
                model.act {
                    try { model.goalsController()!!.edit(goal.id, GoalEdit.SetDefaultChannel(null, unavailable)); changed.complete(Unit) }
                    catch (e: Exception) { changed.completeExceptionally(e) }
                }
            }
            runBlocking { withTimeout(30_000) { changed.await() } }
            val before = goalChats(SampleIds.GENERAL).size + goalChats(SampleIds.DESIGN).size
            tap("goal.discuss"); waitTag("goal.channelPicker.unavailable")
            compose.onNodeWithTag("goal.channelPicker.save").assertIsNotEnabled()
            assertEquals("an unavailable default must not route anywhere", before, goalChats(SampleIds.GENERAL).size + goalChats(SampleIds.DESIGN).size)
            assertEquals(unavailable, runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal.defaultChannelId })
            capture("focused-unavailable-default-no-fallback")
            tap("goal.channelPicker.${SampleIds.GENERAL}"); tap("goal.channelPicker.save")
            waitTag("chat.composer")
            compose.waitUntil(30_000) { goalChats(SampleIds.GENERAL).size == 1 }
            val replacementChat = goalChats(SampleIds.GENERAL).single()
            assertEquals(SampleIds.GENERAL, replacementChat.channelId)
            assertEquals(goal.id, GoalAssociation.read(replacementChat.raw.obj("metadata")!!))
            assertEquals("an explicit discussion choice does not silently replace the saved default", unavailable,
                runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal.defaultChannelId })
            assertEquals(1, goalChats(SampleIds.DESIGN).size)
            capture("focused-explicit-replacement-native-chat")
        }
    }

    @Test fun defaultsDiscussNativePinsAndCreatedOutputsStayConnected() {
        check(BuildConfig.DEBUG)
        val context = instrumentation.targetContext
        val app = context.applicationContext as AmbulandoApplication
        if (app.activeModel == null) {
            val sample = AppEnvironment.Sample(context).baseDir
            check(sample.parentFile == context.cacheDir)
            check(!sample.exists() || sample.deleteRecursively())
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) {
                model.identityState is IdentityState.Unlocked && model.channels.any { it.id == SampleIds.GENERAL } &&
                    model.channels.any { it.id == SampleIds.DESIGN } && model.activeEntry?.runtime?.writeActivity?.isOpen == true
            }
            val workspace = model.workspace!!
            val api = model.towerAPI(model.connection!!)
            val controller = model.goalsController()!!
            val goal = runBlocking {
                controller.refresh()
                if (controller.state.value.space is GoalsSpaceState.NeedsSetup) controller.setUp(null, emptyList())
                controller.create(Goal.create("Purposeful goal discussion", "Keep the decisions and useful outputs together"))
            }
            val originalDoc = runBlocking { api.doc(workspace.id, workspace.appNpub, SampleIds.CANVAS_DOC) }
            val originalBoard = runBlocking { api.artefactFile(workspace.id, workspace.appNpub, SampleIds.BOARD_FILE) }
            val originalBoardVersion = runBlocking { api.boardVersions(workspace.id, workspace.appNpub, originalBoard.id).first }
            runBlocking {
                model.store.writer.upsertDocs(workspace.id, listOf(originalDoc))
                model.store.writer.upsertFiles(workspace.id, listOf(originalBoard))
            }
            compose.runOnIdle { model.section = AppSection.HOME }
            waitTag("home.goals")
            compose.onNodeWithTag("home.goals").performScrollToNode(hasTestTag("home.goal.${goal.id}"))
            tap("home.goal.${goal.id}")
            waitTag("goal.detail")
            compose.onNodeWithTag("goal.defaultChannel").assertTextEquals("Ask each time")

            // The picker saves the actual destination; reopening reads the persisted default.
            tap("goal.chooseChannel"); tap("goal.channelPicker.${SampleIds.GENERAL}"); tap("goal.channelPicker.save")
            gone("goal.channelPicker")
            assertEquals(SampleIds.GENERAL, runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal.defaultChannelId })
            androidx.test.espresso.Espresso.pressBack()
            waitTag("home.goals"); tap("home.goal.${goal.id}")
            waitTag("goal.defaultChannel")
            compose.onNodeWithTag("goal.defaultChannel").assertTextContains(model.channels.first { it.id == SampleIds.GENERAL }.name, substring = true)

            fun goalChats(channel: String) = runBlocking {
                api.threads(workspace.id, workspace.appNpub, channel).items.filter {
                    GoalAssociation.read(it.raw.obj("metadata") ?: JsonObject(emptyMap())) == goal.id
                }
            }
            tap("goal.discuss")
            waitTag("thread.artefacts.open"); waitTag("chat.composer")
            compose.waitUntil(30_000) { goalChats(SampleIds.GENERAL).size == 1 }
            val discussion = goalChats(SampleIds.GENERAL).single()
            assertEquals(SampleIds.GENERAL, discussion.channelId)
            assertEquals(goal.id, GoalAssociation.read(discussion.raw.obj("metadata")!!))
            assertEquals("the primary association alone does not add boilerplate", null, discussion.sourceMessageId)
            compose.onNodeWithTag("chat.composer").performTextInput("Agree the next useful step")
            tap("chat.send")
            compose.waitUntil(30_000) { runBlocking { model.store.reader.threadMessages(workspace.id, discussion.id, 50).any { it.body == "Agree the next useful step" } } }
            capture("linked-chat")

            // New Doc and File creation uses the discussion's authoritative goal, preserving channel-owned records.
            tap("thread.artefacts.open"); tap("thread.artefacts.create")
            waitTag("artefacts.title")
            compose.onNodeWithTag("artefacts.title").performScrollTo().performTextInput("Discussion decision document")
            tap("artefacts.create"); gone("artefacts.title")
            compose.waitUntil(45_000) { runBlocking { api.channelDocs(workspace.id, workspace.appNpub, SampleIds.GENERAL).items.any { it.title == "Discussion decision document" } } }
            val createdDoc = runBlocking { api.channelDocs(workspace.id, workspace.appNpub, SampleIds.GENERAL).items.single { it.title == "Discussion decision document" } }
            assertEquals(goal.id, GoalAssociation.read(createdDoc.raw.obj("metadata")!!))
            assertEquals(discussion.id, createdDoc.raw.obj("metadata")!!.string("source_thread_id"))
            tap("thread.artefacts.create"); tap("artefacts.type.excalidraw")
            compose.onNodeWithTag("artefacts.title").performScrollTo().performTextInput("Discussion planning board")
            tap("artefacts.create"); gone("artefacts.title")
            compose.waitUntil(45_000) { runBlocking { api.channelFiles(workspace.id, workspace.appNpub, SampleIds.GENERAL).items.any { it.displayName == "Discussion planning board.excalidraw" } } }
            val createdBoard = runBlocking { api.channelFiles(workspace.id, workspace.appNpub, SampleIds.GENERAL).items.single { it.displayName == "Discussion planning board.excalidraw" } }
            assertEquals(goal.id, GoalAssociation.read(createdBoard.raw.obj("metadata")!!))
            assertEquals(discussion.id, createdBoard.raw.obj("metadata")!!.string("source_thread_id"))
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty() }
            tap("nav.back")
            waitTag("goal.detail")
            compose.waitUntil(30_000) { runBlocking { model.store.reader.associatedGoalLinks(workspace.id, goal.id, emptyList()).map { it.link.recordId }.containsAll(listOf(discussion.id, createdDoc.id, createdBoard.id)) } }

            fun pinOriginal(type: String, title: String) {
                tap("goal.pin"); waitTag("goal.pinPicker"); tap("goal.picker.$type")
                compose.onNodeWithTag("goal.picker.search").performTextReplacement(title)
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("goal.picker.item").fetchSemanticsNodes().size == 1 }
                tap("goal.picker.item"); gone("goal.pinPicker")
            }
            pinOriginal("doc", originalDoc.title)
            pinOriginal("file", originalBoard.displayName)
            val savedPins = runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal }
            assertEquals(setOf(originalDoc.id, originalBoard.id), savedPins.pinnedRefs.map { it.recordId }.toSet())
            assertTrue("pins do not become associated-work links", savedPins.links.isEmpty())
            reveal("goal.pinnedReference")
            compose.onAllNodesWithTag("goal.pinnedReference")[0].performScrollTo()
            capture("pins-and-associated-outputs")
            fun pinned(title: String) = compose.onNode(hasTestTag("goal.pinnedReference") and (hasText(title) or hasAnyDescendant(hasText(title))))
            pinned(originalDoc.title).performScrollTo().performClick()
            waitTag("doc.paper")
            assertEquals(originalDoc.id, runBlocking { api.doc(workspace.id, workspace.appNpub, originalDoc.id).id })
            tap("nav.back"); tap("section.home")
            waitTag("goal.detail"); reveal("goal.pinnedReference")
            pinned(originalBoard.displayName).performScrollTo().performClick()
            waitTag("board.title")
            tap("board.back")
            tap("section.home")
            waitTag("goal.detail"); reveal("goal.unpin")
            compose.onAllNodesWithTag("goal.unpin")[0].performScrollTo().performClick()
            compose.waitUntil(30_000) { runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal.pinnedRefs.size == 1 } }
            assertEquals(originalDoc.storageObjectId, runBlocking { api.doc(workspace.id, workspace.appNpub, originalDoc.id).storageObjectId })
            assertEquals(originalBoardVersion, runBlocking { api.boardVersions(workspace.id, workspace.appNpub, originalBoard.id).first })

            // Unset asks for an explicit channel; choosing one does not silently save it as a default.
            tap("goal.chooseChannel"); tap("goal.channelPicker.unset"); tap("goal.channelPicker.save"); gone("goal.channelPicker")
            reveal("goal.defaultChannel")
            compose.waitUntil(30_000) {
                runCatching { compose.onNodeWithTag("goal.defaultChannel").assertTextEquals("Ask each time") }.isSuccess
            }
            tap("goal.discuss"); waitTag("goal.channelPicker")
            compose.onNodeWithTag("goal.channelPicker.save").assertIsNotEnabled()
            tap("goal.channelPicker.${SampleIds.DESIGN}"); tap("goal.channelPicker.save")
            waitTag("chat.composer")
            compose.waitUntil(30_000) { goalChats(SampleIds.DESIGN).size == 1 }
            assertNull(runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal.defaultChannelId })
            tap("nav.back"); waitTag("goal.detail")

            // A disappeared default is disclosed. No thread is created before an explicit replacement choice.
            val unavailable = "99999999-9999-4999-8999-999999999999"
            runBlocking { controller.edit(goal.id, GoalEdit.SetDefaultChannel(null, unavailable)) }
            reveal("goal.defaultChannel")
            compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("goal.defaultChannel").assertTextEquals("Saved channel unavailable") }.isSuccess }
            val before = goalChats(SampleIds.GENERAL).size + goalChats(SampleIds.DESIGN).size
            tap("goal.discuss"); waitTag("goal.channelPicker.unavailable")
            compose.onNodeWithTag("goal.channelPicker.save").assertIsNotEnabled()
            assertEquals(before, goalChats(SampleIds.GENERAL).size + goalChats(SampleIds.DESIGN).size)
            assertEquals(unavailable, runBlocking { api.readGoal(workspace.id, workspace.appNpub, goal.id).goal.defaultChannelId })
            capture("unavailable-default-no-fallback")
            androidx.test.espresso.Espresso.pressBack()
        }
    }
}
