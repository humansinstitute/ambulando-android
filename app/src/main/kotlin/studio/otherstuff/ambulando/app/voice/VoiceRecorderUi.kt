package studio.otherstuff.ambulando.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

@Composable
internal fun VoiceRecorderUi(model: AppModel, c: ConnectionRecord, w: WorkspaceRecord, target: ChatTarget, enabled: Boolean, enqueued: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val voice = model.voice
    val draft by voice.state.collectAsState()
    var permissionTarget by remember { mutableStateOf<VoiceDestination?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun start(destination: VoiceDestination) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { error = "Return to Ambulando and tap Record again."; return }
        if (!model.foreground || model.identityState !is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked || model.identityState?.npub != destination.signerNpub ||
            model.runtimeMap.values.none { destination.matches(it.connection, it.workspace) }) { error = "Return to the original unlocked account and workspace, then tap Record again."; return }
        try { voice.start(destination); error = null } catch (e: Exception) { error = e.message }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val destination = permissionTarget
        permissionTarget = null
        if (result.values.all { it } && destination != null) start(destination)
        else error = "Microphone and notification permission are required to record. Enable them in Android settings, then tap Record again."
    }
    // Only this identity may inspect a retained recording; switching account cannot expose or redirect it.
    val visible = draft?.takeIf { it.destination.signerNpub == c.signerNpub }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("voice.panel")) {
        if (draft == null) {
            TextButton(enabled = enabled, onClick = {
                val destination = VoiceDestination.capture(c, w, target,
                    (model.channels.firstOrNull { it.id == target.channelId }?.name ?: "Original channel") + if (target.threadId != null) " · reply" else " · new conversation")
                val required = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (required.isEmpty()) start(destination)
                else { permissionTarget = destination; permission.launch(required.toTypedArray()) }
            }, modifier = Modifier.testTag("voice.record")) { Text("Record voice") }
        } else if (visible != null) {
            val recording = visible.phase == VoicePhase.RECORDING || visible.phase == VoicePhase.STARTING
            var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
            LaunchedEffect(visible.id, recording) { while (recording) { now = android.os.SystemClock.elapsedRealtime(); delay(500) } }
            val seconds = (if (visible.phase == VoicePhase.STARTING) 0 else if (recording) (now - visible.startedElapsed).coerceAtLeast(0) else visible.elapsedMillis) / 1000
            Text((if (recording) "Recording " else "Voice message ") + "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60), modifier = Modifier.testTag("voice.elapsed"))
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
        } else Text("A recording is kept for another account. Return to that account to review it.")
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
