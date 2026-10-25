package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.context.WorkspaceSelection
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.W2
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.support.tempDir
import java.io.File

class StoreTests {
    @Test fun migrationIsIdempotentAndDataSurvivesReopen() = runBlocking {
        val dir = tempDir()
        StoreFixtures.store(dir).use { store ->
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Alpha")), complete = true)
            store.writer.setState("selected-workspace", W)
        }
        StoreFixtures.store(dir).use { reopened ->
            assertEquals(Schema.VERSION, reopened.writer.schemaVersion())
            assertEquals(listOf("Alpha"), reopened.reader.scopes(W).map { it.name })
            assertEquals(W, reopened.reader.state("selected-workspace"))
        }
    }

    @Test fun laterMigrationsRunOnceAboveTheStoredVersion() {
        SqlConnection.memory().use { db ->
            Schema.migrate(db)
            assertEquals(Schema.VERSION, db.userVersion)
            val next = Schema.migrations + Schema.Migration(Schema.VERSION + 1, listOf("CREATE TABLE future_table(id TEXT PRIMARY KEY)"))
            Schema.migrate(db, next)
            Schema.migrate(db, next)   // already applied: no "table exists" error
            assertEquals(Schema.VERSION + 1, db.userVersion)
            assertEquals(0L, db.longValue("SELECT COUNT(*) FROM future_table"))
        }
    }

    @Test fun rowVersionAndTimestampGuardPreventsStaleOverwrite() = runBlocking {
        StoreFixtures.store().use { store ->
            val w = store.writer
            assertEquals(1, w.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "New", rowVersion = 3)), complete = false))
            assertEquals(0, w.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Stale", rowVersion = 2)), complete = false))
            assertEquals("identical payload is a no-op", 0, w.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "New", rowVersion = 3)), complete = false))
            // Tower serializes scopes/channels with row_version 1 always, so updated_at must order them.
            assertEquals(0, w.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Old", rowVersion = 3, updated = "2026-09-01T00:00:00.000Z")), complete = false))
            assertEquals(1, w.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Renamed", rowVersion = 3, updated = "2026-11-01T00:00:00.000Z")), complete = false))
            assertEquals(listOf("Renamed"), store.reader.scopes(W).map { it.name })
        }
    }

    @Test fun messageRowVersionGuardKeepsTheNewerRow() = runBlocking {
        StoreFixtures.store().use { store ->
            val at = "2026-10-01T00:00:00.000Z"
            store.writer.upsertMessages(listOf(StoreFixtures.message("m1", "edited", at, rowVersion = 2)))
            assertEquals(0, store.writer.upsertMessages(listOf(StoreFixtures.message("m1", "original", at, rowVersion = 1))))
            assertEquals("edited", store.reader.message(W, "m1")?.body)
        }
    }

    @Test fun workspaceIsolation() = runBlocking {
        StoreFixtures.store().use { store ->
            val connection = StoreFixtures.connect(store)
            val w1 = StoreFixtures.workspace(W, "One")
            val w2 = StoreFixtures.workspace(W2, "Two")
            store.writer.replaceWorkspaces(connection.id, listOf(w1, w2))
            // The same scope id under two workspaces stays two independent rows.
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "In one", W)), complete = true)
            store.writer.upsertScopes(W2, listOf(scope(Fixtures.SCOPE_A, "In two", W2)), complete = true)
            // A record claiming another workspace is ignored rather than written across the boundary.
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_B, "Smuggled", W2)), complete = false)
            assertEquals(listOf("In one"), store.reader.scopes(W).map { it.name })
            assertEquals(listOf("In two"), store.reader.scopes(W2).map { it.name })
            assertEquals(listOf("One", "Two"), store.reader.workspaces(connection.id).map { it.label })
        }
    }

    @Test fun connectionIsBoundToOriginAndSigner() = runBlocking {
        StoreFixtures.store().use { store ->
            val first = StoreFixtures.connect(store)
            val again = store.writer.saveConnection(ConnectionRecord.create(studio.otherstuff.ambulando.core.transport.TowerOrigin("https://tower.example.com"),
                TestKeys.NPUB, TestKeys.syntheticNpub(0x31)))
            assertEquals("same origin+signer reuses the connection", first.id, again.id)
            val other = StoreFixtures.connect(store, signer = TestKeys.syntheticNpub(0x32))
            assertNotEquals(first.id, other.id)
            assertEquals(2, store.reader.connections().size)
        }
    }

    @Test fun messagesOrderedNewestWindowOldestFirst() = runBlocking {
        StoreFixtures.store().use { store ->
            val records = (0 until 10).map { i -> StoreFixtures.message("m%02d".format(i), "b$i", "2026-10-01T00:00:%02d.000Z".format(i)) }
            assertEquals(10, store.writer.upsertMessages(records.shuffled()))
            assertEquals(listOf("b7", "b8", "b9"), store.reader.latestMessages(W, Fixtures.CHANNEL_1, 3).map { it.body })
        }
    }

    @Test fun rawJsonIsStoredCanonicallyAndKeepsUnknownFields() = runBlocking {
        StoreFixtures.store().use { store ->
            val record = studio.otherstuff.ambulando.core.transport.MessageRecord.parse(Fixtures.obj(Fixtures.message(Fixtures.newId(), body = "hello")))
            store.writer.upsertMessages(listOf(record))
            val stored = store.reader.message(W, record.id)!!
            assertEquals(record.raw, stored.raw)
            assertTrue(stored.raw.containsKey("future_field"))
            assertEquals(0, store.writer.upsertMessages(listOf(record)))
        }
    }

    private suspend fun ReceiveChannel<Set<ChangeKey>>.next(): Set<ChangeKey> = withTimeout(2_000) { receive() }

    @Test fun writesEmitCoalescedChangesAndNoOpsEmitNothing() = runBlocking {
        StoreFixtures.store(intervalMillis = 20).use { store ->
            val stream = store.changes.subscribe()
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "A")), complete = false)
            assertEquals(setOf(ChangeKey(StoreTable.SCOPES, W), ChangeKey(StoreTable.CHANNELS, W)), stream.next())
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "A")), complete = false)
            store.writer.setState("k", "v")
            assertEquals("the identical scope upsert produced no change", setOf(ChangeKey(StoreTable.APP_STATE)), stream.next())
            stream.cancel()
            waitFor { store.changes.observerCount == 0 }
        }
    }

    @Test fun writerTransactionsRollBackAsAWhole() = runBlocking {
        StoreFixtures.store().use { store ->
            val good = StoreFixtures.message("m1", "kept out", "2026-10-01T00:00:00.000Z")
            // A failing statement inside one writer step leaves nothing from that step behind.
            runCatching { store.writer.write { writeMessages(listOf(good)); db.execute("INSERT INTO no_such_table VALUES(1)") } }
            assertEquals(0, store.reader.messageCount(W))
        }
    }

    @Test fun coalescerBurstIsRateLimitedAndLossless() = runBlocking {
        ChangeCoalescer(100).use { coalescer ->
            val stream = coalescer.subscribe()
            val batches = ArrayList<Set<ChangeKey>>()
            val collector = launch {
                val seen = HashSet<ChangeKey>()
                while (seen.size < 2_000) { val b = stream.receive(); batches.add(b); seen.addAll(b) }
            }
            val start = System.nanoTime()
            for (i in 0 until 2_000) {
                coalescer.record(setOf(ChangeKey(StoreTable.MESSAGES, "w$i")))
                if (i % 200 == 0) delay(50)
            }
            withTimeout(5_000) { collector.join() }
            val seconds = (System.nanoTime() - start) / 1e9
            assertEquals("no key lost", 2_000, batches.flatten().toSet().size)
            assertTrue("≤ 10 emissions per second (${batches.size} in ${"%.2f".format(seconds)} s)", batches.size <= Math.ceil(seconds * 10) + 1)
            stream.cancel()
        }
    }

    @Test fun revisionsCountRecordedChangesPerTableSynchronously() {
        ChangeCoalescer(1_000).use { c ->
            val before = c.revisions.value(setOf(StoreTable.ACCESS))
            c.record(setOf(ChangeKey(StoreTable.ACCESS, W), ChangeKey(StoreTable.ACCESS, W2), ChangeKey(StoreTable.MESSAGES, W)))
            assertEquals("one bump per table per record call", before + 1, c.revisions.value(setOf(StoreTable.ACCESS)))
            assertEquals(1L, c.revisions.value(setOf(StoreTable.MESSAGES)))
        }
    }

    @Test fun removingAConnectionDeletesItsPrivateRowsAndUnsharedWorkspaces() = runBlocking {
        StoreFixtures.store().use { store ->
            val mine = StoreFixtures.connect(store)
            val other = StoreFixtures.connect(store, origin = "https://other.example.com")
            store.writer.replaceWorkspaces(mine.id, listOf(StoreFixtures.workspace(W, "Shared"), StoreFixtures.workspace(W2, "Mine only")))
            store.writer.replaceWorkspaces(other.id, listOf(StoreFixtures.workspace(W, "Shared")))
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Shared scope")), complete = true)
            store.writer.upsertScopes(W2, listOf(scope(Fixtures.SCOPE_A, "Private scope", W2)), complete = true)
            store.writer.saveDraft(ChatTarget(mine.id, W2, Fixtures.CHANNEL_1), Draft("draft", emptyList()))
            store.writer.removeConnection(mine.id)
            assertEquals(listOf("Shared scope"), store.reader.scopes(W).map { it.name })
            assertTrue(store.reader.scopes(W2).isEmpty())
            assertEquals(null, store.reader.draft(ChatTarget(mine.id, W2, Fixtures.CHANNEL_1)))
            assertEquals(null, store.reader.state(WorkspaceSelection.listedKey(mine.id)))
            assertEquals(listOf(other.id), store.reader.connections().map { it.id })
        }
    }
}

class AccountRetentionTests {
    /** The production names. Renaming any of them strands existing data. */
    @Test fun productionStorageNamesArePinned() {
        assertEquals("AmbulandoNative", ProductionStorage.DATA_DIRECTORY_NAME)
        assertEquals("Store", ProductionStorage.STORE_DIRECTORY_NAME)
        assertEquals("ambulando.sqlite", ProductionStorage.DATABASE_FILE_NAME)
        assertEquals("Identity/vault.json", ProductionStorage.VAULT_PATH)
        assertEquals("studio.otherstuff.ambulando.native.identity-vault.v1", ProductionStorage.VAULT_SECRET_SERVICE)
        assertEquals("studio.otherstuff.ambulando.native.background-read.v1", ProductionStorage.BACKGROUND_READ_SERVICE)
        assertEquals("studio.otherstuff.ambulando.native.session.v1", ProductionStorage.SESSION_SERVICE)
        assertEquals("AmbulandoFiles", ProductionStorage.FILE_CACHE_DIRECTORY_NAME)
        assertEquals("AmbulandoAvatars", ProductionStorage.AVATAR_CACHE_DIRECTORY_NAME)
        val base = File("/data/user/0/app/no_backup")
        val data = ProductionStorage.dataDirectory(base)
        assertEquals("/data/user/0/app/no_backup/AmbulandoNative/Identity/vault.json", ProductionStorage.vaultFile(data).path)
        assertEquals("/data/user/0/app/no_backup/AmbulandoNative/Store", ProductionStorage.storeDirectory(data).path)
    }

    /** Everything a running build leaves behind is found again by a new process at the same paths. */
    @Test fun relaunchKeepsConnectionSelectionsDraftsAndUnsentWork() = runBlocking {
        val data = tempDir()
        val target: ChatTarget
        val draftTarget: ChatTarget
        val connectionId: String
        LocalStore(ProductionStorage.storeDirectory(data)).use { store ->
            val connection = StoreFixtures.connect(store)
            connectionId = connection.id
            val workspace = StoreFixtures.workspace(W, "Studio")
            store.writer.replaceWorkspaces(connection.id, listOf(workspace))
            store.writer.setState("selected.connection", connection.id)
            store.writer.setState("selected.workspace:${connection.id}", workspace.id)
            target = ChatTarget(connection.id, workspace.id, Fixtures.CHANNEL_1)
            // Sending clears that conversation's draft, so the draft is kept in another channel.
            draftTarget = ChatTarget(connection.id, workspace.id, Fixtures.CHANNEL_2)
            store.writer.saveDraft(draftTarget, Draft("half-written reply", emptyList()))
            store.writer.enqueueSend(target, connection.origin, TestKeys.NPUB, workspace.appNpub, "sent while offline", emptyList())
        }
        LocalStore(ProductionStorage.storeDirectory(data)).use { store ->
            assertTrue(File(ProductionStorage.storeDirectory(data), ProductionStorage.DATABASE_FILE_NAME).exists())
            assertEquals(listOf(connectionId), store.reader.connections().map { it.id })
            assertEquals(connectionId, store.reader.state("selected.connection"))
            assertEquals(W, store.reader.state("selected.workspace:$connectionId"))
            assertEquals("half-written reply", store.reader.draft(draftTarget)?.text)
            assertEquals(listOf("sent while offline"), store.reader.outbox(target).map { it.body })
            assertEquals(Schema.VERSION, store.writer.schemaVersion())
        }
    }
}
