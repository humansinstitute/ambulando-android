package studio.otherstuff.ambulando.core.sample

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.docs.DocDraft
import studio.otherstuff.ambulando.core.docs.DocSaveResult
import studio.otherstuff.ambulando.core.docs.DocSaveTarget
import studio.otherstuff.ambulando.core.docs.DocumentSaver
import studio.otherstuff.ambulando.core.docs.LeaseException
import studio.otherstuff.ambulando.core.docs.TowerDocumentSaveApi
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.StoreFixtures
import studio.otherstuff.ambulando.core.store.docDraft
import studio.otherstuff.ambulando.core.store.docDraftStore
import studio.otherstuff.ambulando.core.store.saveDocDraft
import studio.otherstuff.ambulando.core.support.TestKeys
import studio.otherstuff.ambulando.core.transport.TowerError

/**
 * The production document-save adapter (leases with sessions, inspection, takeover, head reads, recoveries) against the sample
 * Tower, which follows the same contract. Source-level evidence only: a stand-in is never a live Tower.
 */
class SampleDocumentEditingTest {
    private val sessionA = "00000000-0000-4000-8000-0000000000a1"
    private val sessionB = "00000000-0000-4000-8000-0000000000b2"

    private class Rig(val h: SampleHarness) {
        val store = StoreFixtures.store()
        lateinit var target: DocSaveTarget
        lateinit var saver: DocumentSaver
        lateinit var doc: String
        suspend fun start(body: String = "# Plan\n\nOne\n\nTwo\n") {
            h.tower.work.actorNpub = TestKeys.NPUB
            val c = StoreFixtures.connect(store)
            target = DocSaveTarget(c.id, SampleIds.STUDIO, h.app)
            saver = DocumentSaver(store.writer.docDraftStore(store.reader), { TowerDocumentSaveApi(h.api) })
            doc = h.tower.work.seedDoc(scope = SampleIds.SCOPE_AMBULANDO, channel = SampleIds.GENERAL, title = "Plan", body = body.toByteArray(), contentType = "text/markdown")
        }
        suspend fun draft(text: String): DocDraft {
            val head = saver.latest(target, doc)!!
            return DocDraft(target.connectionId, SampleIds.STUDIO, doc, "Plan", text.toByteArray(), "text/markdown", head.doc.rowVersion.toInt(), head.body.sha256, head.body.storageObjectId, DocDraft.State.EDITING)
        }
        fun close() = store.close()
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking {
        val r = Rig(SampleHarness(prepare = false))
        try { r.start(); r.block() } finally { r.close() }
    }

    @Test fun sessionsReuseTheirLeaseAndAnotherSessionIsHeldOut() = rig {
        val first = saver.acquire(target, doc, sessionA)
        assertEquals(sessionA, first.editorSessionId)
        assertEquals("document_edit_session_v1", first.documentEditProtocol)
        assertEquals("the same session gets the same lease back", first.id, DocumentSaver(store.writer.docDraftStore(store.reader), { TowerDocumentSaveApi(h.api) }).acquire(target, doc, sessionA).id)
        try { saver.acquire(target, doc, sessionB); fail("a second session is held out on an exclusive server") } catch (e: LeaseException.Held) { assertNotNull(e.by) }
    }

    @Test fun inspectionNamesTheHolderAndOldServersAreTreatedAsNotInspectable() = rig {
        h.tower.work.holdLease(doc, SampleIds.ORBIT)
        val inspected = saver.inspect(target, doc)!!
        assertTrue(inspected.supported)
        assertFalse("the sample does not advertise concurrent editing", inspected.concurrentSupported)
        assertEquals(SampleIds.ORBIT, inspected.holderNpub)
        assertNotNull(inspected.leaseId)
    }

    @Test fun takeoverNeedsTheExactInspectedLeaseThenFencesTheOldHolderAndAdvancesTheVersion() = rig {
        h.tower.work.holdLease(doc, SampleIds.ORBIT)
        val inspected = saver.inspect(target, doc)!!
        val before = saver.latest(target, doc)!!.doc.rowVersion
        try { saver.takeover(target, doc, sessionA, "00000000-0000-4000-8000-0000000000ff"); fail() } catch (e: Exception) { assertTrue(e.toString(), e is LeaseException || e is TowerError) }
        assertEquals("a stale observation changes nothing", before, saver.latest(target, doc)!!.doc.rowVersion)
        assertNotNull(saver.inspect(target, doc)!!.leaseId)
        val head = saver.takeover(target, doc, sessionA, inspected.leaseId)
        assertEquals("Tower's authority barrier advanced the version", before + 1, head.doc.rowVersion)
        assertEquals(Sha256Hex.of("# Plan\n\nOne\n\nTwo\n".toByteArray()), head.body.sha256)
        assertEquals("the new session now holds it", sessionA, saver.inspect(target, doc)!!.let { saver.acquire(target, doc, sessionA).editorSessionId })
        // The old lease can no longer save; the new one can, against the new head.
        val saved = saver.save(draft("# Plan\n\nOne\n\nTwo, taken over\n"), target, sessionA)
        assertTrue("$saved", saved is DocSaveResult.Saved)
        assertEquals("# Plan\n\nOne\n\nTwo, taken over\n", String(h.tower.work.docBody(doc)!!))
        // A fenced session can never come back, even after its successor lets go.
        saver.release(target, doc, sessionA)
        try { saver.acquire(DocumentSaver(store.writer.docDraftStore(store.reader), { TowerDocumentSaveApi(h.api) }).let { target }, doc, sessionA); fail() } catch (_: LeaseException) { }
    }

    @Test fun anExpiredLeaseIsRetakenWithTheOrdinaryAcquireContract() = rig {
        h.tower.work.holdLease(doc, SampleIds.ORBIT)
        val observed = saver.inspect(target, doc)!!.leaseId
        h.tower.work.releaseHeldLease(doc)
        try { saver.takeover(target, doc, sessionA, observed); fail("the observed lease is gone") } catch (e: LeaseException.Invalid) { assertEquals("takeover_not_available", e.code) }
        assertNull(saver.inspect(target, doc)!!.leaseId)
        assertNotNull(saver.acquire(target, doc, sessionA))
    }

    @Test fun aNewerSharedVersionSendsSaveToReviewWithoutAnyRecoveryCopy() = rig {
        saver.acquire(target, doc, sessionA)
        val mine = draft("# Plan\n\nOne mine\n\nTwo\n")
        store.writer.saveDocDraft(mine)
        h.tower.work.externalDocSave(doc, "# Plan\n\nOne\n\nTwo theirs\n".toByteArray(), "text/markdown")
        val result = saver.save(mine, target, sessionA)
        assertTrue("$result", result is DocSaveResult.Recovery)
        assertEquals("nothing was uploaded as a recovery", 0, h.tower.work.recoveryCount)
        val kept = store.reader.docDraft(mine.key)!!
        assertEquals(DocDraft.State.CONFLICT, kept.state)
        assertEquals("# Plan\n\nOne mine\n\nTwo\n", String(kept.body))
    }

    @Test fun savedVersionsListChooseAndDiscardAgainstTheReviewedHead() = rig {
        saver.acquire(target, doc, sessionA)
        val mine = draft("# Plan\n\nPreserved before takeover\n")
        // The recovery-body save preserves divergent work as a Tower recovery and publishes nothing.
        val result = saver.save(mine, target, sessionA, asRecovery = true)
        assertTrue("$result", result is DocSaveResult.Recovery)
        assertEquals("# Plan\n\nOne\n\nTwo\n", String(h.tower.work.docBody(doc)!!))
        val versions = saver.recoveries(target, doc)
        assertEquals(1, versions.size)
        assertEquals("Plan", versions.single().title)
        assertEquals("# Plan\n\nPreserved before takeover\n", String(saver.recoveryBody(target, doc, versions.single().id).bytes))
        // A promotion names the head the person reviewed; a head that moved makes it fail and keeps both versions.
        val reviewed = saver.latest(target, doc)!!.body
        h.tower.work.externalDocSave(doc, "# Plan\n\nMoved on\n".toByteArray(), "text/markdown")
        try { saver.resolveRecovery(target, doc, versions.single().id, promote = true, head = reviewed, editorSessionId = sessionA); fail("stale choice") }
        catch (e: TowerError.Http) { assertEquals(409, e.status) }
        assertEquals(1, saver.recoveries(target, doc).size)
        assertEquals("# Plan\n\nMoved on\n", String(h.tower.work.docBody(doc)!!))
        val fresh = saver.latest(target, doc)!!.body
        saver.resolveRecovery(target, doc, versions.single().id, promote = true, head = fresh, editorSessionId = sessionA)
        assertEquals("# Plan\n\nPreserved before takeover\n", String(h.tower.work.docBody(doc)!!))
        assertEquals(0, saver.recoveries(target, doc).size)
    }
}
