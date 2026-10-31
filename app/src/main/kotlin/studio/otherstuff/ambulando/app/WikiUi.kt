package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.core.store.directoryMembers
import studio.otherstuff.ambulando.core.store.docs
import studio.otherstuff.ambulando.core.store.workspaceWrites
import studio.otherstuff.ambulando.core.docs.WikiContext
import studio.otherstuff.ambulando.core.docs.WikiLinkController
import studio.otherstuff.ambulando.core.docs.WikiLinks
import studio.otherstuff.ambulando.core.transport.DocRecord

/** A result row: the title, and where the page lives (Scope / channel), with a note when another page has the same title. */
@Composable private fun PageRow(doc: DocRecord, context: WikiContext, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(doc.title, style = MaterialTheme.typography.bodyLarge)
            Text(context.place(doc) + (if (context.titleCount(doc.title) > 1) " · same title as another page" else ""), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The result of `[[` typing: this channel first, every channel and scope on request, and "Create “q” or search more…". */
@Composable internal fun WikiQueryPanel(wiki: WikiLinkController, state: WikiLinkController.State, scope: kotlinx.coroutines.CoroutineScope) {
    val query = state.query ?: return
    if (state.sheet != null) return
    var everywhere by remember(query.range.from) { mutableStateOf(false) }
    LaunchedEffect(query.range.from) { wiki.refresh() }
    val results = remember(query.text, everywhere, state.context) { wiki.panelResults(query.text, everywhere) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("wiki.panel")) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Text(if (everywhere) "Pages in every channel and scope" else "Pages in this channel", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            results.forEach { doc ->
                PageRow(doc, state.context, Modifier.testTag("wiki.panel.result")) { scope.launch { wiki.choose(doc, WikiLinkController.Pick.Insert(query.range), openAfter = false) } }
            }
            if (results.isEmpty()) Text("No page matches yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Checkbox(everywhere, { everywhere = it }, modifier = Modifier.testTag("wiki.panel.everywhere"))
                Text("Search every channel and scope", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { scope.launch { wiki.openPickerForQuery() } }, modifier = Modifier.testTag("wiki.panel.more")) {
                Text(if (query.text.isBlank()) "Search more…" else "Create “${query.text.trim()}” or search more…")
            }
        }
    }
}

/** The wiki sheets of a document screen (create a missing page, choose between same-titled pages, the link picker) and its banner. */
/** [scope] outlives the sheets: choosing closes a sheet at once, and the work it starts must not be cancelled with it. */
@Composable internal fun WikiSheets(wiki: WikiLinkController, state: WikiLinkController.State, scope: kotlinx.coroutines.CoroutineScope) {
    state.banner?.let { text ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).testTag("wiki.banner"), style = MaterialTheme.typography.bodySmall,
                color = if (state.bannerIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { wiki.clearBanner() }) { Text("OK") }
        }
    }
    when (val sheet = state.sheet) {
        null -> {}
        is WikiLinkController.Sheet.Create -> CreateSheet(wiki, state.context, sheet, scope)
        is WikiLinkController.Sheet.Choose -> AlertDialog(onDismissRequest = { wiki.dismissSheet() }, title = { Text("Choose a page") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(sheet.reason, style = MaterialTheme.typography.bodySmall)
                sheet.matches.forEach { doc ->
                    PageRow(doc, state.context, Modifier.testTag("wiki.choose.item")) {
                        scope.launch { wiki.choose(doc, sheet.pick, openAfter = sheet.pick is WikiLinkController.Pick.Reference) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { wiki.dismissSheet() }) { Text("Cancel") } })
        is WikiLinkController.Sheet.Picker -> PickerSheet(wiki, state.context, sheet, scope)
    }
}

/** Destination channels: every channel the viewer can read, labelled Scope / channel. */
@Composable private fun Destinations(context: WikiContext, selected: String?, onSelect: (String) -> Unit) {
    Column {
        Text("Create in", style = MaterialTheme.typography.labelMedium)
        context.channels.filter { !it.accessRemoved }.forEach { channel ->
            TextButton(onClick = { onSelect(channel.id) }, modifier = Modifier.fillMaxWidth().testTag("wiki.destination.${channel.id}")) {
                Text((if (selected == channel.id) "✓ " else "") + context.placeOfChannel(channel.id), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable private fun CreateSheet(wiki: WikiLinkController, context: WikiContext, sheet: WikiLinkController.Sheet.Create, scope: kotlinx.coroutines.CoroutineScope) {
    var channelId by remember(sheet) { mutableStateOf<String?>(null) }
    LaunchedEffect(sheet) { wiki.refresh() }
    val chosen = channelId ?: wiki.sourceChannelId ?: context.channels.firstOrNull { !it.accessRemoved }?.id
    AlertDialog(onDismissRequest = { wiki.dismissSheet() }, title = { Text("Create page") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text("There is no page called “${sheet.target.title}” yet.")
            Destinations(context, chosen) { channelId = it }
            if (chosen != null) Text(
                "It will be created as a ${context.kindWord(chosen)} in ${context.placeOfChannel(chosen)} and linked here. Anyone who can read that channel can open it; a link does not give anyone access.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }, confirmButton = {
        TextButton(enabled = chosen != null, modifier = Modifier.testTag("wiki.create.confirm"),
            onClick = { chosen?.let { scope.launch { wiki.createAndLink(sheet.target.title, it, WikiLinkController.Pick.Reference(sheet.target)) } } }) { Text("Create and link") }
    }, dismissButton = { TextButton(onClick = { wiki.dismissSheet() }, modifier = Modifier.testTag("wiki.create.cancel")) { Text("Cancel") } })
}

/**
 * Search accessible pages and link to one, or create a new page and link it. Search is limited to this channel until the person
 * asks for every channel and scope; same-titled pages are all listed with where they are, never merged.
 */
@Composable private fun PickerSheet(wiki: WikiLinkController, context: WikiContext, sheet: WikiLinkController.Sheet.Picker, scope: kotlinx.coroutines.CoroutineScope) {
    var query by remember(sheet) { mutableStateOf(sheet.query) }
    var everywhere by remember(sheet) { mutableStateOf(false) }
    var newTitle by remember(sheet) { mutableStateOf(sheet.query) }
    var channelId by remember(sheet) { mutableStateOf<String?>(null) }
    val results = remember(query, everywhere, context) { WikiLinks.search(query, context.docs, wiki.sourceChannelId, wiki.sourceDocId, everywhere) }
    val destination = channelId ?: wiki.sourceChannelId ?: context.channels.firstOrNull { !it.accessRemoved }?.id
    AlertDialog(onDismissRequest = { wiki.dismissSheet() }, title = { Text("Link to a page") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(query, { query = it }, label = { Text("Search pages") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wiki.picker.search"))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(everywhere, { everywhere = it }, modifier = Modifier.testTag("wiki.picker.everywhere"))
                Text("Search every channel and scope", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (everywhere) "Showing pages you can read in every scope. Pages with the same title are listed separately; check where each one is." else "Showing pages in this channel only.", style = MaterialTheme.typography.labelSmall)
            results.forEach { doc -> PageRow(doc, context, Modifier.testTag("wiki.picker.result")) { scope.launch { wiki.choose(doc, sheet.pick, openAfter = false) } } }
            if (results.isEmpty()) Text("No page matches.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Or create a new page", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(newTitle, { newTitle = it }, label = { Text("New page title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wiki.picker.newtitle"))
            Destinations(context, destination) { channelId = it }
            if (destination != null && newTitle.isNotBlank()) Text(
                "It will be created as a ${context.kindWord(destination)} in ${context.placeOfChannel(destination)}.", style = MaterialTheme.typography.bodySmall,
            )
            TextButton(enabled = destination != null && newTitle.isNotBlank(), modifier = Modifier.testTag("wiki.picker.create"),
                onClick = { destination?.let { scope.launch { wiki.createAndLink(newTitle, it, sheet.pick) } } }) { Text("Create and link") }
        }
    }, confirmButton = { TextButton(onClick = { wiki.dismissSheet() }, modifier = Modifier.testTag("wiki.picker.cancel")) { Text("Cancel") } })
}

/**
 * The app's wiki environment for one document screen (reader or editor): context from the local store, navigation through the
 * app's record routing, and creation/binding through the durable write queue and the draft-first save path.
 */
internal fun appWikiEnvironment(
    model: AppModel, c: studio.otherstuff.ambulando.core.store.ConnectionRecord, w: studio.otherstuff.ambulando.core.transport.WorkspaceRecord, docId: String,
    visit: () -> studio.otherstuff.ambulando.core.docs.WikiVisit, beforeLeaving: suspend () -> Boolean, navigate: (String) -> Unit,
): studio.otherstuff.ambulando.core.docs.WikiEnvironment = object : studio.otherstuff.ambulando.core.docs.WikiEnvironment {
    override suspend fun loadContext(): WikiContext {
        val names = model.store.reader.directoryMembers(w.id).associate { it.npub to it.label }
        return WikiContext(
            docs = model.store.reader.docs(w.id, null, 2000).filter { !it.accessRemoved }, channels = model.channels, scopes = model.scopes, names = names,
            pending = model.store.reader.workspaceWrites(c.id, w.id), viewerNpub = c.signerNpub,
        )
    }
    override fun visit() = visit()
    override suspend fun beforeLeaving(): Boolean = beforeLeaving()
    override fun navigate(documentId: String) = navigate(documentId)
    override suspend fun submit(intent: studio.otherstuff.ambulando.core.store.WriteIntent): Boolean = model.connection?.id == c.id && model.workspace?.id == w.id && model.submit(intent)
    override suspend fun stage(bytes: ByteArray): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            model.writes.staging.mkdirs()
            val name = "doc-${java.util.UUID.randomUUID()}"
            java.io.File(model.writes.staging, name).writeBytes(bytes); name
        }.getOrNull()
    }
    override suspend fun bindInDraft(title: String, documentId: String, occurrence: Int?, expectedCount: Int?): studio.otherstuff.ambulando.core.docs.WikiBindOutcome? =
        studio.otherstuff.ambulando.core.docs.WikiBinder.bind(title, documentId, occurrence, expectedCount, docId, c, w, model.store,
            studio.otherstuff.ambulando.core.docs.RunnerAuthority(model.documents, c, w))
}
