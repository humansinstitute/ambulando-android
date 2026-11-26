package studio.otherstuff.ambulando.core.goals

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.chat.members
import studio.otherstuff.ambulando.core.management.InitialChannelGrant
import studio.otherstuff.ambulando.core.management.ScopeChannelRequests
import studio.otherstuff.ambulando.core.transport.*

/**
 * Where goals live: one dedicated channel per owner, found by `metadata.system_key = ambulando.goals` and
 * `metadata.goals.owner_npub`. Tower authorizes a channel only through grants on that channel (never by scope or
 * workspace role), so a channel created with no other grants is readable by its creator alone; agents are added by
 * explicit channel grants the owner chooses. Nothing here is an NStack endpoint: it is channel create, channel grants
 * and Files.
 *
 * The client never trusts the channel's label. Before every use it reads the channel's grants and refuses the channel
 * if anyone other than the owner and workspace agents holds access, so a channel someone else shared with the owner
 * and dressed up as a Goals space is never written to.
 */
object GoalsSpace {
    const val SYSTEM_KEY = "ambulando.goals"
    const val CHANNEL_NAME = "Goals"
    const val HOME_SCOPE = "Home"

    fun metadata(owner: String): JsonObject = jsonObjectOf("system_key" to SYSTEM_KEY, "goals" to jsonObjectOf("schema_version" to 1, "owner_npub" to owner))

    fun isOwnedBy(channel: ChannelRecord, viewer: String): Boolean {
        if (channel.kind != "channel" || channel.accessRemoved || channel.raw.string("record_state") == "deleted" || channel.raw.string("archived_at") != null) return false
        val meta = channel.raw.obj("metadata") ?: return false
        return meta.string("system_key") == SYSTEM_KEY && meta.obj("goals")?.string("owner_npub") == viewer
    }

    /** The one scope named Home, when exactly one exists. A name is only a suggestion: the owner confirms it. */
    fun suggestedScope(scopes: List<ScopeRecord>): ScopeRecord? =
        scopes.filter { it.name == HOME_SCOPE && it.kind != "dm" && !it.accessRemoved && it.raw.string("archived_at") == null }.singleOrNull()
}

/** One workspace agent and what it may do in the Goals channel (null: nothing). */
data class GoalsAgent(val member: MemberRecord, val access: String?)

sealed class GoalsSpaceState {
    /** [ownerOnly] is the disclosed fallback in the owner's private conversation: no agent can be given access there. */
    data class Ready(val channel: ChannelRecord, val agents: List<GoalsAgent>, val ownerOnly: Boolean) : GoalsSpaceState()
    /** No goals channel exists yet. [scopes] are the places a channel could be created, [suggested] the one named Home. */
    data class NeedsSetup(val scopes: List<ScopeRecord>, val suggested: ScopeRecord?, val agents: List<MemberRecord>) : GoalsSpaceState()
    /** A channel claims to hold the owner's goals but is shared beyond the owner and agents, or cannot be checked. */
    data class Unsafe(val reason: String) : GoalsSpaceState()
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private data class ChannelGrantRow(val actorId: String?, val npub: String?, val kind: String?, val isGroup: Boolean, val access: String?)

private suspend fun TowerAPI.channelGrants(workspace: String, app: String, channelId: String): List<ChannelGrantRow> {
    val o = obj(client.request(path = TowerAPI.base(workspace) + "/channels/" + TowerAPI.uuid(channelId) + "/grants", appNpub = app), "channel grants")
    val rows = o.objects("grants") ?: throw TowerError.Malformed("channel grants")
    return rows.map { r ->
        val principal = r.obj("principal")
        ChannelGrantRow(
            actorId = (principal?.str("actor_id") ?: principal?.str("id") ?: r.str("principal_id"))?.lowercase(), npub = principal?.str("npub"), kind = principal?.str("kind"),
            isGroup = r.str("principal_type") == "group" || principal?.str("type") == "group", access = r.str("access_level"),
        )
    }
}

/** Resolves where this viewer's goals are, without writing anything. [ownerOnlyChosen]: the owner accepted the owner-only fallback this session. */
suspend fun TowerAPI.goalsSpace(workspace: String, app: String, viewer: String, ownerOnlyChosen: Boolean = false): GoalsSpaceState {
    val scopes = scopes(workspace, app).filter { it.workspaceId == workspace && !it.accessRemoved && it.raw.string("archived_at") == null }
    val named = scopes.filter { it.kind != "dm" }.flatMap { scope ->
        channels(workspace, scope.id, app).filter { it.workspaceId == workspace && it.scopeId == scope.id && GoalsSpace.isOwnedBy(it, viewer) }
    }.sortedWith(compareBy({ it.raw.string("created_at") ?: "" }, { it.id }))
    val members = members(workspace, app)
    val agents = members.filter { it.isAgent }
    val channel = named.firstOrNull()
    if (channel == null) {
        selfConversation(workspace, app, viewer, scopes)?.let { self ->
            if (ownerOnlyChosen || goalFiles(workspace, app, self.id).isNotEmpty()) return GoalsSpaceState.Ready(self, emptyList(), ownerOnly = true)
        }
        return GoalsSpaceState.NeedsSetup(scopes.filter { it.kind != "dm" }, GoalsSpace.suggestedScope(scopes), agents)
    }
    val grants = try { channelGrants(workspace, app, channel.id) }
    catch (e: TowerError.Http) { return GoalsSpaceState.Unsafe("This Goals space could not be checked, so it was not used.") }
    val agentIds = agents.associateBy { it.actorId }
    val ownerActorId = members.firstOrNull { it.npub == viewer }?.actorId
    var owner = false
    val agentAccess = HashMap<String, String?>()
    for (g in grants) {
        when {
            g.isGroup -> return GoalsSpaceState.Unsafe("This Goals space is shared with a group, so it was not used. Remove the group's access to use it.")
            g.npub == viewer || (g.actorId != null && g.actorId == ownerActorId) -> owner = true
            g.actorId != null && g.actorId in agentIds -> agentAccess[g.actorId] = g.access
            else -> return GoalsSpaceState.Unsafe("This Goals space is shared with someone other than you and your agents, so it was not used.")
        }
    }
    if (!owner) return GoalsSpaceState.Unsafe("You do not manage this Goals space, so it was not used.")
    return GoalsSpaceState.Ready(channel, agents.map { GoalsAgent(it, agentAccess[it.actorId]) }, ownerOnly = false)
}

/**
 * Creates the owner's private Goals channel in [scopeId] with explicit grants for [agentActorIds] and returns it.
 * Tower decides whether this identity may create channels there; a refusal surfaces as the Tower error and nothing
 * is created. A null [scopeId] is the disclosed owner-only fallback: the owner's private conversation, where agents cannot be added.
 */
suspend fun TowerAPI.createGoalsSpace(workspace: String, app: String, viewer: String, scopeId: String?, agentActorIds: List<String>): ChannelRecord {
    if (scopeId == null) {
        require(agentActorIds.isEmpty()) { "Agents cannot use the owner-only space." }
        return selfConversation(workspace, app, viewer, emptyList(), create = true) ?: throw TowerError.Malformed("self conversation")
    }
    val grants = agentActorIds.distinct().map { InitialChannelGrant("person", it, "contribute") }
    var lastError: Exception? = null
    for (name in listOf(GoalsSpace.CHANNEL_NAME, "My Goals", "Goals (${viewer.takeLast(6)})")) {
        val request = ScopeChannelRequests.createChannel(workspace, scopeId, name, "Private goals: visible only to you and agents you allow.", null, grants, GoalsSpace.metadata(viewer))
        try {
            val reply = obj(client.request(method = request.method, path = request.path, appNpub = app, jsonBody = jsonBody(request.body!!)), "channel")
            val channel = ChannelRecord.parse(reply.obj("channel") ?: throw TowerError.Malformed("channel"))
            if (!GoalsSpace.isOwnedBy(channel, viewer) || channel.scopeId != scopeId) throw TowerError.Malformed("channel")
            return channel
        } catch (e: TowerError.Http) {
            if (e.status != 409) throw e
            lastError = e   // the name is taken in this scope; try the next label
        }
    }
    throw lastError ?: TowerError.Malformed("channel")
}

/** Gives one workspace agent contribute access to the owner's Goals channel, or removes it. Only the owner can. */
suspend fun TowerAPI.setGoalsAgentAccess(workspace: String, app: String, channelId: String, agent: MemberRecord, enabled: Boolean) {
    require(agent.isAgent) { "Only workspace agents can be added here." }
    val base = TowerAPI.base(workspace) + "/channels/" + TowerAPI.uuid(channelId) + "/grants"
    if (enabled) client.request(method = "POST", path = base, appNpub = app, jsonBody = jsonBody(jsonObjectOf(
        "principal_type" to "person", "principal_id" to agent.actorId, "access_level" to "contribute")))
    else client.request(method = "DELETE", path = "$base/person/${agent.actorId}", appNpub = app)
}

/** The owner's private conversation, created only when [create] is set. Used for the disclosed owner-only fallback. */
internal suspend fun TowerAPI.selfConversation(workspace: String, app: String, viewer: String, knownScopes: List<ScopeRecord>, create: Boolean = false): ChannelRecord? {
    fun isSelf(c: ChannelRecord) = c.workspaceId == workspace && c.kind == "dm" && c.raw.string("record_state") != "deleted" && c.raw.bool("is_archived") != true &&
        c.raw["participant_npubs"] == JsonArray(listOf(JsonPrimitive(viewer)))
    val dmScopes = (knownScopes.ifEmpty { scopes(workspace, app) }).filter { it.workspaceId == workspace && it.kind == "dm" }
    for (scope in dmScopes) channels(workspace, scope.id, app).firstOrNull(::isSelf)?.let { return it }
    if (!create) return null
    val scope = dmScopes.firstOrNull() ?: ScopeRecord.parse(
        obj(client.request(method = "POST", path = TowerAPI.base(workspace) + "/scopes", appNpub = app, jsonBody = jsonBody(
            jsonObjectOf("name" to "DMs", "description" to "Direct message conversations", "kind" to "dm"))), "scope").obj("scope") ?: throw TowerError.Malformed("scope"),
    )
    val request = ScopeChannelRequests.createDirectMessage(workspace, scope.id, viewer, viewer)
    val channel = ChannelRecord.parse(
        obj(client.request(method = "POST", path = request.path, appNpub = app, jsonBody = jsonBody(request.body!!)), "channel").obj("channel") ?: throw TowerError.Malformed("channel"),
    )
    if (!isSelf(channel)) throw TowerError.Malformed("channel")
    return channel
}
