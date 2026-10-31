package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.MemorySecretStore
import studio.otherstuff.ambulando.core.support.StubTransport
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.surfaces.SurfaceHttpException
import studio.otherstuff.ambulando.core.surfaces.TaskHierarchy
import studio.otherstuff.ambulando.core.surfaces.TaskRelationshipOutcome
import studio.otherstuff.ambulando.core.surfaces.TaskTreeError
import studio.otherstuff.ambulando.core.surfaces.TaskTreeService
import studio.otherstuff.ambulando.core.surfaces.TaskTreeWrites
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.transport.TaskRecord
import java.io.File
import java.io.IOException

/** Per-parent relationship membership, completeness, restriction and refresh state, on generic synthetic tasks. */
class TaskRelationshipStoreTests {
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private val channel = Fixtures.CHANNEL_1

    private fun row(n: Int, parent: Int? = null, after: Int? = null, state: String = "new", created: String = "2026-10-01T10:00:%02d.000Z".format(n % 60),
                    version: Long = 1, updated: String = created): JsonObject {
        val metadata = LinkedHashMap<String, Any?>()
        if (parent != null) metadata["parent_task_id"] = id(parent)
        if (after != null) metadata["predecessor_task_ids"] = listOf(id(after))
        return Fixtures.obj(mapOf("id" to id(n), "workspace_id" to W, "channel_id" to channel, "title" to "T$n", "state" to state, "priority" to "sand",
            "created_at" to created, "updated_at" to updated, "row_version" to version, "metadata" to metadata))
    }

    private fun task(n: Int, parent: Int? = null, after: Int? = null, state: String = "new", version: Long = 1, created: String = "2026-10-01T10:00:%02d.000Z".format(n % 60),
                     updated: String = created): TaskRecord = TaskRecord.parse(row(n, parent, after, state, created, version, updated))

    /** A scripted Tower: `pages[parent]` is the ordered list of (child rows, unavailable) pages. */
    private class Tower(val pages: MutableMap<String, List<Pair<List<JsonObject>, Boolean>>> = HashMap(), val rows: Map<String, JsonObject>) {
        val requests = ArrayList<Pair<String, String?>>()
        var failAt: (String, String?) -> Exception? = { _, _ -> null }
        val reader = TaskTreeService.RelationshipsReader { taskId, cursor ->
            requests.add(taskId to cursor)
            failAt(taskId, cursor)?.let { throw it }
            val list = pages[taskId] ?: throw SurfaceHttpException(404, "not_found")
            val index = cursor?.removePrefix("c")?.toInt() ?: 0
            val (children, unavailable) = list[index]
            TaskTreeService.RelationshipsPage(rows[taskId]!!, children, emptyMap(), unavailable, if (index + 1 < list.size) "c${index + 1}" else null)
        }
    }

    private fun writes(store: LocalStore): TaskTreeWrites {
        val dir = tempDir()
        return TaskTreeWrites(store, IdentityService(File(dir, "vault.json"), MemorySecretStore()), StubTransport { 404 to null })
    }

    private suspend fun setup(store: LocalStore) = StoreFixtures.connect(store).let { StoreFixtures.workspace(W, "Work") to it }

    private suspend fun children(store: LocalStore, parent: Int) = store.reader.directSubtasks(W, id(parent)).map { it.id }

    @Test fun anUnloadedParentShowsSavedChildrenAndCarriesNoState() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertTasks(W, listOf(task(1), task(2, parent = 1), task(3, parent = 1, state = "archive"), task(4, after = 1), task(5, parent = 2)))
            assertEquals("archived children count; a dependency successor is not a child", listOf(id(2), id(3)), children(store, 1))
            assertNull(store.reader.taskRelationship(W, id(1)))
            assertEquals(TaskHierarchy.Kind.UNLOADED, TaskHierarchy.kind(null, readable = true))
            assertEquals(TaskHierarchy.Kind.OFFLINE, TaskHierarchy.kind(null, readable = false))
        }
    }

    @Test fun aCompleteMultiPageReadReplacesStaleMembershipWithoutTouchingTaskRecords() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            // The device saved two children, one of which Tower no longer lists (removed/reparented elsewhere), and the dependent 4.
            store.writer.upsertTasks(W, listOf(task(1), task(2, parent = 1), task(9, parent = 1)))
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1), row(3, parent = 1)) to false, listOf(row(4, after = 1), row(5, parent = 1, state = "archive")) to false)
            val before = store.reader.task(W, id(9))
            assertEquals(TaskRelationshipOutcome.COMPLETE, writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader))
            assertEquals(listOf(id(2), id(3), id(5)), children(store, 1))
            val state = store.reader.taskRelationship(W, id(1))!!
            assertTrue(state.complete); assertFalse(state.restricted); assertNull(state.failure); assertNull(state.nextCursor)
            assertEquals(2, state.pages); assertNotNull(state.refreshedAt)
            assertEquals("the unlisted child's record is neither deleted nor re-parented", before, store.reader.task(W, id(9)))
            assertEquals(TaskHierarchy.Kind.COMPLETE, TaskHierarchy.kind(state, readable = true))
            assertEquals("3", TaskHierarchy.countLabel(children(store, 1).size, TaskHierarchy.isConfirmed(TaskHierarchy.kind(state, true))))
            assertEquals("the same list is only cached when the device cannot read", TaskHierarchy.Kind.CACHED, TaskHierarchy.kind(state, readable = false))
        }
    }

    @Test fun aSecondCompleteReadDropsRemovedAndReparentedChildren() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1), id(6) to row(6)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1), row(3, parent = 1), row(4, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            assertEquals(listOf(id(2), id(3), id(4)), children(store, 1))
            // 3 is gone for this identity; 4 moved under 6 (its newer row names 6).
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false)
            store.writer.upsertTasks(W, listOf(task(6), task(4, parent = 6, version = 2)))
            assertEquals("a newer row that names another parent leaves the old list at once", listOf(id(2), id(3)), children(store, 1))
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            assertEquals(listOf(id(2)), children(store, 1))
            assertEquals("the reparented task still exists, under its new parent", id(6), TaskHierarchy.parentId(store.reader.task(W, id(4))!!))
            assertNotNull(store.reader.task(W, id(3)))
        }
    }

    @Test fun aFailedPageKeepsTheConfirmedListAndTheNextReadResumes() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false, listOf(row(3, parent = 1)) to false, listOf(row(4, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            val confirmed = children(store, 1)
            assertEquals(listOf(id(2), id(3), id(4)), confirmed)
            // The refresh loses its second page.
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false, listOf(row(5, parent = 1)) to false, listOf(row(6, parent = 1)) to false)
            tower.failAt = { _, cursor -> if (cursor == "c1") IOException("offline") else null }
            assertTrue(runCatching { writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader) }.exceptionOrNull() is IOException)
            val failed = store.reader.taskRelationship(W, id(1))!!
            assertTrue("the earlier confirmed list stays", failed.complete)
            assertEquals("failed", failed.failure); assertEquals("c1", failed.nextCursor)
            assertEquals(TaskHierarchy.Kind.CACHED, TaskHierarchy.kind(failed, readable = true))
            assertEquals(confirmed, children(store, 1).filter { it in confirmed })
            // Reconnected: it resumes at the stored cursor and then replaces the list.
            tower.failAt = { _, _ -> null }
            tower.requests.clear()
            assertEquals(TaskRelationshipOutcome.COMPLETE, writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader))
            assertEquals(listOf(id(1) to "c1", id(1) to "c2"), tower.requests)
            assertEquals(listOf(id(2), id(5), id(6)), children(store, 1))
            assertEquals(TaskHierarchy.Kind.COMPLETE, TaskHierarchy.kind(store.reader.taskRelationship(W, id(1)), readable = true))
        }
    }

    @Test fun aFirstReadThatStopsPartWayIsPartialAndKeepsWhatWasRead() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            store.writer.upsertTasks(W, listOf(task(1), task(8, parent = 1)))
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = (2..6).map { listOf(row(it, parent = 1)) to false }
            assertEquals("the page budget bounds one call", TaskRelationshipOutcome.PARTIAL, writes(store).loadRelationships(connection, workspace, id(1), pageBudget = 2, reader = tower.reader))
            val partial = store.reader.taskRelationship(W, id(1))!!
            assertFalse(partial.complete); assertEquals(2, partial.pages); assertEquals("c2", partial.nextCursor)
            assertEquals(TaskHierarchy.Kind.PARTIAL, TaskHierarchy.kind(partial, readable = true))
            assertEquals("pages read so far plus saved children", listOf(id(2), id(3), id(8)), children(store, 1))
            assertEquals(TaskRelationshipOutcome.COMPLETE, writes(store).loadRelationships(connection, workspace, id(1), pageBudget = 10, reader = tower.reader))
            assertEquals("completion drops the saved child Tower never listed", (2..6).map { id(it) }, children(store, 1))
        }
    }

    @Test fun aCursorTowerNoLongerAcceptsRestartsTheRead() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = (2..4).map { listOf(row(it, parent = 1)) to false }
            writes(store).loadRelationships(connection, workspace, id(1), pageBudget = 1, reader = tower.reader)
            tower.failAt = { _, cursor -> if (cursor != null) SurfaceHttpException(400, "bad_cursor") else null }
            assertTrue(runCatching { writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader) }.isFailure)
            assertNull("the dead cursor is dropped", store.reader.taskRelationship(W, id(1))!!.nextCursor)
            tower.failAt = { _, _ -> null }
            tower.requests.clear()
            assertEquals(TaskRelationshipOutcome.COMPLETE, writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader))
            assertEquals(id(1) to null, tower.requests.first())
            assertEquals((2..4).map { id(it) }, children(store, 1))
        }
    }

    @Test fun aRefusedParentIsRestrictedAndNeverClaimsToEnumerate() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            tower.failAt = { _, _ -> SurfaceHttpException(403, "forbidden") }
            assertEquals(TaskTreeError.Unavailable, runCatching { writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader) }.exceptionOrNull())
            val state = store.reader.taskRelationship(W, id(1))!!
            assertTrue(state.restricted); assertFalse(state.complete)
            assertEquals(TaskHierarchy.Kind.RESTRICTED, TaskHierarchy.kind(state, readable = true))
            assertFalse("a restricted count is never certain", TaskHierarchy.isConfirmed(TaskHierarchy.kind(state, true)))
            assertEquals("saved readable children stay", listOf(id(2)), children(store, 1))
        }
    }

    @Test fun towerReportingSomeTasksUnavailableMakesACompleteReadRestricted() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to true, listOf(row(3, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            val state = store.reader.taskRelationship(W, id(1))!!
            assertTrue(state.complete); assertTrue("a flag on any page restricts the whole list", state.restricted)
            assertEquals(TaskHierarchy.Kind.RESTRICTED, TaskHierarchy.kind(state, readable = true))
            // A later read with nothing hidden lifts the restriction.
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1), row(3, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            assertFalse(store.reader.taskRelationship(W, id(1))!!.restricted)
        }
    }

    @Test fun stateAndMembershipSurviveRelaunch() = runBlocking {
        val dir = tempDir()
        StoreFixtures.store(dir).use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false, listOf(row(3, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), pageBudget = 1, reader = tower.reader)
            store.writer.upsertTasks(W, listOf(task(9, parent = 1)))
        }
        StoreFixtures.store(dir).use { store ->
            val state = store.reader.taskRelationship(W, id(1))!!
            assertEquals(1, state.pages); assertEquals("c1", state.nextCursor); assertFalse(state.complete)
            assertEquals(listOf(id(2), id(9)), children(store, 1))
            assertEquals(Schema.VERSION, store.writer.schemaVersion())
        }
    }

    @Test fun anAuthorizedUpsertJoinsAKnownListWhateverTheClocksSay() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            // Acknowledged/streamed children whose timestamps are years before and after this device's clock and the list's refresh.
            store.writer.upsertTasks(W, listOf(task(3, parent = 1, created = "2001-01-01T00:00:00.000Z"), task(4, parent = 1, created = "2099-01-01T00:00:00.000Z")))
            assertEquals(listOf(id(3), id(2), id(4)), children(store, 1))
            // A write acknowledgement stores through the same path.
            store.writer.upsertTasks(W, listOf(task(5, parent = 1, created = "1999-12-31T23:59:59.000Z")))
            assertTrue(id(5) in children(store, 1))
            // An older copy of a task never changes membership.
            store.writer.upsertTasks(W, listOf(task(2, parent = 7, version = 0)))
            assertTrue(id(2) in children(store, 1))
        }
    }

    @Test fun aChildNotedWhileAReadIsInFlightSurvivesItsReplacement() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false, listOf(row(3, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            // Second read: a local creation is acknowledged between its pages, after Tower built the first page.
            val gate = TaskTreeService.RelationshipsReader { taskId, cursor ->
                if (cursor == "c1") store.writer.upsertTasks(W, listOf(task(7, parent = 1)))
                tower.reader.page(taskId, cursor)
            }
            writes(store).loadRelationships(connection, workspace, id(1), reader = gate)
            assertEquals(listOf(id(2), id(3), id(7)), children(store, 1))
        }
    }

    @Test fun aSupersededReadCannotWriteItsPage() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertTasks(W, listOf(task(1), task(2, parent = 1)))
            val first = store.writer.beginTaskRelationshipRead(W, id(1))
            store.writer.applyTaskRelationshipPage(W, id(1), first.generation, listOf(task(2, parent = 1)), listOf(id(2)), false, "c1")
            val resumed = store.writer.beginTaskRelationshipRead(W, id(1))
            assertEquals("an unfinished read resumes at its cursor rather than starting another", TaskRelationshipRead(first.generation, "c1"), resumed)
            store.writer.applyTaskRelationshipPage(W, id(1), first.generation, emptyList(), emptyList(), false, null)
            val next = store.writer.beginTaskRelationshipRead(W, id(1))
            assertEquals(first.generation + 1, next.generation)
            assertFalse("a page of the finished read is ignored", store.writer.applyTaskRelationshipPage(W, id(1), first.generation, emptyList(), emptyList(), false, null))
            val newest = store.writer.beginTaskRelationshipRead(W, id(1))
            assertEquals("a second start before any page replaces the first", next.generation + 1, newest.generation)
            assertFalse(store.writer.applyTaskRelationshipPage(W, id(1), next.generation, emptyList(), emptyList(), false, null))
        }
    }

    @Test fun aReplayedPageCannotRewindOrReopenAFinishedRead() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertTasks(W, listOf(task(1), task(2, parent = 1), task(3, parent = 1)))
            val read = store.writer.beginTaskRelationshipRead(W, id(1))
            assertTrue(store.writer.applyTaskRelationshipPage(W, id(1), read.generation, listOf(task(2, parent = 1)), listOf(id(2)), false, "c1", fromCursor = null))
            assertFalse("a duplicate first page is not a second first page", store.writer.applyTaskRelationshipPage(W, id(1), read.generation, listOf(task(2, parent = 1)), listOf(id(2)), false, "c1", fromCursor = null))
            assertTrue(store.writer.applyTaskRelationshipPage(W, id(1), read.generation, listOf(task(3, parent = 1)), listOf(id(3)), false, null, fromCursor = "c1"))
            // A late copy of page one (or two) arrives after the read finished: nothing changes and no cursor is reopened.
            assertFalse(store.writer.applyTaskRelationshipPage(W, id(1), read.generation, emptyList(), listOf(id(2)), false, "c1", fromCursor = null))
            assertFalse(store.writer.applyTaskRelationshipPage(W, id(1), read.generation, emptyList(), emptyList(), false, "c9", fromCursor = "c1"))
            val state = store.reader.taskRelationship(W, id(1))!!
            assertTrue(state.complete)
            assertNull(state.nextCursor)
            assertEquals("the next refresh starts a new generation", read.generation + 1, store.writer.beginTaskRelationshipRead(W, id(1)).generation)
        }
    }

    @Test fun completenessOnlyChangesAreObserved() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertTasks(W, listOf(task(1), task(2, parent = 1)))
            val seen = withTimeout(5_000) {
                val flow = store.liveQuery({ it.workspaceId == W && it.table == StoreTable.TASK_RELATIONSHIPS }) { store.reader.subtaskTree(W, id(1)) }
                val collected = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { flow.take(3).toList() }
                kotlinx.coroutines.delay(100)
                val read = store.writer.beginTaskRelationshipRead(W, id(1))
                kotlinx.coroutines.delay(100)
                // The only difference between the last two values is that the list is now confirmed complete.
                store.writer.applyTaskRelationshipPage(W, id(1), read.generation, listOf(task(2, parent = 1)), listOf(id(2)), false, null)
                collected.await()
            }
            assertNull(seen[0].states[id(1)])
            assertFalse(seen[1].states[id(1)]!!.complete)
            assertTrue(seen[2].states[id(1)]!!.complete)
            assertEquals(seen[1].children, seen[2].children)
        }
    }

    @Test fun nestedExpansionLoadsEachDirectListAndCyclesStop() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1), id(2) to row(2, parent = 1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false)
            tower.pages[id(2)] = listOf(listOf(row(3, parent = 2), row(1, parent = 2)) to false) // 1 names 2 as parent too: a cycle
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            store.writer.upsertTasks(W, listOf(task(1)))
            val before = store.reader.subtaskTree(W, id(1))
            assertEquals("only the root has been read", setOf(id(1)), before.states.keys)
            writes(store).loadRelationships(connection, workspace, id(2), reader = tower.reader)
            val tree = store.reader.subtaskTree(W, id(1))
            assertEquals(listOf(id(2)), tree.children[id(1)]!!.map { it.id })
            assertEquals("task 1 is listed under its own child 2, closing a cycle", listOf(id(1), id(3)), tree.children[id(2)]!!.map { it.id })
            assertEquals("expansion stops at the repeat: task 1 is not expanded twice", setOf(id(1), id(2), id(3)), tree.children.keys)
            assertEquals(setOf(id(1), id(2)), tree.states.keys)
            assertTrue(TaskHierarchy.closesCycle(listOf(id(1), id(2)), id(1)))
            // A genuine cycle in saved metadata terminates and is bounded.
            store.writer.upsertTasks(W, listOf(task(10, parent = 11), task(11, parent = 10)))
            val loop = store.reader.subtaskTree(W, id(10))
            assertEquals(setOf(id(10), id(11)), loop.children.keys)
        }
    }

    @Test fun aDeletedTaskLeavesItsParentsListAndLosesItsOwn() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1), row(3, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            store.writer.applyTombstones(W, setOf(studio.otherstuff.ambulando.core.store.Tombstone.Task(id(2))))
            assertEquals(listOf(id(3)), children(store, 1))
            store.writer.applyTombstones(W, setOf(studio.otherstuff.ambulando.core.store.Tombstone.Task(id(1))))
            assertNull(store.reader.taskRelationship(W, id(1)))
        }
    }

    @Test fun aQueuedCreateStaysSeparateFromTheConfirmedList() = runBlocking {
        StoreFixtures.store().use { store ->
            val (workspace, connection) = setup(store)
            val tower = Tower(rows = mapOf(id(1) to row(1)))
            tower.pages[id(1)] = listOf(listOf(row(2, parent = 1)) to false)
            writes(store).loadRelationships(connection, workspace, id(1), reader = tower.reader)
            // Nothing is stored for a queued creation (it lives in the write queue); its acknowledgement joins the list.
            assertEquals(listOf(id(2)), children(store, 1))
            store.writer.upsertTasks(W, listOf(task(6, parent = 1)))
            assertEquals(listOf(id(2), id(6)), children(store, 1))
        }
    }
}
