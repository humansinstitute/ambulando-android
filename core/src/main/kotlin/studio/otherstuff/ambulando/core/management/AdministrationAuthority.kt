package studio.otherstuff.ambulando.core.management

import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.transport.strings
import java.time.Instant

/**
 * What a person may do, decided only from fresh answers: the `/me` read made for this action, the grants read made for this
 * channel, the access read made for this scope, the delegations read made for this installation. A cached list is a hint for
 * drawing a screen and is never an authority: nothing here accepts "the last time we looked" as permission, and Tower decides
 * every write again.
 *
 * Authority levels, narrowest first:
 * - **Scope roles** (`context_editor`, `scope_manager`) manage a scope's settings and context. They never grant access to
 *   linked content or channel participation.
 * - **Channel grants** are managed by an administrator, or by a holder of `channel.grants.manage` on that exact channel
 *   (directly, or through a group they belong to, nested groups included). Workspace-wide `channel.manage` or a union of
 *   permissions elsewhere never proves it.
 * - **Installations** are reconciled or revoked by an administrator or by the holder of an active, unexpired delegation that
 *   names that exact installation (or its app and destinations).
 * - Everything else in workspace administration needs a workspace administrator.
 */
object AdministrationAuthority {
    private const val GRANTS_MANAGE = "channel.grants.manage"

    /**
     * True when [membership] (fresh) is an administrator, or one of [grants] (fresh, for this channel) holds grant
     * management for [membership]'s actor or for a group whose members (nested groups included) contain [npub].
     */
    fun canManageChannelGrants(membership: WorkspaceMembership, grants: List<AdministrationRow>, groups: List<AdministrationRow>, npub: String): Boolean {
        if (membership.canAdminister) return true
        return grants.any { row ->
            val permissions = row.raw.strings("permissions") ?: listOfNotNull(row.fields["permission"])
            // A row that names no permission at all is judged by its level; one that names permissions must name this one.
            val holds = GRANTS_MANAGE in permissions || (permissions.all { it.isEmpty() } && row.fields["access_level"] == "manage")
            if (!holds) return@any false
            when (row.fields["principal_type"]) {
                "actor" -> membership.actorId != null && row.fields["principal_id"] == membership.actorId
                "group" -> {
                    val group = row.fields["principal_id"] ?: return@any false
                    npub in GroupDirectory.effectiveMemberNpubs(group, groups)
                }
                else -> false
            }
        }
    }

    /**
     * Conservative exact-installation delegation: the delegation belongs to [actor], is not revoked, has a future expiry and
     * names this installation, or names its app and covers every destination it publishes to. Listing an installation, or a
     * delegation with no installation, app or destination filter, never enables management. Tower still enforces its own
     * filters and any owner approval.
     */
    fun hasInstallationDelegation(
        actor: String, installation: String, delegations: List<AdministrationRow>, installationData: AdministrationRow? = null, now: Instant = Instant.now(),
    ): Boolean = delegations.any { row ->
        if (actor.isEmpty() || row.fields["delegate_actor_id"] != actor || row.fields["revoked_at"] != null) return@any false
        val expires = row.fields["expires_at"]?.let(AdministrationCommand::parseInstant) ?: return@any false
        if (!expires.isAfter(now)) return@any false
        val filters = row.raw["filters"] as? JsonObject ?: return@any false
        val ids = filters.strings("installation_ids") ?: emptyList()
        val apps = filters.strings("app_ids") ?: emptyList()
        if (ids.isNotEmpty() && installation !in ids) return@any false
        val installationApp = installationData?.fields?.get("app_id")
        if (apps.isNotEmpty() && installationApp != null && installationApp !in apps) return@any false
        if (installation in ids) return@any true
        val data = installationData ?: return@any false
        if (installationApp == null || installationApp !in apps) return@any false
        val destinations = data.raw.destinations()
        val scopes = filters.strings("scope_ids") ?: emptyList()
        val channels = filters.strings("channel_ids") ?: emptyList()
        if ((scopes.isNotEmpty() || channels.isNotEmpty()) && destinations.isEmpty()) return@any false
        destinations.all { (scope, channel) -> (scopes.isEmpty() || scope in scopes) && (channels.isEmpty() || channel in channels) }
    }

    /**
     * Whether [delegationId], offered when an administrator is not acting, is an active delegation of [actor] that could cover
     * activating [appId] into [scope]/[channel]. Anything the delegation's own filters rule out is refused here; the rest
     * (origins, capabilities, owner approval) is Tower's.
     */
    fun delegationCovers(
        actor: String, delegationId: String, appId: String, scope: String, channel: String, delegations: List<AdministrationRow>, now: Instant = Instant.now(),
    ): Boolean {
        val row = delegations.firstOrNull { it.id == delegationId } ?: return false
        if (actor.isEmpty() || row.fields["delegate_actor_id"] != actor || row.fields["revoked_at"] != null) return false
        val expires = row.fields["expires_at"]?.let(AdministrationCommand::parseInstant) ?: return false
        if (!expires.isAfter(now)) return false
        val filters = row.raw["filters"] as? JsonObject ?: return false
        val apps = filters.strings("app_ids") ?: emptyList()
        val scopes = filters.strings("scope_ids") ?: emptyList()
        val channels = filters.strings("channel_ids") ?: emptyList()
        // A delegation that names no app is not a wildcard for activation; scope and channel filters, when present, must contain the destination.
        return appId in apps && (scopes.isEmpty() || scope in scopes) && (channels.isEmpty() || channel in channels)
    }

    /** Delegations of [actor] that are still active at [now], for choosing the authority an activation runs under. */
    fun activeDelegations(actor: String?, delegations: List<AdministrationRow>, now: Instant = Instant.now()): List<AdministrationRow> =
        delegations.filter { row ->
            actor != null && row.fields["delegate_actor_id"] == actor && row.fields["revoked_at"] == null &&
                row.fields["expires_at"]?.let(AdministrationCommand::parseInstant)?.isAfter(now) == true
        }

    private fun JsonObject.destinations(): List<Pair<String, String>> =
        (this["destinations"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }?.map {
            (it["scope_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() to (it["channel_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        } ?: emptyList()
}
