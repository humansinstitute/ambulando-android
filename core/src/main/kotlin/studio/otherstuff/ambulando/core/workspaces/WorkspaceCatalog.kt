package studio.otherstuff.ambulando.core.workspaces

import studio.otherstuff.ambulando.core.context.NavChannel
import studio.otherstuff.ambulando.core.context.NavScope
import studio.otherstuff.ambulando.core.context.ScopeOrdering
import studio.otherstuff.ambulando.core.context.SidebarGroup
import studio.otherstuff.ambulando.core.context.SidebarNavigation
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.StoreStateKeys
import studio.otherstuff.ambulando.core.store.channels
import studio.otherstuff.ambulando.core.store.connections
import studio.otherstuff.ambulando.core.store.people
import studio.otherstuff.ambulando.core.store.scopes
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.store.unreadChannelIds
import studio.otherstuff.ambulando.core.store.workspaces
import studio.otherstuff.ambulando.core.store.workspacesWithLocalWork
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/** One workspace a connection lists, as the catalog knows it. */
data class WorkspaceListing(val connection: ConnectionRecord, val workspace: WorkspaceRecord) {
    val boundary: WorkspaceBoundary get() = WorkspaceBoundary.of(connection, workspace)
    val id: String get() = boundary.id
}

/** Reads and writes everything the catalog owns for multi-workspace use: listings, enabled set, order, colours, selection. */
object WorkspaceCatalog {
    /** The boundary id of the workspace in front. Empty selection is stored explicitly as the empty string. */
    const val SELECTED_KEY = "workspaces.selected.v1"

    /** Every listing of connections belonging to [signerNpub] (another identity's connections are never shown). */
    suspend fun listings(catalog: LocalStore, signerNpub: String?): List<WorkspaceListing> {
        val out = ArrayList<WorkspaceListing>()
        for (c in catalog.reader.connections()) {
            if (signerNpub != null && c.signerNpub != signerNpub) continue
            for (w in catalog.reader.workspaces(c.id)) out.add(WorkspaceListing(c, w))
        }
        return out
    }

    suspend fun preferences(catalog: LocalStore): WorkspacePreferences? = WorkspacePreferences.decode(catalog.reader.state(WorkspacePreferences.STATE_KEY))

    suspend fun save(catalog: LocalStore, preferences: WorkspacePreferences) = catalog.writer.setState(WorkspacePreferences.STATE_KEY, preferences.encode())

    suspend fun selected(catalog: LocalStore): String? = catalog.reader.state(SELECTED_KEY)?.takeIf { it.isNotEmpty() }

    suspend fun saveSelected(catalog: LocalStore, boundaryId: String?) = catalog.writer.setState(SELECTED_KEY, boundaryId ?: "")

    /**
     * What an installation that predates multiple workspaces starts with: the workspace it remembered, plus every workspace
     * that still holds unsent messages, queued changes or saved drafts in the catalog, so work queued by the previous
     * build is picked up instead of waiting for someone to tick a box. Listed in the catalog's order.
     */
    suspend fun initialPreferences(catalog: LocalStore, listings: List<WorkspaceListing>): WorkspacePreferences {
        if (listings.isEmpty()) return WorkspacePreferences()
        val withWork = HashSet<String>()
        for (c in listings.map { it.connection }.distinctBy { it.id }) {
            for (workspaceId in catalog.reader.workspacesWithLocalWork(c.id)) withWork.add(c.id + "|" + workspaceId)
        }
        val remembered = listings.firstOrNull { l -> catalog.reader.state("workspace:${l.connection.id}") == l.workspace.id }
        val first = remembered ?: listings.firstOrNull { !it.workspace.accessRemoved } ?: listings.first()
        val enabled = listings.filter { it.id == first.id || (it.connection.id + "|" + it.workspace.id) in withWork }.map { it.id }
        return WorkspacePreferences(enabled = enabled, order = listings.map { it.id })
    }
}

/** What the sidebar draws for one workspace; read from that workspace's own partition. */
data class NavSnapshot(
    val groups: List<SidebarGroup> = emptyList(),
    /** Scope ids collapsed in this workspace. */
    val collapsed: Set<String> = emptySet(),
    val unread: Set<String> = emptySet(),
    /** Channel id → display label (DM names resolved). */
    val labels: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
) {
    fun label(channel: NavChannel): String = labels[channel.id] ?: channel.name

    companion object {
        suspend fun load(store: LocalStore, connection: ConnectionRecord, workspace: WorkspaceRecord): NavSnapshot {
            val scopes = store.reader.scopes(workspace.id)
            val channels = store.reader.channels(workspace.id)
            val order = ScopeOrdering.decode(store.reader.state(StoreStateKeys.scopeOrder(workspace.id)))
            val collapsed = ScopeOrdering.decode(store.reader.state("collapsed:${connection.id}:${workspace.id}")).toSet()
            val people = store.reader.people(connection.id, workspace.id)
            val navChannels = channels.map { NavChannel(it.id, it.scopeId, it.name, it.kind, it.position, it.raw) }
            val groups = SidebarNavigation.groups(scopes.map { NavScope(it.id, it.name, it.kind) }, navChannels, order)
            val labels = navChannels.associate { it.id to SidebarNavigation.label(it, connection.signerNpub, people.names) }
            return NavSnapshot(groups, collapsed, store.reader.unreadChannelIds(workspace.id), labels, loaded = true)
        }
    }
}
