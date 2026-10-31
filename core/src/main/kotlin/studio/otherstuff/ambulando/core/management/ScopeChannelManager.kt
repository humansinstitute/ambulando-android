package studio.otherstuff.ambulando.core.management

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.chat.ComposerUploadError
import studio.otherstuff.ambulando.core.chat.ComposerUploadException
import studio.otherstuff.ambulando.core.chat.UploadToken
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.StoreStateKeys
import studio.otherstuff.ambulando.core.store.channels
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.upsertChannels
import studio.otherstuff.ambulando.core.store.upsertScopes
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/**
 * Scope and channel management with the local store: every request is a confirmed online request through
 * [ScopeChannelApi] (refused with [ManagementError.Locked] before anything is sent while the connection's identity is
 * locked), and every success is written to the store from Tower's answer or a fresh list read, so the sidebar follows
 * without waiting for the event stream. Nothing is queued or replayed (Tower's create routes carry no idempotency key).
 *
 * Android: a channel or scope that disappears from a complete list read after an archive is flagged `access_removed`
 * by the store (non-destructive), not deleted.
 */
class ScopeChannelManager(
    private val store: LocalStore,
    private val identity: IdentityService,
    private val transport: HttpTransport,
) {
    private fun client(connection: ConnectionRecord) = TowerClient(TowerOrigin(connection.origin), transport, identity)

    private fun api(connection: ConnectionRecord, workspace: WorkspaceRecord) =
        ScopeChannelApi(identity, connection.signerNpub, client(connection), workspace)

    private fun refuseUnresolved(vararg texts: String?) {
        if (texts.any { UploadToken.containsAny(it ?: "") }) throw ComposerUploadException(ComposerUploadError.UNRESOLVED)
    }

    // MARK: Permissions

    suspend fun membership(connection: ConnectionRecord, workspace: WorkspaceRecord): WorkspaceMembership = api(connection, workspace).membership()

    // MARK: Channels

    suspend fun createChannel(
        connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String, name: String, description: String?, contextPrompt: String?,
        grants: List<InitialChannelGrant> = emptyList(),
    ): ChannelRecord {
        refuseUnresolved(description, contextPrompt)
        return storeChannel(api(connection, workspace).createChannel(scopeId, name, description, contextPrompt, grants), workspace)
    }

    suspend fun createDirectMessage(connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String, participantNpub: String): ChannelRecord =
        storeChannel(api(connection, workspace).createDirectMessage(scopeId, participantNpub), workspace)

    suspend fun renameChannel(connection: ConnectionRecord, workspace: WorkspaceRecord, channelId: String, name: String): ChannelRecord =
        storeChannel(api(connection, workspace).renameChannel(channelId, name), workspace)

    suspend fun saveContextPrompt(connection: ConnectionRecord, workspace: WorkspaceRecord, channel: ChannelRecord, prompt: String): ChannelRecord {
        refuseUnresolved(prompt)
        return storeChannel(api(connection, workspace).saveContextPrompt(channel, prompt), workspace)
    }

    /** Moves a channel (1-based), then reads the scope's channels again: every sibling's position may have changed. */
    suspend fun moveChannel(connection: ConnectionRecord, workspace: WorkspaceRecord, channel: ChannelRecord, position: Int) {
        api(connection, workspace).moveChannel(channel, position)
        reloadChannels(connection, workspace, channel.scopeId)
    }

    /** Archives a channel, then reads the scope's channels again (the archived one leaves the sidebar). */
    suspend fun archiveChannel(connection: ConnectionRecord, workspace: WorkspaceRecord, channel: ChannelRecord) {
        api(connection, workspace).archiveChannel(channel)
        reloadChannels(connection, workspace, channel.scopeId)
    }

    private suspend fun storeChannel(record: ChannelRecord, workspace: WorkspaceRecord): ChannelRecord {
        store.writer.upsertChannels(workspace.id, record.scopeId, listOf(record), complete = false)
        return record
    }

    private suspend fun reloadChannels(connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String) {
        try {
            val channels = TowerAPI(client(connection)).channels(workspace.id, scopeId, workspace.appNpub)
            store.writer.upsertChannels(workspace.id, scopeId, channels, complete = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The change is saved on Tower; the sidebar catches up on the next refresh or live event.
            throw ManagementError.SavedButNotRefreshed(e.message ?: e.toString())
        }
    }

    // MARK: Scopes

    suspend fun createScope(connection: ConnectionRecord, workspace: WorkspaceRecord, name: String, description: String?): ScopeRecord {
        refuseUnresolved(description)
        val record = api(connection, workspace).createScope(name, description)
        reloadScopes(connection, workspace, record)
        return record
    }

    suspend fun updateScope(connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String, name: String, description: String?) {
        refuseUnresolved(description)
        val record = api(connection, workspace).updateScope(scopeId, name, description)
        reloadScopes(connection, workspace, record)
    }

    suspend fun archiveScope(connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String) {
        api(connection, workspace).archiveScope(scopeId)
        reloadScopes(connection, workspace, null)
    }

    /** Reads the scope list again (it carries `can_manage`); if that read fails, the saved scope is stored on its own. */
    private suspend fun reloadScopes(connection: ConnectionRecord, workspace: WorkspaceRecord, fallback: ScopeRecord?) {
        val api = TowerAPI(client(connection))
        try {
            val scopes = api.scopes(workspace.id, workspace.appNpub)
            store.writer.upsertScopes(workspace.id, scopes, complete = true)
            // A new scope's channels (Tower may add a default one) are read now rather than on the next refresh.
            val known = store.reader.channels(workspace.id).map { it.scopeId }.toSet()
            for (scope in scopes) {
                if (scope.id in known) continue
                store.writer.upsertChannels(workspace.id, scope.id, api.channels(workspace.id, scope.id, workspace.appNpub), complete = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (fallback != null && fallback.workspaceId == workspace.id) runCatching { store.writer.upsertScopes(workspace.id, listOf(fallback), complete = false) }
            throw ManagementError.SavedButNotRefreshed(e.message ?: e.toString())
        }
    }

    /**
     * Saves the member's personal scope order. The current `row_version` is read from Tower first; if another session
     * saved in between, Tower answers `scope_order_conflict`: the newer order is stored and the conflict is reported so
     * the user makes the move again. Nothing is replayed over the other edit.
     */
    suspend fun saveScopeOrder(connection: ConnectionRecord, workspace: WorkspaceRecord, scopeIds: List<String>) {
        val saved = api(connection, workspace).saveScopeOrder(scopeIds) { fresh -> storeScopeOrder(fresh, workspace) }
        storeScopeOrder(saved, workspace)
    }

    private suspend fun storeScopeOrder(ids: List<String>, workspace: WorkspaceRecord) {
        store.writer.setState(StoreStateKeys.scopeOrder(workspace.id), JsonArray(ids.map(::JsonPrimitive)).toString())
    }

    // MARK: Scope setup support

    /** The current channel names of [scopeId] as Tower lists them (stored too), so setup creates only the missing ones. */
    suspend fun setupChannelNames(connection: ConnectionRecord, workspace: WorkspaceRecord, scopeId: String): Set<String> {
        val channels = try {
            TowerAPI(client(connection)).channels(workspace.id, scopeId, workspace.appNpub)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { throw ManagementError.Offline(e.message ?: e.toString()) }
        store.writer.upsertChannels(workspace.id, scopeId, channels, complete = true)
        return channels.map { it.name.lowercase() }.toSet()
    }

    /** A scope of this workspace with [name] (case-insensitive) as Tower lists it now, for recovering an interrupted create. */
    suspend fun scopeSetupCandidate(connection: ConnectionRecord, workspace: WorkspaceRecord, name: String): ScopeRecord? {
        val scopes = try {
            TowerAPI(client(connection)).scopes(workspace.id, workspace.appNpub)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { throw ManagementError.Offline(e.message ?: e.toString()) }
        store.writer.upsertScopes(workspace.id, scopes, complete = true)
        return scopes.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
