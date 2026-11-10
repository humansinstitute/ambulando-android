package studio.otherstuff.ambulando.app.push

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import studio.otherstuff.ambulando.app.*
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.notifications.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.refreshNotifications
import studio.otherstuff.ambulando.core.transport.*
import java.time.Instant

/** Push supplies hints; existing authenticated Tower reads remain the only authority for content and navigation. */
class AndroidPush(private val app: AmbulandoApplication) {
    val state = PushState(app)
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

    suspend fun enable(model: AppModel, activity: Activity): Unit = mutex.withLock {
        val c = model.connection ?: return@withLock
        val w = model.workspace ?: return@withLock
        if (model.environment.isSample || model.identityState !is IdentityState.Unlocked || w.accessRemoved) return@withLock
        val old = state.all().firstOrNull { it.connectionId == c.id && it.workspaceId == w.id && it.signerNpub == c.signerNpub }
        val row = old?.copy(enabled = true, ready = false) ?: PushRegistration(connectionId = c.id, signerNpub = c.signerNpub, workspaceId = w.id, appNpub = w.appNpub, origin = c.origin)
        state.add(row)
        try {
            val readiness = model.towerAPI(c).pushReadiness(w)
            if (!readiness.ready || readiness.vapidPublicKey == null) {
                state.update(row.instance) { it.copy(message = readinessMessage(readiness.reason), ready = false) }; return@withLock
            }
            state.update(row.instance) { it.copy(vapid = readiness.vapidPublicKey, message = "Waiting for distributor") }
            UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
                if (success) UnifiedPush.register(app, instance = row.instance, messageForDistributor = "Ambulando workspace", vapid = readiness.vapidPublicKey)
                else state.update(row.instance) { it.copy(message = "Install and configure a UnifiedPush distributor, then retry.") }
            }
        } catch (_: Exception) { state.update(row.instance) { it.copy(message = "Could not check server. Unlock and retry.", ready = false) } }
    }

    suspend fun newEndpoint(instance: String, endpoint: PushEndpoint) = mutex.withLock {
        val row = state.all().firstOrNull { it.instance == instance && it.enabled } ?: return@withLock
        val keys = endpoint.pubKeySet ?: run { fail(instance, "Distributor did not supply encryption keys."); return@withLock }
        // Store locally; only Tower's operator-controlled allowlist decides whether to dispatch to this address.
        val uri = runCatching { java.net.URI(endpoint.url) }.getOrNull()
        if (endpoint.url.toByteArray().size > 1000 || uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            fail(instance, "Distributor returned an unsupported endpoint."); return@withLock
        }
        state.update(row.instance) { it.copy(endpoint = endpoint.url, p256dh = keys.pubKey, auth = keys.auth, ready = false, message = "Unlock to register this device with NStack.") }
        app.activeModel?.takeUnless { it.environment.isSample }?.let { renewOne(it, row.instance) }
    }

    /** Renewal/revocation needs signed writes. Locked apps retain the pending change until the next unlock. */
    suspend fun reconcile(model: AppModel) = mutex.withLock {
        if (model.environment.isSample || model.identityState !is IdentityState.Unlocked) return@withLock
        for (row in state.all()) {
            if (!row.enabled) { revoke(model, row); continue }
            val lease = runCatching { Instant.parse(row.leaseExpiresAt).toEpochMilli() }.getOrDefault(0)
            if (!row.ready || !permissionGranted() || lease < System.currentTimeMillis() + 86400_000) renewOne(model, row.instance)
            if (row.enabled && UnifiedPush.getAckDistributor(app) != null) {
                runCatching { UnifiedPush.register(app, instance = row.instance, messageForDistributor = "Ambulando workspace", vapid = row.vapid) }
            }
        }
    }

    private suspend fun renewOne(model: AppModel, instance: String) {
        val row = state.all().firstOrNull { it.instance == instance && it.enabled } ?: return
        val c = model.catalog.reader.connections().firstOrNull { it.id == row.connectionId && it.signerNpub == row.signerNpub && it.origin == row.origin } ?: return
        if (model.identityState !is IdentityState.Unlocked || model.identityState?.npub != row.signerNpub) return
        val w = model.catalog.reader.workspaces(c.id).firstOrNull { it.id == row.workspaceId && it.appNpub == row.appNpub && !it.accessRemoved } ?: return
        if (row.endpoint == null || row.p256dh == null || row.auth == null) return
        try {
            val r = model.towerAPI(c).registerPush(w, instance, row.endpoint, row.p256dh, row.auth, permissionGranted(), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            state.update(instance) { if (it.enabled) it.copy(ready = r.ready, leaseExpiresAt = r.leaseExpiresAt, message = if (r.ready) "Ready for push" else readinessMessage(r.reason)) else it }
        } catch (_: Exception) { fail(instance, "Registration failed. Check server setup, unlock and retry.") }
    }

    suspend fun disable(model: AppModel, connectionId: String, workspaceId: String? = null) {
        val rows = state.all().filter { it.connectionId == connectionId && (workspaceId == null || it.workspaceId == workspaceId) }
        // Reject delivery before waiting for an in-flight network read/registration.
        for (row in rows) {
            state.update(row.instance) { it.copy(enabled = false, ready = false, endpoint = null, p256dh = null, auth = null, message = "Off; server revocation pending") }
            UnifiedPush.unregister(app, row.instance)
            androidx.work.WorkManager.getInstance(app).cancelAllWorkByTag("ambulando.push.${row.instance}")
            manager.activeNotifications.filter { it.tag?.startsWith(row.instance + ":") == true }.forEach { manager.cancel(it.tag, it.id) }
        }
        mutex.withLock { for (row in rows) revoke(model, row.copy(enabled = false)) }
    }
    private suspend fun revoke(model: AppModel, row: PushRegistration) {
        if (model.identityState !is IdentityState.Unlocked || model.identityState?.npub != row.signerNpub) return
        val c = model.catalog.reader.connections().firstOrNull { it.id == row.connectionId && it.signerNpub == row.signerNpub } ?: return
        val w = model.catalog.reader.workspaces(c.id).firstOrNull { it.id == row.workspaceId && it.appNpub == row.appNpub } ?: return
        try { model.towerAPI(c).revokePush(w, row.instance); state.remove(row.instance) }
        catch (_: Exception) { /* Local opt-out already applies. Expiry bounds server registration if offline. */ }
    }
    fun fail(instance: String, message: String) { state.update(instance) { it.copy(ready = false, message = message) } }
    fun unregistered(instance: String) { state.update(instance) { it.copy(ready = false, endpoint = null, p256dh = null, auth = null, message = "Distributor unregistered. Unlock and retry.") } }

    suspend fun message(instance: String, bytes: ByteArray) = mutex.withLock {
        val hint = PushHint.parse(bytes) ?: return@withLock
        val row = state.all().firstOrNull { it.instance == instance && it.enabled } ?: return@withLock
        if ((row.seen[hint.notificationId] ?: 0) >= hint.changeSeq || !permissionGranted()) return@withLock
        val model = app.activeModel?.takeUnless { it.environment.isSample }
        val environment = AppEnvironment.Production(app)
        val catalog = model?.catalog ?: LocalStore(environment.storeFile.parentFile!!)
        val identity = model?.identity ?: environment.identityService()
        val item = try {
            val c = catalog.reader.connections().firstOrNull { it.id == row.connectionId && it.signerNpub == row.signerNpub && hint.matches(it, row.workspaceId) } ?: return@withLock
            val w = catalog.reader.workspaces(c.id).firstOrNull { it.id == row.workspaceId && it.appNpub == row.appNpub && !it.accessRemoved } ?: return@withLock
            // Cold push never constructs AppModel or calls resumeSession: it can only load a GET-only credential.
            if (model?.identityState !is IdentityState.Unlocked && !identity.loadBackgroundRead()) return@withLock
            identity.requireReader(row.signerNpub)
            val api = model?.towerAPI(c) ?: TowerAPI(TowerClient(TowerOrigin(c.origin), OkHttpTransport(), identity))
            (api.notificationItem(w.id, w.appNpub, hint.notificationId) as? NotificationChange.Upsert)?.item
        } finally { if (model == null) catalog.close() }
        if (!PushValidation.presentable(hint, item)) return@withLock
        val title = genericTitle(item!!.sourceApp, item.category)
        model?.entries?.firstOrNull { it.connection.id == row.connectionId && it.workspace.id == row.workspaceId }?.runtime?.let { rt ->
            rt.sync.refreshNotifications(rt.connection, rt.workspace, force = true)
        }
        if (model?.foreground == true && model.identityState is IdentityState.Unlocked && model.connection?.id == row.connectionId && model.workspace?.id == row.workspaceId) { remember(row, hint); return@withLock }
        val intent = Intent(app, MainActivity::class.java).setAction(TAP).putExtra(INSTANCE, instance)
            .putExtra(ITEM, hint.notificationId).putExtra(SEQUENCE, hint.changeSeq)
            .setData(android.net.Uri.parse("ambulando-push://local/$instance/${hint.notificationId}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText("Open Ambulando to view activity.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(pending).build()
        if (permissionGranted() && state.all().any { it.instance == instance && it.enabled }) { manager.notify(instance + ":" + hint.notificationId, 1, notification); remember(row, hint) }
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
            "endpoint_not_allowed" -> "Ask the server operator to allow your distributor origin."
            else -> "Server push is not ready. Check the setup guide."
        }
    }
}
