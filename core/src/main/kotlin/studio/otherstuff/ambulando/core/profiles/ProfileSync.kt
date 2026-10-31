package studio.otherstuff.ambulando.core.profiles

import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.profileCandidates
import studio.otherstuff.ambulando.core.store.profiles
import studio.otherstuff.ambulando.core.store.pruneProfiles
import studio.otherstuff.ambulando.core.store.recordProfileCheck
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.sync.SyncScheduler
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.checkCancelled
import java.time.Duration
import java.time.Instant

/**
 * Keeps the public-profile cache fresh for the people a workspace shows. Runs as one background job per connection and
 * workspace on the shared [SyncEngine] (deduplicated, cancellable, backed off on failure), reads candidates from the
 * local store (at most `ProfileLimits.MAX_CANDIDATES`), and asks the [source] (the relays) only for keys whose cached
 * answer is due, in batches of [BATCH_SIZE].
 *
 * Screens never wait for it: they render from the cache (or the npub and initials) and update when a row changes.
 */
class ProfileSync(
    private val store: LocalStore,
    private val engine: SyncScheduler,
    private val source: ProfileSource,
    private val now: () -> Instant = { Instant.now() },
) {
    /**
     * Schedules a refresh of the profiles [workspaceId] shows. `force` (Settings › Refresh Nostr profiles) asks again for
     * everyone, keeping cached rows until newer ones arrive.
     */
    suspend fun refresh(connection: ConnectionRecord, workspaceId: String, force: Boolean = false) {
        engine.enqueue(jobKey(connection.id, workspaceId), SyncPriority.BACKGROUND, force) { run(connection.id, workspaceId, force) }
    }

    /**
     * Settings › Nostr profiles. Off stores the preference and cancels every queued or running profile job of the
     * connection (a round answered afterwards stores nothing); cached names stay. On schedules nothing by itself: the
     * caller refreshes the shown workspace.
     */
    suspend fun setEnabled(on: Boolean, connection: ConnectionRecord?) {
        store.writer.setState(PREFERENCE_KEY, if (on) null else "off")
        if (!on && connection != null) engine.cancelPrefix(jobPrefix(connection.id))
    }

    /** One refresh pass. Public so tests can await it directly. */
    suspend fun run(connectionId: String, workspaceId: String, force: Boolean) {
        if (!isEnabled(store)) return
        val candidates = store.reader.profileCandidates(connectionId, workspaceId)
        val cached = HashMap<String, ProfileRecord>()
        for (p in store.reader.profiles(connectionId)) cached.putIfAbsent(p.npub, p)
        for (batch in batches(candidates, cached, now(), force)) {
            checkCancelled()
            if (!isEnabled(store)) return
            val found = source.profiles(batch.pubkeys)
            checkCancelled()
            // Turned off while the relays were answering: nothing from that round is stored.
            if (!isEnabled(store)) return
            store.writer.recordProfileCheck(connectionId, found, batch.checked, now(), unlessOff = PREFERENCE_KEY)
        }
        store.writer.pruneProfiles(connectionId)
    }

    /** One batch of a refresh pass: hex keys to ask for, and the npub each stands for (recorded as checked). */
    data class Batch(val pubkeys: List<String>, val checked: Map<String, String>)

    companion object {
        /** `app_state` key; "off" turns off every request for profiles and profile pictures. On by default, as on the web. */
        const val PREFERENCE_KEY = "profiles.public"
        /** A found profile is checked again after a day; a missing one after six hours. */
        val FOUND_TTL: Duration = Duration.ofHours(24)
        val MISSING_TTL: Duration = Duration.ofHours(6)
        const val BATCH_SIZE = 50

        /**
         * One job per connection and workspace: candidates are workspace-specific, so a refresh for workspace B queued
         * while A's is running must not be coalesced into A's job.
         */
        fun jobKey(connectionId: String, workspaceId: String): String = "${jobPrefix(connectionId)}$workspaceId"
        fun jobPrefix(connectionId: String): String = "profiles:$connectionId:"

        /** Whether the stored preference allows profile requests (anything but "off"). */
        fun isEnabled(preference: String?): Boolean = preference != "off"

        suspend fun isEnabled(store: LocalStore): Boolean = isEnabled(runCatching { store.reader.state(PREFERENCE_KEY) }.getOrNull())

        /** Whether a cached answer should be asked for again. A clock that moved back counts as due. */
        fun isDue(profile: ProfileRecord?, now: Instant, force: Boolean): Boolean {
            if (profile == null || force) return true
            val age = Duration.between(profile.checkedAt, now)
            return age.isNegative || age >= (if (profile.status == ProfileRecord.Status.FOUND) FOUND_TTL else MISSING_TTL)
        }

        /**
         * The due candidates of one pass in sorted, bounded batches. Candidates that are not valid npubs are skipped;
         * `force` (Settings › Refresh Nostr profiles) asks again for everyone.
         */
        fun batches(candidates: List<String>, cached: Map<String, ProfileRecord>, now: Instant, force: Boolean): List<Batch> {
            val due = HashMap<String, String>()   // pubkey → npub
            for (npub in candidates) {
                if (!isDue(cached[npub], now, force)) continue
                NostrKeys.publicKeyHex(npub)?.let { due[it] = npub }
            }
            return due.keys.sorted().chunked(BATCH_SIZE).map { keys -> Batch(keys, keys.associateWith { due.getValue(it) }) }
        }
    }
}
