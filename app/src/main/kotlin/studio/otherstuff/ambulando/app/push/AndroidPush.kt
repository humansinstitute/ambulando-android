package studio.otherstuff.ambulando.app.push

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.notifications.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.time.Instant

/** Push supplies hints; existing authenticated Tower reads remain the only authority for content and navigation. */
class AndroidPush(private val app: AmbulandoApplication) {
    val state = PushState(app)
    val firebase = FirebaseDevice(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    init { createChannel() }
    fun launch(block: suspend AndroidPush.() -> Unit) { scope.launch { try { withTimeout(25_000) { block() } } catch (_: Exception) { /* No provider URLs, keys or response bodies in logs. */ } } }
    fun createChannel() { manager.createNotificationChannel(NotificationChannel(CHANNEL, "Workspace activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Private alerts from your connected workspaces"
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }) }
    fun permissionGranted(): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(app).areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    suspend fun enable(model: AppModel): Unit = mutex.withLock {
        val c = model.connection ?: return@withLock
        val w = model.workspace ?: return@withLock
        if (model.environment.isSample || model.identityState !is IdentityState.Unlocked || w.accessRemoved) return@withLock
        // Reusing a disabled enrollment requires finishing its revocation first; never let delayed DELETE undo re-enable.
        val previous = state.all().firstOrNull { it.connectionId == c.id && it.signerNpub == c.signerNpub && it.workspaceId == w.id }
        if (previous != null && !previous.enabled) {
            revoke(model, previous)
            if (state.all().any { it.instance == previous.instance && !it.enabled }) {
                fail(previous.instance, "Finish server revocation before enabling push again. Unlock and retry.")
                return@withLock
            }
        }
        val row = state.enrollment(c.id, c.signerNpub, w.id, w.appNpub, c.origin, c.towerNpub)
        state.add(row)
        firebase.unavailableReason()?.let { fail(row.instance, it); return@withLock }
        renewOne(model, row.instance)
    }

    /** Token callbacks never construct a session or unlock a signer; registration resumes after unlock. */
    fun newToken() {
        state.all().filter { it.enabled }.forEach { row ->
            state.update(row.instance) { it.copy(ready = false, token = null, message = "Unlock to renew phone push.") }
        }
        app.activeModel?.takeUnless { it.environment.isSample }?.let { model -> launch { reconcile(model) } }
    }

    /** Renewal/revocation needs signed writes. Locked apps retain the pending change until the next unlock. */
    suspend fun reconcile(model: AppModel) = mutex.withLock {
        if (model.environment.isSample || model.identityState !is IdentityState.Unlocked) return@withLock
        for (row in state.all()) {
            if (!row.enabled) { revoke(model, row); continue }
            val lease = runCatching { Instant.parse(row.leaseExpiresAt).toEpochMilli() }.getOrDefault(0)
            if (!row.ready || !permissionGranted() || lease < System.currentTimeMillis() + 86400_000) renewOne(model, row.instance)

        }
    }

    private suspend fun renewOne(model: AppModel, instance: String) {
        val row = state.all().firstOrNull { it.instance == instance && it.enabled } ?: return
        val c = model.catalog.reader.connections().firstOrNull { it.id == row.connectionId && it.signerNpub == row.signerNpub && it.origin == row.origin } ?: return
        if (model.identityState !is IdentityState.Unlocked || model.identityState?.npub != row.signerNpub) return
        val w = model.catalog.reader.workspaces(c.id).firstOrNull { it.id == row.workspaceId && it.appNpub == row.appNpub && !it.accessRemoved } ?: return
        try {
            firebase.unavailableReason()?.let { fail(instance, it); return }
            val project = firebase.projectId() ?: return
            val readiness = model.towerAPI(c).pushReadiness(w)
            if (!readiness.ready || readiness.projectId != project || readiness.senderId != firebase.senderId() || readiness.packageName != app.packageName) {
                fail(instance, if (readiness.ready) "Firebase project does not match this server." else readinessMessage(readiness.reason)); return
            }
            val token = firebase.token()
            if (state.all().none { it.instance == instance && it.enabled }) return
            val r = model.towerAPI(c).registerPush(w, row.installationId, token, project, firebase.senderId() ?: return, app.packageName, permissionGranted(), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            state.update(instance) { if (it.enabled) it.copy(token = token, projectId = project, ready = r.ready, leaseExpiresAt = r.leaseExpiresAt, message = if (r.ready) "Ready for push" else readinessMessage(r.reason)) else it }
        } catch (_: Exception) { fail(instance, "Registration failed. Check server setup, unlock and retry.") }
    }

    suspend fun disable(model: AppModel, connectionId: String, workspaceId: String? = null) {
        val rows = state.all().filter { it.connectionId == connectionId && (workspaceId == null || it.workspaceId == workspaceId) }
        // Reject delivery before waiting for an in-flight network read/registration.
        for (row in rows) {
            state.update(row.instance) { it.copy(enabled = false, ready = false, token = null, message = "Off; server revocation pending") }
            manager.activeNotifications.filter { it.tag?.startsWith(row.installationId + ":" + row.workspaceId + ":") == true }.forEach { manager.cancel(it.tag, it.id) }
        }
        mutex.withLock {
            for (row in rows) revoke(model, row.copy(enabled = false))
            if (state.all().none { it.enabled }) { firebase.requireTokenReset(); runCatching { firebase.deleteToken() } }
        }
    }
    private suspend fun revoke(model: AppModel, row: PushRegistration) {
        if (model.identityState !is IdentityState.Unlocked || model.identityState?.npub != row.signerNpub) return
        val c = model.catalog.reader.connections().firstOrNull { it.id == row.connectionId && it.signerNpub == row.signerNpub } ?: return
        val w = model.catalog.reader.workspaces(c.id).firstOrNull { it.id == row.workspaceId && it.appNpub == row.appNpub } ?: return
        try { model.towerAPI(c).revokePush(w, row.installationId); state.remove(row.instance) }
        catch (_: Exception) { /* Local opt-out already applies. Expiry bounds server registration if offline. */ }
    }
    suspend fun removeIdentity() {
        state.all().forEach { row -> state.update(row.instance) { it.copy(enabled = false, ready = false, token = null) } }
        manager.cancelAll()
        firebase.requireTokenReset()
        runCatching { firebase.deleteToken() }
    }
    fun fail(instance: String, message: String) { state.update(instance) { it.copy(ready = false, message = message) } }
    /** Foreground FCM is handled locally. Background notification+data is displayed by the SDK/OS, without reads. */
    suspend fun message(bytes: ByteArray) = mutex.withLock {
        val accepted = acceptedHint(bytes) ?: return@withLock
        val (row, hint) = accepted
        if ((row.seen[hint.notificationId] ?: 0) >= hint.changeSeq || !permissionGranted()) return@withLock
        val model = app.activeModel?.takeUnless { it.environment.isSample }
        remember(row, hint)
        if (model?.foreground == true) return@withLock // Existing live inbox owns foreground presentation.
        val intent = Intent(app, MainActivity::class.java).setAction(TAP).putExtra(INSTANCE, row.instance)
            .putExtra(ITEM, hint.notificationId).putExtra(SEQUENCE, hint.changeSeq)
            .setData(android.net.Uri.parse("ambulando-push://local/${row.instance}/${hint.notificationId}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New workspace activity").setContentText("Open Ambulando to view activity.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(pending).build()
        if (permissionGranted() && state.all().any { it.instance == row.instance && it.enabled })
            manager.notify(row.installationId + ":" + row.workspaceId + ":" + hint.notificationId, 0, notification)
    }

    /** Also used for cold notification taps: incoming fields select a stored boundary, never a remote URL. */
    fun acceptedHint(bytes: ByteArray): Pair<PushRegistration, PushHint>? {
        val hint = PushHint.parse(bytes) ?: return null
        val row = state.matching(hint, firebase.projectId()) ?: return null
        return row to hint
    }
    fun acceptFcmTap(intent: Intent): Triple<String, String, Long>? {
        val raw = intent.getStringExtra("fd") ?: return null
        val (row, hint) = acceptedHint(raw.toByteArray()) ?: return null
        return Triple(row.instance, hint.notificationId, hint.changeSeq)
    }
    private fun remember(row: PushRegistration, hint: PushHint) = state.update(row.instance) {
        it.copy(seen = (it.seen.filterKeys { id -> id != hint.notificationId }.entries.toList().takeLast(199).associate { e -> e.toPair() } + (hint.notificationId to hint.changeSeq)))
    }
    companion object {
        const val CHANNEL = "workspace_activity_v1"
        const val TAP = "studio.otherstuff.ambulando.PUSH_TAP"
        const val INSTANCE = "push_instance"
        const val ITEM = "push_item"
        const val SEQUENCE = "push_seq"
        fun genericTitle(source: String, category: String): String = if (source != "flightdeck.core") "New workspace activity" else when (category) {
            "mention" -> "You were mentioned"; "dm" -> "New direct message"; "comment_tag" -> "You were mentioned in a comment"
            "task_assignment" -> "A task was assigned to you"; "chat_thread" -> "New thread activity"; else -> "New workspace activity"
        }
        fun readinessMessage(reason: String?): String = when (reason) {
            "transport_not_implemented" -> "This NStack server does not support Android push yet."
            "permission_not_granted" -> "Android notifications are blocked."
            "project_mismatch" -> "Firebase project does not match this server."
            else -> "Server push is not ready. Check the setup guide."
        }
    }
}
