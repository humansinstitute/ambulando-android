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
import studio.otherstuff.ambulando.app.BuildConfig
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
    var diagnostics by mutableStateOf("Phone ${BuildConfig.VERSION_CODE}: RX 0 / rejected 0"); private set
    private var rx = 0
    private var rejected = 0
    private fun diagnostic(code: String, reject: Boolean = false) {
        if (reject) rejected++
        diagnostics = "Phone ${BuildConfig.VERSION_CODE}: RX $rx / rejected $rejected / $code"
        android.util.Log.i("GarminRemote", diagnostics) // Codes only: no audio, account, destination or payload.
    }
    var binding by mutableStateOf(journal.data.binding); private set
    var enabled by mutableStateOf(journal.data.consent?.binding == binding && binding != null); private set
    var listening by mutableStateOf(false); private set
    var readiness by mutableStateOf("Watch control is disabled. Saved destination and drafts are kept."); private set
    private var authorityProblem: String? = null
    private val processStartedSeconds = System.currentTimeMillis() / 1000
    private val launchable = mutableSetOf<String>()
    private data class PendingStart(val binding: RemoteBinding, val revision: Int)
    private val awaitingReceipt = mutableMapOf<String, PendingStart>()
    private val sdkContext = GarminSdkContext(context) { diagnostic("P0 malformed IPC", true) }
    private var sdk: ConnectIQ? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val replies = WatchReplies(android.os.SystemClock::elapsedRealtime, { java.util.UUID.randomUUID().toString() },
        { id, packet, done ->
            val device = link.active?.takeIf { it.deviceIdentifier == id } ?: error("Watch unavailable")
            checkNotNull(sdk).sendMessage(device, IQApp(GARMIN_APP_ID), packet) { sent, app, result ->
                main.post { if (sent.deviceIdentifier == id && isRecorderApp(app)) done(result == ConnectIQ.IQMessageStatus.SUCCESS) }
            }
        }, { if (replyStatus != it) { replyStatus = it; android.util.Log.i("GarminRemote", it) } }, { link.received(it) }, { device, packet -> main.post { startAfterReceipt(device, packet) } })
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
            override fun clearListeners() { replies.reset(); instance.unregisterAllForEvents() }
            override fun shutdown() { try { instance.shutdown(sdkContext) } finally { replies.reset(transportClosed = true); if (sdk === instance) sdk = null } }
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
                try {
                    if (enabled && permissionProblem() != null) suspendAuthority(permissionProblem()!!)
                    readiness = when {
                        !enabled && listening -> authorityProblem ?: "Watch control needs setup. Enable again in Settings."
                        !enabled -> "Watch control disabled. Enable in Settings before a fresh wrist Start."
                        !listening -> "Watch listener stopped. Open Settings and enable watch control again."
                        model.identityState !is IdentityState.Unlocked -> "Unlock the original account in Ambulando before using watch control."
                        else -> "Watch control listening. Bluetooth headset required for Start."
                    }
                    if (listening && link.phase in setOf(GarminLink.Phase.ERROR, GarminLink.Phase.IDLE) && android.os.SystemClock.elapsedRealtime() >= reconnectAfter) {
                        reconnectAfter = android.os.SystemClock.elapsedRealtime() + 30_000
                        link.connect()
                    }
                    link.tick(); replies.tick(); refreshCapture()
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { connection = "Status unavailable. Reopen the original workspace." }
                delay(500)
            }
        }
    }
    fun connect() { replies.reset(); link.connect() }
    fun bind(device: IQDevice, destination: VoiceDestination) {
        authorize(destination)
        check(model.voice.state.value?.phase !in setOf(VoicePhase.STARTING, VoicePhase.RECORDING, VoicePhase.SENDING)) { "Finish recording or sending first." }
        disable()
        replies.reset()
        journal.bind(RemoteBinding(device.deviceIdentifier, destination, binding?.takeIf { it.device == device.deviceIdentifier }?.association))
        binding = journal.data.binding
        link.bind(device.deviceIdentifier)
    }
    /** Settings calls this only after the explicit consent dialog, while its Activity is resumed. */
    fun enable(activity: androidx.activity.ComponentActivity) {
        check(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { "Keep Settings visible while enabling watch control." }
        val saved = requireNotNull(binding) { "Save a destination and watch first." }
        authorize(saved.destination)
        check(permissionProblem() == null) { permissionProblem().orEmpty() }
        invalidateWaiting("Setup changed. Choose New recording on watch after enabling.")
        journal.consent(WatchConsent(saved, System.currentTimeMillis() / 1000))
        enabled = true
        authorityProblem = null
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, WatchControlService::class.java)
                .putExtra(AppEnvironment.SAMPLE_EXTRA, model.environment.isSample))
        } catch (e: Exception) { disable(); throw e }
    }
    fun disable(reason: String = "Watch control stopped. Saved destination and drafts kept.") {
        enabled = false
        journal.consent(null)
        launchable.clear(); awaitingReceipt.clear()
        invalidateWaiting(reason)
        sessions.filter { it.status == "recording" }.forEach { s ->
            if (model.voice.state.value?.id == s.recording) model.voice.stop()
            update(s.session, "error", "Watch control stopped. Review retained audio on phone.")
        }
        context.stopService(Intent(context, WatchControlService::class.java))
        listenerStopped()
        readiness = reason
    }
    private fun suspendAuthority(reason: String) {
        enabled = false
        authorityProblem = reason
        journal.consent(null)
        launchable.clear(); awaitingReceipt.clear()
        sessions.filter { it.status in setOf("waiting", "recording") }.forEach { s ->
            if (model.voice.state.value?.id == s.recording) model.voice.stop()
            update(s.session, "error", reason)
        }
    }
    private fun invalidateWaiting(reason: String) {
        sessions.filter { it.status == "waiting" }.forEach { s ->
            if (model.voice.state.value?.id == s.recording) model.voice.stop()
            update(s.session, "cancelled", reason)
        }
    }
    internal fun restoreAllowed(): Boolean = enabled && journal.data.consent?.binding == binding && permissionProblem() == null
    internal fun listenerStarted() { listening = true; binding?.let { link.bind(it.device) }; link.connect() }
    internal fun listenerStopped() { listening = false; launchable.clear(); awaitingReceipt.clear(); replies.reset(); link.stop() }
    fun permissionProblem(): String? {
        val required = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(android.Manifest.permission.BLUETOOTH_CONNECT)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (required.any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) return "Allow microphone, nearby devices and notifications in Settings."
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return "Enable Ambulando notifications in Android Settings."
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(WatchControlService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
            return "Enable watch control notifications in Android Settings so Stop watch control remains visible."
        if (!canOpenRecordingScreen()) return "Allow Display over other apps in Settings so the watch can open the recorder above the lock screen."
        return null
    }
    private fun registerApp(instance: ConnectIQ, device: IQDevice) {
        instance.registerForAppEvents(device, IQApp(GARMIN_APP_ID)) { receivedDevice, app, data, status ->
            main.post {
                if (binding?.device != receivedDevice.deviceIdentifier || !isRecorderApp(app)) { diagnostic("P1 binding/app", true); return@post }
                if (status == ConnectIQ.IQMessageStatus.SUCCESS) data.orEmpty().forEach { receive(receivedDevice, it) }
                else link.transportError("Watch message failed: $status. Check Garmin Connect and retry from watch.")
            }
        }
    }
    private var reconnectAfter = 0L
    private var receiptCapable = false
    private var receiving = false
    private val commandIds = mutableMapOf<String, String>()
    internal fun receive(device: IQDevice, parcel: Any?) {
        rx++
        if (journal.data.binding?.device != device.deviceIdentifier) { diagnostic("P1 binding", true); return }
        val m = parcel as? Map<*, *> ?: run { diagnostic("P2 shape", true); return }
        if (m["v"] != 1 && m["v"] != 1L) { diagnostic("P3 version", true); return }
        if (m["op"] == "receipt") { val valid = replies.receipt(device.deviceIdentifier, m); diagnostic(if (valid) "receipt accepted" else "P4 receipt stale/invalid", !valid); return }
        if (m["op"] == "hello") {
            diagnostic("hello received")
            receiptCapable = true
            replies.offer(device.deviceIdentifier, mapOf("v" to 1, "s" to "hello", "r" to 0, "state" to "hello", "detail" to "Phone listener ready", "b" to BuildConfig.VERSION_CODE))
            return // No journal entry, tombstone, recording or ACK loop.
        }
        receiptCapable = (m["b"] as? Int)?.let { it >= 38 } == true || (m["b"] as? Long)?.let { it >= 38 } == true
        val id = m["s"] as? String ?: run { diagnostic("P5 session", true); return }
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { diagnostic("P5 session", true); return }
        val op = m["op"] as? String ?: return
        if (op !in setOf("start", "finish", "cancel", "status", "retry")) return
        if (m["c"] != "${id}_$op") { diagnostic("P6 command", true); return }
        diagnostic("command $op received")
        commandIds[id] = "${id}_$op"
        receiving = true
        try {
            var s = journal.find(id)
            if (s == null) {
                // Finish/Cancel arriving ahead of Start leaves a terminal tombstone; delayed Start cannot capture.
                s = journal.begin(id)
                sessions = journal.data.sessions
                if (op != "start") { update(id, "cancelled", "Start was not received; tap New to try again."); return }
                authorize(s.destination)
                check(model.voice.state.value == null) { "Another recording or retained draft is open." }
                if (!enabled) {
                    check(!listening) { authorityProblem ?: "Enable automatic watch control again in Settings, then choose New recording." }
                    update(id, "waiting", "Watch control off. Open Ambulando for manual headset recording, or enable watch control in Settings.")
                    runCatching { notifyConfirmation() }
                    return
                }
                check(listening && journal.data.consent?.binding == binding) { "Enable watch control in phone Settings, then choose New recording on watch." }
                check(permissionProblem() == null) { permissionProblem().orEmpty() }
                check(freshWatchStart(id, maxOf(processStartedSeconds, journal.data.consent!!.enabledAtSeconds), System.currentTimeMillis() / 1000)) {
                    "Old Start or watch clock mismatch. Check watch time, then choose New recording."
                }
                check(receiptCapable) { "Update Ambulando Remote on the watch for automatic recording." }
                update(id, "waiting", "Confirming this Start with watch")
                awaitingReceipt[id] = PendingStart(binding!!, journal.find(id)!!.revision)
                scope.launch {
                    delay(25_000)
                    if (awaitingReceipt.remove(id) != null) update(id, "error", "Watch Start receipt timed out. Check connection, then choose New recording.")
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
        } catch (e: Exception) {
            if (journal.find(id) != null) update(id, "error", e.message ?: "Phone operation failed")
            else replies.offer(device.deviceIdentifier, mapOf("v" to 1, "s" to id, "state" to "error", "r" to 0, "detail" to "Phone operation unavailable", "b" to BuildConfig.VERSION_CODE), receiptCapable)
        } finally {
            receiving = false
            journal.find(id)?.let(::reply)
        }
    }
    /** A forgeable SDK broadcast is not Start authority. The actual wrist must apply this nonce snapshot. */
    private fun startAfterReceipt(device: Long, packet: Map<String, Any>) {
        val id = packet["s"] as? String ?: return
        val pending = awaitingReceipt[id] ?: return
        val s = journal.find(id) ?: return
        if (device != pending.binding.device || packet["r"] != pending.revision || packet["c"] != "${id}_start" ||
            packet["state"] != "waiting" || s.revision != pending.revision || s.status != "waiting") return
        awaitingReceipt.remove(id)
        try {
            check(enabled && listening && binding == pending.binding && journal.data.consent?.binding == pending.binding) { "Watch control settings changed. Enable again and choose New recording." }
            check(permissionProblem() == null) { permissionProblem().orEmpty() }
            authorize(s.destination)
            launchable.add(id)
            // Direct Activity launch: SAW/CDM grants BAL; the focused real Activity grants mic eligibility.
            context.startActivity(Intent(context, WatchRecordingActivity::class.java).putExtra("session", id)
                .putExtra(AppEnvironment.SAMPLE_EXTRA, model.environment.isSample).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scope.launch {
                delay(10_000)
                if (launchable.remove(id)) update(id, "error", "Android did not focus the recorder. Check permissions, then choose New recording.")
            }
        } catch (e: Exception) { launchable.remove(id); update(id, "error", e.message ?: "Could not open recorder") }
    }
    fun resendStatus() { journal.data.sessions.lastOrNull()?.let(::reply) }
    fun canOpenRecordingScreen(): Boolean {
        if (android.provider.Settings.canDrawOverlays(context)) return true
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val id = journal.data.binding?.association ?: return false
        return runCatching { context.getSystemService(android.companion.CompanionDeviceManager::class.java)?.myAssociations?.any { it.id == id } == true }.getOrDefault(false)
    }

    fun confirm(id: String, activity: Activity, manual: Boolean = false) {
        val s = journal.find(id) ?: return
        if (s.status != "waiting" || model.voice.state.value != null) return
        if (activity !is WatchRecordingActivity || activity.intent.getStringExtra("session") != id || !activity.hasWindowFocus() ||
            !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        try {
            if (manual) check(!enabled) { "Automatic watch control is active. Choose New recording on watch." }
            else {
                check(enabled && listening && journal.data.consent?.binding == binding && s.destination == binding?.destination && launchable.remove(id)) { "Watch start authority expired. Choose New recording after enabling watch control." }
                check(permissionProblem() == null) { permissionProblem().orEmpty() }
            }
            authorize(s.destination)
            model.watchDelivery(s.destination)
            android.util.Log.i("WatchRecorder", "focused/resumed; keyguardLocked=${context.getSystemService(KeyguardManager::class.java).isKeyguardLocked}; interactive=${context.getSystemService(android.os.PowerManager::class.java).isInteractive}")
            model.voice.start(s.destination, s.recording, headsetOnly = true)
            update(id, "waiting", "Verifying Bluetooth microphone")
            context.getSystemService(NotificationManager::class.java).cancel(3410)
        } catch (e: Exception) { update(id, "error", e.message ?: "Recording could not start") }
    }
    fun screenHidden(id: String) {
        val s = journal.find(id) ?: return
        if (s.status != "waiting") return
        if (model.voice.state.value?.let { it.id == s.recording && it.phase == VoicePhase.RECORDING } == true) {
            update(id, "recording", "Bluetooth microphone recording")
            return
        }
        launchable.remove(id)
        if (model.voice.state.value?.id == s.recording) model.voice.stop()
        update(id, "error", "Recorder lost focus before capture was ready. Choose New recording.")
    }
    fun dismiss(id: String) {
        val session = journal.find(id) ?: return
        if (session.status != "waiting") return
        if (model.voice.state.value?.id == session.recording) model.voice.cancel()
        update(id, "cancelled", "Cancelled on phone")
    }
    private fun authorize(d: VoiceDestination) {
        check(model.identityState is IdentityState.Unlocked && model.identityState?.npub == d.signerNpub) { "Unlock the original signed-in account on phone." }
        check(model.runtimeMap.values.any { d.matches(it.connection, it.workspace) && it.enabled && !it.workspace.accessRemoved && it.activity.isOpen }) { "Open the original trusted workspace on phone." }
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
        if (state == "sending") model.watchDelivery(s.destination)
        if (state in setOf("sent", "error", "cancelled")) {
            launchable.remove(id); awaitingReceipt.remove(id)
            if (sessions.none { it.status in setOf("recording", "sending") }) model.watchDelivery(null)
        }
        if (!receiving) reply(s)
    }
    private fun reply(s: RemoteSession) {
        val device = link.active ?: run { replyStatus = "Reply pending: reconnect the saved watch, then check Status."; return }
        replies.offer(device.deviceIdentifier, mapOf("v" to 1, "s" to s.session, "r" to s.revision, "state" to s.status, "detail" to s.detail, "b" to BuildConfig.VERSION_CODE, "c" to (commandIds[s.session] ?: "")), receiptCapable)
    }

    private fun notifyConfirmation() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch_remote_v1", "Watch recorder requests", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 3410, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(3410, NotificationCompat.Builder(context, "watch_remote_v1").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watch control is off").setContentText("Open Ambulando for manual recording, or enable watch control in Settings")
            .setContentIntent(open).setAutoCancel(true).build())
    }

}

/** IQApp canonicalizes UUIDs to uppercase, including after external IPC. */
internal fun isRecorderApp(app: IQApp): Boolean = app.applicationId.replace("-", "").equals(GARMIN_APP_ID, ignoreCase = true)
