package studio.otherstuff.ambulando.core.context

/** Which workspace the app shows for a connection. */
object WorkspaceSelection {
    /** App-state key set when Tower's workspace list for a connection was stored (in the same transaction). */
    fun listedKey(connectionId: String): String = "workspaces.listed:$connectionId"

    /**
     * The chosen workspace while Tower still lists it, otherwise the first listed one (`listed` holds workspace ids in
     * Tower's order).
     *
     * A choice is persisted only when the user picks a workspace in the switcher, so most people have none. An empty
     * list keeps the choice only while no list has been stored yet (`listComplete` false: first launch, offline);
     * once Tower has answered with no workspaces, nothing is shown, so a removed workspace cannot linger.
     */
    fun effective(persisted: String?, listed: List<String>, listComplete: Boolean): String? {
        if (listed.isEmpty()) return if (listComplete) null else persisted
        if (persisted != null && persisted in listed) return persisted
        return listed.first()
    }

    sealed interface Change {
        /** Nothing to do (the common case: the same list stored again by a refresh). */
        data object None : Change
        /** The shown workspace is right but its sidebar was never started (no choice at launch, list arrived later). */
        data class StartNavigation(val workspaceId: String) : Change
        /** Show another workspace from its Home (the shown one is no longer listed, or none was shown). */
        data class Show(val workspaceId: String) : Change
        /** No workspace is listed any more: clear the selection and everything read for it. */
        data object Clear : Change
    }

    /**
     * What a new or re-stored workspace list means for the app, given the shown workspace and the workspace the
     * sidebar is reading. Re-storing the same list is `None`, so a refresh never resets context, drafts, boards or
     * scroll.
     */
    fun change(selected: String?, navigationFor: String?, listed: List<String>, listComplete: Boolean): Change {
        val shown = effective(selected, listed, listComplete)
            ?: return if (selected == null && navigationFor == null) Change.None else Change.Clear
        if (shown != selected) return Change.Show(shown)
        return if (navigationFor == shown) Change.None else Change.StartNavigation(shown)
    }
}

/**
 * What the sidebar (and the Chat channel list) can honestly say about scopes and channels. Never an empty list
 * without a reason: a failed refresh, a lock or a refresh still running is shown as such.
 */
sealed interface NavigationLoadState {
    /** No workspace is stored yet and discovery is running (or about to). */
    data object FindingWorkspaces : NavigationLoadState
    /** No workspace is stored and discovery failed. */
    data class WorkspacesFailed(val error: String) : NavigationLoadState
    /** Discovery finished and this identity is a member of no workspace. */
    data object NoWorkspaces : NavigationLoadState
    /** The stored scopes are being read, or nothing is stored yet and a refresh is running. */
    data object Loading : NavigationLoadState
    /** Nothing is stored and the last refresh failed. */
    data class Failed(val error: String) : NavigationLoadState
    /** Nothing is stored and requests cannot be signed (identity locked or sealed). */
    data object Locked : NavigationLoadState
    /** Tower answered and lists no scopes for this member. */
    data object Empty : NavigationLoadState
    /** Scopes are shown. `refreshError` is set when the latest refresh failed, so the list may be out of date. */
    data class Ready(val refreshError: String?) : NavigationLoadState

    data class Inputs(
        val hasWorkspace: Boolean,
        val workspacesLoaded: Boolean,
        val navigationLoaded: Boolean,
        val hasScopes: Boolean,
        val canRead: Boolean,
        val refreshing: Boolean,
        val error: String?,
    )

    companion object {
        fun resolve(i: Inputs): NavigationLoadState {
            if (!i.hasWorkspace) {
                if (!i.workspacesLoaded) return FindingWorkspaces
                if (i.error != null && !i.refreshing) return WorkspacesFailed(i.error)
                if (!i.canRead) return Locked
                return if (i.refreshing) FindingWorkspaces else NoWorkspaces
            }
            if (!i.navigationLoaded) return Loading
            if (i.hasScopes) return Ready(if (i.refreshing) null else i.error)
            if (i.refreshing) return Loading
            if (i.error != null) return Failed(i.error)
            if (!i.canRead) return Locked
            return Empty
        }
    }
}

/**
 * The sync engine's job state as the sidebar reads it (seam: mirrors the engine snapshot once the sync layer lands).
 * `scheduled` jobs wait for a retry time and do not count as refreshing.
 */
data class NavigationSyncSnapshot(
    val running: Set<String> = emptySet(),
    val queued: Set<String> = emptySet(),
    val scheduled: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

/** Sync keys that feed the sidebar, and what their state means for it. */
object NavigationKeys {
    /** Sync keys that feed the sidebar of `workspaceId`: discovery for the connection, its scopes and channel lists. */
    fun isNavigationKey(key: String, connectionId: String, workspaceId: String?): Boolean {
        if (key == "discover:$connectionId") return true
        if (workspaceId == null) return false
        return key == "scopes:$workspaceId" || key.startsWith("channels:$workspaceId:")
    }

    /** Whether sidebar data is being fetched now (scheduled retries waiting for their backoff do not count). */
    fun refreshing(snapshot: NavigationSyncSnapshot, connectionId: String, workspaceId: String?): Boolean =
        (snapshot.running + snapshot.queued).any { isNavigationKey(it, connectionId, workspaceId) }

    /** The latest failure of a sidebar job, discovery first (without it nothing below can load). */
    fun error(snapshot: NavigationSyncSnapshot, connectionId: String, workspaceId: String?): String? {
        snapshot.errors["discover:$connectionId"]?.let { return it }
        return snapshot.errors.filterKeys { isNavigationKey(it, connectionId, workspaceId) }.entries.sortedBy { it.key }.firstOrNull()?.value
    }
}
