package studio.otherstuff.ambulando.app.garmin

import android.app.*
import android.content.*
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import com.garmin.android.connectiq.*
import kotlinx.coroutines.*
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.app.R
import studio.otherstuff.ambulando.app.voice.*
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.*
import java.io.File

/** All events, journal writes and recorder ownership changes execute on the main dispatcher. */
class GarminRemote(private val context: Context, private val model: AppModel) {
    val journal = RemoteJournal(File(model.environment.baseDir, "garmin/remote.json"))
    var sessions by mutableStateOf(journal.data.sessions); private set
    var devices by mutableStateOf<List<IQDevice>>(emptyList()); private set
    var connection by mutableStateOf("Not connected"); private set
    private var sdk: ConnectIQ? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeDevice: IQDevice? = null
    init {
        // Process death never restarts a microphone or auto-sends an interrupted recording.
        sessions.filter { it.status == "recording" || it.status == "waiting" }.forEach { update(it.session, "error", "Phone restarted. Review retained draft in Ambulando.") }
        if (journal.data.binding != null && !model.environment.isSample) connect()
        scope.launch {
            while (isActive) {
                refreshCapture()
                delay(500)
            }
        }
    }
    fun connect() {
        if (sdk != null) { loadDevices(); return }
        try {
            sdk = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
            connection = "Connecting to Garmin Connect"
            sdk!!.initialize(context, false, object : ConnectIQ.ConnectIQListener {
                override fun onSdkReady() { main.post { connection = "Select your watch"; loadDevices() } }
                override fun onSdkShutDown() { main.post { connection = "Disconnected"; sdk = null } }
                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) { main.post { connection = "Garmin Connect unavailable: $status"; sdk = null } }
            })
        } catch (e: Exception) { connection = e.message ?: "Garmin unavailable"; sdk = null }
    }
    private fun loadDevices() {
        runCatching {
            devices = sdk?.knownDevices.orEmpty()
            journal.data.binding?.let { b -> devices.firstOrNull { it.deviceIdentifier == b.device }?.let(::listen) }
        }.onFailure { connection = "Garmin not ready. Retry connection." }
    }
    fun bind(device: IQDevice, destination: VoiceDestination) {
        authorize(destination)
        check(model.voice.state.value == null) { "Finish the existing voice draft first." }
        journal.bind(RemoteBinding(device.deviceIdentifier, destination))
        listen(device)
    }
    private fun listen(device: IQDevice) {
        activeDevice = device
        sdk?.registerForDeviceEvents(device) { _, status -> main.post {
            connection = "${device.friendlyName}: $status"
            if (status == IQDevice.IQDeviceStatus.CONNECTED) runCatching { register(device) }
        } }
        register(device)
        connection = "${device.friendlyName}: open Ambulando Remote on watch"
    }
    private fun register(device: IQDevice) {
        sdk?.registerForAppEvents(device, IQApp(GARMIN_APP_ID)) { receivedDevice, app, data, status ->
            if (status == ConnectIQ.IQMessageStatus.SUCCESS && app.applicationId.replace("-", "") == GARMIN_APP_ID)
                main.post { data.orEmpty().forEach { receive(receivedDevice, it) } }
        }
    }
    internal fun receive(device: IQDevice, parcel: Any?) {
        if (journal.data.binding?.device != device.deviceIdentifier) return
        val m = parcel as? Map<*, *> ?: return
        if ((m["v"] as? Number)?.toInt() != 1) return
        val id = m["s"] as? String ?: return
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
        val op = m["op"] as? String ?: return
        if (op !in setOf("start", "finish", "cancel", "status", "retry")) return
        if (m["c"] != "${id}_$op") return
        try {
            var s = journal.find(id)
            if (s == null) {
                // Finish/Cancel arriving ahead of Start leaves a terminal tombstone; delayed Start cannot capture.
                s = journal.begin(id)
                sessions = journal.data.sessions
                if (op != "start") { update(id, "cancelled", "Start was not received; tap New to try again."); return }
                authorize(s.destination)
                check(model.voice.state.value == null) { "Another recording or retained draft is open." }
                notifyConfirmation()
                if (canOpenRecordingScreen()) {
                    // CDM permits a visible Activity launch, never direct background microphone use.
                    context.startActivity(Intent(context, WatchRecordingActivity::class.java)
                        .putExtra("session", id).putExtra(AppEnvironment.SAMPLE_EXTRA, model.environment.isSample).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } else {
                val draft = model.voice.state.value
                when (remoteAction(s.status, op, draft?.id == s.recording, draft?.phase)) {
                    RemoteAction.FINISH -> {
                        model.voice.stop()
                        if (model.voice.state.value?.phase == VoicePhase.REVIEW) {
                            update(id, "sending", "Uploading to original destination")
                            model.sendVoice()
                        } else update(id, "error", "Recording interrupted. Review draft on phone.")
                    }
                    RemoteAction.CANCEL -> {
                        if (draft?.id == s.recording) model.voice.cancel()
                        update(id, "cancelled", "Cancelled")
                    }
                    RemoteAction.RETRY -> {
                        authorize(s.destination)
                        update(id, "sending", "Retrying original recording")
                        model.sendVoice()
                    }
                    RemoteAction.NONE -> Unit
                }
            }
            journal.find(id)?.let(::reply)
        } catch (e: Exception) {
            if (journal.find(id) != null) update(id, "error", e.message ?: "Phone operation failed")
            else runCatching { sdk?.sendMessage(device, IQApp(GARMIN_APP_ID), mapOf("v" to 1, "s" to id, "state" to "error", "r" to 0, "detail" to (e.message ?: "Operation failed")), null) }
        }
    }
    fun associate(device: Long, association: Int) {
        val b = requireNotNull(journal.data.binding)
        check(b.device == device) { "Watch binding changed. Retry association." }
        journal.bind(b.copy(association = association))
    }
    fun canOpenRecordingScreen(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val id = journal.data.binding?.association ?: return false
        return context.getSystemService(android.companion.CompanionDeviceManager::class.java)?.myAssociations?.any { it.id == id } == true
    }
    fun confirm(id: String) {
        val s = journal.find(id) ?: return
        if (s.status != "waiting" || model.voice.state.value != null) return
        try {
            check(model.foreground) { "Open Ambulando before starting." }
            authorize(s.destination)
            model.voice.start(s.destination, s.recording, headsetOnly = true)
            update(id, "waiting", "Verifying Bluetooth microphone")
            context.getSystemService(NotificationManager::class.java).cancel(3410)
        } catch (e: Exception) { update(id, "error", e.message ?: "Recording could not start") }
    }
    fun dismiss(id: String) {
        val session = journal.find(id) ?: return
        if (session.status != "waiting") return
        if (model.voice.state.value?.id == session.recording) model.voice.cancel()
        update(id, "cancelled", "Cancelled on phone")
    }
    private fun authorize(d: VoiceDestination) {
        check(model.identityState is IdentityState.Unlocked && model.identityState?.npub == d.signerNpub) { "Unlock the original signed-in account on phone." }
        check(model.runtimeMap.values.any { d.matches(it.connection, it.workspace) }) { "Open the original trusted workspace on phone." }
    }
    private suspend fun refreshCapture() {
        for (s in journal.data.sessions.filter { it.status in setOf("waiting", "recording", "sending", "error") }) {
            val d = model.voice.state.value?.takeIf { it.id == s.recording }
            when {
                d?.phase == VoicePhase.RECORDING && s.status == "waiting" -> update(s.session, "recording", "Bluetooth microphone recording")
                s.status == "recording" && d == null -> update(s.session, "error", "Recording ended on phone. Review the original conversation.")
                d?.phase == VoicePhase.INTERRUPTED -> update(s.session, "error", d.error ?: "Recording interrupted; review on phone")
                d?.phase == VoicePhase.REVIEW && s.status == "recording" -> update(s.session, "error", "Recording stopped on phone or reached its limit. Review audio before sending.")
                d?.phase == VoicePhase.REVIEW && s.status == "sending" -> update(s.session, "error", d.error ?: "Audio kept; retry on phone")
                s.status == "sending" || (s.status == "error" && d == null) -> {
                    val owner = model.runtimeMap.values.firstOrNull { s.destination.matches(it.connection, it.workspace) } ?: continue
                    if (owner.store.reader.sentMessage(s.destination.workspaceId, s.recording) != null) update(s.session, "sent", "Delivered to original destination")
                    else if (s.status == "sending" && owner.store.reader.outboxItem(s.recording)?.state == OutboxState.FAILED) update(s.session, "error", "Message rejected. Review original conversation outbox.")
                }
            }
        }
    }
    private fun update(id: String, state: String, detail: String) {
        val before = journal.find(id)?.revision
        val s = journal.update(id, state, detail)
        if (s.revision == before) return
        sessions = journal.data.sessions
        reply(s)
    }
    private fun reply(s: RemoteSession) {
        val device = activeDevice ?: return
        runCatching { sdk?.sendMessage(device, IQApp(GARMIN_APP_ID), mapOf("v" to 1, "s" to s.session, "r" to s.revision, "state" to s.status, "detail" to s.detail), null) }
    }
    private fun notifyConfirmation() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch_remote_v1", "Watch recorder requests", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 3410, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(3410, NotificationCompat.Builder(context, "watch_remote_v1").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watch recorder: confirm on phone").setContentText("Open Ambulando to start Bluetooth microphone recording")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
