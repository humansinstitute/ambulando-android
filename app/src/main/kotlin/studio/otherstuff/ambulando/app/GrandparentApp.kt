package studio.otherstuff.ambulando.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.onboarding.*
import studio.otherstuff.ambulando.app.ui.theme.Amb
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*

/** A closed navigation graph: external intents and record links never choose the family conversation. */
@Composable fun GrandparentApp(model: AppModel) {
    val raw by remember(model) { model.catalog.liveQuery({ it.table == StoreTable.APP_STATE }) { model.catalog.reader.state(FamilyBinding.KEY) } }.collectAsState("loading")
    val binding = remember(raw) { FamilyBinding.decode(raw) }
    var settings by remember { mutableStateOf(false) }
    var change by remember { mutableStateOf(false) }
    BackHandler(settings) { settings = false }
    Column(Modifier.fillMaxSize().background(Amb.colors.background).safeDrawingPadding()) {
        if (model.identityState is IdentityState.Unlocked) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Grandparent Chat", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (model.identityState is IdentityState.Unlocked) TextButton(onClick = { settings = !settings }) { Text(if (settings) "Done" else "Settings") }
        }
        model.notice?.let { notice -> AlertDialog(onDismissRequest = model::clearNotice, title = { Text("Please try again") }, text = { Text(notice) }, confirmButton = { TextButton(onClick = model::clearNotice) { Text("OK") } }) }
        when (val identity = model.identityState) {
            null -> CircularProgressIndicator()
            IdentityState.None -> ImportIdentityScreen(model.notice, model::import)
            IdentityState.Unavailable -> IdentityUnavailableScreen(false) { model.identity.refreshAvailability() }
            is IdentityState.Locked -> LockScreen(identity.npub, model.notice, model::unlock)
            is IdentityState.Unlocked -> when {
                raw == "loading" -> CircularProgressIndicator()
                settings -> Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Your account", style = MaterialTheme.typography.titleLarge)
                    Text("Signed in to your family account")
                    Text(model.connection?.origin ?: "No connection")
                    Text("Changing chats keeps saved messages, drafts and unsent work on this device. Unsent messages for another chat wait until you choose that chat again.")
                    Button(onClick = { change = true }) { Text("Change family chat") }
                    OutlinedButton(onClick = { model.retryConnection() }) { Text("Reconnect") }
                    OutlinedButton(onClick = { settings = false; model.lock() }) { Text("Sign out and lock") }
                    Text("Sign back in with your PIN. Your identity and drafts stay safely on this device.")
                }
                model.connections.isEmpty() -> ConnectScreen(identity.npub, model.notice, { origin -> runCatching {
                    val d = model.descriptor(origin)
                    DescriptorSummaryModel(d.tower?.availability?.status ?: "Available", d.tower?.protocols ?: emptyList(), d.services.map { it.kind }, d.advertisesFIPS)
                } }, model::connect)
                raw != null && binding == null -> Column(Modifier.padding(20.dp)) {
                    Text("The saved family chat could not be read. Your drafts are still on this device.")
                    Button(onClick = { change = true }) { Text("Choose family chat again") }
                }
                binding == null -> FamilySetup(model)
                else -> BoundFamily(model, binding)
            }
        }
    }
    if (change) AlertDialog(onDismissRequest = { change = false }, title = { Text("Change family chat?") }, text = { Text("You will choose a channel and conversation again. Saved messages, drafts and unsent work are kept.") }, confirmButton = {
        TextButton(onClick = { model.act { model.catalog.writer.setState(FamilyBinding.KEY, null) }; change = false; settings = false }) { Text("Choose again") }
    }, dismissButton = { TextButton(onClick = { change = false }) { Text("Keep this chat") } })
}

@Composable private fun FamilySetup(model: AppModel) {
    val c = model.connection
    val w = model.workspace
    var channelId by remember(model.activeBoundaryId) { mutableStateOf<String?>(null) }
    val listing = model.family
    val listMatches = listing.choicesKey == (model.activeBoundaryId to channelId)
    val threads = listing.threads.takeIf { listMatches }
    val error = listing.choicesError.takeIf { listMatches }
    var reload by remember { mutableIntStateOf(0) }
    var proposed by remember(model.activeBoundaryId, channelId) { mutableStateOf<ThreadRecord?>(null) }
    val channel = model.channels.firstOrNull { it.id == channelId && !it.accessRemoved }
    LaunchedEffect(model.activeBoundaryId, channelId, reload) { channel?.let(model.family::list) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose your family chat", style = MaterialTheme.typography.headlineSmall)
        Text("Choose the workspace, channel and existing conversation your family uses in Ambulando.")
        model.entries.filter { !it.workspace.accessRemoved }.forEach { entry ->
            OutlinedButton(onClick = { model.openWorkspace(entry) }, modifier = Modifier.fillMaxWidth()) { Text(entry.workspace.label + if (entry.id == model.activeBoundaryId) " · selected" else "") }
        }
        if (model.entries.isEmpty()) Text("No workspaces are available for this account. Ask your family to invite you, then reconnect.")
        if (w != null && !w.accessRemoved) {
            Text("Channel", style = MaterialTheme.typography.titleLarge)
            val channels = model.channels.filter { !it.accessRemoved && it.raw.bool("archived") != true && it.raw.string("record_state") !in setOf("deleted", "archived") }
            if (channels.isEmpty()) Text("No channels are available. Ask your family for access, then reconnect.")
            channels.forEach { row -> OutlinedButton(onClick = { channelId = row.id }, modifier = Modifier.fillMaxWidth()) { Text(row.name + if (channelId == row.id) " · selected" else "") } }
            if (channel != null) {
                Text("Conversation", style = MaterialTheme.typography.titleLarge)
                if (threads == null && error == null) CircularProgressIndicator()
                threads?.forEach { thread -> Button(onClick = { proposed = thread }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(thread.title?.let(MentionCodec::plain)?.takeIf { it.isNotBlank() } ?: "Conversation ${thread.id.take(8)}") } }
                if (threads?.isEmpty() == true) Text("No active conversation here. Ask your family to start one in Ambulando, then refresh. Grandparent Chat uses that same conversation.")
                error?.let { Text(it) }
                OutlinedButton(onClick = { reload++ }) { Text("Refresh conversations") }
            }
        }
        OutlinedButton(onClick = { model.retryConnection() }) { Text("Reconnect") }
    }
    proposed?.let { chosen -> AlertDialog(onDismissRequest = { proposed = null }, title = { Text("Use this family conversation?") }, text = { Text("${channel?.name}\n${chosen.title?.let(MentionCodec::plain) ?: chosen.id}\n\nThe app will always open this conversation.") }, confirmButton = {
        TextButton(onClick = {
            if (c != null && w != null && channel != null) model.family.bind(channel, chosen)
            proposed = null
        }) { Text("Use this conversation") }
    }, dismissButton = { TextButton(onClick = { proposed = null }) { Text("Back") } }) }
}

@Composable private fun BoundFamily(model: AppModel, binding: FamilyBinding) {
    val entry = model.entries.firstOrNull { it.id == binding.boundaryId && it.connection.id == binding.connectionId }
    LaunchedEffect(binding, entry?.id, model.activeBoundaryId) {
        if (entry != null && model.activeBoundaryId != binding.boundaryId) model.openWorkspace(entry, binding.scopeId, binding.channelId)
    }
    if (entry == null || entry.workspace.accessRemoved || entry.connection.signerNpub != (model.identityState as? IdentityState.Unlocked)?.npub) {
        Text("Your saved family chat is unavailable for this account. Reconnect or deliberately change family chat in Settings.", Modifier.padding(20.dp)); return
    }
    if (model.activeBoundaryId != binding.boundaryId || model.active == null) { CircularProgressIndicator(); return }
    key(binding) { FamilyConversation(model, binding) }
}

@Composable private fun FamilyConversation(model: AppModel, binding: FamilyBinding) {
    val c = model.connection ?: return
    val w = model.workspace ?: return
    if (!binding.owns(c, w)) return
    val thread by remember(binding) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.thread(w.id, binding.threadId) } }.collectAsState(null)
    val failing by model.transport.failing.collectAsState()
    val state = if (failing) "Connection problem · reconnect to retry" else model.family.status
    LaunchedEffect(binding) { model.family.verify(binding) }
    val context = LocalContext.current
    val avatars = remember { studio.otherstuff.ambulando.app.media.AvatarStore(java.io.File(context.cacheDir, "FamilyAvatars"), studio.otherstuff.ambulando.app.media.OkHttpAvatarTransport()) }
    val profilesEnabled by remember(model) { model.catalog.liveQuery({ it.table == StoreTable.APP_STATE }) { studio.otherstuff.ambulando.core.profiles.ProfileSync.isEnabled(model.catalog) } }.collectAsState(false)
    LaunchedEffect(profilesEnabled) { avatars.setEnabled(profilesEnabled) }
    var image by remember { mutableStateOf<Pair<String, String>?>(null) }
    // No record, thread, notification or URL callback can navigate out of this binding.
    val services = remember(binding) { familyServices(avatars) { id, title -> image = id to title } }
    CompositionLocalProvider(LocalAppServices provides services, LocalAvatars provides avatars) {
        Column(Modifier.fillMaxSize()) {
            Text(thread?.title?.let(MentionCodec::plain) ?: "Family conversation", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(state, modifier = Modifier.weight(1f).testTag("family.connection"), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { model.retryConnection() }) { Text("Reconnect") }
            }
            if (thread?.let(binding::allows) == false) Text("This conversation cannot receive messages. Change family chat deliberately in Settings.", Modifier.padding(20.dp))
            else if (thread == null) Text("Checking your saved conversation. Reconnect to load it before sending.", Modifier.padding(20.dp))
            else ConnectedChat(model, binding.threadId, family = true, writeAllowed = !model.family.accessDenied)
        }
        image?.let { (id, title) -> StoredImageViewer(model, id, title, null) { image = null } }
    }
}

/** Closed callbacks are shared by rich text, images and chat rows. They never open another record or browser. */
internal fun familyServices(avatars: studio.otherstuff.ambulando.app.media.AvatarStore?, photo: (String, String) -> Unit) =
    AppServices(avatars, { url -> studio.otherstuff.ambulando.app.ui.rich.RichLinks.storageObjectId(url)?.let { photo(it, "Photo") } },
        {}, {}, { _, _ -> }, true, false, photo)
