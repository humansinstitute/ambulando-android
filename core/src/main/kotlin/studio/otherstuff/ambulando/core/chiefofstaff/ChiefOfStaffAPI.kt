package studio.otherstuff.ambulando.core.chiefofstaff

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import studio.otherstuff.ambulando.core.chat.members
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.store.setState
import studio.otherstuff.ambulando.core.store.state
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.FileRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.VersionOutcome
import studio.otherstuff.ambulando.core.transport.addFileVersion
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.lowerUUID
import studio.otherstuff.ambulando.core.transport.newRequestId
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.store
import studio.otherstuff.ambulando.core.transport.string
import java.time.Instant

sealed class ChiefOfStaffError(message: String) : Exception(message, null, false, false) {
    class Forbidden(message: String) : ChiefOfStaffError(message)
    class Conflict(message: String) : ChiefOfStaffError(message)
    class NotInitialized(message: String) : ChiefOfStaffError(message)
    class Restricted(message: String) : ChiefOfStaffError(message)
    class BoundaryMismatch(message: String) : ChiefOfStaffError(message)
    class Integrity(message: String) : ChiefOfStaffError(message)
    class Invalid(message: String) : ChiefOfStaffError(message)
    class Offline(message: String) : ChiefOfStaffError(message)
}

sealed interface AssignmentLoadResult {
    data object NeedsSetup : AssignmentLoadResult
    data class NeedsFile(val channel: ChannelRecord) : AssignmentLoadResult
    data class Ready(
        val channel: ChannelRecord,
        val file: FileRecord,
        val versionId: String,
        val assignment: ChiefOfStaffAssignment,
    ) : AssignmentLoadResult
}

sealed interface CohortLoadResult {
    /** Viewer is not an authorized principal of the owner+agent private ontology cohort. */
    data object Restricted : CohortLoadResult
    /** Assignment exists, but the private cohort channel/files for this assignment epoch have not been provisioned yet. */
    data class PendingSetup(val assignmentEpoch: Int) : CohortLoadResult
    data class Ready(
        val channel: ChannelRecord,
        val assignmentEpoch: Int,
        val jobStatus: ChiefOfStaffJobStatus,
        val jobStatusVersionId: String,
        val ontology: ChiefOfStaffOntology,
        val ontologyVersionId: String,
        val corrections: ChiefOfStaffCorrections,
        val correctionsFileId: String,
        val correctionsVersionId: String,
    ) : CohortLoadResult {
        val applied: ChiefOfStaffAppliedOntology
            get() = ChiefOfStaffOntologyLogic.applyCorrections(ontology.topics, ontology.evidence, corrections.corrections)
    }
}

private suspend fun TowerAPI.checkGrantsComplete(workspaceId: String, appNpub: String, channelId: String): List<JsonObject> {
    val raw = client.request(
        path = TowerAPI.base(workspaceId) + "/channels/${TowerAPI.uuid(channelId)}/grants",
        appNpub = appNpub,
    )
    val acl = parseJsonObject(raw, "chief of staff grants")
    if (acl.bool("partial") == true || acl.bool("complete") == false || acl.bool("has_more") == true || acl.string("next_cursor") != null) {
        throw ChiefOfStaffError.Integrity("Channel grants are incomplete.")
    }
    return acl.objects("grants") ?: throw ChiefOfStaffError.Integrity("Channel grants could not be parsed.")
}

private suspend fun <T> TowerAPI.readVerifiedChiefOfStaffFile(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    expectedChannelId: String,
    adapter: ChiefOfStaffEnvelopeAdapter,
    parseDocument: (JsonObject) -> T,
): Triple<FileRecord, String, T> {
    val resp = parseJsonObject(
        client.request(path = TowerAPI.base(workspaceId) + "/files/${TowerAPI.uuid(fileId)}", appNpub = appNpub),
        "chief of staff file",
    )
    val fileObj = resp.obj("file") ?: throw ChiefOfStaffError.Invalid("Chief of Staff file could not be read.")
    val file = FileRecord.parse(fileObj)
    if (file.id != fileId.lowercase() || file.workspaceId != workspaceId.lowercase() || file.channelId != expectedChannelId.lowercase()) {
        throw ChiefOfStaffError.BoundaryMismatch("Chief of Staff file boundary mismatch.")
    }
    val versionId = lowerUUID(file.raw.string("current_version_id"))
        ?: throw ChiefOfStaffError.Integrity("Chief of Staff file has no saved version.")
    val meta = file.raw.obj("object")?.obj("storage_object")
        ?: throw ChiefOfStaffError.Integrity("Chief of Staff file has no verified storage object.")
    val sha = meta.string("sha256_hex")?.lowercase()
        ?: throw ChiefOfStaffError.Integrity("Chief of Staff file has no content digest.")
    val size = flexibleLong(meta["size_bytes"])
        ?: throw ChiefOfStaffError.Integrity("Chief of Staff file size is missing.")
    if (size !in 0..ChiefOfStaffContract.MAX_BYTES.toLong() || meta.bool("is_public") != false) {
        throw ChiefOfStaffError.Integrity("Unverified or public Chief of Staff file object.")
    }
    val bytes = client.request(
        path = TowerAPI.base(workspaceId) + "/files/${TowerAPI.uuid(file.id)}/versions/${TowerAPI.uuid(versionId)}/object",
        appNpub = appNpub,
    )
    if (bytes.size.toLong() != size || Sha256Hex.of(bytes) != sha) {
        throw ChiefOfStaffError.Integrity("Chief of Staff file content digest mismatch.")
    }
    val payload = ChiefOfStaffEnvelope.decode(bytes, adapter)
    return Triple(file, versionId, parseDocument(payload))
}

private suspend fun TowerAPI.writeVerifiedChiefOfStaffVersion(
    workspaceId: String,
    appNpub: String,
    fileId: String,
    baseVersionId: String,
    bytes: ByteArray,
    mime: String,
    fileName: String,
): String {
    val objectId = store(workspaceId, appNpub, bytes, mime, fileName, null) {}
    return when (val outcome = addFileVersion(workspaceId, appNpub, fileId, baseVersionId, objectId, newRequestId())) {
        is VersionOutcome.Saved -> {
            val savedVersionId = outcome.versionId ?: outcome.file.currentVersionId
                ?: throw ChiefOfStaffError.Integrity("Version write outcome uncertain; refresh before retrying.")
            val readback = client.request(
                path = TowerAPI.base(workspaceId) + "/files/${TowerAPI.uuid(fileId)}/versions/${TowerAPI.uuid(savedVersionId)}/object",
                appNpub = appNpub,
            )
            if (Sha256Hex.of(readback) != Sha256Hex.of(bytes)) {
                throw ChiefOfStaffError.Integrity("Chief of Staff file readback digest mismatch.")
            }
            savedVersionId
        }
        is VersionOutcome.Conflict -> throw ChiefOfStaffError.Conflict("This record changed elsewhere (${outcome.code}). Refresh and try again.")
        is VersionOutcome.Denied -> {
            if (outcome.status == 403) throw ChiefOfStaffError.Forbidden(outcome.message)
            throw ChiefOfStaffError.Invalid(outcome.message)
        }
        is VersionOutcome.Retryable -> throw ChiefOfStaffError.Offline(outcome.message)
    }
}

private data class VerifiedManager(val actorId: String, val actorNpub: String)

private suspend fun TowerAPI.assertChiefOfStaffManager(
    workspaceId: String,
    ownerNpub: String,
    appNpub: String,
): VerifiedManager {
    val me = parseJsonObject(
        client.request(path = TowerAPI.base(workspaceId) + "/me", appNpub = appNpub),
        "me",
    )
    val identity = me.obj("identity")
    if (identity?.string("workspace_id")?.lowercase() != workspaceId.lowercase() ||
        identity.string("workspace_owner_npub") != ownerNpub ||
        identity.string("app_npub") != appNpub
    ) {
        throw ChiefOfStaffError.BoundaryMismatch("Workspace identity boundary mismatch.")
    }
    // Server-computed effective permissions only; membership role labels are not authority.
    val perms = (me["permissions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet().orEmpty()
    if ("workspace.manage" !in perms) {
        throw ChiefOfStaffError.Forbidden("Workspace manager access is required to change the Chief of Staff assignment.")
    }
    val actor = me.obj("actor")
    val actorId = lowerUUID(actor?.string("actor_id") ?: actor?.string("id"))
        ?: throw ChiefOfStaffError.Invalid("Manager actor_id is missing.")
    val actorNpub = actor?.string("npub")?.takeIf(ChiefOfStaffContract::isValidNpub)
        ?: throw ChiefOfStaffError.Invalid("Manager npub is missing.")
    return VerifiedManager(actorId, actorNpub)
}

/** The caller as a verified manager when its own `/me` effective permissions include `workspace.manage`; otherwise null. */
private suspend fun TowerAPI.callerVerifiedManager(workspaceId: String, ownerNpub: String, appNpub: String): VerifiedManager? =
    try {
        assertChiefOfStaffManager(workspaceId, ownerNpub, appNpub)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private suspend fun TowerAPI.rawMembersWithRoles(workspaceId: String, appNpub: String): List<ChiefOfStaffAcl.MemberRoleEntry> {
    return client.list(TowerAPI.base(workspaceId) + "/members", appNpub, "members").mapNotNull { raw ->
        val actor = raw.obj("actor")
        val id = lowerUUID(raw.string("actor_id") ?: actor?.string("actor_id") ?: actor?.string("id") ?: raw.string("id")) ?: return@mapNotNull null
        val npub = (raw.string("npub") ?: actor?.string("npub"))?.takeIf(ChiefOfStaffContract::isValidNpub) ?: return@mapNotNull null
        val role = raw.string("role") ?: raw.obj("membership")?.string("role")
        val permsArray = (raw["permissions"] as? JsonArray)
            ?: (raw["effective_permissions"] as? JsonArray)
            ?: (raw.obj("membership")?.get("permissions") as? JsonArray)
            ?: (raw.obj("membership")?.get("effective_permissions") as? JsonArray)
        val perms = permsArray?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet().orEmpty()
        // Strict server-derived boolean only (missing, false, or non-boolean string fails closed).
        val canManageWorkspace = raw.obj("authority")?.get("can_manage_workspace")
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull } == true
        ChiefOfStaffAcl.MemberRoleEntry(id, npub, role, perms, canManageWorkspace)
    }
}

private suspend fun TowerAPI.rawGroups(workspaceId: String, appNpub: String): List<ChiefOfStaffAcl.GroupEntry> {
    return client.list(TowerAPI.base(workspaceId) + "/groups", appNpub, "groups").mapNotNull { raw ->
        val id = lowerUUID(raw.string("id") ?: raw.string("group_id")) ?: return@mapNotNull null
        val effRows = raw.objects("effective_members") ?: raw.objects("members")
        val effIds = effRows?.mapNotNull { m -> lowerUUID(m.string("actor_id") ?: m.string("id")) }?.toSet()
        ChiefOfStaffAcl.GroupEntry(id, raw.string("kind") ?: raw.string("group_kind"), raw.string("name"), effIds)
    }
}

/**
 * Discovers and reads the workspace Chief of Staff assignment.
 *
 * Ordinary read-only workspace members call this with [verifyAcl] = false so reading NEVER requires
 * administrator-only `/groups` or `/grants` inventory calls, while STILL verifying authoritative NStack
 * channel/file/current-version manager provenance against `/members` (failing closed on forged non-manager
 * channels/files or ambiguous candidates). Once a canonical channel/file identity is established, passing
 * [expectedCanonicalChannelId] / [expectedCanonicalFileId] preserves it and rejects substitution.
 */
suspend fun TowerAPI.readChiefOfStaffAssignment(
    workspaceId: String,
    ownerNpub: String,
    appNpub: String,
    verifyAcl: Boolean = false,
    expectedCanonicalChannelId: String? = null,
    expectedCanonicalFileId: String? = null,
    adapter: ChiefOfStaffEnvelopeAdapter = PlaintextEnvelopeAdapter,
): AssignmentLoadResult {
    val ws = workspaceId.lowercase()
    val memberRoles = rawMembersWithRoles(ws, appNpub)
    val found = ArrayList<ChannelRecord>()
    for (scope in scopes(ws, appNpub)) {
        if (scope.kind == "dm" || scope.accessRemoved || !ChiefOfStaffContract.isActiveRecord(scope.raw)) continue
        for (ch in channels(ws, scope.id, appNpub)) {
            if (ChiefOfStaffContract.isAssignmentChannel(ch, ws, appNpub)) found += ch
        }
    }
    if (found.size > 1) throw ChiefOfStaffError.Integrity("Multiple Chief of Staff assignment channels need reconciliation.")
    val channel = found.singleOrNull() ?: return AssignmentLoadResult.NeedsSetup
    if (expectedCanonicalChannelId != null && channel.id != expectedCanonicalChannelId.lowercase()) {
        throw ChiefOfStaffError.Integrity("Chief of Staff assignment channel identity changed unexpectedly.")
    }

    val additionalManagers = LinkedHashMap<String, String>()
    if (verifyAcl) {
        val groups = try {
            rawGroups(ws, appNpub)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        // The caller's own server-computed effective workspace.manage (from `/me`) also verifies the caller; group
        // names and membership role labels are never authority.
        callerVerifiedManager(ws, ownerNpub, appNpub)?.let { additionalManagers[it.actorId] = it.actorNpub }
        val grants = checkGrantsComplete(ws, appNpub, channel.id)
        try {
            ChiefOfStaffAcl.verifyAssignmentChannelGrants(grants, memberRoles, groups, ownerNpub, additionalManagers.keys)
        } catch (e: IllegalArgumentException) {
            throw ChiefOfStaffError.Integrity(e.message ?: "Chief of Staff assignment grants failed verification.")
        }
    }

    val files = client.list(TowerAPI.base(ws) + "/channels/${TowerAPI.uuid(channel.id)}/files", appNpub, "files")
        .map(FileRecord::parse)
        .filter { ChiefOfStaffContract.isAssignmentFile(it, ws, appNpub) }
    if (files.size > 1) throw ChiefOfStaffError.Integrity("Multiple Chief of Staff assignment files need reconciliation.")
    val file = files.singleOrNull() ?: return AssignmentLoadResult.NeedsFile(channel)
    if (expectedCanonicalFileId != null && file.id != expectedCanonicalFileId.lowercase()) {
        throw ChiefOfStaffError.Integrity("Chief of Staff assignment file identity changed unexpectedly.")
    }

    val expected = ExpectedWorkspaceContext(ws, ownerNpub, appNpub)
    val (verifiedFile, versionId, assignment) = readVerifiedChiefOfStaffFile(ws, appNpub, file.id, channel.id, adapter) {
        ChiefOfStaffAssignment.parse(it, expected)
    }
    try {
        ChiefOfStaffAcl.verifyManagerProvenance(
            channelRaw = channel.raw,
            fileRaw = verifiedFile.raw,
            members = memberRoles,
            ownerNpub = ownerNpub,
            payloadUpdatedByActorId = assignment.updatedByActorId,
            payloadUpdatedByNpub = assignment.updatedByNpub,
            additionalVerifiedManagers = additionalManagers,
        )
    } catch (e: IllegalArgumentException) {
        throw ChiefOfStaffError.Integrity(e.message ?: "Chief of Staff assignment provenance could not be verified.")
    }
    return AssignmentLoadResult.Ready(channel, verifiedFile, versionId, assignment)
}

/**
 * Provisions the manager-writable / workspace-readable Chief of Staff assignment channel and initial file.
 * Manager authority is checked via `/me` and enforced by Tower.
 */
suspend fun TowerAPI.setupChiefOfStaffAssignment(
    workspaceId: String,
    ownerNpub: String,
    appNpub: String,
    scopeId: String,
    adapter: ChiefOfStaffEnvelopeAdapter = PlaintextEnvelopeAdapter,
    now: Instant = Instant.now(),
): AssignmentLoadResult.Ready {
    val ws = workspaceId.lowercase()
    val manager = assertChiefOfStaffManager(ws, ownerNpub, appNpub)
    var current = readChiefOfStaffAssignment(ws, ownerNpub, appNpub, verifyAcl = false, adapter = adapter)
    if (current is AssignmentLoadResult.NeedsSetup) {
        val groups = rawGroups(ws, appNpub)
        val adminsGroup = groups.firstOrNull { it.kind == "system" && it.name?.lowercase() == "admins" }
        val workspaceGroup = groups.firstOrNull { it.kind == "system" && it.name?.lowercase() == "workspace" }
        val grants = buildJsonArray {
            if (adminsGroup != null) {
                add(buildJsonObject {
                    put("principal_type", "group")
                    put("principal_id", adminsGroup.id)
                    put("access_level", "manage")
                })
            } else {
                add(buildJsonObject {
                    put("principal_type", "actor")
                    put("principal_id", manager.actorId)
                    put("access_level", "manage")
                })
            }
            if (workspaceGroup != null) {
                add(buildJsonObject {
                    put("principal_type", "group")
                    put("principal_id", workspaceGroup.id)
                    put("access_level", "view")
                })
            }
        }
        val body = buildJsonObject {
            put("name", "Chief of Staff")
            put("kind", "channel")
            put("description", "Workspace Chief of Staff assignment (manager-controlled, workspace-readable).")
            put("metadata", ChiefOfStaffContract.assignmentChannelMetadata(ws, appNpub))
            put("grants", grants)
        }
        val reply = client.exchange("POST", TowerAPI.base(ws) + "/scopes/${TowerAPI.uuid(scopeId)}/channels", appNpub, jsonBody(body))
        if (reply.status == 403) throw ChiefOfStaffError.Forbidden("You do not have permission to create the Chief of Staff assignment channel.")
        if (reply.status !in 200..299) {
            val code = parseJsonObjectOrNull(reply.body)?.string("code")
            throw ChiefOfStaffError.Invalid("Could not create assignment channel (HTTP ${reply.status}${code?.let { ", $it" } ?: ""}).")
        }
        current = readChiefOfStaffAssignment(ws, ownerNpub, appNpub, verifyAcl = true, adapter = adapter)
    }
    if (current is AssignmentLoadResult.NeedsFile) {
        val initial = ChiefOfStaffAssignment.empty(ws, ownerNpub, appNpub, manager.actorId, manager.actorNpub, now)
        val bytes = ChiefOfStaffEnvelope.encode(initial.toJson(), adapter)
        val objectId = store(ws, appNpub, bytes, ChiefOfStaffContract.MIME_ASSIGNMENT, ChiefOfStaffContract.FILE_NAME_ASSIGNMENT, null) {}
        val createBody = buildJsonObject {
            put("storage_object_id", objectId)
            put("display_name", ChiefOfStaffContract.FILE_NAME_ASSIGNMENT)
            put("metadata", ChiefOfStaffContract.assignmentFileMetadata(ws, appNpub))
        }
        val reply = client.exchange("POST", TowerAPI.base(ws) + "/channels/${TowerAPI.uuid(current.channel.id)}/files", appNpub, jsonBody(createBody))
        if (reply.status == 403) throw ChiefOfStaffError.Forbidden("You do not have permission to initialize the Chief of Staff assignment file.")
        if (reply.status !in 200..299) {
            throw ChiefOfStaffError.Invalid("Could not initialize assignment file (HTTP ${reply.status}).")
        }
    }
    return readChiefOfStaffAssignment(ws, ownerNpub, appNpub, verifyAcl = true, adapter = adapter) as? AssignmentLoadResult.Ready
        ?: throw ChiefOfStaffError.NotInitialized("Chief of Staff assignment channel could not be initialized.")
}

/**
 * Assigns, changes, or removes ([agentNpub] = null) the workspace Chief of Staff with optimistic revision +
 * `base_version_id` CAS. Assignment grants no new authority to the selected agent.
 */
suspend fun TowerAPI.setChiefOfStaffAssignment(
    workspaceId: String,
    ownerNpub: String,
    appNpub: String,
    agentNpub: String?,
    expectedRevision: Int? = null,
    scopeIdForSetup: String? = null,
    runtimeBinding: ChiefOfStaffRuntimeBinding? = null,
    preserveExistingBinding: Boolean = true,
    adapter: ChiefOfStaffEnvelopeAdapter = PlaintextEnvelopeAdapter,
    now: Instant = Instant.now(),
): AssignmentLoadResult.Ready {
    val ws = workspaceId.lowercase()
    val manager = assertChiefOfStaffManager(ws, ownerNpub, appNpub)
    var current = readChiefOfStaffAssignment(ws, ownerNpub, appNpub, verifyAcl = true, adapter = adapter)
    if (current !is AssignmentLoadResult.Ready) {
        if (scopeIdForSetup == null) throw ChiefOfStaffError.NotInitialized("Chief of Staff assignment channel is not initialized.")
        current = setupChiefOfStaffAssignment(ws, ownerNpub, appNpub, scopeIdForSetup, adapter, now)
    }
    if (expectedRevision != null && current.assignment.assignmentRevision != expectedRevision) {
        throw ChiefOfStaffError.Conflict(
            "Chief of Staff assignment changed elsewhere (expected revision $expectedRevision, found ${current.assignment.assignmentRevision}). Refresh and try again.",
        )
    }
    var assignedActorId: String? = null
    var assignedNpub: String? = null
    var assignedDisplayName: String? = null
    if (agentNpub != null) {
        val memberList = members(ws, appNpub)
        val match = memberList.firstOrNull { it.npub == agentNpub }
            ?: throw ChiefOfStaffError.Invalid("Selected Chief of Staff agent is not a current member of this workspace.")
        if (!match.isAgent) throw ChiefOfStaffError.Invalid("Chief of Staff must be an existing workspace agent.")
        assignedActorId = match.actorId.lowercase()
        assignedNpub = match.npub
        assignedDisplayName = match.displayName?.takeIf { it.isNotBlank() }
    }
    // Never reuse a previous agent's subscription_id for a different agent or after removal!
    val sameAgent = assignedNpub != null && assignedNpub == current.assignment.assignedNpub
    val effectiveBinding = when {
        assignedNpub == null -> null
        runtimeBinding != null -> runtimeBinding.copy(
            ownerNpub = ownerNpub,
            workspaceId = ws,
            agentNpub = assignedNpub,
        )
        preserveExistingBinding && sameAgent -> current.assignment.runtimeBinding?.copy(
            ownerNpub = ownerNpub,
            workspaceId = ws,
            agentNpub = assignedNpub,
        )
        else -> null
    }
    val next = ChiefOfStaffAssignment(
        workspaceId = ws,
        workspaceOwnerNpub = ownerNpub,
        appNpub = appNpub,
        assignmentRevision = current.assignment.assignmentRevision + 1,
        assignedActorId = assignedActorId,
        assignedNpub = assignedNpub,
        assignedDisplayName = assignedDisplayName,
        previousAssignedNpub = current.assignment.assignedNpub,
        updatedByActorId = manager.actorId,
        updatedByNpub = manager.actorNpub,
        updatedAt = now.toString(),
        runtimeBinding = effectiveBinding,
        extra = current.assignment.extra,
    )
    val bytes = ChiefOfStaffEnvelope.encode(next.toJson(), adapter)
    writeVerifiedChiefOfStaffVersion(
        workspaceId = ws,
        appNpub = appNpub,
        fileId = current.file.id,
        baseVersionId = current.versionId,
        bytes = bytes,
        mime = ChiefOfStaffContract.MIME_ASSIGNMENT,
        fileName = ChiefOfStaffContract.FILE_NAME_ASSIGNMENT,
    )
    return readChiefOfStaffAssignment(
        workspaceId = ws,
        ownerNpub = ownerNpub,
        appNpub = appNpub,
        verifyAcl = true,
        expectedCanonicalChannelId = current.channel.id,
        expectedCanonicalFileId = current.file.id,
        adapter = adapter,
    ) as? AssignmentLoadResult.Ready
        ?: throw ChiefOfStaffError.Integrity("Updated Chief of Staff assignment could not be verified.")
}

/**
 * Reads the private owner+assigned-agent ontology cohort for the exact [assignmentEpoch].
 *
 * Unauthorized viewers (anyone other than [ownerNpub] or [agentNpub]) receive [CohortLoadResult.Restricted]
 * immediately so private-source knowledge is never surfaced to ordinary workspace members.
 * Strict 2-actor grants are verified both before and after reading bytes.
 */
suspend fun TowerAPI.readChiefOfStaffCohort(
    workspaceId: String,
    ownerNpub: String,
    appNpub: String,
    viewerNpub: String,
    agentNpub: String,
    assignmentEpoch: Int,
    adapter: ChiefOfStaffEnvelopeAdapter = PlaintextEnvelopeAdapter,
): CohortLoadResult {
    val ws = workspaceId.lowercase()
    if (viewerNpub != ownerNpub && viewerNpub != agentNpub) {
        return CohortLoadResult.Restricted
    }
    val epoch = if (assignmentEpoch >= 1) assignmentEpoch else 1
    val found = ArrayList<ChannelRecord>()
    for (scope in scopes(ws, appNpub)) {
        if (scope.kind == "dm" || scope.accessRemoved || !ChiefOfStaffContract.isActiveRecord(scope.raw)) continue
        for (ch in channels(ws, scope.id, appNpub)) {
            if (ChiefOfStaffContract.isCohortChannel(ch, ws, appNpub, ownerNpub, agentNpub, epoch)) found += ch
        }
    }
    if (found.size > 1) throw ChiefOfStaffError.Integrity("Multiple Chief of Staff ontology cohort channels for epoch $epoch need reconciliation.")
    val channel = found.singleOrNull() ?: return CohortLoadResult.PendingSetup(epoch)

    suspend fun verifyPrivateGrants() {
        val grants = checkGrantsComplete(ws, appNpub, channel.id)
        val people = members(ws, appNpub)
        try {
            ChiefOfStaffAcl.verifyCohortChannelGrants(grants, people, ownerNpub, agentNpub)
        } catch (e: IllegalArgumentException) {
            throw ChiefOfStaffError.Restricted(e.message ?: "Private ontology cohort ACL verification failed.")
        }
    }

    verifyPrivateGrants()
    val allFiles = client.list(TowerAPI.base(ws) + "/channels/${TowerAPI.uuid(channel.id)}/files", appNpub, "files")
        .map(FileRecord::parse)
    fun pick(kind: String, mime: String): FileRecord? {
        val matches = allFiles.filter { ChiefOfStaffContract.isCohortFile(it, kind, mime, ws, appNpub, ownerNpub, agentNpub, epoch) }
        if (matches.size > 1) throw ChiefOfStaffError.Integrity("Multiple Chief of Staff $kind files need reconciliation.")
        return matches.singleOrNull()
    }
    val jobStatusFile = pick("job_status", ChiefOfStaffContract.MIME_JOB_STATUS)
    val ontologyFile = pick("ontology", ChiefOfStaffContract.MIME_ONTOLOGY)
    val correctionsFile = pick("corrections", ChiefOfStaffContract.MIME_CORRECTIONS)
    if (jobStatusFile == null || ontologyFile == null || correctionsFile == null) {
        return CohortLoadResult.PendingSetup(epoch)
    }

    val expected = ExpectedWorkspaceContext(ws, ownerNpub, appNpub)
    val (_, jobStatusVer, jobStatus) = readVerifiedChiefOfStaffFile(ws, appNpub, jobStatusFile.id, channel.id, adapter) {
        ChiefOfStaffJobStatus.parse(it, expected)
    }
    val (_, ontologyVer, ontology) = readVerifiedChiefOfStaffFile(ws, appNpub, ontologyFile.id, channel.id, adapter) {
        ChiefOfStaffOntology.parse(it, expected)
    }
    val (_, correctionsVer, corrections) = readVerifiedChiefOfStaffFile(ws, appNpub, correctionsFile.id, channel.id, adapter) {
        ChiefOfStaffCorrections.parse(it, expected)
    }
    verifyPrivateGrants()
    return CohortLoadResult.Ready(
        channel = channel,
        assignmentEpoch = epoch,
        jobStatus = jobStatus,
        jobStatusVersionId = jobStatusVer,
        ontology = ontology,
        ontologyVersionId = ontologyVer,
        corrections = corrections,
        correctionsFileId = correctionsFile.id,
        correctionsVersionId = correctionsVer,
    )
}

/**
 * Appends a durable human/manager correction to `Chief of Staff.corrections.json` using `base_version_id` CAS
 * with bounded retry on `409 stale_base_version`.
 */
suspend fun TowerAPI.appendChiefOfStaffCorrection(
    workspaceId: String,
    ownerNpub: String,
    appNpub: String,
    viewerNpub: String,
    agentNpub: String,
    assignmentEpoch: Int,
    correction: ChiefOfStaffCorrection,
    adapter: ChiefOfStaffEnvelopeAdapter = PlaintextEnvelopeAdapter,
    now: Instant = Instant.now(),
    maxAttempts: Int = 4,
): ChiefOfStaffCorrections {
    val ws = workspaceId.lowercase()
    require(correction.kind in ChiefOfStaffContract.CORRECTION_KINDS) { "Unsupported correction kind." }
    for (attempt in 0 until maxAttempts) {
        val state = readChiefOfStaffCohort(ws, ownerNpub, appNpub, viewerNpub, agentNpub, assignmentEpoch, adapter)
        if (state !is CohortLoadResult.Ready) {
            throw ChiefOfStaffError.NotInitialized("Chief of Staff topic index is not ready for corrections yet.")
        }
        val cur = state.corrections
        if (cur.corrections.any { it.id == correction.id }) return cur
        val next = cur.copy(
            corrections = cur.corrections + correction,
            updatedAt = now.toString(),
        )
        val bytes = ChiefOfStaffEnvelope.encode(next.toJson(), adapter)
        try {
            writeVerifiedChiefOfStaffVersion(
                workspaceId = ws,
                appNpub = appNpub,
                fileId = state.correctionsFileId,
                baseVersionId = state.correctionsVersionId,
                bytes = bytes,
                mime = ChiefOfStaffContract.MIME_CORRECTIONS,
                fileName = ChiefOfStaffContract.FILE_NAME_CORRECTIONS,
            )
            return next
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChiefOfStaffError.Conflict) {
            if (attempt < maxAttempts - 1) continue
            throw e
        }
    }
    throw ChiefOfStaffError.Conflict("Could not save correction after conflict retries.")
}

/**
 * Local-first workspace-bound assignment projection in [LocalStore] (`APP_STATE`).
 * Keyed by exact `(connectionId, workspaceId, appNpub)` so workspaces never share cached assignments.
 * Retains verified canonical channel/file IDs once established. Private ontology prose is NOT cached here.
 */
data class CachedChiefOfStaffAssignment(
    val assignment: ChiefOfStaffAssignment,
    val canonicalChannelId: String? = null,
    val canonicalFileId: String? = null,
)

object ChiefOfStaffStore {
    fun assignmentKey(connectionId: String, workspaceId: String, appNpub: String): String =
        "chief-of-staff:assignment:$connectionId:${workspaceId.lowercase()}:$appNpub"

    suspend fun loadCachedAssignment(
        store: LocalStore,
        connectionId: String,
        workspaceId: String,
        ownerNpub: String?,
        appNpub: String,
    ): CachedChiefOfStaffAssignment? {
        if (ownerNpub == null) return null
        val raw = store.reader.state(assignmentKey(connectionId, workspaceId, appNpub))?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val expected = ExpectedWorkspaceContext(workspaceId.lowercase(), ownerNpub, appNpub)
            val nested = obj.obj("assignment")
            if (nested != null) {
                CachedChiefOfStaffAssignment(
                    assignment = ChiefOfStaffAssignment.parse(nested, expected),
                    canonicalChannelId = lowerUUID(obj.string("canonical_channel_id")),
                    canonicalFileId = lowerUUID(obj.string("canonical_file_id")),
                )
            } else {
                CachedChiefOfStaffAssignment(ChiefOfStaffAssignment.parse(obj, expected))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadAssignment(
        store: LocalStore,
        connectionId: String,
        workspaceId: String,
        ownerNpub: String?,
        appNpub: String,
    ): ChiefOfStaffAssignment? = loadCachedAssignment(store, connectionId, workspaceId, ownerNpub, appNpub)?.assignment

    suspend fun saveAssignment(
        store: LocalStore,
        connectionId: String,
        workspaceId: String,
        appNpub: String,
        assignment: ChiefOfStaffAssignment?,
        canonicalChannelId: String? = null,
        canonicalFileId: String? = null,
    ) {
        val key = assignmentKey(connectionId, workspaceId, appNpub)
        if (assignment == null) {
            store.writer.setState(key, "")
            return
        }
        val wrapped = buildJsonObject {
            put("assignment", assignment.toJson())
            canonicalChannelId?.let { put("canonical_channel_id", it.lowercase()) }
            canonicalFileId?.let { put("canonical_file_id", it.lowercase()) }
        }
        store.writer.setState(key, wrapped.toString())
    }

    suspend fun clearAssignment(store: LocalStore, connectionId: String, workspaceId: String, appNpub: String) {
        store.writer.setState(assignmentKey(connectionId, workspaceId, appNpub), "")
    }
}
