package studio.otherstuff.ambulando.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.otherstuff.ambulando.core.surfaces.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HtmlArtefactCommentsSheet(
    viewedVersionId: String,
    viewedVersionNumber: Int,
    isViewingLatest: Boolean,
    threads: List<FileCommentThread>,
    permissions: FileCommentPermissions,
    anchorMatches: Map<String, Boolean>,
    pendingTarget: HtmlCommentAnchor?,
    focusedThreadId: String?,
    showEarlierUnresolved: Boolean,
    onToggleShowEarlier: (Boolean) -> Unit,
    drafts: Map<String, FileCommentDraftStore.Draft>,
    busy: Boolean,
    error: String?,
    authorLabel: (String?) -> String,
    onClearPendingTarget: () -> Unit,
    onDraftChange: (key: String, body: String, parentCommentId: String?, anchor: HtmlCommentAnchor?) -> Unit,
    onSubmitRoot: (anchor: HtmlCommentAnchor?, body: String, clientRequestId: String) -> Unit,
    onSubmitReply: (parentCommentId: String, body: String, clientRequestId: String) -> Unit,
    onToggleResolve: (FileCommentRecord) -> Unit,
    onEditComment: (FileCommentRecord, String) -> Unit,
    onDeleteComment: (FileCommentRecord) -> Unit,
    onJumpToVersion: (String) -> Unit,
    onFocusTarget: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("artefacts.comments.sheet"),
    ) {
        HtmlArtefactCommentsSheetContent(
            viewedVersionId = viewedVersionId,
            viewedVersionNumber = viewedVersionNumber,
            isViewingLatest = isViewingLatest,
            threads = threads,
            permissions = permissions,
            anchorMatches = anchorMatches,
            pendingTarget = pendingTarget,
            focusedThreadId = focusedThreadId,
            showEarlierUnresolved = showEarlierUnresolved,
            onToggleShowEarlier = onToggleShowEarlier,
            drafts = drafts,
            busy = busy,
            error = error,
            authorLabel = authorLabel,
            onClearPendingTarget = onClearPendingTarget,
            onDraftChange = onDraftChange,
            onSubmitRoot = onSubmitRoot,
            onSubmitReply = onSubmitReply,
            onToggleResolve = onToggleResolve,
            onEditComment = onEditComment,
            onDeleteComment = onDeleteComment,
            onJumpToVersion = onJumpToVersion,
            onFocusTarget = onFocusTarget,
            onRefresh = onRefresh,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun HtmlArtefactCommentsSheetContent(
    viewedVersionId: String,
    viewedVersionNumber: Int,
    isViewingLatest: Boolean,
    threads: List<FileCommentThread>,
    permissions: FileCommentPermissions,
    anchorMatches: Map<String, Boolean>,
    pendingTarget: HtmlCommentAnchor?,
    focusedThreadId: String?,
    showEarlierUnresolved: Boolean,
    onToggleShowEarlier: (Boolean) -> Unit,
    drafts: Map<String, FileCommentDraftStore.Draft>,
    busy: Boolean,
    error: String?,
    authorLabel: (String?) -> String,
    onClearPendingTarget: () -> Unit,
    onDraftChange: (key: String, body: String, parentCommentId: String?, anchor: HtmlCommentAnchor?) -> Unit,
    onSubmitRoot: (anchor: HtmlCommentAnchor?, body: String, clientRequestId: String) -> Unit,
    onSubmitReply: (parentCommentId: String, body: String, clientRequestId: String) -> Unit,
    onToggleResolve: (FileCommentRecord) -> Unit,
    onEditComment: (FileCommentRecord, String) -> Unit,
    onDeleteComment: (FileCommentRecord) -> Unit,
    onJumpToVersion: (String) -> Unit,
    onFocusTarget: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Comments · v$viewedVersionNumber",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag("artefacts.comments.title"),
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    modifier = Modifier.testTag("artefacts.comments.refresh"),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh comments")
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            // Explicit permission summary so reviewers know what actions their channel ACL grants.
            val permNote = when {
                !permissions.canComment -> "Read-only access: you can view feedback, not add comments."
                !permissions.canEditSource -> "Comment access: you can comment and reply (source editing requires file write access)."
                else -> null
            }
            if (permNote != null) {
                Text(
                    text = permNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("artefacts.comments.permissions"),
                )
            }

            if (isViewingLatest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Show unresolved from earlier versions", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = showEarlierUnresolved,
                        onCheckedChange = onToggleShowEarlier,
                        modifier = Modifier.testTag("artefacts.comments.earlierToggle"),
                    )
                }
            }

            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().testTag("artefacts.comments.error"),
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (pendingTarget != null) {
                val rootKey = pendingTarget.draftKey(viewedVersionId)
                val draft = drafts[rootKey]
                var text by remember(rootKey, draft?.body) { mutableStateOf(draft?.body ?: "") }
                val requestId = remember(rootKey, draft?.clientRequestId) {
                    draft?.clientRequestId ?: java.util.UUID.randomUUID().toString()
                }
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("artefacts.comments.newComposer"),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "New comment on ${pendingTarget.targetSummary()}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = onClearPendingTarget) { Text("Clear") }
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                onDraftChange(rootKey, it, null, pendingTarget)
                            },
                            enabled = permissions.canComment && !busy,
                            label = { Text("Add feedback") },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth().testTag("artefacts.comments.rootInput"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                enabled = permissions.canComment && !busy && text.isNotBlank(),
                                onClick = { onSubmitRoot(pendingTarget, text.trim(), requestId) },
                                modifier = Modifier.testTag("artefacts.comments.postRoot"),
                            ) {
                                Text(if (busy) "Posting…" else "Post comment")
                            }
                        }
                    }
                }
            }

            if (threads.isEmpty()) {
                Text(
                    text = if (permissions.canComment) "No comments for v$viewedVersionNumber yet. Turn on Comment mode and tap any element in the HTML to start a thread."
                    else "No comments for v$viewedVersionNumber.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("artefacts.comments.empty"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(threads, key = { it.root.id }) { thread ->
                        val root = thread.root
                        val rootAnchor = root.anchor
                        val hasElementTarget = rootAnchor != null && rootAnchor.kind == "html_element"
                        val matched = if (!hasElementTarget) null else anchorMatches[root.id]
                        val isFocused = focusedThreadId == root.id
                        val replyKey = "reply:${root.id}"
                        val replyDraft = drafts[replyKey]
                        var replyText by remember(replyKey, replyDraft?.body) { mutableStateOf(replyDraft?.body ?: "") }
                        val replyRequestId = remember(replyKey, replyDraft?.clientRequestId) {
                            replyDraft?.clientRequestId ?: java.util.UUID.randomUUID().toString()
                        }
                        var editingId by remember(root.id) { mutableStateOf<String?>(null) }
                        var editText by remember(root.id) { mutableStateOf("") }

                        Surface(
                            tonalElevation = if (isFocused) 4.dp else 1.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().testTag("artefacts.thread.${root.id}"),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = authorLabel(root.createdByActorNpub),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    AssistChip(
                                        onClick = { if (root.canResolve && !busy) onToggleResolve(root) },
                                        enabled = root.canResolve && !busy,
                                        label = { Text(if (root.isResolved) "Resolved" else "Open") },
                                        modifier = Modifier.testTag("artefacts.thread.status.${root.id}"),
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = root.createdAt.take(16).replace('T', ' '),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (thread.fromEarlierVersion) {
                                        val originLabel = thread.originVersionNumber?.let { "From v$it" } ?: "Earlier version"
                                        TextButton(
                                            onClick = { onJumpToVersion(thread.originVersionId) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.testTag("artefacts.thread.origin.${root.id}"),
                                        ) {
                                            Text("$originLabel · View", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                root.anchor?.let { anchor ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = anchor.targetSummary(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        when (matched) {
                                            true -> TextButton(
                                                onClick = { onFocusTarget(root.id); onDismiss() },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            ) { Text("Highlight", style = MaterialTheme.typography.labelSmall) }
                                            false -> Text(
                                                text = "Target detached in v$viewedVersionNumber",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.testTag("artefacts.thread.detached.${root.id}"),
                                            )
                                            null -> Unit
                                        }
                                    }
                                }

                                if (editingId == root.id) {
                                    OutlinedTextField(
                                        value = editText,
                                        onValueChange = { editText = it },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            enabled = !busy && editText.isNotBlank(),
                                            onClick = { onEditComment(root, editText.trim()); editingId = null },
                                        ) { Text("Save") }
                                        TextButton(onClick = { editingId = null }) { Text("Cancel") }
                                    }
                                } else {
                                    Text(root.body, style = MaterialTheme.typography.bodyMedium)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (root.canResolve) {
                                        TextButton(
                                            enabled = !busy,
                                            onClick = { onToggleResolve(root) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        ) { Text(if (root.isResolved) "Reopen" else "Resolve", style = MaterialTheme.typography.labelSmall) }
                                    }
                                    if (root.canEdit && editingId != root.id) {
                                        TextButton(
                                            enabled = !busy,
                                            onClick = { editingId = root.id; editText = root.body },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        ) { Text("Edit", style = MaterialTheme.typography.labelSmall) }
                                    }
                                    if (root.canDelete) {
                                        TextButton(
                                            enabled = !busy,
                                            onClick = { onDeleteComment(root) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        ) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
                                    }
                                }

                                if (thread.replies.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    thread.replies.forEach { reply ->
                                        Column(Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = authorLabel(reply.createdByActorNpub),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Text(
                                                    text = reply.createdAt.take(16).replace('T', ' '),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(reply.body, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }

                                if (permissions.canComment) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = replyText,
                                            onValueChange = {
                                                replyText = it
                                                onDraftChange(replyKey, it, root.id, null)
                                            },
                                            enabled = !busy,
                                            singleLine = false,
                                            maxLines = 3,
                                            placeholder = { Text("Reply…") },
                                            modifier = Modifier.weight(1f).testTag("artefacts.thread.replyInput.${root.id}"),
                                        )
                                        TextButton(
                                            enabled = !busy && replyText.isNotBlank(),
                                            onClick = { onSubmitReply(root.id, replyText.trim(), replyRequestId) },
                                            modifier = Modifier.testTag("artefacts.thread.replySend.${root.id}"),
                                        ) { Text("Reply") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}
