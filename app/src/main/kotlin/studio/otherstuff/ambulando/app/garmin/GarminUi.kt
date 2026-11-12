package studio.otherstuff.ambulando.app.garmin

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import studio.otherstuff.ambulando.app.AppModel
import studio.otherstuff.ambulando.app.voice.VoiceDestination
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

@Composable
fun GarminSetup(model: AppModel, c: ConnectionRecord, w: WorkspaceRecord, target: ChatTarget, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (model.environment.isSample) return
    val remote = model.garmin
    val context = androidx.compose.ui.platform.LocalContext.current
    val recordingPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        error = if (result.values.all { it }) null else "Microphone, Bluetooth and notifications are required. Enable denied permissions in Android settings."
    }
    var associatingDevice by remember { mutableStateOf<Long?>(null) }
    val associationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (Build.VERSION.SDK_INT >= 33 && result.resultCode == android.app.Activity.RESULT_OK) {
            val info = result.data?.getParcelableExtra(android.companion.CompanionDeviceManager.EXTRA_ASSOCIATION, android.companion.AssociationInfo::class.java)
            try { if (info != null && associatingDevice != null) remote.associate(associatingDevice!!, info.id) }
            catch (e: Exception) { error = e.message }
        }
    }
    TextButton(onClick = { open = true; remote.connect() }, enabled = enabled) { Text("Watch recorder") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Watch recorder destination") }, text = {
        androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text("Choose your watch to bind this signed-in account and conversation. Select the intended direct message before binding. Finish on watch sends here automatically; navigation will not change the destination.")
            Text("Start asks for phone confirmation. Keep Ambulando open or in memory; if Android closes it, reopen it. Bluetooth microphone required.")
            TextButton(onClick = {
                recordingPermissions.launch(buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray())
            }) { Text("Grant recording permissions") }
            Text(remote.connection)
            remote.devices.forEach { device -> TextButton(onClick = {
                try {
                    remote.bind(device, VoiceDestination.capture(c, w, target, model.channels.firstOrNull { it.id == target.channelId }?.name ?: "Selected conversation"))
                    error = null; open = false
                } catch (e: Exception) { error = e.message }
            }) { Text("Bind ${device.friendlyName} to this conversation") } }
            Text(remote.journal.data.binding?.destination?.label?.let { "Bound to: $it" } ?: "No destination bound")
            if (Build.VERSION.SDK_INT >= 33 && remote.journal.data.binding != null) {
                Text("Optional: let a watch tap open a visible recording screen, including on the lock screen. Grant microphone, Bluetooth and notifications in Android settings first. Device verification still required.")
                TextButton(onClick = {
                    val bound = remote.journal.data.binding ?: return@TextButton
                    val name = remote.devices.firstOrNull { it.deviceIdentifier == bound.device }?.friendlyName ?: return@TextButton
                    associatingDevice = bound.device
                    try {
                        val request = android.companion.AssociationRequest.Builder()
                            .addDeviceFilter(android.companion.BluetoothLeDeviceFilter.Builder().setNamePattern(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name), java.util.regex.Pattern.CASE_INSENSITIVE)).build()).build()
                        context.getSystemService(android.companion.CompanionDeviceManager::class.java).associate(request, context.mainExecutor,
                            object : android.companion.CompanionDeviceManager.Callback() {
                                override fun onAssociationPending(sender: android.content.IntentSender) { associationLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
                                override fun onFailure(message: CharSequence?) { error = message?.toString() ?: "Association failed" }
                            })
                    } catch (e: Exception) { error = e.message }
                }) { Text(if (remote.canOpenRecordingScreen()) "Reconfigure watch screen access" else "Allow watch to open recording screen") }
            }
            error?.let { Text(it) }
        }
    }, confirmButton = { TextButton(onClick = { remote.connect() }) { Text("Retry connection") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

@Composable
fun GarminConfirmation(model: AppModel) {
    if (model.environment.isSample) return
    val remote = model.garmin
    val pending = remote.sessions.firstOrNull { it.status == "waiting" && model.voice.state.value == null } ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var grantingSession by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val requested = grantingSession
        grantingSession = null
        if (requested != null && result.values.all { it } && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) remote.confirm(requested)
    }
    AlertDialog(onDismissRequest = { remote.dismiss(pending.session) }, title = { Text("Record from watch?") },
        text = { Text("Use Bluetooth headset microphone. Phone music will pause. Finish on watch automatically sends to ${pending.destination.label}. Audio will not resume automatically.") },
        confirmButton = { TextButton(onClick = {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                grantingSession = pending.session
                permissions.launch(buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
            }
        }) { Text("Start headset recording") } },
        dismissButton = { TextButton(onClick = { remote.dismiss(pending.session) }) { Text("Cancel") } })
}
