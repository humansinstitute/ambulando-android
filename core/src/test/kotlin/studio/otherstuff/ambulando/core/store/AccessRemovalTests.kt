package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.store.StoreFixtures.message
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.TaskRecord

/** Android: access loss flags records (readable, labelled, out of sync) instead of deleting them. */
class AccessRemovalTests {
    private val at = "2026-10-01T00:00:00.000Z"

    private fun task(id: String, channel: String = Fixtures.CHANNEL_1, title: String = "Task $id") = TaskRecord(
        workspaceId = W, id = id, scopeId = Fixtures.SCOPE_A, channelId = channel, title = title, description = null, state = "new", priority = "sand",
        assignedToNpub = null, rowVersion = 1, createdAt = at, updatedAt = at, raw = Fixtures.obj(mapOf("id" to id, "title" to title)),
    )

    private suspend fun seeded(): LocalStore {
        val store = StoreFixtures.store()
        store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "A"), scope(Fixtures.SCOPE_B, "B")), complete = true)
        store.writer.upsertChannels(W, Fixtures.SCOPE_A, listOf(channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "general")), complete = true)
        store.writer.upsertChannels(W, Fixtures.SCOPE_B, listOf(channel(Fixtures.CHANNEL_2, Fixtures.SCOPE_B, "ops")), complete = true)
        store.writer.upsertMessages(listOf(message("m1", "secret plan", at), message("m2", "ops note", at, channel = Fixtures.CHANNEL_2)))
        store.writer.upsertTasks(W, listOf(task("t1")))
        store.writer.upsertComments(W, listOf(CommentRecord(W, "c1", CommentRecord.Parent.TASK, "t1", body = "note", senderNpub = null, createdAt = at)))
        return store
    }

    @Test fun completeListsFlagRevokedScopesWithTheirChannelsAndContent() = runBlocking {
        seeded().use { store ->
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "A")), complete = true)
            // Nothing was deleted: the rows are readable and labelled.
            assertEquals(listOf(Fixtures.SCOPE_A to false, Fixtures.SCOPE_B to true), store.reader.scopes(W).map { it.id to it.accessRemoved })
            assertEquals(true, store.reader.channel(W, Fixtures.CHANNEL_2)?.accessRemoved)
            assertEquals(listOf(true), store.reader.latestMessages(W, Fixtures.CHANNEL_2, 10).map { it.accessRemoved })
            // Sync never selects them again.
            assertEquals(listOf(Fixtures.SCOPE_A), store.reader.syncableScopes(W).map { it.id })
            assertEquals(listOf(Fixtures.CHANNEL_1), store.reader.syncableChannels(W).map { it.id })
            assertTrue(store.reader.isChannelAccessRemoved(W, Fixtures.CHANNEL_2))
            assertFalse(store.reader.isChannelAccessRemoved(W, Fixtures.CHANNEL_1))
        }
    }

    @Test fun channelAccessLossFlagsContentAndBlocksFurtherWrites() = runBlocking {
        seeded().use { store ->
            store.writer.upsertChannels(W, Fixtures.SCOPE_A, emptyList(), complete = true)
            assertEquals(true, store.reader.channel(W, Fixtures.CHANNEL_1)?.accessRemoved)
            assertEquals(true, store.reader.message(W, "m1")?.accessRemoved)
            assertEquals(true, store.reader.task(W, "t1")?.accessRemoved)
            assertEquals(listOf(true), store.reader.comments(W, CommentRecord.Parent.TASK, "t1").map { it.accessRemoved })
            assertEquals(setOf("m1"), store.reader.accessRemovedIds(W, FlaggedTable.MESSAGES))
            // The other channel is untouched.
            assertEquals(false, store.reader.message(W, "m2")?.accessRemoved)
            // A late fetch for the flagged channel writes nothing.
            assertEquals(0, store.writer.upsertMessages(listOf(message("m3", "late", at))))
            assertNull(store.reader.message(W, "m3"))
            assertEquals(0, store.writer.upsertTasks(W, listOf(task("t2"))))
            // Flagged rows are still found by search, labelled.
            assertEquals(listOf(true), store.reader.search(W, "secret").map { it.accessRemoved })
        }
    }

    @Test fun aChannelListedAgainIsRestoredWithItsContent() = runBlocking {
        seeded().use { store ->
            store.writer.upsertChannels(W, Fixtures.SCOPE_A, emptyList(), complete = true)
            store.writer.upsertChannels(W, Fixtures.SCOPE_A, listOf(channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "general")), complete = true)
            assertEquals(false, store.reader.channel(W, Fixtures.CHANNEL_1)?.accessRemoved)
            assertEquals(false, store.reader.message(W, "m1")?.accessRemoved)
            assertEquals(false, store.reader.task(W, "t1")?.accessRemoved)
            assertEquals(1, store.writer.upsertMessages(listOf(message("m3", "after return", at))))
        }
    }

    @Test fun markAccessRemovedFlagsSingleRecordsAndAnUpsertClearsTheFlag() = runBlocking {
        seeded().use { store ->
            val before = store.changes.revisions.value(LiveQuery.ACCESS_CHANGES)
            assertEquals(2, store.writer.markAccessRemoved(W, AccessTarget.Task("t1")))
            assertTrue("access revision moved", store.changes.revisions.value(LiveQuery.ACCESS_CHANGES) > before)
            assertEquals(true, store.reader.task(W, "t1")?.accessRemoved)
            assertEquals(0, store.writer.markAccessRemoved(W, AccessTarget.Task("t1")))
            // Tower shows the same record again (identical payload): the row is rewritten without the flag.
            assertEquals(1, store.writer.upsertTasks(W, listOf(task("t1"))))
            assertEquals(false, store.reader.task(W, "t1")?.accessRemoved)
            store.writer.markAccessRemoved(W, AccessTarget.Thread("th1"), AccessTarget.Message("m2"))
            assertEquals(true, store.reader.message(W, "m2")?.accessRemoved)
        }
    }

    @Test fun completeChannelListsAndReconcileFlagInsteadOfDeleting() = runBlocking {
        seeded().use { store ->
            store.writer.upsertTasks(W, listOf(task("t2")), completeChannel = Fixtures.CHANNEL_1)
            assertEquals(true, store.reader.task(W, "t1")?.accessRemoved)
            assertEquals(false, store.reader.task(W, "t2")?.accessRemoved)
            val flagged = store.writer.reconcileMessages(W, Fixtures.CHANNEL_1, null, null, MessageKey("2026-12-01T00:00:00.000Z", "z"), keep = emptySet())
            assertEquals(1, flagged)
            assertNotNull(store.reader.message(W, "m1"))
            assertEquals(true, store.reader.message(W, "m1")?.accessRemoved)
        }
    }

    @Test fun tombstonesStillDelete() = runBlocking {
        seeded().use { store ->
            store.writer.markAccessRemoved(W, AccessTarget.Task("t1"))
            assertEquals(2, store.writer.applyTombstones(W, setOf(Tombstone.Task("t1"))))
            assertNull(store.reader.task(W, "t1"))
            assertTrue(store.reader.comments(W, CommentRecord.Parent.TASK, "t1").isEmpty())
            store.writer.applyTombstones(W, setOf(Tombstone.Message("m2")))
            assertNull(store.reader.message(W, "m2"))
        }
    }

    @Test fun anUnlistedWorkspaceIsFlaggedAndKeptButDisconnectDeletesIt() = runBlocking {
        StoreFixtures.store().use { store ->
            val connection = StoreFixtures.connect(store)
            store.writer.replaceWorkspaces(connection.id, listOf(StoreFixtures.workspace(W, "One"), StoreFixtures.workspace(StoreFixtures.W2, "Two")))
            store.writer.upsertScopes(StoreFixtures.W2, listOf(scope(Fixtures.SCOPE_A, "In two", StoreFixtures.W2)), complete = true)
            store.writer.replaceWorkspaces(connection.id, listOf(StoreFixtures.workspace(W, "One")))
            assertEquals(listOf("One" to false, "Two" to true), store.reader.workspaces(connection.id).map { it.label to it.accessRemoved })
            assertEquals(listOf("One"), store.reader.syncableWorkspaces(connection.id).map { it.label })
            assertEquals(listOf("In two"), store.reader.scopes(StoreFixtures.W2).map { it.name })
            // Listed again: unflagged.
            store.writer.replaceWorkspaces(connection.id, listOf(StoreFixtures.workspace(W, "One"), StoreFixtures.workspace(StoreFixtures.W2, "Two")))
            assertEquals(2, store.reader.syncableWorkspaces(connection.id).size)
            store.writer.markWorkspaceAccessRemoved(connection.id, StoreFixtures.W2)
            assertEquals(1, store.reader.syncableWorkspaces(connection.id).size)
            // Disconnect removes everything for the connection, flagged or not.
            store.writer.removeConnection(connection.id)
            assertTrue(store.reader.workspaces(connection.id).isEmpty())
            assertTrue(store.reader.scopes(StoreFixtures.W2).isEmpty())
        }
    }
}
