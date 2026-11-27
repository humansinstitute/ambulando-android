package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.app.ui.nav.LocalAppServices
import studio.otherstuff.ambulando.app.ui.nav.WorkRoute
import studio.otherstuff.ambulando.core.goals.*
import studio.otherstuff.ambulando.core.store.*
import studio.otherstuff.ambulando.core.transport.*
import java.util.UUID

@Composable internal fun GoalDiscussButton(model: AppModel, doc: GoalDocument, readOnly: Boolean) {
    val runtime = model.active ?: return
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    var choosing by remember(doc.id) { mutableStateOf(false) }
    var busy by remember(doc.id) { mutableStateOf(false) }
    var error by remember(doc.id) { mutableStateOf<String?>(null) }
    var pending by remember(doc.id) { mutableStateOf<GoalDiscussionReceipt?>(null) }
    var loaded by remember(doc.id) { mutableStateOf(false) }
    var recoveryFailed by remember(doc.id) { mutableStateOf(false) }
    var abandoning by remember(doc.id) { mutableStateOf(false) }
    val receiptKey = GoalDiscussionReceipt.key(runtime.connection.id, runtime.workspace.id, doc.id)
    LaunchedEffect(receiptKey) {
        try { pending = runtime.store.reader.state(receiptKey)?.let { GoalDiscussionReceipt.parse(it, runtime.workspace.id, doc.id) }; loaded = true }
        catch (_: Exception) { error = "The pending discussion receipt could not be read. Existing chats are preserved." }
    }
    val goal = doc.goal
    suspend fun open(created: ThreadRecord) {
        runtime.store.writer.upsertThreads(runtime.workspace.id, listOf(created))
        runtime.store.writer.setState(receiptKey, null); pending = null
        check(model.active === runtime && services.unlocked) { "The discussion was saved. Return to this goal to open it." }
        choosing = false
        services.open(WorkRoute.Thread(runtime.workspace.id, created.channelId, created.id))
    }
    suspend fun start(channel: String) {
        check(model.active === runtime && services.unlocked) { "Return to the original workspace and unlock it." }
        check(pending == null) { "Find the pending discussion before starting another." }
        busy = true
        try {
            val target = validateGoalDiscussionChannel(model, channel)
            val requestId = UUID.randomUUID().toString()
            val receipt = GoalDiscussionReceipt(runtime.workspace.id, doc.id, channel, requestId)
            val created = model.towerAPI(runtime.connection).createGoalDiscussion(runtime.workspace.id, runtime.workspace.appNpub,
                doc.id, channel, target.scopeId, goal.defaultChannelId, requestId) {
                check(model.active === runtime && services.unlocked) { "Return to the original workspace and unlock it." }
                runtime.store.writer.setState(receiptKey, receipt.encode()); pending = receipt
            }
            open(created)
        } catch (e: Exception) {
            // Only definitive precondition/HTTP refusals can clear a receipt. Timeouts and cancellation may have committed.
            if (e is GoalError || e is TowerError.Http && e.status in setOf(400, 401, 403, 404)) {
                runtime.store.writer.setState(receiptKey, null); pending = null
            }
            throw e
        } finally { busy = false }
    }
    suspend fun recover() {
        val receipt = requireNotNull(pending)
        check(model.active === runtime && services.unlocked)
        val channel = receipt.channelId
        val request = receipt.requestId
        val found = model.towerAPI(runtime.connection).findGoalDiscussion(runtime.workspace.id, runtime.workspace.appNpub, channel, doc.id, request)
        if (found == null) error("The earlier start is not confirmed in the accessible recent chats. No second chat was created. Refresh associated work or check this channel before trying again.")
        open(found)
    }
    Column {
        Button(enabled = !readOnly && !busy && loaded, modifier = Modifier.testTag("goal.discuss"), onClick = {
            error = null
            val saved = goal.defaultChannelId
            if (pending != null) { busy = true; scope.launch {
                try { recover() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = ConnectionFailure.userMessage(e) ?: e.message; recoveryFailed = true }
                finally { busy = false }
            } }
            else if (saved == null || goalDiscussionChannels(model).none { it.id == saved }) choosing = true
            else { busy = true; scope.launch {
                try { start(saved) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = (ConnectionFailure.userMessage(e) ?: e.message ?: "Could not start discussion.") + " Check associated chats before starting another discussion." }
            } }
        }) { Text(if (busy) "Starting…" else if (pending != null) "Find discussion" else "Discuss goal") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("goal.discuss.error")) }
        if (pending != null && recoveryFailed) TextButton(enabled = !busy, onClick = { abandoning = true }, modifier = Modifier.testTag("goal.discuss.startAnother")) { Text("Start another discussion…") }
    }
    if (choosing) GoalChannelPicker(model, goal.defaultChannelId, onDismiss = { if (!busy) choosing = false }, allowUnset = false) {
        start(requireNotNull(it))
    }
    if (abandoning) AlertDialog(onDismissRequest = { if (!busy) abandoning = false },
        title = { Text("Start a separate discussion?") },
        text = { Text("The earlier request may already have started a chat. It will be kept if it appears. You can explicitly choose a channel for a new discussion.") },
        confirmButton = { TextButton(enabled = !busy, modifier = Modifier.testTag("goal.discuss.confirmAnother"), onClick = {
            busy = true
            scope.launch {
                try {
                    check(model.active === runtime && services.unlocked)
                    runtime.store.writer.setState(receiptKey, null); pending = null
                    recoveryFailed = false; error = null; abandoning = false; choosing = true
                } catch (e: Exception) { error = e.message }
                finally { busy = false }
            }
        }) { Text("Choose channel") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { abandoning = false }) { Text("Keep looking") } })
}
