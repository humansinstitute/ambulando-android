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
    var replyStatus by mutableStateOf("No phone reply sent yet"); private set
    var binding by mutableStateOf(journal.data.binding); private set
    private val sdkContext = GarminSdkContext(context) { connection = "Invalid watch message ignored. Retry from watch." }
    private var sdk: ConnectIQ? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val link: GarminLink = GarminLink(::transport, android.os.SystemClock::elapsedRealtime) {
        devices = linkDevices()
        connection = linkMessage()
    }
    private fun linkDevices(): List<IQDevice> = link.devices
    private fun linkMessage(): String = link.message
    private fun transport(): GarminTransport {
        val instance = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        sdk = instance
        return object : GarminTransport {
            override fun initialize(ready: () -> Unit, stopped: (String) -> Unit) {
                instance.initialize(sdkContext, false, object : ConnectIQ.ConnectIQListener {
                    override fun onSdkReady() { main.post { ready() } }
                    override fun onSdkShutDown() { main.post { stopped("Garmin Connect disconnected. Retry connection.") } }
                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) { main.post { stopped("Garmin Connect unavailable: $status. Open Garmin Connect, then retry.") } }
                })
            }
            override fun knownDevices() = instance.knownDevices.orEmpty()
            override fun status(device: IQDevice) = instance.getDeviceStatus(device)
            override fun listen(device: IQDevice, changed: (IQDevice.IQDeviceStatus) -> Unit) {
                instance.registerForDeviceEvents(device) { _, status -> main.post { changed(status) } }
            }
            override fun register(device: IQDevice) { registerApp(instance, device) }
            override fun clearListeners() { instance.unregisterAllForEvents() }
            override fun shutdown() { instance.shutdown(sdkContext); if (sdk === instance) sdk = null }
        }
    }
    init {
        // Process death never restarts a microphone or auto-sends an interrupted recording.
        sessions.filter { it.status == "recording" || it.status == "waiting" }.forEach {
            update(it.session, "error", if (it.status == "waiting") "Phone restarted before Start. Check Status, then New recording on watch." else "Phone restarted. Review retained draft in Ambulando.")
        }
        if (journal.data.binding != null && !model.environment.isSample) link.bind(journal.data.binding!!.device)
        scope.launch {
            while (isActive) {
                try { link.tick(); refreshCapture() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { connection = "Status unavailable. Reopen the original workspace." }
                delay(500)
            }
        }
    }
    fun connect() { link.connect() }
    fun bind(device: IQDevice, destination: VoiceDestination) {
        authorize(destination)
        check(model.voice.state.value?.phase !in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.SENDING)) { "Finish recording or sending first." }
        journal.bind(RemoteBinding(device.deviceIdentifier, destination, binding?.takeIf { it.device == device.deviceIdentifier }?.association))
        binding = journal.data.binding
        link.bind(device.deviceIdentifier)
    }
    fun disable() {
        check(model.voice.state.value?.phase !in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.SENDING)) { "Finish recording or sending first." }
        journal.bind(null)
        binding = null
        link.bind(null)
    }
    private fun registerApp(instance: ConnectIQ, device: IQDevice) {
        instance.registerForAppEvents(device, IQApp(GARMIN_APP_ID)) { receivedDevice, app, data, status ->
            main.post {
                if (binding?.device != receivedDevice.deviceIdentifier || !isRecorderApp(app)) return@post
                if (status == ConnectIQ.IQMessageStatus.SUCCESS) data.orEmpty().forEach { receive(receivedDevice, it) }
                else link.transportError("Watch message failed: $status. Check Garmin Connect and retry from watch.")
            }
        }
    }
    internal fun receive(device: IQDevice, parcel: Any?) {
        if (journal.data.binding?.device != device.deviceIdentifier) return
        val m = parcel as? Map<*, *> ?: return
        if (m["v"] != 1 && m["v"] != 1L) return
        val id = m["s"] as? String ?: return
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
        val op = m["op"] as? String ?: return
        if (op !in setOf("start", "finish", "cancel", "status", "retry")) return
        if (m["c"] != "${id}_$op") return
        link.received(device.deviceIdentifier)
        try {
            var s = journal.find(id)
            if (s == null) {
                // Finish/Cancel arriving ahead of Start leaves a terminal tombstone; delayed Start cannot capture.
                s = journal.begin(id)
                sessions = journal.data.sessions
                if (op != "start") { update(id, "cancelled", "Start was not received; tap New to try again."); return }
                authorize(s.destination)
                check(model.voice.state.value == null) { "Another recording or retained draft is open." }
                runCatching { notifyConfirmation() }
                update(id, "waiting", "Open Ambulando and tap Start on phone; optional watch access is not required.")
                if (canOpenRecordingScreen()) {
                    // CDM permits a visible Activity launch, never direct background microphone use.
                    runCatching { context.startActivity(Intent(context, WatchRecordingActivity::class.java)
                        .putExtra("session", id).putExtra(AppEnvironment.SAMPLE_EXTRA, model.environment.isSample).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { update(id, "waiting", "Open Ambulando and tap Start on phone. Android could not open the recorder automatically.") }
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
    fun resendStatus() { journal.data.sessions.lastOrNull()?.let(::reply) }
    fun canOpenRecordingScreen(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val id = journal.data.binding?.association ?: return false
        return context.getSystemService(android.companion.CompanionDeviceManager::class.java)?.myAssociations?.any { it.id == id } == true
    }
    fun confirm(id: String, activity: Activity) {
        val s = journal.find(id) ?: return
        if (s.status != "waiting" || model.voice.state.value != null) return
        if (activity !is WatchRecordingActivity || activity.intent.getStringExtra("session") != id || !activity.hasWindowFocus() ||
            !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        try {
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
        val device = link.active ?: run { replyStatus = "Reply pending: reconnect the saved watch, then check Status."; return }
        try {
            replyStatus = "Sending ${s.status} reply to Garmin Connect…"
            sdk?.sendMessage(device, IQApp(GARMIN_APP_ID), mapOf("v" to 1, "s" to s.session, "r" to s.revision, "state" to s.status, "detail" to s.detail)) { sentDevice, app, status ->
                main.post {
                    if (binding?.device == sentDevice.deviceIdentifier && isRecorderApp(app)) {
                        replyStatus = if (status == ConnectIQ.IQMessageStatus.SUCCESS) "Garmin Connect accepted ${s.status} reply. Wrist receipt is not confirmed." else "Garmin reply failed: $status. Reconnect, then check Status on watch."
                        if (status != ConnectIQ.IQMessageStatus.SUCCESS) link.transportError(replyStatus)
                    }
                }
            }
        } catch (e: Exception) { replyStatus = "Reply failed. Reconnect, then check Status on watch."; link.transportError("Watch acknowledgement failed: ${e.message ?: e.javaClass.simpleName}. Retry from watch.") }
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

/** IQApp canonicalizes UUIDs to uppercase, including after external IPC. */
internal fun isRecorderApp(app: IQApp): Boolean = app.applicationId.replace("-", "").equals(GARMIN_APP_ID, ignoreCase = true)
