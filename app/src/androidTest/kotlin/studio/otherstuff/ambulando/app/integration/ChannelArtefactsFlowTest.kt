package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds

/** Dedicated synthetic emulator fixture; does not access a member account. */
@RunWith(AndroidJUnit4::class)
class ChannelArtefactsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun channelListFindsAndOpensSharedWhiteboardWithoutCanvas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var itActivity: android.view.Window
            scenario.onActivity { itActivity = it.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.section = AppSection.DOCS }
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.filter.excalidraw")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.filter.excalidraw").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.row")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("docs.filter").performTextInput("Architecture")
            compose.onNodeWithTag("artefacts.row").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.title")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.title").assertTextContains("Architecture", substring = true)
            assertBoardVisible(itActivity)
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("board.version")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.reload").assertIsDisplayed()
        }
    }
    @Test fun filesListOpensAVisiblyRenderedWhiteboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window
            scenario.onActivity { window = it.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.FILES }
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("Architecture sketch.excalidraw")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Architecture sketch.excalidraw").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.title")).fetchSemanticsNodes().isNotEmpty() }
            assertBoardVisible(window)
        }
    }

    @Test fun drawingOnAnEmptyBoardSavesReopensAndKeepsHistoryReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var window: android.view.Window
            scenario.onActivity { window = it.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            val boards = model.sampleTower!!.boards
            boards.externalSave(SampleIds.BOARD_FILE, """{"type":"excalidraw","version":2,"elements":[],"appState":{},"files":{}}""".toByteArray(), SampleIds.ANA)
            val before = boards.versionCount(SampleIds.BOARD_FILE)
            fun open() { compose.runOnIdle { model.editorBoard = studio.otherstuff.ambulando.core.surfaces.BoardReference(SampleIds.BOARD_FILE, SampleIds.BOARD_VERSION, before, "", 0, null, true) to "Drawing test" } }
            open()
            val web = awaitBoardPage(window)
            page(web, "document.querySelector('[data-testid=toolbar-rectangle]').click(); true")
            val position = IntArray(2)
            instrumentation.runOnMainSync { web.getLocationOnScreen(position) }
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float, y: Float, time: Long) {
                val event = android.view.MotionEvent.obtain(down, time, action, position[0] + x, position[1] + y, 0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, web.width * .2f, web.height * .35f, down)
            touch(android.view.MotionEvent.ACTION_MOVE, web.width * .6f, web.height * .65f, down + 80)
            touch(android.view.MotionEvent.ACTION_UP, web.width * .6f, web.height * .65f, down + 160)
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("board.saveNote") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.saveNote").performClick()
            compose.onNodeWithTag("board.note.field").performTextReplacement("Rectangle from touch")
            compose.onNodeWithTag("board.note.confirm").performClick()
            compose.waitUntil(20_000) { boards.versionCount(SampleIds.BOARD_FILE) == before + 1 }
            org.junit.Assert.assertEquals(1, org.json.JSONObject(String(boards.currentBytes(SampleIds.BOARD_FILE)!!)).getJSONArray("elements").length())
            compose.onNodeWithTag("board.back").performClick()
            compose.waitUntil(20_000) { model.editorBoard == null }
            open()
            assertBoardVisible(window)
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("board.version") and hasText("v$before", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTag("board.version") and hasText("v$before", substring = true)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("board.viewing")).fetchSemanticsNodes().isNotEmpty() }
            val history = awaitBoardPage(window)
            compose.waitUntil(10_000) { page(history, "document.querySelector('#board-root').dataset.boardReadonly") == "true" }
            org.junit.Assert.assertEquals("0", page(history, "document.querySelector('#board-root').dataset.boardElementCount"))
            compose.onNodeWithTag("board.latest").performClick()
            assertBoardVisible(window)
        }
    }

    @Test fun htmlCanBeCreatedEditedSavedAndBrowsedFromChannelArtefacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.DOCS }
            compose.waitUntil(20_000) { model.context.channelId == SampleIds.GENERAL }
            compose.onNodeWithTag("artefacts.new").performClick()
            val name = "HTML runtime " + System.currentTimeMillis()
            compose.onNodeWithTag("artefacts.title").performTextInput(name)
            compose.onNode(hasText("HTML") and hasAnyAncestor(isDialog())).performClick()
            compose.onNode(hasText("general", substring = true) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithTag("artefacts.create").assertIsEnabled().performClick()
            compose.onNodeWithTag("docs.filter").performTextReplacement("")
            compose.onNodeWithTag("artefacts.filter.html").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("$name.html")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTag("artefacts.row") and hasAnyDescendant(hasText("$name.html")), useUnmergedTree = true).performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.editSource")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.editSource").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("artefacts.source")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.source").performTextReplacement("<h1>Saved on Android</h1>")
            compose.onNodeWithTag("artefacts.saveSource").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.source")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("artefacts.history").performClick()
            compose.onNodeWithText("v2").assertIsDisplayed()
            compose.onNodeWithText("v1").performClick()
            compose.onNodeWithTag("artefacts.editSource").assertTextContains("Restore source")
        }
    }

    /** DOM readiness alone misses zero-height pages: verify geometry and actual screen pixels. */
    private fun awaitBoardPage(window: android.view.Window): android.webkit.WebView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun find(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        var web: android.webkit.WebView? = null
        compose.waitUntil(30_000) {
            instrumentation.runOnMainSync { web = find(window.decorView) }
            web != null
        }
        var ready = false
        compose.waitUntil(30_000) {
            instrumentation.runOnMainSync {
                web!!.evaluateJavascript("JSON.stringify({state:document.querySelector('#board-root')?.dataset.boardEditorState,height:document.querySelector('canvas')?.getBoundingClientRect().height})") { result ->
                    val encoded = result?.let { org.json.JSONTokener(it).nextValue() as? String }
                    val probe = encoded?.let { org.json.JSONObject(it) }
                    ready = probe?.optString("state") == "ready" && probe.optDouble("height") > 100
                }
            }
            ready
        }
        return web!!
    }

    private fun page(web: android.webkit.WebView, script: String): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var value: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { raw ->
                value = raw?.let { org.json.JSONTokener(it).nextValue()?.toString() }
                latch.countDown()
            }
        }
        check(latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Editor page did not answer" }
        return value
    }

    private fun assertBoardVisible(window: android.view.Window) {
        val web = awaitBoardPage(window)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Chromium presents independently of Compose. Exclude the toolbar and native header.
        compose.waitUntil(10_000) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val location = IntArray(2)
            instrumentation.runOnMainSync { web.getLocationOnScreen(location) }
            var drawn = 0
            for (x in location[0].coerceAtLeast(0) until (location[0] + web.width).coerceAtMost(screenshot.width) step 4) {
                for (y in (location[1] + 160).coerceAtLeast(0) until (location[1] + web.height - 160).coerceAtMost(screenshot.height) step 4) {
                    val pixel = screenshot.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) < 180 && android.graphics.Color.green(pixel) < 180 && android.graphics.Color.blue(pixel) < 180) drawn++
                }
            }
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "board-route-screen.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            drawn > 100
        }
    }
}
