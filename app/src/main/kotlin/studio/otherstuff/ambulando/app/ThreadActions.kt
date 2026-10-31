package studio.otherstuff.ambulando.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.shell.LocalRouteExit
import studio.otherstuff.ambulando.app.ui.work.PickerRow
import studio.otherstuff.ambulando.core.chat.*
import studio.otherstuff.ambulando.core.deck.PersonalAgentLauncher
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.surfaces.PinRecord
import studio.otherstuff.ambulando.core.transport.MessageRecord

/** A phone thread already occupies its section; its menu sits next to Pin and Close. */
@Composable internal fun ThreadHeader(model: AppModel, threadId: String, enabled: Boolean) {
    val w = model.workspace ?: return
    val exit = LocalRouteExit.current
    val menu by remember(w.id, threadId) { model.store.liveQuery({ it.workspaceId == w.id }) {
        model.store.reader.threadMenu(w.id, threadId) to model.store.reader.thread(w.id, threadId)?.title
    } }.collectAsState(null)
    val record by remember(w.id, threadId) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.thread(w.id, threadId) } }.collectAsState(null)
    var renaming by remember(threadId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("thread.header"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(menu?.second?.let(MentionCodec::plain) ?: "Conversation", maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(start = 12.dp))
        MessageMenu(model, menu?.first?.root, menu?.first?.rootMessageId, menu?.first?.reference, menu?.first?.autopilotSource,
            enabled, "thread", "Thread menu", conversationThreadId = threadId, onRename = if (record != null) ({ renaming = true }) else null)
        if (exit != null) IconButton(onClick = exit, modifier = Modifier.testTag("thread.close")) { Icon(Icons.Outlined.Close, "Close thread") }
    }
    if (renaming) record?.let { RenameThreadDialog(model, it) { renaming = false } }
}

/**
 * Renames the conversation online (Tower decides who may). The draft is kept in the field on any failure, with the
 * reason beside it; success stores Tower's answer so the header and lists follow.
 */
@Composable private fun RenameThreadDialog(model: AppModel, thread: studio.otherstuff.ambulando.core.transport.ThreadRecord, dismiss: () -> Unit) {
    val w = model.workspace ?: return; val c = model.connection ?: return
    val services = LocalAppServices.current
    // Fixed when the editor opens: a rename elsewhere is detected against this title and version, never overwritten.
    val originalTitle = remember(thread.id) { thread.title }
    val originalVersion = remember(thread.id) { thread.rowVersion }
    var title by remember(thread.id) { mutableStateOf(thread.title?.let(MentionCodec::plain).orEmpty()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!working) dismiss() }, title = { Text("Rename thread") }, text = {
        Column {
            OutlinedTextField(title, { title = it; error = null }, singleLine = true, label = { Text("Title") }, modifier = Modifier.testTag("thread.rename.field"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("thread.rename.error")) }
        }
    }, confirmButton = { TextButton(enabled = !working && title.isNotBlank() && services.unlocked, modifier = Modifier.testTag("thread.rename.save"), onClick = {
        working = true; error = null
        model.act {
            try {
                model.sync.renameThread(c, w, thread.id, title, originalVersion, originalTitle)
                dismiss()
            } catch (e: ThreadRenameException) { error = e.message } finally { working = false }
        }
    }) { Text(if (working) "Saving…" else "Save") } }, dismissButton = { TextButton(enabled = !working, onClick = dismiss) { Text("Cancel") } })
}

/** Shared header/message menu; all mutation controls also respect local access loss and author identity. */
@Composable internal fun MessageMenu(
    model: AppModel, message: MessageRecord?, rootId: String?, reference: String?, autopilot: MessageRecord?,
    enabled: Boolean, prefix: String, label: String, conversationThreadId: String? = message?.threadId, onRename: (() -> Unit)? = null,
) {
    val w = model.workspace ?: return
    val c = model.connection ?: return
    val services = LocalAppServices.current
    val context = LocalContext.current
    var expanded by remember(message?.id, reference) { mutableStateOf(false) }
    var gettingItDone by remember(message?.id) { mutableStateOf(false) }
    var deleting by remember(message?.id) { mutableStateOf(false) }
    var failure by remember(message?.id) { mutableStateOf<String?>(null) }
    var branching by remember(message?.id) { mutableStateOf(false) }
    val branchRequestId = remember(message?.id) { "branch:${java.util.UUID.randomUUID()}" }
    val writable = enabled && services.unlocked && message?.accessRemoved != true
    fun copy(text: String) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text)) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = message != null || reference != null || autopilot != null || onRename != null,
            modifier = Modifier.testTag("$prefix.actions")) { Icon(Icons.Outlined.MoreHoriz, label) }
        DropdownMenu(expanded, { expanded = false }) {
            if (message != null && !message.deleted && rootId != null) DropdownMenuItem({ Text("Get it done") }, enabled = writable,
                onClick = { expanded = false; gettingItDone = true }, modifier = Modifier.testTag("$prefix.menu.getitdone"))
            if (message != null) DropdownMenuItem({ Text("Copy message") },
                onClick = { expanded = false; copy(MentionCodec.plain(message.body)) }, modifier = Modifier.testTag("$prefix.menu.copy"))
            if (prefix != "thread" && message != null && message.threadId != null && !message.deleted) DropdownMenuItem(
                { Text(if (branching) "Branching…" else "Branch from here") }, enabled = writable && !branching, onClick = {
                    expanded = false; branching = true
                    model.act {
                        try {
                            val recipient = model.store.reader.members(w.id).firstOrNull { it.npub == message.senderNpub && it.isAgent }?.npub
                                ?: model.store.reader.workspaceAgents(w.id).firstOrNull { it.agentNpub == message.senderNpub }?.agentNpub
                            val created = model.sync.branchThread(c, w, message, recipient, branchRequestId)
                            services.open(WorkRoute.Thread(w.id, created.channelId, created.id))
                        } finally { branching = false }
                    }
                }, modifier = Modifier.testTag("$prefix.menu.branch"))
            if (autopilot != null) DropdownMenuItem({ Text("Autopilot Link") }, onClick = {
                expanded = false
                model.act {
                    val launchers = PersonalAgentLauncher.decodeList(model.store.reader.state(PersonalAgentLauncher.stateKey(c.id, w.id)))
                    val url = AutopilotSessionLink.resolve(autopilot, w.raw, launchers)
                    if (url != null) services.openUrl(url) else failure = "This workspace has no supported HTTPS Autopilot address for this session."
                }
            }, modifier = Modifier.testTag("$prefix.menu.autopilot"))
            if (reference != null) DropdownMenuItem({ Text("FD Ref") }, onClick = { expanded = false; copy(reference) },
                modifier = Modifier.testTag("$prefix.menu.ref"))
            if (onRename != null) DropdownMenuItem({ Text("Rename thread") }, enabled = writable, onClick = { expanded = false; onRename() },
                modifier = Modifier.testTag("$prefix.menu.rename"))
            if (message != null && !message.deleted && message.senderNpub == c.signerNpub) DropdownMenuItem({ Text("Delete message") }, enabled = writable,
                onClick = { expanded = false; deleting = true }, modifier = Modifier.testTag("$prefix.menu.delete"))
        }
    }
    if (gettingItDone && message != null && rootId != null) GetItDoneDialog(model, message, rootId, conversationThreadId, writable) { gettingItDone = false }
    if (deleting && message != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this message for everyone?") },
        confirmButton = { TextButton(enabled = writable, modifier = Modifier.testTag("$prefix.delete.confirm"), onClick = {
            if (model.submit(WriteIntent(WriteKind.MESSAGE_DELETE, message.id, buildJsonObject {
                put("channel_id", message.channelId); put("row_version", message.rowVersion); message.threadId?.let { put("thread_id", it) }
            }))) deleting = false
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    failure?.let { AlertDialog(onDismissRequest = { failure = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } }) }
}

@Composable private fun GetItDoneDialog(model: AppModel, message: MessageRecord, rootId: String, threadId: String?, enabled: Boolean, dismiss: () -> Unit) {
    val w = model.workspace ?: return
    val members by produceState(emptyList<studio.otherstuff.ambulando.core.transport.MemberRecord>(), w.id) { value = model.store.reader.directoryMembers(w.id) }
    val messages by produceState(listOf(message), w.id, message.id) {
        value = threadId?.let { model.store.reader.threadMessages(w.id, it, 6) }?.takeIf { it.isNotEmpty() } ?: listOf(message)
    }
    var title by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var output by remember { mutableStateOf(GetItDone.Output.CHAT) }
    var assignee by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    LaunchedEffect(members) { if (message.senderNpub != model.connection?.signerNpub) assignee = members.firstOrNull { it.npub == message.senderNpub }?.npub }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Get it done") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("getitdone.form")) {
            OutlinedTextField(title, { title = it }, label = { Text("What should happen?") }, modifier = Modifier.testTag("getitdone.title"))
            PickerRow("Output", output.label, GetItDone.Output.entries.map { it.label to it }, { output = it }, tag = "getitdone.output")
            PickerRow("Assignee", members.firstOrNull { it.npub == assignee }?.label ?: "Unassigned",
                listOf<Pair<String, String?>>("Unassigned" to null) + members.map { it.label to it.npub }, { assignee = it }, tag = "getitdone.assignee")
            OutlinedTextField(extra, { extra = it }, label = { Text("Extra instructions (optional)") }, maxLines = 4, modifier = Modifier.testTag("getitdone.extra"))
            Text(GetItDone.excerpt(messages), style = MaterialTheme.typography.bodySmall, maxLines = 4)
            if (failure) Text("Could not queue the task. Check your connection and try again.")
        }
    }, confirmButton = { TextButton(enabled = enabled && title.isNotBlank(), modifier = Modifier.testTag("getitdone.create"), onClick = {
        if (model.submit(GetItDone.intent(message.copy(threadId = threadId), rootId, title, output, extra, assignee, messages))) dismiss() else failure = true
    }) { Text("Create task") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
