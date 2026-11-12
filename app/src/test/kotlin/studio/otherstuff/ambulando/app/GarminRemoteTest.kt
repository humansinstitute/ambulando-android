package studio.otherstuff.ambulando.app

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.garmin.*
import studio.otherstuff.ambulando.app.voice.*
import java.io.File
import java.nio.file.Files

class GarminRemoteTest {
    @Test fun acceptsSdkCanonicalUppercaseAppOnly() {
        val app = com.garmin.android.connectiq.IQApp(GARMIN_APP_ID)
        assertEquals(GARMIN_APP_ID.uppercase(), app.applicationId)
        assertTrue(isRecorderApp(app))
        assertTrue(isRecorderApp(com.garmin.android.connectiq.IQApp("4edbc3d4-1f92-4958-b53a-29734ba5a821")))
        assertFalse(isRecorderApp(com.garmin.android.connectiq.IQApp("00000000000000000000000000000000")))
    }

    private fun destination(channel: String = "original") = VoiceDestination("connection", "https://tower.example", "tower", "account", "workspace", "app", channel, null, "Chosen DM")
    private fun journal(dir: File) = RemoteJournal(File(dir, "remote.json"))
    @Test fun duplicateAndRestartRetainOriginalSessionAndDestination() {
        val dir = Files.createTempDirectory("remote").toFile()
        try {
            val j = journal(dir)
            j.bind(RemoteBinding(7, destination()))
            val first = j.begin("watch_1")
            assertEquals(first, j.begin("watch_1"))
            assertThrows(IllegalStateException::class.java) { j.begin("watch_2") }
            assertThrows(IllegalStateException::class.java) { j.bind(RemoteBinding(8, destination("different"))) }
            j.update("watch_1", "sent", "Acknowledged")
            j.bind(RemoteBinding(8, destination("different")))
            val restored = journal(dir)
            assertEquals(first.recording, restored.begin("watch_1").recording)
            assertEquals("original", restored.find("watch_1")!!.destination.channelId)
            assertEquals("sent", restored.begin("watch_1").status)
            assertNotEquals(first.recording, restored.begin("watch_2").recording)
        } finally { dir.deleteRecursively() }
    }
    @Test fun terminalTombstoneSurvivesLateStartAndRevisionIncreases() {
        val dir = Files.createTempDirectory("remote").toFile()
        try {
            val j = journal(dir); j.bind(RemoteBinding(7, destination()))
            j.begin("out_of_order")
            val cancelled = j.update("out_of_order", "cancelled", "Finish before Start")
            assertEquals(1, cancelled.revision)
            assertEquals(cancelled, j.update("out_of_order", "cancelled", "Finish before Start"))
            assertEquals(cancelled, journal(dir).begin("out_of_order"))
            assertEquals("cancelled", j.begin("out_of_order").status)
            assertThrows(IllegalArgumentException::class.java) { j.begin("../../bad") }
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptJournalFailsClosedAndDoesNotOverwriteEvidence() {
        val dir = Files.createTempDirectory("remote").toFile()
        try {
            val f = File(dir, "remote.json"); f.writeText("broken")
            val j = journal(dir)
            assertTrue(j.damaged)
            assertThrows(IllegalStateException::class.java) { j.bind(RemoteBinding(7, destination())) }
            assertEquals("broken", f.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun staleFinishCancelAndRetryCannotAffectAnotherDraft() {
        assertEquals(RemoteAction.NONE, remoteAction("recording", "finish", false, VoicePhase.RECORDING))
        assertEquals(RemoteAction.NONE, remoteAction("error", "cancel", false, VoicePhase.REVIEW))
        assertEquals(RemoteAction.NONE, remoteAction("error", "retry", false, VoicePhase.REVIEW))
        assertEquals(RemoteAction.FINISH, remoteAction("recording", "finish", true, VoicePhase.RECORDING))
        assertEquals(RemoteAction.FINISH, remoteAction("waiting", "finish", true, VoicePhase.RECORDING))
        assertEquals(RemoteAction.RETRY, remoteAction("error", "retry", true, VoicePhase.REVIEW))
    }
    @Test fun duplicateFinishAndCancelDuringSendingAreNoOps() {
        for (op in listOf("finish", "cancel", "retry", "start")) {
            assertEquals(RemoteAction.NONE, remoteAction("sending", op, true, VoicePhase.SENDING))
            assertEquals(RemoteAction.NONE, remoteAction("sent", op, false, null))
        }
        assertEquals(RemoteAction.CANCEL, remoteAction("waiting", "finish", true, VoicePhase.STARTING))
        assertEquals(RemoteAction.NONE, remoteAction("error", "retry", true, VoicePhase.INTERRUPTED))
    }
    @Test fun headsetRequirementAndStableMessageIdSurviveDraftRecovery() {
        val dir = Files.createTempDirectory("remote-draft").toFile()
        try {
            val id = java.util.UUID.randomUUID().toString()
            val store = VoiceDraftStore(dir)
            val draft = store.begin(destination(), id, true)
            store.audio(draft).writeText("synthetic test bytes")
            store.save(draft.copy(phase = VoicePhase.SENDING, objectId = "existing-object", uploadURL = "https://objects.example/put"))
            val recovered = VoiceDraftStore(dir)
            assertEquals(id, recovered.state.value!!.id)
            assertTrue(recovered.state.value!!.headsetOnly)
            assertEquals("existing-object", recovered.state.value!!.objectId)
            assertEquals(destination(), recovered.state.value!!.destination)
            assertTrue(recovered.audio(draft).exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun automaticConsentIsAbsentOnUpgradeAndInvalidatedByAnyBindingChange() {
        val dir = Files.createTempDirectory("watch-consent").toFile()
        try {
            val j = journal(dir)
            val binding = RemoteBinding(7, destination())
            j.bind(binding)
            assertNull(j.data.consent)
            j.consent(WatchConsent(binding, 100))
            assertEquals(WatchConsent(binding, 100), journal(dir).data.consent)
            j.bind(binding.copy(destination = destination("other")))
            assertNull(j.data.consent)
            assertThrows(IllegalStateException::class.java) { j.consent(WatchConsent(binding, 101)) }
            j.consent(WatchConsent(j.data.binding!!, 102))
            j.consent(null)
            assertEquals("other", journal(dir).data.binding!!.destination.channelId)
        } finally { dir.deleteRecursively() }
    }
    @Test fun queuedAndDuplicateStartsCannotBecomeFreshAfterEnablingOrRestart() {
        assertFalse(freshWatchStart("100_1234", 100, 101))
        assertFalse(freshWatchStart("99_1234", 100, 101))
        assertTrue(freshWatchStart("101_1234", 100, 101))
        assertFalse(freshWatchStart("101_1234", 102, 103))
        assertFalse(freshWatchStart("watch_1234", 100, 101))
        assertFalse(freshWatchStart("200_1234", 100, 101))
        assertFalse(freshWatchStart("101_1234", 100, 222))
    }

}
