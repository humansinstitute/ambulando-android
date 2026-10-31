package studio.otherstuff.ambulando.core.workspaces

import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.ConnectionRecord
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.WorkspaceRecord

/**
 * Local resource identity. Server ids are meaningful only inside this complete boundary: the normalised Tower origin,
 * the signing identity, the application namespace and the workspace id. Two Towers (or two identities) may serve the same
 * workspace id; nothing local (store file, scheduler key, cache directory, draft) is ever keyed by the id alone.
 */
data class WorkspaceBoundary(val origin: String, val signerNpub: String, val appNpub: String, val workspaceId: String) {
    /** Stable, versioned, length-delimited digest: safe as a file name and as a scheduler namespace. */
    val id: String get() = digest(listOf(origin, signerNpub, appNpub, workspaceId))

    /** The boundary of the whole connection (origin + signer), shared by all of its workspaces. */
    val connectionNamespace: String get() = digest(listOf(origin, signerNpub))

    /** Refuses work that names another connection, workspace or application than this boundary's own. */
    fun requireOwns(connection: ConnectionRecord, workspace: WorkspaceRecord) {
        if (of(connection, workspace) != this) throw BoundaryViolation("workspace")
    }

    companion object {
        fun of(connection: ConnectionRecord, workspace: WorkspaceRecord): WorkspaceBoundary =
            WorkspaceBoundary(TowerOrigin(connection.origin).url, connection.signerNpub, workspace.appNpub, workspace.id)

        fun connectionNamespace(connection: ConnectionRecord): String =
            digest(listOf(TowerOrigin(connection.origin).url, connection.signerNpub))

        /** Length-delimited fields avoid delimiter collisions; the namespace version makes future changes explicit. */
        fun digest(fields: List<String>): String =
            Sha256Hex.of("ambulando.boundary.v1|" + fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
    }
}

/** A route from shared navigation (a pinned or unread row) always carries its exact boundary. */
data class WorkspaceNavigationTarget(val boundary: WorkspaceBoundary, val scopeId: String? = null, val channelId: String? = null)

/** Saved workspace colours. Names always accompany colour; it never decides routing. */
enum class WorkspaceColour(val rgb: Long) {
    BLUE(0x174EA6), GREEN(0x146C43), PURPLE(0x63339A), RED(0xA52834), SLATE(0x394956);

    companion object {
        fun parse(value: String?): WorkspaceColour = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BLUE
    }
}

/**
 * Durable local workspace preferences. An empty enabled list is intentional (the chooser is shown), not an instruction to
 * pick a default. Collapse and reorder affect presentation only.
 */
data class WorkspacePreferences(
    val enabled: List<String> = emptyList(),
    val order: List<String> = emptyList(),
    val collapsed: Set<String> = emptySet(),
    val colours: Map<String, WorkspaceColour> = emptyMap(),
) {
    fun withEnabled(id: String, value: Boolean): WorkspacePreferences {
        val rest = enabled.filter { it != id }
        return copy(enabled = if (value) rest + id else rest, order = if (id in order) order else order + id)
    }

    fun withCollapsed(id: String, value: Boolean) = copy(collapsed = if (value) collapsed + id else collapsed - id)

    fun withColour(id: String, colour: WorkspaceColour) = copy(colours = colours + (id to colour))

    fun withOrder(ids: List<String>) = copy(order = ids.distinct())

    fun sorted(ids: List<String>): List<String> {
        val rank = order.withIndex().associate { it.value to it.index }
        return ids.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it }))
    }

    fun colour(id: String): WorkspaceColour = colours[id] ?: WorkspaceColour.BLUE

    fun encode(): String = kotlinx.serialization.json.buildJsonObject {
        put("enabled", kotlinx.serialization.json.JsonArray(enabled.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("order", kotlinx.serialization.json.JsonArray(order.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("collapsed", kotlinx.serialization.json.JsonArray(collapsed.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("colours", kotlinx.serialization.json.buildJsonObject { colours.toSortedMap().forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v.name)) } })
    }.toString()

    companion object {
        const val STATE_KEY = "workspaces.preferences.v1"

        /** Damaged or unknown state restores an empty preference set rather than guessing. */
        fun decode(text: String?): WorkspacePreferences? {
            if (text == null) return null
            return runCatching {
                val o = studio.otherstuff.ambulando.core.transport.TowerJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                fun strings(key: String) = (o[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
                WorkspacePreferences(
                    enabled = strings("enabled").distinct(), order = strings("order").distinct(), collapsed = strings("collapsed").toSet(),
                    colours = (o["colours"] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (k, v) ->
                        (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.let { k to WorkspaceColour.parse(it.content) }
                    }?.toMap() ?: emptyMap(),
                )
            }.getOrNull()
        }
    }
}
