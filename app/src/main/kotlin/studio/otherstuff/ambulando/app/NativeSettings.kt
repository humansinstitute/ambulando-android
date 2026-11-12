package studio.otherstuff.ambulando.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.background.BackgroundScheduler
import studio.otherstuff.ambulando.app.ui.onboarding.shortNpub
import studio.otherstuff.ambulando.app.ui.theme.AmbType
import studio.otherstuff.ambulando.app.ui.theme.AppearancePreference
import studio.otherstuff.ambulando.app.ui.work.FormDivider
import studio.otherstuff.ambulando.app.ui.work.FormGroup
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.sync.loadNotificationPreferences
import studio.otherstuff.ambulando.core.sync.NotificationPreferenceResult
import studio.otherstuff.ambulando.core.sync.setNotificationInbox

@Composable internal fun NativeSettings(model: AppModel, onManage: (() -> Unit)? = null, done: () -> Unit) {
    val context = LocalContext.current
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    val syncStatus by model.engine.status.collectAsState()
    val failing by model.transport.failing.collectAsState()
    var background by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(true) }
    LaunchedEffect(model) { background = model.identity.backgroundReadEnabled(); profiles = studio.otherstuff.ambulando.core.profiles.ProfileSync.isEnabled(model.catalog) }
    val c = model.connection
    val w = model.workspace
    val people by remember(c?.id, w?.id) { model.store.liveQuery({ it.workspaceId == w?.id || it.table == StoreTable.PROFILES }) { if (c != null && w != null) model.store.reader.people(c.id, w.id) else null } }.collectAsState(null)
    val npub = model.identityState?.npub
    Column(Modifier.fillMaxSize().background(amb.canvas).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(amb.background).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = done, modifier = Modifier.testTag("settings.done")) { Text("Done", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (npub != null) {
                val me = people?.person(npub)
                FormGroup("Profile") {
                    Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (me != null) studio.otherstuff.ambulando.app.ui.nav.PersonAvatar(me, 52.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(me?.name ?: "You", fontSize = AmbType.body, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = amb.foreground)
                            Text(nameSourceLine(me), fontSize = AmbType.caption, color = amb.mutedForeground)
                            Text(shortNpub(npub), fontSize = AmbType.caption, color = amb.mutedForeground, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
            if (onManage != null && w != null && c != null) FormGroup("Workspace") {
                SettingsAction("Manage Workspace", tag = "settings.manage", onClick = onManage)
                FormDivider()
                Text(w.label, fontSize = AmbType.caption, color = amb.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            FormGroup("Identity") {
                SettingsValue("Public key", npub?.let(::shortNpub) ?: "None")
                if (model.identityState is studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked) { FormDivider(); SettingsAction("Lock now", tag = "settings.lock") { model.lock(); done() } }
                if (npub != null) { FormDivider(); SettingsAction("Remove identity from this device", destructive = true, tag = "settings.remove") { remove = true } }
            }
            if (c != null) FormGroup("Connection") {
                SettingsValue("Tower", c.origin)
                FormDivider(); SettingsValue("Transport", "HTTPS + NIP-98")
                FormDivider(); SettingsValue("Workspaces", "${model.workspaces.size}")
                FormDivider(); SettingsValue("Status", studio.otherstuff.ambulando.core.sync.connectionStatus(failing, syncStatus), tag = "settings.status")
                FormDivider(); SettingsAction("Retry", tag = "settings.retry") { model.retryConnection() }
                FormDivider(); SettingsAction("Disconnect and remove downloaded history", destructive = true, tag = "settings.disconnect") { confirm = true }
            }
            FormGroup("Watch recorder") { Column(Modifier.padding(16.dp)) { studio.otherstuff.ambulando.app.garmin.GarminSetup(model) } }
            FormGroup("Diagnostics", "Diagnostics stay on this device until you choose to share. The newest 300 entries are kept in private app storage until replaced or cleared. No keys, tokens, message text or user identifiers are included.") {
                SettingsAction("Export diagnostics", tag = "settings.diagnostics.export") { model.exportDiagnostics() }
                FormDivider(); SettingsAction("Clear diagnostics", tag = "settings.diagnostics.clear") { model.clearDiagnostics() }
            }
            FormGroup("Notifications") { Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                studio.otherstuff.ambulando.app.push.PushSettings(model)
                NativeNotificationSettings(model)
            } }
            FormGroup("Nostr profiles", "Names and pictures from people's public Nostr profiles are saved on this device and shown offline. Profiles are read from relay.primal.net, nos.lol and purplepag.es, as in the web app, and each picture is fetched from the address in the profile, which sees this device's network address. Nothing is signed or sent with them. Turning this off stops all of these requests; saved names and pictures stay until you disconnect.") {
                SettingsToggle("Public Nostr profiles", profiles, "settings.profiles.toggle") { on -> profiles = on; model.setPublicProfiles(on) }
            }
            FormGroup("Background", "Optional background reads use a GET-only credential. Android decides when refresh can run. Lock now revokes it.") {
                SettingsToggle("Refresh while locked", background, "settings.backgroundRefresh") { on -> model.act {
                    val conn = model.connection ?: return@act
                    if (on) {
                        model.identity.enableBackgroundRead(studio.otherstuff.ambulando.core.transport.TowerOrigin(conn.origin), model.workspaces.filter { !it.accessRemoved }.map { it.id })
                        if (!model.environment.isSample) BackgroundScheduler.scheduleRefresh(context)
                    } else { model.identity.disableBackgroundRead(); BackgroundScheduler.cancelRefresh(context) }
                    background = on
                } }
            }
            FormGroup("Sections", "Agents and Context are optional sections, off unless you turn them on, as in the web app. They appear in the bar at the bottom.") {
                SettingsToggle("Agents", model.showAgents, "settings.sections.agents") { model.showAgents = it; model.act { model.catalog.writer.setState("settings.agents", it.toString()) } }
                FormDivider()
                SettingsToggle("Context", model.showContext, "settings.sections.context") { model.showContext = it; model.act { model.catalog.writer.setState("settings.context", it.toString()) } }
            }
            FormGroup("Documents", "Choose how existing documents open. You can still switch between View and Edit inside a document.") {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Open documents in", Modifier.weight(1f), color = amb.foreground)
                    listOf(false to "View", true to "Edit").forEach { (edit, label) ->
                        FilterChip(model.documentOpensInEdit == edit, { model.documentOpensInEdit = edit; model.act { model.catalog.writer.setState("settings.documentOpening", if (edit) "edit" else "view") } },
                            { Text(label) }, Modifier.padding(start = 8.dp).testTag("settings.documentOpening.$label"))
                    }
                }
            }
            FormGroup("Appearance") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                    AppearancePreference.entries.forEachIndexed { index, value ->
                        SegmentedButton(selected = model.appearance == value, onClick = { model.appearance = value; model.act { model.catalog.writer.setState("settings.appearance", value.name) } },
                            shape = SegmentedButtonDefaults.itemShape(index, AppearancePreference.entries.size), modifier = Modifier.testTag("settings.appearance.${value.name}")) {
                            Text(value.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
            FormGroup("About") {
                SettingsValue("Version", BuildConfig.VERSION_NAME, "settings.version")
                FormDivider(); SettingsValue("Build", "${BuildConfig.VERSION_CODE}", "settings.build")
                FormDivider(); SettingsValue("Platform", "Android", "settings.platform")
                FormDivider()
                Text("FIPS transport is not part of this build. This app uses HTTPS only; notifications are read from Tower's inbox.", fontSize = AmbType.caption, color = amb.mutedForeground, modifier = Modifier.padding(16.dp))
            }
        }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove identity?") }, text = { Text("This deletes the local identity, history, drafts and unsent work. Keep your own backup before proceeding.") }, confirmButton = { TextButton(onClick = { model.removeIdentity(); done() }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Disconnect this Tower?") }, text = { Text("This Tower's workspaces, drafts and unsent work are removed from the app and move to a private recovery folder it does not read. Removing the identity deletes that folder too.") }, confirmButton = {
        TextButton(onClick = { model.disconnect(); confirm = false; done() }) { Text("Disconnect") }
    }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

private fun nameSourceLine(me: studio.otherstuff.ambulando.core.profiles.PersonDisplay?): String = when (me?.nameSource) {
    studio.otherstuff.ambulando.core.profiles.PersonDisplay.NameSource.WORKSPACE -> "Your name in this workspace"
    studio.otherstuff.ambulando.core.profiles.PersonDisplay.NameSource.AGENT -> "Agent name in this workspace"
    studio.otherstuff.ambulando.core.profiles.PersonDisplay.NameSource.MESSAGE -> "Name from Tower"
    studio.otherstuff.ambulando.core.profiles.PersonDisplay.NameSource.NOSTR -> "From your public Nostr profile"
    else -> "No name in this workspace or your public profile"
}

@Composable private fun SettingsValue(label: String, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = studio.otherstuff.ambulando.app.ui.theme.Amb.colors.foreground)
        Spacer(Modifier.width(12.dp))
        Text(value, color = studio.otherstuff.ambulando.app.ui.theme.Amb.colors.mutedForeground, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable private fun SettingsAction(label: String, destructive: Boolean = false, tag: String, onClick: () -> Unit) {
    val amb = studio.otherstuff.ambulando.app.ui.theme.Amb.colors
    Text(label, color = if (destructive) amb.destructive else amb.primary, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable private fun SettingsToggle(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = studio.otherstuff.ambulando.app.ui.theme.Amb.colors.foreground)
        Switch(checked, onChange, Modifier.testTag(tag))
    }
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
