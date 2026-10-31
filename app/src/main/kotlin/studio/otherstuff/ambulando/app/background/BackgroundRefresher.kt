package studio.otherstuff.ambulando.app.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.sync.BackgroundRefreshReport
import studio.otherstuff.ambulando.core.sync.PendingSender
import studio.otherstuff.ambulando.core.sync.backgroundRefresh
import studio.otherstuff.ambulando.core.workspaces.WorkspaceCatalog
import studio.otherstuff.ambulando.core.workspaces.WorkspaceListing
import studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntimes

/**
 * What the app does with a window of background time that Android grants. Only enabled workspaces are touched, one after
 * another and each within its share of the budget; the starting workspace rotates between windows so none is starved. A
 * workspace's own runtime does the work: its partition, its scoped scheduler, its signer check. Unsent messages and changes
 * are sent only for a workspace whose access was revalidated and only while the identity is unlocked in memory; with it locked
 * the read-only credential refreshes reads (for the Tower it is pinned to) and nothing is sent.
 */
class BackgroundRefresher(
    private val catalog: LocalStore,
    private val runtimes: WorkspaceRuntimes,
    private val identity: IdentityService,
) {
    suspend fun refresh(budgetMillis: Long): Boolean {
        val enabled = enabled()
        if (enabled.isEmpty()) return false
        val unlocked = identity.state() is IdentityState.Unlocked
        val rotation = (catalog.reader.state(ROTATION_KEY)?.toIntOrNull() ?: 0).mod(enabled.size)
        catalog.writer.setState(ROTATION_KEY, ((rotation + 1) % enabled.size).toString())
        val ordered = enabled.drop(rotation) + enabled.take(rotation)
        val started = System.nanoTime()
        var workspaces = 0; var events = 0; var gaps = 0; var sent = false
        var result = BackgroundRefreshReport.Result.COMPLETED; var error: String? = null
        for ((index, listing) in ordered.withIndex()) {
            val remaining = budgetMillis - (System.nanoTime() - started) / 1_000_000
            if (remaining <= 0) { result = BackgroundRefreshReport.Result.EXPIRED; break }
            val share = remaining / (ordered.size - index)
            val runtime = try { runtimes.enable(listing.connection, listing.workspace) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (result == BackgroundRefreshReport.Result.COMPLETED) { result = BackgroundRefreshReport.Result.FAILED; error = e.message }
                continue
            }
            withTimeoutOrNull(share) { runtime.awaitStarted() }
            val senders = if (unlocked && runtime.canSend) listOf(PendingSender { runtime.outbox.kick(it) }, PendingSender { runtime.writes.kick(it) }) else emptyList()
            val report = runtime.sync.backgroundRefresh(runtime.connection, listOf(runtime.workspace), senders, maxOf(1_000, share))
            if (report.result != BackgroundRefreshReport.Result.SKIPPED_LOCKED) workspaces += report.workspaces
            events += report.events; gaps += report.gapRecoveries; sent = sent || report.sentPending
            when {
                report.result == BackgroundRefreshReport.Result.EXPIRED -> result = BackgroundRefreshReport.Result.EXPIRED
                report.result == BackgroundRefreshReport.Result.FAILED && result == BackgroundRefreshReport.Result.COMPLETED -> { result = BackgroundRefreshReport.Result.FAILED; error = report.error }
                report.result == BackgroundRefreshReport.Result.SKIPPED_LOCKED && workspaces == 0 -> result = BackgroundRefreshReport.Result.SKIPPED_LOCKED
            }
        }
        val now = java.time.Instant.now().toString()
        catalog.writer.setState(BackgroundRefreshReport.STATE_KEY, BackgroundRefreshReport.encode(BackgroundRefreshReport(result, now, now, workspaces, events, gaps, sent, error)))
        return result == BackgroundRefreshReport.Result.COMPLETED
    }

    suspend fun finishSending(budgetMillis: Long): Boolean {
        if (identity.state() !is IdentityState.Unlocked) return false
        for (listing in enabled()) {
            val runtime = runtimes.enable(listing.connection, listing.workspace)
            withTimeoutOrNull(budgetMillis / 2) { runtime.awaitStarted() }
            runtime.kickSenders()
        }
        return withTimeoutOrNull(budgetMillis) { runtimes.waitUntilIdle(); true } ?: false
    }

    private suspend fun enabled(): List<WorkspaceListing> {
        val signer = identity.state().npub ?: return emptyList()
        val listings = WorkspaceCatalog.listings(catalog, signer).filter { !it.workspace.accessRemoved }
        val prefs = WorkspaceCatalog.preferences(catalog) ?: WorkspaceCatalog.initialPreferences(catalog, listings)
        return prefs.sorted(listings.map { it.id }).mapNotNull { id -> listings.firstOrNull { it.id == id && id in prefs.enabled } }
    }

    private companion object { const val ROTATION_KEY = "background.rotation" }
}
