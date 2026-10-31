package studio.otherstuff.ambulando.core.management

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.isUUID
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import studio.otherstuff.ambulando.core.transport.newRequestId
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Every workspace-administration write, as a value. [request] builds the exact Tower request (the contract the iPhone and
 * web clients use) and validates its inputs; it sends nothing. Identifiers that enter a path must be UUIDs, a public key
 * must be a full lower-case `npub`, an expiry must be in the future, and an origin must be HTTPS without credentials.
 *
 * Administration is online only: none of these is queued, because Tower's create routes carry no idempotency key and a
 * blind replay could create a second record.
 */
sealed interface AdministrationCommand {
    data class Profile(val name: String, val description: String, val avatarUrl: String, val slug: String? = null) : AdministrationCommand
    data class AddMember(val npub: String, val kind: String) : AdministrationCommand
    data class MemberProfile(val actor: String, val name: String) : AdministrationCommand
    data class DeleteWorkspace(val confirmation: String) : AdministrationCommand
    data class CreateGroup(val name: String) : AdministrationCommand
    /** Adds an existing workspace member to a group. Cannot change their workspace role or kind. */
    data class GroupMember(val group: String, val npub: String) : AdministrationCommand
    /** Tower's additive atomic contract: enrolment and the group edge commit together, preserving any existing role and kind. */
    data class EnsureGroupMember(val group: String, val npub: String, val kind: String) : AdministrationCommand
    data class RemoveGroupMember(val group: String, val actor: String) : AdministrationCommand
    data class ChildGroup(val parent: String, val child: String, val remove: Boolean) : AdministrationCommand
    data class ChannelGrant(val channel: String, val principalType: String, val principal: String, val access: String, val update: Boolean) : AdministrationCommand
    data class RevokeChannelGrant(val channel: String, val principalType: String, val principal: String) : AdministrationCommand
    data class Delegation(val actor: String, val expires: String, val app: String, val scope: String, val channel: String) : AdministrationCommand
    data class RevokeDelegation(val id: String) : AdministrationCommand
    data class Installation(val id: String, val revoke: Boolean) : AdministrationCommand
    data class Publishing(
        val id: String, val app: String, val publisher: String, val owner: String, val name: String,
        val destinations: List<AdministrationDestination>, val origins: List<String>,
    ) : AdministrationCommand
    data class DisablePublishing(val id: String) : AdministrationCommand
    data class RevokePublishing(val id: String) : AdministrationCommand
    data class RotatePublisher(val id: String, val current: String, val replacement: String) : AdministrationCommand

    /** The operation used to word a refusal. */
    val operation: ManagementOperation
        get() = when (this) {
            is ChannelGrant, is RevokeChannelGrant -> ManagementOperation.MANAGE_GRANTS
            is Installation -> ManagementOperation.MANAGE_INSTALLATION
            else -> ManagementOperation.ADMINISTER
        }

    /**
     * Builds the request. [now] and [nonce] are injected so expiries and rotation nonces are deterministic in tests.
     * Throws [ManagementError.Invalid] for anything Tower would also refuse, before a request is made.
     */
    fun request(workspaceId: String, now: () -> Instant = Instant::now, nonce: () -> String = { newRequestId() }): ManagementRequest {
        fun uuid(value: String, what: String = "identifier"): String =
            if (isUUID(value)) value.lowercase() else throw ManagementError.Invalid("Choose a valid $what.")
        fun npub(value: String): String =
            if (NostrKeys.isValidNpub(value)) value else throw ManagementError.Invalid("Enter a valid full npub.")
        fun principal(type: String, id: String): String {
            if (type != "actor" && type != "group") throw ManagementError.Invalid("Choose a person or group.")
            return uuid(id, "recipient")
        }
        fun kind(value: String): String = if (value == "human" || value == "agent") value else throw ManagementError.Invalid("Choose a person or agent.")
        val base = TowerAPI.base(workspaceId)
        fun req(method: String, path: String, body: JsonObject?) = ManagementRequest(method, base + path, body, operation)
        return when (this) {
            is Profile -> {
                if (name.isBlank()) throw ManagementError.Invalid("Workspace name is required.")
                if (avatarUrl.startsWith("storage://")) uuid(avatarUrl.removePrefix("storage://"), "avatar")
                else if (avatarUrl.isNotEmpty()) {
                    val ok = runCatching { java.net.URI(avatarUrl) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrEmpty() && it.userInfo == null } == true
                    if (!ok) throw ManagementError.Invalid("Use an HTTPS avatar URL without credentials.")
                }
                val fields = mutableListOf<Pair<String, Any?>>("name" to name.trim(), "description" to description, "avatar_url" to if (avatarUrl.isEmpty()) JsonNull else avatarUrl)
                if (slug != null) {
                    if (slug.isEmpty() || !slug.all { it.isLowerCase() || it.isDigit() || it == '-' }) {
                        throw ManagementError.Invalid("Use a lowercase slug containing letters, numbers and hyphens.")
                    }
                    fields += "slug" to slug
                }
                req("PATCH", "", jsonObjectOf(*fields.toTypedArray()))
            }
            is AddMember -> req("POST", "/members", jsonObjectOf("member_npub" to npub(this.npub), "role" to "member", "kind" to kind(this.kind)))
            is MemberProfile -> {
                if (name.isBlank()) throw ManagementError.Invalid("Display name is required.")
                req("PATCH", "/members/${uuid(actor, "member")}/profile", jsonObjectOf("display_name" to name.trim()))
            }
            is DeleteWorkspace -> req("DELETE", "", jsonObjectOf("confirmation" to uuid(confirmation, "workspace")))
            is CreateGroup -> {
                if (name.isBlank()) throw ManagementError.Invalid("Group name is required.")
                req("POST", "/groups", jsonObjectOf("name" to name.trim(), "kind" to "custom"))
            }
            is GroupMember -> req("POST", "/groups/${uuid(group, "group")}/members", jsonObjectOf("member_npub" to npub(this.npub)))
            is EnsureGroupMember -> req(
                "POST", "/groups/${uuid(group, "group")}/members",
                jsonObjectOf("member_npub" to npub(this.npub), "ensure_workspace_member" to true, "kind" to kind(this.kind)),
            )
            is RemoveGroupMember -> req("DELETE", "/groups/${uuid(group, "group")}/members/${uuid(actor, "member")}", null)
            is ChildGroup -> {
                val path = "/groups/${uuid(parent, "group")}/child-groups"
                if (remove) req("DELETE", "$path/${uuid(child, "group")}", null) else req("POST", path, jsonObjectOf("child_group_id" to uuid(child, "group")))
            }
            is ChannelGrant -> {
                if (access !in ACCESS_LEVELS) throw ManagementError.Invalid("Choose an access level.")
                val target = principal(principalType, principal)
                val channelId = uuid(channel, "channel")
                if (update) req("PUT", "/channels/$channelId/grants/$principalType/$target", jsonObjectOf("access_level" to access))
                else req("POST", "/channels/$channelId/grants", jsonObjectOf("principal_type" to principalType, "principal_id" to target, "access_level" to access))
            }
            is RevokeChannelGrant -> req("DELETE", "/channels/${uuid(channel, "channel")}/grants/$principalType/${principal(principalType, principal)}", null)
            is Delegation -> {
                if (!isFuture(expires, now()) || app.isBlank()) throw ManagementError.Invalid("Choose an app and a future expiry.")
                req(
                    "POST", "/wapp-delegations",
                    jsonObjectOf(
                        "delegate_actor_id" to uuid(actor, "delegate"), "expires_at" to expires,
                        "filters" to jsonObjectOf(
                            "app_ids" to listOf(app), "scope_ids" to listOf(uuid(scope, "scope")), "channel_ids" to listOf(uuid(channel, "channel")),
                            "capabilities" to listOf("activity.publish"), "open_origins" to emptyList<String>(), "autopilot_origins" to emptyList<String>(),
                            "installation_ids" to emptyList<String>(),
                        ),
                    ),
                )
            }
            is RevokeDelegation -> req("POST", "/wapp-delegations/${uuid(id, "delegation")}/revoke", JsonObject(emptyMap()))
            is Publishing -> {
                if (app.isEmpty() || name.isEmpty() || destinations.isEmpty()) throw ManagementError.Invalid("App identifier and display name are required.")
                for (origin in origins) try { TowerOrigin(origin) } catch (e: TowerError) { throw ManagementError.Invalid(e.message ?: "Use HTTPS origins.") }
                req(
                    "PUT", "/wapp-publishing-grants/${uuid(id, "installation")}",
                    jsonObjectOf(
                        "app_id" to app, "publisher_npub" to npub(publisher), "owner_npub" to npub(owner), "display_name" to name,
                        "capabilities" to listOf("activity.publish"),
                        "destinations" to destinations.map { jsonObjectOf("scope_id" to uuid(it.scope, "scope"), "channel_id" to uuid(it.channel, "channel")) },
                        "registered_open_origins" to origins,
                    ),
                )
            }
            is DisablePublishing -> req("POST", "/wapp-publishing-grants/${uuid(id, "installation")}/disable", jsonObjectOf("disabled" to true, "reason" to "Disabled in Ambulando"))
            is RevokePublishing -> req("POST", "/wapp-publishing-grants/${uuid(id, "installation")}/revoke", jsonObjectOf("disable_open_links" to true, "reason" to "Revoked in Ambulando"))
            is RotatePublisher -> req(
                "POST", "/wapp-publishing-grants/${uuid(id, "installation")}/rotate",
                jsonObjectOf(
                    "current_publisher_npub" to npub(current), "new_publisher_npub" to npub(replacement), "nonce" to nonce().lowercase(),
                    "expires_at" to now().plusSeconds(600).truncatedSeconds(),
                ),
            )
            is Installation -> req("POST", "/wapp-installations/${uuid(id, "installation")}/${if (revoke) "revoke" else "reconcile"}", JsonObject(emptyMap()))
        }
    }

    companion object {
        val ACCESS_LEVELS = listOf("view", "contribute", "manage")

        /** True when [text] is an ISO-8601 instant after [now]. */
        fun isFuture(text: String, now: Instant): Boolean = parseInstant(text)?.isAfter(now) == true

        fun parseInstant(text: String): Instant? = try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            try { OffsetDateTime.parse(text).toInstant() } catch (_: DateTimeParseException) { null }
        }

        fun Instant.truncatedSeconds(): String = isoSeconds(this)

        /** An instant as `2026-11-01T10:00:00Z`: whole seconds, always exactly one trailing `Z`. */
        fun isoSeconds(instant: Instant): String = java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
    }
}
