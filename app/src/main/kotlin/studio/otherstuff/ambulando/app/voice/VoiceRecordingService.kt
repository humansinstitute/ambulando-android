package studio.otherstuff.ambulando.app.voice

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import kotlinx.coroutines.*
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
    private var route: HeadsetRoute? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var closing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish(); return START_NOT_STICKY }
        if (recorder != null) return START_NOT_STICKY
        val app = application as AmbulandoApplication
        val environment = if (intent?.getBooleanExtra("sample", false) == true) AppEnvironment.Sample(this) else AppEnvironment.Production(this)
        val voice = app.model(environment).voice
        val draft = voice.state.value
        if (draft == null || draft.id != intent?.getStringExtra("recording") || draft.phase != VoicePhase.STARTING) { stopSelf(); return START_NOT_STICKY }
        if (closing) {
            voice.drafts.save(draft.copy(phase = VoicePhase.INTERRUPTED, error = "Recorder is closing. Cancel this draft and retry."))
            stopSelf()
            return START_NOT_STICKY
        }
        owner = voice
        voice.service = this
        scope.launch {
            try {
                check(ContextCompat.checkSelfPermission(this@VoiceRecordingService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW))
                check(manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) { "Enable voice recording notifications in Android settings." }
                val stop = PendingIntent.getService(this@VoiceRecordingService, 0, Intent(this@VoiceRecordingService, VoiceRecordingService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val open = PendingIntent.getActivity(this@VoiceRecordingService, 1, Intent(this@VoiceRecordingService, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notificationBuilder = NotificationCompat.Builder(this@VoiceRecordingService, CHANNEL)
                    .setSmallIcon(studio.otherstuff.ambulando.app.R.drawable.ic_notification)
                    .setContentTitle(if (draft.headsetOnly) "Preparing headset recording" else "Recording voice message")
                    .setContentText(if (draft.headsetOnly) "Waiting for verified Bluetooth microphone" else "Return to Ambulando to review and send")
                    .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                    .setUsesChronometer(!draft.headsetOnly).setWhen(System.currentTimeMillis())
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .addAction(0, "Stop", stop)
                val notification = notificationBuilder.build()
                if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(NOTIFICATION, notification)
                @Suppress("DEPRECATION")
                val capture = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this@VoiceRecordingService) else MediaRecorder()
                recorder = capture
                capture.setAudioSource(if (draft.headsetOnly) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
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
                if (draft.headsetOnly) {
                    route = HeadsetRoute(this@VoiceRecordingService) { finish("Audio focus lost. Recording kept for review.") }
                    route!!.acquire(capture)
                }
                capture.prepare()
                wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Ambulando:VoiceRecording").apply { acquire(65L * 60 * 1000) }
                capture.start()
                startElapsed = SystemClock.elapsedRealtime()
                if (draft.headsetOnly) {
                    // Selection alone is not proof of the active input. Never acknowledge recording until routed.
                    val deadline = SystemClock.elapsedRealtime() + 3000
                    fun verify() {
                        if (recorder !== capture) return
                        if (route?.verified(capture) == true) {
                            if (voice.state.value?.phase == VoicePhase.STARTING) {
                                voice.drafts.save(draft.copy(phase = VoicePhase.RECORDING, startedAt = System.currentTimeMillis(), startedElapsed = startElapsed))
                                manager.notify(NOTIFICATION, notificationBuilder.setContentTitle("Recording from Bluetooth headset")
                                    .setContentText("Finish on watch to send; Stop keeps a draft")
                                    .setUsesChronometer(true).setWhen(System.currentTimeMillis()).build())
                            }
                            handler.postDelayed({
                                if (recorder === capture) {
                                    if (route?.verified(capture) != true) finish("Bluetooth microphone disconnected. Recording stopped; review before sending.")
                                    else verify()
                                }
                            }, 100)
                        } else if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed({ verify() }, 50)
                        else finish("Bluetooth microphone could not be verified. Cancel this draft and reconnect your headset.")
                    }
                    capture.addOnRoutingChangedListener({
                        if (recorder === capture && voice.state.value?.phase == VoicePhase.RECORDING && route?.verified(capture) != true)
                            finish("Bluetooth microphone route changed. Recording interrupted; review before sending.")
                    }, handler)
                    verify()
                } else voice.drafts.save(draft.copy(phase = VoicePhase.RECORDING, startedAt = System.currentTimeMillis(), startedElapsed = startElapsed))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { finish(e.message ?: "Microphone unavailable. Check permission and try again.") }
        }
        return START_NOT_STICKY
    }
    fun finish(error: String? = null) {
        if (closing) return
        closing = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        val capture = recorder
        recorder = null
        var failure = error
        if (capture != null) {
            try { if (startElapsed > 0) capture.stop() } catch (_: RuntimeException) { if (failure == null) failure = "Recording was too short or interrupted. Cancel and record again if playback fails." }
            finally { runCatching { capture.release() } }
        }
        runCatching { route?.release() }; route = null
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
