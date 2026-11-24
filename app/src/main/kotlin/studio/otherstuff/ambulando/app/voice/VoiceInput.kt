package studio.otherstuff.ambulando.app.voice

import android.media.AudioDeviceInfo
import kotlin.math.log10

/** Session-only OS device IDs are never persisted or mistaken for route evidence. */
data class VoiceInput(val id: Int, val label: String)
data class VoiceInputStatus(
    val requested: VoiceInput? = null,
    val actual: VoiceInput? = null,
    val level: Float = 0f,
    val warning: String? = null,
    val silenced: Boolean = false,
    val samples: Long = 0,
) {
    val routeText: String get() = when {
        actual == null -> "Active microphone: not yet reported by Android"
        requested != null && requested.id != actual.id -> "Active microphone: ${actual.label} · Android is using a different input"
        else -> "Active microphone: ${actual.label}"
    }
}
internal fun AudioDeviceInfo.voiceInput(): VoiceInput {
    val kind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth microphone"
        else -> "Microphone"
    }
    return VoiceInput(id, "$kind · $productName")
}
/** 60 dB display range, with a true zero for silence. No animation or synthetic history. */
internal fun microphoneLevel(amplitude: Int): Float = if (amplitude <= 0) 0f else
    ((20 * log10(amplitude.coerceAtMost(32767).toDouble() / 32767) + 60) / 60).toFloat().coerceIn(0f, 1f)

/** Android also reports telephony, FM and remote-submix sources; these are not recording microphones. */
internal fun AudioDeviceInfo.isRecordingMicrophone(): Boolean = isSource && type in setOf(
    AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
)
