package studio.otherstuff.ambulando.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import studio.otherstuff.ambulando.core.management.AdministrationCommand
import studio.otherstuff.ambulando.core.management.AdministrationList
import studio.otherstuff.ambulando.core.management.AdministrationResult
import studio.otherstuff.ambulando.core.management.AdministrationRow
import studio.otherstuff.ambulando.core.management.AdministrationSuperseded
import studio.otherstuff.ambulando.core.management.ListRefresh
import studio.otherstuff.ambulando.core.management.ManagementError
import studio.otherstuff.ambulando.core.management.WorkspaceAdministrator
import studio.otherstuff.ambulando.core.management.WorkspaceMembership
import studio.otherstuff.ambulando.core.nostr.Sha256Hex

/** What the screens know about the member's authority right now. A failed read is its own state, never a default. */
internal sealed interface Authority {
    data object Checking : Authority
    data class Known(val membership: WorkspaceMembership) : Authority
    /** `/me` could not be read: administration controls stay off instead of falling back to what was cached. */
    data class Unavailable(val message: String) : Authority
}

internal sealed interface Phase {
    data object Idle : Phase
    data class Working(val label: String) : Phase
    data class Done(val message: String) : Phase
    data class Failed(val message: String, val retryable: Boolean) : Phase
}

/**
 * One open Manage Workspace screen, bound to exactly one boundary through its [WorkspaceAdministrator]. Everything that
 * changes state here first asks [isCurrent]: once the screen is closed or another workspace is in front, a delayed reply or
 * failure is dropped and cannot appear in (or alter) any other workspace's screen. Writes are confirmed online requests;
 * nothing is queued.
 */
@Stable
internal class AdministrationSession(
    val admin: WorkspaceAdministrator,
    val workspaceLabel: String,
    /** The boundary id of the workspace in front, read at the moment of each check. */
    private val front: () -> String?,
    private val scope: CoroutineScope,
) {
    val boundaryId: String get() = admin.boundary.id
    val isCurrent: Boolean get() = admin.isOpen && front() == boundaryId

    var authority by mutableStateOf<Authority>(Authority.Checking); private set
    val rows = mutableStateMapOf<AdministrationList, List<AdministrationRow>>()
    val listState = mutableStateMapOf<AdministrationList, ListRefresh>()
    var phase by mutableStateOf<Phase>(Phase.Idle); private set

    val membership: WorkspaceMembership? get() = (authority as? Authority.Known)?.membership
    /** A workspace administrator, from a `/me` read made on this screen. */
    val canAdminister: Boolean get() = membership?.canAdminister == true

    fun close() = admin.close()

    /** Leaving a page clears its outcome, but never hides a request that is still in flight (other forms stay disabled until it ends). */
    fun clearPhase() { if (phase !is Phase.Working) phase = Phase.Idle }

    /** A refusal found before anything was sent (an invalid form). */
    fun reject(e: Throwable) { if (isCurrent) failureText(e).let { phase = Phase.Failed(it.first, false) } }

    /** Re-reads `/me`. While the read is outstanding, and if it fails, no control is enabled on its account. */
    fun checkAuthority() { scope.launch { reloadAuthority() } }

    suspend fun reloadAuthority() {
        if (!isCurrent) return
        authority = Authority.Checking
        try {
            val m = admin.membership()
            if (isCurrent) authority = Authority.Known(m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdministrationSuperseded) {
            // Closed: this screen no longer speaks for anything.
        } catch (e: Exception) {
            if (isCurrent) authority = Authority.Unavailable(failureText(e).first)
        }
    }

    /** Shows what is saved first, then asks Tower; a failed refresh keeps the saved rows and says why. */
    fun refresh(vararg lists: AdministrationList, thenReloadAuthority: Boolean = true) {
        scope.launch {
            if (thenReloadAuthority) reloadAuthority()
            for (list in lists) loadCached(list)
            for (list in lists) {
                if (!isCurrent) return@launch
                val result = admin.refresh(list)
                if (!isCurrent) return@launch
                listState[list] = result
                loadCached(list)
            }
        }
    }

    suspend fun loadCached(list: AdministrationList) {
        val cached = admin.cached(list)
        if (isCurrent) rows[list] = cached
    }

    fun rows(list: AdministrationList): List<AdministrationRow> = rows[list] ?: emptyList()

    /**
     * Sends one administration command and shows the outcome. [then] runs only while this screen is still current and only
     * after Tower accepted the change. A failure re-reads authority, so a control Tower just refused is not offered again on
     * the strength of what was shown before.
     */
    fun write(command: AdministrationCommand, working: String = "Waiting for Tower…", then: suspend (AdministrationResult) -> Unit = {}) {
        if (!isCurrent) return
        phase = Phase.Working(working)
        scope.launch {
            try {
                val result = admin.administer(command)
                if (!isCurrent) return@launch
                phase = Phase.Done(
                    if (result.observed == false) "Saved on Tower. The refreshed list does not show it yet; refresh to confirm."
                    else "Saved on Tower.",
                )
                for (list in AdministrationList.entries) if (rows.containsKey(list)) loadCached(list)
                then(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdministrationSuperseded) {
                // The workspace in front changed before the request left. Nothing to report here.
            } catch (e: ManagementError.SavedButNotRefreshed) {
                // Tower has it: a create must not be offered again. Only the list could not be read back.
                if (!isCurrent) return@launch
                phase = Phase.Done(e.message ?: "Saved on Tower, but the list could not be refreshed yet.")
                then(AdministrationResult(kotlinx.serialization.json.JsonObject(emptyMap()), null))
            } catch (e: Exception) {
                if (!isCurrent) return@launch
                val (message, retryable) = failureText(e)
                phase = Phase.Failed(message, retryable)
                reloadAuthority()
            }
        }
    }

    /** Runs [block] as a confirmed online step with the same state handling as [write]. */
    fun run(working: String = "Waiting for Tower…", done: String = "Saved on Tower.", block: suspend () -> Unit, then: suspend () -> Unit = {}) {
        if (!isCurrent) return
        phase = Phase.Working(working)
        scope.launch {
            try {
                block()
                if (!isCurrent) return@launch
                phase = Phase.Done(done)
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdministrationSuperseded) {
            } catch (e: ManagementError.SavedButNotRefreshed) {
                if (!isCurrent) return@launch
                phase = Phase.Done(e.message ?: "Saved on Tower, but the list could not be refreshed yet.")
                then()
            } catch (e: Exception) {
                if (!isCurrent) return@launch
                val (message, retryable) = failureText(e)
                phase = Phase.Failed(message, retryable)
                reloadAuthority()
            }
        }
    }

    companion object {
        /** A refusal in plain words, and whether trying the same thing again can help. */
        fun failureText(e: Throwable): Pair<String, Boolean> = when (e) {
            // A lost connection can happen after Tower committed, so "nothing was changed" is never claimed for an administration request.
            is ManagementError.Offline -> "Couldn't confirm that Tower received this (${e.detail}). It may have been received: refresh and check before trying again." to true
            is ManagementError -> (e.message ?: "Tower refused the request.") to e.retryable
            else -> ("Something went wrong: " + (e.message ?: e::class.simpleName) + ". Nothing is assumed to have changed; refresh and check.") to true
        }
    }
}

/**
 * The values of one administration form, kept on this device per connection, workspace and form. A refusal or a lost
 * connection never clears them; a draft Tower acknowledged cannot be sent again unchanged without an edit.
 */
@Stable
internal class FormState(
    private val session: AdministrationSession, val kind: String, private val initial: Map<String, String>,
    /**
     * The keys of [initial] that come from Tower (an edit form starts from the current record). A saved draft is only restored over
     * values that are still the ones it was started from: if Tower's record changed meanwhile, the draft is dropped and the current
     * values are shown, so an old entry can never silently undo someone else's change. Generated identifiers are not part of the base.
     */
    private val baseKeys: Set<String> = initial.keys,
) {
    val values = mutableStateMapOf<String, String>().apply { putAll(initial) }
    var loaded by mutableStateOf(false); private set
    var acknowledged by mutableStateOf<String?>(null); private set
    /** True when a saved draft was set aside because Tower's record is no longer the one it was started from. */
    var draftDiscarded by mutableStateOf(false); private set

    private val base: String get() = Sha256Hex.of(baseKeys.sorted().joinToString("\n") { "$it=${initial[it] ?: ""}" })

    operator fun get(key: String): String = values[key] ?: ""
    operator fun set(key: String, value: String) { values[key] = value }

    val fingerprint: String get() = Sha256Hex.of(values.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })
    val unchangedSinceAcknowledged: Boolean get() = acknowledged == fingerprint

    suspend fun load() {
        if (loaded) return
        val stored = session.admin.loadDraft(kind)
        if (stored != null) {
            if (stored[BASE_KEY] == base) { values.clear(); values.putAll(stored - BASE_KEY) }
            else { draftDiscarded = true; session.admin.clearDraft(kind) }
        }
        acknowledged = if (draftDiscarded) null else session.admin.confirmedFingerprint(kind)
        loaded = true
    }

    suspend fun persist() { if (loaded) session.admin.saveDraft(kind, values.toMap() + (BASE_KEY to base)) }

    /** The form's purpose is done (a create that must not reuse its generated identifiers): this open editor cannot resend it and a reopened one starts fresh. */
    suspend fun complete() {
        acknowledged = fingerprint
        session.admin.clearDraft(kind)
    }

    /**
     * A finished create starts over in place: the entries are cleared and [fresh] (new generated identifiers) takes their place, so the
     * open editor can neither resend what Tower has nor, after an edit, overwrite the record it just created under the same id.
     */
    suspend fun restart(fresh: Map<String, String>) {
        values.clear(); values.putAll(fresh)
        acknowledged = null
        session.admin.clearDraft(kind)
    }

    suspend fun acknowledge() {
        acknowledged = fingerprint
        session.admin.confirmDraft(kind, acknowledged)
    }
}

private const val BASE_KEY = "__base"
