package studio.otherstuff.ambulando.app.voice

import android.content.Context
import android.media.*
import android.os.Build
import kotlinx.coroutines.delay
import android.os.SystemClock

/** Owns only the route/focus acquired for this capture. No PLAY or transient-focus resume. */
internal class HeadsetRoute(context: Context, private val interrupted: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val previousMode = audio.mode
    private var selected = false
    private var focused = false
    private var changedMode = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) interrupted() }.build()
    suspend fun acquire(capture: MediaRecorder) {
        if (Build.VERSION.SDK_INT < 31) throw IllegalStateException("Watch recording requires Android 12 or later for verified headset routing.")
        val device = audio.availableCommunicationDevices.firstOrNull { bluetooth(it.type) }
            ?: error("Connect a Bluetooth headset with a microphone, then retry. Phone microphone is disabled for watch recordings.")
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Could not pause phone audio. Retry with Ambulando open." }
        focused = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        changedMode = true
        check(audio.setCommunicationDevice(device)) { "Bluetooth communication route was refused." }
        selected = true
        val deadline = SystemClock.elapsedRealtime() + 5000
        var input: AudioDeviceInfo? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { bluetooth(it.type) }
            if (audio.communicationDevice?.id == device.id && input != null) break
            delay(50)
        }
        check(audio.communicationDevice?.id == device.id && input != null) { "Bluetooth microphone did not become ready. Reconnect your headset." }
        check(capture.setPreferredDevice(input)) { "Bluetooth microphone selection failed." }
    }
    fun verified(capture: MediaRecorder): Boolean = capture.routedDevice?.let { it.isSource && bluetooth(it.type) } == true
    fun release() {
        // A platform cleanup failure must not prevent the other resources being released.
        if (Build.VERSION.SDK_INT >= 31 && selected) runCatching { audio.clearCommunicationDevice() }
        selected = false
        if (changedMode) runCatching { if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = previousMode }
        changedMode = false
        if (focused) runCatching { audio.abandonAudioFocusRequest(focus) }
        focused = false
    }
    companion object {
        fun bluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}
