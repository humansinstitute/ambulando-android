package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*
import java.io.File

/** Isolated synthetic account. Optional private pilot snapshots are device-local clones, never live write targets. */
@RunWith(AndroidJUnit4::class)
class SystemFlowFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var window: android.view.Window
    private fun launch(body: (AppModel) -> Unit) {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            it.onActivity { activity -> window = activity.window }
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(30_000) { app.activeModel?.workspaces?.any { it.id == SampleIds.STUDIO } == true }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(30_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.selectContext(SampleIds.SCOPE_AMBULANDO, SampleIds.GENERAL); model.section = AppSection.DOCS }
            compose.waitUntil(30_000) {
                model.workspace?.id == SampleIds.STUDIO && model.context.channelId == SampleIds.GENERAL && runBlocking {
                    runCatching { model.towerAPI(model.connection!!).channelFiles(SampleIds.STUDIO, model.workspace!!.appNpub, SampleIds.GENERAL); true }.getOrDefault(false)
                }
            }
            captureOnFailure(compose, model, "system-flow") { body(model) }
        }
    }
    private fun present(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("artefacts.create", "flow.save", "flow.versions", "flow.discard", "flow.back", "flow.latest", "flow.version.1")) node.performScrollTo()
        node.performClick()
    }
    private fun screen(name: String): android.graphics.Bitmap {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        Thread.sleep(350) // Chromium/surface presentation follows semantic and DOM readiness.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
    private fun openName(name: String) {
        compose.onNodeWithTag("docs.filter").performTextReplacement(name)
        compose.onNodeWithTag("artefacts.filter.all").performClick()
        val row = hasTestTag("artefacts.row") and hasAnyDescendant(hasText(name, substring = true))
        compose.waitUntil(30_000) { compose.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row, useUnmergedTree = true).performClick()
    }
    @Test fun primaryCreationTypedSaveDraftBackHistoryConflictAndReadOnly() = launch { model ->
        val name = "Native flow ${System.currentTimeMillis()}"
        compose.onNodeWithTag("artefacts.new").performClick()
        tap("artefacts.type.system-flow")
        tap("artefacts.style.paper")
        compose.onNodeWithTag("artefacts.title").performScrollTo().performTextInput(name)
        compose.onNodeWithTag("artefacts.create").assertIsEnabled()
        screen("native-flow-creation")
        tap("artefacts.create")
        openName(name)
        compose.waitUntil(30_000) { present("flow.node.start.label") }
        compose.onNodeWithTag("flow.node.start.label").performScrollTo().performTextReplacement("Edited on")
        compose.onNodeWithTag("flow.node.start.label").performTextInput(" Android")
        compose.waitUntil(15_000) { compose.onNodeWithTag("flow.node.start.label").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "Edited on Android" }
        compose.onNodeWithTag("section.bar").assertDoesNotExist()
        compose.onNodeWithTag("flow.back").assertExists()
        screen("native-flow-typed-draft")
        androidx.test.espresso.Espresso.pressBack() // IME first if shown
        if (present("flow.title")) androidx.test.espresso.Espresso.pressBack()
        openName(name)
        compose.waitUntil(30_000) { present("flow.node.start.label") }
        compose.onNodeWithTag("flow.node.start.label").assertTextContains("Edited on Android")
        tap("flow.save")
        tap("flow.versions")
        compose.waitUntil(30_000) { compose.onNodeWithTag("flow.status").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text.contains("Saved version 2") } }
        tap("flow.version.1")
        compose.waitUntil(20_000) { compose.onNodeWithTag("flow.node.start.label").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        compose.waitUntil(20_000) { compose.onNodeWithTag("flow.node.start.label").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "Start" }
        compose.onNodeWithTag("flow.node.start.label").assertIsNotEnabled().assertTextContains("Start")
        tap("flow.versions"); tap("flow.latest")
        compose.waitUntil(20_000) { compose.onNodeWithTag("flow.node.start.label").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "Edited on Android" }
        val api = model.towerAPI(model.connection!!); val w = model.workspace!!
        val file = runBlocking { api.channelFiles(w.id, w.appNpub, SampleIds.GENERAL).items.single { it.displayName == "$name.flow.json" } }
        assertEquals("paper", file.raw.obj("metadata")!!.obj("artefact")!!.string("style_id"))
        compose.onNodeWithTag("flow.node.start.label").performScrollTo().performTextReplacement("Keep conflict draft")
        compose.waitUntil(15_000) { compose.onNodeWithTag("flow.node.start.label").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "Keep conflict draft" }
        val external = ArtefactTemplates.source("system-flow", "system", "paper", "External")
        val work = model.sampleTower!!.work
        work.externalFileVersion(file.id, work.seedObject(external.toByteArray(), ArtefactType.SYSTEM_FLOW.mime, "flow.json"))
        tap("flow.save")
        compose.waitUntil(30_000) { compose.onNodeWithTag("flow.status").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text.contains("newer version") } }
        screen("native-flow-conflict")
        androidx.test.espresso.Espresso.pressBack(); if (present("flow.title")) androidx.test.espresso.Espresso.pressBack()
        work.externalFilePolicy(file.id, canWrite = false)
        openName(name)
        compose.waitUntil(20_000) { present("flow.node.start.label") }
        compose.onNodeWithTag("flow.node.start.label").assertIsNotEnabled().assertTextContains("Keep conflict draft")
        compose.onNodeWithTag("flow.save").assertIsNotEnabled()
        screen("native-flow-read-only-draft")
    }

    @Test fun migratedHtmlCloneDecodesPrivateImagesInsideUnchangedSandbox() = launch { model ->
        val dir = File(context.filesDir, "artefact66")
        val bytes = File(dir, "brand-source.html").readBytes()
        val metadata = Json.parseToJsonElement(File(dir, "brand-live.json").readText()).jsonObject["metadata"]!!.jsonObject
        val work = model.sampleTower!!.work
        val name = "Brand clone ${System.currentTimeMillis()}.html"
        val id = work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, name, bytes, "text/html")
        work.externalFilePolicy(id, metadata = metadata, canWrite = false)
        work.externalFileVersion(id, work.seedObject(bytes, "text/html", "brand.html"))
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(30_000) { runBlocking { runCatching { model.towerAPI(model.connection!!).artefactFile(SampleIds.STUDIO, model.workspace!!.appNpub, id); true }.getOrDefault(false) } }
        openName(name)
        compose.waitUntil(30_000) { present("artefacts.menu") }
        compose.onNodeWithTag("artefacts.menu").performClick()
        compose.onNodeWithTag("artefacts.editSource").assertIsNotEnabled()
        compose.onNodeWithTag("artefacts.original").assertExists()
        var web: android.webkit.WebView? = null
        fun find(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        compose.waitUntil(30_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null }
        fun evaluate(script: String): String? {
            val latch = java.util.concurrent.CountDownLatch(1); var result: String? = null
            instrumentation.runOnMainSync { web!!.evaluateJavascript(script) { result = it; latch.countDown() } }
            check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); return result
        }
        compose.waitUntil(30_000) { evaluate("[...document.images].filter(i=>i.complete&&i.naturalWidth>0).length") == "33" }
        assertEquals("undefined", evaluate("typeof window.nostr")?.trim('"'))
        androidx.test.espresso.Espresso.pressBack()
        screen("pilot-brand-html-images")
        assertTrue(studio.otherstuff.ambulando.app.web.HtmlArtefactDocument.CSP.contains("form-action 'none'"))
    }

    @Test fun privatePilotClonesKeepAllDiagramsAndRenderOriginalPngPixels() = launch { model ->
        val dir = File(context.filesDir, "artefact66")
        val flowBytes = File(dir, "flow-source.json").readBytes()
        val boardBytes = File(dir, "board-source.json").readBytes()
        val originalFlow = SystemFlow.parse(flowBytes)
        val work = model.sampleTower!!.work
        fun seed(name: String, bytes: ByteArray, type: ArtefactType, meta: JsonObject): String {
            val id = work.seedFile(SampleIds.GENERAL, SampleIds.SCOPE_AMBULANDO, name, bytes, type.mime)
            work.externalFilePolicy(id, metadata = meta, canWrite = false)
            work.externalFileVersion(id, work.seedObject(bytes, type.mime, name))
            return id
        }
        val flowMeta = Json.parseToJsonElement(File(dir, "flow-live.json").readText()).jsonObject["metadata"]!!.jsonObject
        val suffix = System.currentTimeMillis()
        val flowName = "Pilot flow clone $suffix"
        val boardName = "Pilot board clone $suffix.excalidraw"
        val flowId = seed(flowName, flowBytes, ArtefactType.SYSTEM_FLOW, flowMeta)
        val boardMeta = Json.parseToJsonElement(File(dir, "board-live.json").readText()).jsonObject["metadata"]!!.jsonObject
        val boardId = seed(boardName, boardBytes, ArtefactType.WHITEBOARD, boardMeta)
        model.sampleTower!!.boards.seed(boardId, boardBytes, false, model.connection!!.signerNpub)
        compose.runOnIdle { model.refresh() }
        openName(flowName)
        compose.waitUntil(30_000) { present("flow.node.${SystemFlow.items(originalFlow, 0, "nodes").first().string("id")}.label") }
        assertEquals(5, SystemFlow.diagrams(originalFlow).size)
        compose.onNodeWithTag("artefacts.provenance").assertDoesNotExist()
        tap("flow.versions")
        compose.onNodeWithTag("artefacts.original").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("flow.save").assertIsNotEnabled()
        screen("pilot-flow-clone")
        androidx.test.espresso.Espresso.pressBack()
        openName(boardName)
        compose.waitUntil(30_000) { present("board.title") }
        var web: android.webkit.WebView? = null
        fun find(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        compose.waitUntil(30_000) { instrumentation.runOnMainSync { web = find(window.decorView) }; web != null }
        // The screenshot must contain the blue microphone images, not just a loaded asset count.
        compose.waitUntil(20_000) {
            val bitmap = screen("pilot-whiteboard-pngs")
            var blue = 0
            for (x in 0 until bitmap.width step 3) for (y in 200 until bitmap.height - 150 step 3) {
                val p = bitmap.getPixel(x, y)
                if (android.graphics.Color.blue(p) > 110 && android.graphics.Color.blue(p) > android.graphics.Color.red(p) * 1.4 && android.graphics.Color.blue(p) > android.graphics.Color.green(p) * 1.1) blue++
            }
            blue > 150
        }
        val api = model.towerAPI(model.connection!!); val w = model.workspace!!
        val version = runBlocking { api.fileVersions(w.id, w.appNpub, flowId).versions.last() }
        assertArrayEquals(flowBytes, runBlocking { api.fileVersionBytes(w.id, w.appNpub, flowId, version) })
        assertArrayEquals(boardBytes, model.sampleTower!!.boards.currentBytes(boardId))
    }
}
