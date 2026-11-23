package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
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
            assertFocusedEditor()
            assertBoardVisible(itActivity)
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("board.version")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("board.reload").assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack() // dismiss options first
            compose.onNodeWithTag("board.back").performClick()
            compose.onNodeWithTag("docs.filter").assertTextEquals("Architecture")
            compose.onNodeWithTag("section.bar").assertIsDisplayed()
            compose.onNodeWithTag("artefacts.row").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.title")).fetchSemanticsNodes().isNotEmpty() }
            assertFocusedEditor()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("docs.filter").assertTextEquals("Architecture")
            compose.onNodeWithTag("section.bar").assertIsDisplayed()
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
            assertFocusedEditor()
            assertBoardVisible(window)
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("Architecture sketch.excalidraw").assertIsDisplayed()
            compose.onNodeWithTag("section.bar").assertIsDisplayed()
        }
    }

    private fun assertFocusedEditor() {
        compose.onNodeWithTag("board.back").assertIsDisplayed()
        compose.onNodeWithTag("board.menu").assertIsDisplayed()
        compose.onNodeWithTag("nav.back").assertDoesNotExist()
        compose.onNodeWithTag("search").assertDoesNotExist()
        compose.onNodeWithTag("section.bar").assertDoesNotExist()
        compose.onNodeWithTag("tablet.sidebar").assertDoesNotExist()
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
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.DOCS }
            compose.onNodeWithTag("docs.filter").performTextReplacement("")
            compose.onNodeWithTag("artefacts.filter.all").performClick()
            val name = "Primary whiteboard " + System.currentTimeMillis()
            compose.onNodeWithTag("artefacts.new").assertContentDescriptionEquals("New artefact").performClick()
            compose.onNodeWithTag("docs.new").assertDoesNotExist()
            compose.onNodeWithTag("artefacts.type.document").assertIsDisplayed()
            compose.onNodeWithTag("artefacts.type.excalidraw").assertIsDisplayed().performClick()
            compose.onNodeWithTag("artefacts.type.html").assertIsDisplayed()
            compose.onNodeWithTag("artefacts.title").performTextInput(name)
            compose.onNodeWithTag("artefacts.create").performClick()
            compose.onNodeWithTag("docs.filter").performTextReplacement(name)
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.row") and hasAnyDescendant(hasText("$name.excalidraw")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val api = model.towerAPI(model.connection!!)
            val file = runBlocking { api.channelFiles(SampleIds.STUDIO, model.workspace!!.appNpub, SampleIds.GENERAL).items.single { it.displayName == "$name.excalidraw" } }
            assertEquals(ArtefactType.WHITEBOARD, ArtefactType.of(file))
            assertEquals(SampleIds.GENERAL, file.channelId)
            fun versions() = runBlocking { api.fileVersions(SampleIds.STUDIO, model.workspace!!.appNpub, file.id) }
            val before = versions().versions.size
            assertEquals(1, before)
            fun open() {
                compose.onNode(hasTestTag("artefacts.row") and hasAnyDescendant(hasText("$name.excalidraw")), useUnmergedTree = true).performClick()
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("board.title")).fetchSemanticsNodes().isNotEmpty() }
            }
            open()
            assertFocusedEditor()
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
            compose.waitUntil(20_000) { versions().versions.size == before + 1 }
            val saved = versions().let { list -> list.versions.single { it.id == list.currentVersionId } }
            val bytes = runBlocking { api.fileVersionBytes(SampleIds.STUDIO, model.workspace!!.appNpub, file.id, saved) }
            assertEquals(1, org.json.JSONObject(String(bytes)).getJSONArray("elements").length())
            captureSettledBoard(window, "board-drawn-saved")
            compose.onNodeWithTag("board.back").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("artefacts.new")).fetchSemanticsNodes().isNotEmpty() }
            open()
            assertBoardVisible(window)
            captureSettledBoard(window, "board-drawn-reopened")
            compose.onNodeWithTag("board.menu").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("board.version") and hasText("v$before", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTag("board.version") and hasText("v$before", substring = true)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("board.viewing")).fetchSemanticsNodes().isNotEmpty() }
            val history = awaitBoardPage(window)
            compose.waitUntil(10_000) { page(history, "document.querySelector('#board-root').dataset.boardReadonly") == "true" }
            org.junit.Assert.assertEquals("0", page(history, "document.querySelector('#board-root').dataset.boardElementCount"))
            compose.onNodeWithTag("board.latest").performClick()
            compose.waitUntil(20_000) { page(history, "document.querySelector('#board-root').dataset.boardElementCount") == "1" && page(history, "document.querySelector('#board-root').dataset.boardReadonly") == "false" }
            assertBoardVisible(window)
            captureSettledBoard(window, "board-drawn-latest")
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
            compose.onNodeWithTag("artefacts.type.html").performClick()
            compose.onNode(hasText("general", substring = true) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithTag("artefacts.create").assertIsEnabled().performClick()
            compose.onNodeWithTag("docs.filter").performTextReplacement("")
            compose.onNodeWithTag("artefacts.filter.html").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.row") and hasAnyDescendant(hasText("$name.html")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTag("artefacts.row") and hasAnyDescendant(hasText("$name.html")), useUnmergedTree = true).performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.menu")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.menu").performClick()
            compose.onNodeWithTag("artefacts.editSource").performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("artefacts.source")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("artefacts.source").performTextReplacement("<h1>Saved on Android</h1>")
            compose.onNodeWithTag("artefacts.saveSource").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.source")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("artefacts.menu").performClick()
            compose.onNodeWithText("v2").assertIsDisplayed()
            compose.onNodeWithText("v1").performClick()
            compose.onNodeWithTag("artefacts.readOnly").assertIsDisplayed()
            compose.onNodeWithTag("artefacts.menu").performClick()
            compose.onNodeWithTag("artefacts.editSource").assertTextContains("Restore source")
        }
    }

    @Test fun primaryDocumentCancellationAndOfflineWhiteboardRetryKeepOneRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.DOCS }
            compose.onNodeWithTag("docs.filter").performTextReplacement("")
            compose.onNodeWithTag("artefacts.filter.all").performClick()
            val suffix = System.currentTimeMillis().toString()
            val docName = "Primary document $suffix"
            compose.onNodeWithTag("artefacts.new").performClick()
            compose.onNodeWithTag("artefacts.type.document").performClick()
            compose.onNodeWithTag("artefacts.create").assertIsNotEnabled()
            compose.onNodeWithTag("artefacts.title").performTextInput("   ")
            compose.onNodeWithTag("artefacts.create").assertIsNotEnabled()
            compose.onNodeWithTag("artefacts.title").performTextReplacement(docName)
            // Current channel bounds the destination, rather than offering another channel in the same scope.
            compose.onNodeWithTag("artefacts.destination.${SampleIds.GENERAL}").assertIsDisplayed()
            assertEquals(1, compose.onAllNodes(hasTestTag("artefacts.destination.${SampleIds.GENERAL}")).fetchSemanticsNodes().size)
            compose.onNodeWithTag("artefacts.cancel").performClick()
            assertTrue(runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, SampleIds.STUDIO).none { it.string("title") == docName } })
            compose.onNodeWithTag("artefacts.new").performClick()
            compose.onNodeWithTag("artefacts.title").assertTextContains(docName)
            // Invoke the same displayed button callback twice before recomposition: only one write may be queued.
            compose.onNodeWithTag("artefacts.create").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { click -> click(); click() }
            compose.onNodeWithTag("docs.filter").performTextReplacement(docName)
            compose.waitUntil(30_000) { runBlocking { model.towerAPI(model.connection!!).channelDocs(SampleIds.STUDIO, model.workspace!!.appNpub, SampleIds.GENERAL).items.any { it.title == docName } } }
            val api = model.towerAPI(model.connection!!)
            val doc = runBlocking { api.channelDocs(SampleIds.STUDIO, model.workspace!!.appNpub, SampleIds.GENERAL).items.single { it.title == docName } }
            assertEquals(SampleIds.GENERAL, doc.channelId)
            assertEquals(SampleIds.SCOPE_AMBULANDO, doc.scopeId)
            assertTrue(runBlocking { api.channelFiles(SampleIds.STUDIO, model.workspace!!.appNpub, SampleIds.GENERAL).items.none { it.displayName.startsWith(docName) } })
            compose.onNodeWithTag("docs.filter").performTextReplacement("")
            val boardName = "Retry whiteboard $suffix"
            compose.onNodeWithTag("artefacts.new").performClick()
            compose.onNodeWithTag("artefacts.type.excalidraw").performClick()
            compose.onNodeWithTag("artefacts.title").performTextReplacement(boardName)
            compose.runOnIdle { model.sampleTower!!.offline = true }
            compose.onNodeWithTag("artefacts.create").performClick()
            compose.onNodeWithTag("docs.filter").performTextReplacement(boardName)
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("write.retry")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("write.status").assertIsDisplayed()
            assertTrue(runBlocking { model.store.reader.files(SampleIds.STUDIO).none { it.displayName == "$boardName.excalidraw" } })
            val pending = runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, SampleIds.STUDIO).single { it.string("display_name") == "$boardName.excalidraw" } }
            assertEquals(WriteState.RETRYING, pending.state)
            compose.runOnIdle { model.sampleTower!!.offline = false }
            compose.onNodeWithTag("write.retry").performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("artefacts.row") and hasAnyDescendant(hasText("$boardName.excalidraw")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasTestTag("write.retry")).fetchSemanticsNodes().isEmpty() }
            assertEquals(1, runBlocking { api.channelFiles(SampleIds.STUDIO, model.workspace!!.appNpub, SampleIds.GENERAL).items.count { it.displayName == "$boardName.excalidraw" } })
            assertTrue(runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, SampleIds.STUDIO).none { it.id == pending.id } })
        }
    }

    private fun captureSettledBoard(window: android.view.Window, name: String) {
        awaitBoardPage(window)
        compose.waitForIdle()
        Thread.sleep(3500) // Capture the presented scene and native toolbar, not a transitional Chromium frame.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
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
                web!!.evaluateJavascript("JSON.stringify({state:document.querySelector('#board-root')?.dataset.boardEditorState,key:document.querySelector('#board-root')?.dataset.boardKey,height:document.querySelector('canvas')?.getBoundingClientRect().height})") { result ->
                    val encoded = result?.let { org.json.JSONTokener(it).nextValue() as? String }
                    val probe = encoded?.let { org.json.JSONObject(it) }
                    ready = probe?.optString("state") == "ready" && !probe.optString("key").isNullOrBlank() && probe.optDouble("height") > 100
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
