package studio.otherstuff.ambulando.app.integration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.voice.*

/** Production environment and real Android service/capture; isolated package, synthetic destination, no upload. */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class VoiceServiceStartupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }
    private fun test(grantMicrophone: Boolean = true, block: (VoiceRecorder, NotificationManager) -> Unit) {
        check(context.packageName.endsWith(".watchtest")) { "Use -PisolatedWatchTest=true; never run against user data" }
        if (grantMicrophone) shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        else check(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { "Revoke microphone permission before this isolated test" }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val manager = context.getSystemService(NotificationManager::class.java)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val app = context.applicationContext as AmbulandoApplication
            val voice = requireNotNull(app.activeModel).voice
            assertFalse(app.activeModel!!.environment.isSample)
            instrumentation.runOnMainSync { voice.cancel() }
            try { block(voice, manager) }
            finally {
                instrumentation.runOnMainSync { voice.cancel() }
            }
        }
    }
    private val destination get() = VoiceDestination("connection", "https://tower.example", "tower", "signer", "workspace", "app", "channel", null, "Synthetic recording")

    private fun assertNoCapture(voice: VoiceRecorder) {
        assertNull(voice.service)
        assertTrue(context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
    @Test fun aDeniedPermissionStillSettlesForegroundRequestWithoutMicrophone() = test(grantMicrophone = false) { voice, manager ->
        instrumentation.runOnMainSync {
            val draft = voice.drafts.begin(destination)
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, VoiceRecordingService::class.java).putExtra("recording", draft.id))
        }
        await("Denied service startup must retain a failure") { voice.state.value?.phase != VoicePhase.STARTING }
        assertEquals(VoicePhase.INTERRUPTED, voice.state.value?.phase)
        assertTrue(voice.state.value?.error.orEmpty().contains("permission"))
        Thread.sleep(6500)
        assertNoCapture(voice)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun firstProductionRecordingPromotesAndRetainsActualCapture() = test { voice, manager ->
        instrumentation.runOnMainSync { voice.start(destination) }
        await("Production capture must start; state=${voice.state.value}") { voice.state.value?.phase != VoicePhase.STARTING }
        assertEquals(VoicePhase.RECORDING, voice.state.value?.phase)
        assertTrue(manager.activeNotifications.any { it.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0 })
        val first = voice.state.value!!
        instrumentation.runOnMainSync {
            assertThrows(IllegalStateException::class.java) { voice.start(destination) }
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, VoiceRecordingService::class.java).putExtra("recording", first.id))
        }
        Thread.sleep(1500)
        assertEquals(first.id, voice.state.value?.id)
        assertEquals(VoicePhase.RECORDING, voice.state.value?.phase)
        assertEquals(1, context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.size)
        instrumentation.runOnMainSync { voice.stop() }
        assertEquals(VoicePhase.REVIEW, voice.state.value?.phase)
        assertTrue(voice.drafts.audio(voice.state.value!!).length() > 0)
    }

    @Test fun cancelBeforeServiceDeliveryNeverResurrectsOrTimesOut() = test { voice, manager ->
        instrumentation.runOnMainSync { voice.start(destination); voice.cancel() }
        Thread.sleep(6500) // Includes Android's foreground promotion deadline, not just onStartCommand return.
        assertNull(voice.state.value)
        assertNoCapture(voice)
        assertTrue(manager.activeNotifications.isEmpty())
        instrumentation.runOnMainSync { voice.start(destination) }
        await("A fresh start after cancellation must work") { voice.state.value?.phase != VoicePhase.STARTING }
        assertEquals(VoicePhase.RECORDING, voice.state.value?.phase)
    }

    @Test fun recorderPreparationFailureRetainsDraftAndCleansService() = test { voice, manager ->
        instrumentation.runOnMainSync {
            voice.start(destination)
            voice.drafts.audio(voice.state.value!!).mkdirs() // Actual output-open failure, not an injected callback.
        }
        await("Output failure must settle startup") { voice.state.value?.phase != VoicePhase.STARTING }
        assertEquals(VoicePhase.INTERRUPTED, voice.state.value?.phase)
        Thread.sleep(1000)
        assertNoCapture(voice)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun staleAndStopRequestsDoNotCaptureOrChangeRetainedBytes() = test { voice, manager ->
        lateinit var retained: VoiceDraft
        instrumentation.runOnMainSync {
            retained = voice.drafts.begin(destination).copy(phase = VoicePhase.REVIEW)
            voice.drafts.save(retained)
            voice.drafts.audio(retained).writeText("retained synthetic bytes")
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, VoiceRecordingService::class.java).putExtra("recording", "stale"))
        }
        Thread.sleep(1000)
        assertNoCapture(voice)
        assertEquals(retained, voice.state.value)
        assertEquals("retained synthetic bytes", voice.drafts.audio(retained).readText())
        instrumentation.runOnMainSync {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, VoiceRecordingService::class.java).setAction("studio.otherstuff.ambulando.voice.STOP"))
        }
        Thread.sleep(6500)
        assertNoCapture(voice)
        assertEquals(retained, voice.state.value)
        assertEquals("retained synthetic bytes", voice.drafts.audio(retained).readText())
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun zDisabledChannelDuringDeliverySettlesFailureWithoutDeadlineCrash() = test { voice, manager ->
        manager.createNotificationChannel(NotificationChannel("voice_recording_v1", "Voice recording", NotificationManager.IMPORTANCE_NONE))
        instrumentation.runOnMainSync { voice.start(destination) }
        await("Channel failure must leave a retained actionable draft") { voice.state.value?.phase != VoicePhase.STARTING }
        assertEquals(VoicePhase.INTERRUPTED, voice.state.value?.phase)
        assertTrue(voice.state.value?.error.orEmpty().contains("notifications"))
        Thread.sleep(6500)
        assertNoCapture(voice)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
