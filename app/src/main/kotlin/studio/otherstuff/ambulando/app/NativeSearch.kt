package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.app.ui.nav.*
import studio.otherstuff.ambulando.app.ui.shell.AppSection
import studio.otherstuff.ambulando.core.store.*

@Composable internal fun NativeSearch(model: AppModel, done: () -> Unit) {
    val w = model.workspace ?: return
    val services = LocalAppServices.current
    var text by remember { mutableStateOf("") }
    val hits by remember(w.id, text) { model.store.liveQuery({ it.workspaceId == w.id }) { model.store.reader.search(w.id, text) } }.collectAsState(emptyList())
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onClick = done) { Text("Done") }
        OutlinedTextField(text, { text = it }, label = { Text("Search downloaded history") }, modifier = Modifier.fillMaxWidth())
        LazyColumn { items(hits, key = { it.id }) { hit -> TextButton(onClick = {
            when (hit.kind) {
                SearchHit.Kind.TASK -> { model.section = AppSection.TASKS; services.open(WorkRoute.Task(w.id, hit.recordId)) }
                SearchHit.Kind.DOC -> { model.section = AppSection.DOCS; services.open(WorkRoute.Doc(w.id, hit.recordId)) }
                SearchHit.Kind.FILE -> model.section = AppSection.FILES
                SearchHit.Kind.MESSAGE -> {
                    model.section = AppSection.CHAT
                    hit.channelId?.let { id -> model.channels.firstOrNull { it.id == id }?.let { model.selectContext(it.scopeId, id) } }
                    hit.threadId?.let { services.open(WorkRoute.Thread(w.id, hit.channelId, it)) }
                }
            }; done()
        }) { Column { Text(hit.title); Text(hit.snippet); if (hit.accessRemoved) Text("No longer available to you") } } } }
    }
}
