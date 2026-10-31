package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.store.WriteIntent
import studio.otherstuff.ambulando.core.store.WriteItem
import studio.otherstuff.ambulando.core.store.WriteKind
import studio.otherstuff.ambulando.core.store.WriteState
import studio.otherstuff.ambulando.core.store.docDraft
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.store.StoreFixtures
import java.net.UnknownHostException

/** Linking an existing page and create-and-link: the source keeps its link through offline, lost answers, drafts and races. */
class WikiCreateAndLinkTest {
    private val chA = Fixtures.CHANNEL_1
    private val chB = Fixtures.CHANNEL_2
    private fun id(n: Int) = "00000000-0000-4000-8000-0000000000%02d".format(n)

    private fun doc(n: Int, title: String, channel: String = chA, workspace: String = Fixtures.WORKSPACE_ID, archived: Boolean = false) = DocRecord(
        workspace, id(n), Fixtures.SCOPE_A, channel, "obj$n", title, null, archived, 1, 1, "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z",
    )

    // MARK: binding into a source with no open editor

    private suspend fun World.setSource(vararg paragraphs: String) {
        tower.body = Docs.rich(*paragraphs); tower.objects[tower.objectId] = tower.body; cacheHead()
    }

    private suspend fun World.bind(title: String, target: Int = 5, occurrence: Int? = null, expected: Int? = null) =
        WikiBinder.bind(title, id(target), occurrence, expected, tower.docId, connection, workspace, store, authority)

    @Test fun aCleanSourceSavesTheLinkRightAway() = world {
        setSource("see [[Roadmap]] here", "other")
        val outcome = bind("Roadmap")
        assertEquals(WikiBindOutcome.Saved, outcome)
        assertEquals(1, tower.patches.size)
        val refs = WikiLinks.references(DocumentBody(tower.body, tower.contentType))
        assertEquals(listOf("Roadmap" to id(5)), refs.map { it.title to it.documentId })
        assertEquals("unknown envelope fields survive", JsonValue.Bool(true), JsonValue.parse(tower.body)["future_field"]?.get("keep"))
        assertNull(store.reader.docDraft(DocDraft.key(connection.id, workspace.id, tower.docId)))
    }

    @Test fun unsavedEditsInTheSourceReceiveTheLinkWithoutBeingUploaded() = world {
        setSource("see [[Roadmap]] here", "other")
        val page = FakePage()
        val editor = open(page)
        page.editParagraph(1, "other, edited")
        editor.pageChanged(page.text); editor.checkpoint()
        val outcome = bind("Roadmap")
        assertTrue("kept in the draft: $outcome", outcome is WikiBindOutcome.KeptInDraft)
        assertEquals("nothing is uploaded behind the person's back", 0, tower.patches.size)
        val draft = store.reader.docDraft(DocDraft.key(connection.id, workspace.id, tower.docId))!!
        assertEquals("the person's own edit is still there", "other, edited", Docs.paragraphs(draft.body)[1])
        assertEquals(id(5), WikiLinks.references(DocumentBody(draft.body, draft.contentType)).single().documentId)
    }

    @Test fun anOfflineSourceKeepsTheLinkInADraftAndLaterSavesIt() = world {
        setSource("see [[Roadmap]] here")
        tower.offline = true
        val outcome = bind("Roadmap")
        assertTrue(outcome is WikiBindOutcome.KeptInDraft)
        val draft = store.reader.docDraft(DocDraft.key(connection.id, workspace.id, tower.docId))!!
        assertEquals(id(5), WikiLinks.references(DocumentBody(draft.body, draft.contentType)).single().documentId)
        tower.offline = false
        val editor = open(FakePage())
        editor.prepareOpening()
        assertTrue(editor.save())
        assertEquals(id(5), WikiLinks.references(DocumentBody(tower.body, tower.contentType)).single().documentId)
    }

    @Test fun referencesThatChangedSinceTheRequestAreNotGuessedAt() = world {
        setSource("[[Roadmap]] and [[Roadmap]]")
        assertEquals("one reference was added after the request counted one", WikiBindOutcome.NoLongerRequested, bind("Roadmap", expected = 1))
        assertEquals(0, tower.patches.size)
        assertNull(store.reader.docDraft(DocDraft.key(connection.id, workspace.id, tower.docId)))
        // With the right count, only the opened occurrence is bound.
        assertEquals(WikiBindOutcome.Saved, bind("Roadmap", occurrence = 1, expected = 2))
        val refs = WikiLinks.references(DocumentBody(tower.body, tower.contentType))
        assertEquals(listOf(null, id(5)), refs.map { it.documentId })
    }

    @Test fun anAlreadyBoundSourceIsLeftAlone() = world {
        setSource("[Roadmap](wiki:${id(5)})")
        assertEquals(WikiBindOutcome.AlreadyBound, bind("Roadmap"))
        assertEquals(0, tower.patches.size)
        setSource("nothing to bind here")
        assertEquals("no matching reference: the body is not rewritten", WikiBindOutcome.AlreadyBound, bind("Roadmap"))
    }

    @Test fun aSourceThatIsNotSavedHereOrCannotBeEditedIsReportedNotInvented() = world {
        val other = "00000000-0000-4000-8000-0000000000fe"
        assertEquals(WikiBindOutcome.SourceNotSaved, WikiBinder.bind("T", id(5), null, null, other, connection, workspace, store, authority))
        val item = fakeWrite(mapOf(WikiCreate.SOURCE_KEY to other, WikiCreate.TITLE_KEY to "T"))
        try {
            WikiBinder.complete(item, doc(5, "T"), connection, workspace, store, authority)
            fail("a missing source keeps the write queued")
        } catch (e: WikiBindError) {
            assertEquals(WikiBindError.SourceUnavailable, e)
        }
    }

    @Test fun markdownSourcesBindToo() = world {
        tower.body = "intro [[Roadmap]]\n\n`[[Roadmap]]`\n".toByteArray(); tower.contentType = "text/markdown"; tower.objects[tower.objectId] = tower.body; cacheHead()
        assertEquals(WikiBindOutcome.Saved, bind("Roadmap"))
        assertEquals("intro [Roadmap](wiki:${id(5)})\n\n`[[Roadmap]]`\n", tower.body.toString(Charsets.UTF_8))
    }

    private fun World.fakeWrite(fields: Map<String, String>): WriteItem {
        val payload = kotlinx.serialization.json.JsonObject(fields.mapValues { JsonPrimitive(it.value) })
        return WriteItem("w1", connection.id, connection.origin, connection.signerNpub, workspace.id, workspace.appNpub, WriteKind.DOC_CREATE, chA,
            "doc:$chA", studio.otherstuff.ambulando.core.transport.jsonBody(payload), null, WriteState.QUEUED, 0, null, null, null, "2026-10-01T00:00:00Z")
    }

    // MARK: the controller's decisions

    private class Env(var context: WikiContext, var visit: WikiVisit) : WikiEnvironment {
        val submitted = mutableListOf<WriteIntent>()
        val navigated = mutableListOf<String>()
        val bound = mutableListOf<List<Any?>>()
        var checkpointOk = true
        var onCheckpoint: (() -> Unit)? = null
        var connected = true
        var bindOutcome: WikiBindOutcome? = WikiBindOutcome.Saved
        override suspend fun loadContext() = context
        override fun visit() = visit
        override suspend fun beforeLeaving(): Boolean { onCheckpoint?.invoke(); return checkpointOk }
        override fun navigate(documentId: String) { navigated.add(documentId) }
        override suspend fun submit(intent: WriteIntent): Boolean { if (connected) submitted.add(intent); return connected }
        override suspend fun stage(bytes: ByteArray): String? = "staged-${bytes.size}"
        override suspend fun bindInDraft(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): WikiBindOutcome? {
            bound.add(listOf(title, documentId, occurrence, expectedCount)); return bindOutcome
        }
    }

    private fun context(vararg docs: DocRecord, pending: List<WriteItem> = emptyList()) = WikiContext(
        docs.toList(),
        listOf(ChannelRecord(Fixtures.WORKSPACE_ID, chA, Fixtures.SCOPE_A, "general", null, 1.0, 1, "x"), ChannelRecord(Fixtures.WORKSPACE_ID, chB, Fixtures.SCOPE_A, "ideas", null, 2.0, 1, "x")),
        listOf(ScopeRecord.parse(Fixtures.obj(Fixtures.scope(Fixtures.SCOPE_A, Fixtures.WORKSPACE_ID, "Team")))), emptyMap(), pending,
    )

    private fun visit(epoch: Int = 1, visible: Boolean = true, workspace: String = Fixtures.WORKSPACE_ID) = WikiVisit("c1", workspace, visible, epoch)

    private fun controller(env: Env, source: Int = 1) = WikiLinkController(id(source), env, newRequestId = { "request-1" })

    @Test fun aBoundLinkOpensByIdEvenWhenThePageIsNotSavedHere() = runBlocking {
        val env = Env(context(doc(1, "Source")), visit())
        val wiki = controller(env)
        wiki.activate(WikiTarget(documentId = id(42), title = "Gone"))
        assertEquals(listOf(id(42)), env.navigated)
        assertTrue("nothing is created or rebound for an unknown id", env.submitted.isEmpty() && env.bound.isEmpty())
    }

    @Test fun aTitleThatMatchesOnePageIsBoundThenOpened() = runBlocking {
        val env = Env(context(doc(1, "Source"), doc(2, "Roadmap")), visit())
        val wiki = controller(env)
        wiki.activate(WikiTarget(title = "roadmap", occurrence = 1, count = 3))
        assertEquals(listOf(listOf<Any?>("roadmap", id(2), 1, 3)), env.bound)
        assertEquals(listOf(id(2)), env.navigated)
    }

    @Test fun anAmbiguousTitleOffersAChoiceAndPicksNothing() = runBlocking {
        val env = Env(context(doc(1, "Source"), doc(2, "Roadmap"), doc(3, "Roadmap"), doc(4, "Roadmap", channel = chB)), visit())
        val wiki = controller(env)
        wiki.activate(WikiTarget(title = "Roadmap"))
        val sheet = wiki.state.value.sheet as WikiLinkController.Sheet.Choose
        assertEquals(setOf(id(2), id(3)), sheet.matches.map { it.id }.toSet())
        assertTrue(env.bound.isEmpty() && env.navigated.isEmpty() && env.submitted.isEmpty())
        // The person chooses one: it is bound and opened.
        wiki.choose(sheet.matches.first { it.id == id(3) }, sheet.pick, openAfter = true)
        assertEquals(id(3), env.bound.single()[1])
        assertEquals(listOf(id(3)), env.navigated)
    }

    @Test fun aMissingPageOffersCreationAndCreateAndLinkQueuesOneDurableWrite() = runBlocking {
        val env = Env(context(doc(1, "Source")), visit())
        val wiki = controller(env)
        val target = WikiTarget(title = "New page", occurrence = 2, count = 3)
        wiki.activate(target)
        assertTrue(wiki.state.value.sheet is WikiLinkController.Sheet.Create)
        wiki.createAndLink("  New page ", chA, WikiLinkController.Pick.Reference(target))
        val intent = env.submitted.single()
        assertEquals(WriteKind.DOC_CREATE, intent.kind)
        assertEquals(chA, intent.targetId)
        val p = intent.payload
        assertEquals(chA, p["channel_id"]!!.jsonPrimitive.content)
        assertEquals("New page", p["title"]!!.jsonPrimitive.content)
        assertEquals(id(1), p[WikiCreate.SOURCE_KEY]!!.jsonPrimitive.content)
        assertEquals("New page", p[WikiCreate.TITLE_KEY]!!.jsonPrimitive.content)
        assertEquals(2, p[WikiCreate.OCCURRENCE_KEY]!!.jsonPrimitive.content.toInt())
        assertEquals(3, p[WikiCreate.EXPECTED_COUNT_KEY]!!.jsonPrimitive.content.toInt())
        assertEquals("request-1", p[WikiCreate.REQUEST_KEY]!!.jsonPrimitive.content)
        assertTrue(p["staged"]!!.jsonPrimitive.content.startsWith("staged-"))
        assertFalse(wiki.state.value.bannerIsProblem)
    }

    @Test fun aRepeatRequestFindsThePendingOrExistingPageInsteadOfCreatingAnother() = runBlocking {
        val pendingItem = pendingCreate("Queued", chA)
        val env = Env(context(doc(1, "Source"), doc(2, "Existing"), doc(3, "Twin"), doc(4, "Twin"), pending = listOf(pendingItem)), visit())
        val wiki = controller(env)
        wiki.createAndLink("queued", chA, WikiLinkController.Pick.Reference(WikiTarget(title = "queued")))
        assertTrue(env.submitted.isEmpty())
        assertTrue(wiki.state.value.banner!!.contains("already being created"))
        wiki.createAndLink("Existing", chA, WikiLinkController.Pick.Reference(WikiTarget(title = "Existing", occurrence = 0)))
        assertTrue("linked instead of creating another", env.submitted.isEmpty())
        assertEquals(id(2), env.bound.single()[1])
        env.bound.clear()
        wiki.createAndLink("Twin", chA, WikiLinkController.Pick.Reference(WikiTarget(title = "Twin")))
        val sheet = wiki.state.value.sheet as WikiLinkController.Sheet.Choose
        assertEquals(2, sheet.matches.size)
        assertTrue("no page is created or chosen arbitrarily", env.submitted.isEmpty() && env.bound.isEmpty())
    }

    @Test fun aDeviceThatIsNotConnectedSaysSoAndQueuesNothing() = runBlocking {
        val env = Env(context(doc(1, "Source")), visit()).apply { connected = false }
        val wiki = controller(env)
        wiki.createAndLink("Anything", chA, WikiLinkController.Pick.Reference(WikiTarget(title = "Anything")))
        assertTrue(env.submitted.isEmpty())
        assertTrue(wiki.state.value.bannerIsProblem)
    }

    @Test fun draftFirstNavigationNeverLeavesWithUnstoredTextOrCarriesAPersonToAScreenTheyLeft() = runBlocking {
        val env = Env(context(doc(1, "Source")), visit())
        val wiki = controller(env)
        env.checkpointOk = false
        wiki.openPage(id(2))
        assertTrue("the text could not be stored: the other page is not opened", env.navigated.isEmpty())
        assertTrue(wiki.state.value.bannerIsProblem)
        env.checkpointOk = true
        env.onCheckpoint = { env.visit = env.visit.copy(epoch = env.visit.epoch + 1) } // the person left and came back during the wait
        wiki.openPage(id(2))
        assertTrue("a new visit does not inherit the old request", env.navigated.isEmpty())
        env.onCheckpoint = { env.visit = env.visit.copy(workspaceId = Fixtures.WORKSPACE_ID_2) }
        wiki.openPage(id(2))
        assertTrue("a workspace switch cancels it", env.navigated.isEmpty())
    }

    @Test fun aCreatedPageOpensOnlyOnTheSameVisitAndConnection() = runBlocking {
        val env = Env(context(doc(1, "Source")), visit())
        val wiki = controller(env)
        val origin = env.visit
        wiki.openCreated(doc(9, "Made"), "c1", origin)
        assertEquals(id(9), wiki.pendingOpen!!.first)
        wiki.openPendingCreated()
        assertEquals(listOf(id(9)), env.navigated)
        env.navigated.clear()
        env.visit = origin.copy(epoch = 2)
        wiki.openCreated(doc(10, "Other"), "c1", origin)
        assertNull("a later visit only gets a notice", wiki.pendingOpen)
        assertTrue(wiki.state.value.banner!!.contains("was created and linked"))
        wiki.openCreated(doc(11, "Other account"), "c2", env.visit)
        assertNull(wiki.pendingOpen)
        assertTrue(env.navigated.isEmpty())
    }

    @Test fun typingDoubleBracketsSearchesThisChannelFirstAndOffersEveryChannelOnRequest() = runBlocking {
        val env = Env(context(doc(1, "Source"), doc(2, "Roadmap"), doc(3, "Roadmap notes", channel = chB), doc(4, "Roadmap", channel = chB)), visit())
        val wiki = controller(env)
        wiki.refresh()
        wiki.pageWikiQuery(true, "road", 10, 16)
        assertEquals(WikiQuery("road", WikiRange(10, 16)), wiki.state.value.query)
        assertEquals(listOf(id(2)), wiki.panelResults("road", everywhere = false).map { it.id })
        assertEquals(listOf(id(2), id(4), id(3)), wiki.panelResults("road", everywhere = true).map { it.id })
        wiki.pageWikiQuery(false, "", null, null)
        assertNull(wiki.state.value.query)
    }

    private fun pendingCreate(title: String, channel: String): WriteItem {
        val payload = kotlinx.serialization.json.JsonObject(mapOf("title" to JsonPrimitive(title), "channel_id" to JsonPrimitive(channel)))
        return WriteItem("pending1", "c1", "https://tower.example.com", "npub", Fixtures.WORKSPACE_ID, "app", WriteKind.DOC_CREATE, channel, "doc:$channel",
            studio.otherstuff.ambulando.core.transport.jsonBody(payload), null, WriteState.QUEUED, 0, null, null, null, "2026-10-01T00:00:00Z")
    }

    // MARK: the open editor

    @Test fun insertingALinkAtTheTypedRangeStoresADurableDraftBeforeAnythingIsAsked() = world {
        val page = FakePage()
        val editor = open(page)
        val env = Env(context(doc(1, "Source")).copy(channels = emptyList()), visit(workspace = workspace.id))
        val wiki = WikiLinkController(tower.docId, env, newRequestId = { "r" })
        wiki.editor = editor
        wiki.refresh()
        // Choosing an existing page.
        wiki.choose(doc(7, "Roadmap"), WikiLinkController.Pick.Insert(WikiRange(1, 5)), openAfter = false)
        val draft = store.reader.docDraft(editor.draftKey)!!
        assertEquals(id(7), WikiLinks.references(DocumentBody(draft.body, draft.contentType)).single().documentId)
        assertEquals("no network is needed to link an existing page", 0, tower.patches.size)
        // Create-and-link: the title-only link is stored first, then one write is queued carrying its position.
        wiki.createAndLink("Fresh", chA, WikiLinkController.Pick.Insert(WikiRange(1, 5)))
        val stored = store.reader.docDraft(editor.draftKey)!!
        assertEquals(listOf(id(7) as String?, null), WikiLinks.references(DocumentBody(stored.body, stored.contentType)).map { it.documentId })
        val p = env.submitted.single().payload
        assertEquals("Fresh", p[WikiCreate.TITLE_KEY]!!.jsonPrimitive.content)
        assertEquals(0, p[WikiCreate.OCCURRENCE_KEY]!!.jsonPrimitive.content.toInt())
        assertEquals(1, p[WikiCreate.EXPECTED_COUNT_KEY]!!.jsonPrimitive.content.toInt())
    }

    @Test fun anEditorThatIsNotReadyCreatesNothing() = world {
        val page = FakePage().apply { ready = false }
        val editor = open(page)
        val env = Env(context(doc(1, "Source")), visit(workspace = workspace.id))
        val wiki = WikiLinkController(tower.docId, env)
        wiki.editor = editor
        wiki.createAndLink("Fresh", chA, WikiLinkController.Pick.Insert(null))
        assertTrue(env.submitted.isEmpty())
        assertTrue(wiki.state.value.bannerIsProblem)
    }

    @Test fun anEditorBindsTheCreatedPageAndOnlyCountsItOnceTheTextIsDurable() = world {
        setSource("see [[Fresh]]")
        val page = FakePage()
        val editor = open(page)
        assertEquals(WikiEditorBinding.BOUND, editor.bindWiki("Fresh", id(8), occurrence = 0, expectedCount = 1))
        assertEquals(id(8), WikiLinks.references(DocumentBody(store.reader.docDraft(editor.draftKey)!!.let { it.body }, DocumentBody.ENVELOPE_CONTENT_TYPE)).single().documentId)
        // A second request that still expects one reference finds none: the text changed, so nothing is bound by position.
        assertEquals(WikiEditorBinding.NO_LONGER_REQUESTED, editor.bindWiki("Fresh", id(8), occurrence = 0, expectedCount = 1))
        // A retry whose link is already in the text binds nothing more, and still reports the durable state.
        assertEquals(WikiEditorBinding.NOTHING_TO_BIND, editor.bindWiki("Fresh", id(8), occurrence = null, expectedCount = null))
        assertEquals(WikiEditorBinding.FAILED, open(FakePage().apply { ready = false }).bindWiki("Fresh", id(8), null, null))
    }

    @Test fun workspacesAreKeptApartByTheirContext() = runBlocking {
        val other = Env(context(doc(1, "Source", workspace = Fixtures.WORKSPACE_ID_2), doc(2, "Roadmap", workspace = Fixtures.WORKSPACE_ID_2)), visit(workspace = Fixtures.WORKSPACE_ID_2))
        val here = Env(context(doc(1, "Source")), visit())
        val wikiHere = controller(here)
        wikiHere.activate(WikiTarget(title = "Roadmap"))
        assertTrue("a page in another workspace is never matched by title", wikiHere.state.value.sheet is WikiLinkController.Sheet.Create)
        assertTrue(other.bound.isEmpty())
    }
}
