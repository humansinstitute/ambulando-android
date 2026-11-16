package studio.otherstuff.ambulando.core.sync

import studio.otherstuff.ambulando.core.transport.LocalStages
import studio.otherstuff.ambulando.core.transport.LocalStages.Stage
import studio.otherstuff.ambulando.core.transport.LocalStages.Result

/** Called only for the snapshot observed by the active workspace UI, never for shared/background status.
 * Keys are inspected transiently; only a finite category crosses into the bounded local observation sink.
 * One event per category per observed failed snapshot keeps volume independent of the number of failed jobs.
 */
fun SyncScheduler.observeActiveFailures(observed: SyncSnapshot) {
    if (this !is ScopedSyncScheduler || observed.errors.isEmpty()) return
    val categories = observed.errors.keys.map(::activeFailureCategory).toSet()
    val observation = LocalStages.next()
    categories.sortedBy { it.ordinal }.forEach { LocalStages.emit(observation, it, Result.FAILED) }
}

/** Closed vocabulary from the core factories and core/app scheduling sites. Never returns key data.
 * This is observation only; diagnostic categories grant no recovery/replay authority.
 */
internal fun activeFailureCategory(key: String): Stage = when {
    key == "resume-access" -> Stage.ACTIVE_FAILED_RESUME_ACCESS
    key.startsWith("discover:") -> Stage.ACTIVE_FAILED_DISCOVER
    key.startsWith("scopes:") -> Stage.ACTIVE_FAILED_SCOPES
    key.startsWith("profiles:") -> Stage.ACTIVE_FAILED_PROFILES
    key.startsWith("attention-types:") -> Stage.ACTIVE_FAILED_ATTENTION
    key.startsWith("notifications:") -> Stage.ACTIVE_FAILED_NOTIFICATIONS
    key.startsWith("pins:") -> Stage.ACTIVE_FAILED_PINS
    key.startsWith("messages:") -> Stage.ACTIVE_FAILED_MESSAGES
    key.startsWith("reactions:") -> Stage.ACTIVE_FAILED_REACTIONS
    key.startsWith("thread:") -> Stage.ACTIVE_FAILED_THREAD
    key.startsWith("directory:") -> Stage.ACTIVE_FAILED_DIRECTORY
    key.startsWith("channels:") -> Stage.ACTIVE_FAILED_CHANNELS
    key.startsWith("tasks:") -> Stage.ACTIVE_FAILED_TASKS
    key.startsWith("task:") -> Stage.ACTIVE_FAILED_TASK
    key.startsWith("task-relationships:") -> Stage.ACTIVE_FAILED_TASK_RELATIONSHIPS
    key.startsWith("docs:") -> Stage.ACTIVE_FAILED_DOCS
    key.startsWith("doc:") -> Stage.ACTIVE_FAILED_DOC
    key.startsWith("files:") -> Stage.ACTIVE_FAILED_FILES
    key.startsWith("inbox:") -> Stage.ACTIVE_FAILED_INBOX
    key.startsWith("events:") -> Stage.ACTIVE_FAILED_EVENTS
    key.startsWith("scope-order:") -> Stage.ACTIVE_FAILED_SCOPE_ORDER
    key.startsWith("scope-collapsed:") -> Stage.ACTIVE_FAILED_COLLAPSED
    key.startsWith("notifications-archive:") -> Stage.ACTIVE_FAILED_NOTIFICATIONS_ARCHIVE
    key.startsWith("notification-item:") -> Stage.ACTIVE_FAILED_NOTIFICATION_ITEM
    key.startsWith("notification-prefs:") -> Stage.ACTIVE_FAILED_NOTIFICATION_PREFS
    key.startsWith("context:") -> Stage.ACTIVE_FAILED_CONTEXT
    key.startsWith("context-refs:") -> Stage.ACTIVE_FAILED_CONTEXT_REFS
    key.startsWith("canvases:") -> Stage.ACTIVE_FAILED_CANVASES
    key.startsWith("canvas:") -> Stage.ACTIVE_FAILED_CANVAS
    key.startsWith("board:") -> Stage.ACTIVE_FAILED_BOARD
    key.startsWith("agents:") -> Stage.ACTIVE_FAILED_AGENTS
    key.startsWith("agent-activity:") -> Stage.ACTIVE_FAILED_AGENT_ACTIVITY
    key.startsWith("toolbox:") -> Stage.ACTIVE_FAILED_TOOLBOX
    key.startsWith("thread-record:") -> Stage.ACTIVE_FAILED_THREAD_RECORD
    key.startsWith("download:") -> Stage.ACTIVE_FAILED_DOWNLOAD
    key.startsWith("attachment:") -> Stage.ACTIVE_FAILED_ATTACHMENT
    key.startsWith("voice:") -> Stage.ACTIVE_FAILED_VOICE
    key.startsWith("admin:") -> Stage.ACTIVE_FAILED_ADMIN
    key.startsWith("manage:") -> Stage.ACTIVE_FAILED_MANAGE
    key.startsWith("personal-agents:") -> Stage.ACTIVE_FAILED_PERSONAL_AGENTS
    key.startsWith("writes:") -> Stage.ACTIVE_FAILED_WRITES
    key.startsWith("outbox:") -> Stage.ACTIVE_FAILED_OUTBOX
    key.startsWith("export:") -> Stage.ACTIVE_FAILED_EXPORT
    key.startsWith("doc-move:") -> Stage.ACTIVE_FAILED_DOC_MOVE
    key.startsWith("doc-save:") -> Stage.ACTIVE_FAILED_DOC_SAVE
    key.startsWith("doc-lease:") -> Stage.ACTIVE_FAILED_DOC_LEASE
    key.startsWith("doc-inspect:") -> Stage.ACTIVE_FAILED_DOC_INSPECT
    key.startsWith("doc-latest:") -> Stage.ACTIVE_FAILED_DOC_LATEST
    key.startsWith("doc-versions:") -> Stage.ACTIVE_FAILED_DOC_VERSIONS
    key.startsWith("paste:") -> Stage.ACTIVE_FAILED_PASTE
    key.startsWith("branch:") -> Stage.ACTIVE_FAILED_BRANCH
    key.startsWith("rename-thread:") -> Stage.ACTIVE_FAILED_RENAME_THREAD
    key.startsWith("naplet-html:") -> Stage.ACTIVE_FAILED_NAPLET_HTML
    key.startsWith("pin-order:") -> Stage.ACTIVE_FAILED_PIN_ORDER
    key.startsWith("canvas-create:") -> Stage.ACTIVE_FAILED_CANVAS_CREATE
    key.startsWith("board-versions:") -> Stage.ACTIVE_FAILED_BOARD_VERSIONS
    key.startsWith("board-read:") -> Stage.ACTIVE_FAILED_BOARD_READ
    key.startsWith("board-publish:") -> Stage.ACTIVE_FAILED_BOARD_PUBLISH
    key.startsWith("board-rename:") -> Stage.ACTIVE_FAILED_BOARD_RENAME
    key.startsWith("task-tree:") -> Stage.ACTIVE_FAILED_TASK_TREE
    key.startsWith("tree-create:") -> Stage.ACTIVE_FAILED_TREE_CREATE
    key.startsWith("tree-view:") -> Stage.ACTIVE_FAILED_TREE_VIEW
    key.startsWith("canvas-edit:") -> Stage.ACTIVE_FAILED_CANVAS_EDIT
    else -> Stage.ACTIVE_FAILED_OTHER
}
