package studio.otherstuff.ambulando.app.ui.work

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.core.store.WriteState

class WriteStatusTest {
    @Test fun queueSendingValidationAndUnlockHaveDistinctTruthfulStatus() {
        val queued = writeStatusText(WriteState.QUEUED, "Upload", true, "Grant unavailable.", null)
        assertTrue(queued.contains("waiting for connection validation"))
        assertTrue(queued.contains("Grant unavailable."))
        assertFalse(queued.contains("sending…"))
        assertEquals("Upload: sending…", writeStatusText(WriteState.SENDING, "Upload", true, "Old error", null))
        assertEquals("Upload: waits for unlock", writeStatusText(WriteState.QUEUED, "Upload", false, null, null))
        assertFalse(writeStatusText(WriteState.QUEUED, "Upload", true, null, null).contains("Grant unavailable"))
    }
}
