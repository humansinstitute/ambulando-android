package studio.otherstuff.ambulando.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.nav.PersonAvatar
import studio.otherstuff.ambulando.app.ui.onboarding.shortNpub
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.app.ui.theme.AppearancePreference
import studio.otherstuff.ambulando.core.profiles.PersonDisplay

/** Everything Settings shows, read by the app model. */
@Immutable
data class SettingsState(
    val me: PersonDisplay?,
    val publicProfileName: String?,
    val npub: String?,
    val unlocked: Boolean,
    val towerOrigin: String?,
    val workspaceCount: Int,
    val running: Int,
    val queued: Int,
    val errors: Map<String, String>,
    val publicProfilesEnabled: Boolean,
    val savedProfiles: Int,
    val backgroundRefreshEnabled: Boolean,
    val canReadWhileLocked: Boolean,
    val backgroundRevocationIncomplete: Boolean,
    val liveLabel: String,
    /** "Updated · 10:42 · 3 changes" style summary of the last background refresh, if any ran. */
    val lastBackgroundRefresh: String?,
    val showAgents: Boolean,
    val showContext: Boolean,
    val appearance: AppearancePreference,
    val versionName: String,
    val versionCode: String,
    val sample: Boolean,
)

interface SettingsActions {
    suspend fun lockNow()
    suspend fun removeIdentity()
    fun refreshNow()
    suspend fun unsentCount(): Int
    suspend fun disconnect()
    suspend fun setPublicProfiles(on: Boolean)
    fun refreshProfiles()
    suspend fun setBackgroundRefresh(on: Boolean)
    fun setShowAgents(on: Boolean)
    fun setShowContext(on: Boolean)
    fun setAppearance(value: AppearancePreference)
    fun done()
}

@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions) {
    val amb = Amb.colors
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().background(amb.canvas).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(amb.background).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::done, modifier = Modifier.testTag("settings.done")) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (state.me != null && state.npub != null) Section("Profile") {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonAvatar(state.me, 44.dp)
                    Column {
                        Text(state.me.name, fontWeight = FontWeight.SemiBold)
                        Text(shortNpub(state.npub), style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                        state.publicProfileName?.let { Text("Public profile: $it", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground) }
                    }
                }
            }
            Section("Identity") {
                Labeled("Public key", state.npub?.let(::shortNpub) ?: "None")
                if (state.unlocked) Action("Lock now", tag = "settings.lock") { scope.launch { actions.lockNow() } }
                if (state.npub != null) Action("Remove identity from this device", destructive = true, tag = "settings.remove") { confirmRemove = true }
            }
            state.towerOrigin?.let { origin ->
                Section("Connection") {
                    Labeled("Tower", origin)
                    Labeled("Transport", "HTTPS + NIP-98")
                    Labeled("Workspaces", "${state.workspaceCount}")
                    Action("Refresh now", enabled = state.npub != null, tag = "settings.refresh") { actions.refreshNow() }
                    Action("Disconnect and clear local data", destructive = true, tag = "settings.disconnect") {
                        scope.launch { confirmDisconnect = actions.unsentCount() }
                    }
                }
            }
            Section("Sync") {
                Labeled("Running", "${state.running}")
                Labeled("Queued", "${state.queued}")
                state.errors.entries.sortedBy { it.key }.forEach { (key, message) ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(key.substringBefore(':'), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        Text(message, style = MaterialTheme.typography.labelSmall, color = amb.destructive)
                    }
                }
            }
            Section("Nostr profiles", footer = "Names and pictures from people's public Nostr profiles are saved on this device and shown offline. " +
                "Profiles are read from relay.primal.net, nos.lol and purplepag.es, as in the web app, and each picture is fetched from the address in " +
                "the profile, which sees this device's network address. Nothing is signed or sent with them. Turning this off stops all of these " +
                "requests; saved names and pictures stay until you disconnect.") {
                Toggle("Public Nostr profiles", state.publicProfilesEnabled, "settings.profiles.toggle") { on -> scope.launch { actions.setPublicProfiles(on) } }
                Action("Refresh Nostr profiles", enabled = state.publicProfilesEnabled && state.towerOrigin != null, tag = "settings.profiles.refresh") { actions.refreshProfiles() }
                Labeled("Saved on this device", "${state.savedProfiles}", "settings.profiles.saved")
            }
            Section("Background", footer = backgroundFooter(state)) {
                Toggle("Refresh while locked", state.backgroundRefreshEnabled, "settings.backgroundRefresh",
                    enabled = state.unlocked || state.backgroundRefreshEnabled) { on -> scope.launch { actions.setBackgroundRefresh(on) } }
                Labeled("Live updates", state.liveLabel, "settings.liveStatus")
                state.lastBackgroundRefresh?.let { Labeled("Last background refresh", it, "settings.lastBackgroundRefresh") }
                if (state.backgroundRevocationIncomplete) Text(
                    "The stored key could not be deleted yet. It is blocked and will not be used; Ambulando retries the deletion.",
                    style = MaterialTheme.typography.labelSmall, color = amb.destructive, modifier = Modifier.padding(12.dp),
                )
            }
            Section("Sections", footer = "Agents and Context are optional sections, off unless you turn them on, as in the web app. They appear in the bar at the bottom.") {
                Toggle("Agents", state.showAgents, "settings.sections.agents") { actions.setShowAgents(it) }
                Toggle("Context", state.showContext, "settings.sections.context") { actions.setShowContext(it) }
            }
            Section("Appearance") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                    AppearancePreference.entries.forEachIndexed { index, value ->
                        SegmentedButton(selected = state.appearance == value, onClick = { actions.setAppearance(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, AppearancePreference.entries.size)) {
                            Text(value.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
            Section("About") {
                Labeled("Version", state.versionName, "settings.version")
                Labeled("Build", state.versionCode, "settings.build")
                Labeled("Platform", "Android", "settings.platform")
                if (state.sample) Labeled("Data", "Sample data on this device (nothing is sent to a Tower)", "settings.sample")
                Text("Push notifications and FIPS transport are not part of this Android build. Android alerts need a supported provider and device-registration contract; this app uses HTTPS only.",
                    style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove identity?") },
        text = { Text("This deletes the encrypted identity from this app only. Keep your own backup of your nsec; Ambulando cannot recover it.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; scope.launch { actions.removeIdentity() } }) { Text("Remove identity", color = amb.destructive) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
    confirmDisconnect?.let { unsent ->
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            title = { Text("Disconnect?") },
            text = {
                Text((if (unsent > 0) "$unsent unsent ${if (unsent == 1) "message" else "messages"} will be discarded. " else "") +
                    "Removes saved workspaces, channels, messages and drafts for this Tower from this device. Nothing on the server changes.")
            },
            confirmButton = { TextButton(onClick = { confirmDisconnect = null; scope.launch { actions.disconnect(); actions.done() } }) { Text("Disconnect", color = amb.destructive) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text("Cancel") } },
        )
    }
}

private fun backgroundFooter(state: SettingsState): String {
    val host = state.towerOrigin?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "your Tower"
    var text = "While Ambulando is open, changes arrive live. In the background, Android decides if and when the app may refresh " +
        "(opportunistically, at most every 15 minutes, never on demand). While the identity is still unlocked in memory, that refresh also sends waiting " +
        "messages. Once Android has ended the app, nothing can be signed without your PIN unless Refresh while locked is on.\n\n" +
        "Refresh while locked keeps a copy of your full signing key on this device, outside the PIN-protected vault, sealed by an Android Keystore " +
        "key that stays on this device and opens after the first unlock following a restart. It is not a separate read-only key: this app only " +
        "allows it to sign requests that fetch updates from $host for your workspaces, and still asks for your PIN to send anything. Lock now " +
        "and turning this off remove it."
    if (state.backgroundRefreshEnabled && !state.canReadWhileLocked) text += " Paused until you unlock."
    return text
}

@Composable
private fun Section(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val amb = Amb.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, modifier = Modifier.padding(start = 12.dp))
        Column(Modifier.fillMaxWidth().background(amb.card, RoundedCornerShape(Amb.radiusPanel)), content = content)
        footer?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, modifier = Modifier.padding(horizontal = 12.dp)) }
    }
}

@Composable
private fun Labeled(label: String, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Amb.colors.mutedForeground, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Action(label: String, destructive: Boolean = false, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    val amb = Amb.colors
    Text(
        label,
        color = when { !enabled -> amb.mutedForeground; destructive -> amb.destructive; else -> amb.primary },
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, onClick = onClick).testTag(tag).padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = if (enabled) Color.Unspecified else Amb.colors.mutedForeground)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}
