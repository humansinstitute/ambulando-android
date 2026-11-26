package studio.otherstuff.ambulando.core.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.surfaces.ArtefactType
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.transport.FileRecord

class FileMentionSearchTest {
    private val w = StoreFixtures.W
    private val channel = Fixtures.CHANNEL_1
    private fun file(id: String, title: String, type: ArtefactType? = null) = FileRecord(
        w, id, Fixtures.SCOPE_A, channel, null, "storage-$id", title,
        contentType = type?.mime ?: "application/pdf", sizeBytes = 1, sha256 = null,
        createdAt = "2026-11-26", updatedAt = "2026-11-26",
        raw = buildJsonObject { if (type != null) put("metadata", type.metadata()) },
    )

    @Test fun everyExistingFormatAndGenericFileSearchesByTitleAndKeepsFileIdentity() = runBlocking {
        StoreFixtures.store().use { store ->
            val files = ArtefactType.entries.map { file(it.wire, "Roadmap ${it.label}", it) } + file("pdf", "Roadmap attachment")
            store.writer.upsertFiles(w, files)
            val found = store.reader.mentionCandidates(w, "roadmap")
            assertEquals(files.map { it.id }.toSet(), found.map { it.mention.id }.toSet())
            assertTrue(found.all { it.mention.type == MentionType.FILE && it.mention.token.contains("mention:file:") })
            assertEquals(setOf("Whiteboard", "HTML", "System Flow", "File"), found.map { it.sublabel }.toSet())
            for (prefix in listOf("file:", "artefact:", "artifact:")) {
                assertEquals(4, store.reader.mentionCandidates(w, prefix).size)
                assertEquals(listOf("html"), store.reader.mentionCandidates(w, prefix + "HTML").map { it.mention.id })
            }
            assertTrue(store.reader.mentionCandidates(w, "").any { it.mention.type == MentionType.FILE })
            assertTrue(store.reader.mentionCandidates(StoreFixtures.W2, "file:").isEmpty())
            // Search the whole available table, rather than a first-page/500-file snapshot.
            store.writer.upsertFiles(w, (1..600).map { file("bulk-$it", "A file $it") } + file("last", "Zebra target"))
            assertEquals("last", store.reader.mentionCandidates(w, "Zebra").single().mention.id)
        }
    }

    @Test fun archivedMissingAndRevokedRecordsAreNeverOffered() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertFiles(w, listOf(file("ok", "Visible"), file("archived", "Archived").copy(archived = true), file("gone", "Gone")))
            store.writer.markAccessRemoved(w, AccessTarget.File("gone"))
            assertEquals(listOf("ok"), store.reader.mentionCandidates(w, "file:").map { it.mention.id })
            store.writer.upsertFiles(w, emptyList(), completeChannel = channel)
            assertTrue(store.reader.mentionCandidates(w, "file:").isEmpty())
        }
    }

    @Test fun parentRevocationExcludesRetainedFiles() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertScopes(w, listOf(StoreFixtures.scope(Fixtures.SCOPE_A, "Scope")), complete = false)
            store.writer.upsertChannels(w, Fixtures.SCOPE_A, listOf(StoreFixtures.channel(channel, Fixtures.SCOPE_A, "General")), complete = false)
            store.writer.upsertFiles(w, listOf(file("retained", "Private plan")))
            store.writer.markAccessRemoved(w, AccessTarget.Channel(channel))
            assertNotNull(store.reader.file(w, "retained"))
            assertTrue(store.reader.mentionCandidates(w, "file:Private").isEmpty())
        }
    }

    @Test fun selectedFileSurvivesComposerWireStoreReloadAndEditWithoutActorRouting() = runBlocking {
        StoreFixtures.store().use { store ->
            store.writer.upsertFiles(w, listOf(file("file-id", "System plan", ArtefactType.SYSTEM_FLOW)))
            val picked = store.reader.mentionCandidates(w, "System plan").single().mention
            val composed = MentionCodec.complete("See @System", picked)
            val wire = MentionCodec.encode(composed, listOf(picked))
            assertEquals("See @[System plan](mention:file:file-id) ", wire.body)
            assertTrue(wire.mentions.isEmpty())
            store.writer.upsertMessages(listOf(StoreFixtures.message("sent", wire.body, "2026-11-26")))
            val reloaded = store.reader.message(w, "sent")!!.body
            val ref = MentionCodec.segments(reloaded).filterIsInstance<BodySegment.Reference>().single()
            assertEquals("file", ref.kind)
            assertEquals("file-id", ref.id)
            assertEquals("System plan", ref.label)
            val edit = MentionCodec.decode(reloaded)
            assertEquals(reloaded, MentionCodec.encode(edit.text, edit.mentions).body)
        }
    }
}
