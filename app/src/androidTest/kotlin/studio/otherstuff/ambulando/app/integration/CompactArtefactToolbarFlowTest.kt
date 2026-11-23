package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File

/** Device-only fixtures: source provenance must not consume the editor's normal reading area. */
@RunWith(AndroidJUnit4::class)
class CompactArtefactToolbarFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var window: android.view.Window
    private fun present(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun screen(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        Thread.sleep(if (!name.contains("overflow")) 3500 else 600) // Wait for actual surface presentation, beyond semantics/DOM readiness.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        if (name.endsWith("-excalidraw")) {
            var coloured = 0
            for (x in 0 until bitmap.width step 3) for (y in bitmap.height / 4 until bitmap.height * 3 / 4 step 3) {
                val pixel = bitmap.getPixel(x, y)
                val channels = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                if (channels.max() - channels.min() > 35) coloured++
            }
            assertTrue("The populated board must be visibly rendered, not just loaded", coloured > 100)
        }
        bitmap.recycle()
        if (name.endsWith("-html")) {
            val target = File(context.getExternalFilesDir(null), "$name-system.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p ${target.absolutePath}")).use { it.readBytes() }
        }
    }

    private fun awaitRenderedBoard() {
        fun find(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var ready = false
        compose.waitUntil(30_000) {
            instrumentation.runOnMainSync {
                find(window.decorView)?.evaluateJavascript("document.querySelector('#board-root')?.dataset.boardEditorState === 'ready' && document.querySelector('canvas')?.getBoundingClientRect().height > 100") { ready = it == "true" }
            }
            ready
        }
    }

    @Test fun importedSourcesAndHistoryLiveInOneOverflowAcrossFilesAndArtefacts() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            it.onActivity { activity -> window = activity.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            val work = model.sampleTower!!.work
            val metadata = buildJsonObject {
                put("migration", buildJsonObject {
                    put("source_version", "v2")
                    put("source_url", "https://example.invalid/original")
                    put("limitations", buildJsonArray { add("Older history remains in the original.") })
                })
            }
            data class Fixture(val type: ArtefactType, val title: String, val menu: String, val back: String, val ready: String)
            val suffix = System.currentTimeMillis()
            val fixtures = listOf(
                Fixture(ArtefactType.WHITEBOARD, "Compact board $suffix.excalidraw", "board.menu", "board.back", "board.title"),
                Fixture(ArtefactType.HTML, "Compact HTML $suffix.html", "artefacts.menu", "artefacts.back", "naplet.html"),
                Fixture(ArtefactType.SYSTEM_FLOW, "Compact flow $suffix.flow.json", "flow.versions", "flow.back", "flow.node.start.label"),
            )
            fixtures.forEach { fixture ->
                val bytes = if (fixture.type == ArtefactType.WHITEBOARD) requireNotNull(model.sampleTower!!.boards.currentBytes(SampleIds.BOARD_FILE))
                    else ArtefactTemplates.source(fixture.type.wire, if (fixture.type == ArtefactType.SYSTEM_FLOW) "system" else "explorer", "default", fixture.title).toByteArray()
                val id = work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, fixture.title, bytes, fixture.type.mime)
                work.externalFileVersion(id, work.seedObject(bytes, fixture.type.mime, fixture.title))
                work.externalFilePolicy(id, metadata = JsonObject(metadata + fixture.type.metadata()), canWrite = false)
                if (fixture.type == ArtefactType.WHITEBOARD) model.sampleTower!!.boards.seed(id, bytes, false, model.connection!!.signerNpub)
            }
            for (section in listOf(AppSection.DOCS, AppSection.FILES)) {
                compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = section; model.refresh() }
                for (fixture in fixtures) {
                    if (section == AppSection.DOCS) {
                        compose.waitUntil(30_000) { present("docs.filter") }
                        compose.onNodeWithTag("docs.filter").performTextReplacement(fixture.title)
                        compose.onNodeWithTag("artefacts.filter.all").performClick()
                        val row = hasTestTag("artefacts.row") and hasAnyDescendant(hasText(fixture.title))
                        compose.waitUntil(30_000) { compose.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNode(row, useUnmergedTree = true).performClick()
                    } else {
                        compose.waitUntil(30_000) { present("files.upload") }
                        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(fixture.title))
                        compose.onNodeWithText(fixture.title).performClick()
                    }
                    compose.waitUntil(30_000) { present(fixture.ready) }
                    if (fixture.type == ArtefactType.WHITEBOARD) awaitRenderedBoard()
                    compose.onNodeWithTag(fixture.back).assertIsDisplayed()
                    compose.onNodeWithTag(fixture.menu).assertIsDisplayed()
                    compose.onNodeWithTag("artefacts.provenance").assertDoesNotExist()
                    compose.onNodeWithTag("artefacts.original").assertDoesNotExist()
                    compose.onNodeWithTag("section.bar").assertDoesNotExist()
                    compose.onNodeWithTag("nav.back").assertDoesNotExist()
                    compose.onNodeWithTag("web.label").assertDoesNotExist()
                    if (fixture.type == ArtefactType.HTML) compose.onNodeWithTag("artefacts.readOnly").assertIsDisplayed()
                    if (fixture.type == ArtefactType.SYSTEM_FLOW) compose.onNodeWithTag("flow.save").assertIsNotEnabled()
                    screen("compact-${section.name.lowercase()}-${fixture.type.wire}")
                    compose.onNodeWithTag(fixture.menu).performClick()
                    compose.onNodeWithTag("artefacts.provenance").assertIsDisplayed()
                    compose.onNodeWithTag("artefacts.original").performScrollTo().assertIsDisplayed()
                    when (fixture.type) {
                        ArtefactType.HTML -> compose.onNodeWithTag("artefacts.editSource").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
                        ArtefactType.SYSTEM_FLOW -> compose.onNodeWithTag("flow.latest").performScrollTo().assertIsDisplayed()
                        ArtefactType.WHITEBOARD -> {
                            compose.waitUntil(20_000) { present("board.version") }
                            compose.onAllNodesWithTag("board.version").onFirst().performScrollTo().assertIsDisplayed()
                        }
                    }
                    screen("compact-${section.name.lowercase()}-${fixture.type.wire}-overflow")
                    androidx.test.espresso.Espresso.pressBack()
                    compose.onNodeWithTag(fixture.back).performClick()
                    compose.waitUntil(30_000) { present("section.bar") }
                }
            }
            assertTrue(runBlocking { model.towerAPI(model.connection!!).channelFiles(SampleIds.STUDIO, model.workspace!!.appNpub, SampleIds.GENERAL).items.count { it.displayName.contains(suffix.toString()) } } == 3)
        }
    }
}
