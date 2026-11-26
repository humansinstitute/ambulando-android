package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.W2
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.store.StoreFixtures.message
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.store.StoreFixtures.store
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.ThreadRecord

class LatestChatsTest {
    private fun row(id: String, thread: String?, at: String, channel: String = "c") =
        ConversationRow(id, thread, channel, "t$id", emptyList(), 0, "", at, unread = false, mine = false)

    @Test fun newestFiveOneCardPerConversation() {
        val rows = listOf(
            row("1", "t1", "2026-11-26T09:00:00Z"), row("1b", "t1", "2026-11-26T09:05:00Z"),
            row("2", "t2", "2026-11-26T08:00:00Z"), row("3", "t3", "2026-11-26T07:00:00Z"),
            row("4", "t4", "2026-11-26T06:00:00Z"), row("5", "t5", "2026-11-26T05:00:00Z"),
            row("6", "t6", "2026-11-26T04:00:00Z"), row("7", null, "2026-11-26T10:00:00Z"),
        )
        assertEquals(listOf("7", "1b", "2", "3", "4"), LatestChats.pick(rows).map { it.id })
    }

    @Test fun onlyAccessibleChannelsOfThisWorkspace() {
        val a = Fixtures.SCOPE_A; val b = Fixtures.SCOPE_B
        val scopes = listOf(scope(a, "A"), scope(b, "B").copy(accessRemoved = true), scope(a, "A elsewhere", workspace = W2))
        val channels = listOf(
            channel(Fixtures.CHANNEL_1, a, "open"), channel(Fixtures.CHANNEL_2, a, "removed").copy(accessRemoved = true),
            channel("00000000-0000-4000-8000-000000000c03", b, "in removed scope"),
            channel("00000000-0000-4000-8000-000000000c04", a, "other workspace", workspace = W2),
        )
        assertEquals(setOf(Fixtures.CHANNEL_1), LatestChats.channelIds(W, false, channels, scopes))
        assertTrue(LatestChats.channelIds(W, true, channels, scopes).isEmpty())
    }

    @Test fun loadsFromTheConversationIndexExcludingRemovedAndOtherChannels() = runBlocking {
        store().use { s ->
            val sc = Fixtures.SCOPE_A
            s.writer.upsertScopes(W, listOf(scope(sc, "Scope")), complete = true)
            s.writer.upsertChannels(W, sc, listOf(channel(Fixtures.CHANNEL_1, sc, "general"), channel(Fixtures.CHANNEL_2, sc, "other")), complete = true)
            fun thread(id: String, ch: String, at: String) = ThreadRecord(W, id, ch, "r$id", "Thread $id", null, false, false, 1, at, Fixtures.obj(mapOf("id" to id, "title" to "Thread $id", "row_version" to 1)))
            val ids = (1..7).map { "t$it" }
            s.writer.upsertThreads(W, ids.mapIndexed { i, id -> thread(id, if (i == 6) Fixtures.CHANNEL_2 else Fixtures.CHANNEL_1, "2026-11-2${i}T00:00:00Z") })
            s.writer.upsertMessages(ids.mapIndexed { i, id -> message("r$id", "hello $id", "2026-11-2${i}T00:00:00Z", channel = if (i == 6) Fixtures.CHANNEL_2 else Fixtures.CHANNEL_1, thread = id, source = "r$id") })
            val viewer = TestKeys.NPUB
            val latest = LatestChats.load(s.reader, W, setOf(Fixtures.CHANNEL_1), viewer)
            assertEquals("five newest of the chosen channels", listOf("t6", "t5", "t4", "t3", "t2"), latest.map { it.threadId })
            s.writer.markAccessRemoved(W, AccessTarget.Message("rt6"))
            s.writer.markAccessRemoved(W, AccessTarget.Channel(Fixtures.CHANNEL_1))
            assertTrue("a removed channel shows nothing", LatestChats.load(s.reader, W, setOf(Fixtures.CHANNEL_1), viewer).isEmpty())
            assertEquals(listOf("t7"), LatestChats.load(s.reader, W, setOf(Fixtures.CHANNEL_2), viewer).map { it.threadId })
            assertTrue("other workspace", LatestChats.load(s.reader, W2, setOf(Fixtures.CHANNEL_2), viewer).isEmpty())
        }
    }
}
