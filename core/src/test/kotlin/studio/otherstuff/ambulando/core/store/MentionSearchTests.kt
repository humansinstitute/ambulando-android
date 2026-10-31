package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.MentionType
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.message
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.DocRecord
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.transport.TaskRecord
import studio.otherstuff.ambulando.core.transport.ThreadRecord

class MentionSearchTests {
    private val at = "2026-10-01T00:00:00.000Z"
    /** A synthetic npub (fixed bytes, not a real key). */
    private val agentNpub = Bech32.encode("npub", ByteArray(32) { 0x22 })

    private fun task(id: String, title: String, updated: String = at, workspace: String = W) = TaskRecord(
        workspaceId = workspace, id = id, scopeId = Fixtures.SCOPE_A, channelId = Fixtures.CHANNEL_1, title = title, description = null,
        state = "new", priority = "sand", assignedToNpub = null, rowVersion = 1, createdAt = updated, updatedAt = updated,
        raw = Fixtures.obj(mapOf("id" to id, "title" to title, "row_version" to 1)),
    )

    private fun doc(id: String, title: String, rowVersion: Long = 1, updated: String = at) = DocRecord(
        workspaceId = W, id = id, scopeId = Fixtures.SCOPE_A, channelId = Fixtures.CHANNEL_1, storageObjectId = "obj-$id", title = title, summary = null,
        rowVersion = rowVersion, createdAt = updated, updatedAt = updated, raw = Fixtures.obj(mapOf("id" to id, "title" to title, "row_version" to rowVersion)),
    )

    private suspend fun seed(store: LocalStore) {
        store.writer.upsertScopes(W, listOf(StoreFixtures.scope(Fixtures.SCOPE_A, "Roadmap scope")), complete = false)
        store.writer.upsertChannels(W, Fixtures.SCOPE_A, listOf(StoreFixtures.channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "general")), complete = false)
        store.writer.replaceMembers(W, listOf(
            MemberRecord(W, "a1", TestKeys.NPUB, "human", "Alex Rivera"),
            MemberRecord(W, "a2", agentNpub, "agent", "Roadie"),
        ))
        store.writer.upsertDocs(W, listOf(doc("d1", "Quarterly roadmap"), doc("d2", "Meeting notes", updated = "2026-10-02T00:00:00.000Z")))
        store.writer.upsertTasks(W, listOf(task("t1", "Ship the roadmap deck"), task("t2", "Book travel"), task("tx", "Roadmap elsewhere", workspace = StoreFixtures.W2)))
        store.writer.upsertMessages(listOf(message("m1", "Kick-off for the **launch** plan", at, thread = "th1", source = "m1")))
        store.writer.upsertThreads(W, listOf(ThreadRecord(W, "th1", Fixtures.CHANNEL_1, "m1", null, updatedAt = at)))
    }

    @Test fun everyTypeFiltersOnItsNameWithCanonicalIds() = runBlocking {
        StoreFixtures.store().use { store ->
            seed(store)
            val road = store.reader.mentionCandidates(W, "road")
            assertEquals(
                listOf(MentionType.AGENT, MentionType.DOC, MentionType.TASK, MentionType.SCOPE),
                road.map { it.mention.type },
            )
            assertEquals("d1", road.first { it.mention.type == MentionType.DOC }.mention.id)
            assertEquals("another workspace's task is never offered", listOf("t1"), road.filter { it.mention.type == MentionType.TASK }.map { it.mention.id })
            assertEquals("@[Quarterly roadmap](mention:doc:d1)", road.first { it.mention.type == MentionType.DOC }.mention.token)

            val launch = store.reader.mentionCandidates(W, "launch").single()
            assertEquals(MentionType.CHAT, launch.mention.type)
            assertEquals("a thread is named by its first message and referenced as channel#root", "${Fixtures.CHANNEL_1}#m1", launch.mention.id)
            assertEquals("Kick-off for the launch plan", launch.mention.label)

            assertEquals(listOf(MentionType.PERSON), store.reader.mentionCandidates(W, "ALEX").map { it.mention.type })
            assertEquals(listOf(MentionType.CHANNEL), store.reader.mentionCandidates(W, "gener").map { it.mention.type })
        }
    }

    @Test fun typePrefixesBareAtAndShortQueries() = runBlocking {
        StoreFixtures.store().use { store ->
            seed(store)
            assertEquals(listOf("t1"), store.reader.mentionCandidates(W, "task:road").map { it.mention.id })
            assertEquals(listOf("d2", "d1"), store.reader.mentionCandidates(W, "doc:").map { it.mention.id })
            assertTrue(store.reader.mentionCandidates(W, "person:").all { it.mention.type.isActor })
            val bare = store.reader.mentionCandidates(W, "")
            assertTrue(bare.size <= MentionSearch.DEFAULT_LIMIT)
            assertEquals("people first, then the most recent document", MentionType.PERSON, bare.first().mention.type)
            assertEquals("d2", bare.first { it.mention.type == MentionType.DOC }.mention.id)
            assertEquals("two letters read the tables directly", listOf("t2"), store.reader.mentionCandidates(W, "task:bo").map { it.mention.id })
            assertTrue("FTS syntax in the input is literal text", store.reader.mentionCandidates(W, "\" OR *").isEmpty())
        }
    }

    @Test fun indexFollowsRenamesRemovalAndArchiving() = runBlocking {
        StoreFixtures.store().use { store ->
            seed(store)
            store.writer.upsertDocs(W, listOf(doc("d1", "Quarterly plan", rowVersion = 2)))
            assertTrue(store.reader.mentionCandidates(W, "doc:roadmap").isEmpty())
            assertEquals(listOf("d1"), store.reader.mentionCandidates(W, "doc:rterly").map { it.mention.id })
            store.writer.upsertDocs(W, emptyList(), completeChannel = Fixtures.CHANNEL_1)
            assertTrue("records no longer available are not offered", store.reader.mentionCandidates(W, "doc:quarter").isEmpty())
        }
    }

    @Test fun lookaheadStaysFastOverLargeLocalLists() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertTasks(W, (0 until 20_000).map { task("t$it", "Task number $it about topic ${it % 97}") })
            store.writer.upsertDocs(W, (0 until 5_000).map { doc("d$it", "Document $it on subject ${it % 31}") })
            store.reader.mentionCandidates(W, "warm")
            val started = System.nanoTime()
            repeat(20) { store.reader.mentionCandidates(W, "topic 4${it % 10}") }
            val perQueryMs = (System.nanoTime() - started) / 20 / 1_000_000.0
            assertTrue("indexed lookahead took $perQueryMs ms per query", perQueryMs < 50)
            assertEquals(4, store.reader.mentionCandidates(W, "topic 42").count { it.mention.type == MentionType.TASK })
        }
    }

    @Test fun bareAtListsWhomThePersonTaggedMostRecentlyFirst() = runBlocking {
        StoreFixtures.store().use { store ->
            seed(store)
            val me = "npub1viewer"
            store.writer.upsertMessages(listOf(
                message("r1", "ping @[Alex Rivera](mention:person:${TestKeys.NPUB})", "2026-10-03T00:00:00.000Z", sender = me),
                message("r2", "ask @[Old Label](mention:agent:$agentNpub) and @[Gone](mention:person:npub1gone)", "2026-10-04T00:00:00.000Z", sender = me),
                message("r3", "someone else tagged @[Alex Rivera](mention:person:${TestKeys.NPUB})", "2026-10-05T00:00:00.000Z", sender = "npub1other"),
            ))
            val bare = store.reader.mentionCandidates(W, "", viewerNpub = me)
            // Newest message first; each identity once, under its current directory name; people no longer listed are not offered.
            assertEquals(listOf(agentNpub, TestKeys.NPUB), bare.take(2).map { it.mention.npub })
            assertEquals("Roadie", bare.first().mention.label)
            assertTrue(bare.none { it.mention.npub == "npub1gone" })
            assertEquals("each identity appears once", bare.size, bare.distinctBy { it.mention.type to it.mention.npub }.size)
            // A typed query is unchanged, and without a viewer the bare list is the usual default.
            assertEquals(listOf(MentionType.PERSON), store.reader.mentionCandidates(W, "alex", viewerNpub = me).map { it.mention.type })
            assertEquals(store.reader.mentionCandidates(W, ""), store.reader.mentionCandidates(W, "", viewerNpub = "npub1nobody"))
        }
    }
}
