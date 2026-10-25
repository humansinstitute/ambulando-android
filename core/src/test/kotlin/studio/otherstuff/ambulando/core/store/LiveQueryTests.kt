package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.store.StoreFixtures.channel
import studio.otherstuff.ambulando.core.store.StoreFixtures.message
import studio.otherstuff.ambulando.core.store.StoreFixtures.scope
import studio.otherstuff.ambulando.core.support.Fixtures

class LiveQueryTests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    /** A slow load for a replaced query (previous workspace) must not overwrite the new one. */
    @Test fun slowLoadFromReplacedQueryIsDiscarded() = runBlocking {
        ChangeCoalescer(10).use { coalescer ->
            val query = LiveQuery(emptyList<String>(), scope)
            query.start(coalescer, matches = { true }) { delay(300); listOf("old workspace row") }
            delay(20)
            query.start(coalescer, matches = { true }) { listOf("new workspace row") }
            delay(500)
            assertEquals(listOf("new workspace row"), query.value)
            assertTrue(query.loaded)
            assertNull(query.error)
        }
    }

    @Test fun slowErrorFromReplacedQueryIsDiscarded() = runBlocking {
        ChangeCoalescer(10).use { coalescer ->
            val query = LiveQuery(emptyList<String>(), scope)
            query.start(coalescer, matches = { true }) { delay(300); error("old workspace failed") }
            delay(20)
            query.start(coalescer, matches = { true }) { listOf("fresh") }
            delay(500)
            assertEquals(listOf("fresh"), query.value)
            assertNull("the old query's late error is not shown on the new one", query.error)
        }
    }

    @Test fun errorsAreShownAndLoadedIsSet() = runBlocking {
        ChangeCoalescer(10).use { coalescer ->
            val query = LiveQuery(emptyList<String>(), scope)
            query.start(coalescer, matches = { true }) { error("disk unavailable") }
            waitFor { query.loaded }
            assertEquals("disk unavailable", query.error)
        }
    }

    @Test fun stoppedQueryIgnoresLateResult() = runBlocking {
        ChangeCoalescer(10).use { coalescer ->
            val query = LiveQuery(emptyList<String>(), scope)
            query.start(coalescer, matches = { true }) { delay(200); listOf("late") }
            delay(20)
            query.stop()
            delay(400)
            assertEquals(emptyList<String>(), query.value)
            assertFalse(query.loaded)
        }
    }

    /** A paused query (a card out of sight) keeps its rows, ignores changes, and resumes without flashing empty. */
    @Test fun pauseKeepsRowsAndResumeKeepsThemUntilTheFreshRead() = runBlocking {
        StoreFixtures.store(intervalMillis = 10).use { store ->
            val query = LiveQuery(emptyList<String>(), scope)
            val load: suspend () -> List<String> = { store.reader.scopes(W).map { it.name } }
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Alpha")), complete = false)
            query.start(store, matches = { it.table == StoreTable.SCOPES }, load = load)
            waitFor { query.value == listOf("Alpha") }
            query.pause()
            assertEquals("rows stay while paused", listOf("Alpha"), query.value)
            assertTrue(query.loaded)
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_B, "Beta")), complete = false)
            delay(80)
            assertEquals("no reads while paused", listOf("Alpha"), query.value)
            query.start(store, keepingValue = true, matches = { it.table == StoreTable.SCOPES }) { delay(100); load() }
            assertEquals("resuming keeps the rows until the fresh read", listOf("Alpha"), query.value)
            assertTrue(query.loaded)
            waitFor { query.value.toSet() == setOf("Alpha", "Beta") }
        }
    }

    @Test fun reloadsOnlyForMatchingChangesAndEmitsOnlyChangedValues() = runBlocking {
        StoreFixtures.store(intervalMillis = 10).use { store ->
            val query = LiveQuery(emptyList<String>(), scope)
            var loads = 0
            val emitted = ArrayList<List<String>>()
            val collector = scope.async { query.state.collect { synchronized(emitted) { emitted.add(it.value) } } }
            query.start(store, matches = { it.table == StoreTable.SCOPES && it.workspaceId == W }) { loads++; store.reader.scopes(W).map { it.name } }
            waitFor { query.loaded }
            store.writer.setState("unrelated", "x")
            delay(80)
            assertEquals("an unrelated change does not reload", 1, loads)
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Alpha")), complete = false)
            waitFor { query.value == listOf("Alpha") }
            collector.cancel()
            assertEquals(1, synchronized(emitted) { emitted.count { it == listOf("Alpha") } })
        }
    }

    /**
     * A card paused while its channel lost access must not resume showing the rows it read before (unlabelled): it
     * starts empty and shows only the fresh read, where the rows are now flagged.
     */
    @Test fun resumeAfterAccessLossStartsFromAFreshRead() = runBlocking {
        StoreFixtures.store(intervalMillis = 10).use { store ->
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Alpha")), complete = true)
            store.writer.upsertChannels(W, Fixtures.SCOPE_A, listOf(channel(Fixtures.CHANNEL_1, Fixtures.SCOPE_A, "general")), complete = true)
            store.writer.upsertMessages(listOf(message("m1", "secret", "2026-10-24T00:00:00Z")))
            val query = LiveQuery(emptyList<Pair<String, Boolean>>(), scope)
            val load: suspend () -> List<Pair<String, Boolean>> = {
                store.reader.latestMessages(W, Fixtures.CHANNEL_1, 10).map { it.body to it.accessRemoved }
            }
            val matches: (ChangeKey) -> Boolean = { it.workspaceId == W && it.table == StoreTable.MESSAGES }
            query.start(store, matches = matches, load = load)
            waitFor { query.value == listOf("secret" to false) }
            query.pause()
            // While hidden: Tower no longer lists the channel (access revoked).
            store.writer.upsertChannels(W, Fixtures.SCOPE_A, emptyList(), complete = true)
            // Resume at once, before the change batch is delivered, with a slow fresh read.
            query.start(store, keepingValue = true, matches = matches) { delay(100); load() }
            assertEquals("rows read before the access change are not shown again", emptyList<Pair<String, Boolean>>(), query.value)
            assertFalse(query.loaded)
            waitFor { query.loaded }
            assertEquals(listOf("secret" to true), query.value)
        }
    }

    @Test fun resumeAfterADeletedRecordAndAfterOrdinaryChanges() = runBlocking {
        StoreFixtures.store(intervalMillis = 10).use { store ->
            store.writer.upsertMessages(listOf(message("m1", "one", "2026-10-24T00:00:00Z"), message("m2", "two", "2026-10-24T00:00:01Z")))
            val query = LiveQuery(emptyList<String>(), scope)
            val load: suspend () -> List<String> = { store.reader.latestMessages(W, Fixtures.CHANNEL_1, 10).map { it.body } }
            val matches: (ChangeKey) -> Boolean = { it.table == StoreTable.MESSAGES }
            query.start(store, matches = matches, load = load)
            waitFor { query.value == listOf("one", "two") }
            query.pause()
            // An ordinary new message while hidden keeps the rows on resume (no flash).
            store.writer.upsertMessages(listOf(message("m3", "three", "2026-10-24T00:00:02Z")))
            query.start(store, keepingValue = true, matches = matches, load = load)
            assertEquals(listOf("one", "two"), query.value)
            waitFor { query.value == listOf("one", "two", "three") }
            query.pause()
            // A message deleted on Tower while hidden is never shown again.
            store.writer.applyTombstones(W, setOf(Tombstone.Message("m2")))
            query.start(store, keepingValue = true, matches = matches, load = load)
            assertEquals(emptyList<String>(), query.value)
            waitFor { query.value == listOf("one", "three") }
        }
    }

    @Test fun flowLiveQueryEmitsFirstReadThenDistinctChanges() = runBlocking {
        StoreFixtures.store(intervalMillis = 10).use { store ->
            val values = async {
                withTimeout(3_000) { store.liveQuery({ it.table == StoreTable.SCOPES }) { store.reader.scopes(W).map { it.name } }.take(3).toList() }
            }
            waitFor { store.changes.observerCount == 1 }
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Alpha")), complete = false)
            delay(50)
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_A, "Alpha", rowVersion = 2)), complete = false)   // same names: no emission
            delay(50)
            store.writer.upsertScopes(W, listOf(scope(Fixtures.SCOPE_B, "Beta")), complete = false)
            assertEquals(listOf(emptyList(), listOf("Alpha"), listOf("Alpha", "Beta")), values.await())
            waitFor { store.changes.observerCount == 0 }
        }
    }
}
