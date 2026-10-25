package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.surfaces.AgentActivityRecord
import studio.otherstuff.ambulando.core.surfaces.BoardSceneRecord
import studio.otherstuff.ambulando.core.surfaces.CanvasRecord
import studio.otherstuff.ambulando.core.surfaces.CanvasSnapshotRecord
import studio.otherstuff.ambulando.core.surfaces.ContextComponentRecord
import studio.otherstuff.ambulando.core.surfaces.ContextReferenceRecord
import studio.otherstuff.ambulando.core.surfaces.PersonalWappRecord
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.toJsonElement

class SurfaceStoreTests {
    private val at = "2026-10-01T00:00:00.000Z"
    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012x".format(n)

    private fun component(id: String, title: String, parent: String? = null, sort: Int = 0, scopeId: String = Fixtures.SCOPE_A) =
        ContextComponentRecord.parse(Fixtures.obj(mapOf("id" to id, "workspace_id" to W, "scope_id" to scopeId, "parent_id" to parent, "title" to title,
            "sort_order" to sort, "row_version" to 1, "updated_at" to at)))

    private fun reference(id: String, component: String) = ContextReferenceRecord.parse(Fixtures.obj(mapOf("id" to id, "workspace_id" to W,
        "scope_id" to Fixtures.SCOPE_A, "component_id" to component, "target_type" to "task", "target" to mapOf("record_id" to uuid(0x70)),
        "resolution" to mapOf("status" to "available", "title" to "Ship"), "sort_order" to 0, "row_version" to 1, "updated_at" to at)))

    private fun canvas(id: String, title: String = "Board") =
        CanvasRecord.parse(Fixtures.obj(mapOf("id" to id, "title" to title, "position" to 0, "row_version" to 1, "updated_at" to at)), W)

    private fun snapshotBytes(board: String?): ByteArray {
        val naplets = listOfNotNull(board?.let {
            mapOf("id" to uuid(0x33), "row_version" to 1, "title" to "Sketch", "access" to "ready",
                "layout" to mapOf("x" to 0, "y" to 0, "width" to 600, "height" to 420, "z" to 1),
                "source" to mapOf("type" to "excalidraw", "id" to it),
                "content" to mapOf("file_id" to it, "current_version_id" to uuid(0x34), "current_version_number" to 1, "sha256" to "ab".repeat(32),
                    "size_bytes" to 10, "can_write" to false))
        })
        return jsonBody(toJsonElement(mapOf("naplets" to naplets, "elements" to emptyList<Any>())) as kotlinx.serialization.json.JsonObject)
    }

    @Test fun contextTreeIsNestedScopedAndFlagsMissingComponents() = runBlocking {
        StoreFixtures.store().use { store ->
            val root = uuid(0x10); val child = uuid(0x11); val orphan = uuid(0x12)
            store.writer.upsertContextComponents(W, Fixtures.SCOPE_A, listOf(component(root, "Root"), component(child, "Child", root),
                component(orphan, "Orphan", uuid(0x99), sort = 1), component(uuid(0x13), "Other scope", scopeId = Fixtures.SCOPE_B)), complete = true)
            store.writer.replaceContextReferences(W, Fixtures.SCOPE_A, child, listOf(reference(uuid(0x20), child)))
            val tree = store.reader.contextTree(W, Fixtures.SCOPE_A)
            assertEquals(listOf("Root", "Orphan"), tree.map { it.component.title })
            assertEquals(listOf("Child"), tree.first().children.map { it.component.title })
            assertEquals(listOf("Ship"), store.reader.contextReferences(W, child).map { it.resolvedTitle })
            // A complete listing without the child flags it and its references (kept, labelled).
            store.writer.upsertContextComponents(W, Fixtures.SCOPE_A, listOf(component(root, "Root"), component(orphan, "Orphan", uuid(0x99), 1)), complete = true)
            assertEquals(true, store.reader.contextTree(W, Fixtures.SCOPE_A).first().children.single().accessRemoved)
            assertEquals(setOf(uuid(0x20)), store.reader.accessRemovedIds(W, FlaggedTable.CONTEXT_REFERENCES))
            // The scope's whole tree is unavailable.
            store.writer.removeContextTree(W, Fixtures.SCOPE_A)
            assertTrue(store.reader.contextTree(W, Fixtures.SCOPE_A).all { it.accessRemoved })
        }
    }

    @Test fun boardScenesAreKeptOnlyWhileAStoredSnapshotShowsThem() = runBlocking {
        StoreFixtures.store().use { store ->
            val c = uuid(0x40); val board = uuid(0x41)
            fun scene() = BoardSceneRecord(W, board, uuid(0x34), "ab".repeat(32), "{}".toByteArray(), at)
            // A fetch with no canvas showing the board stores nothing.
            store.writer.saveBoardScene(scene())
            assertNull(store.reader.boardScene(W, board))
            store.writer.saveCanvasSnapshot(CanvasSnapshotRecord(W, c, 1, snapshotBytes(board), at), canvas(c))
            store.writer.saveBoardScene(scene())
            assertNotNull(store.reader.boardScene(W, board))
            assertEquals(listOf(c), store.reader.snapshotCanvasIds(W))
            // The board is now denied: the new snapshot no longer shows it and its scene goes.
            store.writer.saveCanvasSnapshot(CanvasSnapshotRecord(W, c, 2, snapshotBytes(null), at), canvas(c))
            assertNull(store.reader.boardScene(W, board))
        }
    }

    @Test fun canvasesNoLongerVisibleAreFlaggedAndKeepTheirSnapshot() = runBlocking {
        StoreFixtures.store().use { store ->
            val a = uuid(0x50); val b = uuid(0x51)
            store.writer.replaceCanvases(W, listOf(canvas(a, "A"), canvas(b, "B")))
            store.writer.saveCanvasSnapshot(CanvasSnapshotRecord(W, b, 1, snapshotBytes(null), at), canvas(b, "B"))
            store.writer.replaceCanvases(W, listOf(canvas(a, "A")))
            assertEquals(listOf("A", "B"), store.reader.canvases(W).map { it.title })
            assertEquals(setOf(b), store.reader.accessRemovedIds(W, FlaggedTable.CANVASES))
            assertNotNull(store.reader.canvasSnapshot(W, b))
            assertTrue("sync does not refresh a flagged canvas", store.reader.snapshotCanvasIds(W).isEmpty())
            store.writer.removeCanvas(W, a)
            assertEquals(setOf(a, b), store.reader.accessRemovedIds(W, FlaggedTable.CANVASES))
            // Listed again: unflagged.
            store.writer.replaceCanvases(W, listOf(canvas(a, "A"), canvas(b, "B")))
            assertTrue(store.reader.accessRemovedIds(W, FlaggedTable.CANVASES).isEmpty())
        }
    }

    @Test fun pinsAndToolboxAreBoundToTheirConnection() = runBlocking {
        StoreFixtures.store().use { store ->
            val mine = StoreFixtures.connect(store)
            val taskId = uuid(0x60)
            store.writer.upsertTasks(W, listOf(TaskRecord(W, taskId, Fixtures.SCOPE_A, Fixtures.CHANNEL_1, title = "Pinned task", description = null,
                state = "new", priority = "sand", assignedToNpub = null, rowVersion = 1, createdAt = at, updatedAt = at)))
            val pin = PinRecord.parse(Fixtures.obj(mapOf("id" to uuid(0x61), "workspace_id" to W, "resource_type" to "task", "resource_id" to taskId,
                "pinned" to true, "row_version" to 1, "updated_at" to at)))
            val wapp = PersonalWappRecord.parse(Fixtures.obj(mapOf("id" to uuid(0x62), "workspace_id" to W, "title" to "Mine",
                "launch_url" to "https://mine.example.com", "status" to "active", "row_version" to 1, "updated_at" to at)))
            store.writer.upsertPins(mine.id, W, listOf(pin), complete = true)
            store.writer.replacePersonalWapps(mine.id, W, listOf(wapp))
            val other = StoreFixtures.connect(store, origin = "https://other-tower.example.com", signer = TestKeys.syntheticNpub(0x42))
            assertTrue(store.reader.pinnedItems(other.id, W).isEmpty())
            assertTrue(store.reader.personalWapps(other.id, W).isEmpty())
            assertEquals(listOf("Pinned task"), store.reader.pinnedItems(mine.id, W).map { it.title })
            assertNotNull(store.reader.pin(mine.id, W, PinRecord.ResourceType.TASK, taskId))
            // The pinned task loses access: the pin stays, labelled.
            store.writer.markAccessRemoved(W, AccessTarget.Task(taskId))
            assertEquals(listOf(true), store.reader.pinnedItems(mine.id, W).map { it.accessRemoved })
            // Disconnecting removes them with the connection.
            store.writer.removeConnection(mine.id)
            assertTrue(store.reader.pinnedItems(mine.id, W).isEmpty())
            assertTrue(store.reader.personalWapps(mine.id, W).isEmpty())
        }
    }

    @Test fun losingAScopeOrChannelFlagsItsSurfaces() = runBlocking {
        StoreFixtures.store().use { store ->
            val s = Fixtures.SCOPE_A
            store.writer.upsertScopes(W, listOf(scope(s, "A")), complete = true)
            store.writer.upsertChannels(W, s, listOf(channel(Fixtures.CHANNEL_1, s, "one"), channel(Fixtures.CHANNEL_2, s, "two")), complete = true)
            store.writer.upsertContextComponents(W, s, listOf(component(uuid(0x80), "Kept until scope is lost")), complete = true)
            val activity = AgentActivityRecord.parse(Fixtures.obj(mapOf("id" to uuid(0x81), "workspace_id" to W, "channel_id" to Fixtures.CHANNEL_2,
                "scope_id" to s, "activity_id" to "act-1", "visibility" to "user_visible", "sequence" to 1, "agent_npub" to TestKeys.NPUB,
                "state" to "working", "label" to "Busy", "updated_at" to at)))
            store.writer.replaceAgentActivities(W, Fixtures.CHANNEL_2, listOf(activity))
            // Channel two disappears from the complete channel list (lost access).
            store.writer.upsertChannels(W, s, listOf(channel(Fixtures.CHANNEL_1, s, "one")), complete = true)
            assertEquals(setOf(uuid(0x81)), store.reader.accessRemovedIds(W, FlaggedTable.AGENT_ACTIVITIES))
            assertEquals(1, store.reader.agentActivities(W, null).size)
            assertEquals("no new activity for a flagged channel", 0, store.writer.replaceAgentActivities(W, Fixtures.CHANNEL_2, listOf(activity)))
            // The whole scope disappears.
            store.writer.upsertScopes(W, emptyList(), complete = true)
            val tree = store.reader.contextTree(W, s)
            assertEquals(listOf(true), tree.map { it.accessRemoved })
            // The scope comes back: its tree is readable unflagged again.
            store.writer.upsertScopes(W, listOf(scope(s, "A")), complete = true)
            assertEquals(listOf(false), store.reader.contextTree(W, s).map { it.accessRemoved })
        }
    }

    @Test fun canvasSourceChoicesLeaveOutFlaggedRecords() = runBlocking {
        StoreFixtures.store().use { store ->
            val a = uuid(0x90); val b = uuid(0x91)
            store.writer.replaceCanvases(W, listOf(canvas(a, "Plan 10"), canvas(b, "Plan 2")))
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "A")), complete = true)
            store.writer.upsertChannels(W, Fixtures.SCOPE_A, listOf(channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "general")), complete = true)
            val titles = store.reader.canvasSourceChoices(W, excludingCanvas = "none").map { it.title }
            assertEquals(listOf("general task tree", "general tasks", "Plan 2", "Plan 10"), titles)
            store.writer.removeCanvas(W, b)
            assertEquals(listOf("Plan 10"), store.reader.canvasSourceChoices(W, excludingCanvas = "none").map { it.title }.filter { it.startsWith("Plan") })
        }
    }
}
