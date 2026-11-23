package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.profiles.PeopleDirectory
import studio.otherstuff.ambulando.core.profiles.PersonDisplay
import studio.otherstuff.ambulando.core.profiles.PersonNamePolicy

/** Per-editor presentation state. Never enters a board scene, draft, comment, or version. */
class BoardAuthorNames {
    private var key = ""
    private var identities = emptyList<String>()

    fun sent(json: String) {
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return
        when ((message["type"] as? JsonPrimitive)?.content) {
            "load" -> {
                val next = (message["key"] as? JsonPrimitive)?.content ?: return
                if (next != key) { key = next; identities = emptyList() }
            }
            "clear" -> { key = ""; identities = emptyList() }
        }
    }

    /** Reject stale view keys, malformed identities and oversized requests. No network lookup. */
    fun request(json: String): Boolean {
        if (json.length > 160_000) return false
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return false
        if ((message["type"] as? JsonPrimitive)?.content != "author-identities" || key.isEmpty() ||
            (message["key"] as? JsonPrimitive)?.content != key) return false
        val requested = message["identities"] as? JsonArray ?: return false
        if (requested.size > 2000) return false
        identities = requested.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter(NostrKeys::isValidNpub).distinct()
        return true
    }

    fun message(people: PeopleDirectory?): String? {
        if (key.isEmpty()) return null
        return buildJsonObject {
            put("type", "author-names"); put("key", key)
            put("names", buildJsonObject {
                for (npub in identities) {
                    val person = people?.person(npub) ?: continue
                    if (person.nameSource == PersonDisplay.NameSource.NPUB) continue
                    val name = PersonNamePolicy.usable(person.name) ?: continue
                    if (name.length <= 256) put(npub, name)
                }
            })
        }.toString()
    }
}
