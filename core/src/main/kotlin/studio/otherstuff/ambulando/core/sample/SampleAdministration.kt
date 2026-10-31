package studio.otherstuff.ambulando.core.sample

import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.transport.HttpRequest
import java.time.Instant
import java.util.UUID

/** Who the sample viewer is for administration. Authority is decided here the way Tower decides it: from exact grants, not from labels. */
enum class SampleViewer {
    /** An ordinary member: no administration authority anywhere. */
    MEMBER,
    /** Workspace administrator. */
    ADMIN,
    /** An ordinary member holding `channel.grants.manage` on exactly one channel (the design channel) through a direct grant. */
    CHANNEL_MANAGER,
    /** An ordinary member who is a scope manager of exactly the Ambulando scope. */
    SCOPE_MANAGER,
    /** An ordinary member with an active delegation for exactly one managed installation. */
    DELEGATE,
}

/**
 * In-process stand-in for Tower's workspace-administration routes (members, groups, channel and scope grants, app
 * delegations, installations, install intents and publishing grants), for tests and the clearly labelled sample-data mode. It is
 * **not** Tower and is never evidence of live behaviour. It follows the rules the client depends on: administrator-only
 * writes and lists, exact-resource authority for a channel grant manager, a scope manager and a delegate, a scope access
 * revision with a canonical edit lease, `step_up_required` refusals on request, idempotent install intents by
 * `client_request_id`, and a two-step activation through a synthetic Autopilot origin. State is in memory (sample data
 * does not survive a relaunch).
 */
class SampleAdministration(private val auth: SampleAuth) {
    private val lock = Any()

    @Volatile var viewer: SampleViewer = SampleViewer.MEMBER
    /** The next write is refused with HTTP 403 `step_up_required`. */
    @Volatile var stepUpOnNextWrite = false
    /** Every administration write answers this status/code (null: normal). */
    @Volatile var failWrites: Pair<Int, String>? = null
    /** Another editor holds the scope edit lease. */
    @Volatile var scopeLeaseHeld = false
    /** Autopilot's catalog also lists an app that names a different Autopilot (it must never be offered through this origin). */
    @Volatile var catalogForeignEntry = false
    /** Autopilot answers its catalog and process requests with a redirect to another host. */
    @Volatile var autopilotRedirect = false
    /** Autopilot's process step fails once (the claim may or may not have happened). */
    @Volatile var failNextProcess = false
    /** Called when the workspace was deleted (the owning tower then stops listing it). */
    @Volatile var onDeleted: (String) -> Unit = {}
    @Volatile private var lastSigner: String? = null

    class Recorded(val method: String, val path: String, val body: Row)
    private val log = ArrayList<Recorded>()
    /** Every administration request this tower answered, in order. */
    fun requests(): List<Recorded> = synchronized(lock) { log.toList() }
    fun writes(): List<Recorded> = requests().filter { it.method != "GET" }
    /** Forgets the recorded requests (a test that shares one tower with earlier tests starts from an empty log). */
    fun clearRequests() = synchronized(lock) { log.clear() }

    private class Ws {
        var name: String? = null; var slug: String? = null; var description: String? = null; var avatar: String? = null
        val members = ArrayList<Row>()
        val groups = LinkedHashMap<String, Row>()
        val channelGrants = LinkedHashMap<String, MutableList<Row>>()
        val scopeGrants = LinkedHashMap<String, MutableList<Row>>()
        val scopeRevision = HashMap<String, Long>()
        val delegations = ArrayList<Row>()
        val installations = LinkedHashMap<String, Row>()
        val intents = LinkedHashMap<String, Row>()
        val publishing = LinkedHashMap<String, Row>()
        val leases = HashMap<String, Pair<String, String>>()
    }

    private val workspaces = HashMap<String, Ws>()
    private fun ws(w: String): Ws = workspaces.getOrPut(w) { seed(w) }

    private fun actor(npub: String) = UUID.nameUUIDFromBytes(npub.toByteArray()).toString()

    private fun person(id: String, npub: String, name: String?, kind: String) =
        linkedMapOf<String, Any?>("actor_id" to id, "npub" to npub, "display_name" to name, "kind" to kind, "role" to "member")

    private fun seed(w: String): Ws {
        val s = Ws()
        if (w != SampleIds.STUDIO) return s
        s.members += person(actor(SampleIds.ANA), SampleIds.ANA, SampleContent.ANA_LABEL, "human")
        s.members += person(actor(SampleIds.ORBIT), SampleIds.ORBIT, SampleContent.ORBIT_LABEL, "agent")
        s.members += person(actor(SampleIds.BEA), SampleIds.BEA, null, "human")
        s.groups[GROUP_DESIGNERS] = group(GROUP_DESIGNERS, "Designers", listOf(actor(SampleIds.ANA)), listOf(GROUP_REVIEWERS))
        s.groups[GROUP_REVIEWERS] = group(GROUP_REVIEWERS, "Reviewers", listOf(actor(SampleIds.ORBIT)), emptyList())
        s.groups[GROUP_EVERYONE] = group(GROUP_EVERYONE, "Workspace members", emptyList(), emptyList()).also { it["kind"] = "workspace_member" }
        s.channelGrants[SampleIds.DESIGN] = mutableListOf(
            grant("group", GROUP_DESIGNERS, "Designers", "contribute", listOf("channel.read", "channel.write")),
        )
        s.scopeGrants[SampleIds.SCOPE_AMBULANDO] = mutableListOf(scopeGrant("actor", actor(SampleIds.ANA), SampleContent.ANA_LABEL, "context_editor"))
        s.scopeRevision[SampleIds.SCOPE_AMBULANDO] = 4
        s.installations[INSTALLATION_FEED] = installation(INSTALLATION_FEED, "sample.feed", "Sample Feed", "active")
        s.installations[INSTALLATION_OTHER] = installation(INSTALLATION_OTHER, "sample.other", "Other App", "active")
        return s
    }

    private fun group(id: String, name: String, memberActors: List<String>, children: List<String>): Row = linkedMapOf(
        "group_id" to id, "id" to id, "name" to name, "kind" to "custom", "child_group_ids" to ArrayList(children),
        "members" to ArrayList<Any?>(), "member_actor_ids" to ArrayList(memberActors),
    )

    private fun grant(type: String, id: String, name: String, level: String, permissions: List<String>): Row = linkedMapOf(
        "principal_type" to type, "principal_id" to id, "display_name" to name, "access_level" to level, "permissions" to ArrayList(permissions),
    )

    private fun scopeGrant(type: String, id: String, name: String, role: String): Row = linkedMapOf(
        "principal_type" to type, "principal_id" to id, "display_name" to name, "role" to role,
    )

    private fun installation(id: String, app: String, title: String, status: String): Row = linkedMapOf(
        "wapp_installation_id" to id, "app_id" to app, "title" to title, "status" to status,
        "destinations" to listOf(mapOf("scope_id" to SampleIds.SCOPE_AMBULANDO, "channel_id" to SampleIds.GENERAL)),
    )

    // MARK: Viewer authority

    private val viewerActor get() = SampleIds.ACTOR
    private fun isAdmin() = viewer == SampleViewer.ADMIN
    private fun managesChannel(channel: String) = isAdmin() || (viewer == SampleViewer.CHANNEL_MANAGER && channel == SampleIds.DESIGN)
    private fun managesScope(scope: String) = isAdmin() || (viewer == SampleViewer.SCOPE_MANAGER && scope == SampleIds.SCOPE_AMBULANDO)
    private fun delegatesInstallation(id: String) = viewer == SampleViewer.DELEGATE && id == INSTALLATION_FEED
    private fun forbidden() = 403 to linkedMapOf<String, Any?>("code" to "forbidden", "error" to "Permission denied")

    /** `GET /me` when the sample is being used for administration; null leaves the ordinary member answer. */
    fun me(signer: String): Reply? {
        if (viewer == SampleViewer.MEMBER) return null
        return 200 to linkedMapOf(
            "actor" to linkedMapOf("actor_id" to viewerActor, "npub" to signer),
            "membership" to mapOf("role" to if (isAdmin()) "admin" else "member"),
            "permissions" to (listOf("channel.create", "channel.manage", "channel.read", "channel.write", "scope.create", "workspace.read") + if (isAdmin()) listOf("workspace.manage") else emptyList()),
        )
    }

    /** Grants submitted atomically with a channel's creation are stored as that channel's grants. */
    fun recordInitialGrants(w: String, channel: String, grants: Any?) = synchronized(lock) {
        val rows = ws(w).channelGrants.getOrPut(channel) { mutableListOf() }
        for (g in grants.asList() ?: emptyList()) {
            val m = g.asMap() ?: continue
            val type = m["principal_type"].asString() ?: continue; val id = m["principal_id"].asString() ?: continue
            val level = m["access_level"].asString() ?: "view"
            rows += grant(type, id, nameOf(ws(w), type, id), level, permissionsFor(level))
        }
    }

    /** Members added or renamed through administration, merged into the ordinary member list. */
    fun addedMembers(w: String): List<Row> = synchronized(lock) { ws(w).members.map { SampleJson.row(it)!! } }

    fun profile(w: String): Triple<String?, String?, String?> = synchronized(lock) { ws(w).let { Triple(it.name, it.slug, it.avatar) } }

    /** Seeds the signer's own groups so group-derived authority can be exercised (direct member of Reviewers, a child of Designers). */
    fun makeViewerGroupMember(w: String) = synchronized(lock) {
        val s = ws(w)
        @Suppress("UNCHECKED_CAST")
        (s.groups.getValue(GROUP_REVIEWERS)["member_actor_ids"] as MutableList<String>).add(viewerActor)
    }

    /** A channel grant held by a group (what makes its members managers of that channel's grants when it names the permission). */
    fun grantChannelToGroup(w: String, channel: String, group: String, permissions: List<String>) = synchronized(lock) {
        ws(w).channelGrants.getOrPut(channel) { mutableListOf() } += grant("group", group, nameOf(ws(w), "group", group), "custom", permissions)
    }

    /** The grants this tower holds for [channel] (principal type, principal id and level), for tests that create a channel with explicit grants. */
    fun channelGrantsOf(w: String, channel: String): List<Triple<String, String, String>> = synchronized(lock) {
        ws(w).channelGrants[channel].orEmpty().map { Triple(it["principal_type"].asString() ?: "", it["principal_id"].asString() ?: "", it["access_level"].asString() ?: "") }
    }

    /** Another session changes a scope's access, so an editor's revision becomes stale. */
    fun concurrentScopeChange(w: String, scope: String) = synchronized(lock) {
        val s = ws(w)
        s.scopeRevision[scope] = (s.scopeRevision[scope] ?: 1) + 1
    }

    fun delegateGrantFor(w: String, actorId: String, expires: String, installation: String? = null): Unit = synchronized(lock) {
        ws(w).delegations += linkedMapOf<String, Any?>(
            "id" to newUUID(), "delegate_actor_id" to actorId, "expires_at" to expires, "revoked_at" to null,
            "filters" to linkedMapOf(
                "app_ids" to listOf("sample.feed"), "scope_ids" to emptyList<String>(), "channel_ids" to emptyList<String>(),
                "installation_ids" to listOfNotNull(installation), "open_origins" to emptyList<String>(), "autopilot_origins" to emptyList<String>(),
                "capabilities" to listOf("activity.publish"),
            ),
        )
    }

    // MARK: Routes

    private fun membersView(s: Ws): List<Row> = s.members.map { SampleJson.row(it)!! }

    private fun groupRow(s: Ws, g: Row): Row {
        val out = SampleJson.row(g)!!
        val all = (listOf(person(actor(SampleIds.ANA), SampleIds.ANA, SampleContent.ANA_LABEL, "human"), person(actor(SampleIds.ORBIT), SampleIds.ORBIT, SampleContent.ORBIT_LABEL, "agent"),
            person(viewerActor, lastSigner ?: SampleIds.ANA, "You", "human")) + s.members).associateBy { it["actor_id"] as String }
        val ids = out["member_actor_ids"].asStrings() ?: emptyList()
        out["members"] = ids.mapNotNull { id -> all[id]?.let { m -> linkedMapOf("actor_id" to id, "npub" to m["npub"], "display_name" to m["display_name"], "kind" to m["kind"]) } }
        out.remove("member_actor_ids")
        return out
    }

    private fun record(request: HttpRequest, path: String, body: Row) { log += Recorded(request.method.uppercase(), path, body) }

    /** Routes under `/workspaces/:w/…`. Null: not an administration route. */
    fun handle(request: HttpRequest, w: String, rest: List<String>, signer: String): Reply? {
        lastSigner = signer
        val method = request.method.uppercase()
        val head = rest.firstOrNull() ?: return if (method == "PATCH" || method == "DELETE") synchronized(lock) { workspaceRoot(request, w, method) } else null
        val n = rest.size
        val admin = head in setOf("members", "groups", "wapp-delegations", "wapp-installations", "wapp-install-intents", "wapp-publishing-grants") ||
            (head == "channels" && n >= 3 && rest[2] == "grants") || (head == "scopes" && n == 3 && rest[2] == "grants") ||
            false
        if (!admin) return null
        if (head == "members" && method == "GET" && n == 1) return null
        return synchronized(lock) {
            val body = SampleJson.objectOf(request.body)
            record(request, "/" + rest.joinToString("/"), body)
            if (method != "GET") {
                if (stepUpOnNextWrite) { stepUpOnNextWrite = false; return@synchronized errorReply(403, "step_up_required") }
                failWrites?.let { (status, code) -> return@synchronized errorReply(status, code) }
            }
            route(method, w, rest, body, signer) ?: errorReply(404, "not_found")
        }
    }

    private fun workspaceRoot(request: HttpRequest, w: String, method: String): Reply {
        val body = SampleJson.objectOf(request.body)
        record(request, "", body)
        if (!isAdmin()) return forbidden()
        if (stepUpOnNextWrite) { stepUpOnNextWrite = false; return errorReply(403, "step_up_required") }
        failWrites?.let { (status, code) -> return errorReply(status, code) }
        val s = ws(w)
        if (method == "DELETE") {
            if (body["confirmation"] != w) return validationError("confirmation must name this workspace")
            onDeleted(w)
            return 200 to mapOf("deleted" to true)
        }
        body["name"].asString()?.takeIf { it.isNotBlank() }?.let { s.name = it }
        if (body.containsKey("description")) s.description = body["description"].asString()
        if (body.containsKey("avatar_url")) s.avatar = body["avatar_url"].asString()
        body["slug"].asString()?.let { s.slug = it }
        return 200 to mapOf("workspace" to linkedMapOf("name" to s.name, "description" to s.description, "avatar_url" to s.avatar, "slug" to s.slug))
    }

    private fun route(method: String, w: String, rest: List<String>, body: Row, signer: String): Reply? {
        val s = ws(w); val head = rest[0]; val n = rest.size
        return when {
            // Members
            head == "members" && method == "POST" && n == 1 -> {
                if (!isAdmin()) return forbidden()
                val npub = body["member_npub"].asString() ?: return validationError("member_npub is required")
                if (!NostrKeys.isValidNpub(npub)) return validationError("member_npub must be an npub")
                if (s.members.none { it["npub"] == npub }) s.members += person(actor(npub), npub, null, body["kind"].asString() ?: "human")
                201 to mapOf("member" to s.members.first { it["npub"] == npub })
            }
            head == "members" && method == "PATCH" && n == 3 && rest[2] == "profile" -> {
                if (!isAdmin()) return forbidden()
                val member = s.members.firstOrNull { it["actor_id"] == rest[1] } ?: return errorReply(404, "member_not_found")
                member["display_name"] = body["display_name"].asString()
                200 to mapOf("member" to member)
            }
            // Groups
            head == "groups" && method == "GET" && n == 1 -> if (!isAdmin()) forbidden() else indexList("groups", s.groups.values.map { groupRow(s, it) })
            head == "groups" && method == "POST" && n == 1 -> {
                if (!isAdmin()) return forbidden()
                val name = body["name"].asString()?.trimWhitespace() ?: ""
                if (name.isEmpty()) return validationError("name must be a non-empty string")
                if (s.groups.values.any { (it["name"] as String).equals(name, true) }) return errorReply(409, "duplicate_group")
                val id = newUUID(); s.groups[id] = group(id, name, emptyList(), emptyList())
                201 to mapOf("group" to groupRow(s, s.groups.getValue(id)))
            }
            head == "groups" && method == "POST" && n == 3 && rest[2] == "members" -> {
                if (!isAdmin()) return forbidden()
                val g = s.groups[rest[1]] ?: return errorReply(404, "group_not_found")
                val npub = body["member_npub"].asString() ?: return validationError("member_npub is required")
                var known = s.members.firstOrNull { it["npub"] == npub }
                    ?: (if (npub == signer) person(viewerActor, npub, "You", "human") else null)
                    ?: (if (npub == SampleIds.ANA) person(actor(npub), npub, SampleContent.ANA_LABEL, "human") else null)
                if (known == null) {
                    // Only the additive atomic contract may enrol an outsider; an ordinary request for one is a not-found.
                    if (body["ensure_workspace_member"] != true) return errorReply(404, "member_not_found")
                    known = person(actor(npub), npub, null, body["kind"].asString() ?: "human"); s.members += known
                }
                @Suppress("UNCHECKED_CAST") val ids = g["member_actor_ids"] as MutableList<String>
                val id = known["actor_id"] as String
                if (id !in ids) ids += id
                200 to mapOf("group" to groupRow(s, g))
            }
            head == "groups" && method == "DELETE" && n == 4 && rest[2] == "members" -> {
                if (!isAdmin()) return forbidden()
                val g = s.groups[rest[1]] ?: return errorReply(404, "group_not_found")
                if (g["kind"] == "workspace_member") return errorReply(409, "protected_group")
                @Suppress("UNCHECKED_CAST") (g["member_actor_ids"] as MutableList<String>).remove(rest[3])
                200 to mapOf("group" to groupRow(s, g))
            }
            head == "groups" && n >= 3 && rest[2] == "child-groups" -> {
                if (!isAdmin()) return forbidden()
                val parent = s.groups[rest[1]] ?: return errorReply(404, "group_not_found")
                @Suppress("UNCHECKED_CAST") val children = parent["child_group_ids"] as MutableList<String>
                if (method == "POST") {
                    val child = body["child_group_id"].asString() ?: return validationError("child_group_id is required")
                    if (s.groups[child] == null) return errorReply(404, "group_not_found")
                    if (child == rest[1] || reaches(s, child, rest[1])) return errorReply(409, "group_cycle")
                    if (child !in children) children += child
                } else children.remove(rest.getOrNull(3))
                200 to mapOf("group" to groupRow(s, parent))
            }
            // Channel grants
            head == "channels" && n >= 3 && rest[2] == "grants" -> channelGrantRoute(method, s, rest, body)
            // Scope access
            head == "scopes" && n == 3 && rest[2] == "grants" -> scopeGrantRoute(method, s, rest[1], body)
            // Delegations
            head == "wapp-delegations" && method == "GET" -> if (isAdmin() || viewer == SampleViewer.DELEGATE) indexList("delegations", s.delegations.map { SampleJson.row(it)!! }) else forbidden()
            head == "wapp-delegations" && method == "POST" && n == 1 -> {
                if (!isAdmin()) return forbidden()
                val expires = body["expires_at"].asString()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return validationError("expires_at is required")
                if (!expires.isAfter(Instant.now())) return validationError("expires_at must be in the future")
                val row = linkedMapOf<String, Any?>("id" to newUUID(), "delegate_actor_id" to body["delegate_actor_id"], "expires_at" to body["expires_at"], "revoked_at" to null, "filters" to body["filters"])
                s.delegations += row
                201 to mapOf("delegation" to row)
            }
            head == "wapp-delegations" && method == "POST" && n == 3 && rest[2] == "revoke" -> {
                if (!isAdmin()) return forbidden()
                val row = s.delegations.firstOrNull { it["id"] == rest[1] } ?: return errorReply(404, "delegation_not_found")
                row["revoked_at"] = Instant.now().toString()
                200 to mapOf("delegation" to row)
            }
            // Installations
            head == "wapp-installations" && method == "GET" ->
                if (isAdmin() || viewer == SampleViewer.DELEGATE) indexList("installations", s.installations.values.map { SampleJson.row(it)!! }) else forbidden()
            head == "wapp-installations" && method == "POST" && n == 3 -> {
                val row = s.installations[rest[1]] ?: return errorReply(404, "installation_not_found")
                if (!isAdmin() && !delegatesInstallation(rest[1])) return forbidden()
                if (rest[2] == "revoke") row["status"] = "revoked" else if (rest[2] == "reconcile") row["reconciled_at"] = Instant.now().toString() else return null
                200 to mapOf("installation" to row)
            }
            // Install intents (the activation's Tower half)
            head == "wapp-install-intents" && method == "GET" -> if (isAdmin()) indexList("intents", s.intents.values.map { SampleJson.row(it)!! }) else forbidden()
            head == "wapp-install-intents" && method == "POST" -> {
                val delegation = body["delegation_id"].asString()
                val delegated = delegation != null && viewer == SampleViewer.DELEGATE && s.delegations.any { it["id"] == delegation && it["revoked_at"] == null }
                if (!isAdmin() && !delegated) return forbidden()
                val request = body["client_request_id"].asString() ?: return validationError("client_request_id is required")
                val existing = s.intents.values.firstOrNull { it["client_request_id"] == request }
                if (existing != null) return 200 to mapOf("intent" to existing, "idempotent_replay" to true)
                val id = newUUID()
                val row = linkedMapOf<String, Any?>("id" to id, "client_request_id" to request, "status" to "pending", "app_id" to body["app_id"], "wapp_installation_id" to body["wapp_installation_id"], "destinations" to body["destinations"], "title" to body["title"])
                s.intents[id] = row
                201 to mapOf("intent" to row)
            }
            // Publishing
            head == "wapp-publishing-grants" && method == "GET" -> if (isAdmin()) indexList("grants", s.publishing.values.map { SampleJson.row(it)!! }) else forbidden()
            head == "wapp-publishing-grants" && method == "PUT" && n == 2 -> {
                if (!isAdmin()) return forbidden()
                val row = SampleJson.row(body)!!
                row["wapp_installation_id"] = rest[1]; row["publishing_status"] = s.publishing[rest[1]]?.get("publishing_status") ?: "active"
                s.publishing[rest[1]] = row
                200 to mapOf("grant" to row)
            }
            head == "wapp-publishing-grants" && method == "POST" && n == 3 -> {
                if (!isAdmin()) return forbidden()
                val row = s.publishing[rest[1]] ?: return errorReply(404, "publishing_grant_not_found")
                when (rest[2]) {
                    "disable" -> row["publishing_status"] = "disabled"
                    "revoke" -> row["publishing_status"] = "revoked"
                    "rotate" -> {
                        if (row["publisher_npub"] != body["current_publisher_npub"]) return errorReply(409, "publisher_changed")
                        if (body["nonce"].asString().isNullOrEmpty() || body["expires_at"].asString().isNullOrEmpty()) return validationError("nonce and expires_at are required")
                        row["publisher_npub"] = body["new_publisher_npub"]
                    }
                    else -> return null
                }
                200 to mapOf("grant" to row)
            }
            else -> null
        }
    }

    private fun reaches(s: Ws, from: String, target: String): Boolean {
        val seen = HashSet<String>(); val stack = ArrayDeque(listOf(from))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id == target) return true
            if (seen.add(id)) stack.addAll(s.groups[id]?.get("child_group_ids").asStrings() ?: emptyList())
        }
        return false
    }

    private fun indexList(key: String, rows: List<Any?>): Reply = 200 to linkedMapOf(key to rows, "next_cursor" to null)

    private fun channelGrantRoute(method: String, s: Ws, rest: List<String>, body: Row): Reply {
        val channel = rest[1]
        val rows = s.channelGrants.getOrPut(channel) { mutableListOf() }
        if (method == "GET") {
            // Readable to those who manage the channel's grants (or everyone for the sample's own channel list).
            return 200 to linkedMapOf("grants" to rows.map { SampleJson.row(it)!! }.plus(managerRow(channel)), "next_cursor" to null)
        }
        if (!managesChannel(channel)) return forbidden()
        return when {
            method == "POST" && rest.size == 3 -> {
                val type = body["principal_type"].asString(); val id = body["principal_id"].asString()
                if (type == null || id == null || type !in setOf("actor", "group")) return validationError("principal is required")
                if (body["access_level"].asString() !in setOf("view", "contribute", "manage")) return validationError("access_level is invalid")
                if (rows.any { it["principal_type"] == type && it["principal_id"] == id }) return errorReply(409, "grant_exists")
                rows += grant(type, id, nameOf(s, type, id), body["access_level"] as String, permissionsFor(body["access_level"] as String))
                201 to mapOf("grant" to rows.last())
            }
            method == "PUT" && rest.size == 5 -> {
                val row = rows.firstOrNull { it["principal_type"] == rest[3] && it["principal_id"] == rest[4] } ?: return errorReply(404, "grant_not_found")
                row["access_level"] = body["access_level"]; row["permissions"] = ArrayList(permissionsFor(body["access_level"].asString() ?: "view"))
                200 to mapOf("grant" to row)
            }
            method == "DELETE" && rest.size == 5 -> {
                rows.removeAll { it["principal_type"] == rest[3] && it["principal_id"] == rest[4] }
                200 to mapOf("revoked" to true)
            }
            else -> errorReply(404, "not_found")
        }
    }

    /** The viewer's own direct manager grant on the design channel (what makes them an exact grant manager). */
    private fun managerRow(channel: String): List<Row> =
        if (viewer == SampleViewer.CHANNEL_MANAGER && channel == SampleIds.DESIGN) listOf(grant("actor", viewerActor, "You", "manage", listOf("channel.read", "channel.write", "channel.manage", "channel.grants.manage"))) else emptyList()

    private fun permissionsFor(level: String) = when (level) {
        "manage" -> listOf("channel.read", "channel.write", "channel.manage", "channel.grants.manage")
        "contribute" -> listOf("channel.read", "channel.write")
        else -> listOf("channel.read")
    }

    private fun nameOf(s: Ws, type: String, id: String): String =
        if (type == "group") (s.groups[id]?.get("name") as? String ?: "Group") else (s.members.firstOrNull { it["actor_id"] == id }?.get("display_name") as? String ?: "Member")

    private fun scopeGrantRoute(method: String, s: Ws, scope: String, body: Row): Reply {
        val rows = s.scopeGrants.getOrPut(scope) { mutableListOf() }
        val revision = s.scopeRevision.getOrPut(scope) { 1 }
        fun view(): Row = linkedMapOf(
            "revision" to (s.scopeRevision[scope] ?: 1L), "can_manage" to managesScope(scope), "grants" to rows.map { SampleJson.row(it)!! },
            "principals" to linkedMapOf(
                "actors" to listOf(person(actor(SampleIds.ANA), SampleIds.ANA, SampleContent.ANA_LABEL, "human"), person(actor(SampleIds.ORBIT), SampleIds.ORBIT, SampleContent.ORBIT_LABEL, "agent")),
                "groups" to s.groups.values.map { mapOf("group_id" to it["group_id"], "name" to it["name"]) },
            ),
        )
        if (method == "GET") return 200 to view()
        if (!managesScope(scope)) return forbidden()
        val token = body["lease_token"].asString()
        if (token == null || s.leases.values.none { it.second == token && it.first == scope }) return errorReply(409, "lease_invalid")
        val expected = (body["expected_revision"] as? Number)?.toLong()
        if (expected != revision) return errorReply(409, "revision_conflict", mapOf("details" to mapOf("revision" to revision)))
        val type = body["principal_type"].asString(); val id = body["principal_id"].asString()
        if (type == null || id == null) return validationError("principal is required")
        rows.removeAll { it["principal_type"] == type && it["principal_id"] == id }
        if (body["revoke"] != true) rows += scopeGrant(type, id, nameOf(s, type, id), body["role"].asString() ?: "context_editor")
        s.scopeRevision[scope] = revision + 1
        return 200 to view()
    }

    /**
     * Scope edit leases (`entity_type: scope`) for the scope access editor. Called before the work stand-in, which owns document
     * leases and would otherwise answer the acquire with a lease this tower cannot recognise. Null: not a scope lease.
     */
    fun scopeLease(request: HttpRequest, path: String): Reply? {
        val prefix = "/api/v4/flightdeck-pg/workspaces/"
        if (request.method.uppercase() != "POST" || !path.startsWith(prefix)) return null
        val parts = path.removePrefix(prefix).split('/').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts[1] != "edit-leases") return null
        return synchronized(lock) { leaseRoute(request, parts[0], parts.drop(1)) }
    }

    private fun leaseRoute(request: HttpRequest, w: String, rest: List<String>): Reply? {
        val body = SampleJson.objectOf(request.body)
        val s = ws(w)
        if (rest[1] == "acquire") {
            if (body["entity_type"] != "scope") return null
            record(request, "/edit-leases/acquire", body)
            if (scopeLeaseHeld) return errorReply(409, "edit_lease_held", mapOf("details" to mapOf("holder_actor_npub" to SampleIds.ANA, "expires_at" to "later")))
            val scope = body["entity_id"].asString() ?: return validationError("entity_id is required")
            val id = newUUID(); val token = newUUID()
            s.leases[id] = scope to token
            return 201 to linkedMapOf("lease" to linkedMapOf("id" to id, "entity_type" to "scope", "entity_id" to scope, "lease_token" to token, "holder_actor_npub" to SampleIds.ANA, "expires_at" to "2099-01-01T00:00:00.000Z"))
        }
        val lease = s.leases[rest[1]] ?: return null
        record(request, "/edit-leases/${rest[1]}/${rest[2]}", body)
        if (rest[2] == "release") { s.leases.remove(rest[1]); return 200 to mapOf("released" to true) }
        return null
    }

    // MARK: Synthetic Autopilot (the activation's second half), on its own host

    /** Autopilot's catalog and process routes. Requests must carry a valid NIP-98 event like Tower's. */
    fun handleAutopilot(request: HttpRequest, path: String): Reply {
        when (val verified = auth.verify(request)) {
            is SampleAuth.Result.Invalid -> return SampleAuth.unauthorized(verified.reason)
            is SampleAuth.Result.Valid -> {}
        }
        val method = request.method.uppercase()
        record(request, "autopilot:$path", SampleJson.objectOf(request.body))
        if (autopilotRedirect) return 302 to mapOf("location" to "https://elsewhere.sample.invalid$path")
        return when {
            method == "GET" && path == "/api/wapps/activation-catalog" -> 200 to mapOf(
                "apps" to listOfNotNull(
                    linkedMapOf(
                        "app_id" to "sample.feed", "app_version" to "1.0.0", "title" to "Sample Feed", "launch_url" to "https://app.sample.invalid/launch",
                        "autopilot_origin" to SampleHosts.AUTOPILOT, "autopilot_npub" to SampleIds.APP, "view_origin" to "https://view.sample.invalid",
                    ),
                    if (catalogForeignEntry) linkedMapOf(
                        "app_id" to "sample.foreign", "app_version" to "1.0.0", "title" to "Foreign App", "launch_url" to "https://app.sample.invalid/foreign",
                        "autopilot_origin" to "https://other-autopilot.sample.invalid", "autopilot_npub" to SampleIds.APP, "view_origin" to "https://view.sample.invalid",
                    ) else null,
                ),
            )
            method == "POST" && path == "/api/wapps/install-intents/process" -> synchronized(lock) {
                val body = SampleJson.objectOf(request.body)
                val s = ws(body["workspace_id"].asString() ?: return@synchronized validationError("workspace_id is required"))
                val intent = s.intents[body["intent_id"].asString()] ?: return@synchronized errorReply(404, "intent_not_found")
                if (failNextProcess) { failNextProcess = false; return@synchronized errorReply(502, "bad_gateway") }
                if (intent["status"] != "processed") {
                    intent["status"] = "processed"
                    val installation = intent["wapp_installation_id"] as String
                    s.installations.putIfAbsent(installation, installation(installation, intent["app_id"].toString(), intent["title"].toString(), "active"))
                }
                200 to mapOf("intent" to intent)
            }
            else -> errorReply(404, "not_found")
        }
    }

    companion object {
        const val GROUP_DESIGNERS = "5a000000-0000-4000-8000-0000000000b1"
        const val GROUP_REVIEWERS = "5a000000-0000-4000-8000-0000000000b2"
        const val GROUP_EVERYONE = "5a000000-0000-4000-8000-0000000000b3"
        const val INSTALLATION_FEED = "5a000000-0000-4000-8000-0000000000d1"
        const val INSTALLATION_OTHER = "5a000000-0000-4000-8000-0000000000d2"
    }
}
