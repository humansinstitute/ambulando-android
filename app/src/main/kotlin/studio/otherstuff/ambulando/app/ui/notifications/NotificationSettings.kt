package studio.otherstuff.ambulando.app.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.store.NotificationSyncState
import studio.otherstuff.ambulando.core.sync.NotificationPreferences
import studio.otherstuff.ambulando.core.transport.NotificationCategoryInfo

/** What the Notifications block of Settings shows. */
@Immutable
sealed interface NotificationSettingsState {
    data object Loading : NotificationSettingsState
    /** Tower has no notification routes: say so, show no toggles. */
    data object NotSupported : NotificationSettingsState
    data class Failed(val message: String) : NotificationSettingsState
    data class Ready(val preferences: NotificationPreferences, val inboxStarted: String?, val saving: Boolean = false) : NotificationSettingsState
}

interface NotificationSettingsActions {
    fun setInbox(category: NotificationCategoryInfo, on: Boolean)
    fun reload()
}

/** Inbox toggles per category, and an honest account of what this build and this Tower cannot do (no look-alike controls). */
@Composable
fun NotificationSettingsContent(state: NotificationSettingsState, syncPhase: NotificationSyncState.Phase?, actions: NotificationSettingsActions) {
    val amb = Amb.colors
    Column(Modifier.fillMaxWidth().testTag("settings.notifications"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            NotificationSettingsState.Loading -> Line("Loading notification settings…")
            NotificationSettingsState.NotSupported -> Line("This Tower does not have the notification inbox yet, so there is nothing to set.", "settings.notifications.unsupported")
            is NotificationSettingsState.Failed -> {
                Line("Could not load notification settings: ${state.message}", "settings.notifications.failed")
                androidx.compose.material3.TextButton(onClick = actions::reload) { Text("Retry") }
            }
            is NotificationSettingsState.Ready -> {
                state.inboxStarted?.let { Line("Inbox started ${startedLabel(it)}. Earlier activity is not imported.", "settings.notifications.started") }
                Text("Show in my inbox", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                state.preferences.categories.forEach { category ->
                    val on = state.preferences.inboxOn(category)
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(category.label)
                            Text(if (category.mandatory) "Always on" else category.sourceLabel, style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
                        }
                        Switch(checked = on, onCheckedChange = { actions.setInbox(category, it) }, enabled = !category.mandatory && !state.saving,
                            modifier = Modifier.testTag("settings.notifications.${category.sourceAppId}.${category.category}"))
                    }
                }
            }
        }
        if (syncPhase == NotificationSyncState.Phase.ACCESS_LOST) Line("This workspace no longer lets you read its notifications.", "settings.notifications.accessLost")
        // Deferred features are described, not faked.
        Line("Alerts and phone push: not available in this Android app. A supported Android provider and device-registration contract are required, so notifications appear " +
            "in the inbox while the app is open and when Android runs a background refresh.", "settings.notifications.push")
        Line("Quiet hours and per-device settings: not supported by this Tower yet.", "settings.notifications.deferred")
    }
}

@Composable
private fun Line(text: String, tag: String? = null) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Amb.colors.mutedForeground,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier))
}
