package studio.otherstuff.ambulando.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.otherstuff.ambulando.core.chiefofstaff.AssignmentLoadResult
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffAssignment
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffOntologyLogic
import studio.otherstuff.ambulando.core.chiefofstaff.ChiefOfStaffStore
import studio.otherstuff.ambulando.core.chiefofstaff.CohortLoadResult
import studio.otherstuff.ambulando.core.chiefofstaff.readChiefOfStaffAssignment
import studio.otherstuff.ambulando.core.chiefofstaff.readChiefOfStaffCohort
import studio.otherstuff.ambulando.core.crypto.IdentityState
import studio.otherstuff.ambulando.core.management.ScopeChannelManager
import studio.otherstuff.ambulando.core.store.channels
import studio.otherstuff.ambulando.core.store.scopes
import studio.otherstuff.ambulando.core.store.upsertChannels
import studio.otherstuff.ambulando.core.store.upsertScopes
import studio.otherstuff.ambulando.core.sync.SyncPriority
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.workspaces.WorkspaceRuntime

internal fun chiefOfStaffAssignmentSyncKey(workspaceId: String): String =
    "chief-of-staff:assignment:${workspaceId.lowercase()}"

internal fun chiefOfStaffCohortSyncKey(workspaceId: String, assignmentEpoch: Int): String =
    "chief-of-staff:cohort:${workspaceId.lowercase()}:$assignmentEpoch"

/**
 * Central SyncEngine-owned refresh for the workspace Chief of Staff assignment.
 *
 * Uses explicit coalesced keys on [WorkspaceRuntime.engine], preserves canonical channel/file identity once
 * established, enforces NStack manager provenance (without requiring admin `/groups` or `/grants` for ordinary
 * members), clears cached state on access loss, and updates UI state only if the exact boundary is still active.
 */
internal suspend fun AppModel.refreshChiefOfStaffAssignment(
    runtime: WorkspaceRuntime,
    workspace: WorkspaceRecord,
    priority: SyncPriority = SyncPriority.BACKGROUND,
    verifyAcl: Boolean = false,
): AssignmentLoadResult? {
    val ownerNpub = workspace.ownerNpub ?: return null
    val c = runtime.connection
    val boundaryId = runtime.boundary.id
    if (workspace.accessRemoved) {
        ChiefOfStaffStore.clearAssignment(runtime.store, c.id, workspace.id, workspace.appNpub)
        if (activeBoundaryId == boundaryId) chiefOfStaffAssignment = null
        return null
    }
    if (identityState !is IdentityState.Unlocked || identityState?.npub != c.signerNpub) {
        return null
    }
    return runtime.engine.perform(chiefOfStaffAssignmentSyncKey(workspace.id), priority) {
        val cached = ChiefOfStaffStore.loadCachedAssignment(runtime.store, c.id, workspace.id, ownerNpub, workspace.appNpub)
        val result = withContext(Dispatchers.IO) {
            towerAPI(c).readChiefOfStaffAssignment(
                workspaceId = workspace.id,
                ownerNpub = ownerNpub,
                appNpub = workspace.appNpub,
                verifyAcl = verifyAcl,
                expectedCanonicalChannelId = cached?.canonicalChannelId,
                expectedCanonicalFileId = cached?.canonicalFileId,
            )
        }
        when (result) {
            is AssignmentLoadResult.Ready -> {
                ChiefOfStaffStore.saveAssignment(
                    store = runtime.store,
                    connectionId = c.id,
                    workspaceId = workspace.id,
                    appNpub = workspace.appNpub,
                    assignment = result.assignment,
                    canonicalChannelId = result.channel.id,
                    canonicalFileId = result.file.id,
                )
            }
            AssignmentLoadResult.NeedsSetup, is AssignmentLoadResult.NeedsFile -> {
                ChiefOfStaffStore.clearAssignment(runtime.store, c.id, workspace.id, workspace.appNpub)
            }
        }
        val activeAssignment = (result as? AssignmentLoadResult.Ready)?.assignment
        if (activeBoundaryId == boundaryId &&
            identityState is IdentityState.Unlocked &&
            identityState?.npub == c.signerNpub &&
            this.workspace?.id == workspace.id &&
            this.workspace?.accessRemoved == false
        ) {
            chiefOfStaffAssignment = activeAssignment
        }
        result
    }
}

/**
 * Central SyncEngine-owned read for the private owner+assigned-agent ontology cohort for [assignment].
 * Never caches private ontology prose to shared/unscoped storage.
 */
internal suspend fun AppModel.loadChiefOfStaffCohort(
    runtime: WorkspaceRuntime,
    workspace: WorkspaceRecord,
    assignment: ChiefOfStaffAssignment,
    priority: SyncPriority = SyncPriority.VISIBLE,
): CohortLoadResult {
    val ownerNpub = workspace.ownerNpub ?: return CohortLoadResult.Restricted
    val agentNpub = assignment.assignedNpub ?: return CohortLoadResult.PendingSetup(assignment.assignmentEpoch)
    val c = runtime.connection
    if (workspace.accessRemoved || identityState !is IdentityState.Unlocked || identityState?.npub != c.signerNpub) {
        return CohortLoadResult.Restricted
    }
    return runtime.engine.perform(chiefOfStaffCohortSyncKey(workspace.id, assignment.assignmentEpoch), priority) {
        withContext(Dispatchers.IO) {
            towerAPI(c).readChiefOfStaffCohort(
                workspaceId = workspace.id,
                ownerNpub = ownerNpub,
                appNpub = workspace.appNpub,
                viewerNpub = c.signerNpub,
                agentNpub = agentNpub,
                assignmentEpoch = assignment.assignmentEpoch,
            )
        }
    }
}

/**
 * Opens the viewer's OWN authorized 2-party DM channel with the active workspace's assigned Chief of Staff.
 *
 * Always revalidates the active assignment via SyncEngine before routing so a stale in-memory sidebar
 * shortcut cannot open a DM to a removed or superseded Chief of Staff. Never opens another member's DM,
 * never creates duplicates, and creates a new DM only via [ScopeChannelManager.createDirectMessage].
 */
internal suspend fun AppModel.openChiefOfStaffDirectMessage(entry: WorkspaceEntry): ChannelRecord {
    fun checkCurrent() {
        check(
            activeEntry === entry &&
                entry.enabled &&
                !entry.workspace.accessRemoved &&
                identityState is IdentityState.Unlocked &&
                identityState?.npub == entry.connection.signerNpub,
        ) { "Unlock the original account and workspace to message your Chief of Staff." }
    }
    checkCurrent()
    val runtime = requireNotNull(entry.runtime) { "Workspace is not open." }
    val c = entry.connection
    val w = entry.workspace
    val viewerNpub = c.signerNpub

    // Revalidate active assignment before routing (never trust a stale in-memory shortcut).
    val refreshed = refreshChiefOfStaffAssignment(runtime, w, priority = SyncPriority.USER)
    checkCurrent()
    val currentAssignment = (refreshed as? AssignmentLoadResult.Ready)?.assignment
    val agentNpub = currentAssignment?.assignedNpub
        ?: error("No Chief of Staff is currently assigned in this workspace.")
    check(agentNpub != viewerNpub) { "You cannot open a Chief of Staff direct message with yourself." }

    // 1. Check local scopes & channels for an existing 2-party viewer+agent DM.
    val localScopes = runtime.store.reader.scopes(w.id)
    val localChannels = runtime.store.reader.channels(w.id)
    ChiefOfStaffOntologyLogic.findViewerDirectMessage(localScopes, localChannels, viewerNpub, agentNpub)?.let { existing ->
        checkCurrent()
        return existing
    }

    // 2. Refresh scopes & DM channels from Tower before creating so we never create a duplicate DM.
    val api = towerAPI(c)
    val remoteScopes = withContext(Dispatchers.IO) { api.scopes(w.id, w.appNpub) }
    checkCurrent()
    runtime.store.writer.upsertScopes(w.id, remoteScopes, complete = true)
    val dmScope = ChiefOfStaffOntologyLogic.directMessageScope(remoteScopes)
        ?: error("Direct messages are not available in this workspace.")
    val remoteDmChannels = withContext(Dispatchers.IO) { api.channels(w.id, dmScope.id, w.appNpub) }
    checkCurrent()
    runtime.store.writer.upsertChannels(w.id, dmScope.id, remoteDmChannels, complete = true)

    ChiefOfStaffOntologyLogic.findViewerDirectMessage(remoteScopes, remoteDmChannels, viewerNpub, agentNpub)?.let { existing ->
        checkCurrent()
        return existing
    }

    // 3. Create viewer's own DM only via existing ScopeChannelManager.createDirectMessage.
    val manager = ScopeChannelManager(runtime.store, identity, runtime.directTransport)
    val created = try {
        withContext(Dispatchers.IO) { manager.createDirectMessage(c, w, dmScope.id, agentNpub) }
    } catch (e: CancellationException) {
        throw e
    }
    checkCurrent()
    return created
}
