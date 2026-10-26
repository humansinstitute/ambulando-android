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
