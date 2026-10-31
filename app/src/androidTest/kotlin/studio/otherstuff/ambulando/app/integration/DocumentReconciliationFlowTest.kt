package studio.otherstuff.ambulando.app.integration

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.ComposeTimeoutException
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
import studio.otherstuff.ambulando.core.docs.DocDraft
import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.docs.WikiLinks
import studio.otherstuff.ambulando.core.sample.SampleIds
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.refreshDoc
import studio.otherstuff.ambulando.core.sync.refreshDocs

/**
 * Document draft recovery, review/merge, takeover and wiki linking on a dedicated emulator against the in-process synthetic
 * Tower. Most review tests seed the stored draft the way the editor stores it and drive the review, takeover and picker screens
 * by touch; [typingIntoTheEditorPageBecomesADurableDraftAndOnlySaveCanPublishIt] instead taps into the real editor page and
 * types with key events. Synthetic data only; never evidence of a live Tower.
 */
@RunWith(AndroidJUnit4::class)
class DocumentReconciliationFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(block: (AppModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra(AppEnvironment.SAMPLE_EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { launched ->
            scenario = launched
            val app = context.applicationContext as AmbulandoApplication
            compose.waitUntil(20_000) { app.activeModel?.workspaces?.size == 2 }
            val model = app.activeModel!!
            compose.runOnIdle { model.selectWorkspace(model.workspaces.first { it.id == SampleIds.STUDIO }) }
            compose.waitUntil(20_000) { model.channels.any { it.id == SampleIds.GENERAL } }
            compose.runOnIdle { model.act { model.sync.refreshDocs(model.connection!!, model.workspace!!) } }
            try { block(model) } finally {
                compose.runOnIdle { model.editorDoc = null; model.documentOpensInEdit = false; model.autoEditedDoc = null }
                runBlocking { model.store.writer.setState("settings.documentOpening", "view") }
            }
        }
    }

    private fun cachedDoc(model: AppModel, title: String): String {
        compose.waitUntil(20_000) { runBlocking { model.store.reader.docs(SampleIds.STUDIO).any { it.title == title } } }
        val doc = runBlocking { model.store.reader.docs(SampleIds.STUDIO).first { it.title == title } }
        compose.runOnIdle { model.act { model.sync.refreshDoc(model.connection!!, model.workspace!!, doc.id) } }
        compose.waitUntil(20_000) { runBlocking { model.store.reader.docBody(SampleIds.STUDIO, doc.id) != null } }
        return doc.id
    }

    /** The sample state outlives a test: put a document back to known text with no draft, review work, lease or recovery. */
    private fun reset(model: AppModel, doc: String, body: String, contentType: String) {
        val key = key(model, doc)
        runBlocking {
            model.store.writer.deleteDocDraft(key, SampleIds.STUDIO)
            model.store.writer.setState(studio.otherstuff.ambulando.core.store.DocumentReviewCodec.key(key), null)
        }
        model.sampleTower!!.work.resetEditing(doc)
        model.sampleTower!!.work.externalDocSave(doc, body.toByteArray(), contentType)
        compose.runOnIdle { model.act { model.sync.refreshDoc(model.connection!!, model.workspace!!, doc) } }
        compose.waitUntil(20_000) { runBlocking { String(model.store.reader.docBody(SampleIds.STUDIO, doc)?.bytes ?: ByteArray(0)) } == body }
    }

    private val themeNotes = "# Theme notes\n\nThe primary colour is **deep purple**.\n\n- Light\n- Dark"

    /** Waits for a tagged node, and on timeout fails with what the screen showed. */
    private fun awaitTag(tag: String, timeoutMillis: Long = 20_000) {
        try { compose.waitUntil(timeoutMillis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: ComposeTimeoutException) {
            val tree = runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { it.config.toString() } }.getOrDefault("?")
            throw AssertionError("No node tagged '$tag'. Screen: " + tree.take(2500), e)
        }
    }

    private fun key(model: AppModel, doc: String) = DocDraft.key(model.connection!!.id, SampleIds.STUDIO, doc)

    @Test fun aNewerSharedVersionIsReviewedAndMergedIntoALocalDraftThatOnlySaveCanPublish() = launch { model ->
        val doc = cachedDoc(model, "Theme notes (Markdown)")
        reset(model, doc, themeNotes, "text/markdown")
        val recoveriesBefore = model.sampleTower!!.work.recoveryCount
        val cached = runBlocking { model.store.reader.docBody(SampleIds.STUDIO, doc)!! }
        val original = String(cached.bytes)
        // This device holds an unsaved edit; someone else then saves a different part of the page.
        val mine = original.replace("- Light", "- Light mode")
        runBlocking {
            model.store.writer.saveDocDraft(DocDraft(model.connection!!.id, SampleIds.STUDIO, doc, "Theme notes (Markdown)", mine.toByteArray(), cached.contentType,
                cached.docRowVersion.toInt(), cached.sha256, cached.storageObjectId, DocDraft.State.EDITING))
        }
        // The editor retained this base when the page was first opened for editing; a draft restored later merges against it.
        runBlocking {
            val journal = studio.otherstuff.ambulando.core.docs.DocumentReviewJournal()
            journal.retainBase(studio.otherstuff.ambulando.core.docs.PreservedDocumentSnapshot.of(cached, "Theme notes (Markdown)"))
            model.store.writer.saveDocumentReview(journal, key(model, doc))
        }
        val theirs = original.replace("deep purple", "indigo")
        model.sampleTower!!.work.externalDocSave(doc, theirs.toByteArray(), "text/markdown")

        compose.runOnIdle { model.editorDoc = doc }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("doc.editor.review").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Newer version available").assertIsDisplayed()
        assertEquals("the page still shows this device's work; nothing was replaced", mine, String(runBlocking { model.store.reader.docDraft(key(model, doc))!!.body }))
        compose.onNodeWithTag("doc.editor.review").performClick()
        compose.onNodeWithTag("doc.review.merge").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("doc.review.apply").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("reviewing and merging publish nothing", theirs, String(model.sampleTower!!.work.docBody(doc)!!))
        compose.onNodeWithTag("doc.review.apply").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("doc.review").fetchSemanticsNodes().isEmpty() }
        val merged = runBlocking { model.store.reader.docDraft(key(model, doc))!! }
        assertTrue(String(merged.body).contains("Light mode") && String(merged.body).contains("indigo"))
        assertEquals("the merged draft is a local draft based on the reviewed head", theirs, String(model.sampleTower!!.work.docBody(doc)!!))
        // Only the explicit Save publishes it.
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("doc.editor.save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("doc.editor.save").performClick()
        compose.waitUntil(30_000) { String(model.sampleTower!!.work.docBody(doc)!!).contains("Light mode") }
        assertTrue(String(model.sampleTower!!.work.docBody(doc)!!).contains("indigo"))
        compose.waitUntil(20_000) { runBlocking { model.store.reader.docDraft(key(model, doc)) } == null }
        compose.onNodeWithTag("doc.editor.back").performClick()
        compose.waitUntil(20_000) { model.editorDoc == null }
    }

    @Test fun acceptingTheLatestVersionAsksFirstAndKeepsTheReplacedCopyOnTheDevice() = launch { model ->
        val doc = cachedDoc(model, "Theme notes (Markdown)")
        reset(model, doc, themeNotes, "text/markdown")
        val recoveriesBefore = model.sampleTower!!.work.recoveryCount
        val cached = runBlocking { model.store.reader.docBody(SampleIds.STUDIO, doc)!! }
        val mine = String(cached.bytes).replace("- Dark", "- Dark mine")
        runBlocking {
            model.store.writer.saveDocDraft(DocDraft(model.connection!!.id, SampleIds.STUDIO, doc, "Theme notes (Markdown)", mine.toByteArray(), cached.contentType,
                cached.docRowVersion.toInt(), cached.sha256, cached.storageObjectId, DocDraft.State.EDITING))
        }
        model.sampleTower!!.work.externalDocSave(doc, "# Theme notes\n\nRewritten by someone else.\n".toByteArray(), "text/markdown")
        compose.runOnIdle { model.editorDoc = doc }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("doc.editor.review").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("doc.editor.review").performClick()
        compose.onNodeWithTag("doc.review.accept").performScrollTo().performClick()
        compose.onNodeWithText("Accept the latest version?").assertIsDisplayed()
        assertNotNull("nothing is replaced until the person confirms", runBlocking { model.store.reader.docDraft(key(model, doc)) })
        compose.onNodeWithTag("doc.review.accept.confirm").performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.docDraft(key(model, doc)) } == null }
        assertEquals("accepting creates no version", "# Theme notes\n\nRewritten by someone else.\n", String(model.sampleTower!!.work.docBody(doc)!!))
        val journal = runBlocking { model.store.loadDocumentReview(key(model, doc)) }
        assertTrue("the replaced copy is preserved", journal.retained.any { String(it.bytes).contains("Dark mine") })
        assertEquals(recoveriesBefore, model.sampleTower!!.work.recoveryCount)
    }

    @Test fun anotherEditorsLeaseCanBeTakenOverOnlyAfterConfirmationAndLocalWorkIsPreservedFirst() = launch { model ->
        val doc = cachedDoc(model, "Theme notes (Markdown)")
        reset(model, doc, themeNotes, "text/markdown")
        val recoveriesBefore = model.sampleTower!!.work.recoveryCount
        val cached = runBlocking { model.store.reader.docBody(SampleIds.STUDIO, doc)!! }
        val mine = String(cached.bytes).replace("- Dark", "- Dark takeover")
        runBlocking {
            model.store.writer.saveDocDraft(DocDraft(model.connection!!.id, SampleIds.STUDIO, doc, "Theme notes (Markdown)", mine.toByteArray(), cached.contentType,
                cached.docRowVersion.toInt(), cached.sha256, cached.storageObjectId, DocDraft.State.EDITING))
        }
        model.sampleTower!!.work.holdLease(doc, SampleIds.ORBIT)
        compose.runOnIdle { model.editorDoc = doc }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("doc.editor.takeover").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("doc.editor.authority").assertIsDisplayed()
        // While another editor holds it, Save does not force anything.
        assertEquals(recoveriesBefore, model.sampleTower!!.work.recoveryCount)
        compose.onNodeWithTag("doc.editor.takeover").performClick()
        compose.onNodeWithText("Take over editing?").assertIsDisplayed()
        assertEquals("nothing happens before the person confirms", recoveriesBefore, model.sampleTower!!.work.recoveryCount)
        compose.onNodeWithTag("doc.takeover.confirm").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("doc.editor.takeover").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithTag("doc.editor.authority").fetchSemanticsNodes().isEmpty() }
        assertEquals("the divergent local work became one Tower recovery first", recoveriesBefore + 1, model.sampleTower!!.work.recoveryCount)
        assertEquals("and the shared document was not overwritten", String(cached.bytes), String(model.sampleTower!!.work.docBody(doc)!!))
        compose.onNodeWithTag("doc.editor.back").performClick()
        compose.waitUntil(20_000) { model.editorDoc == null }
    }

    @Test fun theLinkPickerLinksAnExistingPageAndCreatesAndLinksANewOne() = launch { model ->
        val plan = cachedDoc(model, "Native app plan (sample)")
        runBlocking { model.store.writer.deleteDocDraft(key(model, plan), SampleIds.STUDIO) }
        model.sampleTower!!.work.resetEditing(plan)
        val theme = runBlocking { model.store.reader.docs(SampleIds.STUDIO).first { it.title == "Theme notes (Markdown)" } }
        compose.runOnIdle { model.editorDoc = plan }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("doc.editor.link") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("doc.editor.link").performScrollTo().performClick()
        awaitTag("wiki.picker.search")
        compose.onNodeWithTag("wiki.picker.search").performTextInput("Theme")
        compose.onNodeWithTag("wiki.picker.everywhere").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("wiki.picker.result").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("wiki.picker.result")[0].performClick()
        compose.waitUntil(20_000) { runBlocking { model.store.reader.docDraft(key(model, plan)) } != null }
        val linked = runBlocking { model.store.reader.docDraft(key(model, plan))!! }
        assertTrue("the stable link to the chosen page is in the stored draft", WikiLinks.links(DocumentBody(linked.body, linked.contentType), theme.id))
        // Create and link: one durable write; the page appears and the draft gets its id.
        compose.onNodeWithTag("doc.editor.link").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("wiki.picker.newtitle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wiki.picker.newtitle").performTextReplacement("Brand new wiki page")
        compose.onNodeWithTag("wiki.picker.create").performScrollTo().performClick()
        try { compose.waitUntil(40_000) { runBlocking { model.store.reader.docs(SampleIds.STUDIO).any { it.title == "Brand new wiki page" } } } }
        catch (e: ComposeTimeoutException) {
            val writes = runBlocking { model.store.reader.workspaceWrites(model.connection!!.id, SampleIds.STUDIO) }.joinToString("; ") { "${it.kind.wire} ${it.state} ${it.lastCode} ${it.lastError}" }
            val banner = compose.onAllNodesWithTag("wiki.banner").fetchSemanticsNodes().joinToString { it.config.toString() }
            throw AssertionError("Create-and-link did not finish. Banner: $banner. Writes: $writes. Draft: " + runBlocking { model.store.reader.docDraft(key(model, plan))?.let { String(it.body).take(400) } }, e)
        }
        val created = runBlocking { model.store.reader.docs(SampleIds.STUDIO).first { it.title == "Brand new wiki page" } }
        compose.waitUntil(40_000) {
            runBlocking { model.store.reader.docDraft(key(model, plan))?.let { WikiLinks.links(DocumentBody(it.body, it.contentType), created.id) } == true }
        }
        assertEquals("exactly one page was created", 1, runBlocking { model.store.reader.docs(SampleIds.STUDIO).count { it.title == "Brand new wiki page" } })
        compose.onNodeWithTag("doc.editor.back").performClick()
        compose.waitUntil(20_000) { model.editorDoc == null }
    }

    private fun editorWebView(): android.webkit.WebView? {
        var found: android.webkit.WebView? = null
        scenario!!.onActivity { activity ->
            fun walk(view: android.view.View) {
                if (found == null && view is android.webkit.WebView && view.isShown) found = view
                else if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(activity.window.decorView)
        }
        return found
    }

    /** A real finger tap inside the editor page, injected as pointer events. */
    private fun tapEditorPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val location = IntArray(2)
        var x = 0f; var y = 0f
        scenario!!.onActivity { _ ->
            val view = editorWebView()!!
            view.getLocationOnScreen(location)
            x = location[0] + view.width / 2f; y = location[1] + 120f
        }
        val down = android.os.SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, down + 80, android.view.MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun pageText(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var text = ""
        scenario!!.onActivity { _ ->
            editorWebView()!!.evaluateJavascript("document.getElementById('document-root').innerText") { text = it ?: ""; latch.countDown() }
        }
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        return text
    }

    @Test fun typingIntoTheEditorPageBecomesADurableDraftAndOnlySaveCanPublishIt() = launch { model ->
        val doc = cachedDoc(model, "Theme notes (Markdown)")
        reset(model, doc, themeNotes, "text/markdown")
        val typed = "Typedbyfinger"
        compose.runOnIdle { model.editorDoc = doc }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("doc.editor.save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { editorWebView() != null && pageText().contains("Theme notes") }
        tapEditorPage()
        InstrumentationRegistry.getInstrumentation().sendStringSync(typed)
        // Typing travelled page -> bridge -> controller -> durable draft; no state was written by the test.
        compose.waitUntil(30_000) { runBlocking { model.store.reader.docDraft(key(model, doc)) }?.let { String(it.body).contains(typed) } == true }
        assertFalse("typing alone publishes nothing", String(model.sampleTower!!.work.docBody(doc)!!).contains(typed))
        // Leaving and coming back reloads the page from the stored draft.
        compose.onNodeWithTag("doc.editor.back").performClick()
        compose.waitUntil(20_000) { model.editorDoc == null }
        compose.runOnIdle { model.editorDoc = doc }
        compose.waitUntil(30_000) { editorWebView() != null && pageText().contains(typed) }
        // So does the activity being recreated while the editor is open.
        scenario!!.recreate()
        compose.waitUntil(30_000) { editorWebView() != null && pageText().contains(typed) }
        assertNotNull(runBlocking { model.store.reader.docDraft(key(model, doc)) })
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("doc.editor.save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("doc.editor.save").performClick()
        try { compose.waitUntil(30_000) { String(model.sampleTower!!.work.docBody(doc)!!).contains(typed) } }
        catch (e: ComposeTimeoutException) {
            val status = compose.onAllNodesWithTag("doc.editor.status").fetchSemanticsNodes().joinToString { it.config.toString() }
            val stored = runBlocking { model.store.reader.docDraft(key(model, doc))?.let { "${it.state} base=${it.baseRowVersion} ${it.lastError}" } }
            throw AssertionError("Save did not publish. Status: $status. Draft: $stored. Shared: ${String(model.sampleTower!!.work.docBody(doc)!!).take(200)}", e)
        }
        compose.waitUntil(20_000) { runBlocking { model.store.reader.docDraft(key(model, doc)) } == null }
        assertNotNull("Save keeps the editor open", editorWebView())
        compose.onNodeWithTag("doc.editor.back").performClick()
        compose.waitUntil(20_000) { model.editorDoc == null }
    }
}
