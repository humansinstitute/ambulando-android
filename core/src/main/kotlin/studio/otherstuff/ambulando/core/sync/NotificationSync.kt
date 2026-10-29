package studio.otherstuff.ambulando.core.sync

import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.NotificationSyncState
import studio.otherstuff.ambulando.core.store.applyNotificationChanges
import studio.otherstuff.ambulando.core.store.markNotificationBackend
import studio.otherstuff.ambulando.core.store.notificationCounts
import studio.otherstuff.ambulando.core.store.notificationRows
import studio.otherstuff.ambulando.core.store.notificationSync
import studio.otherstuff.ambulando.core.store.removeNotificationRow
import studio.otherstuff.ambulando.core.store.acknowledgeNotificationItem
import studio.otherstuff.ambulando.core.store.replaceNotificationInbox
import studio.otherstuff.ambulando.core.store.resetNotificationCursor
import studio.otherstuff.ambulando.core.store.beginNotificationArchive
import studio.otherstuff.ambulando.core.store.beginNotificationSnapshot
import studio.otherstuff.ambulando.core.store.completeNotificationArchive
import studio.otherstuff.ambulando.core.store.completeNotificationSnapshot
import studio.otherstuff.ambulando.core.store.notificationArchiveProgress
import studio.otherstuff.ambulando.core.store.notificationSnapshotProgress
import studio.otherstuff.ambulando.core.store.saveArchivedNotificationPage
import studio.otherstuff.ambulando.core.store.saveNotificationSnapshotPage
import studio.otherstuff.ambulando.core.transport.NotificationItem
import studio.otherstuff.ambulando.core.transport.NotificationView
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.notificationChanges
import studio.otherstuff.ambulando.core.transport.notificationCounts
import studio.otherstuff.ambulando.core.transport.notificationItem
import studio.otherstuff.ambulando.core.transport.notificationItems
import studio.otherstuff.ambulando.core.transport.notificationCategories
import studio.otherstuff.ambulando.core.transport.notificationRules
import studio.otherstuff.ambulando.core.transport.putNotificationRules
import studio.otherstuff.ambulando.core.transport.NOTIFICATION_CLIENT_APP_ID
import studio.otherstuff.ambulando.core.transport.NotificationCategoryInfo
import studio.otherstuff.ambulando.core.transport.NotificationRule
import studio.otherstuff.ambulando.core.transport.NotificationRuleWrite
import studio.otherstuff.ambulando.core.transport.NotificationRulesResult

// The notification inbox read path. Like every other read, it is one keyed `SyncEngine` job (coalescing, bounded,
// cancellable, backed off); screens observe the store and never call the network. The event stream carries only a
// content-free hint ("notification_item"): the hint schedules this job, and `changes` is the authority.
//
// Recovery protocol (snapshot, then replay):
// 1. Cold start (nothing stored, or Tower answered 410 `reset_required`): page `items?view=inbox` newest first. The first
//    page's `head_seq` is `S`, read in the same Tower snapshot as that page.
// 2. Store the listed items atomically and set `local_seq = S` (never a later page's head).
// 3. Replay `changes?after_seq=local_seq` page by page. Each page is applied idempotently and `local_seq` moves to that
//    page's `through_seq` in the same transaction, so a crash or cancellation resumes exactly where it stopped.
// Tower may redeliver; nothing here assumes exactly-once.

/** Schedules the inbox sync of one workspace (a hint, a foreground refresh, a reseed or a manual pull to refresh). */
suspend fun WorkspaceSync.refreshNotifications(
    connection: ConnectionRecord, workspace: WorkspaceRecord, priority: SyncPriority = SyncPriority.VISIBLE, force: Boolean = false,
) {
    if (workspace.accessRemoved) return
    engine.enqueueRead(WorkspaceSync.notificationsKey(workspace.id), priority, force) { runNotifications(connection, workspace) }
}

/** The `notifications:<w>` job. */
internal suspend fun WorkspaceSync.runNotifications(connection: ConnectionRecord, workspace: WorkspaceRecord) {
    identity.requireReader(connection.signerNpub)
    val w = workspace.id
    val api = api(connection)
    try {
        var sync = store.reader.notificationSync(connection.id, w)
        if (sync == null || sync.state != NotificationSyncState.Phase.READY) {
            // Not complete (continues in the next job, already scheduled) means nothing is replayed yet.
            if (!coldStartNotifications(connection, workspace)) return
            sync = store.reader.notificationSync(connection.id, w)
        }
        var local = sync?.localSeq ?: 0L
        var rules = sync?.rulesRevision
        var pages = 0
        while (pages < WorkspaceSync.NOTIFICATION_REPLAY_BUDGET) {
            checkCancelled()
            val page = try {
                api.notificationChanges(w, workspace.appNpub, local, rulesRevision = rules)
            } catch (e: TowerError.Http) {
                if (e.status != 410) throw e
                // The cursor is older than Tower keeps tombstones for, ahead of its head after a restore, or the person's
                // rules changed (what this app may show changed): start over from a fresh snapshot.
                store.writer.resetNotificationCursor(connection.id, w)
                if (!coldStartNotifications(connection, workspace)) return
                store.reader.notificationSync(connection.id, w).let { local = it?.localSeq ?: 0L; rules = it?.rulesRevision }
                pages += 1
                continue
            }
            checkCancelled()
            if (page.rulesRevision != null && rules != null && page.rulesRevision != rules) {
                // Tower answered under other rules without a 410: this page is not comparable with the snapshot. Same recovery.
                store.writer.resetNotificationCursor(connection.id, w)
                if (!coldStartNotifications(connection, workspace)) return
                store.reader.notificationSync(connection.id, w).let { local = it?.localSeq ?: 0L; rules = it?.rulesRevision }
                pages += 1
                continue
            }
            store.writer.applyNotificationChanges(connection.id, w, page.changes, page.throughSeq)
            local = maxOf(local, page.throughSeq)
            pages += 1
            if (!page.hasMore) break
        }
        engine.recovered(WorkspaceSync.notificationsKey(w))
    } catch (e: TowerError.Http) {
        when (e.status) {
            // 403: membership/ACL lost. Nothing Tower sent for this inbox is kept.
            403 -> store.writer.markNotificationBackend(connection.id, w, NotificationSyncState.Phase.ACCESS_LOST)
            // 404/405/501: this Tower has no inbox routes (older build). Honest unavailable state, not an error loop.
            404, 405, 501 -> store.writer.markNotificationBackend(connection.id, w, NotificationSyncState.Phase.UNSUPPORTED)
            else -> throw e
        }
    }
}

/**
 * Steps 1 and 2 of the recovery protocol, resumable. Each job reads at most [WorkspaceSync.NOTIFICATION_PAGE_BUDGET] pages and
 * stores each page with its continuation cursor, the snapshot sequence `S` and a generation number, so a larger inbox (or a
 * job that is cancelled, or the app dying) resumes where it stopped instead of restarting at page one. The replay cursor
 * is set only when the last page is stored. Returns true when the snapshot completed in this job; false when it continues
 * in a following job (already scheduled) or was superseded by a newer generation.
 */
internal suspend fun WorkspaceSync.coldStartNotifications(connection: ConnectionRecord, workspace: WorkspaceRecord): Boolean {
    val w = workspace.id
    val api = api(connection)
    val resumed = store.reader.notificationSnapshotProgress(connection.id, w)
    var gen: Long
    var seq: Long?
    var cursor: String?
    var rules: Long? = null
    if (resumed?.seq != null) { gen = resumed.gen; seq = resumed.seq; cursor = resumed.cursor; rules = resumed.rulesRevision }
    else { gen = store.writer.beginNotificationSnapshot(connection.id, w); seq = null; cursor = null }
    val seen = HashSet<String>()
    var restarted = false
    var ruleRestarts = 0
    var pages = 0
    while (true) {
        checkCancelled()
        val first = seq == null
        val page = try {
            api.notificationItems(w, workspace.appNpub, NotificationView.INBOX, cursor)
        } catch (e: TowerError.Http) {
            // A continuation Tower no longer accepts (expired, or bound to something else): restart the generation once.
            if (e.status == 400 && cursor != null && !restarted) {
                restarted = true
                gen = store.writer.beginNotificationSnapshot(connection.id, w); seq = null; cursor = null; seen.clear(); pages = 0; rules = null
                continue
            }
            throw e
        }
        if (!first && page.rulesRevision != null && rules != null && page.rulesRevision != rules) {
            // The rules changed while the snapshot was being paged: its pages were computed under different filters. Start the
            // generation over (a few times at most; a Tower that keeps changing them is reported, not looped on).
            if (++ruleRestarts > 2) throw TowerError.Malformed("notifications rules revision")
            gen = store.writer.beginNotificationSnapshot(connection.id, w); seq = null; cursor = null; rules = null; seen.clear(); pages = 0
            continue
        }
        val s = seq ?: page.headSeq
        if (first) rules = page.rulesRevision
        val next = page.nextCursor
        if (next != null && !seen.add(next)) throw TowerError.Malformed("notifications cursor")
        if (!store.writer.saveNotificationSnapshotPage(connection.id, w, gen, page.items, next, s, if (first) page.inboxStartedAt else null, if (first) page.rulesRevision else null)) return false
        seq = s; cursor = next; pages += 1
        if (next == null) return store.writer.completeNotificationSnapshot(connection.id, w, gen, s)
        if (pages >= WorkspaceSync.NOTIFICATION_PAGE_BUDGET) {
            // Yield to the scheduler and continue from the stored cursor; nothing is declared complete.
            refreshNotifications(connection, workspace, SyncPriority.VISIBLE)
            return false
        }
    }
}

/** Lists the archive on demand (it is not hydrated by default), resumable like the inbox snapshot; `changes` keeps it current afterwards. */
suspend fun WorkspaceSync.loadArchivedNotifications(connection: ConnectionRecord, workspace: WorkspaceRecord) {
    engine.enqueueRead("notifications-archive:${workspace.id}", SyncPriority.USER, true) {
        identity.requireReader(connection.signerNpub)
        val w = workspace.id
        val api = api(connection)
        val resumed = store.reader.notificationArchiveProgress(connection.id, w)
        val gen = resumed?.gen ?: store.writer.beginNotificationArchive(connection.id, w)
        var cursor = resumed?.cursor
        val seen = HashSet<String>()
        var pages = 0
        while (true) {
            checkCancelled()
            val page = try {
                api.notificationItems(w, workspace.appNpub, NotificationView.ARCHIVED, cursor)
            } catch (e: TowerError.Http) {
                if (e.status == 400 && cursor != null) { store.writer.beginNotificationArchive(connection.id, w); loadArchivedNotifications(connection, workspace); return@enqueueRead }
                throw e
            }
            val next = page.nextCursor
            if (next != null && !seen.add(next)) throw TowerError.Malformed("archived notifications cursor")
            if (!store.writer.saveArchivedNotificationPage(connection.id, w, gen, page.items, next)) return@enqueueRead
            cursor = next; pages += 1
            if (next == null) { store.writer.completeNotificationArchive(connection.id, w, gen); return@enqueueRead }
            if (pages >= WorkspaceSync.NOTIFICATION_PAGE_BUDGET) { loadArchivedNotifications(connection, workspace); return@enqueueRead }
        }
    }
}

/**
 * Compares Tower's unread count with the local one. Returns true (after scheduling a cold start) when they differ with
 * nothing in flight, which means the local copy drifted; callers bound how often they ask.
 */
suspend fun WorkspaceSync.reconcileNotificationCounts(connection: ConnectionRecord, workspace: WorkspaceRecord): Boolean {
    val sync = store.reader.notificationSync(connection.id, workspace.id)
    if (sync?.state != NotificationSyncState.Phase.READY) return false
    val server = api(connection).notificationCounts(workspace.id, workspace.appNpub)
    if (server.rulesRevision != null && sync.rulesRevision != null && server.rulesRevision != sync.rulesRevision) {
        // The person's rules changed since this copy was taken (another device): what is shown no longer matches what counts.
        store.writer.resetNotificationCursor(connection.id, workspace.id)
        refreshNotifications(connection, workspace, SyncPriority.VISIBLE, force = true)
        return true
    }
    val rows = store.reader.notificationRows(connection.id, workspace.id, NotificationView.INBOX)
    if (rows.any { it.pending }) return false
    val localSeqNow = store.reader.notificationSync(connection.id, workspace.id)?.localSeq ?: return false
    if (server.headSeq != localSeqNow) return false     // not caught up; the next hint/refresh converges
    val capped = server.cappedAt
    val local = store.reader.notificationCounts(connection.id, workspace.id).unread
    val same = if (capped != null && server.unreadTotal >= capped) local >= capped else server.unreadTotal == local
    if (same) return false
    store.writer.resetNotificationCursor(connection.id, workspace.id)
    refreshNotifications(connection, workspace, SyncPriority.VISIBLE, force = true)
    return true
}

/**
 * Re-reads one item with Tower's current access decision (opening a notification whose record cannot be read, or a
 * rejected action). A removed or unavailable item stays redacted; nothing is shown from before the answer.
 */
suspend fun WorkspaceSync.refreshNotificationItem(connection: ConnectionRecord, workspace: WorkspaceRecord, itemId: String) {
    engine.enqueueRead("notification-item:${workspace.id}:$itemId", SyncPriority.USER, true) {
        identity.requireReader(connection.signerNpub)
        val change = try {
            api(connection).notificationItem(workspace.id, workspace.appNpub, itemId)
        } catch (e: TowerError.Http) {
            if (e.status == 404) { store.writer.removeNotificationRow(connection.id, workspace.id, itemId, Long.MAX_VALUE); return@enqueueRead }
            throw e
        }
        when (change) {
            is studio.otherstuff.ambulando.core.transport.NotificationChange.Upsert -> store.writer.acknowledgeNotificationItem(connection.id, workspace.id, change.item)
            is studio.otherstuff.ambulando.core.transport.NotificationChange.Removed -> store.writer.removeNotificationRow(connection.id, workspace.id, change.id, change.changeSeq)
        }
    }
}

// MARK: Preferences (categories and inbox rules)

/**
 * The registry and the caller's rules for one workspace, read together with the `rules_revision` they were read at. The
 * rows (with their `row_version`s) are the base every later write is checked against. Only the `inbox` channel is acted on
 * by Tower in this release.
 */
data class NotificationPreferences(
    val categories: List<NotificationCategoryInfo>,
    val rules: List<NotificationRule>,
    val rulesRevision: Long? = null,
) {
    /**
     * Whether items of [category] reach this app's inbox: the most specific matching rule (source/category level first, then
     * a rule for this app over a wildcard one at the same level, `off` winning ties), else the registry default. A rule in
     * `default` mode inherits (it decides nothing). Mandatory categories are always on.
     */
    fun inboxOn(category: NotificationCategoryInfo): Boolean {
        if (category.mandatory) return true
        val best = rules.filter { it.channel == "inbox" && it.mode != "default" && matches(it, category) }
            .maxWithOrNull(compareBy<NotificationRule> { specificity(it) }.thenBy { if (it.clientAppId == NOTIFICATION_CLIENT_APP_ID) 1 else 0 }.thenBy { if (it.mode == "off") 1 else 0 })
        return best?.on ?: category.defaultInbox
    }

    /** The exact key's current row (any mode, including `default`): its `row_version` is the compare-and-set base for a write of this app's rule. */
    fun ownRule(category: NotificationCategoryInfo): NotificationRule? =
        rules.firstOrNull { it.sourceAppId == category.sourceAppId && it.category == category.category && it.clientAppId == NOTIFICATION_CLIENT_APP_ID && it.channel == "inbox" }

    private fun matches(r: NotificationRule, c: NotificationCategoryInfo) =
        (r.sourceAppId == "*" || r.sourceAppId == c.sourceAppId) && (r.category == "*" || r.category == c.category) &&
            (r.clientAppId == "*" || r.clientAppId == NOTIFICATION_CLIENT_APP_ID)
    private fun specificity(r: NotificationRule) = (if (r.sourceAppId != "*") 2 else 0) + (if (r.category != "*") 1 else 0)
}

/** How a preference write ended. */
sealed class NotificationPreferenceResult {
    /** Saved; [preferences] are Tower's current rules. */
    data class Saved(val preferences: NotificationPreferences) : NotificationPreferenceResult()
    /** Another device changed these rules first. Nothing was written; [preferences] are the current rules to re-apply the intent against. */
    data class Conflict(val preferences: NotificationPreferences) : NotificationPreferenceResult()
    /** Tower refused the request as a different use of an id it had seen. [preferences] is a fresh read. */
    data class Rejected(val preferences: NotificationPreferences) : NotificationPreferenceResult()
}

/** Reads the registry and rules through the sync engine (the single network owner). */
suspend fun WorkspaceSync.loadNotificationPreferences(connection: ConnectionRecord, workspace: WorkspaceRecord): NotificationPreferences =
    engine.perform("notification-prefs:${workspace.id}", SyncPriority.USER) {
        identity.requireReader(connection.signerNpub)
        readPreferences(api(connection), workspace)
    }

private suspend fun readPreferences(api: studio.otherstuff.ambulando.core.transport.TowerAPI, workspace: WorkspaceRecord): NotificationPreferences {
    val rules = api.notificationRules(workspace.id, workspace.appNpub)
    return NotificationPreferences(api.notificationCategories(workspace.id, workspace.appNpub), rules.rules, rules.rulesRevision)
}

/**
 * Turns one category's inbox on or off for THIS app (a signed rules write on the app-specific key `client_app_id=ambulando`;
 * a wildcard rule would change the person's other apps). [base] is the read the person was looking at: the exact key's
 * `row_version` in it (0 when absent) is sent as `expected_row_version`, so a change made on another device meanwhile
 * is a conflict, never silently overwritten. A conflict adopts Tower's current rules and the person decides again; every
 * attempt uses a new `mutation_id`.
 */
suspend fun WorkspaceSync.setNotificationInbox(
    connection: ConnectionRecord, workspace: WorkspaceRecord, category: NotificationCategoryInfo, on: Boolean, base: NotificationPreferences,
): NotificationPreferenceResult {
    require(!category.mandatory) { "This category is always on." }
    return engine.perform("notification-prefs:${workspace.id}", SyncPriority.USER) {
        identity.requireReader(connection.signerNpub)
        val api = api(connection)
        val write = NotificationRuleWrite(
            category.sourceAppId, category.category, NOTIFICATION_CLIENT_APP_ID, "inbox", if (on) "on" else "off", null, base.ownRule(category)?.rowVersion ?: 0,
        )
        when (val r = api.putNotificationRules(workspace.id, workspace.appNpub, studio.otherstuff.ambulando.core.transport.newRequestId(), listOf(write))) {
            is NotificationRulesResult.Saved ->
                NotificationPreferenceResult.Saved(NotificationPreferences(api.notificationCategories(workspace.id, workspace.appNpub), r.current.rules, r.current.rulesRevision))
            is NotificationRulesResult.Conflict ->
                NotificationPreferenceResult.Conflict(NotificationPreferences(base.categories, r.current.rules, r.current.rulesRevision))
            NotificationRulesResult.MutationReused -> NotificationPreferenceResult.Rejected(readPreferences(api, workspace))
            is NotificationRulesResult.Failed -> throw TowerError.Http(r.status, r.code, "notification rules")
        }
    }
}
