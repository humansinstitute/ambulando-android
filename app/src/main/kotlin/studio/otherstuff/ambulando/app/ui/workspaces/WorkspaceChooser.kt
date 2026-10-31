package studio.otherstuff.ambulando.app.ui.workspaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.AppModel
import studio.otherstuff.ambulando.app.WorkspaceEntry
import studio.otherstuff.ambulando.app.ui.onboarding.ConnectScreen
import studio.otherstuff.ambulando.app.ui.onboarding.DescriptorSummaryModel
import studio.otherstuff.ambulando.app.ui.shell.WorkspaceTint
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.workspaces.WorkspaceColour

/**
 * Choose which workspaces appear together and keep syncing. Checked workspaces stay in sync; unchecked ones are paused (no
 * network activity) while their saved content, drafts and unsent work stay on this device. Grouped by Tower, with each
 * workspace's colour and an entry for adding another Tower with the same unlocked identity.
 */
@Composable
fun WorkspaceChooser(model: AppModel, done: () -> Unit) {
    val amb = Amb.colors
    var adding by remember { mutableStateOf(false) }
    if (adding) { AddTower(model) { adding = false }; return }
    val towers = model.entries.map { it.connection.origin }.distinct().sorted()
    Column(Modifier.fillMaxSize().background(amb.background).verticalScroll(rememberScrollState()).padding(16.dp).testTag("workspace.selection"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = done, modifier = Modifier.testTag("workspace.selection.done")) { Text("Done") }
        Text("Choose workspaces", style = MaterialTheme.typography.titleLarge)
        Text(
            "Checked workspaces appear together and keep syncing. Uncheck a workspace to pause its network activity; its saved content and drafts stay on this device.",
            style = MaterialTheme.typography.bodyMedium, color = amb.mutedForeground,
        )
        if (towers.isEmpty()) Text("No workspaces have been listed yet.", color = amb.mutedForeground)
        for (tower in towers) {
            Text(tower.removePrefix("https://"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = amb.mutedForeground,
                modifier = Modifier.padding(top = 8.dp).testTag("workspace.tower.$tower"))
            for (entry in model.entries.filter { it.connection.origin == tower }) EntryRow(model, entry)
        }
        HorizontalDivider(color = amb.border)
        OutlinedButton(onClick = { adding = true }, enabled = model.identityState is IdentityState.Unlocked, modifier = Modifier.fillMaxWidth().testTag("workspace.addTower")) {
            Text("Add Tower connection")
        }
        Text("A second Tower uses the same identity. Its workspaces start paused; check the ones you want.", style = MaterialTheme.typography.bodySmall, color = amb.mutedForeground)
    }
}

@Composable
private fun EntryRow(model: AppModel, entry: WorkspaceEntry) {
    val amb = Amb.colors
    val dark = isSystemInDarkTheme()
    val label = entry.workspace.label
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { model.setWorkspaceEnabled(entry, !entry.enabled) }
                .semantics { contentDescription = label; stateDescription = if (entry.enabled) "Enabled" else "Paused"; selected = entry.enabled }
                .testTag("workspace.enabled.$label"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(entry.enabled, onCheckedChange = null)
            Box(Modifier.size(10.dp).background(WorkspaceTint.navigation(entry.colour, dark), CircleShape))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = amb.foreground)
                if (entry.workspace.accessRemoved) Text("No longer available to you", style = MaterialTheme.typography.labelSmall, color = amb.destructive)
                else if (!entry.enabled) Text("Paused", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground)
            }
        }
        Row(Modifier.padding(start = 34.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for (colour in WorkspaceColour.entries) {
                val chosen = entry.colour == colour
                Box(
                    Modifier.size(28.dp).background(WorkspaceTint.swatch(colour).copy(alpha = if (chosen) 1f else 0.35f), CircleShape)
                        .clickable { model.setWorkspaceColour(entry, colour) }
                        .semantics { contentDescription = "${colour.name.lowercase().replaceFirstChar { it.uppercase() }} colour for $label"; selected = chosen }
                        .testTag("workspace.colour.$label.${colour.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun AddTower(model: AppModel, done: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ConnectScreen(model.identityState?.npub, model.notice, { origin -> runCatching {
            val d = model.descriptor(origin)
            DescriptorSummaryModel(d.tower?.availability?.status ?: "Available", d.tower?.protocols ?: emptyList(), d.services.map { it.kind }, d.advertisesFIPS)
        } }) { origin -> model.connect(origin); done() }
        TextButton(onClick = done, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("workspace.addTower.cancel")) { Text("Cancel") }
    }
}

/** Shown when no workspace is enabled (or the saved one is still opening). Empty selection is intentional, never an automatic default. */
@Composable
fun WorkspaceSelectionEmptyState(model: AppModel) {
    val amb = Amb.colors
    var choosing by remember { mutableStateOf(false) }
    if (choosing) { WorkspaceChooser(model) { choosing = false }; return }
    Column(Modifier.fillMaxSize().background(amb.background).padding(24.dp).testTag("workspace.empty"), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (model.entries.isEmpty()) {
            Text("Finding your workspaces…", style = MaterialTheme.typography.titleMedium)
            Text("Your Tower is being asked which workspaces this identity can use.", color = amb.mutedForeground, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { model.retryConnection() }, modifier = Modifier.testTag("workspace.empty.retry")) { Text("Retry") }
        } else {
            Text("Choose a workspace", style = MaterialTheme.typography.titleMedium)
            Text("All workspaces are paused. Enable one to return to its saved content and catch up.", color = amb.mutedForeground, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { choosing = true }, modifier = Modifier.testTag("workspace.empty.choose")) { Text("Choose workspaces") }
        }
        TextButton(onClick = { choosing = true }, modifier = Modifier.testTag("workspace.empty.towers")) { Text("Towers and workspaces") }
        var disconnecting by remember { mutableStateOf<studio.otherstuff.ambulando.core.store.ConnectionRecord?>(null) }
        for (c in model.connections) TextButton(onClick = { disconnecting = c }, modifier = Modifier.testTag("workspace.empty.disconnect.${c.origin}")) { Text("Disconnect ${c.origin.removePrefix("https://")}") }
        TextButton(onClick = { model.lock() }, modifier = Modifier.testTag("workspace.empty.lock")) { Text("Lock now") }
        disconnecting?.let { c ->
            androidx.compose.material3.AlertDialog(onDismissRequest = { disconnecting = null }, title = { Text("Disconnect this Tower?") },
                text = { Text("Its workspaces, drafts and unsent work are removed from the app. A recovery copy stays on this device, out of reach of the app, until you remove the identity.") },
                confirmButton = { TextButton(onClick = { disconnecting = null; model.act { model.disconnect(c) } }) { Text("Disconnect") } },
                dismissButton = { TextButton(onClick = { disconnecting = null }) { Text("Cancel") } })
        }
    }
}

/** Directly above an input: while several workspaces are enabled, similarly named channels always keep their destination. */
@Composable
fun WorkspaceComposerContext(model: AppModel, entry: WorkspaceEntry? = model.activeEntry) {
    if (model.enabledEntries.size < 2 || entry == null) return
    val amb = Amb.colors
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag("composer.workspace"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(WorkspaceTint.navigation(entry.colour, dark), CircleShape))
        Text("Working in ${entry.workspace.label}", style = MaterialTheme.typography.labelSmall, color = amb.mutedForeground, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
