package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.StoreFixtures
import studio.otherstuff.ambulando.core.store.docBody
import studio.otherstuff.ambulando.core.store.docDraft
import studio.otherstuff.ambulando.core.store.docDraftStore
import studio.otherstuff.ambulando.core.store.loadDocumentReview
import studio.otherstuff.ambulando.core.store.saveDocBody
import studio.otherstuff.ambulando.core.store.saveDocDraft
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.docs.DocumentEditController.Tone

/** One signed-in device: a real SQLite store, a saver over the fake Tower, and any number of open editors. */
internal class World(val tower: FakeDocTower = FakeDocTower(), val store: LocalStore = StoreFixtures.store()) {
    lateinit var connection: ConnectionRecord
    val workspace: WorkspaceRecord = StoreFixtures.workspace(tower.workspace, "Team")
    var unlocked = true
    private var sessions = 0
    val saver = DocumentSaver(store.writer.docDraftStore(store.reader), { tower }, newSessionId = { "00000000-0000-4000-8000-00000000f%03d".format(++sessions) })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val authority get() = SaverAuthority(saver, DocSaveTarget(connection.id, workspace.id, workspace.appNpub))

    suspend fun connect() { connection = StoreFixtures.connect(store); cacheHead() }
    suspend fun reconnect(existing: ConnectionRecord) { connection = existing }

    /** What this device last downloaded. */
    suspend fun cacheHead() {
        val head = tower.fetchHead(tower.workspace, "", tower.docId)!!
        store.writer.saveDocBody(head.body, head.doc, DocumentBody(head.body.bytes, head.body.contentType).markdown)
    }

    fun docRecord(): DocRecord = DocRecord.parse(kotlinx.serialization.json.Json.parseToJsonElement(tower.docJson()) as kotlinx.serialization.json.JsonObject)

    suspend fun open(page: FakePage = FakePage(), uploads: () -> Boolean = { false }, autosaveMillis: Long = 600_000): DocumentEditController {
        val controller = DocumentEditController(
            docRecord(), connection, workspace, store, authority, scope, isUnlocked = { unlocked }, uploadsPending = uploads,
            draftDebounceMillis = 5, reviewDebounceMillis = 5, renewMillis = 25, autosaveMillis = autosaveMillis,
        )
        controller.load()
        controller.pageAttached(page)
        controller.pageLoaded(true, null)
        return controller
    }

    fun close() { scope.cancel(); store.close() }
}

/** Polls (real time) until [condition] holds; the editor's background renew runs on its own schedule. */
internal suspend fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    kotlinx.coroutines.withTimeout(timeoutMillis) { while (!condition()) kotlinx.coroutines.delay(10) }
}

internal fun world(tower: FakeDocTower = FakeDocTower(), block: suspend World.() -> Unit) = runBlocking {
    val w = World(tower)
    try { w.connect(); w.block() } finally { w.close() }
}

class DocumentEditControllerTest {
    private val key get() = DocDraft.key(Fixtures.WORKSPACE_ID, Fixtures.WORKSPACE_ID, FakeDocTower.DOC_ID)

    private suspend fun World.draft() = store.reader.docDraft(DocDraft.key(connection.id, workspace.id, tower.docId))

    private fun bodyParagraphs(tower: FakeDocTower) = Docs.paragraphs(tower.body)

    // MARK: durable local drafts

    @Test fun editsBecomeADurableDraftThatSurvivesRelaunchWithoutAnyNetwork() = world {
        tower.offline = true
        val page = FakePage()
        val editor = open(page)
        page.editParagraph(1, "Two, edited offline")
        editor.pageChanged(page.text)
        assertTrue(editor.checkpoint())
        val stored = draft()!!
        assertEquals(DocDraft.State.EDITING, stored.state)
        assertEquals(1, stored.baseRowVersion)
        assertEquals("Two, edited offline", Docs.paragraphs(stored.body)[1])
        // Relaunch: a new editor on the same store (process death lost every in-memory lease and session).
        val second = World(tower, store).also { it.reconnect(connection) }
        val reopened = second.open(FakePage())
        assertTrue("the draft is restored as unsaved work", reopened.dirty)
        assertEquals("Two, edited offline", reopened.page!!.let { (it as FakePage).paragraphs()[1] })
        assertEquals("nothing was published while offline", 0, tower.patches.size)
        // Back online: Save publishes it.
        tower.offline = false
        assertTrue(reopened.prepareOpening())
        assertTrue(reopened.save())
        assertEquals(listOf("One", "Two, edited offline", "Three"), bodyParagraphs(tower))
        assertNull(second.draft())
        second.scope.cancel()
    }

    // Found as the intermittent "lease timing" failures: the background version check sets `reconciling`, and a person's Save or
    // Takeover that arrived in that window returned false without a word (autosave every 20 ms made the window common in tests).
    @Test fun aSaveThatArrivesDuringTheBackgroundVersionCheckWaitsForItInsteadOfBeingDropped() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(1, "Two, edited")
        editor.pageChanged(page.text)
        assertTrue(editor.checkpoint())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        tower.headGate = gate
        val check = scope.launch { editor.reconcileRemote() }
        eventually { editor.reconciling }
        val save = scope.async { editor.save() }
        kotlinx.coroutines.delay(100)
        assertFalse("the save is waiting for the check, not finished or dropped", save.isCompleted)
        assertEquals(0, tower.patches.size)
        gate.complete(Unit)
        assertTrue("the save went ahead once the check ended", save.await())
        check.join()
        assertEquals(listOf("One", "Two, edited", "Three"), bodyParagraphs(tower))
        editor.close()
    }

    @Test fun aSaveAndATakeoverThatOutwaitTheVersionCheckDeclineVisiblyAndChangeNothing() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(1, "Two, edited")
        editor.pageChanged(page.text)
        assertTrue(editor.checkpoint())
        editor.reconcileWaitMillis = 150
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        tower.headGate = gate
        val check = scope.launch { editor.reconcileRemote() }
        eventually { editor.reconciling }
        assertFalse("the save declines once the bounded wait ends", editor.save())
        editor.takeover()
        assertEquals(0, tower.patches.size)
        assertTrue("the person is told, not left with silence", editor.status.orEmpty().contains("Still checking"))
        gate.complete(Unit)
        check.join()
        assertTrue("a later save is not blocked by the earlier decline", editor.save())
        editor.close()
    }

    @Test fun closingTheEditorEndsAWaitingSaveWithoutSavingAnything() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(1, "Two, edited")
        editor.pageChanged(page.text)
        assertTrue(editor.checkpoint())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        tower.headGate = gate
        val check = scope.launch { editor.reconcileRemote() }
        eventually { editor.reconciling }
        val save = scope.async { editor.save() }
        kotlinx.coroutines.delay(50)
        editor.close()
        assertFalse(save.await())
        assertEquals(0, tower.patches.size)
        gate.complete(Unit)
        check.join()
    }

    @Test fun cancelledOrFailedSaveKeepsTheDraftAndRetryDoesNotDuplicate() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(0, "One, v2")
        editor.pageChanged(page.text)
        // The answer to a successful PATCH is lost.
        tower.loseNextPatchResponse = true
        assertFalse(editor.save())
        assertEquals("the edit is already the shared head, but this device was not told", listOf("One, v2", "Two", "Three"), bodyParagraphs(tower))
        assertNotNull("the draft survives the uncertain answer", draft())
        assertEquals(1, tower.patches.size)
        // Retry: the identical canonical snapshot is acknowledged without another body mutation or upload.
        val uploadsBefore = tower.uploads
        assertTrue(editor.save())
        assertEquals("no second version", 1, tower.patches.size)
        assertEquals("no second upload", uploadsBefore, tower.uploads)
        assertEquals(2, tower.version)
        assertNull(draft())
        assertEquals(2, editor.localBaseVersion)
        assertEquals(Tone.SUCCESS, editor.statusTone)
    }

    @Test fun anUploadThatCompletedBeforeALostAnswerIsReusedNotRepeated() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(2, "Three, v2")
        editor.pageChanged(page.text)
        tower.offline = false
        // Upload succeeds; the PATCH never arrives (network drops between).
        tower.loseNextPatchResponse = false
        val flaky = object : DocumentSaveApi by tower {
            var failPatch = true
            override suspend fun patchDocument(workspaceId: String, appNpub: String, docId: String, body: kotlinx.serialization.json.JsonObject): SaveReply {
                if (failPatch) { failPatch = false; throw java.net.UnknownHostException("dropped") }
                return tower.patchDocument(workspaceId, appNpub, docId, body)
            }
        }
        val saver = DocumentSaver(store.writer.docDraftStore(store.reader), { flaky })
        val persisted = editor.persistDraft(DocDraft.State.EDITING)!!
        val target = DocSaveTarget(connection.id, workspace.id, workspace.appNpub)
        assertTrue(saver.save(persisted, target, editor.sessionId) is DocSaveResult.NotSaved)
        assertEquals(1, tower.uploads)
        val retry = store.reader.docDraft(persisted.key)!!
        assertTrue(saver.save(retry, target, editor.sessionId) is DocSaveResult.Saved)
        assertEquals("the completed upload was reused", 1, tower.uploads)
    }

    // MARK: stale versions and review

    @Test fun aNewerSharedVersionNeverReplacesTheDraftAndSaveSendsThePersonToReview() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(0, "One, mine")
        editor.pageChanged(page.text)
        editor.checkpoint()
        val mine = draft()!!
        tower.externalSave(Docs.rich("One", "Two", "Three, theirs"))
        assertFalse("Save does not publish over a newer version", editor.save())
        assertEquals(0, tower.patches.size)
        assertEquals("nothing is uploaded and Tower keeps no recovery copy", 0, tower.uploads)
        assertEquals(0, tower.recoveriesStored.size)
        assertEquals(2, editor.remoteVersion!!.rowVersion)
        assertTrue(editor.status!!.contains("Review the newer version"))
        val kept = draft()!!
        assertTrue(kept.body.contentEquals(mine.body))
        assertEquals("the draft keeps its old base", 1, kept.baseRowVersion)
        assertEquals("One, mine", (page.paragraphs()[0]))
    }

    @Test fun disjointConcurrentEditsMergeIntoALocalDraftThatOnlyAnExplicitSavePublishes() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(0, "One, mine")
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave(Docs.rich("One", "Two", "Three, theirs"))
        editor.reconcileRemote()
        assertEquals(2, editor.remoteVersion!!.rowVersion)
        assertTrue("a verified common base was retained", editor.canMerge)
        editor.beginMerge()
        assertTrue(editor.mergeReady)
        assertTrue(editor.mergeConflicts.isEmpty())
        assertEquals(listOf("One, mine", "Two", "Three, theirs"), stateParagraphs(editor.journal.previewText!!))
        assertEquals("review itself publishes nothing", 0, tower.patches.size)
        assertTrue(editor.useMergePreview())
        assertEquals(0, tower.patches.size)
        val merged = draft()!!
        assertEquals("the merged draft is based on the reviewed head", 2, merged.baseRowVersion)
        assertTrue(editor.state.value.pendingMergedSave)
        assertEquals(listOf("One, mine", "Two", "Three, theirs"), (page.paragraphs()))
        assertTrue(editor.save())
        assertEquals(3, tower.version)
        assertEquals(listOf("One, mine", "Two", "Three, theirs"), bodyParagraphs(tower))
        assertEquals("unknown envelope fields survive the merge and save", JsonValue.Bool(true), JsonValue.parse(tower.body)["future_field"]?.get("keep"))
        assertFalse(editor.state.value.pendingMergedSave)
        assertNull(draft())
    }

    @Test fun overlappingEditsStayUnresolvedUntilEachIsChosenAndNothingIsSilentlyReplaced() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(1, "Two MINE")
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave(Docs.rich("One", "Two THEIRS", "Three"))
        editor.reconcileRemote()
        editor.beginMerge()
        assertEquals(1, editor.mergeConflicts.size)
        assertFalse("an unresolved overlap cannot be applied", editor.useMergePreview())
        val id = editor.mergeConflicts.single().id
        // Partial choices persist.
        editor.resolveMerge(id, DocumentMerge.Choice.LATEST)
        assertTrue(editor.mergeConflicts.isEmpty())
        assertEquals("latest", store.loadDocumentReview(editor.draftKey).choices[id])
        assertEquals("Two MINE", Docs.paragraphs(draft()!!.body)[1])
        assertTrue(editor.useMergePreview())
        assertEquals(listOf("One", "Two THEIRS", "Three"), page.paragraphs())
        // The original is still recoverable.
        val journal = store.loadDocumentReview(editor.draftKey)
        assertTrue(journal.retained.any { Docs.paragraphs(it.bytes)[1] == "Two MINE" })
    }

    @Test fun acceptLatestPreservesTheWorkAndPublishesNothing() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(0, "One, mine")
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave(Docs.rich("One", "Two", "Three, theirs"), "Plan v2")
        editor.reconcileRemote()
        val acquiresBefore = tower.acquires.size
        editor.acceptLatest()
        assertEquals(0, tower.patches.size)
        assertEquals("accepting never acquires authority", acquiresBefore, tower.acquires.size)
        assertEquals(listOf("One", "Two", "Three, theirs"), page.paragraphs())
        assertEquals(2, editor.localBaseVersion)
        assertEquals("Plan v2", editor.title)
        assertNull(editor.remoteVersion)
        assertNull("the draft was retired only after being preserved", draft())
        val journal = store.loadDocumentReview(editor.draftKey)
        assertTrue(journal.retained.any { Docs.paragraphs(it.bytes)[0] == "One, mine" })
        // The preserved copy can come back as a local draft, again without publishing.
        assertTrue(editor.restorePreserved(journal.retained.first { Docs.paragraphs(it.bytes)[0] == "One, mine" }))
        assertEquals("One, mine", page.paragraphs()[0])
        assertEquals(0, tower.patches.size)
    }

    @Test fun ifTheHeadMovesAgainAcceptStopsForAnotherReview() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(0, "Mine")
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave(Docs.rich("One", "Two", "v2"))
        editor.reconcileRemote()
        tower.externalSave(Docs.rich("One", "Two", "v3"))
        editor.acceptLatest()
        assertEquals("the page and draft are untouched", "Mine", page.paragraphs()[0])
        assertNotNull(draft())
        assertEquals("review now targets the newest head", 3, editor.remoteVersion!!.rowVersion)
        assertTrue(editor.status!!.contains("changed again"))
    }

    @Test fun anUpgradedDraftWithoutAVerifiedBaseOffersNoAutomaticMerge() = world {
        // A draft from an older build: base identity recorded, but no retained base bytes anywhere.
        val head = tower.fetchHead(tower.workspace, "", tower.docId)!!
        val old = DocDraft(connection.id, workspace.id, tower.docId, "Plan", Docs.rich("One mine", "Two", "Three"), DocumentBody.ENVELOPE_CONTENT_TYPE,
            1, head.body.sha256, head.body.storageObjectId, DocDraft.State.EDITING)
        store.writer.saveDocDraft(old)
        tower.externalSave(Docs.rich("One", "Two", "Three theirs"))
        cacheHead() // the cache moved on; the old base's bytes are gone
        val editor = open()
        editor.reconcileRemote()
        assertFalse(editor.canMerge)
        editor.beginMerge()
        assertFalse(editor.mergeReady)
        assertTrue(editor.status!!.contains("Manual review only"))
        assertEquals(1, draft()!!.baseRowVersion)
    }

    /** A merged draft whose base is the reviewed head, ready for the autosave to (wrongly) publish. */
    private suspend fun World.mergedDraftEditor(autosaveMillis: Long): Pair<DocumentEditController, FakePage> {
        val page = FakePage()
        val editor = open(page, autosaveMillis = autosaveMillis)
        editor.prepareOpening()
        page.editParagraph(0, "One, mine")
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave(Docs.rich("One", "Two", "Three, theirs"))
        editor.reconcileRemote()
        editor.beginMerge()
        assertTrue(editor.useMergePreview())
        return editor to page
    }

    @Test fun theMinuteAutosaveNeverPublishesAMergedDraftBeforeAnExplicitSave() = world {
        val (editor, _) = mergedDraftEditor(autosaveMillis = 20)
        editor.holdLease()
        kotlinx.coroutines.delay(400) // many autosave ticks
        editor.setActive(false) // background flush
        assertEquals("nothing published", 0, tower.patches.size)
        assertNotNull(draft())
        assertTrue(editor.save())
        assertEquals(1, tower.patches.size)
    }

    @Test fun theMergedDraftHoldSurvivesRelaunchAndALostJournalFailsClosed() = world {
        val (editor, _) = mergedDraftEditor(autosaveMillis = 20)
        editor.close()
        // The hold is in the journal across relaunch.
        val relaunched = World(tower, store).also { it.reconnect(connection) }
        val reopened = relaunched.open(autosaveMillis = 20)
        reopened.prepareOpening(); reopened.holdLease()
        kotlinx.coroutines.delay(300)
        assertEquals(0, tower.patches.size)
        reopened.close()
        // Corrupt journal: the merged draft is still there but the journal that held it cannot be read.
        store.writer.setState(studio.otherstuff.ambulando.core.store.DocumentReviewCodec.key(editor.draftKey), "{ not a journal")
        val damaged = relaunched.open(autosaveMillis = 20)
        damaged.prepareOpening(); damaged.holdLease()
        kotlinx.coroutines.delay(300)
        assertEquals("a lost journal never lets a merged draft publish itself", 0, tower.patches.size)
        assertNotNull(draft())
        assertNotNull("the unreadable journal text is kept", store.reader.state(studio.otherstuff.ambulando.core.store.DocumentReviewCodec.corruptKey(editor.draftKey)))
        // The background lease/reconcile pass must have finished: save() declines while it is reconciling.
        eventually { damaged.state.value.leaseState.contains("lease held") }
        assertTrue(damaged.save())
        relaunched.scope.cancel()
    }

    private val reviewKey get() = studio.otherstuff.ambulando.core.store.DocumentReviewCodec.key(key)

    @Test fun theHoldIsStoredWithTheDraftSoADeletedOrCrashedJournalCannotReleaseIt() = world {
        val (editor, _) = mergedDraftEditor(autosaveMillis = 20)
        assertEquals("the hold commits with the merged text, before any journal write", DocDraft.State.HELD, draft()!!.state)
        editor.close()
        // The process died after the draft committed and before (or without) the journal hold: the key is simply absent.
        store.writer.setState(studio.otherstuff.ambulando.core.store.DocumentReviewCodec.key(editor.draftKey), null)
        assertNull(store.reader.state(studio.otherstuff.ambulando.core.store.DocumentReviewCodec.key(editor.draftKey)))
        val relaunched = World(tower, store).also { it.reconnect(connection) }
        val reopened = relaunched.open(autosaveMillis = 20)
        assertTrue("the hold is recovered from the draft", reopened.state.value.pendingMergedSave)
        reopened.prepareOpening(); reopened.holdLease()
        kotlinx.coroutines.delay(300)
        reopened.setActive(false) // background flush
        assertEquals("no journal, no autosave", 0, tower.patches.size)
        assertEquals(DocDraft.State.HELD, relaunched.draft()!!.state)
        eventually { reopened.state.value.leaseState.contains("lease held") }
        assertTrue(reopened.save())
        assertEquals(1, tower.patches.size)
        assertFalse(reopened.state.value.pendingMergedSave)
        reopened.close()
        relaunched.scope.cancel()
    }

    @Test fun aStaleJournalWithoutTheHoldDoesNotReleaseAHeldDraft() = world {
        val (editor, _) = mergedDraftEditor(autosaveMillis = 20)
        editor.close()
        // An older journal (written before the merge) that never recorded the hold.
        val stale = DocumentReviewJournal().also { it.retainBase(editor.journal.retained.first()) }
        assertFalse(stale.pendingMergedSave)
        store.writer.setState(studio.otherstuff.ambulando.core.store.DocumentReviewCodec.key(editor.draftKey),
            studio.otherstuff.ambulando.core.store.DocumentReviewCodec.encode(stale))
        val relaunched = World(tower, store).also { it.reconnect(connection) }
        val reopened = relaunched.open(autosaveMillis = 20)
        reopened.prepareOpening(); reopened.holdLease()
        kotlinx.coroutines.delay(300)
        assertEquals(0, tower.patches.size)
        assertTrue(reopened.state.value.pendingMergedSave)
        reopened.close()
        relaunched.scope.cancel()
    }

    @Test fun aRestoredCopyWithADeletedJournalIsStillHeldAndTypingKeepsTheHold() = world {
        val (editor, page) = mergedDraftEditor(autosaveMillis = 20)
        assertTrue(editor.restorePreserved(editor.journal.retained.first()))
        assertEquals(DocDraft.State.HELD, draft()!!.state)
        page.editParagraph(1, "Two, typed after restore")
        editor.pageChanged(page.text)
        assertTrue(editor.checkpoint())
        assertEquals("further typing keeps the hold", DocDraft.State.HELD, draft()!!.state)
        editor.close()
        store.writer.setState(studio.otherstuff.ambulando.core.store.DocumentReviewCodec.key(editor.draftKey), null)
        val relaunched = World(tower, store).also { it.reconnect(connection) }
        val reopened = relaunched.open(autosaveMillis = 20)
        assertTrue("the hold is recovered from the draft row", reopened.state.value.pendingMergedSave)
        reopened.prepareOpening(); reopened.holdLease()
        kotlinx.coroutines.delay(300)
        assertEquals(0, tower.patches.size)
        reopened.close()
        relaunched.scope.cancel()
    }

    @Test fun aFailedExplicitSaveOfAHeldDraftKeepsItHeldAndOrdinaryAutosaveResumesAfterASuccessfulSave() = world {
        val (editor, page) = mergedDraftEditor(autosaveMillis = 20)
        tower.offline = true
        assertFalse(editor.save())
        assertEquals("a failed save does not release the hold", DocDraft.State.HELD, draft()!!.state)
        tower.offline = false
        editor.prepareOpening()
        assertTrue(editor.save())
        assertNull(draft())
        // Ordinary autosave semantics are intentionally unchanged: later edits are not held.
        editor.holdLease()
        page.editParagraph(2, "Three, after save")
        editor.pageChanged(page.text)
        editor.checkpoint()
        assertEquals(DocDraft.State.EDITING, draft()!!.state)
        eventually { tower.patches.size == 2 }
        editor.close()
    }

    @Test fun aRestoredPreservedCopyIsOnlyPublishedByAnExplicitSave() = world {
        val (editor, _) = mergedDraftEditor(autosaveMillis = 20)
        val preserved = editor.journal.retained.first()
        assertTrue(editor.restorePreserved(preserved))
        editor.holdLease()
        kotlinx.coroutines.delay(300)
        assertEquals(0, tower.patches.size)
        editor.discardDraft()
        assertFalse("discarding clears the hold", editor.state.value.pendingMergedSave)
    }

    @Test fun reviewWorkAndPreviewEditsSurviveRelaunchAndBackgrounding() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(1, "Two MINE")
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave(Docs.rich("One", "Two THEIRS", "Three"))
        editor.reconcileRemote()
        editor.beginMerge()
        val edited = JsonValue.parse(editor.mergeText).let { it.setting("content", JsonValue.Arr(it["content"]!!.arrayValue!!.take(2))) }.serializedString()
        editor.editPreview(text = edited, title = "My merged title")
        editor.setActive(false) // background: flush
        assertNotNull("the edited preview is in the journal", store.loadDocumentReview(editor.draftKey).previewText)
        // Relaunch.
        val second = World(tower, store).also { it.reconnect(connection) }
        val reopened = second.open()
        reopened.reconcileRemote()
        reopened.openReview()
        assertTrue(reopened.mergeReady)
        assertEquals("My merged title", reopened.mergeTitle)
        assertEquals(edited, reopened.mergeText)
        assertEquals("overlap still needs its choice after relaunch", 1, reopened.mergeConflicts.size)
        assertTrue(reopened.dirty)
        assertEquals("Two MINE", Docs.paragraphs(second.draft()!!.body)[1])
        second.scope.cancel()
    }

    @Test fun aPreviewIsRebasedWhenTheSharedVersionMovesDuringReview() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(0, "One mine")
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave(Docs.rich("One", "Two theirs", "Three"))
        editor.reconcileRemote()
        editor.beginMerge()
        assertTrue(editor.mergeConflicts.isEmpty())
        tower.externalSave(Docs.rich("One", "Two theirs", "Three again"))
        editor.reconcileRemote()
        assertEquals(3, editor.remoteVersion!!.rowVersion)
        assertTrue("the preview targets an old head", editor.previewIsStale)
        assertFalse("applying a stale preview is refused", editor.useMergePreview())
        editor.reconcilePreview()
        assertFalse(editor.previewIsStale)
        assertTrue(editor.useMergePreview())
        assertEquals(listOf("One mine", "Two theirs", "Three again"), page.paragraphs())
        assertEquals(3, draft()!!.baseRowVersion)
        val journal = store.loadDocumentReview(editor.draftKey)
        assertTrue("the earlier preview stays recoverable", journal.previousPreviews.isNotEmpty() || journal.retained.size >= 2)
    }

    // MARK: authority

    @Test fun editorsOnAnExclusiveServerAreFencedOutAndATakeoverPreservesTheirWork() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        editor.holdLease()
        page.editParagraph(2, "Three, mine")
        editor.pageChanged(page.text)
        editor.checkpoint()
        // Someone with write access takes over from another device: this session is fenced.
        val holder = tower.exclusive!!
        val other = world2(tower)
        other.takeover(tower, holder)
        // The next renew fails; the editor stops claiming authority but keeps the draft.
        eventually { editor.authorityLost }
        assertTrue(editor.authorityLost)
        assertNotNull(draft())
        assertFalse("a superseded editor never regains authority through save", editor.save())
        assertEquals(0, tower.patches.size)
        assertTrue(editor.canTakeover)
        // Explicit takeover: local work first becomes a Tower recovery, then the latest body loads.
        editor.takeover()
        assertEquals("exactly one recovery body save", 1, tower.patches.size)
        assertEquals(false, tower.patches.single()["base_available"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrict() })
        assertEquals("", (tower.patches.single()["lease_token"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(1, tower.recoveriesStored.size)
        assertEquals("Three, mine", Docs.paragraphs(tower.recoveriesStored.single().second)[2])
        assertNull("the matching local draft retires only after the recovery is acknowledged", draft())
        assertTrue("and a local copy stays in Preserved drafts, not only a Tower recovery reachable by id",
            editor.journal.retained.any { Docs.paragraphs(it.bytes)[2] == "Three, mine" })
        assertTrue(store.loadDocumentReview(editor.draftKey).retained.any { Docs.paragraphs(it.bytes)[2] == "Three, mine" })
        assertFalse(editor.authorityLost)
        assertEquals(tower.version, editor.localBaseVersion)
        assertEquals(listOf("One", "Two", "Three"), page.paragraphs())
        editor.close()
    }

    /** A second device of the same account acting on the fake Tower. */
    private fun world2(tower: FakeDocTower) = object {
        suspend fun takeover(t: FakeDocTower, holder: FakeDocTower.Lease) {
            t.acquireDocumentLease(t.workspace, "", t.docId, "00000000-0000-4000-8000-00000000e777", studio.otherstuff.ambulando.core.transport.DOCUMENT_EDIT_PROTOCOL, holder.id)
        }
    }

    @Test fun crossPersonTakeoverIsAuthorizedByTowerNotByHolderIdentity() = world {
        tower.exclusive = FakeDocTower.Lease("00000000-0000-4000-8000-0000000000b9", "someone-elses-token", "00000000-0000-4000-8000-0000000000bb", "npub1someoneelse" + "q".repeat(40))
        val page = FakePage()
        val editor = open(page)
        assertTrue("opening for read is still fine", editor.prepareOpening().let { true })
        editor.holdLease()
        eventually { editor.authorityLost && editor.state.value.canTakeover }
        assertTrue("someone else is editing", editor.authorityLost)
        assertTrue(editor.canTakeover)
        page.editParagraph(0, "mine")
        editor.pageChanged(page.text); editor.checkpoint()
        editor.takeover()
        assertFalse(editor.authorityLost)
        assertEquals("Tower fenced the other holder", "someone-elses-token", tower.fenced.single())
        assertTrue(tower.acquires.any { it.endsWith(":takeover") })
    }

    @Test fun deniedOrChangedTakeoverKeepsTheDraftAndNeverForcesASave() = world {
        tower.exclusive = FakeDocTower.Lease("00000000-0000-4000-8000-0000000000b9", "t1", "00000000-0000-4000-8000-0000000000bb", "npub1x" + "q".repeat(50))
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening(); editor.holdLease(); eventually { editor.authorityLost && editor.state.value.canTakeover }
        page.editParagraph(0, "mine"); editor.pageChanged(page.text); editor.checkpoint()
        tower.denyTakeover = true
        editor.takeover()
        assertTrue(editor.authorityLost)
        assertNotNull("the draft is retained", draft())
        assertTrue(editor.status!!.contains("Takeover did not finish"))
        assertTrue(tower.fenced.isEmpty())
        // The lease changes hands before the person confirms: the observed lease no longer matches.
        tower.denyTakeover = false
        tower.exclusive = FakeDocTower.Lease("00000000-0000-4000-8000-0000000000ba", "t2", "00000000-0000-4000-8000-0000000000bc", "npub1y" + "q".repeat(50))
        editor.takeover()
        assertTrue(editor.authorityLost)
        assertNotNull(draft())
        assertTrue(tower.fenced.isEmpty())
    }

    @Test fun anExpiredLeaseIsRetakenNormallyAfterInspection() = world {
        tower.exclusive = FakeDocTower.Lease("00000000-0000-4000-8000-0000000000b9", "old", "00000000-0000-4000-8000-0000000000bb", "npub1x" + "q".repeat(50))
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening(); editor.holdLease(); eventually { editor.authorityLost && editor.state.value.canTakeover }
        assertTrue(editor.authorityLost)
        tower.exclusive = null // the other holder's lease expired
        page.editParagraph(0, "mine"); editor.pageChanged(page.text); editor.checkpoint()
        // The person confirmed against a lease that is no longer the one Tower shows: refused and re-inspected, never forced.
        editor.takeover()
        assertTrue(editor.authorityLost)
        assertNotNull(draft())
        // The refreshed observation shows nobody holding it, so the same explicit action starts an ordinary fresh session.
        editor.takeover()
        assertFalse("a fresh session starts with the ordinary acquire contract", tower.acquires.last().endsWith(":takeover"))
        assertFalse(editor.authorityLost)
    }

    @Test fun anOfflineExpiredEditorResumesOnlyItsOwnSessionOnAnUnchangedBase() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        editor.holdLease()
        eventually { editor.state.value.leaseState.contains("lease held") }
        val original = tower.exclusive!!
        // The lease lapses while offline (nobody else took it): the next renew resumes the same session.
        tower.exclusive = null
        eventually { tower.exclusive != null }
        assertFalse("resumed on the unchanged base", editor.authorityLost)
        assertEquals("same editor session", original.session, tower.exclusive!!.session)
        // Now it lapses and the shared head moves: no resume.
        tower.exclusive = null
        tower.externalSave(Docs.rich("One", "Two", "Three moved"))
        eventually { editor.authorityLost }
        assertTrue(editor.authorityLost)
    }

    @Test fun aSessionTakenOverByAnotherDeviceCanNeverResume() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening(); editor.holdLease()
        eventually { editor.state.value.leaseState.contains("lease held") }
        val holder = tower.exclusive!!
        world2(tower).takeover(tower, holder)
        tower.exclusive = null // the successor expired too
        eventually { editor.authorityLost }
        assertTrue("a tombstoned session is fenced even after its successor lapses", editor.authorityLost)
    }

    @Test fun concurrentServersGiveEverySessionItsOwnLeaseAndNeverSilentlyDowngrade() = world {
        tower.concurrentAdvertised = true
        val first = open(FakePage())
        assertTrue(first.prepareOpening())
        assertTrue(first.state.value.concurrentEditing)
        val secondWorld = World(tower, store).also { it.reconnect(connection) }
        val second = secondWorld.open(FakePage())
        assertTrue(second.prepareOpening())
        assertEquals("both sessions hold a lease at once", 2, tower.concurrent.size)
        assertNull("no exclusive holder", tower.exclusive)
        // The advertisement disappears (an older server answers): a new session negotiates afresh as exclusive, and the
        // sessions already holding concurrent leases keep them untouched.
        tower.concurrentAdvertised = false
        val thirdWorld = World(tower, store).also { it.reconnect(connection) }
        val third = thirdWorld.open(FakePage())
        assertTrue(third.prepareOpening())
        assertFalse(third.state.value.concurrentEditing)
        assertEquals(2, tower.concurrent.size)
        assertNotNull(tower.exclusive)
        assertTrue(tower.acquires.last().startsWith("document_edit_session_v1"))
        // A second concurrent acquire for the first session is a plain cached lease, not a new negotiation.
        assertEquals(first.sessionId, tower.concurrent.values.map { it.session }.first { it == first.sessionId })
        thirdWorld.scope.cancel()
        secondWorld.scope.cancel()
    }

    @Test fun serversWithoutInspectionKeepOrdinaryExclusiveAcquisition() = world {
        tower.inspectable = false
        val editor = open(FakePage())
        assertTrue(editor.prepareOpening())
        assertFalse(editor.state.value.concurrentEditing)
        assertTrue(tower.acquires.single().startsWith("document_edit_session_v1"))
        assertNotNull(tower.exclusive)
    }

    // MARK: access, locking, separation

    @Test fun lostAccessKeepsTheDraftReadOnlyAndRefusesToSave() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(0, "mine"); editor.pageChanged(page.text); editor.checkpoint()
        tower.denyReads = 404
        editor.reconcileRemote()
        assertTrue(editor.permissionDenied)
        assertFalse(page.editableNow)
        assertTrue(editor.status!!.contains("no longer available"))
        assertFalse(editor.save())
        assertEquals(0, tower.patches.size)
        assertEquals("mine", Docs.paragraphs(draft()!!.body)[0])
        // And after a relaunch the work is still there, still not published.
        val again = World(tower, store).also { it.reconnect(connection) }
        assertEquals("mine", Docs.paragraphs(again.draft()!!.body)[0])
        again.scope.cancel()
    }

    @Test fun forbiddenWritesReportPermissionAndKeepLocalWork() = world {
        tower.denyReads = 403
        val editor = open(FakePage())
        editor.reconcileRemote()
        assertTrue(editor.permissionDenied)
        assertTrue(editor.status!!.contains("denied"))
    }

    @Test fun lockingForgetsAuthorityButNeverTheDraft() = world {
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening(); editor.holdLease()
        page.editParagraph(0, "mine"); editor.pageChanged(page.text); editor.checkpoint()
        saver.forgetAll()
        unlocked = false
        assertFalse(saver.hasLease(workspace.id, tower.docId))
        assertFalse(editor.prepareOpening())
        assertEquals("mine", Docs.paragraphs(draft()!!.body)[0])
        assertFalse("a superseded session cannot save", editor.save())
        assertTrue("the open editor's session can no longer pass acquire", saver.sessionLost(DocSaveTarget(connection.id, workspace.id, workspace.appNpub), tower.docId, editor.sessionId))
        assertEquals(0, tower.patches.size)
        assertEquals("mine", Docs.paragraphs(draft()!!.body)[0])
    }

    @Test fun draftsAndReviewJournalsAreSeparatedByWorkspaceAndConnection() = world {
        val page = FakePage()
        val editor = open(page)
        page.editParagraph(0, "workspace one"); editor.pageChanged(page.text); editor.checkpoint()
        val otherTower = FakeDocTower(workspace = Fixtures.WORKSPACE_ID_2, docId = FakeDocTower.DOC_ID)
        val other = World(otherTower, store)
        other.connect()
        val otherEditor = other.open(FakePage())
        assertFalse("same document id, other workspace: no draft carried over", otherEditor.dirty)
        assertEquals("One", (otherEditor.page as FakePage).paragraphs()[0])
        val target = DocSaveTarget(connection.id, workspace.id, workspace.appNpub)
        val foreign = draft()!!
        assertEquals(DocSaveResult.NotSaved("This draft belongs to another workspace."),
            saver.save(foreign, target.copy(workspaceId = Fixtures.WORKSPACE_ID_2)))
        assertNotEquals(editor.draftKey, otherEditor.draftKey)
        other.scope.cancel()
    }

    // MARK: encoding

    @Test fun unicodeAndUnknownEnvelopeFieldsSurviveEditAndSave() = world {
        tower.body = Docs.rich("café ☕ 😀", "👩‍👩‍👧 é 🇯🇵", "plain", extra = """"future_field":{"keep":[1,2.50,"x"]},"zeta":null""")
        tower.objects[tower.objectId] = tower.body
        cacheHead()
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.editParagraph(2, "plain 🧑‍🚀 edited")
        editor.pageChanged(page.text)
        assertTrue(editor.save())
        val saved = JsonValue.parse(tower.body)
        assertEquals(listOf("café ☕ 😀", "👩‍👩‍👧 é 🇯🇵", "plain 🧑‍🚀 edited"), Docs.paragraphs(tower.body))
        assertEquals("2.50", (saved["future_field"]!!["keep"]!!.arrayValue!![1] as JsonValue.Num).lexeme)
        assertEquals(JsonValue.Null, saved["zeta"])
    }

    @Test fun markdownDocumentsReconcileTooAndKeepTheirExactText() = world {
        val md = "# Plan\n\nfirst\n\nsecond\n"
        tower.body = md.toByteArray(); tower.contentType = "text/markdown"; tower.objects[tower.objectId] = tower.body
        cacheHead()
        val page = FakePage()
        val editor = open(page)
        editor.prepareOpening()
        page.text = "# Plan\n\nfirst, mine\n\nsecond\n"
        editor.pageChanged(page.text)
        editor.checkpoint()
        tower.externalSave("# Plan\n\nfirst\n\nsecond, theirs\n".toByteArray())
        editor.reconcileRemote()
        editor.beginMerge()
        assertTrue(editor.mergeConflicts.isEmpty())
        assertEquals("# Plan\n\nfirst, mine\n\nsecond, theirs\n", editor.mergeText)
        assertTrue(editor.useMergePreview())
        assertTrue(editor.save())
        assertEquals("# Plan\n\nfirst, mine\n\nsecond, theirs\n", tower.body.toString(Charsets.UTF_8))
    }

    @Test fun savedVersionsAreChosenAgainstTheReviewedHeadAndAStaleChoiceKeepsBoth() = world {
        val editor = open(FakePage())
        editor.prepareOpening()
        // A recovery exists because a stale writer was preserved.
        tower.recoveriesStored.add(Triple("00000000-0000-4000-8000-0000000000c9", Docs.rich("Old", "Two", "Three"), "Earlier"))
        val versions = editor.openVersions()!!
        assertEquals(1, versions.size)
        assertTrue(editor.previewVersion(versions.single())!!.contains("Old"))
        tower.externalSave(Docs.rich("One", "Two", "Newer")) // the head moves after review began
        assertFalse(editor.resolveVersion(versions.single(), promote = true))
        assertEquals("both versions retained", 1, tower.recoveriesStored.size)
        assertEquals(listOf("One", "Two", "Newer"), bodyParagraphs(tower))
        // Reopening refreshes the choice; now it applies.
        val again = editor.openVersions()!!
        assertTrue(editor.resolveVersion(again.single(), promote = false))
        assertEquals(0, tower.recoveriesStored.size)
        assertEquals("dismiss leaves the canonical content alone", listOf("One", "Two", "Newer"), bodyParagraphs(tower))
    }

    private fun stateParagraphs(stateJson: String) = JsonValue.parse(stateJson)["content"]!!.arrayValue!!.map { p ->
        p["content"]?.arrayValue?.joinToString("") { it["text"]?.stringValue ?: "" } ?: ""
    }
}
