package studio.otherstuff.ambulando.core.sample

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.surfaces.HtmlDraftStore

class HtmlDraftStoreTest {
    @Test fun closeAndReopenPreserveExactSourceAndOriginalBase() = runBlocking {
        val root = Files.createTempDirectory("html-draft").toFile()
        try {
            val file = File(root,"boundary/html.json")
            val store = HtmlDraftStore(file)
            val expected = HtmlDraftStore.Draft("original-base", "<!doctype html>\n<p>Unicode ☀ and \\\"quotes\\\"</p>")
            store.save(expected)
            assertEquals(expected,HtmlDraftStore(file).load())
            store.save(expected.copy(source = "latest"))
            assertEquals("latest",store.load()?.source)
            assertFalse(File(file.parentFile,file.name+".pending").exists())
            store.clear()
            assertNull(store.load())
        } finally { root.deleteRecursively() }
    }
    @Test fun successfulSaveFencesDelayedEditsAcrossEditorInstances() = runBlocking {
        val root = Files.createTempDirectory("html-draft-clear").toFile()
        try {
            val file = File(root,"html.json")
            val leavingEditor = HtmlDraftStore(file)
            val acceptedEdit = leavingEditor.ticket()
            leavingEditor.save(HtmlDraftStore.Draft("base","saved text"),acceptedEdit)
            val reopenedEditor = HtmlDraftStore(file)
            reopenedEditor.clear()
            // A view-model job from the old dialog can start after successful save/clear.
            leavingEditor.save(HtmlDraftStore.Draft("base","obsolete text"),acceptedEdit)
            assertNull(reopenedEditor.load())
            reopenedEditor.save(HtmlDraftStore.Draft("next base","new edit"),reopenedEditor.ticket())
            assertEquals("new edit",leavingEditor.load()?.source)
        } finally { root.deleteRecursively() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationAfterAtomicWriteStartsStillKeepsLatestRecoveryText() = runTest {
        val root = Files.createTempDirectory("html-draft-cancel").toFile()
        try {
            val file = File(root,"html.json")
            val io = StandardTestDispatcher(testScheduler)
            val store = HtmlDraftStore(file,io)
            val edit = HtmlDraftStore.Draft("original","latest accepted text")
            val job = launch(start=CoroutineStart.UNDISPATCHED) { store.save(edit,store.ticket()) }
            job.cancel() // IO is queued; cancellation must not interrupt the atomic replacement.
            advanceUntilIdle()
            assertEquals(edit,HtmlDraftStore(file).load())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedWritePreservesPreviouslyDurableSource() = runBlocking {
        val root = Files.createTempDirectory("html-draft-fail").toFile()
        try {
            val file = File(root,"html.json")
            val store = HtmlDraftStore(file)
            val previous = HtmlDraftStore.Draft("base","last durable edit")
            store.save(previous)
            File(root,"html.json.pending").mkdir()
            assertTrue(runCatching { store.save(previous.copy(source="new edit")) }.isFailure)
            assertEquals(previous,store.load())
        } finally { root.deleteRecursively() }
    }
    @Test fun malformedDraftIsNotSilentlyOverwrittenOrDiscarded() = runBlocking {
        val root = Files.createTempDirectory("html-draft-invalid").toFile()
        try {
            val file = File(root,"html.json").apply { writeText("incomplete recovery data") }
            assertTrue(runCatching { HtmlDraftStore(file).load() }.isFailure)
            assertEquals("incomplete recovery data",file.readText())
        } finally { root.deleteRecursively() }
    }
}
