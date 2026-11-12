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
    internal var service: VoiceRecordingService? = null
    fun start(destination: VoiceDestination, id: String = java.util.UUID.randomUUID().toString(), headsetOnly: Boolean = false) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required. You can enable it in Android settings." }
        // Require notifications for a reliably visible Stop action, including on Android 13+.
        check(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) { "Enable Ambulando notifications in Android settings to record with a visible Stop action." }
        val draft = drafts.begin(destination, id, headsetOnly)
        try {
            ContextCompat.startForegroundService(context, Intent(context, VoiceRecordingService::class.java).putExtra("sample", sample).putExtra("recording", draft.id))
        } catch (e: Exception) {
            drafts.save(draft.copy(phase = VoicePhase.INTERRUPTED, error = "Could not start microphone recording. Return to Ambulando and retry."))
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
    }
    fun fail(message: String) { state.value?.let { drafts.save(it.copy(phase = VoicePhase.REVIEW, error = message)) } }
}
