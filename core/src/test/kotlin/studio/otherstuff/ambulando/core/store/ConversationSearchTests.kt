package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.store
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.W2
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.store.StoreFixtures.message
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.ThreadRecord

class ConversationSearchTests {
    @Test fun controlledParserSupportsScopedClausesAndHelpfulFailures() {
        val q = ConversationQuery.parse("title:deployment checklist body:”free text”")
        assertNull(q.error)
        assertEquals(listOf("title : \"deployment\"", "\"checklist\"", "body : \"free text\""), q.terms.map { it.match })
        assertNotNull(ConversationQuery.parse("title:").error)
        assertNull(ConversationQuery.parse("title: \"deployment checklist\"").error)
        assertNotNull(ConversationQuery.parse("foo\"bar\"").error)
        assertNotNull(ConversationQuery.parse("\"foo\"bar").error)
        assertNotNull(ConversationQuery.parse("author:rick").error)
        assertNotNull(ConversationQuery.parse("\"unclosed").error)
        assertNotNull(ConversationQuery.parse("***").error)
        assertEquals("\"OR\"", ConversationQuery.parse("OR").terms.single().match)
        assertEquals(listOf("\"deploy\"", "\"check\""), ConversationQuery.parse("deploy-check").terms.map { it.match })
        assertNotNull(ConversationQuery.parse("a".repeat(2049)).error)
    }

    @Test fun conversationTermsSplitAcrossTitleAndRepliesButPhrasesStayWithinOneRecord() = runBlocking {
        store().use { s ->
            val ch = Fixtures.CHANNEL_1
            val sc = Fixtures.SCOPE_A
            s.writer.upsertScopes(W, listOf(scope(sc, "Scope")), complete = true)
            s.writer.upsertChannels(W, sc, listOf(channel(ch, sc, "general")), complete = true)
            fun thread(title: String, version: Long = 1) = ThreadRecord(W, "thread", ch, "root", title, null, false, false, version, "2026-10-01T00:00:00Z", Fixtures.obj(mapOf("id" to "thread", "title" to title, "row_version" to version)))
            s.writer.upsertThreads(W, listOf(thread("deployment guide")))
            val root = message("root", "first message", "2026-10-01T00:00:00Z", thread = "thread", source = "root", sender = "other")
            val reply = message("reply", "checklist free text", "2026-10-02T00:00:00Z", thread = "thread", source = "root")
            s.writer.upsertMessages(listOf(root, reply))
            suspend fun search(q: String, channels: Set<String> = setOf(ch), workspace: String = W, filter: ConversationFilter = ConversationFilter.ALL) =
                s.reader.conversations(workspace, channels, TestKeys.NPUB, q, filter)
            assertEquals(1, search("deployment checklist").size)
            assertEquals(1, search("first checklist").size)
            assertEquals(0, search("\"first checklist\"").size)
            assertEquals(1, search("title:deployment checklist").size)
            assertEquals(0, search("\"deployment checklist\"").size)
            assertEquals(1, search("body:”free text”").size)
            assertEquals(0, search("title:\"free text\"").size)
            assertEquals(0, search("deploy").size)
            assertEquals("reply", search("checklist").single().hitMessageId)
            assertTrue(search("checklist").single().excerpt.contains("⟦checklist⟧"))
            assertEquals(1, search("", filter = ConversationFilter.MINE).size)
            assertEquals(0, search("", filter = ConversationFilter.UNREAD).size)
            assertEquals(0, search("checklist", emptySet()).size)
            assertEquals(0, search("checklist", workspace = W2).size)
            s.writer.upsertThreads(W, listOf(thread("renamed title", 2)))
            assertEquals(0, search("deployment").size)
            assertEquals(1, search("title:renamed").size)
            s.writer.upsertMessages(listOf(reply.copy(body = "edited body", rowVersion = 2, raw = Fixtures.obj(mapOf("id" to "reply", "body" to "edited body", "row_version" to 2)))))
            assertEquals(0, search("checklist").size)
            assertEquals(1, search("edited").size)
            s.writer.markAccessRemoved(W, AccessTarget.Message("reply"))
            assertEquals(0, search("edited").size)
            assertEquals(0, search("edited first").size)
            assertEquals(0, search("", filter = ConversationFilter.MINE).size)
            s.writer.markAccessRemoved(W, AccessTarget.Channel(ch))
            assertEquals(0, search("renamed").size)
        }
    }
    @Test fun versionEightUpgradeBackfillsTitlesAndUpdatesEveryIndexMutation() {
        SqlConnection.memory().use { db ->
            Schema.migrate(db, Schema.migrations.filter { it.version <= 8 })
            db.run("INSERT INTO threads(workspace_id,id,channel_id,title,row_version,updated_at,raw) VALUES('w','t','c','deployment checklist',1,'now','{}')")
            db.run("INSERT INTO messages(workspace_id,id,channel_id,thread_id,body,created_at,updated_at,row_version,raw) VALUES('w','m','c','t','free text','now','now',1,'{}')")
            Schema.migrate(db)
            fun count(q: String) = db.longValue("SELECT count(*) FROM search_index WHERE search_index MATCH ? AND workspace_id = 'w'", q)
            assertEquals(1L, count("title : \"deployment checklist\""))
            assertEquals(1L, count("body : \"free text\""))
            db.run("UPDATE threads SET title = 'renamed' WHERE id = 't'")
            assertEquals(0L, count("deployment"))
            assertEquals(1L, count("renamed"))
            db.run("UPDATE messages SET body = 'edited' WHERE id = 'm'")
            assertEquals(0L, count("free"))
            assertEquals(1L, count("edited"))
            db.run("UPDATE threads SET access_removed = 1 WHERE id = 't'")
            assertEquals(0L, count("renamed"))
            db.run("UPDATE messages SET deleted = 1 WHERE id = 'm'")
            assertEquals(0L, count("edited"))
            db.run("DELETE FROM threads WHERE id = 't'")
            db.run("DELETE FROM messages WHERE id = 'm'")
            assertEquals(0L, db.longValue("SELECT count(*) FROM search_index WHERE workspace_id = 'w'"))
        }
    }

    @Test fun inheritedMembershipAndCreatorUnreadOrderingRemainCanonical() = runBlocking {
        store().use { s ->
            val ch = Fixtures.CHANNEL_1; val sc = Fixtures.SCOPE_A
            s.writer.upsertScopes(W, listOf(scope(sc, "Scope")), true)
            s.writer.upsertChannels(W, sc, listOf(channel(ch, sc, "general")), true)
            val raw = Fixtures.obj(mapOf("created_by_actor_npub" to TestKeys.NPUB))
            s.writer.upsertThreads(W, listOf(
                ThreadRecord(W, "parent", ch, "root", "Parent", updatedAt = "2026-10-01", raw = raw),
                ThreadRecord(W, "branch", ch, null, "Branch", updatedAt = "2026-10-02", raw = raw),
                ThreadRecord(W, "uncached", ch, null, "Uncached title", updatedAt = "2026-10-03", raw = raw)))
            val m = message("root", "inherited phrase", "2026-10-01", thread = "parent", sender = "other")
            s.writer.upsertMessages(listOf(m))
            s.writer.upsertEffectiveThreadPage(W, ch, "branch", listOf(m.copy(effectiveThreadId = "branch")))
            s.writer.upsertViewStates(W, listOf(studio.otherstuff.ambulando.core.transport.ViewStateRecord(W,
                studio.otherstuff.ambulando.core.transport.ViewStateRecord.ResourceType.THREAD, "branch", sc, ch, 4, 2, updatedAt = "2026-10-03")))
            val all = s.reader.conversations(W, setOf(ch), TestKeys.NPUB)
            assertEquals(3, all.size)
            assertEquals("uncached", all.first().id)
            assertEquals(3, s.reader.conversations(W, setOf(ch), TestKeys.NPUB, filter = ConversationFilter.MINE).size)
            assertEquals(listOf("branch"), s.reader.conversations(W, setOf(ch), TestKeys.NPUB, filter = ConversationFilter.UNREAD).map { it.id })
            assertEquals(setOf("parent", "branch"), s.reader.conversations(W, setOf(ch), TestKeys.NPUB, "body:\"inherited phrase\"").map { it.id }.toSet())
            s.writer.finishEffectiveThread(W, ch, "branch", emptySet())
            assertEquals(listOf("parent"), s.reader.conversations(W, setOf(ch), TestKeys.NPUB, "inherited").map { it.id })
            s.writer.markAccessRemoved(W, AccessTarget.Thread("parent"))
            assertTrue(s.reader.conversations(W, setOf(ch), TestKeys.NPUB, "inherited Branch").isEmpty())
        }
    }

    @Test fun oldMessageAnchorUsesBoundedWindowAndKeepsCanonicalOrder() = runBlocking {
        store().use { s ->
            val messages = (0 until 240).map { n -> message("m%03d".format(n), "message $n", "2026-10-01T00:%02d:%02dZ".format(n / 60, n % 60), thread = "long") }
            s.writer.upsertMessages(messages)
            val offset = s.reader.threadMessageWindowOffset(W, "long", "m005")!!
            val window = s.reader.threadMessages(W, "long", 50, offset)
            assertTrue(window.size <= 50)
            assertTrue(window.any { it.id == "m005" })
            assertEquals(window.sortedBy { it.createdAt }.map { it.id }, window.map { it.id })
            assertEquals(214, offset)
            assertNull(s.reader.threadMessageWindowOffset(W2, "long", "m005"))
            assertNull(s.reader.threadMessageWindowOffset(W, "other", "m005"))
            s.writer.markAccessRemoved(W, AccessTarget.Message("m005"))
            assertNull(s.reader.threadMessageWindowOffset(W, "long", "m005"))
        }
    }

}
