package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.support.Fixtures
import studio.otherstuff.ambulando.core.support.makeIdentity
import studio.otherstuff.ambulando.core.support.tempDir
import studio.otherstuff.ambulando.core.transport.*

class TextAttachmentServiceTest {
    private val ws = Fixtures.WORKSPACE_ID
    private val ch = Fixtures.CHANNEL_1
    private val app = Fixtures.APP_NPUB
    private val message = Fixtures.newId()

    private class Rig(val tower: FakeTextTower, val api: TowerAPI, val service: TextAttachmentService, val original: MessageAttachment, val originalText: String)

    private fun rig(originalText: String = "original\r\ntext é 😀\r\n"): Rig = runBlocking {
        val tower = FakeTextTower()
        val bytes = TextAttachments.bytes(originalText)
        val id = tower.putObject(bytes)
        val api = TowerAPI(TowerClient(TowerOrigin(Fixtures.ORIGIN), tower, makeIdentity()))
        val dir = tempDir()
        val service = TextAttachmentService(TextEditStore(java.io.File(dir, "edits")), TextCache(java.io.File(dir, "cache")))
        val attachment = MessageAttachment("file", id, "Pasted text.txt", TextAttachments.CONTENT_TYPE, bytes.size.toLong(), tower.objects[id]!!.sha,
            TextAttachmentInfo.of(originalText, "paste"))
        Rig(tower, api, service, attachment, originalText)
    }

    private fun Rig.begin(text: String, fileId: String? = null, base: String? = null, id: String = Fixtures.newId()) =
        service.beginEdit(id, "conn", ws, ch, app, message, original, "Pasted text.txt", text, fileId, base)

    @Test fun firstEditCreatesLineageFileAndKeepsTheOriginalUntouched() = runBlocking {
        val r = rig()
        val text = "edited\r\nbody é 😀"
        val saved = r.service.save(r.api, r.begin(text).id)
        assertEquals(TextEditState.SAVED, saved.state)
        val file = r.tower.files.values.single()
        assertEquals(message, file.lineage["origin_message_id"])
        assertEquals(r.original.storageObjectId, file.lineage["origin_storage_object_id"])
        assertEquals(1, file.versions.size)
        // The new object holds the exact edited bytes; the original object is unchanged.
        assertArrayEquals(TextAttachments.bytes(text), r.tower.objects[file.versions[0].objectId]!!.bytes)
        assertNotEquals(r.original.storageObjectId, file.versions[0].objectId)
        assertArrayEquals(TextAttachments.bytes(r.originalText), r.tower.objects[r.original.storageObjectId]!!.bytes)
        assertEquals(file.versions[0].id, saved.baseVersionId)
        assertEquals(file.id, saved.fileId)
    }

    @Test fun acceptedVersionRenameFailureResumesOnlyRenameAfterRelaunch() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("one").id)
        val draft = r.begin("two", first.fileId, first.baseVersionId).copy(filename = "Renamed.txt")
        r.service.edits.update(draft)
        r.tower.transientRenames = 1
        val pending = r.service.save(r.api, draft.id)
        assertTrue(pending.versionAccepted)
        assertTrue(pending.renamePending)
        assertEquals(TextEditState.PENDING, pending.state)
        val uploads = r.tower.count("POST", "/storage/prepare")
        val versions = r.tower.files.values.single().versions.size
        val reopened = TextAttachmentService(r.service.edits, r.service.cache)
        val saved = reopened.save(r.api, draft.id)
        assertEquals(TextEditState.SAVED, saved.state)
        assertEquals("Renamed.txt", r.tower.files.values.single().name)
        assertEquals(versions, r.tower.files.values.single().versions.size)
        assertEquals(uploads, r.tower.count("POST", "/storage/prepare"))
    }

    @Test fun lostRenameResponseReconcilesWithoutAnotherVersionOrPatch() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("one").id)
        val draft = r.begin("two", first.fileId, first.baseVersionId).copy(filename = "Renamed.txt")
        r.service.edits.update(draft)
        r.tower.dropReplyOnNext = { it.method == "PATCH" }
        assertTrue(r.service.save(r.api, draft.id).renamePending)
        assertEquals(TextEditState.SAVED, r.service.save(r.api, draft.id).state)
        assertEquals(2, r.tower.files.values.single().versions.size)
        assertEquals(1, r.tower.count("PATCH", "/files/${first.fileId}"))
    }

    @Test fun exactLineageLookupFindsArchivedCanonicalBeyondGenericListLimit() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("one").id)
        val canonical = r.tower.files.values.single()
        canonical.archived = true
        repeat(205) {
            val id = Fixtures.newId()
            r.tower.files[id] = FakeTextTower.FileRow(id, "unrelated.txt", mapOf("origin_message_id" to Fixtures.newId(), "origin_storage_object_id" to Fixtures.newId()))
        }
        val history = r.service.history(r.api, ws, app, ch, message, r.original)
        assertEquals(first.fileId, history.fileId)
        val lookups = r.tower.requests.filter { it.method == "GET" && it.url.contains("/channels/$ch/files?") }
        assertTrue(lookups.all { it.url.contains("origin_message_id=$message") && it.url.contains("origin_storage_object_id=${r.original.storageObjectId}") })
        assertTrue(lookups.any { it.url.contains("archived=true") })
    }

    @Test fun acceptedVersionWithFailedLocalCommitReconcilesAfterReopen() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("one").id)
        val directory = tempDir()
        var failed = false
        val store = TextEditStore(directory) {
            if (!failed && r.tower.files.values.single().versions.size == 2) {
                failed = true
                throw java.io.IOException("acceptance record failed")
            }
        }
        val service = TextAttachmentService(store, r.service.cache)
        val edit = service.beginEdit(Fixtures.newId(), "conn", ws, ch, app, message, r.original, "Pasted text.txt", "two", first.fileId, first.baseVersionId)
        try { service.save(r.api, edit.id); fail("injected commit failure expected") } catch (_: java.io.IOException) { }
        assertTrue(TextEditStore(directory).get(edit.id)!!.uncertain)
        val reopened = TextAttachmentService(TextEditStore(directory), r.service.cache)
        assertEquals(TextEditState.SAVED, reopened.save(r.api, edit.id).state)
        assertEquals(2, r.tower.files.values.single().versions.size)
        assertEquals("two", reopened.edits.text(reopened.edits.get(edit.id)!!))
    }

    @Test fun intentionallyLocalEditSurvivesReopenUntilExplicitSave() = runBlocking {
        val r = rig()
        val directory = tempDir()
        val service = TextAttachmentService(TextEditStore(directory), r.service.cache)
        val edit = service.beginEdit(Fixtures.newId(), "conn", ws, ch, app, message, r.original,
            "Local.txt", "kept locally", null, null, localOnly = true)
        val reopened = TextEditStore(directory).get(edit.id)!!
        assertTrue(reopened.localOnly)
        assertEquals("kept locally", TextEditStore(directory).text(reopened))
        assertTrue(r.tower.files.isEmpty())
        val saved = TextAttachmentService(TextEditStore(directory), r.service.cache).save(r.api, edit.id)
        assertEquals(TextEditState.SAVED, saved.state)
        assertFalse(saved.localOnly)
        assertEquals(1, r.tower.files.size)
    }

    @Test fun laterEditsAddVersionsOnTheBaseVersion() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("one").id)
        val second = r.service.save(r.api, r.begin("two", first.fileId, first.baseVersionId).id)
        assertEquals(TextEditState.SAVED, second.state)
        val file = r.tower.files.values.single()
        assertEquals(2, file.versions.size)
        assertEquals(file.versions[0].id, file.versions[1].base)
        val history = r.service.history(r.api, ws, app, ch, message, r.original)
        assertEquals(listOf("Original", "Edit 1", "Edit 2"), history.entries.map { it.label })
        assertTrue(history.entries[0].isOriginal)
        assertTrue(history.entries[2].isCurrent)
        assertFalse(history.entries[0].isCurrent)
        // Every earlier version stays readable, byte for byte.
        assertEquals(r.originalText, TextAttachments.text(r.service.read(r.api, ws, app, history.fileId, history.entries[0])))
        assertEquals("one", TextAttachments.text(r.service.read(r.api, ws, app, history.fileId, history.entries[1])))
        assertEquals("two", TextAttachments.text(r.service.read(r.api, ws, app, history.fileId, history.entries[2])))
    }

    @Test fun staleBaseKeepsTheLocalEditAndNeverOverwrites() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("one").id)
        // Someone else saves a newer version.
        val other = r.service.save(r.api, r.begin("theirs", first.fileId, first.baseVersionId).id)
        assertEquals(TextEditState.SAVED, other.state)
        val mine = r.service.save(r.api, r.begin("mine", first.fileId, first.baseVersionId).id)
        assertEquals(TextEditState.CONFLICT, mine.state)
        assertEquals(2, r.tower.files.values.single().versions.size)
        assertEquals("mine", r.service.edits.text(mine))
        // The user's explicit choice: rebase on the latest, then save again.
        val rebased = r.service.rebase(r.api, mine.id)
        assertEquals(TextEditState.PENDING, rebased.state)
        assertEquals(r.tower.files.values.single().versions.last().id, rebased.baseVersionId)
        val resaved = r.service.save(r.api, mine.id)
        assertEquals(TextEditState.SAVED, resaved.state)
        assertEquals(3, r.tower.files.values.single().versions.size)
    }

    @Test fun anotherFirstEditOwnsTheLineageSoMineIsAConflictNotACopy() = runBlocking {
        val r = rig()
        // Their first edit exists on Tower.
        val theirs = r.service.save(r.api, r.begin("theirs").id)
        assertEquals(TextEditState.SAVED, theirs.state)
        val mine = r.service.save(r.api, r.begin("mine").id)
        assertEquals(TextEditState.CONFLICT, mine.state)
        assertEquals(1, r.tower.files.size)
        assertEquals(theirs.fileId, mine.fileId)
    }

    @Test fun offlineEditSurvivesAndUploadsOnceWhenBack() = runBlocking {
        val r = rig()
        r.tower.offline = true
        val id = r.begin("typed offline").id
        val pending = r.service.save(r.api, id)
        assertEquals(TextEditState.PENDING, pending.state)
        assertEquals("typed offline", r.service.edits.text(pending))
        // "Relaunch": a fresh store over the same directory still has it.
        r.tower.offline = false
        val saved = r.service.save(r.api, id)
        assertEquals(TextEditState.SAVED, saved.state)
        assertEquals(1, r.tower.files.size)
        assertEquals(1, r.tower.objects.values.count { it.bytes?.contentEquals(TextAttachments.bytes("typed offline")) == true })
    }

    @Test fun lostReplyOnFirstEditIsReconciledWithoutDuplicating() = runBlocking {
        val r = rig()
        r.tower.dropReplyOnNext = { it.method == "POST" && it.url.endsWith("/channels/$ch/files") }
        val id = r.begin("lost reply").id
        val first = r.service.save(r.api, id)
        assertEquals(TextEditState.PENDING, first.state)
        assertTrue(first.uncertain)
        assertEquals("Tower accepted it", 1, r.tower.files.size)
        val second = r.service.save(r.api, id)
        assertEquals(TextEditState.SAVED, second.state)
        assertEquals(1, r.tower.files.size)
        assertEquals(1, r.tower.count("POST", "/channels/$ch/files"))
    }

    @Test fun lostReplyOnVersionPostIsReconciled() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("one").id)
        r.tower.dropReplyOnNext = { it.method == "POST" && it.url.contains("/versions") }
        val id = r.begin("two", first.fileId, first.baseVersionId).id
        val lost = r.service.save(r.api, id)
        assertTrue(lost.uncertain)
        val done = r.service.save(r.api, id)
        assertEquals(TextEditState.SAVED, done.state)
        assertEquals(2, r.tower.files.values.single().versions.size)
    }

    @Test fun transientFailureOnFirstEditRetriesAfterReloadingLineage() = runBlocking {
        val r = rig()
        r.tower.transientCreates = 1
        val id = r.begin("again").id
        assertEquals(TextEditState.PENDING, r.service.save(r.api, id).state)
        assertEquals(TextEditState.SAVED, r.service.save(r.api, id).state)
        assertEquals(1, r.tower.files.size)
    }

    @Test fun permissionDeniedKeepsTheEditAsFailed() = runBlocking {
        val r = rig()
        r.tower.denyWrites = true
        val failed = r.service.save(r.api, r.begin("no permission").id)
        assertEquals(TextEditState.FAILED, failed.state)
        assertEquals("no permission", r.service.edits.text(failed))
        assertTrue(r.tower.files.isEmpty())
    }

    @Test fun cachedVersionsReadOffline() = runBlocking {
        val r = rig()
        val first = r.service.save(r.api, r.begin("cached text").id)
        val history = r.service.history(r.api, ws, app, ch, message, r.original)
        history.entries.forEach { r.service.read(r.api, ws, app, history.fileId, it) }
        r.tower.offline = true
        history.entries.forEach { assertNotNull(r.service.cached(it)) }
        assertEquals("cached text", TextAttachments.text(r.service.cached(history.entries[1])!!))
        assertNotNull(first.fileId)
        // A fresh service over the same directories (relaunch) still knows the history and the cached bytes.
        val again = TextAttachmentService(r.service.edits, r.service.cache)
        val offline = again.cachedHistory(ws, message, r.original)
        assertEquals(listOf("Original", "Edit 1"), offline.entries.map { it.label })
        assertEquals("cached text", TextAttachments.text(again.cached(offline.current)!!))
        assertEquals(r.originalText, TextAttachments.text(again.cached(offline.entries[0])!!))
    }

    @Test fun largeAndPathologicalTextRoundTripsExactly() = runBlocking {
        val r = rig()
        val huge = "x".repeat(4 * 1024 * 1024) + "\r\n" + "😀".repeat(1000)
        val saved = r.service.save(r.api, r.begin(huge).id)
        assertEquals(TextEditState.SAVED, saved.state)
        val history = r.service.history(r.api, ws, app, ch, message, r.original)
        val back = TextAttachments.text(r.service.read(r.api, ws, app, history.fileId, history.current))
        assertEquals(huge.length, back.length)
        assertTrue(huge == back)
    }

    @Test fun oversizeTextIsRefused() {
        val r = rig()
        try { r.begin("x".repeat(TextAttachments.MAX_BYTES + 1)); fail() } catch (e: IllegalArgumentException) { }
    }
}
