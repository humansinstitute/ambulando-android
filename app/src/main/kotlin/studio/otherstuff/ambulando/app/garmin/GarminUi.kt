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
    val captureBusy = draft?.phase in setOf(studio.otherstuff.ambulando.app.voice.VoicePhase.STARTING, studio.otherstuff.ambulando.app.voice.VoicePhase.RECORDING, studio.otherstuff.ambulando.app.voice.VoicePhase.SENDING) || remote.sessions.any { it.status in setOf("recording", "sending") }
    val busy = captureBusy || remote.sessions.any { it.status == "waiting" }
    var consent by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
        Text(remote.readiness, Modifier.testTag("watch.readiness"))
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
            // Read revision to refresh OS grant state on returning from system Settings.
            @Suppress("UNUSED_VARIABLE") val refreshed = permissionRevision
            val notificationsBlocked = !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(WatchControlService.CHANNEL)?.importance == android.app.NotificationManager.IMPORTANCE_NONE
            if (notificationsBlocked) TextButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Allow watch control notifications") }
            if (!remote.canOpenRecordingScreen()) TextButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.testTag("watch.screenAccess")) { Text("Allow recorder screen over lock screen") }
            TextButton(enabled = (!remote.enabled || !remote.listening) && !captureBusy, onClick = { consent = true }, modifier = Modifier.testTag("watch.enable")) { Text("Enable watch control") }
            if (remote.enabled || remote.listening) TextButton(onClick = { remote.disable() }, modifier = Modifier.testTag("watch.disable")) { Text("Disable watch control") }
            TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide diagnostics" else "Connection diagnostics") }
            if (diagnostics) {
                Text(remote.replyStatus)
                Text(remote.diagnostics)
                remote.sessions.lastOrNull()?.let { Text("Last request: ${it.status}. ${it.detail}") }
                TextButton(onClick = { remote.resendStatus() }) { Text("Send latest status to watch") }
                TextButton(onClick = { remote.connect() }, modifier = Modifier.testTag("watch.reconnect")) { Text("Reconnect watch") }
            }
        }
        if (busy) Text("Finish recording or sending before changing settings.")
        error?.let { Text(it) }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Enable watch control?") },
        text = { Text("For ${binding?.destination?.label ?: "the saved conversation"}, a fresh wrist Start will pause cooperative music and automatically record from the Bluetooth headset. Finish sends once to this conversation. The real recorder screen may wake above the lock screen; the phone stays locked. Display over other apps permits this screen to open; it does not grant microphone access. No overlay is drawn. A persistent notification lets you stop watch control.\n\nAfter enabling, choose New recording on watch. Force-stop, reboot or a locked account may require opening Ambulando and enabling again.") },
        confirmButton = { TextButton(onClick = {
            try {
                val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
                remote.enable(requireNotNull(activity) { "Open Settings in Ambulando." })
                error = null; consent = false
            } catch (e: Exception) { error = e.message; consent = false }
        }) { Text("Enable automatic headset recording") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
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


/** Legacy manual request remains available when the user has not enabled automatic control. */
@Composable
fun GarminConfirmation(model: AppModel) {
    val remote = model.garmin
    if (remote.enabled || model.environment.isSample) return
    val pending = remote.sessions.firstOrNull { it.status == "waiting" && model.voice.state.value == null } ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = { remote.dismiss(pending.session) }, title = { Text("Watch control is off") },
        text = { Text("Open the recorder for manual headset recording, or cancel and enable watch control once in Settings for automatic wrist Start.") },
        confirmButton = { TextButton(onClick = { context.startActivity(android.content.Intent(context, WatchRecordingActivity::class.java).putExtra("session", pending.session)) }) { Text("Open recorder") } },
        dismissButton = { TextButton(onClick = { remote.dismiss(pending.session) }) { Text("Cancel request") } })
}
