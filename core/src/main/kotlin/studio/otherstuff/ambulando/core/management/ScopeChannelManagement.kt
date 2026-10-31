package studio.otherstuff.ambulando.core.management

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.bool
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.isUUID
import studio.otherstuff.ambulando.core.transport.jsonBody
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.newRequestId
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.parseJsonObjectOrNull
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.strings
import studio.otherstuff.ambulando.core.transport.stringOrNull

/*
 * Scope and channel management through the Tower routes the web app uses:
 *
 * - `POST /scopes/:s/channels` (create, `channel.create` on the scope; DMs need workspace membership)
 * - `PATCH /channels/:c` (rename, context prompt; `channel.manage`), `POST /channels/:c/reorder` (1-based position),
 *   `DELETE /channels/:c` (archive)
 * - `POST /scopes` (`scope.create`), `PATCH /scopes/:s` and `DELETE /scopes/:s` (`scope.manage`)
 * - `PUT /me/scope-order` with the current `expected_row_version` (personal order; conflicts are reported, never replayed)
 *
 * These are confirmed online requests, as on the web: the user sees the result or the reason it failed. They are not
 * queued for later, because Tower's create routes carry no idempotency key and a blind replay could create a second
 * channel. This file holds the request building, sending and outcome classification; writing Tower's answers to the
 * local store (and re-reading lists after a reorder or archive) belongs to the store layer that calls it.
 */

enum class ManagementOperation {
    READ_PERMISSIONS, CREATE_CHANNEL, MANAGE_CHANNEL, CREATE_SCOPE, MANAGE_SCOPE, SCOPE_ORDER,
    /** Workspace administration: members, groups, delegations, publishing, workspace profile. */
    ADMINISTER,
    /** Channel grants: needs the channel's own grant-management authority, not workspace administration. */
    MANAGE_GRANTS,
    /** An installation reconcile or revoke: administrators or an active delegation for that exact installation. */
    MANAGE_INSTALLATION;

    val permissionSentence: String
        get() = when (this) {
            READ_PERMISSIONS -> "You don't have access to this workspace."
            CREATE_CHANNEL -> "You don't have permission to create channels in this scope."
            MANAGE_CHANNEL -> "You need Manage access on this channel."
            CREATE_SCOPE -> "You don't have permission to create scopes in this workspace."
            MANAGE_SCOPE -> "You need Manage access on this scope."
            SCOPE_ORDER -> "Your scope order couldn't be saved for this workspace."
            ADMINISTER -> "Workspace administrator access is required for this change."
            MANAGE_GRANTS -> "You need grant-management access on this channel."
            MANAGE_INSTALLATION -> "An administrator or an active delegation for this exact installation is required."
        }
}

sealed class ManagementError(message: String) : Exception(message, null, false, false) {
    data object Locked : ManagementError("Unlock to make changes.")
    data class Offline(val detail: String) : ManagementError("Couldn't reach Tower, nothing was changed. $detail")
    data class Forbidden(override val message: String) : ManagementError(message)
    data class Duplicate(override val message: String) : ManagementError(message)
    data class Conflict(override val message: String) : ManagementError(message)
    data class NotFound(override val message: String) : ManagementError(message)
    data class Invalid(override val message: String) : ManagementError(message)
    data class Unexpected(override val message: String) : ManagementError(message)

    /** Tower saved the change, but reading the list back failed. */
    data class SavedButNotRefreshed(val detail: String) : ManagementError(
        "Saved on Tower, but the list couldn't be refreshed yet ($detail). It will update on the next refresh.",
    )

    /** Whether trying the same request again can help (shown as Retry). */
    val retryable: Boolean get() = this is Offline || this is Unexpected || this is SavedButNotRefreshed

    companion object {
        fun classify(status: Int, body: JsonObject, operation: ManagementOperation): ManagementError {
            val code = body.string("code") ?: body.string("error") ?: ""
            return when {
                // The owner's additional approval is a refusal, never something the app escalates on its own.
                status == 403 && (code == "step_up_required" || code == "owner_step_up_required") ->
                    Forbidden("Tower needs the workspace owner's additional approval for this change. Nothing was changed.")
                status == 403 -> Forbidden(operation.permissionSentence)
                status == 404 -> NotFound(
                    if (operation == ManagementOperation.CREATE_CHANNEL) "This scope is no longer available."
                    else "This item is no longer available. Refresh and try again.",
                )
                status == 409 && code == "duplicate_channel" -> Duplicate("A channel with this name already exists in this scope.")
                status == 409 && code == "duplicate_scope" -> Duplicate("A scope with this name already exists in this workspace.")
                status == 409 && code == "scope_order_conflict" -> Conflict("Your scope order changed in another session. Try your move again.")
                status == 409 -> Conflict("This changed elsewhere. Refresh and try again.")
                status == 400 || status == 422 -> {
                    val fields = body.obj("details")?.objects("fields")?.mapNotNull { it.string("message") } ?: emptyList()
                    Invalid(
                        if (fields.isEmpty()) body.string("message") ?: "Tower refused the request (${code.ifEmpty { "HTTP $status" }})."
                        else fields.joinToString(" "),
                    )
                }
                else -> Unexpected("Tower returned HTTP $status${if (code.isEmpty()) "" else " ($code)"}. Nothing was changed.")
            }
        }
    }
}

/** What a management screen needs to know about the member (`GET /me`). */
data class WorkspaceMembership(val role: String? = null, val permissions: Set<String> = emptySet(), val actorId: String? = null) {
    /** Workspace owners and admins (the web's `canAdminWorkspace`). */
    val isAdmin: Boolean get() = (role?.lowercase() ?: "") in setOf("owner", "admin")

    /**
     * Who may use workspace administration: owners and admins, or anyone Tower grants `workspace.manage` (the web's
     * `canAdminWorkspace` and the iPhone's administrator check). Only a fresh `/me` may be passed in here; a cached one
     * never authorises a write.
     */
    val canAdminister: Boolean get() = isAdmin || "workspace.manage" in permissions

    companion object {
        fun parse(o: JsonObject): WorkspaceMembership = WorkspaceMembership(
            role = o.obj("membership")?.string("role"),
            permissions = ((o["permissions"] as? kotlinx.serialization.json.JsonArray) ?: emptyList()).mapNotNull { it.stringOrNull() }.toSet(),
            actorId = o.obj("actor")?.string("actor_id"),
        )
    }
}

/**
 * The web's visibility rules for management controls. They only decide what is offered; Tower decides the write.
 * Direct-message channel detection lives with the sidebar navigation, so callers pass its answer in.
 */
object ManagementPermissions {
    fun isDirectMessageScope(scope: ScopeRecord): Boolean = scope.kind?.lowercase() == "dm"

    fun canCreateChannel(scope: ScopeRecord, membership: WorkspaceMembership?): Boolean {
        if (isDirectMessageScope(scope)) return true
        if (membership?.canAdminister == true || scope.canManage == true) return true
        if (scope.canManage == false) return false
        return membership?.permissions?.contains("channel.create") == true
    }

    /** System and direct-message channels are never managed here (as on the web). */
    fun canManage(channel: ChannelRecord, membership: WorkspaceMembership?, isDirectMessageChannel: Boolean): Boolean {
        if (isDirectMessageChannel || channel.kind?.lowercase() == "system") return false
        membership ?: return false
        return membership.canAdminister || "channel.manage" in membership.permissions
    }

    fun canManage(scope: ScopeRecord, membership: WorkspaceMembership?): Boolean = scope.canManage ?: (membership?.canAdminister == true)

    fun canCreateScope(membership: WorkspaceMembership?): Boolean {
        membership ?: return false
        return membership.canAdminister || "scope.create" in membership.permissions
    }

    /** Scopes that take part in the personal order (not DM or system scopes). */
    fun isPersonallyOrdered(scope: ScopeRecord): Boolean = !isDirectMessageScope(scope) && scope.kind?.lowercase() != "system"
}

/**
 * Channel context prompt in `metadata.agent_chat`: legacy `basePrompt`/`contextPrompt` are read and replaced by the
 * canonical key; other metadata keys are kept.
 */
object AgentChatConfig {
    const val ACTIVATION = "mention_then_continue"

    fun contextPrompt(metadata: JsonObject): String =
        metadata.obj("agent_chat")?.string("context_prompt") ?: metadata.string("basePrompt") ?: metadata.string("contextPrompt") ?: ""

    fun write(metadata: JsonObject, contextPrompt: String): JsonObject {
        val next = LinkedHashMap(metadata)
        next.remove("basePrompt")
        next.remove("contextPrompt")
        val chat = LinkedHashMap(next["agent_chat"] as? JsonObject ?: JsonObject(emptyMap()))
        chat["enabled"] = JsonPrimitive(true)
        chat["context_prompt"] = JsonPrimitive(contextPrompt.trim())
        chat["activation"] = JsonPrimitive(ACTIVATION)
        next["agent_chat"] = JsonObject(chat)
        return JsonObject(next)
    }
}

/** The channel's metadata object from Tower's projection (empty when absent). */
val ChannelRecord.metadata: JsonObject get() = raw.obj("metadata") ?: JsonObject(emptyMap())
val ChannelRecord.contextPrompt: String get() = AgentChatConfig.contextPrompt(metadata)
val ChannelRecord.channelDescription: String? get() = raw.string("description")

/** Tower's `can_manage` from the scope list (null when the row came from another route). */
val ScopeRecord.canManage: Boolean? get() = raw.bool("can_manage")

/** One management request: method, exact path and JSON body, plus the operation used to classify a refusal. */
data class ManagementRequest(val method: String, val path: String, val body: JsonObject?, val operation: ManagementOperation)

/**
 * Explicit access submitted with channel creation and authorised atomically by Tower. An empty list leaves the creator alone;
 * scope roles and group ownership never silently widen a channel's audience.
 */
data class InitialChannelGrant(val principalType: String, val principalId: String, val access: String = "view") {
    val key: String get() = "$normalizedType:${principalId.lowercase()}"
    val normalizedType: String get() = if (principalType == "person") "actor" else principalType

    companion object {
        /** Wire rows; refuses an unknown type, level or id, and a recipient chosen twice. */
        fun payloads(grants: List<InitialChannelGrant>): List<JsonObject> {
            val seen = HashSet<String>()
            return grants.map { g ->
                if (g.normalizedType != "actor" && g.normalizedType != "group" || g.access !in listOf("view", "contribute", "manage") || !seen.add(g.key)) {
                    throw ManagementError.Invalid("Choose each person or group once and select an access level.")
                }
                if (!isUUID(g.principalId)) throw ManagementError.Invalid("Choose each person or group once and select an access level.")
                jsonObjectOf("principal_type" to g.normalizedType, "principal_id" to g.principalId.lowercase(), "access_level" to g.access)
            }
        }
    }
}

/** The member's personal scope order and its row version (`GET /me/scope-order`). */
data class ScopeOrder(val scopeIds: List<String>, val rowVersion: Long)

/** Request builders with the web forms' bodies and validation. Pure: nothing is sent here. */
object ScopeChannelRequests {
    private fun base(workspaceId: String) = TowerAPI.base(workspaceId)

    private fun required(name: String, what: String): String =
        name.trim().ifEmpty { throw ManagementError.Invalid("$what name is required.") }

    fun membership(workspaceId: String) = ManagementRequest("GET", base(workspaceId) + "/me", null, ManagementOperation.READ_PERMISSIONS)

    /** A named channel in [scopeId] (optional description and channel context prompt, as the web form). */
    fun createChannel(
        workspaceId: String, scopeId: String, name: String, description: String?, contextPrompt: String?,
        grants: List<InitialChannelGrant> = emptyList(),
    ): ManagementRequest {
        val trimmed = required(name, "Channel")
        val body = LinkedHashMap<String, Any?>()
        body["name"] = trimmed
        body["kind"] = "channel"
        body["metadata"] = AgentChatConfig.write(JsonObject(emptyMap()), contextPrompt ?: "")
        body["grants"] = InitialChannelGrant.payloads(grants)
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { body["description"] = it }
        return ManagementRequest(
            "POST", base(workspaceId) + "/scopes/" + TowerAPI.uuid(scopeId) + "/channels", jsonObjectOf(*body.toList().toTypedArray()),
            ManagementOperation.CREATE_CHANNEL,
        )
    }

    /**
     * A direct message with another member in a DM scope, named and described as the web names it (`DM: <npub>`,
     * `dm:<sorted participants>`). The caller opens an existing DM with the member instead of creating one.
     */
    fun createDirectMessage(workspaceId: String, scopeId: String, signerNpub: String, participantNpub: String): ManagementRequest {
        if (!NostrKeys.isValidNpub(participantNpub)) throw ManagementError.Invalid("Choose a member to message.")
        val participants = setOf(signerNpub, participantNpub).sorted()
        val body = jsonObjectOf(
            "name" to "DM: $participantNpub", "description" to "dm:" + participants.joinToString("|"), "kind" to "dm",
            "participant_npubs" to participants,
        )
        return ManagementRequest("POST", base(workspaceId) + "/scopes/" + TowerAPI.uuid(scopeId) + "/channels", body, ManagementOperation.CREATE_CHANNEL)
    }

    /** Tower's PATCH has no version check: the newest rename wins, as on the web. */
    fun renameChannel(workspaceId: String, channelId: String, name: String) = ManagementRequest(
        "PATCH", base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId), jsonObjectOf("name" to required(name, "Channel")),
        ManagementOperation.MANAGE_CHANNEL,
    )

    /** Saves the context prompt in the channel's `agent_chat` metadata, keeping every other metadata key. */
    fun saveContextPrompt(workspaceId: String, channel: ChannelRecord, prompt: String) = ManagementRequest(
        "PATCH", base(workspaceId) + "/channels/" + TowerAPI.uuid(channel.id),
        jsonObjectOf("metadata" to AgentChatConfig.write(channel.metadata, prompt)), ManagementOperation.MANAGE_CHANNEL,
    )

    /** Moves a channel to a 1-based position among its scope's channels (every sibling's position may change). */
    fun moveChannel(workspaceId: String, channelId: String, position: Int): ManagementRequest {
        if (position < 1) throw ManagementError.Invalid("Position must be 1 or more.")
        return ManagementRequest(
            "POST", base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId) + "/reorder", jsonObjectOf("position" to position),
            ManagementOperation.MANAGE_CHANNEL,
        )
    }

    /** Archives a channel (Tower keeps its records; nobody can open it afterwards). */
    fun archiveChannel(workspaceId: String, channelId: String) =
        ManagementRequest("DELETE", base(workspaceId) + "/channels/" + TowerAPI.uuid(channelId), null, ManagementOperation.MANAGE_CHANNEL)

    /** The web scope form always creates kind `project`; the creator gets read, manage and channel-create on it. */
    fun createScope(workspaceId: String, name: String, description: String?): ManagementRequest {
        val pairs = mutableListOf<Pair<String, Any?>>("name" to required(name, "Scope"), "kind" to "project")
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { pairs.add("description" to it) }
        return ManagementRequest("POST", base(workspaceId) + "/scopes", jsonObjectOf(*pairs.toTypedArray()), ManagementOperation.CREATE_SCOPE)
    }

    /** Renames a scope or changes its description (a null description clears it). */
    fun updateScope(workspaceId: String, scopeId: String, name: String, description: String?) = ManagementRequest(
        "PATCH", base(workspaceId) + "/scopes/" + TowerAPI.uuid(scopeId),
        jsonObjectOf("name" to required(name, "Scope"), "description" to (description?.trim() ?: JsonNull)), ManagementOperation.MANAGE_SCOPE,
    )

    /** Archives a scope and all its channels. */
    fun archiveScope(workspaceId: String, scopeId: String) =
        ManagementRequest("DELETE", base(workspaceId) + "/scopes/" + TowerAPI.uuid(scopeId), null, ManagementOperation.MANAGE_SCOPE)

    fun readScopeOrder(workspaceId: String) = ManagementRequest("GET", base(workspaceId) + "/me/scope-order", null, ManagementOperation.SCOPE_ORDER)

    fun saveScopeOrder(workspaceId: String, scopeIds: List<String>, expectedRowVersion: Long, mutationId: String = newRequestId()) = ManagementRequest(
        "PUT", base(workspaceId) + "/me/scope-order",
        jsonObjectOf(
            "scope_ids" to scopeIds.filter { isUUID(it) }.map { it.lowercase() }, "expected_row_version" to expectedRowVersion,
            "mutation_id" to mutationId,
        ),
        ManagementOperation.SCOPE_ORDER,
    )
}

/**
 * Sends management requests for one workspace as [signerNpub] and classifies every refusal for the screen. Returns
 * Tower's answer parsed into records; it never writes the local store.
 */
class ScopeChannelApi(
    private val identity: IdentityService,
    private val signerNpub: String,
    private val client: TowerClient,
    val workspace: WorkspaceRecord,
) {
    /** Runs one signed request: [ManagementError.Locked] before anything is sent, otherwise Tower's classified answer. */
    suspend fun send(request: ManagementRequest): JsonObject {
        try {
            identity.requireSigner(signerNpub)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ManagementError.Locked
        }
        val result = try {
            client.exchange(request.method, request.path, workspace.appNpub, request.body?.let { jsonBody(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ManagementError.Offline(e.message ?: e.toString())
        }
        val body = parseJsonObjectOrNull(result.body) ?: JsonObject(emptyMap())
        if (result.status !in 200..299) throw ManagementError.classify(result.status, body, request.operation)
        return body
    }

    /** The member's role and granted permission names, used to decide which controls to show. */
    suspend fun membership(): WorkspaceMembership = WorkspaceMembership.parse(send(ScopeChannelRequests.membership(workspace.id)))

    /**
     * With any explicit person grant, `/me` is read again and a grant naming the creator is dropped, so Tower's creator recovery
     * bundle is never replaced by a picker entry. An unknown creator actor refuses the creation.
     */
    suspend fun createChannel(scopeId: String, name: String, description: String?, contextPrompt: String?, grants: List<InitialChannelGrant> = emptyList()): ChannelRecord {
        InitialChannelGrant.payloads(grants)
        var effective = grants
        if (grants.any { it.normalizedType == "actor" }) {
            val actorId = membership().actorId?.lowercase()
                ?: throw ManagementError.Unexpected("Tower did not identify the channel creator. Refresh before creating the channel.")
            effective = grants.filterNot { it.normalizedType == "actor" && it.principalId.lowercase() == actorId }
        }
        return channel(send(ScopeChannelRequests.createChannel(workspace.id, scopeId, name, description, contextPrompt, effective)))
    }

    suspend fun createDirectMessage(scopeId: String, participantNpub: String): ChannelRecord =
        channel(send(ScopeChannelRequests.createDirectMessage(workspace.id, scopeId, signerNpub, participantNpub)))

    suspend fun renameChannel(channelId: String, name: String): ChannelRecord =
        channel(send(ScopeChannelRequests.renameChannel(workspace.id, channelId, name)))

    suspend fun saveContextPrompt(channel: ChannelRecord, prompt: String): ChannelRecord =
        channel(send(ScopeChannelRequests.saveContextPrompt(workspace.id, channel, prompt)))

    /** After success the caller re-reads the scope's channels (every sibling's position may have changed). */
    suspend fun moveChannel(channel: ChannelRecord, position: Int) {
        send(ScopeChannelRequests.moveChannel(workspace.id, channel.id, position))
    }

    /** After success the caller re-reads the scope's channels, which removes the channel and its local copies. */
    suspend fun archiveChannel(channel: ChannelRecord) {
        send(ScopeChannelRequests.archiveChannel(workspace.id, channel.id))
    }

    /** Returns the created scope; the caller re-reads the scope list (it carries `can_manage`). */
    suspend fun createScope(name: String, description: String?): ScopeRecord =
        scope(send(ScopeChannelRequests.createScope(workspace.id, name, description)))

    suspend fun updateScope(scopeId: String, name: String, description: String?): ScopeRecord =
        scope(send(ScopeChannelRequests.updateScope(workspace.id, scopeId, name, description)))

    suspend fun archiveScope(scopeId: String) {
        send(ScopeChannelRequests.archiveScope(workspace.id, scopeId))
    }

    suspend fun readScopeOrder(): ScopeOrder = scopeOrder(send(ScopeChannelRequests.readScopeOrder(workspace.id)), lenient = true)

    /**
     * Saves the member's personal scope order. The current `row_version` is read from Tower first (the stored order may
     * lag another device); if another session saved in between, Tower answers `scope_order_conflict`: the newer order
     * is read and handed to [onConflict] for storing, and the conflict is rethrown so the user makes the move again.
     * Nothing is replayed over the other edit. Returns the saved order.
     */
    suspend fun saveScopeOrder(scopeIds: List<String>, onConflict: suspend (List<String>) -> Unit = {}): List<String> {
        val current = readScopeOrder()
        try {
            return scopeOrder(send(ScopeChannelRequests.saveScopeOrder(workspace.id, scopeIds, current.rowVersion)), lenient = false).scopeIds
        } catch (e: ManagementError.Conflict) {
            val fresh = runCatching { readScopeOrder() }.getOrNull()
            if (fresh != null) runCatching { onConflict(fresh.scopeIds) }
            throw e
        }
    }

    private fun channel(answer: JsonObject): ChannelRecord {
        val row = answer.obj("channel") ?: throw ManagementError.Unexpected("Tower did not return the channel.")
        val record = ChannelRecord.parse(row)
        if (record.workspaceId != workspace.id) throw ManagementError.Unexpected("Tower returned a channel from another workspace.")
        return record
    }

    private fun scope(answer: JsonObject): ScopeRecord {
        val row = answer.obj("scope") ?: throw ManagementError.Unexpected("Tower did not return the scope.")
        return ScopeRecord.parse(row)
    }

    private fun scopeOrder(answer: JsonObject, lenient: Boolean): ScopeOrder {
        val order = answer.obj("scope_order")
        if (order == null) {
            if (lenient) return ScopeOrder(emptyList(), 0)
            throw ManagementError.Unexpected("Tower did not confirm the scope order.")
        }
        val ids = (order.strings("scope_ids") ?: emptyList()).filter { isUUID(it) }.map { it.lowercase() }
        return ScopeOrder(ids, flexibleLong(order["row_version"]) ?: 0)
    }
}
