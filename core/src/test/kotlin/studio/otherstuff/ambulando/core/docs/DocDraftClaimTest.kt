package studio.otherstuff.ambulando.core.docs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.DocumentReviewCodec
import studio.otherstuff.ambulando.core.store.StoreFixtures
import studio.otherstuff.ambulando.core.store.deleteDocDraftIfUnchanged
import studio.otherstuff.ambulando.core.store.docDraft
import studio.otherstuff.ambulando.core.store.finishDocDraftSave
import studio.otherstuff.ambulando.core.store.insertDocDraftIfAbsent
import studio.otherstuff.ambulando.core.store.loadDocumentReview
import studio.otherstuff.ambulando.core.store.replaceDocDraftIfUnchanged
import studio.otherstuff.ambulando.core.store.saveDocDraft
import studio.otherstuff.ambulando.core.store.saveDocumentReview
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.store.docDraftStore
import studio.otherstuff.ambulando.core.support.Fixtures

/** Durable drafts are claimed, replaced and retired only while they are exactly what the caller read. */
class DocDraftClaimTest {
    private val doc = "00000000-0000-4000-8000-0000000000a3"

    private fun draft(connection: String, text: String, version: Int = 1, state: DocDraft.State = DocDraft.State.EDITING) =
        DocDraft(connection, Fixtures.WORKSPACE_ID, doc, "Plan", text.toByteArray(), "text/markdown", version, "aa", "obj1", state)

    @Test fun replaceInsertAndDeleteAreCompareAndSet() = runBlocking {
        StoreFixtures.store().use { store ->
            val c = StoreFixtures.connect(store).id
            val first = draft(c, "one")
            assertTrue(store.writer.insertDocDraftIfAbsent(first))
            assertFalse("a second first-draft never overwrites", store.writer.insertDocDraftIfAbsent(draft(c, "other")))
            assertEquals("one", String(store.reader.docDraft(first.key)!!.body))
            // Someone typed more while a caller was waiting on the network.
            store.writer.saveDocDraft(draft(c, "one and more"))
            assertFalse("the older snapshot cannot replace newer typing", store.writer.replaceDocDraftIfUnchanged(first, draft(c, "stale replacement")))
            assertEquals("one and more", String(store.reader.docDraft(first.key)!!.body))
            val current = store.reader.docDraft(first.key)!!
            assertTrue(store.writer.replaceDocDraftIfUnchanged(current, current.with(DocDraft.State.CONFLICT, error = "Newer version available")))
            assertEquals(DocDraft.State.CONFLICT, store.reader.docDraft(first.key)!!.state)
            assertFalse("a delete of an older snapshot is refused", store.writer.deleteDocDraftIfUnchanged(current))
            assertTrue(store.writer.deleteDocDraftIfUnchanged(store.reader.docDraft(first.key)!!))
            assertNull(store.reader.docDraft(first.key))
        }
    }

    @Test fun finishingASaveRetiresOnlyTheSubmittedSnapshotAndAdvancesLaterTypingToTheAcceptedBase() = runBlocking {
        StoreFixtures.store().use { store ->
            val c = StoreFixtures.connect(store).id
            val submitted = draft(c, "submitted", state = DocDraft.State.SAVING)
            store.writer.saveDocDraft(submitted)
            store.writer.finishDocDraftSave(submitted, 2, "bb", "obj2")
            assertNull("the submitted snapshot is retired", store.reader.docDraft(submitted.key))
            // Typing persisted during the request keeps its text and moves to the base the save produced.
            store.writer.saveDocDraft(draft(c, "submitted"))
            val typing = draft(c, "submitted plus typing")
            store.writer.saveDocDraft(typing)
            store.writer.finishDocDraftSave(draft(c, "submitted"), 2, "bb", "obj2")
            val kept = store.reader.docDraft(typing.key)!!
            assertEquals("submitted plus typing", String(kept.body))
            assertEquals(2, kept.baseRowVersion)
            assertEquals("bb", kept.baseSha256)
            assertEquals("obj2", kept.baseStorageObjectId)
        }
    }

    @Test fun aSaveWhoseDraftChangedMeanwhileDoesNotOverwriteTheNewerText() = runBlocking {
        val tower = FakeDocTower()
        val world = World(tower)
        try {
            world.connect()
            val key = DocDraft.key(world.connection.id, world.workspace.id, tower.docId)
            val mine = DocDraft(world.connection.id, world.workspace.id, tower.docId, "Plan", Docs.rich("One mine", "Two", "Three"), DocumentBody.ENVELOPE_CONTENT_TYPE,
                1, tower.sha, tower.objectId, DocDraft.State.EDITING)
            world.store.writer.saveDocDraft(mine)
            val typing = DocDraft(mine.connectionId, mine.workspaceId, mine.docId, "Plan", Docs.rich("One mine, then more typing", "Two", "Three"), mine.contentType,
                1, mine.baseSha256, mine.baseStorageObjectId, DocDraft.State.EDITING)
            // The person types while the upload is in flight.
            val typingApi = object : DocumentSaveApi by tower {
                override suspend fun storeObject(workspaceId: String, appNpub: String, bytes: ByteArray, contentType: String, fileName: String): String {
                    world.store.writer.saveDocDraft(typing)
                    return tower.storeObject(workspaceId, appNpub, bytes, contentType, fileName)
                }
            }
            val saver = DocumentSaver(world.store.writer.docDraftStore(world.store.reader), { typingApi })
            val result = saver.save(mine, DocSaveTarget(world.connection.id, world.workspace.id, world.workspace.appNpub))
            assertTrue("the submitted snapshot was saved: $result", result is DocSaveResult.Saved)
            val kept = world.store.reader.docDraft(key)!!
            assertEquals("newer typing survives the successful save", "One mine, then more typing", Docs.paragraphs(kept.body)[0])
            assertEquals("and is now based on the saved version", tower.version, kept.baseRowVersion)
            assertEquals(listOf("One mine", "Two", "Three"), Docs.paragraphs(tower.body))
        } finally { world.close() }
    }

    @Test fun aSecondSaveOfTheSameDraftIsRefusedWhileOneIsRunning() = runBlocking {
        val tower = FakeDocTower()
        val world = World(tower)
        try {
            world.connect()
            val mine = DocDraft(world.connection.id, world.workspace.id, tower.docId, "Plan", Docs.rich("A", "Two", "Three"), DocumentBody.ENVELOPE_CONTENT_TYPE,
                1, tower.sha, tower.objectId, DocDraft.State.EDITING)
            world.store.writer.saveDocDraft(mine)
            val target = DocSaveTarget(world.connection.id, world.workspace.id, world.workspace.appNpub)
            lateinit var saver: DocumentSaver
            var inner: DocSaveResult? = null
            val reentrant = object : DocumentSaveApi by tower {
                override suspend fun storeObject(workspaceId: String, appNpub: String, bytes: ByteArray, contentType: String, fileName: String): String {
                    inner = saver.save(world.store.reader.docDraft(mine.key)!!, target)
                    return tower.storeObject(workspaceId, appNpub, bytes, contentType, fileName)
                }
            }
            saver = DocumentSaver(world.store.writer.docDraftStore(world.store.reader), { reentrant })
            assertTrue(saver.save(mine, target) is DocSaveResult.Saved)
            assertEquals(DocSaveResult.NotSaved("A save is already in progress. Your latest edits stay on this device."), inner)
            assertEquals("only one version was made", 2, tower.version)
        } finally { world.close() }
    }

    @Test fun reviewJournalsPersistAndAnUnreadableOneIsKeptNotDiscarded() = runBlocking {
        StoreFixtures.store().use { store ->
            val key = "c:w:d"
            val journal = DocumentReviewJournal()
            journal.retainBase(DocumentMergeTest.Companion.base())
            journal.previewText = "typed preview"
            store.writer.saveDocumentReview(journal, key)
            val loaded = store.loadDocumentReview(key)
            assertEquals("typed preview", loaded.previewText)
            assertNotNull(loaded.base)
            // Damaged storage must not stop the editor from opening, nor lose the bytes.
            store.writer.setState(DocumentReviewCodec.key(key), "{not json")
            val empty = store.loadDocumentReview(key)
            assertTrue("nothing is invented from damaged storage", empty.base == null && empty.retained.isEmpty() && empty.previewText == null)
            assertTrue("but the lost journal may have held a merged-draft hold, so nothing autosaves until an explicit Save", empty.pendingMergedSave)
            assertEquals("{not json", store.reader.state(DocumentReviewCodec.corruptKey(key)))
            // Journals are scoped to their draft key (connection, workspace, document).
            store.writer.saveDocumentReview(journal, "c:other:d")
            assertEquals(null, store.loadDocumentReview("c:another:d").previewText)
            // An empty journal removes its row.
            store.writer.saveDocumentReview(DocumentReviewJournal(), "c:other:d")
            assertNull(store.reader.state(DocumentReviewCodec.key("c:other:d")))
            assertArrayEquals(DocumentMergeTest.Companion.base().bytes, loaded.base!!.bytes)
        }
    }
}
