package studio.otherstuff.ambulando.core.sample

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.*
import studio.otherstuff.ambulando.core.transport.*

class HtmlSourceSaveTest {
    private val version = "00000000-0000-4000-8000-000000000001"
    private fun accepted(id: String?) = VersionOutcome.Saved(LineageFile(FileRecord(
        SampleIds.STUDIO, SampleIds.BOARD_FILE, null, SampleIds.GENERAL, null, null, "Saved.html",
        contentType = "text/html", sizeBytes = 1, sha256 = null, createdAt = "", updatedAt = ""), id), id, false)

    @Test fun acknowledgedSaveReadFailureReopensAsVerificationOnlyAndFencesOldWrites() = runBlocking {
        val root = Files.createTempDirectory("html-save").toFile()
        try {
            val file = java.io.File(root,"draft.json"); val drafts = HtmlDraftStore(file)
            val draft = HtmlDraftStore.Draft("base", "saved text")
            val oldTicket = drafts.ticket()
            var uploads = 0
            assertTrue(runCatching { HtmlSourceSave(drafts).save(draft,{ "object" },
                { uploads++; accepted(version) }, { error("must use exact saved version") },
                { throw java.io.IOException("offline after acknowledgement") }, {}) }.isFailure)
            drafts.save(draft.copy(source="obsolete"),oldTicket)
            val reopened = HtmlDraftStore(file)
            val pending = reopened.load()!!
            assertTrue(pending.accepted); assertEquals(version,pending.savedVersionId)
            assertEquals(draft.source,pending.source)
            val verifiedVersion = HtmlSourceSave(reopened).save(pending,{ error("must not upload twice") },{ uploads++; error("must not resubmit") },{ error("no head lookup") },
                { assertEquals(version,it); draft.source.toByteArray() },{})
            assertEquals(version, verifiedVersion)
            assertEquals(1,uploads); assertNull(reopened.load())
        } finally { root.deleteRecursively() }
    }

    @Test fun nullableAcknowledgementUsesBoundedHeadAndMismatchingConcurrentHeadKeepsRecovery() = runBlocking {
        val root = Files.createTempDirectory("html-save-null").toFile()
        try {
            val drafts = HtmlDraftStore(java.io.File(root,"draft.json"))
            val draft = HtmlDraftStore.Draft("base", "my edit")
            var writes = 0; var heads = 0
            val saver = HtmlSourceSave(drafts)
            assertTrue(runCatching { saver.save(draft,{ "object" },{ writes++; accepted(null) },{ heads++; version },
                { "concurrent edit".toByteArray() },{}) }.isFailure)
            assertTrue(drafts.load()!!.accepted)
            saver.save(drafts.load()!!,{ error("must not upload twice") },{ writes++; error("verification only") },{ heads++; version },
                { draft.source.toByteArray() },{})
            assertEquals(1,writes); assertEquals(2,heads); assertNull(drafts.load())
        } finally { root.deleteRecursively() }
    }

    @Test fun exactSavedVersionWorksPastTwoHundredVersionsDespiteNewerHead() = runBlocking {
        val root = Files.createTempDirectory("html-save-history").toFile()
        try {
            val drafts = HtmlDraftStore(java.io.File(root,"draft.json"))
            val draft = HtmlDraftStore.Draft("base", "exact immutable saved bytes")
            var requests = 0
            val api = TowerAPI(TowerClient(TowerOrigin("https://tower.example.com"), object : HttpTransport {
                override suspend fun send(request: HttpRequest): HttpResponse {
                    requests++
                    // A full-history route would fail at >=200. No history listing is needed here.
                    assertTrue(request.url.endsWith("/versions/$version/object"))
                    return HttpResponse(200,draft.source.toByteArray())
                }
            },SampleSigners.primary))
            HtmlSourceSave(drafts).save(draft,{ "object" },{ accepted(version) },{ error("new head must not select another version") },
                { api.savedHtmlBytes(SampleIds.STUDIO,studio.otherstuff.ambulando.core.support.TestKeys.NPUB,SampleIds.BOARD_FILE,it) },{})
            assertEquals(1,requests); assertNull(drafts.load())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingVersionAndHeadPreserveAcknowledgementWithoutDuplicateCommit() = runBlocking {
        val root = Files.createTempDirectory("html-save-no-id").toFile()
        try {
            val drafts = HtmlDraftStore(java.io.File(root,"draft.json"))
            var commits=0
            repeat(2) {
                val pending=drafts.load() ?: HtmlDraftStore.Draft("base","edit")
                assertTrue(runCatching { HtmlSourceSave(drafts).save(pending,{ "object" },{ commits++; accepted(null) },{ null },
                    { error("no version to read") },{}) }.isFailure)
            }
            assertEquals(1,commits); assertTrue(drafts.load()!!.accepted)
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationAtAcknowledgementStillPersistsReceiptBeforeAnyReadback() = runBlocking {
        val root = Files.createTempDirectory("html-save-ack-cancel").toFile()
        try {
            val drafts = HtmlDraftStore(java.io.File(root,"draft.json"))
            val draft = HtmlDraftStore.Draft("base","accepted edit")
            val job = launch {
                HtmlSourceSave(drafts).save(draft,{ "uploaded-object" },{
                    currentCoroutineContext()[Job]!!.cancel()
                    accepted(version)
                },{ error("cancelled work must not read head") },{ error("cancelled work must not read bytes") },{
                    currentCoroutineContext().ensureActive()
                })
            }
            job.join()
            val receipt = drafts.load()!!
            assertTrue(job.isCancelled)
            assertTrue(receipt.accepted)
            assertEquals(version,receipt.savedVersionId)
            assertEquals("uploaded-object",receipt.uploadedObjectId)
        } finally { root.deleteRecursively() }
    }

    @Test fun lostPostResponseRetriesIdenticalObjectBaseAndMutationWithoutReupload() = runBlocking {
        val root = Files.createTempDirectory("html-save-lost-response").toFile()
        try {
            val drafts = HtmlDraftStore(java.io.File(root,"draft.json"))
            val draft = HtmlDraftStore.Draft("base","saved edit")
            var uploads = 0
            val requests = mutableListOf<Triple<String,String,String>>()
            suspend fun commit(pending: HtmlDraftStore.Draft): VersionOutcome {
                requests += Triple(pending.base,requireNotNull(pending.uploadedObjectId),pending.mutationId)
                return if (requests.size == 1) VersionOutcome.Retryable(null,"response lost") else accepted(version)
            }
            val saver = HtmlSourceSave(drafts)
            assertTrue(runCatching { saver.save(draft,{ uploads++; "exact-object" },::commit,
                { error("exact version available") },{ draft.source.toByteArray() },{}) }.isFailure)
            val recovered = HtmlDraftStore(java.io.File(root,"draft.json")).load()!!
            assertFalse(recovered.accepted)
            saver.save(recovered,{ uploads++; error("no second upload") },::commit,
                { error("exact version available") },{ draft.source.toByteArray() },{})
            assertEquals(1,uploads); assertEquals(2,requests.size)
            assertEquals(requests[0],requests[1]); assertEquals(draft.mutationId,requests[1].third)
            assertNull(drafts.load())
        } finally { root.deleteRecursively() }
    }

}
