package studio.otherstuff.ambulando.core.deck

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import studio.otherstuff.ambulando.core.browser.BrowserURLPolicy
import studio.otherstuff.ambulando.core.json.JsonFields
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.nostr.WireJson
import java.net.URI

/** The web's My Agents launchers, read from the existing actor-owned Tower settings route (`/me/autopilot-agents`). */
@Serializable
data class PersonalAgentLauncher(val npub: String, val address: String) {
    val id: String get() = npub

    /** The launcher address, only when the in-app browser would load it as an HTTPS page. */
    val safeURL: URI?
        get() {
            val url = runCatching { URI(address) }.getOrNull() ?: return null
            if (BrowserURLPolicy.decide(url, mainFrame = true) != BrowserURLPolicy.Decision.Load || url.scheme != "https") return null
            return url
        }

    companion object {
        /** App-state key of the stored launcher list for a connection and workspace. */
        fun stateKey(connectionId: String, workspaceId: String): String = "personal-agents:$connectionId:$workspaceId"

        /** Tower path of the launcher settings for a workspace (the id must be a UUID). */
        fun path(workspaceId: String): String? =
            JsonFields.lowerUuid(workspaceId)?.let { "/api/v4/flightdeck-pg/workspaces/$it/me/autopilot-agents" }

        /** Rows of `settings.autopilot_agents` with a valid npub, first occurrence of each npub kept. */
        fun decode(bytes: ByteArray): List<PersonalAgentLauncher> {
            val o = JsonFields.parseObject(bytes) ?: throw IllegalArgumentException("Tower returned an unexpected personal agents response.")
            val rows = ((o["settings"] as? JsonObject)?.get("autopilot_agents") as? JsonArray)
                ?.takeIf { list -> list.all { it is JsonObject } } ?: throw IllegalArgumentException("Tower returned an unexpected personal agents response.")
            val seen = HashSet<String>()
            return rows.mapNotNull { row ->
                row as JsonObject
                val npub = JsonFields.string(row["agent_npub"]) ?: return@mapNotNull null
                if (!NostrKeys.isValidNpub(npub) || !seen.add(npub)) return@mapNotNull null
                PersonalAgentLauncher(npub, JsonFields.string(row["url"]) ?: "")
            }
        }

        /** The stored list (`[{"npub", "address"}]`). */
        fun encodeList(rows: List<PersonalAgentLauncher>): String = WireJson.encodeToString(ListSerializer(serializer()), rows)

        fun decodeList(text: String?): List<PersonalAgentLauncher> =
            text?.let { runCatching { WireJson.decodeFromString(ListSerializer(serializer()), it) }.getOrNull() } ?: emptyList()
    }
}
