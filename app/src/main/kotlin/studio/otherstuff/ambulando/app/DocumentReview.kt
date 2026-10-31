package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.core.docs.DocumentEditController
import studio.otherstuff.ambulando.core.docs.DocumentMerge
import studio.otherstuff.ambulando.core.docs.DocumentRecovery
import studio.otherstuff.ambulando.core.docs.DocumentReviewDiff
import studio.otherstuff.ambulando.core.docs.PreservedDocumentSnapshot

/**
 * Reviewing a newer shared version against the person's working copy. Nothing here replaces text on its own: **Accept latest**
 * asks first and keeps the replaced copy in Preserved drafts; **Merge my changes** needs a verified common base and leaves each
 * overlap to an explicit choice; **Edit merged result** only makes a local draft that **Save** (in the editor) publishes.
 */
@Composable internal fun DocumentReviewDialog(controller: DocumentEditController, onDismiss: () -> Unit) {
    val s by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmAccept by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<PreservedDocumentSnapshot?>(null) }
    var viewingPreview by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val remote = s.remoteVersion
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("doc.review"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                run {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Review changes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("doc.review.keep")) { Text("Keep editing") }
                    }
                    Text(
                        "Your working copy is kept as it is. Based on version ${s.localBaseVersion ?: "?"}" + (remote?.let { "; latest shared version is ${it.rowVersion}." } ?: "; the latest shared version has not changed."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("doc.review.status")) }
                }
                if (remote != null) {
                    run {
                        Text("What differs", style = MaterialTheme.typography.titleMedium)
                        val lines = remember(s.editRevision, remote) { DocumentReviewDiff.lines(controller.localReviewText, controller.remoteReviewText) }
                        if (lines.isEmpty()) Text("This document is too large to compare line by line. Read the two full copies below.", style = MaterialTheme.typography.bodySmall)
                        else SelectionContainer {
                            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).testTag("doc.review.diff")) {
                                lines.forEach { line ->
                                    Text(line.prefix + " " + line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                        color = when (line.prefix) { "+" -> Color(0xFF1B7F3B); "−" -> Color(0xFFB3261E); else -> MaterialTheme.colorScheme.onSurface })
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(controller.localReviewText)) }) { Text("Copy my text") }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(controller.remoteReviewText)) }) { Text("Copy latest") }
                        }
                    }
                    run {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { scope.launch { controller.beginMerge() } }, enabled = s.canMerge && !s.reconciling, modifier = Modifier.testTag("doc.review.merge")) { Text("Merge my changes") }
                            OutlinedButton(onClick = { confirmAccept = true }, enabled = !s.reconciling && !s.saving, modifier = Modifier.testTag("doc.review.accept")) { Text("Accept latest") }
                        }
                        if (!s.canMerge) Text(
                            "No verified common saved base is kept for this draft, so there is no automatic merge. Compare and copy the versions, then resolve them yourself.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("doc.review.nobase"),
                        )
                    }
                }
                if (s.mergeReady) {
                    run {
                        HorizontalDivider()
                        Text("Merged result", style = MaterialTheme.typography.titleMedium)
                        if (s.previewIsStale) {
                            Text("The shared version changed again while you were reviewing. Your preview was prepared against version ${controller.journal.mergeLatest?.rowVersion}.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { scope.launch { controller.reconcilePreview() } }, enabled = s.mergeConflicts.isEmpty(), modifier = Modifier.testTag("doc.review.reconcile")) { Text("Reconcile preview with latest") }
                        }
                    }
                    s.mergeConflicts.forEach { conflict ->
                        Column(Modifier.fillMaxWidth().testTag("doc.review.conflict")) {
                            Text(conflict.label + " — choose which to keep", style = MaterialTheme.typography.labelLarge)
                            Text("Yours:", style = MaterialTheme.typography.labelSmall)
                            Text(conflict.local.take(600), style = MaterialTheme.typography.bodySmall)
                            Text("Latest:", style = MaterialTheme.typography.labelSmall)
                            Text(conflict.latest.take(600), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { scope.launch { controller.resolveMerge(conflict.id, DocumentMerge.Choice.LOCAL) } }, modifier = Modifier.testTag("doc.review.use.local")) { Text("Use local") }
                                OutlinedButton(onClick = { scope.launch { controller.resolveMerge(conflict.id, DocumentMerge.Choice.LATEST) } }, modifier = Modifier.testTag("doc.review.use.latest")) { Text("Use latest") }
                            }
                        }
                    }
                    run {
                        if (s.mergeConflicts.isEmpty()) {
                            OutlinedTextField(s.mergeTitle, { controller.editPreview(title = it) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("doc.review.title"))
                            if (controller.previewIsRich) {
                                Text("Preview (edit it in the formatted editor after applying):", style = MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(controller.readableMergePreview, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("doc.review.preview")) }
                            } else {
                                OutlinedTextField(s.mergeText, { controller.editPreview(text = it) }, label = { Text("Merged text") }, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("doc.review.preview"))
                            }
                            Button(onClick = { scope.launch { if (controller.useMergePreview()) onDismiss() } }, enabled = !s.reconciling && !s.saving && !s.previewIsStale, modifier = Modifier.testTag("doc.review.apply")) { Text("Edit merged result") }
                            Text("This makes a draft on this device. Nothing is published until you choose Save.", style = MaterialTheme.typography.bodySmall)
                        } else Text("${s.mergeConflicts.size} overlap(s) need a choice before the merged result can be edited.", style = MaterialTheme.typography.bodySmall)
                        if (s.authorityLost && (s.mergeReady || s.concurrentEditing)) {
                            OutlinedButton(onClick = { scope.launch { controller.takeOverForMerge() } }, enabled = !s.takingOver, modifier = Modifier.testTag("doc.review.takeover")) { Text("Get editing authority for this merge") }
                        }
                    }
                }
                run { HorizontalDivider(); Text("Preserved drafts (on this device)", style = MaterialTheme.typography.titleMedium) }
                val copies = s.retained.reversed()
                if (copies.isEmpty() && s.previousPreviews.isEmpty()) Text("Nothing has been preserved yet.", style = MaterialTheme.typography.bodySmall)
                copies.forEach { copy ->
                    Column(Modifier.fillMaxWidth().testTag("doc.review.preserved")) {
                        Text(copy.title.ifBlank { "Untitled" } + " · based on version ${copy.rowVersion}", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewing = copy; viewingPreview = null }) { Text("Preview") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(copy.title + "\n\n" + copy.document.markdown)) }) { Text("Copy") }
                            TextButton(onClick = { scope.launch { if (controller.restorePreserved(copy)) onDismiss() } }, enabled = !s.saving && !s.takingOver, modifier = Modifier.testTag("doc.review.restore")) { Text("Restore as local draft") }
                        }
                    }
                }
                s.previousPreviews.reversed().forEach { preview ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("Earlier merge preview · " + preview.title, style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { viewing = null; viewingPreview = controller.readablePreviousPreview(preview) }) { Text("Preview") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(controller.readablePreviousPreview(preview))) }) { Text("Copy") }
                        }
                    }
                }
            }
        }
    }
    viewing?.let { copy ->
        AlertDialog(onDismissRequest = { viewing = null }, title = { Text(copy.title.ifBlank { "Untitled" }) },
            text = { SelectionContainer { Text(copy.document.markdown, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } })
    }
    viewingPreview?.let { text ->
        AlertDialog(onDismissRequest = { viewingPreview = null }, title = { Text("Earlier merge preview") },
            text = { SelectionContainer { Text(text, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { viewingPreview = null }) { Text("Close") } })
    }
    if (confirmAccept) {
        AlertDialog(onDismissRequest = { confirmAccept = false }, title = { Text("Accept the latest version?") },
            text = { Text("Your working copy will be replaced by the latest shared version. Your copy is first kept in Preserved drafts on this device, and no version is created.") },
            confirmButton = { TextButton(onClick = { confirmAccept = false; scope.launch { controller.acceptLatest(); if (controller.remoteVersion == null) onDismiss() } }, modifier = Modifier.testTag("doc.review.accept.confirm")) { Text("Accept latest") } },
            dismissButton = { TextButton(onClick = { confirmAccept = false }) { Text("Keep my copy") } })
    }
}

/** Explicit takeover: names what happens to the local work and to the other editor before anything is sent. */
@Composable internal fun TakeoverDialog(controller: DocumentEditController, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s by controller.state.collectAsState()
    AlertDialog(onDismissRequest = { if (!s.takingOver) onDismiss() }, title = { Text("Take over editing?") }, text = {
        Text(
            "Another editor currently has this document open. Taking over ends their editing session; Tower checks that you may edit it. " +
                (if (s.dirty) "Your unsaved changes are first saved as a recovery copy in Tower, then the latest saved version is loaded. " else "The latest saved version is loaded. ") +
                "If anything fails, your draft stays on this device and nothing is forced.",
        )
    }, confirmButton = {
        TextButton(enabled = !s.takingOver, modifier = Modifier.testTag("doc.takeover.confirm"), onClick = { scope.launch { controller.takeover(); onDismiss() } }) { Text(if (s.takingOver) "Taking over…" else "Take over") }
    }, dismissButton = { TextButton(enabled = !s.takingOver, onClick = onDismiss) { Text("Cancel") } })
}

/** Saved versions: the latest head and Tower's stored previous versions, previewed as text; a stale choice fails and keeps both. */
@Composable internal fun SavedVersionsDialog(controller: DocumentEditController, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s by controller.state.collectAsState()
    var versions by remember { mutableStateOf<List<DocumentRecovery>?>(null) }
    var selected by remember { mutableStateOf<DocumentRecovery?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        versions = controller.openVersions()
        failed = versions == null
        preview = controller.previewVersion(null)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Saved versions") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            if (failed) Text(s.status ?: "Saved versions are unavailable while offline. Your draft is retained.")
            Text("Latest saved version is shown first. Choosing a previous version never changes the other versions.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { selected = null; scope.launch { preview = controller.previewVersion(null) } }) { Text("Latest saved version") }
            versions.orEmpty().forEach { version ->
                TextButton(onClick = { selected = version; preview = null; scope.launch { preview = controller.previewVersion(version) } }, modifier = Modifier.testTag("doc.versions.item")) {
                    Text((version.title ?: "Previous saved version") + (if (version.createdAt.isNotEmpty()) " · " + version.createdAt.take(16).replace('T', ' ') else ""))
                }
            }
            preview?.let { SelectionContainer { Text(it, modifier = Modifier.padding(top = 8.dp).testTag("doc.versions.preview")) } }
        }
    }, confirmButton = {
        Row {
            selected?.let { chosen ->
                TextButton(onClick = { scope.launch { if (controller.resolveVersion(chosen, promote = false)) onDismiss() } }, enabled = !s.saving, modifier = Modifier.testTag("doc.versions.dismiss")) { Text("Dismiss") }
                TextButton(onClick = { scope.launch { if (controller.resolveVersion(chosen, promote = true)) onDismiss() } }, enabled = !s.saving, modifier = Modifier.testTag("doc.versions.keep")) { Text("Keep this version") }
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    })
}
