package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.*
import java.nio.file.Files

class GoalAssociationStoreTests {
    private val workspace = StoreFixtures.W
    private val otherWorkspace = StoreFixtures.W2
    private val goal = "11111111-1111-4111-8111-111111111111"
    private val chat = "22222222-2222-4222-8222-222222222222"
    private val doc = "33333333-3333-4333-8333-333333333333"
    private val file = "44444444-4444-4444-8444-444444444444"
    private val manual = "55555555-5555-4555-8555-555555555555"
    private val at = "2026-11-27T00:00:00.000Z"
    private fun metadata() = GoalAssociation.metadata(goal, buildJsonObject { put("unrelated", buildJsonObject { put("retained", true) }) })

    private fun row(id: String, name: String, w: String = workspace, metadata: JsonObject = metadata(), version: Long = 1, extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("id", id); put("workspace_id", w); put("channel_id", Fixtures.CHANNEL_1); put("scope_id", Fixtures.SCOPE_A)
        put("title", name); put("display_name", name); put("created_at", at); put("updated_at", at); put("row_version", version)
        put("record_state", "active"); put("metadata", metadata)
        extra.forEach { (key, value) -> put(key, value) }
    }

    private suspend fun seed(store: LocalStore, w: String = workspace) {
        store.writer.upsertScopes(w, listOf(StoreFixtures.scope(Fixtures.SCOPE_A, "Scope", w)), complete = true)
        store.writer.upsertChannels(w, Fixtures.SCOPE_A, listOf(StoreFixtures.channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "Discussion", w)), complete = true)
        store.writer.upsertThreads(w, listOf(ThreadRecord.parse(row(chat, "Purposeful discussion", w))))
        store.writer.upsertDocs(w, listOf(DocRecord.parse(row(doc, "Original vision", w))))
        store.writer.upsertFiles(w, listOf(FileRecord.parse(row(file, "Original whiteboard", w))))
    }

    private suspend fun withStore(block: suspend (LocalStore) -> Unit) {
        val dir = Files.createTempDirectory("goal-associations-test").toFile()
        try { LocalStore(dir).use { block(it) } } finally { dir.deleteRecursively() }
    }

    @Test fun associationsReopenAsOriginalNativeRecordsAndDeduplicateManualReferences() = runBlocking {
        val dir = Files.createTempDirectory("goal-associations-reopen").toFile()
        try {
            LocalStore(dir).use { store ->
                seed(store)
                store.writer.upsertDocs(workspace, listOf(DocRecord.parse(row(manual, "Manual reference", metadata = JsonObject(emptyMap())))))
            }
            LocalStore(dir).use { store ->
                val linked = store.reader.associatedGoalLinks(workspace, goal, listOf(GoalLink(GoalLinkType.DOC, doc), GoalLink(GoalLinkType.DOC, manual)))
                assertEquals(setOf(chat, doc, file, manual), linked.map { it.link.recordId }.toSet())
                assertEquals(4, linked.size)
                assertTrue(linked.all { it.available })
                assertEquals("Original vision", linked.single { it.link.recordId == doc }.title)
                assertEquals("Original whiteboard", linked.single { it.link.recordId == file }.title)
                assertEquals(GoalLinkType.CHAT, linked.single { it.link.recordId == chat }.link.type)
                assertTrue(linked.all { it.channelId == Fixtures.CHANNEL_1 })
                assertEquals(metadata(), store.reader.doc(workspace, doc)!!.raw.obj("metadata"))
                assertEquals(1L, store.reader.file(workspace, file)!!.rowVersion)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun sameRecordIdsAndGoalIdsInAnotherWorkspaceDoNotCrossBoundaries() = runBlocking {
        withStore { store ->
            seed(store); seed(store, otherWorkspace)
            store.writer.upsertDocs(otherWorkspace, listOf(DocRecord.parse(row(doc, "Other workspace secret", otherWorkspace, version = 2))))
            val here = store.reader.associatedGoalLinks(workspace, goal, emptyList())
            assertEquals(3, here.size)
            assertEquals("Original vision", here.single { it.link.recordId == doc }.title)
            assertEquals("Other workspace secret", store.reader.associatedGoalLinks(otherWorkspace, goal, emptyList()).single { it.link.recordId == doc }.title)
            assertTrue(store.reader.associatedGoalLinks(workspace, manual, emptyList()).isEmpty())
        }
    }

    @Test fun revokedRowsDisappearAutomaticallyButAnExplicitReferenceRemainsUnavailableAndRemovable() = runBlocking {
        withStore { store ->
            seed(store)
            store.writer.markAccessRemoved(workspace, AccessTarget.Doc(doc), AccessTarget.File(file), AccessTarget.Thread(chat))
            assertTrue(store.reader.associatedGoalLinks(workspace, goal, emptyList()).isEmpty())
            val explicit = store.reader.associatedGoalLinks(workspace, goal, listOf(GoalLink(GoalLinkType.DOC, doc)))
            assertEquals(1, explicit.size)
            assertFalse(explicit.single().available)
            assertNull(explicit.single().title)
            assertEquals(doc, explicit.single().link.recordId)
            assertNotNull("access loss retains local source data without granting access", store.reader.doc(workspace, doc))
        }
    }

    @Test fun channelAndScopeAccessLossExcludeEveryAutomaticOutput() = runBlocking {
        withStore { store ->
            seed(store)
            store.writer.upsertChannels(workspace, Fixtures.SCOPE_A, emptyList(), complete = true)
            assertTrue(store.reader.associatedGoalLinks(workspace, goal, emptyList()).isEmpty())
        }
        withStore { store ->
            seed(store)
            store.writer.upsertScopes(workspace, emptyList(), complete = true)
            assertTrue(store.reader.associatedGoalLinks(workspace, goal, emptyList()).isEmpty())
        }
    }

    @Test fun deletionAndTombstonesDoNotResurrectOutputsOrExposeDeletedTitles() = runBlocking {
        withStore { store ->
            seed(store)
            store.writer.upsertThreads(workspace, listOf(ThreadRecord.parse(row(chat, "Deleted discussion", version = 2, extra = buildJsonObject { put("record_state", "deleted") }))))
            store.writer.upsertDocs(workspace, listOf(DocRecord.parse(row(doc, "Soft deleted doc", version = 2, extra = buildJsonObject { put("deleted_at", at) }))))
            store.writer.applyTombstones(workspace, setOf(Tombstone.File(file)))
            assertTrue(store.reader.associatedGoalLinks(workspace, goal, emptyList()).isEmpty())
            val missing = store.reader.associatedGoalLinks(workspace, goal, listOf(GoalLink(GoalLinkType.FILE, file))).single()
            assertFalse(missing.available); assertNull(missing.title)
            val deletedDoc = store.reader.associatedGoalLinks(workspace, goal, listOf(GoalLink(GoalLinkType.DOC, doc))).single()
            assertFalse("soft deletion must hide explicit references too", deletedDoc.available)
            assertNull(deletedDoc.title)
        }
    }

    @Test fun archivedRecordsAndArchivedParentsAreUnavailableForAutomaticDiscovery() = runBlocking {
        withStore { store ->
            seed(store)
            val archived = buildJsonObject { put("record_state", "archived") }
            store.writer.upsertThreads(workspace, listOf(ThreadRecord.parse(row(chat, "Archived discussion", version = 2, extra = archived))))
            store.writer.upsertDocs(workspace, listOf(DocRecord.parse(row(doc, "Archived document", version = 2, extra = archived))))
            store.writer.upsertFiles(workspace, listOf(FileRecord.parse(row(file, "Archived file", version = 2, extra = archived))))
            assertTrue(store.reader.associatedGoalLinks(workspace, goal, emptyList()).isEmpty())
        }
        withStore { store ->
            seed(store)
            val channel = StoreFixtures.channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "Archived channel")
            store.writer.upsertChannels(workspace, Fixtures.SCOPE_A, listOf(channel.copy(rowVersion = 2, raw = JsonObject(channel.raw + ("archived_at" to JsonPrimitive(at))))), complete = true)
            assertTrue("an archived parent is not an accessible destination", store.reader.associatedGoalLinks(workspace, goal, emptyList()).isEmpty())
        }
        withStore { store ->
            seed(store)
            val scope = StoreFixtures.scope(Fixtures.SCOPE_A, "Archived scope")
            store.writer.upsertScopes(workspace, listOf(scope.copy(rowVersion = 2, raw = JsonObject(scope.raw + ("archived_at" to JsonPrimitive(at))))), complete = true)
            assertTrue(store.reader.associatedGoalLinks(workspace, goal, emptyList()).isEmpty())
        }
    }

    @Test fun unsupportedOrRemovedAssociationDoesNotBecomeAnAutomaticLinkAndUnknownMetadataSurvives() = runBlocking {
        withStore { store ->
            seed(store)
            val unsupported = buildJsonObject {
                put("unrelated", "retained")
                put(GoalAssociation.KEY, buildJsonObject { put("version", 2); put("goal_file_id", goal) })
            }
            store.writer.upsertDocs(workspace, listOf(DocRecord.parse(row(doc, "Future association", metadata = unsupported, version = 2))))
            store.writer.upsertFiles(workspace, listOf(FileRecord.parse(row(file, "Reference removed", metadata = buildJsonObject { put("unrelated", "retained") }, version = 2))))
            assertEquals(listOf(chat), store.reader.associatedGoalLinks(workspace, goal, emptyList()).map { it.link.recordId })
            val explicit = store.reader.associatedGoalLinks(workspace, goal, listOf(GoalLink(GoalLinkType.DOC, doc), GoalLink(GoalLinkType.FILE, file)))
            assertEquals(3, explicit.size)
            assertTrue("a manual reference is independent of automatic provenance", explicit.all { it.available })
            assertEquals(unsupported, store.reader.doc(workspace, doc)!!.raw.obj("metadata"))
        }
    }
}
