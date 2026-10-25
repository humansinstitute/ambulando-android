package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.core.management.ScopeChannelManager
import studio.otherstuff.ambulando.core.sync.SyncPriority

/** Explicit online management; Tower decides the actor's authority. */
@Composable internal fun WorkspaceManagement(model: AppModel, done: () -> Unit) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val manager = remember(model) { ScopeChannelManager(model.store, model.identity, model.transport) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var participant by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = model.scopes.firstOrNull { it.id == model.context.scopeId }
    val channel = model.channels.firstOrNull { it.id == model.context.channelId }
    var working by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        working = true
        model.act { try { model.engine.perform("manage:${w.id}", SyncPriority.USER) { block() } } finally { working = false } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = done) { Text("Done") }
        Text("Workspace management", style = MaterialTheme.typography.titleLarge)
        Text(scope?.name ?: "Home")
        channel?.let { Text("#${it.name}") }
        OutlinedTextField(name, { name = it }, label = { Text("Name") })
        OutlinedTextField(description, { description = it }, label = { Text("Description") })
        TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.createScope(c, w, name, description); name = "" } }) { Text("Create scope") }
        if (scope != null && !scope.accessRemoved) {
            if (studio.otherstuff.ambulando.core.context.SidebarNavigation.isDirectMessageScope(studio.otherstuff.ambulando.core.context.NavScope(scope.id, scope.name, scope.kind))) {
                OutlinedTextField(participant, { participant = it.trim() }, label = { Text("Participant npub") })
                TextButton(enabled = !working && studio.otherstuff.ambulando.core.nostr.NostrKeys.isValidNpub(participant), onClick = { run { manager.createDirectMessage(c, w, scope.id, participant) } }) { Text("Create direct message") }
            }
            TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.updateScope(c, w, scope.id, name, description) } }) { Text("Update scope") }
            TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.createChannel(c, w, scope.id, name, description, prompt); name = "" } }) { Text("Create channel in ${scope.name}") }
            TextButton(enabled = !working, onClick = { confirm = { run { manager.archiveScope(c, w, scope.id) } } }) { Text("Archive scope") }
            TextButton(enabled = !working, onClick = { run { manager.saveScopeOrder(c, w, listOf(scope.id) + model.scopes.filter { it.id != scope.id }.map { it.id }) } }) { Text("Move scope to top") }
        }
        if (channel != null && !channel.accessRemoved) {
            TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.renameChannel(c, w, channel.id, name) } }) { Text("Rename channel") }
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Channel context prompt") })
            TextButton(enabled = !working, onClick = { run { manager.saveContextPrompt(c, w, channel, prompt) } }) { Text("Save context prompt") }
            TextButton(enabled = !working, onClick = { run { manager.moveChannel(c, w, channel, 0) } }) { Text("Move channel to top") }
            TextButton(enabled = !working, onClick = { confirm = { run { manager.archiveChannel(c, w, channel) } } }) { Text("Archive channel") }
        }
        model.notice?.let { Text(it) }
    }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Archive this item?") }, text = { Text("Tower will update visibility. Previously downloaded history stays on this device.") }, confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Archive") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }) }
}
