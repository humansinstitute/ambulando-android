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
import studio.otherstuff.ambulando.app.ui.nav.ThreadFocus
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*

/** Task-owned emulator only. Full native flow over the labelled synthetic Tower. */
@RunWith(AndroidJUnit4::class)
class ThreadArtefactsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun browserBoardSwitchingPinnedHistoryAndComposerRetainState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            val originalTarget = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL, SampleIds.thread(39))
            runBlocking { model.store.writer.saveDraft(originalTarget, Draft("", emptyList())); model.store.writer.setState("thread.artefacts.${originalTarget.draftKey}", null) }
            model.sampleTower!!.seedLongConversation(SampleIds.thread(39), 75)
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = studio.otherstuff.ambulando.app.ui.shell.AppSection.CHAT }
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat.inbox.row.${SampleIds.thread(39)}")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.inbox.row.${SampleIds.thread(39)}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("thread.artefacts.add")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.messages")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.messages").performTouchInput { swipeDown() }
            compose.waitForIdle()
            fun scrollPosition() = compose.onNodeWithTag("chat.messages").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            val readingPosition = scrollPosition()
            compose.onNodeWithTag("thread.artefacts.add").assertIsDisplayed().performClick()
            compose.onNodeWithTag("thread.artefacts.search").performTextInput("Architecture")
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("thread.artefacts.row.${SampleIds.BOARD_FILE}")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.row.${SampleIds.BOARD_FILE}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.title")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.title").assertTextContains("Architecture", substring = true)
            // Real native pointer drag in the embedded editor: the bottom switch must not intercept panning.
            fun web(view: android.view.View): android.webkit.WebView? {
                if (view is android.webkit.WebView && view.url?.contains("index.html") == true) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
                return null
            }
            fun js(script: String): String? {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result: String? = null
                scenario.onActivity { activity -> requireNotNull(web(activity.window.decorView)).evaluateJavascript(script) { result = it; latch.countDown() } }
                assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); return result
            }
            compose.waitUntil(20_000) { js("document.querySelector('[data-testid=toolbar-hand]') != null") == "true" }
            js("document.querySelector('[data-testid=toolbar-hand]').click()")
            val before = model.threadArtefacts.values.first().tabs.single().viewport
            compose.onNodeWithTag("board.canvas").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(100f, 10f), 600) }
            compose.waitUntil(10_000) { model.threadArtefacts.values.first().tabs.single().viewport != before }
            compose.onNodeWithTag("board.title").assertIsDisplayed()
            js("window.__pinchEvents=[]; for(const type of ['pointerdown','pointerup','pointercancel','touchstart','touchmove']) document.addEventListener(type,e=>window.__pinchEvents.push({type,pointer:e.pointerType,count:e.touches?.length}),true)")
            val zoomBefore = org.json.JSONObject(model.threadArtefacts.values.first().tabs.single().viewport!!).getDouble("zoom")
            // Deliver real timed multi-touch samples. Chromium coalesces a batched test pinch into one move,
            // which establishes Excalidraw's initial distance without ever delivering a second distance.
            val bounds = compose.onNodeWithTag("board.canvas").fetchSemanticsNode().boundsInWindow
            val downAt = android.os.SystemClock.uptimeMillis()
            val properties = Array(2) { i -> android.view.MotionEvent.PointerProperties().apply { id = i; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
            fun touch(action: Int, count: Int, distance: Float) {
                val coords = Array(count) { i -> android.view.MotionEvent.PointerCoords().apply {
                    x = bounds.center.x + if (i == 0) -distance else distance; y = bounds.center.y; pressure = 1f; size = 1f
                } }
                val event = android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action, count, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, 1, 50f)
            touch(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, 50f)
            repeat(20) { step -> Thread.sleep(30); touch(android.view.MotionEvent.ACTION_MOVE, 2, 50f + (step + 1) * 4f) }
            touch(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, 130f)
            touch(android.view.MotionEvent.ACTION_UP, 1, 130f)
            println("PINCH_DIAGNOSTIC " + js("JSON.stringify({events:window.__pinchEvents,zoom:document.getElementById('board-root').dataset.boardZoom,scale:visualViewport.scale})"))
            compose.waitUntil(10_000) { org.json.JSONObject(model.threadArtefacts.values.first().tabs.single().viewport!!).getDouble("zoom") != zoomBefore }
            js("document.querySelector('[data-testid=toolbar-rectangle]').click()")
            compose.onNodeWithTag("board.canvas").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(120f, 100f), 600) }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("board.status")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.artefacts.discuss").performClick()
            compose.onNodeWithTag("thread.artefacts.target").assertIsDisplayed()
            compose.waitUntil(10_000) { kotlin.math.abs(scrollPosition() - readingPosition) < .001f }
            compose.onNodeWithTag("chat.composer").performTextInput("Keep this draft")
            val initialVersions = model.sampleTower!!.boards.versionCount(SampleIds.BOARD_FILE)
            model.sampleTower!!.offline = true
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("thread.artefacts.failure")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat.composer").assertTextContains("Keep this draft")
            assertEquals(initialVersions, model.sampleTower!!.boards.versionCount(SampleIds.BOARD_FILE))
            model.sampleTower!!.offline = false
            model.sampleTower!!.boards.externalSaveBeforeNextPublish = true
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(20_000) { model.sampleTower!!.boards.versionCount(SampleIds.BOARD_FILE) > initialVersions }
            compose.onNodeWithTag("chat.composer").assertTextContains("Keep this draft")
            compose.onNodeWithTag("thread.artefacts").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("board.merge")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.merge").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("board.stale")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("thread.chat").performClick()
            compose.onNodeWithTag("chat.send").performClick()
            compose.waitUntil(20_000) { model.sampleTower!!.boards.versionCount(SampleIds.BOARD_FILE) > initialVersions }
            compose.waitUntil(20_000) { runBlocking { model.store.reader.threadMessages(SampleIds.STUDIO, SampleIds.thread(39), 300).any { it.body == "Keep this draft" && it.raw["metadata"]?.toString()?.contains("ambulando_artefact_context") == true } } }
            compose.onNodeWithTag("chat.composer").performTextInput("Retained after Send")
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("thread.artefacts").performClick()
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("board.version")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("board.version")[0].performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("board.viewing")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread.chat").performClick()
            compose.onNodeWithTag("chat.composer").assertTextContains("Retained after Send")
            compose.onNodeWithTag("thread.artefacts").performClick()
            compose.onNodeWithTag("board.viewing").assertIsDisplayed()
            fun capture(name: String) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.getExternalFilesDir(null), "artefacts87-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            capture("pinned-board")
            compose.onNodeWithTag("thread.artefacts.title").performClick()
            compose.onNodeWithText("Close").performClick()
            compose.waitUntil(10_000) { model.threadArtefacts[originalTarget.draftKey]!!.tabs.none { it.open } }
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("thread.artefacts.add").performClick()
            compose.onNodeWithTag("thread.artefacts.search").performTextInput("Architecture")
            compose.onNodeWithTag("thread.artefacts.row.${SampleIds.BOARD_FILE}").performClick()
            compose.onNodeWithTag("board.viewing").assertIsDisplayed()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("board.viewing").assertIsDisplayed()
            compose.onNodeWithTag("thread.chat").performClick()
            compose.onNodeWithTag("chat.composer").assertTextContains("Retained after Send")
            val target = ChatTarget(model.connection!!.id, SampleIds.STUDIO, SampleIds.GENERAL, SampleIds.thread(39))
            compose.waitUntil(10_000) { runBlocking { model.store.reader.draft(target)?.text == "Retained after Send" } }
            assertEquals(1, model.threadArtefacts[target.draftKey]!!.tabs.size)
            capture("chat-target")
        }
    }
}
