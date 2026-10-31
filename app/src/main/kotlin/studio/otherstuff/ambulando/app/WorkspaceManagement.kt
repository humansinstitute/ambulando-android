package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.core.management.ScopeChannelManager
import studio.otherstuff.ambulando.core.sync.SyncPriority

/** Explicit online management; Tower decides the actor's authority. */
@Composable internal fun WorkspaceManagement(model: AppModel, done: () -> Unit) {
    val c = model.connection ?: return; val w = model.workspace ?: return
    val manager = remember(model) { ScopeChannelManager(model.store, model.identity, model.workspaceTransport) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var participant by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = model.scopes.firstOrNull { it.id == model.context.scopeId }
    val channel = model.channels.firstOrNull { it.id == model.context.channelId }
    var working by remember { mutableStateOf(false) }
    // What the member may do (`GET /me`). Controls the web would hide are hidden; if it cannot be read, everything is
    // offered and Tower still decides each write.
    var membership by remember(w.id) { mutableStateOf<studio.otherstuff.ambulando.core.management.WorkspaceMembership?>(null) }
    var membershipKnown by remember(w.id) { mutableStateOf(false) }
    LaunchedEffect(w.id) { try { membership = model.engine.perform("manage:me:${w.id}", SyncPriority.USER) { manager.membership(c, w) }; membershipKnown = true } catch (_: Exception) { membershipKnown = false } }
    val permissions = studio.otherstuff.ambulando.core.management.ManagementPermissions
    val mayCreateScope = !membershipKnown || permissions.canCreateScope(membership)
    val mayCreateChannel = scope?.let { !membershipKnown || permissions.canCreateChannel(it, membership) } ?: false
    val mayManageScope = scope?.let { !membershipKnown || permissions.canManage(it, membership) } ?: false
    val mayManageChannel = channel?.let { ch -> !membershipKnown || permissions.canManage(ch, membership, studio.otherstuff.ambulando.core.context.SidebarNavigation.isDirectMessageChannel(
        studio.otherstuff.ambulando.core.context.NavChannel(ch.id, ch.scopeId, ch.name, ch.kind, ch.position, ch.raw))) } ?: false
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
        if (mayCreateScope) TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.createScope(c, w, name, description); name = "" } }) { Text("Create scope") }
        if (membershipKnown && !mayCreateScope && !mayManageScope && !mayCreateChannel && !mayManageChannel) Text("Your role does not allow management changes here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("management.noAuthority"))
        if (scope != null && !scope.accessRemoved) {
            if (studio.otherstuff.ambulando.core.context.SidebarNavigation.isDirectMessageScope(studio.otherstuff.ambulando.core.context.NavScope(scope.id, scope.name, scope.kind))) {
                OutlinedTextField(participant, { participant = it.trim() }, label = { Text("Participant npub") })
                TextButton(enabled = !working && studio.otherstuff.ambulando.core.nostr.NostrKeys.isValidNpub(participant), onClick = { run { manager.createDirectMessage(c, w, scope.id, participant) } }) { Text("Create direct message") }
            }
            if (mayManageScope) TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.updateScope(c, w, scope.id, name, description) } }) { Text("Update scope") }
            if (mayCreateChannel) TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.createChannel(c, w, scope.id, name, description, prompt); name = "" } }) { Text("Create channel in ${scope.name}") }
            if (mayManageScope) TextButton(enabled = !working, onClick = { confirm = { run { manager.archiveScope(c, w, scope.id) } } }) { Text("Archive scope") }
            // Reordering is personal (each member orders their own sidebar), so it needs no management authority.
            TextButton(enabled = !working, onClick = { run { manager.saveScopeOrder(c, w, listOf(scope.id) + model.scopes.filter { it.id != scope.id }.map { it.id }) } }) { Text("Move scope to top") }
        }
        if (channel != null && !channel.accessRemoved && mayManageChannel) {
            TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.renameChannel(c, w, channel.id, name) } }) { Text("Rename channel") }
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Channel context prompt") })
            TextButton(enabled = !working, onClick = { run { manager.saveContextPrompt(c, w, channel, prompt) } }) { Text("Save context prompt") }
            TextButton(enabled = !working, onClick = { run { manager.moveChannel(c, w, channel, 0) } }) { Text("Move channel to top") }
            TextButton(enabled = !working, onClick = { confirm = { run { manager.archiveChannel(c, w, channel) } } }) { Text("Archive channel") }
        }
        var people by remember { mutableStateOf(false) }
        TextButton(onClick = { people = !people }, modifier = Modifier.testTag("management.people")) { Text(if (people) "Hide people and access" else "People and access") }
        if (people) WorkspacePeople(model)
        model.notice?.let { Text(it) }
    }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Archive this item?") }, text = { Text("Tower will update visibility. Previously downloaded history stays on this device.") }, confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Archive") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }) }
}
