package studio.otherstuff.ambulando.app.garmin

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.testTag
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.app.AppModel
import studio.otherstuff.ambulando.app.voice.VoiceDestination
import studio.otherstuff.ambulando.core.store.*

@Composable
fun GarminSetup(model: AppModel) {
    val remote = model.garmin
    val binding = remote.binding
    val context = androidx.compose.ui.platform.LocalContext.current
    val draft by model.voice.state.collectAsState()
    val busy = draft?.phase in setOf(studio.otherstuff.ambulando.app.voice.VoicePhase.STARTING, studio.otherstuff.ambulando.app.voice.VoicePhase.RECORDING, studio.otherstuff.ambulando.app.voice.VoicePhase.SENDING) || remote.sessions.any { it.status in setOf("waiting", "recording", "sending") }
    var open by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val workspaces = model.entries.filter { !it.workspace.accessRemoved && it.connection.towerNpub != null && it.connection.signerNpub == model.identityState?.npub }
    fun workspaceLabel(runtime: studio.otherstuff.ambulando.app.WorkspaceEntry): String = runtime.workspace.label +
        if (workspaces.count { it.workspace.label == runtime.workspace.label } > 1) " · ${runtime.connection.origin}" else ""
    var selectedBoundary by remember { mutableStateOf<String?>(null) }
    val owner = model.runtimeMap[selectedBoundary]
    val channels by remember(owner) { if (owner != null) owner.store.liveQuery({ it.table == StoreTable.CHANNELS }) { owner.store.reader.channels(owner.workspace.id) } else kotlinx.coroutines.flow.flowOf(emptyList<ChannelRecord>()) }.collectAsState(emptyList())
    val labels by remember(owner) { if (owner != null) owner.store.liveQuery({ it.workspaceId == owner.workspace.id || it.table == StoreTable.PROFILES }) { studio.otherstuff.ambulando.core.workspaces.NavSnapshot.load(owner.store, owner.connection, owner.workspace).labels } else kotlinx.coroutines.flow.flowOf(emptyMap<String, String>()) }.collectAsState(emptyMap())
    var selectedChannel by remember { mutableStateOf<String?>(null) }
    var selectedDevice by remember { mutableStateOf<Long?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        error = if (result.values.all { it }) null else "Enable denied recording permissions in Android settings."
    }
    Column {
        Text(binding?.destination?.label ?: "No watch destination configured", Modifier.testTag("watch.saved"))
        binding?.let { Text(remote.devices.firstOrNull { device -> device.deviceIdentifier == it.device }?.friendlyName ?: "Saved watch") }
        Text(remote.connection, Modifier.testTag("watch.status"))
        Text(remote.replyStatus)
        TextButton(enabled = !busy, modifier = Modifier.testTag("watch.setup"), onClick = {
            selectedBoundary = workspaces.firstOrNull { binding?.destination?.matches(it.connection, it.workspace) == true }?.boundary?.id
            selectedChannel = binding?.destination?.channelId
            selectedDevice = binding?.device
            error = null; open = true; remote.connect()
        }) { Text(if (binding == null) "Set up watch recorder" else "Change destination or watch") }
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val granted = required.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        TextButton(enabled = draft == null, onClick = { permissions.launch(required.toTypedArray()) }, modifier = Modifier.testTag("watch.permissions")) { Text(if (granted) "Recording permissions granted" else "Grant recording permissions") }
        if (binding != null) {
            Text(if (remote.canOpenRecordingScreen()) "Saved watch screen access is allowed." else "Start recordings on this phone. Optional automatic opening is unavailable without an existing Android association.")
            Text("Open Ambulando after Start on the watch, then open the recorder and tap Start headset recording. No device scan or extra association is required.")
            remote.sessions.lastOrNull()?.let { session ->
                Text("Last watch request: ${session.status}. ${session.detail}")
                if (session.status == "waiting" && draft == null) TextButton(onClick = {
                    context.startActivity(android.content.Intent(context, WatchRecordingActivity::class.java).putExtra("session", session.session))
                }) { Text("Open pending recording on phone") }
                if (session.status in setOf("error", "cancelled", "sent")) Text("On watch, check Status, then choose New for a new recording request.")
                TextButton(onClick = { remote.resendStatus() }) { Text("Send latest status to watch") }
            }
            TextButton(onClick = { remote.connect() }, modifier = Modifier.testTag("watch.reconnect")) { Text("Reconnect watch") }
            TextButton(enabled = !busy, modifier = Modifier.testTag("watch.disable"), onClick = { try { remote.disable(); error = null } catch (e: Exception) { error = e.message } }) { Text("Disable watch recorder") }
        }
        if (busy) Text("Finish recording or sending before changing settings.")
        error?.let { Text(it) }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Watch recorder") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            WatchChoice("Account / workspace", workspaces.firstOrNull { it.id == selectedBoundary }?.let { workspaceLabel(it) }, "watch.workspaces", workspaces.map { it.boundary.id to workspaceLabel(it) }, "watch.workspace") { id -> selectedBoundary = id; selectedChannel = null; workspaces.firstOrNull { it.id == id }?.let { if (!it.enabled) model.setWorkspaceEnabled(it, true) } }
            WatchChoice("Destination", channels.firstOrNull { it.id == selectedChannel }?.let { labels[it.id] ?: it.name }, "watch.channels", channels.filter { watchDestinationAvailable(it) }.map { it.id to (labels[it.id] ?: it.name) }, "watch.channel") { selectedChannel = it }
            WatchChoice("Paired watch", remote.devices.firstOrNull { it.deviceIdentifier == selectedDevice }?.friendlyName, "watch.devices", remote.devices.map { it.deviceIdentifier.toString() to "${it.friendlyName} · ${it.status}" }, "watch.device") { selectedDevice = it.toLong() }
            Text("An offline paired watch can be saved. Recording waits for its connection. Finish sends to this destination. Bluetooth headset required.")
            Text(remote.connection)
            error?.let { Text(it) }
        }
    }, confirmButton = { TextButton(enabled = !busy && owner != null && selectedChannel != null && selectedDevice != null, modifier = Modifier.testTag("watch.save"), onClick = {
        try {
            val runtime = requireNotNull(workspaces.firstOrNull { it.boundary.id == selectedBoundary }?.let { model.runtimeMap[it.boundary.id] }) { "Selected workspace is no longer available. Choose an unlocked, trusted workspace." }
            val (channel, device) = validateWatchSelection(runtime.workspace.id, channels, selectedChannel, remote.devices, selectedDevice)
            remote.bind(device, VoiceDestination.capture(runtime.connection, runtime.workspace, ChatTarget(runtime.connection.id, runtime.workspace.id, channel.id), "${runtime.workspace.label} / ${labels[channel.id] ?: channel.name}"))
            error = null; open = false
        } catch (e: Exception) { error = e.message }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
}

internal fun validateWatchSelection(workspace: String, channels: List<ChannelRecord>, channelId: String?, devices: List<com.garmin.android.connectiq.IQDevice>, deviceId: Long?): Pair<ChannelRecord, com.garmin.android.connectiq.IQDevice> {
    val channel = requireNotNull(channels.firstOrNull { it.workspaceId == workspace && it.id == channelId && watchDestinationAvailable(it) }) { "Selected destination is no longer writable or available. Choose another destination." }
    val device = requireNotNull(devices.firstOrNull { it.deviceIdentifier == deviceId }) { "Selected watch is no longer listed by Garmin Connect. Reconnect, then choose a paired watch." }
    return channel to device
}

internal fun watchDestinationAvailable(channel: ChannelRecord): Boolean = !channel.accessRemoved && channel.kind != "system" &&
    (channel.raw["can_write"] as? JsonPrimitive)?.booleanOrNull != false && (channel.raw["read_only"] as? JsonPrimitive)?.booleanOrNull != true

@Composable private fun WatchChoice(label: String, value: String?, tag: String, choices: List<Pair<String, String>>, itemTag: String, selected: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    TextButton(onClick = { search = ""; choosing = true }, modifier = Modifier.testTag(tag)) { Text("$label: ${value ?: "Choose…"}") }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(label) }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true)
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                choices.filter { it.second.contains(search, ignoreCase = true) }.forEach { (id, name) ->
                    TextButton(onClick = { selected(id); choosing = false }, modifier = Modifier.testTag("$itemTag.$id")) { Text(name) }
                }
                if (choices.isEmpty()) Text("No available choices. Reconnect and try again.")
            }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } })
}

@Composable
fun GarminConfirmation(model: AppModel) {
    if (model.environment.isSample) return
    val remote = model.garmin
    val pending = remote.sessions.firstOrNull { it.status == "waiting" && model.voice.state.value == null } ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = { remote.dismiss(pending.session) }, title = { Text("Record from watch?") },
        text = { Text("The watch is waiting for Start on this phone. Optional watch screen access is not required. Use the Bluetooth headset microphone to record for ${pending.destination.label}. Phone music will pause; Finish on watch sends the recording.") },
        confirmButton = { TextButton(onClick = {
            context.startActivity(android.content.Intent(context, WatchRecordingActivity::class.java).putExtra("session", pending.session))
        }) { Text("Open recorder") } },
        dismissButton = { TextButton(onClick = { remote.dismiss(pending.session) }) { Text("Cancel request") } })
}
