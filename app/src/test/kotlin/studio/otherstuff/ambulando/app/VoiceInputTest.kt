package studio.otherstuff.ambulando.app

import org.junit.Assert.*
import org.junit.Test
import studio.otherstuff.ambulando.app.voice.*

class VoiceInputTest {
    @Test fun silenceIsStillAndLevelIsBoundedMonotonicAcrossCapturedAmplitudeRange() {
        assertEquals(0f, microphoneLevel(0))
        assertEquals(0f, microphoneLevel(-1))
        assertEquals(1f, microphoneLevel(32767))
        assertEquals(1f, microphoneLevel(Int.MAX_VALUE))
        var previous = 0f
        for (amplitude in 0..32767) {
            val level = microphoneLevel(amplitude)
            assertTrue(level in previous..1f)
            previous = level
        }
        assertTrue(microphoneLevel(1000) > microphoneLevel(100))
    }
    @Test fun requestedDeviceNeverClaimsActiveWithoutReportedRoute() {
        val requested = VoiceInput(1, "USB microphone")
        val pending = VoiceInputStatus(requested = requested)
        assertFalse(pending.routeText.contains(requested.label))
        assertTrue(pending.routeText.contains("not yet reported"))
        val fallback = pending.copy(actual = VoiceInput(2, "Built-in microphone"))
        assertTrue(fallback.routeText.contains("Built-in microphone"))
        assertTrue(fallback.routeText.contains("different input"))
        assertFalse(pending.copy(actual = requested).routeText.contains("different input"))
    }
}
