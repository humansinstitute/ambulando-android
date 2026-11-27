package studio.otherstuff.ambulando.core.goals

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.sample.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.*
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.*
import java.nio.file.Files

class ColdGoalsReadTest {
    @Test fun canonicalContentWithout94SnapshotWhileWritesPrepare() = runBlocking {
        val h = SampleHarness(); val w = SampleIds.STUDIO
        val directory = Files.createTempDirectory("cold-goals").toFile()
        try { LocalStore(directory).use { store ->
            val seed = GoalsController(h.api,w,h.app,TestKeys.NPUB) { true }
            seed.refresh(); seed.setUp(null,emptyList())
            val doc = seed.create(Goal.create("Cold canonical goal","Upgrade"))
            val channel = (seed.state.value.space as GoalsSpaceState.Ready).channel
            store.writer.upsertChannels(w,channel.scopeId,listOf(channel),complete=false)
            store.writer.cacheVerifiedArtefact(doc.file) { true }
            val cache = FileCache(directory.resolve("downloaded-files"))
            cache.store(doc.goal.encode().toByteArray(),doc.file)
            val local = GoalsLocalContent(store,w,TestKeys.NPUB, fileCache = cache)
            assertNull(local.token()); assertNotNull(store.reader.file(w,doc.id)); assertNotNull(cache.cached(doc.file))
            // Exact production chain: runtime reads open, direct screen transport behind write readiness.
            val reads = WorkspaceActivity(true); val writes = WorkspaceActivity(false)
            val readTransport = WorkspaceHttpTransport(h.tower,reads)
            val direct = WorkspaceHttpTransport(readTransport,writes) { WritesNotReady() }
            val api = TowerAPI(TowerClient(h.client.origin,GoalsTransport(readTransport,direct),SampleSigners.primary))
            val opened = GoalsController(api,w,h.app,TestKeys.NPUB,local) { true }
            opened.hydrate()
            assertEquals("Canonical pre94 bytes must render before any network", "Cold canonical goal", opened.state.value.goals.single().goal.name)
            assertNull("Canonical metadata is not write authority",opened.state.value.space)
            opened.refresh()
            assertNull("Canonical hydration: blank native Goals, New goal disabled; canonical content exists; ${opened.state.value.error}",opened.state.value.error)
            assertEquals("Cold canonical goal",opened.state.value.goals.single().goal.name)
            assertFalse(writes.isOpen)
            try { opened.create(Goal.create("Must not write","Still gated")); fail("write gate bypassed") }
            catch (expected: WritesNotReady) { }
            // First use has no snapshot or downloaded cache. Opening read authority must recover by itself.
            val empty = GoalsController(api,w,h.app,TestKeys.NPUB) { true }
            reads.pause()
            val observer = launch { empty.followReadiness(reads) }
            yield()
            assertTrue(empty.state.value.goals.isEmpty()); assertNull(empty.state.value.error)
            reads.resume()
            withTimeout(3000) { empty.state.first { it.goals.isNotEmpty() } }
            assertEquals("Cold canonical goal",empty.state.value.goals.single().goal.name)
            observer.cancelAndJoin()
            store.writer.markAccessRemoved(w,AccessTarget.Channel(channel.id))
            opened.hydrate()
            assertTrue("Known channel revocation must hide canonical content",opened.state.value.goals.isEmpty())
        } } finally { directory.deleteRecursively() }
    }
}
