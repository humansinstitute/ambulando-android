package studio.otherstuff.ambulando.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.syncableWorkspaces
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import java.time.Instant

/**
 * What one opportunistic background refresh did. Persisted for the Settings screen (`app_state`
 * [STATE_KEY]), so background behaviour is visible rather than assumed. Times are ISO-8601 instants.
 */
@Serializable
data class BackgroundRefreshReport(
    val result: Result,
    val startedAt: String,
    val finishedAt: String,
    val workspaces: Int = 0,
    val events: Int = 0,
    val gapRecoveries: Int = 0,
    /** Unsent messages/changes were retried (only when the identity was unlocked in memory). */
    val sentPending: Boolean = false,
    val error: String? = null,
) {
    @Serializable
    enum class Result {
        /**
         * Nothing could be signed: the identity is locked and background refresh is not enabled (or was revoked by a manual
         * lock). No request was made.
         */
        @SerialName("skippedLocked") SKIPPED_LOCKED,
        @SerialName("skippedNoConnection") SKIPPED_NO_CONNECTION,
        @SerialName("completed") COMPLETED,
        /** The OS ended the window (or the budget ran out) first; jobs were cancelled, progress so far is kept. */
        @SerialName("expired") EXPIRED,
        @SerialName("failed") FAILED,
    }

    companion object {
        const val STATE_KEY = "background.last-refresh"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(report: BackgroundRefreshReport): String = json.encodeToString(serializer(), report)
        fun decode(text: String?): BackgroundRefreshReport? = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The background refresh pipeline: the same keyed, bounded jobs as the foreground, at background priority.
 *
 * - With the identity unlocked in memory (the process kept it) everything runs, including unsent messages and changes
 *   ([senders] are kicked).
 * - With it locked, only reads run, and only if the user enabled background refresh (the policy-limited credential).
 *   Unsent items wait for the PIN.
 * - When [budgetMillis] runs out, or the calling coroutine is cancelled (the OS ending the window), every engine job is
 *   cancelled and the result is `EXPIRED`; cursors and pages already stored are kept.
 *
 * At most [WorkspaceSync.BACKGROUND_WORKSPACE_LIMIT] workspaces are caught up, [WorkspaceSync.BACKGROUND_PAGE_BUDGET]
 * event pages each; workspaces flagged as no longer visible are skipped. The report is saved to `app_state` before it
 * is returned (also when the caller was cancelled).
 */
suspend fun WorkspaceSync.backgroundRefresh(
    connection: ConnectionRecord, workspaces: List<WorkspaceRecord>, senders: List<PendingSender> = emptyList(), budgetMillis: Long,
): BackgroundRefreshReport {
    val started = Instant.now().toString()
    try {
        identity.requireReader(connection.signerNpub)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        val report = BackgroundRefreshReport(BackgroundRefreshReport.Result.SKIPPED_LOCKED, started, Instant.now().toString())
        saveReport(report)
        return report
    }
    val targets = workspaces.filter { !it.accessRemoved }.take(WorkspaceSync.BACKGROUND_WORKSPACE_LIMIT)
    val before = targets.associate { it.id to live.feed(it.id).status.value }
    var sentPending = false
    val finished = try {
        for (workspace in targets) catchUpEvents(connection, workspace, WorkspaceSync.BACKGROUND_PAGE_BUDGET, SyncPriority.BACKGROUND)
        if (identity.state() is IdentityState.Unlocked) {
            sentPending = true
            for (sender in senders) sender.kick(connection)
        }
        (withTimeoutOrNull(budgetMillis) { engine.waitUntilIdle(); true } ?: false) && currentCoroutineContext().isActive
    } catch (_: CancellationException) {
        false
    }
    return withContext(NonCancellable) {
        var result = BackgroundRefreshReport.Result.COMPLETED
        var error: String? = null
        if (!finished) {
            engine.cancelAll()
            result = BackgroundRefreshReport.Result.EXPIRED
        }
        var events = 0
        var gaps = 0
        for (workspace in targets) {
            val after = live.feed(workspace.id).status.value
            val previous = before[workspace.id] ?: LiveStatus()
            events += after.eventsApplied - previous.eventsApplied
            gaps += after.gapRecoveries - previous.gapRecoveries
            val lastError = after.lastError
            if (result == BackgroundRefreshReport.Result.COMPLETED && lastError != null && lastError != previous.lastError) {
                result = BackgroundRefreshReport.Result.FAILED
                error = lastError
            }
        }
        val report = BackgroundRefreshReport(result, started, Instant.now().toString(), targets.size, events, gaps, sentPending, error)
        saveReport(report)
        report
    }
}

/**
 * The single entry point for the platform scheduler (WorkManager on Android): refreshes [connection] within
 * [budgetMillis], the selected workspace first, then the other workspaces sync may read. No connection is reported as
 * `SKIPPED_NO_CONNECTION` (and saved).
 */
suspend fun WorkspaceSync.backgroundRefresh(
    connection: ConnectionRecord?, selectedWorkspaceId: String?, senders: List<PendingSender> = emptyList(), budgetMillis: Long,
): BackgroundRefreshReport {
    if (connection == null) {
        val now = Instant.now().toString()
        return BackgroundRefreshReport(BackgroundRefreshReport.Result.SKIPPED_NO_CONNECTION, now, now).also { saveReport(it) }
    }
    val stored = store.reader.syncableWorkspaces(connection.id)
    val ordered = stored.filter { it.id == selectedWorkspaceId } + stored.filter { it.id != selectedWorkspaceId }
    return backgroundRefresh(connection, ordered, senders, budgetMillis)
}

private suspend fun WorkspaceSync.saveReport(report: BackgroundRefreshReport) {
    runCatching { withContext(NonCancellable) { store.writer.setState(BackgroundRefreshReport.STATE_KEY, BackgroundRefreshReport.encode(report)) } }
}
