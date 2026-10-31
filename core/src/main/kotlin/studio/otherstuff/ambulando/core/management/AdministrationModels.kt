package studio.otherstuff.ambulando.core.management

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.transport.TowerJson
import studio.otherstuff.ambulando.core.transport.flexibleLong
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.sortedJson
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.stringOrNull
import studio.otherstuff.ambulando.core.transport.strings

/**
 * One administration row (a member, group, grant, delegation, installation or publishing grant) as the screens list it.
 * A persisted projection and never authority: `raw` keeps Tower's fields (merged from the nested shapes Tower uses for a
 * person, a principal or an installation) so new server fields survive, and every decision that matters is taken from a fresh
 * read just before a write.
 */
data class AdministrationRow(
    val id: String,
    val title: String,
    val detail: String,
    val fields: Map<String, String>,
    val children: List<AdministrationRow>,
    val raw: JsonObject,
    val childGroupIds: List<String>,
    val filters: Map<String, List<String>>,
) {
    /** The permission names a channel grant row lists, sorted (empty when the row names none). */
    val channelPermissions: List<String>
        get() = (raw.strings("permissions") ?: listOfNotNull(raw.string("permission"))).filter { it.isNotEmpty() }.sorted()

    fun toJson(): JsonObject = raw

    companion object {
        fun parse(source: JsonObject): AdministrationRow {
            var o: Map<String, JsonElement> = source
            source.obj("principal")?.let { principal ->
                // Display metadata belongs to the recipient; grant identity and resource stay top-level.
                val m = LinkedHashMap(o)
                for (key in listOf("display_name", "name", "npub", "kind")) if (m[key] == null) principal[key]?.let { m[key] = it }
                if (m["principal_id"] == null) (principal["actor_id"] ?: principal["id"])?.let { m["principal_id"] = it }
                o = m
            }
            source.obj("actor")?.let { actor -> o = source + actor }
            source.obj("membership")?.get("role")?.let { role -> o = o + ("role" to role) }
            source.obj("grant")?.let { grant -> o = (source.obj("installation") ?: JsonObject(emptyMap())) + grant }
            // Whichever shape supplied it, a person recipient is an actor in every request.
            if (o["principal_type"].stringOrNull() == "person") o = o + ("principal_type" to JsonPrimitive("actor"))
            val raw = JsonObject(o)
            val fields = raw.entries.mapNotNull { (k, v) -> v.stringOrNull()?.let { k to it } }.toMap()
            val id = fields["wapp_installation_id"] ?: fields["actor_id"] ?: fields["group_id"] ?: fields["principal_id"] ?: fields["id"] ?: fields["npub"]
                ?: ("unavailable:" + Sha256Hex.of(sortedJson(raw).toString()))
            val title = fields["display_name"] ?: fields["label"] ?: fields["name"] ?: fields["title"] ?: fields["npub"] ?: fields["delegate_actor_id"] ?: id
            val detail = listOfNotNull(
                fields["role"], fields["kind"], fields["status"], fields["lifecycle_status"], fields["publishing_status"],
                fields["access_level"], fields["expires_at"], fields["revoked_at"]?.let { "Revoked: $it" },
            ).joinToString(" · ")
            val filters = LinkedHashMap<String, List<String>>()
            raw.obj("filters")?.forEach { (k, v) -> (v as? JsonArray)?.let { a -> if (a.all { it.stringOrNull() != null }) filters[k] = a.mapNotNull { it.stringOrNull() } } }
            return AdministrationRow(
                id = id, title = title, detail = detail, fields = fields,
                children = (raw.objects("members") ?: emptyList()).map(::parse), raw = raw,
                childGroupIds = raw.strings("child_group_ids") ?: emptyList(), filters = filters,
            )
        }

        fun encode(rows: List<AdministrationRow>): String = JsonArray(rows.map { it.raw }).toString()

        fun decode(text: String?): List<AdministrationRow> {
            if (text == null) return emptyList()
            val array = TowerJson.parseToJsonElement(text) as? JsonArray ?: return emptyList()
            return array.mapNotNull { (it as? JsonObject)?.let(::parse) }
        }
    }
}

/** The administration lists Tower serves; each is persisted per connection and workspace and refreshed on its own. */
enum class AdministrationList(val route: String, val listKey: String) {
    MEMBERS("/members", "members"),
    GROUPS("/groups", "groups"),
    DELEGATIONS("/wapp-delegations", "delegations"),
    INSTALLATIONS("/wapp-installations", "installations"),
    INTENTS("/wapp-install-intents", "intents"),
    PUBLISHING("/wapp-publishing-grants", "grants");

    val storageName: String get() = name.lowercase()
}

/** Everything the administration screens persist is keyed by connection and workspace inside the workspace's own partition. */
object AdministrationKeys {
    fun list(connectionId: String, workspaceId: String, list: AdministrationList) = "admin.v1:$connectionId:$workspaceId:${list.storageName}"
    fun channelGrants(connectionId: String, workspaceId: String, channelId: String) = "admin.channel-grants.v1:$connectionId:$workspaceId:$channelId"
    fun scopeAccess(connectionId: String, workspaceId: String, scopeId: String) = "admin.scope-access.v1:$connectionId:$workspaceId:$scopeId"
    fun draft(connectionId: String, workspaceId: String, kind: String) = "admin.draft.v1:$connectionId:$workspaceId:$kind"
    fun confirmedDraft(connectionId: String, workspaceId: String, kind: String) = draft(connectionId, workspaceId, kind) + ":confirmed"
    fun activationIntent(connectionId: String, workspaceId: String) = "admin.activation.v1:$connectionId:$workspaceId"
}

data class AdministrationDestination(val scope: String, val channel: String)

/** Tower's answer to `GET /scopes/:s/grants`: the scope's role grants, its revision and who may be granted a role. */
data class ScopeAdministrationAccess(
    val revision: Long,
    val canManage: Boolean,
    val grants: List<AdministrationRow>,
    val actors: List<AdministrationRow>,
    val groups: List<AdministrationRow>,
    val raw: JsonObject,
) {
    fun encode(): String = raw.toString()

    companion object {
        fun parse(o: JsonObject): ScopeAdministrationAccess {
            val revision = flexibleLong(o["revision"]) ?: throw ManagementError.Unexpected("Tower did not return the scope access revision.")
            val principals = o.obj("principals")
            return ScopeAdministrationAccess(
                revision = revision, canManage = (o["can_manage"] as? JsonPrimitive)?.content == "true",
                grants = (o.objects("grants") ?: emptyList()).map(AdministrationRow::parse),
                actors = (principals?.objects("actors") ?: emptyList()).map(AdministrationRow::parse),
                groups = (principals?.objects("groups") ?: emptyList()).map(AdministrationRow::parse), raw = o,
            )
        }

        fun decode(text: String?): ScopeAdministrationAccess? = runCatching { parse(TowerJson.parseToJsonElement(text ?: return null) as JsonObject) }.getOrNull()
    }
}

/** An app the user-configured Autopilot catalog offers for activation. */
data class ActivationApp(
    val id: String, val version: String, val title: String, val launchUrl: String, val origin: String, val autopilotNpub: String, val viewOrigin: String,
)

/** Local directory operations. Nothing here is sent to Tower or to a public profile service. */
object GroupDirectory {
    fun search(rows: List<AdministrationRow>, query: String): List<AdministrationRow> {
        val term = query.trim()
        return rows.filter { row ->
            term.isEmpty() || listOf(row.title, row.detail, row.id, row.fields["npub"] ?: "").any { it.contains(term, ignoreCase = true) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun contains(group: AdministrationRow, npub: String): Boolean = group.children.any { it.fields["npub"] == npub }

    /**
     * A stale graph is only a hint: Tower still checks the edge under its own authority. Returns why an attachment cannot
     * be offered, or null.
     */
    fun attachmentState(parent: String, child: String, groups: List<AdministrationRow>): String? {
        if (parent == child) return "This group"
        if (groups.firstOrNull { it.id == parent }?.childGroupIds?.contains(child) == true) return "Already nested"
        val visited = HashSet<String>()
        val pending = ArrayDeque(listOf(child))
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            if (id == parent) return "Would create a cycle"
            if (visited.add(id)) pending.addAll(groups.firstOrNull { it.id == id }?.childGroupIds ?: emptyList())
        }
        return null
    }

    /**
     * Every npub that is a member of [groupId] directly or through nested groups (members of a child group belong to its
     * parent), including the member lists Tower itself reports as effective. Cycles are tolerated.
     */
    fun effectiveMemberNpubs(groupId: String, groups: List<AdministrationRow>): Set<String> {
        val byId = groups.associateBy { it.id }
        val seen = HashSet<String>()
        val result = HashSet<String>()
        val pending = ArrayDeque(listOf(groupId))
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            if (!seen.add(id)) continue
            val group = byId[id] ?: continue
            result += group.children.mapNotNull { it.fields["npub"] }
            result += group.raw.strings("member_npubs") ?: emptyList()
            result += group.raw.strings("effective_member_npubs") ?: emptyList()
            pending.addAll(group.childGroupIds)
        }
        return result
    }
}
