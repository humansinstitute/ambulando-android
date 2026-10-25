package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.profiles.PersonDisplay
import studio.otherstuff.ambulando.core.profiles.ProfileRecord
import studio.otherstuff.ambulando.core.store.StoreFixtures.W
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.MemberRecord
import java.time.Instant

class ProfileStoreTests {
    private val ana = NostrKeys.npub("a1".repeat(32))!!
    private val bea = NostrKeys.npub("c3".repeat(32))!!
    private val anaHex get() = NostrKeys.publicKeyHex(ana)!!
    private val beaHex get() = NostrKeys.publicKeyHex(bea)!!

    private fun record(npub: String, name: String, at: Long, id: String = "1".repeat(64), picture: String? = null) = ProfileRecord(
        pubkey = NostrKeys.publicKeyHex(npub)!!, npub = npub, displayName = name, picture = picture, eventId = id, eventCreatedAt = at,
        status = ProfileRecord.Status.FOUND, checkedAt = Instant.now(),
    )

    private suspend fun connectedStore(): Pair<LocalStore, ConnectionRecord> {
        val store = StoreFixtures.store(intervalMillis = 5)
        val connection = StoreFixtures.connect(store)
        store.writer.replaceWorkspaces(connection.id, listOf(StoreFixtures.workspace(W, "Studio")))
        store.writer.replaceMembers(W, listOf(MemberRecord(W, "a", ana, "human", null), MemberRecord(W, "b", bea, "human", "Bea")))
        return store to connection
    }

    @Test fun newestProfileWinsAndMissingNeverClobbersAFoundProfile() = runBlocking {
        val (store, c) = connectedStore()
        store.use {
            val t0 = Instant.ofEpochSecond(1_800_000_000)
            store.writer.recordProfileCheck(c.id, listOf(record(ana, "Ana v2", 200, picture = "https://img.example.com/2.png")),
                mapOf(anaHex to ana, beaHex to bea), t0)
            var rows = store.reader.profiles(c.id).associateBy { it.npub }
            assertEquals("Ana v2", rows[ana]?.displayName)
            assertEquals(ProfileRecord.Status.MISSING, rows[bea]?.status)
            // An older event (relay lag) does not replace a newer one, but the check time moves on.
            val t1 = t0.plusSeconds(60)
            store.writer.recordProfileCheck(c.id, listOf(record(ana, "Ana v1", 100)), mapOf(anaHex to ana), t1)
            // A round where the relays return nothing for Ana keeps her profile.
            val t2 = t1.plusSeconds(60)
            store.writer.recordProfileCheck(c.id, emptyList(), mapOf(anaHex to ana), t2)
            rows = store.reader.profiles(c.id).associateBy { it.npub }
            assertEquals("Ana v2", rows[ana]?.displayName)
            assertEquals(ProfileRecord.Status.FOUND, rows[ana]?.status)
            assertEquals(t2, rows[ana]?.checkedAt)
            // A changed picture (newer event) replaces the URL.
            store.writer.recordProfileCheck(c.id, listOf(record(ana, "Ana v3", 300, picture = "https://img.example.com/3.png")), mapOf(anaHex to ana),
                t2.plusSeconds(1))
            val people = store.reader.people(c.id, W)
            assertEquals("Ana v3", people.person(ana).name)
            assertEquals("https://img.example.com/3.png", people.person(ana).avatarURL?.toString())
            assertEquals("Tower's name is unaffected by a missing profile", "Bea", people.person(bea).name)
            // Same created_at: the lower event id wins (NIP-01).
            store.writer.recordProfileCheck(c.id, listOf(record(ana, "Ana tie-high", 300, id = "f".repeat(64))), mapOf(anaHex to ana), t2.plusSeconds(2))
            assertEquals("Ana v3", store.reader.people(c.id, W).person(ana).name)
            assertEquals(listOf("Ana v3", "Bea"), store.reader.directoryMembers(W).map { it.label })
        }
    }

    @Test fun turningProfilesOffDuringARoundStoresNothing() = runBlocking {
        val (store, c) = connectedStore()
        store.use {
            store.writer.setState("profiles.public", "off")
            store.writer.recordProfileCheck(c.id, listOf(record(ana, "Ana", 1)), mapOf(anaHex to ana), Instant.now(), unlessOff = "profiles.public")
            assertTrue(store.reader.profiles(c.id).isEmpty())
        }
    }

    @Test fun profilesFollowTheConnectionAndArePrunedAndCapped() = runBlocking {
        val (store, c) = connectedStore()
        store.use {
            val outsider = NostrKeys.npub("e5".repeat(32))!!
            store.writer.recordProfileCheck(c.id, listOf(record(ana, "Ana", 1), record(outsider, "Outsider", 1)),
                mapOf(anaHex to ana, NostrKeys.publicKeyHex(outsider)!! to outsider), Instant.now())
            store.writer.pruneProfiles(c.id)
            assertEquals("a profile nobody in the workspaces refers to is pruned", setOf(ana), store.reader.profiles(c.id).map { it.npub }.toSet())
            // Another connection never sees this connection's profiles.
            val other = StoreFixtures.connect(store, origin = "https://other.example.com")
            assertEquals(PersonDisplay.NameSource.NPUB, store.reader.people(other.id, W).person(ana).nameSource)
            // Android: an unlisted workspace is flagged, not removed, so its people stay until disconnect.
            store.writer.replaceWorkspaces(c.id, emptyList())
            assertEquals(listOf(ana), store.reader.profiles(c.id).map { it.npub })
            // The cap keeps the most recently checked rows.
            store.writer.recordProfileCheck(c.id, emptyList(), mapOf(beaHex to bea), Instant.now().plusSeconds(10))
            store.writer.pruneProfiles(c.id, maxRows = 1)
            assertEquals(listOf(bea), store.reader.profiles(c.id).map { it.npub })
            store.writer.removeConnection(c.id)
            assertTrue(store.reader.profiles(c.id).isEmpty())
        }
    }

    @Test fun candidatesAreTheSignerMembersAgentsAndRecentSendersBounded() = runBlocking {
        val (store, c) = connectedStore()
        store.use {
            val sender = NostrKeys.npub("f6".repeat(32))!!
            store.writer.upsertMessages(listOf(
                StoreFixtures.message("m1", "hi", "2026-10-24T00:00:00Z", sender = sender),
                StoreFixtures.message("m2", "bad", "2026-10-24T00:00:01Z", sender = "npub1notvalid"),
            ))
            val candidates = store.reader.profileCandidates(c.id, W)
            assertEquals(setOf(TestKeys.NPUB, ana, bea, sender), candidates.toSet())
            assertEquals(TestKeys.NPUB, candidates.first())
            assertEquals(2, store.reader.profileCandidates(c.id, W, limit = 2).size)
        }
    }
}
