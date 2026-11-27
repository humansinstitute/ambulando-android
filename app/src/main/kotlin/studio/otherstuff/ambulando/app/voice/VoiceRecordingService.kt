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
    private var inputSample: Runnable? = null
    private var inputMonitoring = false
    private var inputRoutingListener: android.media.AudioRouting.OnRoutingChangedListener? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var startElapsed = 0L
    private var route: HeadsetRoute? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var closing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        // startForegroundService is a promise even if a queued start was cancelled, or setup fails.
        // Settle it before reading the draft/model or entering a coroutine. This opens no microphone.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW))
        val notification = notification(null).build()
        when {
            android.os.Build.VERSION.SDK_INT >= 34 -> startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            android.os.Build.VERSION.SDK_INT >= 30 -> startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else -> startForeground(NOTIFICATION, notification)
        }
    }
    private fun notification(draft: VoiceDraft?): NotificationCompat.Builder {
        val stop = PendingIntent.getService(this, 0, Intent(this, VoiceRecordingService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(studio.otherstuff.ambulando.app.R.drawable.ic_notification)
            .setContentTitle(when { draft == null -> "Preparing voice recording"; draft.headsetOnly -> "Preparing headset recording"; else -> "Recording voice message" })
            .setContentText(if (draft?.headsetOnly == true) "Waiting for verified Bluetooth microphone" else "Return to Ambulando to review and send")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setUsesChronometer(draft != null && !draft.headsetOnly).setWhen(System.currentTimeMillis())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop", stop)
    }
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
                check(ContextCompat.checkSelfPermission(this@VoiceRecordingService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required. Enable it in Android settings and try again." }
                val manager = getSystemService(NotificationManager::class.java)
                val notificationBuilder = notification(draft)
                val notification = notificationBuilder.build()
                if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(NOTIFICATION, notification)
                if (draft.headsetOnly) android.util.Log.i("WatchRecorder", "microphone FGS promoted")
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
                if (!draft.headsetOnly) {
                    val requested = voice.input.value.requested
                    val device = getSystemService(android.media.AudioManager::class.java)
                        .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == requested?.id }
                    val accepted = capture.setPreferredDevice(device)
                    val warning = when {
                        requested != null && device == null -> "Requested microphone disconnected. Android will choose an input."
                        !accepted -> "Android refused the microphone preference. Using system routing."
                        else -> null
                    }
                    voice.input.value = voice.input.value.copy(warning = warning)
                }
                capture.prepare()
                wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Ambulando:VoiceRecording").apply { acquire(65L * 60 * 1000) }
                capture.start()
                startElapsed = SystemClock.elapsedRealtime()
                fun updateInput(sample: Boolean) {
                    if (recorder !== capture || closing) return
                    val previous = voice.input.value
                    val actual = runCatching { capture.routedDevice?.takeIf { it.isSource }?.voiceInput() }.getOrNull()
                    val silenced = runCatching { capture.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
                    val amplitude = if (sample) runCatching { capture.maxAmplitude }.getOrNull() else null
                    voice.input.value = previous.copy(actual = actual, silenced = silenced,
                        level = if (silenced) 0f else if (sample) amplitude?.let(::microphoneLevel) ?: 0f else previous.level,
                        samples = previous.samples + if (amplitude != null) 1 else 0)
                }
                inputRoutingListener = android.media.AudioRouting.OnRoutingChangedListener { updateInput(false) }
                fun meter() {
                    if (recorder !== capture || closing || !inputMonitoring) return
                    updateInput(true)
                    inputSample?.let { handler.postDelayed(it, 150) }
                }
                inputSample = Runnable { meter() }
                setInputObserved(voice.inputObservers > 0)
                if (draft.headsetOnly) {
                    // Selection alone is not proof of the active input. Never acknowledge recording until routed.
                    val deadline = SystemClock.elapsedRealtime() + 3000
                    fun verify() {
                        if (recorder !== capture) return
                        if (route?.verified(capture) == true) {
                            if (voice.state.value?.phase == VoicePhase.STARTING) {
                                voice.drafts.save(draft.copy(phase = VoicePhase.RECORDING, startedAt = System.currentTimeMillis(), startedElapsed = startElapsed))
                                manager.notify(NOTIFICATION, notificationBuilder.setContentTitle("Recording from Bluetooth headset")
                                    .setContentText("Stop on watch to send; Stop here keeps a draft")
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
    /** Display observation never owns capture. Hidden/background panels consume no meter callbacks. */
    internal fun setInputObserved(observed: Boolean) {
        val capture = recorder ?: return
        val sample = inputSample ?: return
        if (closing || inputMonitoring == observed) return
        inputMonitoring = observed
        handler.removeCallbacks(sample)
        if (observed) {
            inputRoutingListener?.let { capture.addOnRoutingChangedListener(it, handler) }
            // Discard the peak accumulated while hidden: the next sample represents visible capture.
            runCatching { capture.maxAmplitude }
            handler.post(sample)
        } else {
            inputRoutingListener?.let { capture.removeOnRoutingChangedListener(it) }
            owner?.let { it.input.value = it.input.value.copy(level = 0f, actual = null) }
        }
    }
    fun finish(error: String? = null) {
        if (closing) return
        closing = true
        inputMonitoring = false
        inputSample = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        val capture = recorder
        recorder = null
        var failure = error
        // Finish may arrive before the queued routing callback. Recheck before allowing automatic send.
        if (failure == null && capture != null && owner?.state?.value?.headsetOnly == true &&
            !runCatching { startElapsed > 0 && route?.verified(capture) == true }.getOrDefault(false)) {
            failure = "Bluetooth microphone was not verified at Finish. Recording kept for phone review."
        }
        if (capture != null) {
            try { if (startElapsed > 0) capture.stop() } catch (_: RuntimeException) { if (failure == null) failure = "Recording was too short or interrupted. Cancel and record again if playback fails." }
            finally {
                inputRoutingListener?.let { listener -> runCatching { capture.removeOnRoutingChangedListener(listener) } }
                inputRoutingListener = null
                runCatching { capture.release() }
            }
        }
        runCatching { route?.release() }; route = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        owner?.let { voice ->
            voice.state.value?.let { draft ->
                if (failure != null && startElapsed == 0L && draft.phase == VoicePhase.STARTING) {
                    // Capture never began: there is no audio to play or send. Discard the empty draft and report why.
                    voice.drafts.clear()
                    voice.startFailure.value = failure
                } else if (draft.phase == VoicePhase.STARTING || draft.phase == VoicePhase.RECORDING) voice.drafts.save(draft.copy(
                    phase = if (failure == null && capture != null) VoicePhase.REVIEW else VoicePhase.INTERRUPTED,
                    elapsedMillis = if (startElapsed > 0) SystemClock.elapsedRealtime() - startElapsed else 0, error = failure,
                ))
            }
            voice.input.value = voice.input.value.copy(level = 0f)
            voice.service = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int) { finish("Recording startup timed out. Cancel this draft and try again.") }
    override fun onDestroy() { finish(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "voice_recording_v1"
        private const val NOTIFICATION = 3402
        private const val STOP = "studio.otherstuff.ambulando.voice.STOP"
    }
}
