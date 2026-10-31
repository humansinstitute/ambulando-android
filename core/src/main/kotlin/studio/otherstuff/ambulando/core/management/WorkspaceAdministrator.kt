package studio.otherstuff.ambulando.core.management

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.PreparedObject
import studio.otherstuff.ambulando.core.transport.completeStorage
import studio.otherstuff.ambulando.core.transport.upload
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.sync.SyncScheduler
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.LeaseError
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerJson
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.acquireLease
import studio.otherstuff.ambulando.core.transport.isUUID
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.newRequestId
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.releaseLease
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.workspaces.BoundaryViolation
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import java.time.Instant

/** The administration surface was closed (the workspace in front changed or the screen left) before this step; nothing was sent. */
class AdministrationSuperseded : Exception("This workspace is no longer in front. Nothing was changed. Reopen management for the workspace you want.", null, false, false)

/** Tower accepted a write. [observed] says whether a fresh read of the affected list shows it (null: not checkable). */
data class AdministrationResult(val body: JsonObject, val observed: Boolean?)

/** How one administration list read ended; cached rows stay available whatever it was. */
sealed interface ListRefresh {
    data object Fresh : ListRefresh
    /** Tower refused the list to this member (it is shown to administrators or exact managers only). */
    data class Restricted(val message: String) : ListRefresh
    /** This Tower does not offer the list to this app. */
    data class Unsupported(val message: String) : ListRefresh
    data class Failed(val message: String) : ListRefresh
}

/**
 * Workspace administration for one exact boundary (Tower origin, signer, application and workspace). It owns nothing but the
 * rules: every request goes through the runtime's own guarded, gated transports and its own scoped scheduler, every
 * persisted row lands in that runtime's partition, and [close] stops any step that has not yet sent, so a form that was
 * still in flight when the workspace in front changed can neither send later nor report into another workspace's screen.
 *
 * Writes are confirmed online requests, one at a time. Before a write the member's `/me` is read again and, for a
 * non-administrator, the exact resource authority is read again ([AdministrationAuthority]); a read that fails clears
 * the decision (the write is refused as unverified) rather than falling back to what was cached.
 */
class WorkspaceAdministrator(
    val boundary: WorkspaceBoundary,
    private val connection: ConnectionRecord,
    private val workspace: WorkspaceRecord,
    private val store: LocalStore,
    private val identity: IdentityService,
    private val readTransport: HttpTransport,
    private val writeTransport: HttpTransport,
    private val engine: SyncScheduler,
    private val now: () -> Instant = Instant::now,
    /** Called after a workspace profile change or deletion, so the catalog discovers Tower's current answer. */
    private val workspaceChanged: suspend () -> Unit = {},
    /**
     * Whether this boundary is still the workspace in front. It is asked before every step and again just before a request leaves, so a
     * switch stops a write at once, without waiting for the screen to be torn down.
     */
    private val stillInFront: () -> Boolean = { true },
) {
    init { boundary.requireOwns(connection, workspace) }

    private val origin = TowerOrigin(connection.origin)
    private val readClient = TowerClient(origin, readTransport, identity)
    private val writeClient = TowerClient(origin, writeTransport, identity)
    private val readApi = ScopeChannelApi(identity, connection.signerNpub, readClient, workspace)
    private val writeApi = ScopeChannelApi(identity, connection.signerNpub, writeClient, workspace)
    private val writeLock = Mutex()

    @Volatile private var open = true
    val isOpen: Boolean get() = open && stillInFront()

    /** Ends this administrator's authority to start anything new. In-flight reads and writes finish; their callers drop the result. */
    fun close() { open = false }

    private fun requireOpen() { if (!isOpen) throw AdministrationSuperseded() }

    val connectionId: String get() = connection.id
    val workspaceId: String get() = workspace.id
    val signerNpub: String get() = connection.signerNpub

    // MARK: Fresh authority

    /** A fresh `/me`. Failure is not a default: the caller must treat authority as unknown. */
    suspend fun membership(): WorkspaceMembership { requireOpen(); return job("admin:me") { readApi.membership() } }

    // MARK: Cached and refreshed lists

    suspend fun cached(list: AdministrationList): List<AdministrationRow> =
        AdministrationRow.decode(store.reader.state(AdministrationKeys.list(connection.id, workspace.id, list)))

    /** Reads one list from Tower and stores it. Never throws for a refusal or an offline Tower: the cached rows remain. */
    suspend fun refresh(list: AdministrationList): ListRefresh {
        requireOpen()
        return try {
            val rows = job("admin:list:${list.storageName}") { fetchRows(list) }
            if (!open) return ListRefresh.Fresh
            store.writer.setState(AdministrationKeys.list(connection.id, workspace.id, list), AdministrationRow.encode(rows))
            ListRefresh.Fresh
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            classifyRead(e, list.listKey)
        }
    }

    private suspend fun fetchRows(list: AdministrationList): List<AdministrationRow> {
        val rows = readClient.list(TowerAPI.base(workspace.id) + list.route, workspace.appNpub, list.listKey)
        return rows.map { raw ->
            raw.string("workspace_id")?.let { if (it.lowercase() != workspace.id) throw TowerError.Malformed("cross-workspace administration row") }
            AdministrationRow.parse(raw)
        }
    }

    private fun classifyRead(e: Exception, what: String): ListRefresh = when {
        e is TowerError.Http && (e.status == 401 || e.status == 403) ->
            ListRefresh.Restricted("Tower shows $what to workspace administrators and managers of the exact resource only.")
        e is TowerError.Http && (e.status == 404 || e.status == 405 || e.status == 501) ->
            ListRefresh.Unsupported("This Tower does not offer $what to this app.")
        e is TowerError.Malformed -> ListRefresh.Failed("Tower sent $what in an unexpected shape. Saved rows are kept.")
        else -> ListRefresh.Failed("Could not read $what. Saved rows are kept; check your connection and refresh.")
    }

    /** The grants on one channel, fresh, stored for the screens. */
    suspend fun channelGrants(channelId: String): List<AdministrationRow> {
        requireOpen()
        val channel = TowerAPI.uuid(channelId)
        val body = job("admin:channel-grants:$channel") { readApi.send(ManagementRequest("GET", TowerAPI.base(workspace.id) + "/channels/$channel/grants", null, ManagementOperation.READ_PERMISSIONS)) }
        val rows = (body.objects("grants") ?: emptyList()).map(AdministrationRow::parse)
        if (open) store.writer.setState(AdministrationKeys.channelGrants(connection.id, workspace.id, channel), AdministrationRow.encode(rows))
        return rows
    }

    suspend fun cachedChannelGrants(channelId: String): List<AdministrationRow> =
        AdministrationRow.decode(store.reader.state(AdministrationKeys.channelGrants(connection.id, workspace.id, channelId)))

    // MARK: Scope access

    /** The scope's role grants with Tower's `can_manage` and revision, fresh. */
    suspend fun scopeAccess(scopeId: String): ScopeAdministrationAccess {
        requireOpen()
        return job("admin:scope-access:$scopeId") { readScopeAccess(readApi, scopeId) }.also { storeScopeAccess(it, scopeId) }
    }

    suspend fun cachedScopeAccess(scopeId: String): ScopeAdministrationAccess? =
        ScopeAdministrationAccess.decode(store.reader.state(AdministrationKeys.scopeAccess(connection.id, workspace.id, scopeId)))

    private suspend fun readScopeAccess(api: ScopeChannelApi, scopeId: String): ScopeAdministrationAccess {
        val id = TowerAPI.uuid(scopeId)
        return ScopeAdministrationAccess.parse(api.send(ManagementRequest("GET", TowerAPI.base(workspace.id) + "/scopes/$id/grants", null, ManagementOperation.MANAGE_SCOPE)))
    }

    private suspend fun storeScopeAccess(access: ScopeAdministrationAccess, scopeId: String) {
        if (open) store.writer.setState(AdministrationKeys.scopeAccess(connection.id, workspace.id, scopeId), access.encode())
    }

    /**
     * Grants, changes or revokes a scope role. Authority is the scope's own `can_manage` from a read made now, so a delegated
     * scope manager needs no workspace administration; the editor's [expectedRevision] must still be current (a changed
     * scope is reported, never silently rebased) and the write holds the scope's canonical edit lease.
     */
    suspend fun saveScopeAccess(scopeId: String, principalType: String, principal: String, role: String, revoke: Boolean, expectedRevision: Long) {
        if (principalType != "actor" && principalType != "group" || role !in SCOPE_ROLES) throw ManagementError.Invalid("Choose a principal and scope role.")
        if (!isUUID(scopeId) || !isUUID(principal)) throw ManagementError.Invalid("Choose a valid recipient.")
        val scope = scopeId.lowercase(); val principalId = principal.lowercase()
        writeLock.withLock {
            requireOpen()
            job("admin:write:${newRequestId()}") {
                val fresh = readScopeAccess(writeApi, scope)
                storeScopeAccess(fresh, scope)
                if (!fresh.canManage) throw ManagementError.Forbidden("Scope manager permission is required to change access.")
                if (fresh.revision != expectedRevision) {
                    throw ManagementError.Conflict("Scope access changed elsewhere. Refresh and review it before saving your retained draft.")
                }
                requireOpen()
                val api = TowerAPI(writeClient)
                val lease = try {
                    api.acquireLease(workspace.id, workspace.appNpub, "scope", scope)
                } catch (e: LeaseError.Held) {
                    throw ManagementError.Conflict("Someone else is editing this scope's access right now. Try again in a moment.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw if (e is TowerError.Http && e.status == 403) ManagementError.Forbidden(ManagementOperation.MANAGE_SCOPE.permissionSentence)
                    else ManagementError.Offline(e.message ?: e.toString())
                }
                try {
                    requireOpen()
                    val fields = mutableListOf<Pair<String, Any?>>(
                        "principal_type" to principalType, "principal_id" to principalId, "expected_revision" to expectedRevision, "lease_token" to lease.token,
                    )
                    if (revoke) fields += "revoke" to true else fields += "role" to role
                    val answer = writeApi.send(ManagementRequest("PUT", TowerAPI.base(workspace.id) + "/scopes/$scope/grants", jsonObjectOf(*fields.toTypedArray()), ManagementOperation.MANAGE_SCOPE))
                    storeScopeAccess(ScopeAdministrationAccess.parse(answer), scope)
                } finally {
                    withContextNonCancellable { runCatching { api.releaseLease(workspace.id, workspace.appNpub, lease) } }
                }
            }
        }
    }

    // MARK: Writes

    /**
     * Validates, re-reads the member's authority, checks the exact resource authority when the member is not an
     * administrator, sends the one request, and reads the affected list back. A refusal leaves the caller's draft alone;
     * a lost reply is never retried here (Tower may have accepted it), so the screen tells the person to inspect current state.
     */
    suspend fun administer(command: AdministrationCommand): AdministrationResult {
        val request = command.request(workspace.id, now)
        val body = writeLock.withLock {
            requireOpen()
            job("admin:write:${newRequestId()}") {
                val fresh = writeApi.membership()
                requireOpen()
                if (!fresh.canAdminister) authorizeExactResource(command, fresh)
                // The last look before the request leaves: a switch or close since the checks above stops it.
                requireOpen()
                writeApi.send(request)
            }
        }
        val observed = try {
            readBack(command, body)
        } catch (c: CancellationException) {
            throw c
        } catch (e: AdministrationSuperseded) {
            // The workspace in front changed after the write: Tower has it, and nothing more is read for a screen that is gone.
            return AdministrationResult(body, null)
        } catch (e: Exception) {
            throw ManagementError.SavedButNotRefreshed(e.message ?: e.toString())
        }
        return AdministrationResult(body, observed)
    }

    private suspend fun authorizeExactResource(command: AdministrationCommand, fresh: WorkspaceMembership) {
        when (command) {
            is AdministrationCommand.ChannelGrant, is AdministrationCommand.RevokeChannelGrant -> {
                val channel = if (command is AdministrationCommand.ChannelGrant) command.channel else (command as AdministrationCommand.RevokeChannelGrant).channel
                val grants = channelGrantsFor(channel)
                // The group directory is administrator-only on Tower; a direct channel manager must not need it.
                // A group-derived grant counts only against a group list read now. A saved list (from when this device last held a wider
                // role) never counts, and a refused read leaves only direct grants.
                val groups = try { fetchRows(AdministrationList.GROUPS) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
                if (!AdministrationAuthority.canManageChannelGrants(fresh, grants, groups, connection.signerNpub)) {
                    throw ManagementError.Forbidden("Channel grant management permission is required for this channel.")
                }
            }
            is AdministrationCommand.Installation -> {
                val actor = fresh.actorId ?: throw ManagementError.Forbidden("Tower did not identify you, so delegated management cannot be verified.")
                val delegations = fetchRows(AdministrationList.DELEGATIONS)
                val installations = fetchRows(AdministrationList.INSTALLATIONS)
                if (open) {
                    store.writer.setState(AdministrationKeys.list(connection.id, workspace.id, AdministrationList.DELEGATIONS), AdministrationRow.encode(delegations))
                    store.writer.setState(AdministrationKeys.list(connection.id, workspace.id, AdministrationList.INSTALLATIONS), AdministrationRow.encode(installations))
                }
                if (!AdministrationAuthority.hasInstallationDelegation(actor, command.id.lowercase(), delegations, installations.firstOrNull { it.id == command.id.lowercase() }, now())) {
                    throw ManagementError.Forbidden("An active delegation for this exact installation is required.")
                }
            }
            else -> throw ManagementError.Forbidden("Workspace administrator access is required for this control. Your draft is retained.")
        }
    }

    private suspend fun channelGrantsFor(channelId: String): List<AdministrationRow> {
        val channel = TowerAPI.uuid(channelId.lowercase())
        val body = writeApi.send(ManagementRequest("GET", TowerAPI.base(workspace.id) + "/channels/$channel/grants", null, ManagementOperation.READ_PERMISSIONS))
        val rows = (body.objects("grants") ?: emptyList()).map(AdministrationRow::parse)
        if (open) store.writer.setState(AdministrationKeys.channelGrants(connection.id, workspace.id, channel), AdministrationRow.encode(rows))
        return rows
    }

    /** Reads back what the write changed. Returns whether the fresh data shows it (null when that cannot be checked). */
    private suspend fun readBack(command: AdministrationCommand, body: JsonObject): Boolean? {
        suspend fun reload(list: AdministrationList): List<AdministrationRow> {
            requireOpen()
            val rows = job("admin:list:${list.storageName}") { fetchRows(list) }
            if (open) store.writer.setState(AdministrationKeys.list(connection.id, workspace.id, list), AdministrationRow.encode(rows))
            return rows
        }
        if (!isOpen) return null
        return when (command) {
            is AdministrationCommand.Profile, is AdministrationCommand.DeleteWorkspace -> { workspaceChanged(); null }
            is AdministrationCommand.AddMember -> reload(AdministrationList.MEMBERS).any { it.fields["npub"] == command.npub }
            is AdministrationCommand.MemberProfile -> reload(AdministrationList.MEMBERS).any { it.id == command.actor.lowercase() && it.title == command.name.trim() }
            is AdministrationCommand.CreateGroup -> reload(AdministrationList.GROUPS).any { it.title.equals(command.name.trim(), ignoreCase = true) }
            is AdministrationCommand.GroupMember -> {
                reload(AdministrationList.GROUPS).firstOrNull { it.id == command.group.lowercase() }?.let { GroupDirectory.contains(it, command.npub) } ?: false
            }
            is AdministrationCommand.EnsureGroupMember -> {
                val groups = reload(AdministrationList.GROUPS)
                reload(AdministrationList.MEMBERS)
                groups.firstOrNull { it.id == command.group.lowercase() }?.let { GroupDirectory.contains(it, command.npub) } ?: false
            }
            is AdministrationCommand.RemoveGroupMember -> {
                reload(AdministrationList.GROUPS).firstOrNull { it.id == command.group.lowercase() }?.children?.none { it.id == command.actor.lowercase() } ?: false
            }
            is AdministrationCommand.ChildGroup -> {
                val has = reload(AdministrationList.GROUPS).firstOrNull { it.id == command.parent.lowercase() }?.childGroupIds?.contains(command.child.lowercase())
                has?.let { it != command.remove }
            }
            is AdministrationCommand.ChannelGrant -> {
                val target = command.principal.lowercase()
                channelGrants(command.channel).firstOrNull { it.fields["principal_type"] == command.principalType && it.fields["principal_id"]?.lowercase() == target }
                    ?.let { it.fields["access_level"] == command.access } ?: false
            }
            is AdministrationCommand.RevokeChannelGrant -> {
                val target = command.principal.lowercase()
                channelGrants(command.channel).none { it.fields["principal_type"] == command.principalType && it.fields["principal_id"]?.lowercase() == target }
            }
            is AdministrationCommand.Delegation -> reload(AdministrationList.DELEGATIONS).any {
                it.fields["delegate_actor_id"] == command.actor.lowercase() && it.fields["revoked_at"] == null
            }
            is AdministrationCommand.RevokeDelegation -> reload(AdministrationList.DELEGATIONS).firstOrNull { it.id == command.id.lowercase() }?.let { it.fields["revoked_at"] != null }
            is AdministrationCommand.Installation -> { reload(AdministrationList.INSTALLATIONS); null }
            is AdministrationCommand.Publishing -> reload(AdministrationList.PUBLISHING).any { it.id == command.id.lowercase() }
            is AdministrationCommand.DisablePublishing, is AdministrationCommand.RevokePublishing -> { reload(AdministrationList.PUBLISHING); null }
            is AdministrationCommand.RotatePublisher -> reload(AdministrationList.PUBLISHING).firstOrNull { it.id == command.id.lowercase() }?.let {
                it.fields["publisher_npub"] == command.replacement
            }
        }
    }

    // MARK: Public workspace avatar

    /**
     * The web's workspace-avatar public-storage contract (separate from private attachments): prepare a public object, upload the
     * already metadata-stripped PNG, complete it, and return the `storage://` address for the profile form. The person still
     * saves the workspace to use it. Administrator only, checked on a `/me` read made now; signed as the current user.
     */
    suspend fun uploadWorkspaceAvatar(png: ByteArray): String {
        requireOpen()
        val owner = workspace.ownerNpub
        if (png.isEmpty() || png.size > MAX_AVATAR_BYTES || owner == null || !NostrKeys.isValidNpub(owner)) {
            throw ManagementError.Invalid("Choose an image smaller than 8 MB for a workspace with a known owner.")
        }
        return writeLock.withLock {
            requireOpen()
            job("admin:write:${newRequestId()}") {
                if (!writeApi.membership().canAdminister) throw ManagementError.Forbidden("Workspace administrator access is required to upload a public avatar.")
                requireOpen()
                val prepared = writeApi.send(ManagementRequest("POST", "/api/v4/storage/prepare", jsonObjectOf(
                    "owner_npub" to owner, "content_type" to "image/png", "size_bytes" to png.size, "file_name" to "workspace-avatar.png", "is_public" to true,
                    "metadata" to jsonObjectOf("purpose" to "workspace-profile/avatar", "visibility" to "public", "workspace_id" to workspace.id),
                ), ManagementOperation.ADMINISTER))
                val id = prepared.string("object_id"); val upload = prepared.string("upload_url")
                if (id == null || upload == null || !isUUID(id) || (prepared["is_public"] as? JsonPrimitive)?.content != "true") {
                    throw ManagementError.Unexpected("Tower did not confirm a public avatar upload.")
                }
                requireOpen()
                val api = TowerAPI(writeClient)
                try {
                    api.upload(PreparedObject(id.lowercase(), upload), png, "image/png")
                    api.completeStorage(id.lowercase(), png.size.toLong(), Sha256Hex.of(png))
                } catch (c: CancellationException) {
                    throw c
                } catch (e: ManagementError) {
                    throw e
                } catch (e: Exception) {
                    throw ManagementError.Offline("The avatar could not be uploaded (${e.message ?: e::class.simpleName}).")
                }
                "storage://" + id.lowercase()
            }
        }
    }

    // MARK: Group enrolment (explicit, checked, never a blind retry)

    /**
     * Adds an existing workspace member to a group: refreshes members and groups first, refuses a person who is not in the
     * workspace (workspace enrolment is a separate, explicit step) and treats an edge that already exists as done.
     */
    suspend fun addExistingGroupPerson(group: String, npub: String): AdministrationResult? {
        AdministrationCommand.GroupMember(group, npub).request(workspace.id)
        refreshForced(AdministrationList.MEMBERS); refreshForced(AdministrationList.GROUPS)
        val members = cached(AdministrationList.MEMBERS); val groups = cached(AdministrationList.GROUPS)
        if (members.none { it.fields["npub"] == npub }) {
            throw ManagementError.Invalid("This person is not in the workspace. Workspace enrolment is required before adding them to a group.")
        }
        val target = groups.firstOrNull { it.id == group.lowercase() } ?: throw ManagementError.NotFound("This group is no longer available.")
        if (GroupDirectory.contains(target, npub)) return null
        return administer(AdministrationCommand.GroupMember(group, npub))
    }

    /**
     * Tower's additive atomic contract: enrolment and the group edge commit together and an existing role and kind are
     * preserved. There is deliberately no fallback to the role-replacing `POST /members` and no automatic retry: an older
     * Tower's refusal keeps the draft and says safe enrolment may be unavailable.
     */
    suspend fun enrolGroupPerson(group: String, npub: String, kind: String): AdministrationResult? {
        AdministrationCommand.EnsureGroupMember(group, npub, kind).request(workspace.id)
        refreshForced(AdministrationList.GROUPS)
        val target = cached(AdministrationList.GROUPS).firstOrNull { it.id == group.lowercase() } ?: throw ManagementError.NotFound("This group is no longer available.")
        if (GroupDirectory.contains(target, npub)) return null
        try {
            return administer(AdministrationCommand.EnsureGroupMember(group, npub, kind))
        } catch (e: ManagementError.NotFound) {
            throw ManagementError.NotFound(
                "Tower could not add this identity. This server may not support safe workspace enrolment yet, or the group changed. Refresh and check before trying again. Your draft is retained.",
            )
        }
    }

    /** A refresh whose failure stops the caller: the next step depends on current data. */
    private suspend fun refreshForced(list: AdministrationList) {
        when (val r = refresh(list)) {
            ListRefresh.Fresh -> {}
            is ListRefresh.Restricted -> throw ManagementError.Forbidden(r.message)
            is ListRefresh.Unsupported -> throw ManagementError.NotFound(r.message)
            is ListRefresh.Failed -> throw ManagementError.Offline(r.message)
        }
    }

    // MARK: Managed app activation (user-consented Autopilot origin)

    /**
     * Loads the activation catalog from the Autopilot origin the person typed and confirmed (one signed `GET` at one exact path; nothing from
     * Tower metadata selects the host and a redirect is refused). Entries that name a different Autopilot are counted, not offered.
     */
    suspend fun activationCatalog(origin: ConfirmedAutopilotOrigin): ActivationCatalog {
        requireOpen()
        return job("admin:activation-catalog") {
            identity.requireSigner(connection.signerNpub)
            requireOpen()
            val data = try {
                TowerClient(origin.origin, AutopilotFlowGuard(readTransport, origin.origin, "GET", AutopilotFlowGuard.CATALOG_PATH), identity).request(path = AutopilotFlowGuard.CATALOG_PATH)
            } catch (c: CancellationException) { throw c } catch (e: BoundaryViolation) { throw e } catch (e: Exception) { throw activationFailure(e, "catalog") }
            requireOpen()
            val apps = (parseJsonObject(data, "activation catalog").objects("apps") ?: emptyList()).map(::activationApp)
            val offered = apps.filter { it.origin == origin.url }
            ActivationCatalog(offered, apps.size - offered.size)
        }
    }

    private fun activationFailure(e: Exception, what: String): ManagementError = when {
        e is TowerError.Http && e.status in 300..399 -> ManagementError.Unexpected("Autopilot answered with a redirect. Redirects are refused, so nothing was ${if (what == "catalog") "loaded" else "claimed"}.")
        e is TowerError.Http && e.status == 403 -> ManagementError.Forbidden("Autopilot refused this request.")
        e is TowerError -> ManagementError.Offline(e.message ?: e.toString())
        else -> ManagementError.Offline(e.message ?: e.toString())
    }

    private fun activationApp(row: JsonObject): ActivationApp {
        fun field(key: String) = row.string(key)?.takeIf { it.isNotEmpty() } ?: throw ManagementError.Unexpected("Activation catalog entry is incomplete.")
        val npub = field("autopilot_npub")
        if (!NostrKeys.isValidNpub(npub)) throw ManagementError.Unexpected("Activation catalog entry is incomplete.")
        val origin = TowerOrigin(field("autopilot_origin")).url
        return ActivationApp(
            id = field("app_id"), version = field("app_version"), title = row.string("title") ?: field("app_id"), launchUrl = field("launch_url"),
            origin = origin, autopilotNpub = npub, viewOrigin = TowerOrigin(row.string("view_origin") ?: origin).url,
        )
    }

    /**
     * One intent creation, then the caller's separate claim ([processActivationIntent]). Authority is an administrator, or
     * an active delegation of the member that covers this app and destination. The returned [ActivationIntent] names the origin and
     * the boundary it belongs to; the caller persists it before claiming. A failure may retry processing that exact intent and
     * must never create another installation (a repeated create with the same request id is Tower's idempotent replay).
     */
    suspend fun createActivationIntent(
        app: ActivationApp, origin: ConfirmedAutopilotOrigin, scope: String, channel: String, installation: String, requestId: String, delegation: String?,
    ): ActivationIntent {
        if (origin.url != app.origin) throw ManagementError.Invalid("The catalog app belongs to a different Autopilot origin.")
        if (!isUUID(scope) || !isUUID(channel) || !isUUID(installation) || !isUUID(requestId) || delegation?.let { !isUUID(it) } == true) {
            throw ManagementError.Invalid("Choose a destination channel.")
        }
        val fields = mutableListOf<Pair<String, Any?>>(
            "client_request_id" to requestId.lowercase(), "app_id" to app.id, "app_version" to app.version, "wapp_installation_id" to installation.lowercase(),
            "title" to app.title, "launch_url" to app.launchUrl, "autopilot_origin" to app.origin, "autopilot_npub" to app.autopilotNpub,
            "registered_open_origins" to listOf(app.viewOrigin), "capabilities" to listOf("activity.publish"),
            "destinations" to listOf(jsonObjectOf("scope_id" to scope.lowercase(), "channel_id" to channel.lowercase())),
        )
        if (delegation != null) fields += "delegation_id" to delegation.lowercase()
        val answer = writeLock.withLock {
            requireOpen()
            job("admin:write:${newRequestId()}") {
                val fresh = writeApi.membership()
                if (!fresh.canAdminister) {
                    val actor = fresh.actorId
                    val covers = delegation != null && actor != null &&
                        AdministrationAuthority.delegationCovers(actor, delegation.lowercase(), app.id, scope.lowercase(), channel.lowercase(), fetchRows(AdministrationList.DELEGATIONS), now())
                    if (!covers) throw ManagementError.Forbidden("Administrator or an active delegation covering this app and destination is required.")
                }
                requireOpen()
                writeApi.send(ManagementRequest("POST", TowerAPI.base(workspace.id) + "/wapp-install-intents", jsonObjectOf(*fields.toTypedArray()), ManagementOperation.ADMINISTER))
            }
        }
        val id = answer.obj("intent")?.string("id")
        if (id == null || !isUUID(id)) throw ManagementError.Unexpected("Tower did not return an activation intent. Check the intent list before retrying.")
        return ActivationIntent(id.lowercase(), origin.url, boundary.id)
    }

    /**
     * Claims the saved intent at the Autopilot origin (one signed `POST` at one exact path whose body is this workspace and this intent).
     * The intent must belong to this boundary and to this origin, so a changed origin, account or workspace cannot carry a saved intent
     * into another claim, and a closed or moved-away screen sends nothing. Unlike every other write this does not read `/me`: it carries no
     * authority of its own, it asks Autopilot to process an intent Tower already accepted for this person, and Autopilot and Tower decide it.
     */
    suspend fun processActivationIntent(origin: ConfirmedAutopilotOrigin, intent: ActivationIntent) {
        if (intent.boundary != boundary.id) throw BoundaryViolation("activation intent")
        if (intent.origin != origin.url) throw ManagementError.Invalid("This intent was created for ${intent.origin}, not for ${origin.url}. Nothing was claimed.")
        if (!isUUID(intent.id)) throw ManagementError.Invalid("No saved intent is available.")
        writeLock.withLock {
            requireOpen()
            job("admin:write:${newRequestId()}") {
                identity.requireSigner(connection.signerNpub)
                requireOpen()
                val guard = AutopilotFlowGuard(writeTransport, origin.origin, "POST", AutopilotFlowGuard.PROCESS_PATH, AutopilotFlowGuard.processBody(workspace.id, intent.id.lowercase()))
                try {
                    TowerClient(origin.origin, guard, identity).request(
                        method = "POST", path = AutopilotFlowGuard.PROCESS_PATH,
                        jsonBody = jsonBody(jsonObjectOf("workspace_id" to workspace.id, "intent_id" to intent.id.lowercase())),
                    )
                } catch (c: CancellationException) { throw c } catch (e: BoundaryViolation) { throw e } catch (e: Exception) { throw activationFailure(e, "claim") }
            }
        }
    }

    // MARK: Drafts (kept on this device per connection, workspace and form; never queued)

    suspend fun loadDraft(kind: String): Map<String, String>? =
        store.reader.state(AdministrationKeys.draft(connection.id, workspace.id, kind))?.let(::decodeStrings)

    suspend fun saveDraft(kind: String, values: Map<String, String>) =
        store.writer.setState(AdministrationKeys.draft(connection.id, workspace.id, kind), encodeStrings(values))

    suspend fun clearDraft(kind: String) {
        store.writer.setState(AdministrationKeys.draft(connection.id, workspace.id, kind), null)
        store.writer.setState(AdministrationKeys.confirmedDraft(connection.id, workspace.id, kind), null)
    }

    /** The fingerprint of the last draft Tower acknowledged; an unchanged acknowledged draft is not resubmitted without an edit. */
    suspend fun confirmedFingerprint(kind: String): String? = store.reader.state(AdministrationKeys.confirmedDraft(connection.id, workspace.id, kind))
    suspend fun confirmDraft(kind: String, fingerprint: String?) = store.writer.setState(AdministrationKeys.confirmedDraft(connection.id, workspace.id, kind), fingerprint)

    // MARK: Plumbing

    /** Runs [work] as a keyed job of this workspace's scheduler; a job cancelled by a pause is reported as an interrupted request. */
    private suspend fun <T> job(key: String, work: suspend () -> T): T {
        try {
            return engine.perform(key, SyncPriority.USER) { work() }
        } catch (c: CancellationException) {
            // The caller itself being cancelled is propagated; the workspace pausing under a live caller is a visible failure.
            currentCoroutineContext().ensureActive()
            throw ManagementError.Offline("This workspace was paused while the request was in flight.")
        }
    }

    private suspend fun <T> withContextNonCancellable(block: suspend () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { block() }

    companion object {
        const val MAX_AVATAR_BYTES = 8 * 1024 * 1024
        val SCOPE_ROLES = listOf("context_editor", "scope_manager")

        fun encodeStrings(values: Map<String, String>): String = JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()

        fun decodeStrings(text: String): Map<String, String>? = runCatching {
            (TowerJson.parseToJsonElement(text) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
        }.getOrNull()
    }
}
