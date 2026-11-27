package studio.otherstuff.ambulando.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.File

/** Process owner; the foreground service owns MediaRecorder. UI/navigation never owns capture. */
class VoiceRecorder(private val context: Context, directory: File, private val sample: Boolean) {
    val drafts = VoiceDraftStore(directory)
    val state get() = drafts.state
    val input = kotlinx.coroutines.flow.MutableStateFlow(VoiceInputStatus())
    internal var inputObservers = 0
        private set
    internal fun observeInput(): () -> Unit {
        inputObservers++
        service?.setInputObserved(true)
        var observing = true
        return {
            if (observing) {
                observing = false
                inputObservers--
                if (inputObservers == 0) service?.setInputObserved(false)
            }
        }
    }
    internal var service: VoiceRecordingService? = null
    /** Why the last recording never started. The unusable draft is discarded, so the composer surfaces this instead. */
    val startFailure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun start(destination: VoiceDestination, id: String = java.util.UUID.randomUUID().toString(), headsetOnly: Boolean = false, prompt: String = "", input: VoiceInput? = null) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required. You can enable it in Android settings." }
        // Notifications are not required: a microphone foreground service runs (and appears in Android's
        // Task Manager) even when its notification is hidden, and the composer always offers Stop.
        check(state.value == null) { "Review or cancel the existing recording first." }
        startFailure.value = null
        this.input.value = VoiceInputStatus(requested = input)
        val draft = drafts.begin(destination, id, headsetOnly, prompt)
        try {
            ContextCompat.startForegroundService(context, Intent(context, VoiceRecordingService::class.java).putExtra("sample", sample).putExtra("recording", draft.id))
        } catch (e: Exception) {
            drafts.clear()
            startFailure.value = "Could not start microphone recording. Return to Ambulando and retry."
        }
    }
    fun stop() {
        service?.finish()
        // A queued service start must not resurrect capture after consent/focus was revoked.
        state.value?.takeIf { it.phase == VoicePhase.STARTING }?.let {
            drafts.save(it.copy(phase = VoicePhase.INTERRUPTED, error = "Recording start cancelled before microphone activation."))
        }
    }
    fun cancel() {
        check(state.value?.phase != VoicePhase.SENDING) { "Wait for Send to finish; queued messages can be removed in the conversation." }
        service?.finish()
        drafts.clear()
        input.value = VoiceInputStatus()
    }
    fun fail(message: String) { state.value?.let { drafts.save(it.copy(phase = VoicePhase.REVIEW, error = message)) } }
}
