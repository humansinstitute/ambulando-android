package studio.otherstuff.ambulando.app

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import studio.otherstuff.ambulando.app.voice.*
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class VoiceDraftTest {
    private fun destination() = VoiceDestination("connection", "https://tower.example", "tower", "signer", "workspace", "app", "channel", "thread", "Original channel")
    @Test fun destinationRejectsChangedAccountOriginTowerWorkspaceAppAndConnection() {
        val c = studio.otherstuff.ambulando.core.store.ConnectionRecord("connection", "https://tower.example", "signer", "tower", "")
        val w = studio.otherstuff.ambulando.core.transport.WorkspaceRecord("workspace", "app", "Workspace", null, null, null, null, kotlinx.serialization.json.JsonObject(emptyMap()))
        val d = destination()
        assertTrue(d.matches(c, w))
        assertFalse(d.matches(c.copy(id = "other"), w))
        assertFalse(d.matches(c.copy(origin = "https://other.example"), w))
        assertFalse(d.matches(c.copy(towerNpub = "other"), w))
        assertFalse(d.matches(c.copy(signerNpub = "other"), w))
        assertFalse(d.matches(c, w.copy(id = "other")))
        assertFalse(d.matches(c, w.copy(appNpub = "other")))
        assertFalse(d.matches(c, w.copy(accessRemoved = true)))
        assertThrows(IllegalArgumentException::class.java) { VoiceDestination.capture(c.copy(towerNpub = null), w, d.target, "Channel") }
    }
    @Test fun interruptionKeepsBytesAndExactDestinationAndCannotStartAnother() {
        val directory = Files.createTempDirectory("voice").toFile()
        try {
            val store = VoiceDraftStore(directory)
            val draft = store.begin(destination())
            store.audio(draft).writeBytes(byteArrayOf(1,2,3))
            store.save(draft.copy(phase = VoicePhase.RECORDING))
            val recovered = VoiceDraftStore(directory)
            assertEquals(VoicePhase.INTERRUPTED, recovered.state.value!!.phase)
            assertEquals(destination(), recovered.state.value!!.destination)
            assertEquals("thread", recovered.state.value!!.destination.target.threadId)
            assertArrayEquals(byteArrayOf(1,2,3), recovered.audio(draft).readBytes())
            assertThrows(IllegalStateException::class.java) { recovered.begin(destination().copy(channelId = "elsewhere")) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedSendKeepsPreparedObjectAndStableRequestForRetryAndCancellationDeletesBoth() {
        val directory = Files.createTempDirectory("voice").toFile()
        try {
            val store = VoiceDraftStore(directory)
            val draft = store.begin(destination())
            store.audio(draft).writeText("synthetic not actual audio")
            store.save(draft.copy(phase = VoicePhase.SENDING, objectId = "object", uploadURL = "https://objects.example/upload", fileId = "file"))
            val recovered = VoiceDraftStore(directory)
            val retry = recovered.state.value!!
            assertEquals(VoicePhase.REVIEW, retry.phase)
            assertEquals(draft.id, retry.id)
            assertEquals("object", retry.objectId)
            assertEquals("file", retry.fileId)
            assertTrue(recovered.audio(retry).isFile)
            recovered.clear()
            assertNull(recovered.state.value)
            assertFalse(recovered.audio(retry).exists())
            assertNull(VoiceDraftStore(directory).state.value)
        } finally { directory.deleteRecursively() }
    }
    @Test fun deniedMicrophoneNeverCreatesRecordingOrStartsService() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val directory = Files.createTempDirectory("voice").toFile()
        try {
            val voice = VoiceRecorder(context, directory, true)
            assertThrows(IllegalStateException::class.java) { voice.start(destination()) }
            assertNull(voice.state.value)
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptJournalCannotAcquireCurrentNavigationAsDestination() {
        val directory = Files.createTempDirectory("voice").toFile()
        try {
            java.io.File(directory, "draft.json").writeText("{broken}")
            assertNull(VoiceDraftStore(directory).state.value)
        } finally { directory.deleteRecursively() }
    }
    @Test fun watchPromptIsPinnedThroughRestartAndUploadRetryWhileOldDraftsStayUnchanged() {
        val directory = Files.createTempDirectory("voice-prompt").toFile()
        try {
            val store = VoiceDraftStore(directory)
            val pinned = store.begin(destination(), headsetOnly = true, prompt = "Original instruction")
            store.save(pinned.copy(phase = VoicePhase.SENDING, objectId = "object"))
            val restored = VoiceDraftStore(directory).state.value!!
            assertEquals("Original instruction", restored.prompt)
            assertTrue(voiceMessageBody(restored, "object").startsWith("Original instruction\n\n"))
            assertEquals(restored.id, pinned.id)
            val old = restored.copy(prompt = "")
            assertEquals(studio.otherstuff.ambulando.core.chat.StorageMarkdown.file("object", old.filename), voiceMessageBody(old, "object"))
        } finally { directory.deleteRecursively() }
    }

}
