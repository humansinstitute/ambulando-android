package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import studio.otherstuff.ambulando.core.docs.DocDraft
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private suspend inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try { block() } catch (t: Throwable) { if (t is T) return; throw t }
    throw AssertionError("Expected ${T::class.simpleName}")
}

class WorkspaceStorePoolTests {
    private val w = StoreFixtures.W
    private val other = "npub1" + "q".repeat(58)

    private fun target(c: ConnectionRecord, channel: String = "00000000-0000-4000-8000-000000000c01") = ChatTarget(c.id, w, channel)

    /** A catalog as the previous build left it: one connection, one workspace, content, an unsent message and a draft. */
    private fun legacy(): Triple<LocalStore, ConnectionRecord, studio.otherstuff.ambulando.core.transport.WorkspaceRecord> = runBlocking {
        val catalog = StoreFixtures.store()
        val c = StoreFixtures.connect(catalog)
        val ws = StoreFixtures.workspace(w, "Home")
        catalog.writer.replaceWorkspaces(c.id, listOf(ws))
        catalog.writer.upsertScopes(w, listOf(StoreFixtures.scope("00000000-0000-4000-8000-0000000000a1", "Work")), complete = true)
        catalog.writer.upsertMessages(listOf(StoreFixtures.message("00000000-0000-4000-8000-0000000000b1", "hello", "2026-10-01T00:00:01.000Z")))
        catalog.writer.setState("context:${c.id}:$w", "{\"scope\":\"x\"}")
        catalog.writer.setState("settings.appearance", "DARK")
        catalog.writer.saveDraft(target(c), Draft("half typed", emptyList()))
        catalog.writer.enqueueSend(target(c).copy(channelId = "00000000-0000-4000-8000-000000000c02"), c.origin, c.signerNpub, ws.appNpub, "unsent", emptyList(), id = "11111111-1111-4111-8111-111111111111")
        Triple(catalog, c, ws)
    }

    @Test fun `unique legacy workspace moves with its drafts, outbox and navigation and is not repeated`() = runBlocking {
        val (catalog, c, ws) = legacy()
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val store = pool.store(c, ws)
        assertEquals("hello", store.reader.messageCount(w).let { store.reader.latestMessages(w, "00000000-0000-4000-8000-000000000c01", 5).single().body })
        assertEquals("half typed", store.reader.draft(target(c))?.text)
        assertEquals("unsent", store.reader.outbox(c.id, c.signerNpub).single().body)
        assertEquals("{\"scope\":\"x\"}", store.reader.state("context:${c.id}:$w"))
        // Global settings stay in the catalog only.
        assertNull(store.reader.state("settings.appearance"))
        assertEquals("DARK", catalog.reader.state("settings.appearance"))

        // A completed import is never repeated: removing local rows is not undone by a reopen.
        store.writer.discardOutbox("11111111-1111-4111-8111-111111111111", restoreDraft = false)
        pool.close()
        val again = WorkspaceStorePool(catalog, File(pool.directory.path)).store(c, ws)
        assertTrue(again.reader.outbox(c.id, c.signerNpub).isEmpty())
        assertEquals(WorkspaceStorePool.IMPORTED, again.reader.state(WorkspaceStorePool.LEGACY_STATE))
        // The catalog keeps its frozen legacy copy for recovery.
        assertEquals(1, catalog.reader.outbox(c.id, c.signerNpub).size)
    }

    @Test fun `the frozen backup is taken once, never replaced or changed by later catalog changes or imports`() = runBlocking {
        val (catalog, c, ws) = legacy()
        val directory = File(tempDir(), "Workspaces")
        val first = WorkspaceStorePool(catalog, directory)
        first.store(c, ws).close()
        fun digest() = java.security.MessageDigest.getInstance("SHA-256").digest(first.legacyBackup.readBytes()).joinToString("") { "%02x".format(it) }
        val frozen = digest()
        // The catalog changes afterwards (a late write, another workspace) and another pool, as in a later process, opens a new partition.
        catalog.writer.upsertMessages(listOf(StoreFixtures.message("00000000-0000-4000-8000-0000000000b7", "after the freeze", "2026-10-03T00:00:00.000Z")))
        val ws2 = StoreFixtures.workspace(StoreFixtures.W2, "Second")
        catalog.writer.replaceWorkspaces(c.id, listOf(ws, ws2))
        val later = WorkspaceStorePool(catalog, directory).store(c, ws2)
        assertEquals(frozen, digest())
        assertEquals("a row that arrived after the freeze is not imported", 0, later.reader.messageCount(w))
        assertTrue(File(directory, WorkspaceStorePool.LEGACY_BACKUP_NAME).exists())
    }

    @Test fun `a half-written temporary copy from an interrupted process is removed, a fresh one is left to its owner`() = runBlocking {
        val (catalog, c, ws) = legacy()
        val directory = File(tempDir(), "Workspaces").also { it.mkdirs() }
        val stale = File(directory, "backup-stale.sqlite").also { it.writeText("partial"); it.setLastModified(System.currentTimeMillis() - 3_600_000) }
        val fresh = File(directory, "backup-fresh.sqlite").also { it.writeText("still being copied") }
        WorkspaceStorePool(catalog, directory).store(c, ws).close()
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test fun `identical workspace ids on two Towers never share content and ambiguity is withheld`() = runBlocking {
        val catalog = StoreFixtures.store()
        val a = StoreFixtures.connect(catalog, "https://one.example.com")
        val b = StoreFixtures.connect(catalog, "https://two.example.com", other)
        val ws = StoreFixtures.workspace(w, "Same id")
        catalog.writer.replaceWorkspaces(a.id, listOf(ws)); catalog.writer.replaceWorkspaces(b.id, listOf(ws))
        catalog.writer.upsertMessages(listOf(StoreFixtures.message("00000000-0000-4000-8000-0000000000b1", "whose is this", "2026-10-01T00:00:01.000Z")))
        catalog.writer.enqueueSend(ChatTarget(b.id, w, "00000000-0000-4000-8000-000000000c01"), b.origin, b.signerNpub, ws.appNpub, "for two only", emptyList(), id = "22222222-2222-4222-8222-222222222222")
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val storeA = pool.store(a, ws)
        val storeB = pool.store(b, ws)
        assertNotEquals(storeA.path, storeB.path)
        // The shared cache row has two owners in the frozen backup: it is preserved there and withheld from both.
        assertEquals(0, storeA.reader.messageCount(w)); assertEquals(0, storeB.reader.messageCount(w))
        assertEquals(WorkspaceStorePool.UNATTRIBUTED, storeA.reader.state(WorkspaceStorePool.LEGACY_STATE))
        // The unsent message belongs to exactly one connection's boundary.
        assertTrue(storeA.reader.outbox(a.id, a.signerNpub).isEmpty())
        assertEquals("for two only", storeB.reader.outbox(b.id, b.signerNpub).single().body)
        // New writes land in one partition only.
        storeA.writer.upsertMessages(listOf(StoreFixtures.message("00000000-0000-4000-8000-0000000000b9", "only in A", "2026-10-02T00:00:00.000Z")))
        assertEquals(1, storeA.reader.messageCount(w)); assertEquals(0, storeB.reader.messageCount(w))
    }

    @Test fun `a different application namespace is a different boundary`() {
        val c = ConnectionRecord("c1", "https://t.example.com", TestKeys.NPUB, null, "2026-10-01T00:00:00Z")
        val one = StoreFixtures.workspace(w, "x")
        val two = one.copy(appNpub = other)
        assertNotEquals(WorkspaceBoundary.of(c, one).id, WorkspaceBoundary.of(c, two).id)
        assertEquals(WorkspaceBoundary.of(c, one).connectionNamespace, WorkspaceBoundary.of(c, two).connectionNamespace)
        // Normalised origin: a trailing slash or case change is the same boundary.
        assertEquals(WorkspaceBoundary.of(c, one).id, WorkspaceBoundary.of(c.copy(origin = "https://T.example.com/"), one).id)
    }

    @Test fun `an interrupted import rolls back and a retry completes it`() = runBlocking {
        val (catalog, c, ws) = legacy()
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        // Break the import after the backup exists: a legacy table without the expected columns makes the copy fail.
        pool.store(c, ws).close()
        val boundary = WorkspaceBoundary.of(c, ws)
        val directory = File(pool.directory, boundary.id)
        // Simulate a crash before the marker: remove the marker and the copied rows in a fresh partition.
        directory.deleteRecursively()
        val broken = File(pool.directory, WorkspaceStorePool.LEGACY_BACKUP_NAME)
        val saved = broken.readBytes()
        SqlConnection.open(broken.path).use { it.execute("ALTER TABLE outbox RENAME TO outbox_gone") }
        val failing = WorkspaceStorePool(catalog, pool.directory)
        // outbox is simply absent from the backup, so this import succeeds without it (nothing guessed)...
        failing.store(c, ws).also { assertTrue(it.reader.outbox(c.id, c.signerNpub).isEmpty()); it.close() }
        // ...and a corrupt backup file fails closed, with no partition registered and no catalog fallback.
        File(directory, ProductionStorage.DATABASE_FILE_NAME).delete()
        directory.deleteRecursively()
        broken.writeText("not a database")
        val corrupt = WorkspaceStorePool(catalog, pool.directory)
        assertFailsWith<PartitionUnavailable> { corrupt.store(c, ws) }
        assertTrue(corrupt.openBoundaryIds().isEmpty())
        // The same pool can retry once the backup is sound again.
        broken.writeBytes(saved)
        directory.deleteRecursively()
        val retry = corrupt.store(c, ws)
        assertEquals("unsent", retry.reader.outbox(c.id, c.signerNpub).single().body)
    }

    @Test fun `a failure part-way through the copy leaves nothing behind and the retry brings everything across exactly once`() = runBlocking {
        val (catalog, c, ws) = legacy()
        val directory = File(tempDir(), "Workspaces")
        val setup = WorkspaceStorePool(catalog, directory)
        // Publish the frozen backup without opening a partition, then make a late table of it fail while it is read.
        setup.store(c, StoreFixtures.workspace(StoreFixtures.W2, "Unrelated")).close(); setup.close()
        val backup = File(directory, WorkspaceStorePool.LEGACY_BACKUP_NAME)
        val sound = backup.readBytes()
        SqlConnection.open(backup.path).use { db ->
            db.execute("DROP TABLE pins")
            // Selecting the row overflows (an error inside the import, after the earlier tables were already copied).
            db.execute("""CREATE VIEW pins(connection_id, workspace_id, id, resource_type, resource_id, channel_id, scope_id, pinned, row_version, updated_at, raw)
                AS SELECT '${c.id}', '$w', 'p', 'message', 'm', NULL, NULL, abs(-9223372036854775807 - 1), 1, 'x', '{}'""")
        }
        val pool = WorkspaceStorePool(catalog, directory)
        assertFailsWith<PartitionUnavailable> { pool.store(c, ws) }
        assertTrue(pool.openBoundaryIds().isEmpty())
        // Nothing of the half-done copy survives: the partition has no marker, no messages, no queue, no draft.
        val partitionDirectory = File(directory, WorkspaceBoundary.of(c, ws).id)
        LocalStore(partitionDirectory).use { half ->
            assertNull(half.reader.state(WorkspaceStorePool.MIGRATION_MARKER))
            assertEquals(0, half.reader.messageCount(w))
            assertTrue(half.reader.outbox(c.id, c.signerNpub).isEmpty())
            assertNull(half.reader.draft(target(c)))
        }
        backup.writeBytes(sound)
        val done = pool.store(c, ws)
        assertEquals(1, done.reader.messageCount(w))
        assertEquals("unsent", done.reader.outbox(c.id, c.signerNpub).single().body)
        assertEquals("half typed", done.reader.draft(target(c))?.text)
        assertEquals(WorkspaceStorePool.IMPORTED, done.reader.state(WorkspaceStorePool.LEGACY_STATE))
    }

    @Test fun `disconnect retires only that connection's partitions and a reconnect starts fresh`() = runBlocking {
        val catalog = StoreFixtures.store()
        val a = StoreFixtures.connect(catalog, "https://one.example.com")
        val b = StoreFixtures.connect(catalog, "https://two.example.com")
        val ws = StoreFixtures.workspace(w, "W")
        // The previous build left an unsent message for A in the catalog; the frozen backup keeps it for the life of the install.
        catalog.writer.replaceWorkspaces(a.id, listOf(ws))
        catalog.writer.enqueueSend(ChatTarget(a.id, w, "00000000-0000-4000-8000-000000000c01"), a.origin, a.signerNpub, ws.appNpub, "old queue", emptyList(), id = "33333333-3333-4333-8333-333333333333")
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val storeA = pool.store(a, ws); val storeB = pool.store(b, ws)
        assertEquals("old queue", storeA.reader.outbox(a.id, a.signerNpub).single().body)
        storeA.writer.discardOutbox("33333333-3333-4333-8333-333333333333", restoreDraft = false)
        val order = ArrayList<String>()
        pool.remove(a) { order.add("catalog forgets A"); assertTrue("partition not moved yet", File(pool.directory, WorkspaceBoundary.of(a, ws).id).exists()) }
        // The retired handle rejects stale work without touching anything else.
        assertFailsWith<kotlinx.coroutines.CancellationException> { storeA.reader.messageCount(w) }
        assertEquals(0, storeB.reader.messageCount(w))
        assertEquals(listOf("catalog forgets A"), order)
        assertTrue(File(pool.directory, "Retired").exists())
        assertTrue(File(pool.directory, WorkspaceBoundary.of(b, ws).id).exists())
        // A late enable for the disconnected connection (a listing applied after Disconnect) opens nothing - not even a partition
        // that would import the frozen queue the person already discarded.
        assertFailsWith<kotlinx.coroutines.CancellationException> { pool.store(a, ws) }
        assertTrue(WorkspaceBoundary.of(a, ws).id !in pool.openBoundaryIds())
        // A reconnect is a new connection (new id) over the same boundary: fresh partition, nothing resurrected.
        val again = pool.store(a.copy(id = "00000000-0000-4000-8000-0000000000e1"), ws)
        assertTrue(again.reader.outbox(a.id, a.signerNpub).isEmpty())
        assertTrue(again.reader.outbox("00000000-0000-4000-8000-0000000000e1", a.signerNpub).isEmpty())
        assertNotNull(again.reader.state(WorkspaceStorePool.MIGRATION_MARKER))
    }

    @Test fun `a partition left behind by an interrupted disconnect is retired, never adopted by the next connection`() = runBlocking {
        val catalog = StoreFixtures.store()
        val a = StoreFixtures.connect(catalog, "https://one.example.com")
        val ws = StoreFixtures.workspace(w, "W")
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val orphan = pool.store(a, ws)
        orphan.writer.enqueueSend(ChatTarget(a.id, w, "00000000-0000-4000-8000-000000000c01"), a.origin, a.signerNpub, ws.appNpub, "queued before the interruption", emptyList())
        // The process ended after the catalog dropped the connection and before its partition was moved: a new process, a new connection.
        pool.close()
        // A live connection's partition is never retired because a caller named it by another local id.
        assertFailsWith<PartitionUnavailable> { WorkspaceStorePool(catalog, pool.directory).store(a.copy(id = "00000000-0000-4000-8000-0000000000e3"), ws) }
        assertTrue(File(pool.directory, WorkspaceBoundary.of(a, ws).id).exists())
        assertFalse(File(pool.directory, "Retired").exists())
        catalog.writer.removeConnection(a.id)
        val restarted = WorkspaceStorePool(catalog, pool.directory)
        val reconnected = a.copy(id = "00000000-0000-4000-8000-0000000000e2")
        val fresh = restarted.store(reconnected, ws)
        assertTrue(fresh.reader.outbox(reconnected.id, reconnected.signerNpub).isEmpty())
        assertEquals(reconnected.id, fresh.reader.connections().single().id)
        val retired = File(pool.directory, "Retired").listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
        assertEquals("the orphan is kept aside for recovery", listOf(WorkspaceBoundary.of(a, ws).id), retired.map { it.name })
    }

    @Test fun `the partition row follows the catalog's access answer on open in both directions`() = runBlocking {
        val catalog = StoreFixtures.store()
        val c = StoreFixtures.connect(catalog)
        val ws = StoreFixtures.workspace(w, "W")
        val directory = File(tempDir(), "Workspaces")
        WorkspaceStorePool(catalog, directory).also { it.store(c, ws).close(); it.close() }
        // Access was lost while this workspace was paused (only the catalog heard): the partition reflects it when opened.
        WorkspaceStorePool(catalog, directory).store(c, ws.copy(accessRemoved = true)).also {
            assertTrue(it.reader.workspaces(c.id).single().accessRemoved); it.close()
        }
        // Listed again: the partition no longer claims the workspace is gone (writes still wait for revalidation in the runtime).
        assertFalse(WorkspaceStorePool(catalog, directory).store(c, ws).reader.workspaces(c.id).single().accessRemoved)
    }

    @Test fun `review journals and upload receipts move with their document, only for the exact connection and workspace`() = runBlocking {
        val catalog = StoreFixtures.store()
        val a = StoreFixtures.connect(catalog, "https://one.example.com")
        val b = StoreFixtures.connect(catalog, "https://two.example.com", other)
        val ws = StoreFixtures.workspace(w, "Shared id")
        catalog.writer.replaceWorkspaces(a.id, listOf(ws)); catalog.writer.replaceWorkspaces(b.id, listOf(ws))
        val doc = "00000000-0000-4000-8000-0000000000d1"
        fun key(c: ConnectionRecord, workspace: String = w) = DocDraft.key(c.id, workspace, doc)
        for (c in listOf(a, b)) {
            catalog.writer.setState(DocumentReviewCodec.key(key(c)), "journal-${c.id}")
            catalog.writer.setState(DocumentReviewCodec.corruptKey(key(c)), "corrupt-${c.id}")
            catalog.writer.setState(DocumentReviewCodec.UPLOAD_RECEIPT_PREFIX + key(c), "receipt-${c.id}")
        }
        catalog.writer.setState(DocumentReviewCodec.key(key(a, StoreFixtures.W2)), "another workspace of A")
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val storeA = pool.store(a, ws)
        // The workspace id has two owners (content is withheld) but A's own keyed journal is provably A's.
        assertEquals(WorkspaceStorePool.UNATTRIBUTED, storeA.reader.state(WorkspaceStorePool.LEGACY_STATE))
        assertEquals("journal-${a.id}", storeA.reader.state(DocumentReviewCodec.key(key(a))))
        assertEquals("corrupt-${a.id}", storeA.reader.state(DocumentReviewCodec.corruptKey(key(a))))
        assertEquals("receipt-${a.id}", storeA.reader.state(DocumentReviewCodec.UPLOAD_RECEIPT_PREFIX + key(a)))
        assertNull(storeA.reader.state(DocumentReviewCodec.key(key(b))))
        assertNull(storeA.reader.state(DocumentReviewCodec.key(key(a, StoreFixtures.W2))))
        val storeB = pool.store(b, ws)
        assertEquals("journal-${b.id}", storeB.reader.state(DocumentReviewCodec.key(key(b))))
        assertNull(storeB.reader.state(DocumentReviewCodec.key(key(a))))
    }

    @Test fun `the event cursor moves only together with the cached content it describes`() = runBlocking {
        val catalog = StoreFixtures.store()
        val a = StoreFixtures.connect(catalog, "https://one.example.com")
        val b = StoreFixtures.connect(catalog, "https://two.example.com", other)
        val ws = StoreFixtures.workspace(w, "Shared id")
        catalog.writer.replaceWorkspaces(a.id, listOf(ws))
        catalog.writer.upsertMessages(listOf(StoreFixtures.message("00000000-0000-4000-8000-0000000000b1", "uniquely mine", "2026-10-01T00:00:01.000Z")))
        catalog.writer.setState(StoreStateKeys.eventCursor(a.id, w), "cursor-a")
        catalog.writer.setState("nav-stacks:${a.id}:$w", "route-a")
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val unique = pool.store(a, ws)
        assertEquals("cursor-a", unique.reader.state(StoreStateKeys.eventCursor(a.id, w)))
        assertEquals(1, unique.reader.messageCount(w))
        // The same workspace id is now also listed by a second connection: content is ambiguous, so the cursor must not claim
        // that the (empty) partition is caught up. Navigation state is exactly qualified and still moves.
        catalog.writer.replaceWorkspaces(b.id, listOf(ws))
        val shared = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces2"))
        val withheld = shared.store(a, ws)
        assertEquals(0, withheld.reader.messageCount(w))
        assertNull(withheld.reader.state(StoreStateKeys.eventCursor(a.id, w)))
        assertEquals("route-a", withheld.reader.state("nav-stacks:${a.id}:$w"))
    }
}

class BoardDraftRestoreTests {
    private val w = StoreFixtures.W

    @Test fun uniquelyOwnedBoardDraftsMoveIntoTheirBoundaryAndOnlyOnce() = runBlocking {
        val catalog = StoreFixtures.store()
        val c = StoreFixtures.connect(catalog)
        val ws = StoreFixtures.workspace(w, "Home")
        catalog.writer.replaceWorkspaces(c.id, listOf(ws))
        val boardId = "00000000-0000-4000-8000-0000000000f1"
        val strangerId = "00000000-0000-4000-8000-0000000000f2"
        catalog.writer.upsertFiles(w, listOf(studio.otherstuff.ambulando.core.transport.FileRecord(
            workspaceId = w, id = boardId, scopeId = null, channelId = "00000000-0000-4000-8000-000000000c01", folderId = null, storageObjectId = null,
            displayName = "Board", contentType = null, sizeBytes = null, sha256 = null, createdAt = "2026-10-01T00:00:00.000Z", updatedAt = "2026-10-01T00:00:00.000Z")))
        val legacy = File(tempDir(), "board-drafts")
        val old = studio.otherstuff.ambulando.core.surfaces.BoardDraftStore(legacy)
        val scene = kotlinx.serialization.json.JsonObject(mapOf("elements" to kotlinx.serialization.json.JsonArray(emptyList())))
        old.save(boardId, "00000000-0000-4000-8000-0000000000a1", 3, scene)
        old.save(strangerId, "00000000-0000-4000-8000-0000000000a2", 1, scene)
        val pool = WorkspaceStorePool(catalog, File(tempDir(), "Workspaces"))
        val partition = pool.store(c, ws)
        val destination = File(tempDir(), "board-drafts/${studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary.of(c, ws).id}")
        assertEquals(1, studio.otherstuff.ambulando.core.surfaces.BoardDraftRestore.restore(partition, legacy, destination))
        val moved = studio.otherstuff.ambulando.core.surfaces.BoardDraftStore(destination)
        assertEquals(3, moved.load(boardId)!!.baseVersionNumber)
        assertNull("a draft for a board this workspace does not have is not guessed into it", moved.load(strangerId))
        // The original stays for recovery, and a discarded edit is not brought back.
        assertNotNull(old.load(boardId))
        moved.delete(boardId)
        assertEquals(0, studio.otherstuff.ambulando.core.surfaces.BoardDraftRestore.restore(partition, legacy, destination))
        assertNull(moved.load(boardId))
    }
}
