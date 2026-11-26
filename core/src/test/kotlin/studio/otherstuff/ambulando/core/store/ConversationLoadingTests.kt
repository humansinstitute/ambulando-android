package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.support.Fixtures
import java.io.File
import java.nio.file.Files

/** Retained history through the inbox caller and the same reader used by Deck. */
class ConversationLoadingTests {
    @Test fun retainedHistoryDoesNotHydrateEveryMessageOrIssuePerThreadReads() = runBlocking {
        val dir = Files.createTempDirectory("conversation-loading").toFile()
        try { StoreFixtures.store(dir).use { s ->
            s.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Synthetic")), true)
            s.writer.upsertChannels(W, Fixtures.SCOPE_A, listOf(channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "Synthetic")), true)
            s.writer.write {
                db.prepare("INSERT INTO threads(workspace_id,id,channel_id,title,row_version,updated_at,raw) VALUES(?,?,?,?,1,?,?)").use { t ->
                    db.prepare("INSERT INTO messages(workspace_id,id,channel_id,thread_id,body,created_at,updated_at,row_version,raw,sender_npub) VALUES(?,?,?,?,?,?,?,1,?,?)").use { m ->
                        repeat(1200) { i ->
                            val id = "thread-$i"
                            t.run(W,id,Fixtures.CHANNEL_1,"Topic $i","2026-10-01T00:00:00Z","{}")
                            repeat(40) { j ->
                                val at = "2026-10-01T00:00:%02dZ".format(j)
                                m.run(W,"message-$i-$j",Fixtures.CHANNEL_1,id,"Synthetic retained body $j",at,at,"{\"fixture\":\"${"x".repeat(1000)}\"}","sender-${j%4}")
                            }
                        }
                    }
                }
            }
            var statements = 0; var messageHydrations = 0; var returnedRows = 0
            var summarySql: String? = null
            s.reader.read { queryWorkObserver = { sql, n ->
                statements++; returnedRows += n
                if (sql.contains("eligible AS")) summarySql = sql
                if (sql.contains("m.raw") || sql.contains("SELECT ${Rows.MESSAGE_COLUMNS}")) messageHydrations += n
            } }
            val start = System.nanoTime()
            val chat = async(start = CoroutineStart.UNDISPATCHED) { s.reader.conversations(W,setOf(Fixtures.CHANNEL_1),"sender-0") }
            val deckStart = System.nanoTime()
            val deck = async(start = CoroutineStart.UNDISPATCHED) { s.reader.deckThreads(W,listOf(Fixtures.CHANNEL_1)) }
            assertEquals(1200,chat.await().size)
            val chatMs = (System.nanoTime()-start)/1e6
            assertEquals(300,deck.await().size)
            val deckMs = (System.nanoTime()-deckStart)/1e6
            s.reader.read { queryWorkObserver = null }
            println("retained threads=1200 messages=48000 chatMs=$chatMs queuedDeckMs=$deckMs statements=$statements messageHydrations=$messageHydrations returnedRows=$returnedRows")
            assertTrue("Inbox must not decode retained message JSON: $messageHydrations", messageHydrations <= 1200)
            assertTrue("Inbox/Deck statement budget: $statements", statements <= 20)
            assertTrue("Return summaries, not all retained history: $returnedRows", returnedRows <= 3000)
            s.reader.read {
                summarySql?.let { sql -> println("inboxPlan="+query("EXPLAIN QUERY PLAN $sql",W,Fixtures.CHANNEL_1,"sender-0") { it.text(3) }.joinToString("; ")) }
            }
            var warmStatements = 0
            s.reader.read { queryWorkObserver = { _, _ -> warmStatements++ } }
            repeat(3) {
                val began = System.nanoTime()
                assertEquals(1200,s.reader.conversations(W,setOf(Fixtures.CHANNEL_1),"sender-0").size)
                println("warmInboxMs="+(System.nanoTime()-began)/1e6)
            }
            assertEquals("Unchanged retained contexts must reuse a validated summary",0,warmStatements)
            s.writer.touch(setOf(ChangeKey(StoreTable.WRITES,W)))
            s.reader.conversations(W,setOf(Fixtures.CHANNEL_1),"sender-0")
            assertEquals("An unrelated write must not invalidate the summary",0,warmStatements)
            s.writer.markAccessRemoved(W,AccessTarget.Thread("thread-0"))
            assertEquals(1199,s.reader.conversations(W,setOf(Fixtures.CHANNEL_1),"sender-0").size)
            assertTrue("Access revision must force a fresh read",warmStatements > 0)
            assertTrue(s.reader.conversations(W,setOf(Fixtures.CHANNEL_1),"another-viewer",filter=ConversationFilter.MINE).isEmpty())
            s.reader.read { queryWorkObserver = null }
            val directDeck = System.nanoTime()
            s.reader.deckThreads(W,listOf(Fixtures.CHANNEL_1))
            println("standaloneDeckMs="+(System.nanoTime()-directDeck)/1e6)
        } } finally { dir.deleteRecursively() }
    }
}
