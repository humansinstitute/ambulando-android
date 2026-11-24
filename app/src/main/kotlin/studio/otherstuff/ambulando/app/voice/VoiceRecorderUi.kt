package studio.otherstuff.ambulando.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.Role
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import studio.otherstuff.ambulando.app.AppModel
import studio.otherstuff.ambulando.core.store.ChatTarget
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

internal class VoiceRecorderAction(val record: () -> Unit, val available: Boolean, val error: String?)

/** Capture the destination synchronously on selection, before any permission dialog. */
@Composable
internal fun rememberVoiceRecorderAction(model: AppModel, c: ConnectionRecord, w: WorkspaceRecord, target: ChatTarget, enabled: Boolean): VoiceRecorderAction {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val voice = model.voice
    val draft by voice.state.collectAsState()
    var setupTarget by remember { mutableStateOf<VoiceDestination?>(null) }
    var selectedInput by remember { mutableStateOf<VoiceInput?>(null) }
    var permissionTarget by remember { mutableStateOf<VoiceDestination?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun start(destination: VoiceDestination) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { error = "Return to Ambulando and tap Record again."; return }
        if (!model.foreground || model.identityState !is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked || model.identityState?.npub != destination.signerNpub ||
            model.runtimeMap.values.none { destination.matches(it.connection, it.workspace) }) { error = "Return to the original unlocked account and workspace, then tap Record again."; return }
        try { voice.start(destination, input = selectedInput); error = null } catch (e: Exception) { error = e.message }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val destination = permissionTarget
        permissionTarget = null
        if (result.values.all { it } && destination != null) start(destination)
        else error = "Microphone and notification permission are required to record. Enable them in Android settings, then tap Record again."
    }
    fun requestRecording(destination: VoiceDestination) {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (required.isEmpty()) start(destination)
        else { permissionTarget = destination; permission.launch(required.toTypedArray()) }
    }
    val record: () -> Unit = {
        selectedInput = null
        setupTarget = VoiceDestination.capture(c, w, target,
            (model.channels.firstOrNull { it.id == target.channelId }?.name ?: "Original channel") + if (target.threadId != null) " · reply" else " · new conversation")
    }
    setupTarget?.let { destination ->
        VoiceInputDialog(selectedInput, { selectedInput = it }, { setupTarget = null }) {
            setupTarget = null
            requestRecording(destination)
        }
    }
    return VoiceRecorderAction(record, enabled && draft == null, error)
}

@Composable
internal fun VoiceRecorderUi(model: AppModel, c: ConnectionRecord, target: ChatTarget, enabled: Boolean, error: String?, enqueued: (String) -> Unit) {
    val voice = model.voice
    val draft by voice.state.collectAsState()
    // Only this identity may inspect a retained recording; switching account cannot expose or redirect it.
    val visible = draft?.takeIf { it.destination.signerNpub == c.signerNpub }
    if (draft == null && error == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("voice.panel")) {
        if (visible != null) {
            val recording = visible.phase == VoicePhase.RECORDING || visible.phase == VoicePhase.STARTING
            var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
            LaunchedEffect(visible.id, recording) { while (recording) { now = android.os.SystemClock.elapsedRealtime(); delay(500) } }
            val seconds = (if (visible.phase == VoicePhase.STARTING) 0 else if (recording) (now - visible.startedElapsed).coerceAtLeast(0) else visible.elapsedMillis) / 1000
            Text((if (recording) "Recording " else "Voice message ") + "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60), modifier = Modifier.testTag("voice.elapsed"))
            if (recording) VoiceInputMeter(voice, visible.phase == VoicePhase.RECORDING)
            Text("To ${visible.destination.label}", style = MaterialTheme.typography.labelSmall)
            if (recording) TextButton(onClick = voice::stop, enabled = visible.phase == VoicePhase.RECORDING, modifier = Modifier.testTag("voice.stop")) { Text("Stop recording") }
            else if (visible.phase == VoicePhase.SENDING) Text("Sending to original channel…", modifier = Modifier.testTag("voice.sending"))
            else {
                VoicePlayback(voice.drafts.audio(visible).absolutePath)
                Row {
                    TextButton(onClick = { model.sendVoice { id -> if (visible.destination.target == target) enqueued(id) } }, enabled = enabled, modifier = Modifier.testTag("voice.send")) { Text(if (visible.error != null) "Retry send" else "Send voice") }
                    TextButton(onClick = { voice.cancel() }, modifier = Modifier.testTag("voice.cancel")) { Text("Cancel recording") }
                }
            }
            if (recording) TextButton(onClick = { voice.cancel() }, modifier = Modifier.testTag("voice.cancel")) { Text("Cancel recording") }
            visible.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("voice.error")) }
        } else if (draft != null) Text("A recording is kept for another account. Return to that account to review it.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("voice.permission.error")) }
    }
}

@Composable
private fun VoicePlayback(path: String) {
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    DisposableEffect(path) { onDispose { player?.release(); player = null } }
    TextButton(onClick = {
        if (playing) { player?.pause(); playing = false }
        else try {
            val p = player ?: MediaPlayer().also { p ->
                player = p
                p.setDataSource(path)
                p.setOnCompletionListener { playing = false }
                p.setOnErrorListener { _, _, _ -> playing = false; error = "Audio could not be played. Cancel and record again."; true }
                p.prepare()
            }
            p.start(); playing = true; error = null
        } catch (_: Exception) { player?.release(); player = null; error = "Audio could not be recovered. Cancel and record again." }
    }, modifier = Modifier.testTag("voice.play")) { Text(if (playing) "Pause preview" else "Play recording") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** Enumeration exists only while the chooser is visible; it never opens a microphone. */
@Composable
private fun VoiceInputDialog(selected: VoiceInput?, select: (VoiceInput?) -> Unit, dismiss: () -> Unit, start: () -> Unit) {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    var inputs by remember { mutableStateOf<List<VoiceInput>>(emptyList()) }
    var unavailable by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(audio, lifecycle) {
        fun refresh() {
            runCatching { audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isRecordingMicrophone() }.map { it.voiceInput() } }
                .onSuccess { inputs = it; unavailable = false }.onFailure { unavailable = true; inputs = emptyList() }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = refresh()
        }
        var registered = false
        fun updateObservation() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                refresh()
                if (!registered) registered = runCatching { audio.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper())) }.isSuccess
            } else if (registered) { audio.unregisterAudioDeviceCallback(callback); registered = false }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateObservation() }
        lifecycle.addObserver(observer)
        updateObservation()
        onDispose {
            lifecycle.removeObserver(observer)
            if (registered) audio.unregisterAudioDeviceCallback(callback)
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Recording microphone") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Choose before recording. Android decides the actual route; it will be shown once capture starts.")
            (listOf<VoiceInput?>(null) + inputs).forEach { input ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected?.id == input?.id, role = Role.RadioButton, onClick = { select(input) }).padding(vertical = 4.dp)) {
                    RadioButton(selected = selected?.id == input?.id, onClick = null)
                    val label = input?.let { choice ->
                        if (inputs.count { it.label == choice.label } > 1) "${choice.label} · Input ${inputs.filter { it.label == choice.label }.indexOf(choice) + 1}" else choice.label
                    } ?: "System default"
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
            if (selected != null && inputs.none { it.id == selected.id }) Text("Selected microphone is unavailable. Android may fall back to another input.")
            if (unavailable) Text("Android could not list microphones. System default is available; check device permissions in Android settings.")
            Text("Stop and review this clip before choosing a different microphone. No microphone opens until you start.")
        }
    }, confirmButton = { TextButton(onClick = start, modifier = Modifier.testTag("voice.start")) { Text("Start recording") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun VoiceInputMeter(voice: VoiceRecorder, recording: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = studio.otherstuff.ambulando.app.ui.shell.LocalEditorActive.current
    DisposableEffect(voice, lifecycle, active) {
        var release: (() -> Unit)? = null
        fun refresh() {
            if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (release == null) release = voice.observeInput()
            } else { release?.invoke(); release = null }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer); release?.invoke() }
    }
    val input by voice.input.collectAsState()
    Text("Requested: ${input.requested?.label ?: "System default"}", style = MaterialTheme.typography.labelSmall)
    Text(input.routeText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("voice.input.route"))
    input.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (input.silenced) Text("Android has silenced this microphone. Check microphone privacy settings or other recording apps.", color = MaterialTheme.colorScheme.error)
    // A direct, non-animated level display also respects reduced motion. No pulsing silence state.
    val level = if (recording) input.level else 0f
    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(8.dp)
        .testTag("voice.input.level").semantics { contentDescription = "Microphone input level" })
    Text(if (!recording) "Preparing microphone…" else if (input.silenced) "Microphone silenced" else if (level == 0f) "No input detected" else "Receiving microphone input",
        style = MaterialTheme.typography.labelSmall)
    Text("Choose a different microphone for your next recording.", style = MaterialTheme.typography.labelSmall)
}
