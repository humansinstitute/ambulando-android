package studio.otherstuff.ambulando.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme
import studio.otherstuff.ambulando.core.docs.*
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.nio.file.Files

/** The review, takeover and wiki screens driven through a real store and a controller; nothing is published by looking. */
@RunWith(RobolectricTestRunner::class)
class DocumentReviewUiTest {
    @get:Rule val compose = createComposeRule()

    private val workspaceId = "00000000-0000-4000-8000-000000000001"
    private val docId = "00000000-0000-4000-8000-0000000000a3"
    private val channel = "00000000-0000-4000-8000-000000000c01"
    private val scopeId = "00000000-0000-4000-8000-00000000000a"
    private lateinit var store: LocalStore
    private lateinit var connection: ConnectionRecord
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workspace = WorkspaceRecord.parse(toJsonElement(mapOf(
        "identity" to mapOf("workspace_id" to workspaceId, "app_npub" to "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", "workspace_owner_npub" to "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"),
        "label" to "Team", "slug" to "team", "tower_base_url" to "https://tower.example.com", "membership" to mapOf("role" to "member"),
    )) as JsonObject)

    /** A Tower head the test moves, with every request recorded. */
    private class Authority(var version: Int, var text: String, var title: String = "Plan") : DocumentAuthority {
        val saves = mutableListOf<DocDraft>()
        val takeovers = mutableListOf<String?>()
        var holder: String? = null
        var objectId = "00000000-0000-4000-8000-0000000000e1"
        fun doc(workspace: String, id: String, channel: String) = DocRecord(workspace, id, null, channel, objectId, title, null, false, version.toLong(), 1, "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z")
        lateinit var ids: Triple<String, String, String>
        val bytes get() = text.toByteArray()
        fun body() = DocBodyRecord(ids.first, ids.second, objectId, Sha256Hex.of(bytes), "text/markdown", version.toLong(), bytes, "2026-10-01T00:00:00Z")
        override suspend fun acquire(docId: String, session: String?): EditLease {
            holder?.let { throw LeaseException.Held(it, "soon") }
            return grant(session)
        }
        private fun grant(session: String?) = EditLease("00000000-0000-4000-8000-0000000000b1", "t", "soon", DOCUMENT_EDIT_PROTOCOL, session)
        override suspend fun renew(docId: String, session: String?) {}
        override suspend fun release(docId: String, session: String?) {}
        override suspend fun save(draft: DocDraft, session: String?, asRecovery: Boolean): DocSaveResult { saves.add(draft); return DocSaveResult.NotSaved("not in this test") }
        override suspend fun latest(docId: String) = DocHead(doc(ids.first, ids.second, ids.third), body())
        override suspend fun inspect(docId: String) = DocumentEditInspection(true, holder, holder, false)
        override suspend fun takeover(docId: String, session: String, expectedLeaseId: String?): DocHead { takeovers.add(expectedLeaseId); holder = null; return latest(docId) }
        override suspend fun sessionLost(docId: String, session: String) = false
        override suspend fun resumeUnchangedSession(docId: String, session: String) = false
        override suspend fun recoveries(docId: String) = emptyList<DocumentRecovery>()
        override suspend fun recoveryBody(docId: String, recoveryId: String) = throw UnsupportedOperationException()
        override suspend fun resolveRecovery(docId: String, recoveryId: String, promote: Boolean, head: DocBodyRecord, session: String) {}
    }

    @Before fun setUp() = runBlocking {
        store = LocalStore(Files.createTempDirectory("review-ui").toFile())
        connection = store.writer.saveConnection(ConnectionRecord.create(TowerOrigin("https://tower.example.com"), "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", null))
    }

    @After fun tearDown() { scope.cancel(); store.close() }

    private fun editor(authority: Authority, body: String = "# Plan\n\none\n\ntwo\n\nthree\n"): DocumentEditController = runBlocking {
        authority.ids = Triple(workspaceId, docId, channel)
        authority.text = body
        val head = authority.latest(docId)
        store.writer.saveDocBody(head.body, head.doc, body)
        val controller = DocumentEditController(head.doc, connection, workspace, store, authority, scope, draftDebounceMillis = 5, reviewDebounceMillis = 5)
        controller.load()
        controller
    }

    private class MemoryPage(var text: String) : DocumentPage {
        override suspend fun snapshot() = text
        override fun load(payload: String) { text = org.json.JSONObject(payload).optString("markdown", text) }
        override fun setEditable(editable: Boolean) {}
    }

    private fun typeAndPublishElsewhere(controller: DocumentEditController, authority: Authority, mine: String, theirs: String) = runBlocking {
        val page = MemoryPage("")
        controller.pageAttached(page); controller.pageLoaded(true, null)
        page.text = mine // the person types after the page has opened
        controller.pageChanged(mine)
        controller.checkpoint()
        authority.version++; authority.text = theirs; authority.objectId = "00000000-0000-4000-8000-0000000000e2"
        controller.reconcileRemote()
    }

    @Test fun reviewShowsTheDifferenceAndMergesDisjointChangesWithoutPublishing() {
        val authority = Authority(1, "")
        val controller = editor(authority)
        typeAndPublishElsewhere(controller, authority, "# Plan\n\none, mine\n\ntwo\n\nthree\n", "# Plan\n\none\n\ntwo\n\nthree, theirs\n")
        var dismissed = false
        compose.setContent { AmbulandoTheme { DocumentReviewDialog(controller) { dismissed = true } } }
        compose.onNodeWithTag("doc.review").assertIsDisplayed()
        compose.onNodeWithText("Based on version 1; latest shared version is 2.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("doc.review.diff").assertExists()
        compose.onNodeWithTag("doc.review.merge").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { controller.mergeReady }
        compose.onNodeWithTag("doc.review.apply").performScrollTo().assertIsEnabled()
        assertEquals("looking and merging publish nothing", 0, authority.saves.size)
        compose.onNodeWithTag("doc.review.apply").performClick()
        compose.waitUntil(5_000) { dismissed }
        runBlocking {
            val draft = store.reader.docDraft(controller.draftKey)!!
            assertEquals("# Plan\n\none, mine\n\ntwo\n\nthree, theirs\n", String(draft.body))
            assertEquals("the merged draft is based on the reviewed head", 2, draft.baseRowVersion)
        }
        assertTrue(controller.state.value.pendingMergedSave)
        assertEquals("applying a merge only makes a local draft", 0, authority.saves.size)
    }

    @Test fun anOverlapNeedsAnExplicitChoiceBeforeTheMergedResultCanBeEdited() {
        val authority = Authority(1, "")
        val controller = editor(authority)
        typeAndPublishElsewhere(controller, authority, "# Plan\n\none\n\ntwo MINE\n\nthree\n", "# Plan\n\none\n\ntwo THEIRS\n\nthree\n")
        compose.setContent { AmbulandoTheme { DocumentReviewDialog(controller) {} } }
        compose.onNodeWithTag("doc.review.merge").performScrollTo().performClick()
        compose.waitUntil(5_000) { controller.mergeReady }
        compose.onAllNodesWithTag("doc.review.conflict").assertCountEquals(1)
        assertTrue("no apply while an overlap is unresolved", compose.onAllNodesWithTag("doc.review.apply").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("doc.review.use.latest").performScrollTo().performClick()
        compose.waitUntil(5_000) { controller.mergeConflicts.isEmpty() }
        compose.onNodeWithTag("doc.review.apply").performScrollTo().assertIsEnabled()
        assertEquals("# Plan\n\none\n\ntwo THEIRS\n\nthree\n", controller.mergeText)
    }

    @Test fun acceptLatestAsksFirstThenKeepsTheReplacedCopyAndCreatesNoVersion() {
        val authority = Authority(1, "")
        val controller = editor(authority)
        typeAndPublishElsewhere(controller, authority, "# Plan\n\nmine only\n", "# Plan\n\ntheirs only\n")
        var dismissed = false
        compose.setContent { AmbulandoTheme { DocumentReviewDialog(controller) { dismissed = true } } }
        compose.onNodeWithTag("doc.review.accept").performScrollTo().performClick()
        compose.onNodeWithText("Your working copy will be replaced", substring = true).assertExists()
        assertNotNull("nothing replaced before confirming", runBlocking { store.reader.docDraft(controller.draftKey) })
        compose.onNodeWithTag("doc.review.accept.confirm").performClick()
        compose.waitUntil(5_000) { dismissed }
        runBlocking {
            assertNull(store.reader.docDraft(controller.draftKey))
            assertTrue(store.loadDocumentReview(controller.draftKey).retained.any { String(it.bytes).contains("mine only") })
        }
        assertEquals(0, authority.saves.size)
        assertEquals(2, controller.localBaseVersion)
    }

    @Test fun noVerifiedBaseMeansNoAutomaticMergeAndSaysWhy() {
        val authority = Authority(1, "")
        authority.ids = Triple(workspaceId, docId, channel)
        val head = runBlocking { authority.latest(docId) }
        // A draft from an earlier build whose base bytes were never retained, with the cache already moved on.
        runBlocking {
            authority.text = "# Plan\n\nold\n"
            store.writer.saveDocDraft(DocDraft(connection.id, workspaceId, docId, "Plan", "# Plan\n\nold, mine\n".toByteArray(), "text/markdown", 1, "ff".repeat(32), "00000000-0000-4000-8000-0000000000ee", DocDraft.State.EDITING))
            authority.version = 2; authority.text = "# Plan\n\nnew\n"
            val moved = authority.latest(docId)
            store.writer.saveDocBody(moved.body, moved.doc, "new")
        }
        val controller = DocumentEditController(head.doc, connection, workspace, store, authority, scope)
        runBlocking { controller.load(); controller.pageAttached(MemoryPage("")); controller.pageLoaded(true, null); controller.reconcileRemote() }
        compose.setContent { AmbulandoTheme { DocumentReviewDialog(controller) {} } }
        compose.onNodeWithTag("doc.review.merge").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("doc.review.nobase").assertExists()
        compose.onNodeWithTag("doc.review.accept").assertIsEnabled()
    }

    @Test fun takeoverNamesTheConsequencesAndOnlyActsOnConfirm() {
        val authority = Authority(1, "")
        authority.holder = "00000000-0000-4000-8000-0000000000b9"
        val controller = editor(authority)
        runBlocking {
            controller.pageAttached(MemoryPage("")); controller.pageLoaded(true, null)
            controller.prepareOpening()
            controller.holdLease()
        }
        compose.waitUntil(5_000) { controller.state.value.authorityLost && controller.state.value.canTakeover }
        var dismissed = false
        compose.setContent { AmbulandoTheme { TakeoverDialog(controller) { dismissed = true } } }
        compose.onNodeWithText("Another editor currently has this document open.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("If anything fails, your draft stays on this device", substring = true).assertIsDisplayed()
        assertTrue("nothing was taken yet", authority.takeovers.isEmpty())
        compose.onNodeWithTag("doc.takeover.confirm").performClick()
        compose.waitUntil(5_000) { dismissed }
        assertEquals("the exact lease the person saw was named", listOf<String?>("00000000-0000-4000-8000-0000000000b9"), authority.takeovers)
        assertFalse(controller.state.value.authorityLost)
    }

    // MARK: wiki

    private fun wikiContext(vararg docs: DocRecord) = WikiContext(
        docs.toList(), listOf(ChannelRecord(workspaceId, channel, scopeId, "general", null, 1.0, 1, "x")),
        listOf(ScopeRecord.parse(toJsonElement(mapOf("id" to scopeId, "workspace_id" to workspaceId, "name" to "Team", "row_version" to 1, "updated_at" to "2026-10-01T00:00:00.000Z")) as JsonObject)),
    )

    private fun page(n: Int, title: String) = DocRecord(workspaceId, "00000000-0000-4000-8000-0000000000%02d".format(n), scopeId, channel, "o", title, null, false, 1, 1, "x", "x")

    private class Env(val context: WikiContext) : WikiEnvironment {
        val submitted = mutableListOf<WriteIntent>()
        val opened = mutableListOf<String>()
        override suspend fun loadContext() = context
        override fun visit() = WikiVisit("c", "w", true, 1)
        override suspend fun beforeLeaving() = true
        override fun navigate(documentId: String) { opened.add(documentId) }
        override suspend fun submit(intent: WriteIntent): Boolean { submitted.add(intent); return true }
        override suspend fun stage(bytes: ByteArray) = "staged"
        override suspend fun bindInDraft(title: String, documentId: String, occurrence: Int?, expectedCount: Int?) = WikiBindOutcome.Saved
    }

    @Test fun theDoubleBracketPanelListsThisChannelAndOffersToCreateOrSearchMore() {
        val env = Env(wikiContext(page(1, "Source"), page(2, "Roadmap"), page(3, "Roadmap notes")))
        val source = page(1, "Source")
        val controller = WikiLinkController(source.id, env)
        runBlocking { controller.refresh() }
        controller.pageWikiQuery(true, "road", 4, 10)
        val initial = controller.state.value
        compose.setContent { AmbulandoTheme { WikiQueryPanel(controller, initial, scope) } }
        compose.onNodeWithTag("wiki.panel").assertIsDisplayed()
        compose.onAllNodesWithTag("wiki.panel.result").assertCountEquals(2)
        compose.onNodeWithText("Create “road” or search more…").assertExists()
    }

    @Test fun anAmbiguousTitleShowsEachPagesPlaceAndPicksNothingUntilChosen() {
        val env = Env(wikiContext(page(1, "Source"), page(2, "Standup"), page(3, "Standup")))
        val controller = WikiLinkController(page(1, "Source").id, env)
        runBlocking { controller.activate(WikiTarget(title = "Standup")) }
        compose.setContent { val s by controller.state.collectAsState(); AmbulandoTheme { WikiSheets(controller, s, scope) } }
        compose.onAllNodesWithTag("wiki.choose.item").assertCountEquals(2)
        compose.onAllNodesWithText("Team / #general", substring = true).assertCountEquals(2)
        assertTrue("nothing opened or bound before a choice", env.opened.isEmpty() && env.submitted.isEmpty())
        compose.onAllNodesWithTag("wiki.choose.item")[0].performClick()
        compose.waitUntil(5_000) { env.opened.size == 1 }
    }

    @Test fun aMissingPageOffersCreateAndLinkWithItsDestinationStated() {
        val env = Env(wikiContext(page(1, "Source")))
        val controller = WikiLinkController(page(1, "Source").id, env, newRequestId = { "req" })
        runBlocking { controller.activate(WikiTarget(title = "Brand new", occurrence = 0, count = 1)) }
        compose.setContent { val s by controller.state.collectAsState(); AmbulandoTheme { WikiSheets(controller, s, scope) } }
        compose.onNodeWithText("There is no page called “Brand new” yet.").assertIsDisplayed()
        compose.onNodeWithText("It will be created as a document in Team / #general and linked here.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("wiki.create.confirm").performClick()
        compose.waitUntil(5_000) { env.submitted.isNotEmpty() }
        assertEquals(WriteKind.DOC_CREATE, env.submitted.single().kind)
    }
}
