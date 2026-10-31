package studio.otherstuff.ambulando.core.management

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord
import studio.otherstuff.ambulando.core.transport.objects
import studio.otherstuff.ambulando.core.transport.obj
import studio.otherstuff.ambulando.core.transport.parseJsonObject
import studio.otherstuff.ambulando.core.transport.string
import studio.otherstuff.ambulando.core.transport.stringOrNull

/** A person, agent or group member as the access screens list them. Names fall back to the npub, never to a guess. */
data class AccessPerson(val actorId: String?, val npub: String?, val name: String, val kind: String?)

/** A workspace group: its members and the groups nested in it. Read only; changes are made in the web app. */
data class AccessGroup(val id: String, val name: String, val kind: String?, val members: List<AccessPerson>, val childGroupIds: List<String>)

/** One grant on a channel: who holds it and what it allows. */
data class AccessGrant(val principalType: String?, val principalId: String?, val name: String, val permissions: List<String>)

/**
 * Read-only views of who can see what: workspace groups (`GET /groups`, which Tower limits to administrators) and the
 * grants on one channel (`GET /channels/:c/grants`). Rows are parsed tolerantly (the shapes Tower returns for a person
 * differ between routes) and nothing here changes access. Members come from the synced directory.
 */
object AccessDirectory {
    suspend fun groups(client: TowerClient, workspace: WorkspaceRecord): List<AccessGroup> =
        client.list(TowerAPI.base(workspace.id) + "/groups", workspace.appNpub, "groups")
            .also { rows -> rows.forEach { row -> row.string("workspace_id")?.let { if (it.lowercase() != workspace.id) throw TowerError.Malformed("cross-workspace group") } } }
            .mapNotNull(::group)

    suspend fun channelGrants(client: TowerClient, workspace: WorkspaceRecord, channelId: String): List<AccessGrant> {
        val data = client.request(path = TowerAPI.base(workspace.id) + "/channels/" + TowerAPI.uuid(channelId) + "/grants", appNpub = workspace.appNpub)
        val rows = parseJsonObject(data, "channel grants").objects("grants") ?: throw TowerError.Malformed("channel grants")
        return rows.map(::grant)
    }

    fun person(source: JsonObject): AccessPerson {
        // A person may arrive flat, under `actor`, or under `principal`; the first value found wins, outermost first.
        val layers = listOfNotNull(source, source.obj("actor"), source.obj("principal"))
        fun field(key: String) = layers.firstNotNullOfOrNull { it.string(key)?.takeIf(String::isNotBlank) }
        val npub = field("npub")
        return AccessPerson(field("actor_id") ?: field("principal_id") ?: field("id"), npub, field("display_name") ?: field("name") ?: npub ?: "Unknown", field("kind"))
    }

    fun group(source: JsonObject): AccessGroup? {
        val id = (source.string("group_id") ?: source.string("id"))?.takeIf { it.isNotBlank() } ?: return null
        val members = (source.objects("members") ?: emptyList()).map(::person)
        val children = (source["child_group_ids"] as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()
        return AccessGroup(id.lowercase(), source.string("name")?.takeIf { it.isNotBlank() } ?: "Untitled group", source.string("kind") ?: source.string("group_kind"), members, children)
    }

    fun grant(source: JsonObject): AccessGrant {
        val who = person(source)
        val type = (source.string("principal_type") ?: source.obj("principal")?.string("type"))?.let { if (it == "person") "actor" else it }
        val permissions = ((source["permissions"] as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: listOfNotNull(source.string("permission"))).filter { it.isNotBlank() }.sorted()
        return AccessGrant(type, who.actorId ?: source.string("principal_id"), who.name, permissions)
    }
}
