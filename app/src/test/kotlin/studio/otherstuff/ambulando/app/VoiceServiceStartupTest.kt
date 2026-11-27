package studio.otherstuff.ambulando.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import studio.otherstuff.ambulando.app.voice.*

/** Real production recorder/intent/service. Only account storage/transport use host-compatible test dependencies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class VoiceServiceStartupTest {
    private val destination = VoiceDestination("connection", "https://tower.example", "tower", "signer", "workspace", "app", "channel", null, "Channel")
    private fun test(block: (AmbulandoApplication, VoiceRecorder, Intent, ServiceController<VoiceRecordingService>) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<AmbulandoApplication>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val environment = AppEnvironment.Production(app)
        val identity = studio.otherstuff.ambulando.core.crypto.IdentityService(
            java.io.File(environment.baseDir, "test-vault"),
            object : studio.otherstuff.ambulando.core.crypto.VaultSecretStore {
                override fun read(): ByteArray? = null
                override fun write(data: ByteArray) = Unit
            },
        )
        val tower = studio.otherstuff.ambulando.core.sample.SampleTower(java.io.File(app.cacheDir, "test-tower"))
        val model = AppModel(environment, app, AppSessionDependencies(identity, tower, tower.events), restoreInteractiveIdentity = false)
        @Suppress("UNCHECKED_CAST")
        val models = AmbulandoApplication::class.java.getDeclaredField("models").apply { isAccessible = true }.get(app) as MutableMap<Boolean, AppModel>
        models[false] = model
        val voice = model.voice
        voice.start(destination)
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        val controller = Robolectric.buildService(VoiceRecordingService::class.java, intent)
        try { block(app, voice, intent, controller) }
        finally {
            controller.destroy()
            voice.cancel()
            (AppModel::class.java.getDeclaredField("viewModelScope").apply { isAccessible = true }.get(model) as kotlinx.coroutines.CoroutineScope).cancel()
            kotlinx.coroutines.runBlocking { model.runtimes.close() }; model.sharedEngine.close(); model.catalog.close()
        }
    }
    private fun capture(service: VoiceRecordingService) = VoiceRecordingService::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(service)
    private fun assertPreparing(controller: ServiceController<VoiceRecordingService>) {
        controller.create()
        val notification = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull("Every foreground-service request must promote before draft/model validation or capture", notification)
        assertEquals("Preparing voice recording", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        assertNull("Bootstrap must not open the microphone", capture(controller.get()))
    }
    @Test fun firstProductionRecordingPromotesBeforeCaptureAndDuplicateStartKeepsOneRecorder() = test { _, voice, intent, controller ->
        assertPreparing(controller)
        controller.startCommand(0, 1)
        assertEquals(VoicePhase.RECORDING, voice.state.value?.phase)
        val first = requireNotNull(capture(controller.get()))
        controller.get().onStartCommand(intent, 0, 2)
        assertSame(first, capture(controller.get()))
        assertEquals(VoicePhase.RECORDING, voice.state.value?.phase)
    }
    @Test fun cancelledQueuedStartPromotesButNeverResurrectsCapture() = test { _, voice, _, controller ->
        voice.cancel()
        assertPreparing(controller)
        controller.startCommand(0, 1)
        assertNull(capture(controller.get()))
        assertNull(voice.state.value)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
    @Test fun stoppedQueuedStartKeepsInterruptedDraftWithoutCapture() = test { _, voice, _, controller ->
        val draft = voice.state.value!!
        voice.stop()
        assertPreparing(controller)
        controller.startCommand(0, 1)
        assertNull(capture(controller.get()))
        assertEquals(draft.id, voice.state.value?.id)
        assertEquals(VoicePhase.INTERRUPTED, voice.state.value?.phase)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
    @Test fun staleStartAndStopActionPromoteWithoutChangingRetainedAudio() = test { _, voice, intent, controller ->
        val draft = voice.state.value!!
        voice.drafts.audio(draft).writeText("retained bytes")
        intent.putExtra("recording", "stale")
        assertPreparing(controller)
        controller.startCommand(0, 1)
        assertNull(capture(controller.get()))
        assertEquals(draft, voice.state.value)
        assertEquals("retained bytes", voice.drafts.audio(draft).readText())
        controller.get().onStartCommand(Intent().setAction("studio.otherstuff.ambulando.voice.STOP"), 0, 2)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
    @Test fun disabledNotificationChannelStillRecordsBecauseNotificationsAreNotRequired() = test { app, voice, _, controller ->
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("voice_recording_v1", "Voice recording", NotificationManager.IMPORTANCE_NONE))
        assertPreparing(controller)
        controller.startCommand(0, 1)
        assertNotNull(capture(controller.get()))
        assertEquals(VoicePhase.RECORDING, voice.state.value?.phase)
        assertNull(voice.startFailure.value)
    }
    @Test fun deniedNotificationPermissionStillRecords() = test { app, voice, _, controller ->
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertPreparing(controller)
        controller.startCommand(0, 1)
        assertNotNull(capture(controller.get()))
        assertEquals(VoicePhase.RECORDING, voice.state.value?.phase)
    }
    @Test fun permissionRevokedAfterRequestNeverOpensMicrophoneAndLeavesNoBrokenDraft() = test { app, voice, _, controller ->
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertPreparing(controller)
        controller.startCommand(0, 1)
        assertNull(capture(controller.get()))
        assertNull("A recording that never started must not leave a 0:00 draft with Play/Retry", voice.state.value)
        assertTrue(voice.startFailure.value.orEmpty().contains("Microphone permission"))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
    @Test fun bootstrapTimeoutStopsPromptlyWithoutCapture() = test { _, _, _, controller ->
        assertPreparing(controller)
        controller.get().onTimeout(1)
        assertNull(capture(controller.get()))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
}
