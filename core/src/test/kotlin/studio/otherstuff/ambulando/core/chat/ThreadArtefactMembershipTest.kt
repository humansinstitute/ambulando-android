package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.transport.TowerError
import java.util.UUID
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.ThreadRecord

class ThreadArtefactMembershipTest {
    private fun member(kind: String = "doc") = ThreadArtefactMember(kind, UUID.randomUUID().toString())
    private class Server : ThreadArtefactMembershipSync.Transport {
        var row = ThreadArtefactMembershipSync.Row(1, buildJsonObject { put("unrelated", "preserved") })
        var beforeWrite: (() -> Unit)? = null
        var lostResponse = false
        var responseFailure: Exception? = null
        var afterCommit: (() -> Unit)? = null
        var writes = 0
        var denied = false
        var offline = false
        val inaccessible = mutableSetOf<ThreadArtefactMember>()
        override suspend fun read(): ThreadArtefactMembershipSync.Row { check(!offline) { "Offline" }; return row }
        override suspend fun verify(member: ThreadArtefactMember) { check(!offline); if (member in inaccessible) throw TowerError.Http(403, "denied", null) }
        override suspend fun write(base: ThreadArtefactMembershipSync.Row, metadata: JsonObject): ThreadArtefactMembershipSync.Row {
            if (denied) throw TowerError.Http(403, "denied", null)
            beforeWrite?.also { beforeWrite = null; it() }
            if (base.version != row.version) throw TowerError.Http(409, "stale_row_version", null)
            writes++
            row = ThreadArtefactMembershipSync.Row(row.version + 1, metadata)
            afterCommit?.also { afterCommit = null; it() }
            responseFailure?.also { responseFailure = null; throw it }
            if (lostResponse) { lostResponse = false; error("Response dropped") }
            return row
        }
    }
    @Test fun twoClientsDiscoverAndRemoveSharedMembersWithoutSourceDeletion() = runBlocking {
        val server = Server(); val a = ThreadArtefactMembershipSync(server); val b = ThreadArtefactMembershipSync(server)
        val doc = member(); val board = member("file"); val html = member("file")
        a.add(doc); a.add(board); a.add(html)
        assertEquals(listOf(doc, board, html), ThreadArtefactMembership.read(b.refresh().metadata))
        val version = server.row.version
        a.add(doc); assertEquals(version, server.row.version)
        b.remove(board); assertEquals(listOf(doc, html), ThreadArtefactMembership.read(a.refresh().metadata))
        a.remove(doc); b.remove(html)
        assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read(a.refresh().metadata))
        assertEquals(JsonPrimitive("preserved"), server.row.metadata["unrelated"])
    }
    @Test fun staleAddsAndRemovesPreserveConcurrentItemsAndUnrelatedFields() = runBlocking {
        val server = Server(); val sync = ThreadArtefactMembershipSync(server)
        val first = member(); val second = member(); val third = member()
        sync.add(first)
        server.beforeWrite = { server.row = ThreadArtefactMembershipSync.Row(server.row.version + 1,
            ThreadArtefactMembership.metadata(JsonObject(server.row.metadata + ("newField" to JsonPrimitive(7))), listOf(first, second))) }
        sync.add(third)
        assertEquals(listOf(first, second, third), ThreadArtefactMembership.read(server.row.metadata))
        server.beforeWrite = { server.row = ThreadArtefactMembershipSync.Row(server.row.version + 1,
            ThreadArtefactMembership.metadata(server.row.metadata, listOf(first, second))) }
        sync.remove(first)
        assertEquals(listOf(second), ThreadArtefactMembership.read(server.row.metadata))
        assertEquals(JsonPrimitive(7), server.row.metadata["newField"])
    }
    @Test fun migrationLosingToInitializedEmptyNeverResurrectsLegacyCache() = runBlocking {
        val server = Server(); val sync = ThreadArtefactMembershipSync(server); val old = member()
        server.beforeWrite = { server.row = ThreadArtefactMembershipSync.Row(2, ThreadArtefactMembership.metadata(server.row.metadata, emptyList())) }
        assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read(sync.migrate(listOf(old)).metadata))
        assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read(sync.migrate(listOf(old)).metadata))
        val fresh = Server(); ThreadArtefactMembershipSync(fresh).migrate(emptyList())
        assertNull(ThreadArtefactMembership.read(fresh.row.metadata))
    }
    @Test fun ambiguousCommitReadbackAndDeniedOfflineWritesAreTruthful() = runBlocking {
        val server = Server(); val sync = ThreadArtefactMembershipSync(server); val item = member()
        server.lostResponse = true; sync.add(item); assertEquals(2, server.row.version)
        server.denied = true
        assertTrue(runCatching { sync.remove(item) }.isFailure)
        assertEquals(listOf(item), ThreadArtefactMembership.read(server.row.metadata))
        server.denied = false; server.offline = true
        assertTrue(runCatching { sync.remove(item) }.isFailure)
        server.offline = false; val private = member(); server.inaccessible.add(private)
        assertTrue(runCatching { sync.add(private) }.isFailure)
        assertEquals(listOf(item), ThreadArtefactMembership.read(server.row.metadata))
    }
    @Test fun futureSchemasAndOversizeMembershipFailClosed() = runBlocking {
        val server = Server(); val sync = ThreadArtefactMembershipSync(server)
        server.row = ThreadArtefactMembershipSync.Row(1, buildJsonObject { put(ThreadArtefactMembership.KEY, buildJsonObject { put("version", 2); put("items", JsonArray(emptyList())) }) })
        assertTrue(runCatching { sync.add(member()) }.isFailure)
        assertTrue(runCatching { ThreadArtefactMembership.metadata(JsonObject(emptyMap()), List(33) { member() }) }.isFailure)
    }
    @Test fun independentSQLiteStoresDiscoverCanonicalMembershipAfterReopen() = runBlocking {
        val server = Server(); val workspace = UUID.randomUUID().toString(); val channel = UUID.randomUUID().toString(); val thread = UUID.randomUUID().toString()
        val first = StoreFixtures.store(); val second = StoreFixtures.store()
        fun client(store: LocalStore) = ThreadArtefactMembershipSync(object : ThreadArtefactMembershipSync.Transport {
            suspend fun materialize(row: ThreadArtefactMembershipSync.Row): ThreadArtefactMembershipSync.Row {
                val raw = buildJsonObject { put("id", thread); put("workspace_id", workspace); put("channel_id", channel); put("row_version", row.version); put("updated_at", "2026-11-26T00:00:00Z"); put("metadata", row.metadata) }
                store.writer.upsertThreads(workspace, listOf(ThreadRecord.parse(raw)))
                return row
            }
            override suspend fun read() = materialize(server.read())
            override suspend fun write(base: ThreadArtefactMembershipSync.Row, metadata: JsonObject) = materialize(server.write(base, metadata))
            override suspend fun verify(member: ThreadArtefactMember) = server.verify(member)
        })
        try {
            val doc = member(); val board = member("file"); val html = member("file")
            val a = client(first); a.add(doc); a.add(board); a.add(html)
            assertNull(second.reader.thread(workspace, thread))
            val b = client(second); b.refresh()
            assertEquals(listOf(doc, board, html), ThreadArtefactMembership.read(second.reader.thread(workspace, thread)!!.raw.getValue("metadata").jsonObject))
            b.remove(board); a.refresh()
            assertEquals(listOf(doc, html), ThreadArtefactMembership.read(first.reader.thread(workspace, thread)!!.raw.getValue("metadata").jsonObject))
            b.remove(doc); b.remove(html); client(first).refresh()
            assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read(first.reader.thread(workspace, thread)!!.raw.getValue("metadata").jsonObject))
        } finally { first.close(); second.close() }
    }
    @Test fun ambiguousCommittedAddThenRemoteRemoveNeverResurrectsAndRemovalThenAddNeverRedetaches() = runBlocking {
        val server = Server(); val sync = ThreadArtefactMembershipSync(server); val item = member()
        server.lostResponse = true
        server.afterCommit = { server.row = ThreadArtefactMembershipSync.Row(server.row.version + 1, ThreadArtefactMembership.metadata(server.row.metadata, emptyList())) }
        val addFailure = runCatching { sync.add(item) }.exceptionOrNull()
        assertTrue(addFailure is ThreadArtefactMembershipSync.Uncertain)
        assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read((addFailure as ThreadArtefactMembershipSync.Uncertain).observed.metadata))
        assertEquals(1, server.writes)
        sync.add(item)
        server.lostResponse = true
        server.afterCommit = { server.row = ThreadArtefactMembershipSync.Row(server.row.version + 1, ThreadArtefactMembership.metadata(server.row.metadata, listOf(item))) }
        val removeFailure = runCatching { sync.remove(item) }.exceptionOrNull()
        assertTrue(removeFailure is ThreadArtefactMembershipSync.Uncertain)
        assertEquals(listOf(item), ThreadArtefactMembership.read((removeFailure as ThreadArtefactMembershipSync.Uncertain).observed.metadata))
        assertEquals(3, server.writes)
    }
    @Test fun committed502AndMalformedSuccessRequireCanonicalReadbackWithoutReplay() = runBlocking {
        val server = Server(); val sync = ThreadArtefactMembershipSync(server); val item = member()
        server.responseFailure = TowerError.Http(502, "proxy_failure", null)
        assertEquals(listOf(item), ThreadArtefactMembership.read(sync.add(item).metadata))
        assertEquals(1, server.writes)
        server.responseFailure = TowerError.Malformed("thread")
        assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read(sync.remove(item).metadata))
        assertEquals(2, server.writes)
        server.responseFailure = TowerError.Http(502, "proxy_failure", null)
        server.afterCommit = { server.row = ThreadArtefactMembershipSync.Row(server.row.version + 1, ThreadArtefactMembership.metadata(server.row.metadata, emptyList())) }
        assertTrue(runCatching { sync.add(item) }.exceptionOrNull() is ThreadArtefactMembershipSync.Uncertain)
        assertEquals(3, server.writes)
        assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read(server.row.metadata))
    }
    @Test fun macAndroidPortableContractCasesRoundTripWithoutLeakingLocalState() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/thread-artefacts/membership-v1.json")).readText()).jsonObject
        assertNull(ThreadArtefactMembership.read(cases.getValue("absent").jsonObject))
        assertEquals(emptyList<ThreadArtefactMember>(), ThreadArtefactMembership.read(cases.getValue("initialized_empty").jsonObject))
        val populated = cases.getValue("doc_board_html").jsonObject
        val members = ThreadArtefactMembership.read(populated)!!
        assertEquals(listOf("doc", "file", "file"), members.map { it.kind })
        assertEquals(populated, ThreadArtefactMembership.metadata(populated, members))
        assertEquals(populated.getValue("other_app"), ThreadArtefactMembership.metadata(populated, emptyList()).getValue("other_app"))
        assertTrue(runCatching { ThreadArtefactMembership.read(cases.getValue("future_version").jsonObject) }.isFailure)
    }
}
