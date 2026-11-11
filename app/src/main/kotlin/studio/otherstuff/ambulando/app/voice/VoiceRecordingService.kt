package studio.otherstuff.ambulando.app.voice

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import studio.otherstuff.ambulando.app.*

/** Only started by a visible UI after permission grant. Never restarts the microphone from the background. */
class VoiceRecordingService : Service() {
    private var owner: VoiceRecorder? = null
    private var recorder: MediaRecorder? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var startElapsed = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish(); return START_NOT_STICKY }
        if (recorder != null) return START_NOT_STICKY
        val app = application as AmbulandoApplication
        val environment = if (intent?.getBooleanExtra("sample", false) == true) AppEnvironment.Sample(this) else AppEnvironment.Production(this)
        val voice = app.model(environment).voice
        val draft = voice.state.value
        if (draft == null || draft.id != intent?.getStringExtra("recording") || draft.phase != VoicePhase.STARTING) { stopSelf(); return START_NOT_STICKY }
        owner = voice
        voice.service = this
        try {
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW))
            check(manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) { "Enable voice recording notifications in Android settings." }
            val stop = PendingIntent.getService(this, 0, Intent(this, VoiceRecordingService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(studio.otherstuff.ambulando.app.R.drawable.ic_notification)
                .setContentTitle("Recording voice message")
                .setContentText("Return to Ambulando to review and send")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setUsesChronometer(true).setWhen(System.currentTimeMillis())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(0, "Stop", stop).build()
            if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
            @Suppress("DEPRECATION")
            val capture = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = capture
            capture.setAudioSource(MediaRecorder.AudioSource.MIC)
            capture.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            capture.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            capture.setAudioEncodingBitRate(64000)
            capture.setAudioSamplingRate(44100)
            capture.setOutputFile(voice.drafts.audio(draft).absolutePath)
            // Bound memory/storage: at 64 kbit/s this allows roughly an hour. No unbounded read at upload.
            capture.setMaxFileSize(32L * 1024 * 1024)
            capture.setMaxDuration(60 * 60 * 1000)
            capture.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish() }
            capture.setOnErrorListener { _, _, _ -> finish("Microphone recording was interrupted. Check the audio before sending.") }
            capture.prepare()
            wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Ambulando:VoiceRecording").apply { acquire(65L * 60 * 1000) }
            capture.start()
            startElapsed = SystemClock.elapsedRealtime()
            voice.drafts.save(draft.copy(phase = VoicePhase.RECORDING, startedAt = System.currentTimeMillis(), startedElapsed = startElapsed))
        } catch (e: Exception) { finish(e.message ?: "Microphone unavailable. Check permission and try again.") }
        return START_NOT_STICKY
    }
    fun finish(error: String? = null) {
        val capture = recorder
        recorder = null
        var failure = error
        if (capture != null) {
            try { capture.stop() } catch (_: RuntimeException) { failure = "Recording was too short or interrupted. Cancel and record again if playback fails." }
            finally { capture.release() }
        }
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        owner?.let { voice ->
            voice.state.value?.let { draft ->
                if (draft.phase == VoicePhase.STARTING || draft.phase == VoicePhase.RECORDING) voice.drafts.save(draft.copy(
                    phase = if (failure == null && capture != null) VoicePhase.REVIEW else VoicePhase.INTERRUPTED,
                    elapsedMillis = if (startElapsed > 0) SystemClock.elapsedRealtime() - startElapsed else 0, error = failure,
                ))
            }
            voice.service = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { finish(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "voice_recording_v1"
        private const val NOTIFICATION = 3402
        private const val STOP = "studio.otherstuff.ambulando.voice.STOP"
    }
}
