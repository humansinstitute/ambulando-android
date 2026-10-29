package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.background.BackgroundScheduler
import studio.otherstuff.ambulando.app.ui.theme.AppearancePreference
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.loadNotificationPreferences
import studio.otherstuff.ambulando.core.sync.NotificationPreferenceResult
import studio.otherstuff.ambulando.core.sync.setNotificationInbox

@Composable internal fun NativeSettings(model: AppModel, done: () -> Unit) {
    val context = LocalContext.current
    val syncStatus by model.engine.status.collectAsState()
    val failing by model.transport.failing.collectAsState()
    var background by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(true) }
    LaunchedEffect(model) { background = model.identity.backgroundReadEnabled(); profiles = studio.otherstuff.ambulando.core.profiles.ProfileSync.isEnabled(model.store) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = done) { Text("Done") }
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(model.identityState?.npub ?: "No identity")
        Text("${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}")
        Text(model.connection?.origin ?: "No connection")
        Text("Status: " + studio.otherstuff.ambulando.core.sync.connectionStatus(failing, syncStatus))
        TextButton(onClick = { model.retryConnection() }) { Text("Retry") }
        TextButton(onClick = { model.exportDiagnostics() }) { Text("Export diagnostics") }
        TextButton(onClick = { model.clearDiagnostics() }) { Text("Clear diagnostics") }
        Text("Diagnostics stay on this device until you choose to share. The newest 300 entries are kept in private app storage until replaced or cleared. No keys, tokens, message text or user identifiers are included.", style = MaterialTheme.typography.bodySmall)
        NativeNotificationSettings(model)
        Row { Text("Show Agents", Modifier.weight(1f)); Switch(model.showAgents, { model.showAgents = it; model.act { model.store.writer.setState("settings.agents", it.toString()) } }) }
        Row { Text("Show Context", Modifier.weight(1f)); Switch(model.showContext, { model.showContext = it; model.act { model.store.writer.setState("settings.context", it.toString()) } }) }
        AppearancePreference.entries.forEach { value -> TextButton(onClick = { model.appearance = value; model.act { model.store.writer.setState("settings.appearance", value.name) } }) { Text(value.name) } }
        Row {
            Text("Background reads", Modifier.weight(1f))
            Switch(background, { on -> model.act {
                val c = model.connection ?: return@act
                if (on) {
                    model.identity.enableBackgroundRead(studio.otherstuff.ambulando.core.transport.TowerOrigin(c.origin), model.workspaces.filter { !it.accessRemoved }.map { it.id })
                    if (!model.environment.isSample) BackgroundScheduler.scheduleRefresh(context)
                } else { model.identity.disableBackgroundRead(); BackgroundScheduler.cancelRefresh(context) }
                background = on
            } })
        }
        Text("Optional background reads use a GET-only credential. Android decides when refresh can run. Lock now revokes it.", style = MaterialTheme.typography.bodySmall)
        Row { Text("Public Nostr profiles", Modifier.weight(1f)); Switch(profiles, { on -> model.act { model.profileSync.setEnabled(on, model.connection); profiles = on; if (on && !model.environment.isSample) model.connection?.let { c -> model.workspace?.let { model.profileSync.refresh(c, it.id, true) } } } }) }
        TextButton(onClick = { remove = true }) { Text("Remove identity from this device") }
        TextButton(onClick = { model.lock(); done() }) { Text("Lock now") }
        TextButton(onClick = { confirm = true }) { Text("Disconnect and remove downloaded history") }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove identity?") }, text = { Text("This deletes the local identity, history, drafts and unsent work. Keep your own backup before proceeding.") }, confirmButton = { TextButton(onClick = { model.removeIdentity(); done() }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Disconnect this Tower?") }, text = { Text("Downloaded history, drafts and unsent work for this connection will be removed from this device.") }, confirmButton = {
        TextButton(onClick = { model.disconnect(); confirm = false; done() }) { Text("Disconnect") }
    }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable private fun NativeNotificationSettings(model: AppModel) {
    val c = model.connection ?: return
    val w = model.workspace ?: return
    var state by remember(c.id, w.id) { mutableStateOf<studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState>(studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val sync by remember(c.id, w.id) { model.store.liveQuery({ it.table == StoreTable.NOTIFICATIONS }) { model.store.reader.notificationSync(c.id, w.id) } }.collectAsState(null)
    // A new rules revision on the inbox copy means the person's rules changed (this device or another): read them again.
    LaunchedEffect(c.id, w.id, attempt, sync?.state, sync?.rulesRevision) {
        val phase = sync?.state
        if (phase == NotificationSyncState.Phase.UNSUPPORTED) { state = studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.NotSupported; return@LaunchedEffect }
        if (phase == NotificationSyncState.Phase.ACCESS_LOST || model.identityState !is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked) { state = studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.Failed("Unlock to load these settings."); return@LaunchedEffect }
        state = try {
            studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.Ready(model.sync.loadNotificationPreferences(c, w), sync?.inboxStartedAt)
        } catch (e: studio.otherstuff.ambulando.core.transport.TowerError.Http) {
            if (e.status == 404 || e.status == 501 || e.status == 405) studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.NotSupported
            else studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.Failed(studio.otherstuff.ambulando.core.transport.ConnectionFailure.userMessage(e) ?: "Unexpected error")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.Failed(studio.otherstuff.ambulando.core.transport.ConnectionFailure.userMessage(e) ?: "Unexpected error")
        }
    }
    Text("Notifications", style = MaterialTheme.typography.titleMedium)
    studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsContent(state, sync?.state, object : studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsActions {
        override fun setInbox(category: studio.otherstuff.ambulando.core.transport.NotificationCategoryInfo, on: Boolean) {
            val ready = state as? studio.otherstuff.ambulando.app.ui.notifications.NotificationSettingsState.Ready ?: return
            state = ready.copy(saving = true)
            model.act {
                state = try {
                    when (val result = model.sync.setNotificationInbox(c, w, category, on, ready.preferences)) {
                        is NotificationPreferenceResult.Saved -> {
                            // The inbox is filtered by these rules: refresh it now rather than at the next hint.
                            model.refreshNotifications()
                            ready.copy(preferences = result.preferences, saving = false)
                        }
                        is NotificationPreferenceResult.Conflict -> {
                            model.showNotificationMessage("These settings were changed on another device. The latest are shown; choose again to apply your change.")
                            ready.copy(preferences = result.preferences, saving = false)
                        }
                        is NotificationPreferenceResult.Rejected -> {
                            model.showNotificationMessage("Tower did not accept that change. The current settings are shown.")
                            ready.copy(preferences = result.preferences, saving = false)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    model.showNotificationMessage("Could not save that setting: " + studio.otherstuff.ambulando.core.transport.ConnectionFailure.userMessage(e)); ready.copy(saving = false)
                }
            }
        }
        override fun reload() { attempt += 1 }
    })
}
