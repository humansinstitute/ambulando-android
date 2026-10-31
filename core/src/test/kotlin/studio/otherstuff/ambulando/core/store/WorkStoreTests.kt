package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.context.WorkContext
import studio.otherstuff.ambulando.core.docs.DocDraft
import studio.otherstuff.ambulando.core.docs.SavedDocBody
import studio.otherstuff.ambulando.core.docs.SavedDocument
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.store.StoreFixtures.message
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.DocBodyRecord
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.ReactionRecord
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.ViewStateRecord
import studio.otherstuff.ambulando.core.transport.jsonObjectOf

class WorkStoreTests {
    private val at = "2026-10-01T00:00:00.000Z"
    private val D = "00000000-0000-4000-8000-0000000000d1"

    private fun task(id: String, title: String, channel: String = Fixtures.CHANNEL_1, workspace: String = W, rowVersion: Long = 1, state: String = "new") =
        TaskRecord(
            workspaceId = workspace, id = id, scopeId = Fixtures.SCOPE_A, channelId = channel, title = title, description = "About $title",
            state = state, priority = "sand", assignedToNpub = null, rowVersion = rowVersion, createdAt = at, updatedAt = at,
            raw = Fixtures.obj(mapOf("id" to id, "title" to title, "row_version" to rowVersion, "state" to state)),
        )

    private fun doc(id: String, title: String, rowVersion: Long = 1, storage: String = "obj1") = DocRecord(
        workspaceId = W, id = id, scopeId = Fixtures.SCOPE_A, channelId = Fixtures.CHANNEL_1, storageObjectId = storage, title = title, summary = "Summary",
        rowVersion = rowVersion, createdAt = at, updatedAt = at, raw = Fixtures.obj(mapOf("id" to id, "title" to title, "row_version" to rowVersion)),
    )

    @Test fun taskListsReconcilePerChannelAndStayInTheirWorkspace() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertTasks(W, listOf(task("t1", "One"), task("t2", "Two", Fixtures.CHANNEL_2), task("x", "Foreign", workspace = StoreFixtures.W2)))
            assertNull("a record of another workspace is ignored", store.reader.task(StoreFixtures.W2, "x"))
            store.writer.upsertTasks(W, listOf(task("t3", "Three")), completeChannel = Fixtures.CHANNEL_1)
            assertEquals(true, store.reader.task(W, "t1")?.accessRemoved)
            assertEquals("other channels are untouched", false, store.reader.task(W, "t2")?.accessRemoved)
            assertEquals(0, store.writer.upsertTasks(W, listOf(task("t3", "Stale", rowVersion = 0))))
            store.writer.upsertTasks(W, listOf(task("t4", "Done", state = "archived")))
            assertFalse(store.reader.tasks(W).any { it.id == "t4" })
            assertTrue(store.reader.tasks(W, includeArchived = true).any { it.id == "t4" })
        }
    }

    @Test fun commentsReconcileAgainstACompleteListing() = runBlocking {
        StoreFixtures.store().use { store ->
            fun c(id: String, body: String) = CommentRecord(W, id, CommentRecord.Parent.TASK, "t1", body = body, senderNpub = TestKeys.NPUB, createdAt = at,
                raw = Fixtures.obj(mapOf("id" to id, "body" to body)))
            store.writer.upsertComments(W, listOf(c("c1", "first"), c("c2", "second")))
            store.writer.upsertComments(W, listOf(c("c2", "second")), complete = CommentRecord.Parent.TASK to "t1")
            assertEquals(listOf("c1" to true, "c2" to false), store.reader.comments(W, CommentRecord.Parent.TASK, "t1").map { it.id to it.accessRemoved })
        }
    }

    @Test fun linkedFromListsSavedPagesThatLinkHereAcrossChannelsAndSkipsCodeAndRemovedPages() = runBlocking {
        StoreFixtures.store().use { store ->
            val target = "0a1b2c3d-0000-4000-8000-0000000000aa"
            suspend fun save(id: String, title: String, text: String) =
                store.writer.saveDocBody(DocBodyRecord(W, id, "obj-$id", "sha-$id", "text/markdown", 1, text.toByteArray(), at), doc(id, title), text)
            save(target, "Target", "# Target")
            save("link-b", "Beta", "See [Target](wiki:$target)")
            save("link-a", "Alpha", "Also [Target](wiki:${target.uppercase()})")
            save("code", "Code", "`[Target](wiki:$target)`")
            save("plain", "Plain", "nothing here")
            save("gone", "Gone", "[Target](wiki:$target)")
            store.writer.markAccessRemoved(W, AccessTarget.Doc("gone"))
            val links = store.reader.docBacklinks(W, target)
            assertEquals(listOf("link-a", "link-b"), links.docs.map { it.id })
            assertEquals("every saved page that is still readable was checked, including the target itself", 5, links.checked)
        }
    }

    @Test fun documentBodiesAreCachedNewestFirstAndIndexed() = runBlocking {
        StoreFixtures.store().use { store ->
            val d = doc("d1", "Plan", rowVersion = 2)
            store.writer.saveDocBody(DocBodyRecord(W, "d1", "obj1", "aa", "text/markdown", 2, "zebra crossing".toByteArray(), at), d, "zebra crossing")
            store.writer.saveDocBody(DocBodyRecord(W, "d1", "obj0", "bb", "text/markdown", 1, "old".toByteArray(), at), null, "old")
            val body = store.reader.docBody(W, "d1")!!
            assertEquals("an older body never replaces a newer one", "aa", body.sha256)
            assertEquals(listOf("d1"), store.reader.search(W, "zebra").map { it.recordId })
        }
    }

    @Test fun docDraftStoreBacksTheDocumentSaver() = runBlocking {
        StoreFixtures.store().use { store ->
            val drafts = store.writer.docDraftStore()
            val c1 = StoreFixtures.connect(store).id
            val draft = DocDraft(c1, W, D, "Plan", "# Plan".toByteArray(), "text/markdown", 1, "aa", "obj1", DocDraft.State.EDITING)
            drafts.saveDocDraft(draft)
            assertEquals(draft, store.reader.docDraft(draft.key))
            assertEquals(setOf(D), store.reader.docDraftIds(c1, W))
            val saved = doc(D, "Plan v2", rowVersion = 2, storage = "obj2")
            drafts.saveDocBody(SavedDocBody(W, D, "obj2", "cc", "text/markdown", 2, "# Plan v2".toByteArray(), at),
                SavedDocument(W, D, 2, Fixtures.obj(mapOf("id" to D, "workspace_id" to W, "channel_id" to Fixtures.CHANNEL_1, "title" to "Plan v2",
                    "row_version" to 2, "storage_object_id" to "obj2", "created_at" to at, "updated_at" to at))), "Plan v2 body")
            drafts.deleteDocDraft(draft.key, W)
            assertNull(store.reader.docDraft(draft.key))
            assertArrayEquals("# Plan v2".toByteArray(), store.reader.docBody(W, D)?.bytes)
            assertEquals(saved.title, store.reader.doc(W, D)?.title)
        }
    }

    @Test fun viewStatesAreLocalFirstAndNeverGoBack() = runBlocking {
        StoreFixtures.store().use { store ->
            val v = ViewStateRecord(W, ViewStateRecord.ResourceType.TASK, "t1", Fixtures.SCOPE_A, Fixtures.CHANNEL_1, activityVersion = 5,
                viewedActivityVersion = 2, updatedAt = at)
            store.writer.upsertViewStates(W, listOf(v))
            assertEquals(setOf(Fixtures.CHANNEL_1), store.reader.unreadChannelIds(W))
            store.writer.markViewedLocally(W, listOf(ViewedResource(ViewStateRecord.ResourceType.TASK, "t1", 5)))
            assertTrue(store.reader.unreadChannelIds(W).isEmpty())
            // Tower's older read position (not yet confirmed) does not move the local one back.
            store.writer.upsertViewStates(W, listOf(v.copy(rowVersion = 2)))
            assertEquals(5L, store.reader.viewState(W, ViewStateRecord.ResourceType.TASK, "t1")?.viewedActivityVersion)
        }
    }

    @Test fun reactionsAreReplacedPerTarget() = runBlocking {
        StoreFixtures.store().use { store ->
            fun r(id: String, target: String, deleted: Boolean = false) = ReactionRecord(W, id, "message", target, "thumbs_up", TestKeys.NPUB, deleted, 1, at)
            store.writer.replaceReactions(W, listOf("m1", "m2"), listOf(r("r1", "m1"), r("r2", "m2"), r("r3", "m3")))
            assertEquals(setOf("r1", "r2"), store.reader.reactions(W, listOf("m1", "m2", "m3")).map { it.id }.toSet())
            store.writer.replaceReactions(W, listOf("m1"), listOf(r("r4", "m1"), r("r5", "m1", deleted = true)))
            assertEquals(listOf("r4"), store.reader.reactions(W, listOf("m1")).map { it.id })
            store.writer.deleteReaction(W, "r2")
            assertTrue(store.reader.reactions(W, listOf("m2")).isEmpty())
        }
    }

    @Test fun localSearchIsWorkspaceScopedAndInjectionSafe() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertMessages(listOf(message("m1", "Quarterly roadmap review tomorrow", at)))
            store.writer.upsertTasks(W, listOf(task("t1", "Roadmap slides")))
            assertEquals(setOf(SearchHit.Kind.MESSAGE, SearchHit.Kind.TASK), store.reader.search(W, "roadm").map { it.kind }.toSet())
            assertTrue(store.reader.search(StoreFixtures.W2, "roadmap").isEmpty())
            assertTrue(store.reader.search(W, "\" OR * NEAR(").isEmpty())
            assertTrue(store.reader.search(W, "roadmap\" OR \"x").isEmpty())
            assertTrue("one character is too short", store.reader.search(W, "x").isEmpty())
            assertEquals("\"road\"* \"map\"*", SearchIndex.ftsQuery("road-map"))
            assertNull(SearchIndex.ftsQuery("*** ()"))
            // A deleted message leaves the index; an edit replaces its text.
            store.writer.upsertMessages(listOf(message("m1", "Renamed agenda", "2026-10-01T00:00:00.000Z", rowVersion = 2)))
            assertEquals(listOf(SearchHit.Kind.TASK), store.reader.search(W, "roadmap").map { it.kind })
            assertEquals(1, store.reader.search(W, "agenda").size)
        }
    }

    @Test fun contextReadsAreBoundedToTheScopeOrChannel() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "A"), scope(Fixtures.SCOPE_B, "B")), complete = true)
            store.writer.upsertChannels(W, Fixtures.SCOPE_A, listOf(channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "one")), complete = true)
            store.writer.upsertChannels(W, Fixtures.SCOPE_B, listOf(channel(Fixtures.CHANNEL_2, Fixtures.SCOPE_B, "two")), complete = true)
            store.writer.upsertTasks(W, listOf(task("t1", "In one"), task("t2", "In two", Fixtures.CHANNEL_2).copy(scopeId = null)))
            assertEquals(listOf("t1"), store.reader.tasks(W, WorkContext(Fixtures.SCOPE_A)).map { it.id })
            assertEquals("a record with only a channel matches its scope", listOf("t2"), store.reader.tasks(W, WorkContext(Fixtures.SCOPE_B)).map { it.id })
            assertEquals(listOf("t2"), store.reader.tasks(W, WorkContext(Fixtures.SCOPE_B, Fixtures.CHANNEL_2)).map { it.id })
            assertEquals(2, store.reader.tasks(W, WorkContext()).size)
        }
    }

    @Test fun durableWritesLifecycle() = runBlocking {
        StoreFixtures.store().use { store ->
            val connection = StoreFixtures.connect(store)
            val workspace = StoreFixtures.workspace(W, "Studio")
            val intent = WriteIntent(WriteKind.TASK_STATE, "t1", jsonObjectOf("state" to "done", "expected_row_version" to 1))
            assertEquals("task:t1", intent.groupKey)
            val item = store.writer.enqueueWrite(connection, workspace, intent)
            assertEquals(item, store.reader.writeItem(item.id))
            assertEquals("done", item.string("state"))
            assertTrue(store.writer.freezeWrite(item.id, "req".toByteArray()))
            assertFalse(store.writer.freezeWrite(item.id, "other".toByteArray()))
            store.writer.markWrite(item.id, WriteState.SENDING)
            store.writer.discardWrite(item.id)
            assertTrue("a sending write is not discarded", store.reader.writeItem(item.id) != null)
            // Relaunch: an interrupted write is flagged ambiguous and queued again.
            store.writer.resetInterruptedWrites()
            val reset = store.reader.writeItem(item.id)!!
            assertEquals(WriteState.QUEUED, reset.state)
            assertTrue(reset.payloadObject["ambiguous"]!!.jsonPrimitive.boolean)
            store.writer.markWrite(item.id, WriteState.CONFLICT, status = 409, code = "stale", countAttempt = true)
            assertFalse(store.reader.writeItem(item.id)!!.isPending)
            store.writer.retryWrite(item.id, force = true)
            val forced = store.reader.writeItem(item.id)!!
            assertEquals(WriteState.QUEUED, forced.state)
            assertEquals(JsonPrimitive(true), forced.payloadObject["force"])
            assertEquals(listOf(item.id), store.reader.workspaceWrites(connection.id, W, "t1").map { it.id })
            assertEquals(listOf(item.id), store.reader.writes(connection.id, TestKeys.NPUB).map { it.id })
            // Acknowledgement stores what Tower returned and retires the row in one step.
            store.writer.acknowledgeWrite(item.id, W, WriteAcknowledgement(tasks = listOf(task("t1", "Done task", state = "done"))))
            assertNull(store.reader.writeItem(item.id))
            assertEquals("done", store.reader.task(W, "t1")?.state)
        }
    }

    @Test fun listSyncStateIsKeptPerKey() = runBlocking {
        StoreFixtures.store().use { store ->
            assertNull(store.reader.listSyncComplete(W, "tasks:c1"))
            store.writer.saveListSync(W, "tasks:c1", complete = false)
            assertEquals(false, store.reader.listSyncComplete(W, "tasks:c1"))
            store.writer.saveListSync(W, "tasks:c1", complete = true)
            assertEquals(true, store.reader.listSyncComplete(W, "tasks:c1"))
        }
    }

    @Test fun deckReadsGroupConversationsAndCountComments() = runBlocking {
        StoreFixtures.store().use { store ->
            val thread = Fixtures.newId()
            store.writer.upsertMessages(listOf(
                message("r1", "root", "2026-10-01T00:00:00.000Z", thread = thread, source = "r1"),
                message("r2", "reply", "2026-10-01T00:01:00.000Z", thread = thread, source = "r1"),
                message("p1", "plain post", "2026-10-01T00:02:00.000Z"),
            ))
            val rows = store.reader.deckThreads(W, null)
            assertEquals(listOf("p1", thread), rows.map { it.threadId })
            assertEquals(2, rows.first { it.threadId == thread }.messageCount)
            assertEquals("reply", rows.first { it.threadId == thread }.latestBody)
            assertTrue(store.reader.deckThreads(W, emptyList()).isEmpty())
            assertEquals(listOf("p1", thread), store.reader.deckThreads(W, listOf(Fixtures.CHANNEL_1)).map { it.threadId })
            store.writer.upsertComments(W, listOf(
                CommentRecord(W, "c1", CommentRecord.Parent.TASK, "t1", body = "mine", senderNpub = TestKeys.NPUB, createdAt = at),
                CommentRecord(W, "c2", CommentRecord.Parent.TASK, "t1", body = "theirs", senderNpub = TestKeys.syntheticNpub(0x35), createdAt = at),
            ))
            assertEquals(1, store.reader.deckCommentActivity(W, CommentRecord.Parent.TASK, TestKeys.NPUB)["t1"]?.count)
        }
    }
}
