package studio.otherstuff.ambulando.core.sync

import studio.otherstuff.ambulando.core.docs.DocumentBody
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.ChangeKey
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.StoreTable
import studio.otherstuff.ambulando.core.store.isChannelAccessRemoved
import studio.otherstuff.ambulando.core.store.file
import studio.otherstuff.ambulando.core.store.doc
import studio.otherstuff.ambulando.core.store.replaceReactions
import studio.otherstuff.ambulando.core.store.saveDocBody
import studio.otherstuff.ambulando.core.store.saveListSync
import studio.otherstuff.ambulando.core.store.syncableChannels
import studio.otherstuff.ambulando.core.store.task
import studio.otherstuff.ambulando.core.store.thread
import studio.otherstuff.ambulando.core.store.upsertComments
import studio.otherstuff.ambulando.core.store.upsertDocs
import studio.otherstuff.ambulando.core.store.upsertFiles
import studio.otherstuff.ambulando.core.store.upsertFolders
import studio.otherstuff.ambulando.core.store.upsertTasks
import studio.otherstuff.ambulando.core.surfaces.TaskTreeWrites
import studio.otherstuff.ambulando.core.store.upsertThreads
import studio.otherstuff.ambulando.core.store.upsertViewStates
import studio.otherstuff.ambulando.core.chat.thread
import studio.otherstuff.ambulando.core.transport.CommentRecord
import studio.otherstuff.ambulando.core.transport.FileRecord
import studio.otherstuff.ambulando.core.transport.ReactionRecord
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.ViewStateRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.channelDocs
import studio.otherstuff.ambulando.core.transport.channelFiles
import studio.otherstuff.ambulando.core.transport.channelFolders
import studio.otherstuff.ambulando.core.transport.channelTasks
import studio.otherstuff.ambulando.core.transport.doc
import studio.otherstuff.ambulando.core.transport.docBody
import studio.otherstuff.ambulando.core.transport.docComments
import studio.otherstuff.ambulando.core.transport.fileObject
import studio.otherstuff.ambulando.core.transport.reactions
import studio.otherstuff.ambulando.core.transport.storageContent
import studio.otherstuff.ambulando.core.transport.task
import studio.otherstuff.ambulando.core.transport.taskComments
import studio.otherstuff.ambulando.core.transport.viewStates
import java.io.File

// Read paths for tasks, documents, files, the inbox and reactions. Like chat catch-up, every request is a keyed
// `SyncEngine` job (bounded concurrency, coalescing, cancellation, backoff) and results land in SQLite; screens only
// observe the store. Parsing happens inside the job, never on the main thread.
//
// Per-channel list jobs run only for channels sync may read (`syncableChannels`): a channel flagged as no longer
// visible, or in a flagged scope, is never requested again until Tower lists it again.

internal suspend fun WorkspaceSync.channelIds(workspace: WorkspaceRecord, scopeId: String?): List<String> =
    store.reader.syncableChannels(workspace.id).filter { scopeId == null || it.scopeId == scopeId }.map { it.id }

// MARK: Tasks

/** One job per channel (the task list route is per channel). */
suspend fun WorkspaceSync.refreshTasks(
    connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String? = null, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    val channels = runCatching { channelIds(workspace, scopeId) }.getOrNull() ?: return
    for (channel in channels) {
        engine.enqueueRead(WorkspaceSync.tasksKey(workspace.id, channel), priority, force) {
            identity.requireReader(connection.signerNpub)
            val tasks = api(connection).channelTasks(workspace.id, workspace.appNpub, channel)
            checkCancelled()
            store.writer.upsertTasks(workspace.id, tasks, completeChannel = channel)
            store.writer.saveListSync(workspace.id, WorkspaceSync.tasksKey(workspace.id, channel), complete = true)
        }
    }
}

/** Task detail: the task and its comments (user priority). */
suspend fun WorkspaceSync.refreshTask(
    connection: ConnectionRecord, workspace: WorkspaceRecord, taskId: String, priority: SyncPriority = SyncPriority.USER, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.taskKey(workspace.id, taskId), priority, force) {
        val cached = store.reader.task(workspace.id, taskId)
        if (workspace.accessRemoved || cached?.accessRemoved == true || cached?.channelId?.let { store.reader.isChannelAccessRemoved(workspace.id, it) } == true) return@enqueueRead
        identity.requireReader(connection.signerNpub)
        val api = api(connection)
        val task = api.task(workspace.id, workspace.appNpub, taskId)
        store.writer.upsertTasks(workspace.id, listOf(task))
        val comments = api.taskComments(workspace.id, workspace.appNpub, taskId)
        checkCancelled()
        store.writer.upsertComments(workspace.id, comments, complete = CommentRecord.Parent.TASK to task.id)
    }
}

/**
 * One parent's direct-child list (Tower's paged `/tasks/:id/relationships`), stored with its membership and read state.
 * Descendants are not read here: each expanded child enqueues its own job. A tracked list is refreshed when its task or one
 * of its children changes, on open, on identity unlock and by the detail screen's foreground fallback.
 */
suspend fun WorkspaceSync.refreshTaskRelationships(
    connection: ConnectionRecord, workspace: WorkspaceRecord, taskId: String, priority: SyncPriority = SyncPriority.USER, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.taskRelationshipsKey(workspace.id, taskId), priority, force) {
        if (workspace.accessRemoved) return@enqueueRead
        identity.requireReader(connection.signerNpub)
        TaskTreeWrites(store, identity, transport).loadRelationships(connection, workspace, taskId)
    }
}

// MARK: Documents

suspend fun WorkspaceSync.refreshDocs(
    connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String? = null, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    val channels = runCatching { channelIds(workspace, scopeId) }.getOrNull() ?: return
    for (channel in channels) {
        engine.enqueueRead(WorkspaceSync.docsKey(workspace.id, channel), priority, force) {
            identity.requireReader(connection.signerNpub)
            val page = api(connection).channelDocs(workspace.id, workspace.appNpub, channel)
            checkCancelled()
            // A possibly truncated page is never used to flag local documents.
            store.writer.upsertDocs(workspace.id, page.items, completeChannel = if (page.complete) channel else null)
            store.writer.saveListSync(workspace.id, WorkspaceSync.docsKey(workspace.id, channel), complete = page.complete)
        }
    }
}

/** Opening a document fetches its canonical body (verified) and comments. */
suspend fun WorkspaceSync.refreshDoc(
    connection: ConnectionRecord, workspace: WorkspaceRecord, docId: String, priority: SyncPriority = SyncPriority.USER, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.docKey(workspace.id, docId), priority, force) {
        val cached = store.reader.doc(workspace.id, docId)
        if (workspace.accessRemoved || cached?.accessRemoved == true || cached?.channelId?.let { store.reader.isChannelAccessRemoved(workspace.id, it) } == true) return@enqueueRead
        identity.requireReader(connection.signerNpub)
        val api = api(connection)
        val (doc, body) = api.docBody(workspace.id, workspace.appNpub, docId)
        checkCancelled()
        store.writer.saveDocBody(body, doc, searchText = DocumentBody(body.bytes, body.contentType).markdown)
        val comments = api.docComments(workspace.id, workspace.appNpub, docId)
        checkCancelled()
        store.writer.upsertComments(workspace.id, comments, complete = CommentRecord.Parent.DOC to doc.id)
    }
}

// MARK: Files

suspend fun WorkspaceSync.refreshFiles(
    connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String? = null, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    val channels = runCatching { channelIds(workspace, scopeId) }.getOrNull() ?: return
    for (channel in channels) {
        engine.enqueueRead(WorkspaceSync.filesKey(workspace.id, channel), priority, force) {
            identity.requireReader(connection.signerNpub)
            val api = api(connection)
            val files = api.channelFiles(workspace.id, workspace.appNpub, channel)
            checkCancelled()
            store.writer.upsertFiles(workspace.id, files.items, completeChannel = if (files.complete) channel else null)
            val folders = api.channelFolders(workspace.id, workspace.appNpub, channel)
            checkCancelled()
            store.writer.upsertFolders(workspace.id, channel, folders.items, complete = folders.complete)
            store.writer.saveListSync(workspace.id, WorkspaceSync.filesKey(workspace.id, channel), complete = files.complete)
        }
    }
}

// MARK: Inbox

/** View states (unread and attention), then the few records they point at that are not stored yet. */
suspend fun WorkspaceSync.refreshInbox(
    connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    engine.enqueueRead(WorkspaceSync.inboxKey(workspace.id), priority, force) {
        identity.requireReader(connection.signerNpub)
        val api = api(connection)
        val w = workspace.id
        val states = api.viewStates(w, workspace.appNpub)
        checkCancelled()
        store.writer.upsertViewStates(w, states, complete = true)
        var fetched = 0
        for (state in states) {
            if (!(state.unread || state.needsAttention) || fetched >= WorkspaceSync.INBOX_HYDRATION_LIMIT) continue
            checkCancelled()
            when (state.resourceType) {
                ViewStateRecord.ResourceType.TASK -> {
                    if (store.reader.task(w, state.resourceId) != null) continue
                    fetched += 1
                    readOrNull { api.task(w, workspace.appNpub, state.resourceId) }?.let { store.writer.upsertTasks(w, listOf(it)) }
                }
                ViewStateRecord.ResourceType.DOCUMENT -> {
                    if (store.reader.doc(w, state.resourceId) != null) continue
                    fetched += 1
                    readOrNull { api.doc(w, workspace.appNpub, state.resourceId) }?.let { store.writer.upsertDocs(w, listOf(it)) }
                }
                ViewStateRecord.ResourceType.THREAD -> {
                    if (store.reader.thread(w, state.resourceId) != null) continue
                    fetched += 1
                    readOrNull { api.thread(w, workspace.appNpub, state.resourceId) }?.let { store.writer.upsertThreads(w, listOf(it)) }
                }
            }
        }
    }
}

// MARK: Reactions

/** Reactions for the newest messages of one conversation (`scope` is a channel or thread id). */
suspend fun WorkspaceSync.refreshReactions(
    connection: ConnectionRecord, workspace: WorkspaceRecord, scope: String, messageIds: List<String>, priority: SyncPriority = SyncPriority.VISIBLE,
) {
    val targets = messageIds.takeLast(WorkspaceSync.REACTION_TARGET_LIMIT)
    if (targets.isEmpty()) return
    engine.enqueueRead(WorkspaceSync.reactionsKey(workspace.id, scope), priority) {
        identity.requireReader(connection.signerNpub)
        val api = api(connection)
        val all = ArrayList<ReactionRecord>()
        val done = ArrayList<String>()
        for (target in targets) {
            checkCancelled()
            val list = readOrNull { api.reactions(workspace.id, workspace.appNpub, "message", target) } ?: continue
            all += list
            done += target
        }
        store.writer.replaceReactions(workspace.id, done, all)
    }
}

/** A read whose failure only skips that record (cancellation still propagates). */
internal suspend fun <T> readOrNull(block: suspend () -> T): T? = try {
    block()
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c
} catch (_: Exception) {
    checkCancelled()
    null
}

// MARK: Downloads

/**
 * Downloaded file content, content-addressed under the app's cache directory so the system may purge it. A cached copy
 * is used only when its bytes still hash to the file's current sha256.
 */
class FileCache(val root: File) {
    companion object {
        private val UNSAFE = Regex("[/\\\\:\\x00-\\x1f]")

        fun safeName(name: String): String {
            val cleaned = name.replace(UNSAFE, "_").trim()
            return cleaned.ifEmpty { "file" }.take(120)
        }
    }

    fun file(record: FileRecord): File {
        val version = record.sha256?.take(16) ?: "v${record.rowVersion}"
        return File(File(File(root, record.workspaceId), "${record.id}-$version"), safeName(record.displayName))
    }

    fun cached(record: FileRecord): File? {
        val f = file(record)
        if (!f.exists()) return null
        val sha = record.sha256
        if (sha != null && runCatching { Sha256Hex.ofFile(f) }.getOrNull() != sha) return null
        return f
    }

    internal fun store(bytes: ByteArray, record: FileRecord): File {
        val target = file(record)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) { target.delete(); if (!temp.renameTo(target)) throw java.io.IOException("cache write failed") }
        return target
    }

    fun attachment(workspaceId: String, objectId: String): File? = attachmentFile(workspaceId, objectId).takeIf { it.exists() }

    internal fun attachmentFile(workspaceId: String, objectId: String): File = File(File(File(root, workspaceId), "attachments"), objectId)

    /** Removes cached content of one workspace (disconnect, workspace removal). */
    fun purge(workspaceId: String) { File(root, workspaceId).deleteRecursively() }
}

/**
 * User-initiated download through the typed object route; the verified bytes land in the cache. (Unsigned
 * object-store downloads through a background transfer seam are not wired on Android yet: the typed route is always
 * used.)
 */
suspend fun WorkspaceSync.download(connection: ConnectionRecord, workspace: WorkspaceRecord, file: FileRecord, cache: FileCache) {
    engine.enqueueRead(WorkspaceSync.downloadKey(workspace.id, file.id), SyncPriority.USER, force = true) {
        identity.requireReader(connection.signerNpub)
        if (workspace.accessRemoved || file.accessRemoved || store.reader.isChannelAccessRemoved(workspace.id, file.channelId)) return@enqueueRead
        val obj = api(connection).fileObject(workspace.id, workspace.appNpub, file.id)
        checkCancelled()
        if (file.sha256 != null && obj.sha256 != file.sha256) throw studio.otherstuff.ambulando.core.transport.FileTransferError.Integrity
        cache.store(obj.bytes, file)
        store.writer.touch(setOf(ChangeKey(StoreTable.FILES, workspace.id)))
    }
}

/** Chat attachment bytes (signed `GET /api/v4/storage/:id/content` on the pinned origin), cached by object id. */
suspend fun WorkspaceSync.downloadAttachment(connection: ConnectionRecord, workspace: WorkspaceRecord, objectId: String, cache: FileCache) {
    engine.enqueueRead(WorkspaceSync.attachmentKey(workspace.id, objectId), SyncPriority.VISIBLE) {
        val target = cache.attachmentFile(workspace.id, objectId)
        if (target.exists()) return@enqueueRead
        identity.requireSigner(connection.signerNpub)
        val bytes = api(connection).storageContent(objectId)
        checkCancelled()
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) throw TowerError.Malformed("attachment")
        store.writer.touch(setOf(ChangeKey(StoreTable.MESSAGES, workspace.id)))
    }
}
