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
import studio.otherstuff.ambulando.core.store.directoryMembers
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.sync.SyncPriority

/** Where Manage Workspace opens and, when named, which workspace it must be for (it waits until that one is in front). */
internal data class ManagementTarget(val route: AdminRoute, val boundaryId: String? = null)

/**
 * Manage Workspace for the workspace in front. The administration session is bound to that exact boundary (Tower origin,
 * signer, application and workspace): it is closed when this screen leaves, and a screen opened for one workspace never
 * reports into another.
 */
@Composable internal fun WorkspaceManagement(model: AppModel, target: ManagementTarget = ManagementTarget(AdminRoute.Home), done: () -> Unit) {
    val initial = target.route
    // Opened for a named workspace that is still being brought to the front: nothing is bound until it is the one in front.
    if (target.boundaryId != null && model.activeBoundaryId != target.boundaryId) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("admin.opening")) {
            TextButton(onClick = done, modifier = Modifier.testTag("admin.back")) { Text("Back") }
            Text("Opening the workspace…")
        }
        return
    }
    val runtime = model.active ?: return
    val w = model.workspace ?: return
    val boundaryId = runtime.boundary.id
    val scope = rememberCoroutineScope()
    val session = remember(boundaryId) { AdministrationSession(runtime.administrator { model.activeBoundaryId == boundaryId }, w.label, { model.activeBoundaryId }, scope) }
    DisposableEffect(session) { onDispose { session.close() } }
    // Everything below (navigation stack, forms, remembered managers) belongs to this boundary and is rebuilt for another one.
    androidx.compose.runtime.key(boundaryId) {
        AdministrationHost(
            session = session, workspace = w, scopes = model.scopes, channels = model.channels, done = done, initial = initial,
            scopeChannelContent = { back, openChannelAccess, openScopeAccess -> ScopeChannelManagement(model, back, openChannelAccess, openScopeAccess) },
        )
    }
}

/** Explicit online scope and channel management; Tower decides the actor's authority. */
@Composable private fun ScopeChannelManagement(model: AppModel, done: () -> Unit, openChannelAccess: (String) -> Unit, openScopeAccess: (String) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = done)
    val c = model.connection ?: return; val w = model.workspace ?: return
    val manager = remember(model, model.activeBoundaryId) { ScopeChannelManager(model.store, model.identity, model.workspaceTransport) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var participant by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = model.scopes.firstOrNull { it.id == model.context.scopeId }
    val channel = model.channels.firstOrNull { it.id == model.context.channelId }
    var working by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    if (showSetup) { ScopeSetupPage(model) { showSetup = false }; return }
    // Explicit audience for a new channel: nothing is chosen for the person, and an empty selection leaves the creator alone.
    val shares = remember(w.id) { mutableStateMapOf<String, String>() }
    val people by produceState(emptyList<studio.otherstuff.ambulando.core.transport.MemberRecord>(), model.store, w.id) { value = model.store.reader.directoryMembers(w.id) }
    val groups by produceState(emptyList<studio.otherstuff.ambulando.core.management.AdministrationRow>(), model.store, c.id, w.id) {
        value = studio.otherstuff.ambulando.core.management.AdministrationRow.decode(
            model.store.reader.state(studio.otherstuff.ambulando.core.management.AdministrationKeys.list(c.id, w.id, studio.otherstuff.ambulando.core.management.AdministrationList.GROUPS)),
        )
    }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("admin.scopesPage"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = done, modifier = Modifier.testTag("admin.back")) { Text("Back") }
        Text("Scopes & Channels", style = MaterialTheme.typography.titleLarge)
        Text(w.label, style = MaterialTheme.typography.labelLarge)
        Text(scope?.name ?: "Home")
        channel?.let { Text("#${it.name}") }
        OutlinedTextField(name, { name = it }, label = { Text("Name") })
        OutlinedTextField(description, { description = it }, label = { Text("Description") })
        if (mayCreateScope) TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.createScope(c, w, name, description); name = "" } }) { Text("Create scope") }
        if (mayCreateScope) TextButton(enabled = !working, onClick = { showSetup = true }, modifier = Modifier.testTag("admin.scopeSetup.open")) { Text("New scope with starting channels") }
        if (membershipKnown && !mayCreateScope && !mayManageScope && !mayCreateChannel && !mayManageChannel) Text("Your role does not allow management changes here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("management.noAuthority"))
        if (scope != null && !scope.accessRemoved) {
            if (studio.otherstuff.ambulando.core.context.SidebarNavigation.isDirectMessageScope(studio.otherstuff.ambulando.core.context.NavScope(scope.id, scope.name, scope.kind))) {
                OutlinedTextField(participant, { participant = it.trim() }, label = { Text("Participant npub") })
                TextButton(enabled = !working && studio.otherstuff.ambulando.core.nostr.NostrKeys.isValidNpub(participant), onClick = { run { manager.createDirectMessage(c, w, scope.id, participant) } }) { Text("Create direct message") }
            }
            if (mayManageScope) TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.updateScope(c, w, scope.id, name, description) } }) { Text("Update scope") }
            if (mayCreateChannel) {
                Text("Share with (optional)", style = MaterialTheme.typography.labelLarge)
                Text("Only the people and groups you tick receive access. Scope roles and group ownership never add anyone.", style = MaterialTheme.typography.bodySmall)
                val self = membership?.actorId
                val options = people.filter { it.actorId != self }.map { Triple("actor:" + it.actorId.lowercase(), it.label, "") } +
                    groups.map { Triple("group:" + it.id.lowercase(), it.title + " (group)", "") }
                for ((key, label, _) in options) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = key in shares, onCheckedChange = { on -> if (on) shares[key] = "view" else shares.remove(key) }, modifier = Modifier.testTag("management.share.$key"))
                    Text(label, modifier = Modifier.weight(1f))
                    shares[key]?.let { level ->
                        TextButton(onClick = { shares[key] = when (level) { "view" -> "contribute"; "contribute" -> "manage"; else -> "view" } }, modifier = Modifier.testTag("management.shareLevel.$key")) { Text(level.replaceFirstChar { it.uppercase() }) }
                    }
                }
                TextButton(enabled = !working && name.isNotBlank(), onClick = {
                    val grants = shares.map { (key, level) -> studio.otherstuff.ambulando.core.management.InitialChannelGrant(key.substringBefore(':'), key.substringAfter(':'), level) }
                    run { manager.createChannel(c, w, scope.id, name, description, prompt, grants); name = ""; shares.clear() }
                }, modifier = Modifier.testTag("management.createChannel")) { Text("Create channel in ${scope.name}") }
            }
            if (mayManageScope) TextButton(enabled = !working, onClick = { confirm = { run { manager.archiveScope(c, w, scope.id) } } }) { Text("Archive scope") }
            // Reordering is personal (each member orders their own sidebar), so it needs no management authority.
            TextButton(enabled = !working, onClick = { run { manager.saveScopeOrder(c, w, listOf(scope.id) + model.scopes.filter { it.id != scope.id }.map { it.id }) } }) { Text("Move scope to top") }
            // Scope roles are decided by the exact scope's own authority, which the access page reads fresh.
            TextButton(onClick = { openScopeAccess(scope.id) }, modifier = Modifier.testTag("management.scopeAccess")) { Text("Scope access") }
        }
        if (channel != null && !channel.accessRemoved) {
            if (mayManageChannel) {
                TextButton(enabled = !working && name.isNotBlank(), onClick = { run { manager.renameChannel(c, w, channel.id, name) } }) { Text("Rename channel") }
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Channel context prompt") })
                TextButton(enabled = !working, onClick = { run { manager.saveContextPrompt(c, w, channel, prompt) } }) { Text("Save context prompt") }
                TextButton(enabled = !working, onClick = { run { manager.moveChannel(c, w, channel, 0) } }) { Text("Move channel to top") }
                TextButton(enabled = !working, onClick = { confirm = { run { manager.archiveChannel(c, w, channel) } } }) { Text("Archive channel") }
            }
            // Grant management is its own authority (an exact grant on this channel), not channel.manage.
            TextButton(onClick = { openChannelAccess(channel.id) }, modifier = Modifier.testTag("management.channelAccess")) { Text("Channel access") }
        }
        model.notice?.let { Text(it) }
    }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Archive this item?") }, text = { Text("Tower will update visibility. Previously downloaded history stays on this device.") }, confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Archive") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }) }
}
