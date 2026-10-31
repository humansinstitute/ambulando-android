package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.store.*

@Composable internal fun NativeSearch(model: AppModel, done: () -> Unit) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    var text by remember { mutableStateOf("") }
    // Typing is debounced so the local index is not queried on every keystroke.
    var query by remember { mutableStateOf("") }
    LaunchedEffect(text) { kotlinx.coroutines.delay(200); query = text.trim() }
    val hits by remember(w.id, query) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.search(w.id, query) } }.collectAsState(emptyList())
    // Results are grouped as on iPhone, and each says where it lives (scope · #channel).
    val groups = listOf(SearchHit.Kind.TASK to "Tasks", SearchHit.Kind.DOC to "Documents", SearchHit.Kind.FILE to "Files", SearchHit.Kind.MESSAGE to "Messages")
        .mapNotNull { (kind, title) -> hits.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { title to it } }
    fun place(hit: SearchHit): String? {
        val channel = model.channels.firstOrNull { it.id == hit.channelId } ?: return null
        return listOfNotNull(model.scopes.firstOrNull { it.id == channel.scopeId }?.name, "#" + (channel.name.trim().removePrefix("DM:").trim().takeUnless { it.isEmpty() || it.startsWith("npub1") } ?: "Direct message")).joinToString(" · ")
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onClick = done) { Text("Done") }
        OutlinedTextField(text, { text = it }, label = { Text("Search downloaded history") }, modifier = Modifier.fillMaxWidth())
        if (hits.isEmpty()) Text(if (query.length >= 2) "No matches" else "Searches messages, tasks, documents and files saved on this device for the current workspace.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp).testTag("search.empty"))
        LazyColumn { groups.forEach { (title, rows) ->
            item(key = "header:$title") { Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp)) }
            items(rows, key = { it.id }) { hit -> TextButton(modifier = Modifier.testTag("search.hit"), onClick = {
                when (hit.kind) {
                    SearchHit.Kind.TASK -> { model.section = AppSection.TASKS; services.open(WorkRoute.Task(w.id, hit.recordId)) }
                    SearchHit.Kind.DOC -> { model.section = AppSection.DOCS; services.open(WorkRoute.Doc(w.id, hit.recordId)) }
                    SearchHit.Kind.FILE -> {
                        model.section = AppSection.FILES
                        hit.channelId?.let { id -> model.channels.firstOrNull { it.id == id }?.let { model.selectContext(it.scopeId, id) } }
                        services.open(WorkRoute.File(w.id, hit.recordId))
                    }
                    SearchHit.Kind.MESSAGE -> {
                        model.section = AppSection.CHAT
                        hit.channelId?.let { id -> model.channels.firstOrNull { it.id == id }?.let { model.selectContext(it.scopeId, id) } }
                        hit.threadId?.let { services.open(WorkRoute.Thread(w.id, hit.channelId, it)) }
                    }
                }; done()
            }) { Column(Modifier.fillMaxWidth()) {
                Text(hit.title, maxLines = 1); if (hit.snippet.isNotBlank()) Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                place(hit)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                if (hit.accessRemoved) Text("No longer available to you")
            } } }
        } }
    }
}
