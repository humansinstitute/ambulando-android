package studio.otherstuff.ambulando.core.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.support.tempDir

/** Late, non-cooperative IO must not reach the reply or a different message after edit cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerUploadIsolationTest {
    @Test fun cancelledReadReleasesPermissionAndCannotChangeRestoredDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = LocalStore(tempDir())
        val target = ChatTarget("connection", "workspace", "channel")
        val readGate = CompletableDeferred<Unit>()
        var released = 0
        var uploaded = 0
        val staging = tempDir()
        val uploads = ComposerUploads(store, staging, dispatcher, dispatcher) { uploaded++; "00000000-0000-4000-8000-000000000001" }
        var visible = "editing"
        val host = object : UploadTokenHost {
            override val uploadTarget = target
            override val uploadText get() = visible
            override fun replaceUploadToken(token: String, replacement: String): Boolean {
                if (!visible.contains(token)) return false
                visible = visible.replace(token, replacement); return true
            }
        }
        try {
            uploads.attach(host)
            val token = uploads.paste(listOf(PastedItem(UploadToken.Kind.IMAGE, read = {
                withContext(NonCancellable) { readGate.await() }; byteArrayOf(1,2,3)
            }, release = { released++ })), target, "app", "message").single()
            visible = token
            runCurrent()
            val id = uploads.items.value.single().id
            uploads.remove(id)
            visible = "restored reply"
            readGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, released)
            assertEquals(0, uploaded)
            assertEquals("restored reply", visible)
            assertTrue(uploads.items.value.isEmpty())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally { uploads.close(); store.close() }
    }
    @Test fun cancellationBeforeReadStartsStillReleasesPermissionExactlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = LocalStore(tempDir())
        var released = 0; var reads = 0
        val uploads = ComposerUploads(store, tempDir(), dispatcher, dispatcher) { error("Must not upload") }
        try {
            uploads.paste(listOf(PastedItem(UploadToken.Kind.IMAGE, read = { reads++; byteArrayOf(1) }, release = { released++ })), ChatTarget("c", "w", "ch"), "app", "message")
            uploads.remove(uploads.items.value.single().id)
            advanceUntilIdle()
            assertEquals(0, reads); assertEquals(1, released)
        } finally { uploads.close(); store.close() }
    }
}
